package cloud.cleo.squareup.memory;

import static cloud.cleo.squareup.cloudfunctions.LexFunction.sanitizeAssistantText;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;

import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.*;
import software.amazon.awssdk.enhanced.dynamodb.Key;

/**
 * Spring AI ChatMemoryRepository backed by DynamoDB Enhanced Client.
 *
 * Table schema (Dynamo): PK: conversationId (String) SK: messageIndex (Number, 0..N-1) ttl: epoch seconds for TTL
 * (per-message)
 */
public class DynamoDbChatMemoryRepository implements ChatMemoryRepository {

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbTable<DynamoChatMemoryItem> table;
    private final Duration ttlDuration;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DynamoDbChatMemoryRepository(DynamoDbEnhancedClient enhancedClient,
            String tableName,
            Duration ttlDuration,
            Clock clock) {

        this.enhancedClient = enhancedClient;
        this.table = enhancedClient.table(
                tableName,
                TableSchema.fromBean(DynamoChatMemoryItem.class));
        this.ttlDuration = ttlDuration;
        this.clock = (clock != null) ? clock : Clock.systemUTC();
    }

    // -------------------------------------------------------------------------
    // ChatMemoryRepository
    // -------------------------------------------------------------------------
    @Override
    public List<String> findConversationIds() {
        PageIterable<DynamoChatMemoryItem> pages
                = table.scan(ScanEnhancedRequest.builder().build());

        return pages.items()
                .stream()
                .map(DynamoChatMemoryItem::getConversationId)
                .distinct()
                .toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(conversationId).build());

        // Use SK ordering from Dynamo instead of client-side sort
        return table.query(r -> r
                .queryConditional(condition)
                .scanIndexForward(true)) // ascending messageIndex
                .items()
                .stream()
                .map(this::toMessage)
                .toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // Nothing to do if Spring AI passes no messages.
        if (messages == null || messages.isEmpty()) {
            return;
        }

        long ttlEpochSeconds = clock.instant()
                .plus(ttlDuration)
                .getEpochSecond();

        // 1) Fast path: try to use ONLY the last stored item
        Optional<DynamoChatMemoryItem> lastItemOpt = findLastItem(conversationId);

        if (lastItemOpt.isEmpty()) {
            // New conversation – just write everything starting at index 0
            List<DynamoChatMemoryItem> allItems = new ArrayList<>(messages.size());
            long idx = 0;
            for (Message m : messages) {
                allItems.add(buildItem(conversationId, (int) idx++, m, ttlEpochSeconds));
            }
            batchPutItems(allItems);
            return;
        }

        DynamoChatMemoryItem lastItem = lastItemOpt.get();
        long lastIndex = lastItem.getMessageIndex();
        Message lastPersistedMessage = toMessage(lastItem);

        // 2) Walk incoming messages backwards to find where we left off
        int pivot = findLastPersistedPosition(messages, lastPersistedMessage);

        if (pivot >= 0) {
            // We found the last persisted message inside the incoming list.
            // Everything AFTER pivot is new.
            int totalMessages = messages.size();
            if (pivot + 1 >= totalMessages) {
                // No new messages to write
                return;
            }

            List<DynamoChatMemoryItem> newItems
                    = new ArrayList<>(totalMessages - (pivot + 1));

            long nextIndex = lastIndex;
            for (int i = pivot + 1; i < totalMessages; i++) {
                Message msg = messages.get(i);
                nextIndex++;
                newItems.add(buildItem(conversationId, (int) nextIndex, msg, ttlEpochSeconds));
            }

            batchPutItems(newItems);
            return;
        }

        // 3) Slow fallback: we couldn't match the last stored message
        //    in the incoming list (edge case: advisor trimmed differently,
        //    message sanitized, etc). Fall back to full-partition approach.
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(conversationId).build());

        List<DynamoChatMemoryItem> existingItems = table
                .query(r -> r
                .queryConditional(condition)
                .scanIndexForward(true))
                .items()
                .stream()
                .toList();

        int existingCount = existingItems.size();
        long maxIndex = existingItems.isEmpty()
                ? -1L
                : existingItems.get(existingCount - 1).getMessageIndex();

        int totalMessages = messages.size();
        int alreadyPersistedCount = Math.min(existingCount, totalMessages);

        if (alreadyPersistedCount >= totalMessages) {
            return;
        }

        List<DynamoChatMemoryItem> newItems
                = new ArrayList<>(totalMessages - alreadyPersistedCount);

        long nextIndex = maxIndex;
        for (int i = alreadyPersistedCount; i < totalMessages; i++) {
            Message msg = messages.get(i);
            nextIndex++;
            newItems.add(buildItem(conversationId, (int) nextIndex, msg, ttlEpochSeconds));
        }

        batchPutItems(newItems);
    }

    private int findLastPersistedPosition(List<Message> messages,
            Message lastPersisted) {

        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (sameMessageForMatch(m, lastPersisted)) {
                return i;
            }
        }
        return -1;
    }

    private boolean sameMessageForMatch(Message a, Message b) {
        if (a.getMessageType() != b.getMessageType()) {
            return false;
        }

        // We store only sanitized text for USER / ASSISTANT / SYSTEM.
        // For TOOL messages, text is usually null; you could extend this to
        // compare toolCalls/toolResponses if you care.
        String at = a.getText();
        String bt = b.getText();
        if (at == null && bt == null) {
            return true;
        }
        if (at == null || bt == null) {
            return false;
        }
        return at.equals(bt);
    }

    private Optional<DynamoChatMemoryItem> findLastItem(String conversationId) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(conversationId).build());

        return table.query(r -> r
                .queryConditional(condition)
                .scanIndexForward(false) // highest SK first
                .limit(1))
                .items()
                .stream()
                .findFirst();
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(conversationId).build());

        table.query(r -> r.queryConditional(condition))
                .items()
                .forEach(table::deleteItem);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------
    /**
     * Find the highest messageIndex for a conversation using a descending query with limit(1). This is O(1) in terms of
     * returned items.
     */
    private OptionalLong findLastIndex(String conversationId) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(conversationId).build());

        return table.query(r -> r
                .queryConditional(condition)
                .scanIndexForward(false) // highest SK first
                .limit(1))
                .items()
                .stream()
                .findFirst()
                .map(i -> OptionalLong.of(i.getMessageIndex()))
                .orElse(OptionalLong.empty());
    }

    /**
     * Build a DynamoChatMemoryItem from a Spring AI Message.
     */
    private DynamoChatMemoryItem buildItem(String conversationId,
            int index,
            Message msg,
            long ttlEpochSeconds) {

        DynamoChatMemoryItem item = new DynamoChatMemoryItem();
        item.setConversationId(conversationId);
        item.setMessageIndex((long) index);
        item.setMessageType(msg.getMessageType().name());
        item.setTtl(ttlEpochSeconds);

        // clear optional fields
        item.setToolCallsJson(null);
        item.setToolResponseJson(null);
        item.setMetadataJson(null);

        switch (msg) {
            case AssistantMessage am -> {
                item.setText(sanitizeAssistantText(am.getText()));
                writeToolCalls(item, am);
                //writeMetadata(item, am.getMetadata());
            }
            case UserMessage um -> {
                item.setText(um.getText());
                //writeMetadata(item, um.getMetadata());
            }
            case SystemMessage sm -> {
                item.setText(sm.getText());
                writeMetadata(item, sm.getMetadata());
            }
            case ToolResponseMessage trm -> {
                item.setMessageType(MessageType.TOOL.name());
                item.setText(null);
                writeToolResponses(item, trm);
                writeMetadata(item, trm.getMetadata());
            }
            default -> {
                item.setText(msg.getText());
                writeMetadata(item, msg.getMetadata());
            }
        }

        return item;
    }

    /**
     * Batch-write items in groups of 25 using the Enhanced Client.
     */
    private void batchPutItems(List<DynamoChatMemoryItem> items) {
        if (items.isEmpty()) {
            return;
        }

        final int batchSize = 25;
        for (int from = 0; from < items.size(); from += batchSize) {
            int to = Math.min(from + batchSize, items.size());
            List<DynamoChatMemoryItem> batch = items.subList(from, to);

            BatchWriteItemEnhancedRequest.Builder requestBuilder
                    = BatchWriteItemEnhancedRequest.builder();

            WriteBatch.Builder<DynamoChatMemoryItem> writeBatch
                    = WriteBatch.builder(DynamoChatMemoryItem.class)
                            .mappedTableResource(table);

            for (DynamoChatMemoryItem item : batch) {
                writeBatch.addPutItem(item);
            }

            requestBuilder.addWriteBatch(writeBatch.build());
            enhancedClient.batchWriteItem(requestBuilder.build());
        }
    }

    // -------------------------------------------------------------------------
    // JSON helpers (unchanged)
    // -------------------------------------------------------------------------
    private void writeToolCalls(DynamoChatMemoryItem item, AssistantMessage am) {
        List<ToolCall> toolCalls = am.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        try {
            item.setToolCallsJson(objectMapper.writeValueAsString(toolCalls));
        } catch (Exception e) {
            // fail-soft
        }
    }

    private void writeToolResponses(DynamoChatMemoryItem item, ToolResponseMessage trm) {
        List<ToolResponse> responses = trm.getResponses();
        if (responses == null || responses.isEmpty()) {
            return;
        }
        try {
            item.setToolResponseJson(objectMapper.writeValueAsString(responses));
        } catch (Exception e) {
            // fail-soft
        }
    }

    private void writeMetadata(DynamoChatMemoryItem item, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        try {
            item.setMetadataJson(objectMapper.writeValueAsString(metadata));
        } catch (Exception e) {
            // skip metadata if it doesn't serialize cleanly
        }
    }

    private Map<String, Object> readMetadata(DynamoChatMemoryItem item) {
        if (item.getMetadataJson() == null) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(
                    item.getMetadataJson(),
                    new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private List<ToolCall> readToolCalls(DynamoChatMemoryItem item) {
        if (item.getToolCallsJson() == null) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(
                    item.getToolCallsJson(),
                    new TypeReference<List<ToolCall>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<ToolResponse> readToolResponses(DynamoChatMemoryItem item) {
        if (item.getToolResponseJson() == null) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(
                    item.getToolResponseJson(),
                    new TypeReference<List<ToolResponse>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Message toMessage(DynamoChatMemoryItem item) {
        MessageType type = MessageType.valueOf(item.getMessageType());
        String text = item.getText();
        Map<String, Object> metadata = readMetadata(item);

        try {
            return switch (type) {
                case USER ->
                    UserMessage.builder()
                    .text(text)
                    //.metadata(metadata)
                    .build();

                case SYSTEM ->
                    SystemMessage.builder()
                    .text(text)
                    .metadata(metadata)
                    .build();

                case ASSISTANT -> {
                    List<ToolCall> toolCalls = readToolCalls(item);
                    AssistantMessage.Builder builder = AssistantMessage.builder()
                            .content(text);
                    //.properties(metadata);

                    if (!toolCalls.isEmpty()) {
                        builder.toolCalls(toolCalls);
                    }

                    yield builder.build();
                }

                case TOOL -> {
                    List<ToolResponse> responses = readToolResponses(item);
                    ToolResponseMessage.Builder builder = ToolResponseMessage.builder()
                            .metadata(metadata);

                    if (!responses.isEmpty()) {
                        builder.responses(responses);
                    }

                    yield builder.build();
                }
            };
        } catch (Exception e) {
            return SystemMessage.builder()
                    .text("[memory-deser-error " + type + "]: " + (text == null ? "" : text))
                    .metadata(metadata)
                    .build();
        }
    }
}
