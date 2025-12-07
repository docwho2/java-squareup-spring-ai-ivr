package cloud.cleo.squareup.memory;

import static cloud.cleo.squareup.cloudfunctions.LexFunction.sanitizeAssistantText;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import lombok.extern.log4j.Log4j2;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;

import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.*;
import software.amazon.awssdk.enhanced.dynamodb.Key;

/**
 * Spring AI ChatMemoryRepository backed by DynamoDB Enhanced Client.
 *
 * Table schema (Dynamo): PK: conversationId (String) SK: messageIndex (Number, 0..N-1) ttl: epoch seconds for TTL
 * (per-message)
 */
@Log4j2
public class DynamoDbChatMemoryRepository implements ChatMemoryRepository {

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbTable<DynamoChatMemoryItem> table;
    private final Duration ttlDuration;
    private final Clock clock;
    private final int maxMessages;

    @Autowired
    private ObjectMapper objectMapper;

    // Simple per-JVM cache, keyed by conversationId.
    // Thread-safe because a single Lambda container can handle concurrent requests.
    private final Map<String, ConversationState> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public DynamoDbChatMemoryRepository(DynamoDbEnhancedClient enhancedClient,
            String tableName,
            Duration ttlDuration,
            Clock clock, int maxMessages) {

        this.enhancedClient = enhancedClient;
        this.table = enhancedClient.table(
                tableName,
                TableSchema.fromBean(DynamoChatMemoryItem.class));
        this.ttlDuration = ttlDuration;
        this.clock = (clock != null) ? clock : Clock.systemUTC();
        this.maxMessages = maxMessages;
    }

    private static final class ConversationState {
        // Full logical history Spring thinks exists for this conversation

        List<Message> messages;

        // Index in Dynamo of the last *persisted* message, or -1 if none.
        long lastPersistedIndex;

        ConversationState(List<Message> messages, long lastPersistedIndex) {
            this.messages = messages;
            this.lastPersistedIndex = lastPersistedIndex;
        }
    }

    // -------------------------------------------------------------------------
    // ChatMemoryRepository
    // -------------------------------------------------------------------------
    @Override
    public List<String> findConversationIds() {
        PageIterable<DynamoChatMemoryItem> pages = table.scan(r -> r
                .consistentRead(false)
                .attributesToProject("conversationId")
        );

        final var response = pages.items()
                .stream()
                .map(DynamoChatMemoryItem::getConversationId)
                .distinct()
                .toList();

        log.debug("findConversationIds returning {} keys", response.size());
        return response;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        // 1) Cache first
        var state = cache.get(conversationId);
        if (state != null) {
            log.debug("findByConversationId({}) served from cache, {} messages",
                    conversationId, state.messages.size());
            return state.messages;
        }

        // 2) Load from Dynamo
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(conversationId).build());

        List<DynamoChatMemoryItem> items = table.query(r -> r
                .queryConditional(condition)
                .scanIndexForward(true) // ascending messageIndex
                .limit(maxMessages))
                .items()
                .stream()
                .toList();

        List<Message> messages = items.stream()
                .map(this::toMessage)
                .toList();

        long lastPersistedIndex = items.isEmpty()
                ? -1L
                : items.get(items.size() - 1).getMessageIndex();

        log.debug("findByConversationId({}) loaded {} items from Dynamo, lastPersistedIndex={}",
                conversationId, messages.size(), lastPersistedIndex);

        // 3) Cache for this Lambda invocation
        cache.put(conversationId, new ConversationState(new ArrayList<>(messages), lastPersistedIndex));

        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Message last = messages.get(messages.size() - 1);

        // Only skip the pre-call
        if (last.getMessageType() != MessageType.ASSISTANT) {
            log.debug("saveAll({}) pre-call USER-only, updating cache but not persisting", conversationId);
            cache.compute(conversationId, (id, state) -> {
                if (state == null) {
                    // No prior findByConversationId in this container; treat as new
                    return new ConversationState(new ArrayList<>(messages), -1L);
                }
                state.messages = new ArrayList<>(messages);
                return state;
            });
            return;
        }

        // From here on, we have a "complete" turn (ASSISTANT/SYSTEM/TOOL at tail).
        long ttlEpochSeconds = clock.instant()
                .plus(ttlDuration)
                .getEpochSecond();

        ConversationState state = cache.get(conversationId);

        if (state == null) {
            // Fallback: we didn't have a cached state (e.g., saveAll called without findByConversationId).
            // Use your existing logic to discover last index from Dynamo once.
            log.debug("saveAll({}) with no cache state, falling back to Dynamo last-item lookup", conversationId);
            long lastIdx = findLastItem(conversationId)
                    .map(DynamoChatMemoryItem::getMessageIndex)
                    .orElse(-1L);
            state = new ConversationState(new ArrayList<>(messages), lastIdx);
            cache.put(conversationId, state);
        } else {
            // Update state.messages to the latest list Spring gave us
            state.messages = new ArrayList<>(messages);
        }

        // Write only messages AFTER lastPersistedIndex
        long lastPersisted = state.lastPersistedIndex;
        int totalMessages = state.messages.size();
        int startListIndex = (int) (lastPersisted + 1); // relies on messageIndex starting at 0, no gaps

        if (startListIndex >= totalMessages) {
            log.debug("saveAll({}) nothing new to persist (startListIndex >= totalMessages)", conversationId);
            cache.remove(conversationId); // clean up
            return;
        }

        List<DynamoChatMemoryItem> newItems = new ArrayList<>(totalMessages - startListIndex);
        long nextIndex = lastPersisted;
        for (int i = startListIndex; i < totalMessages; i++) {
            Message msg = state.messages.get(i);
            nextIndex++;
            newItems.add(buildItem(conversationId, (int) nextIndex, msg, ttlEpochSeconds));
        }

        log.debug("saveAll({}) persisting {} new items (indexes {}..{}), then evicting cache entry",
                conversationId, newItems.size(), startListIndex, totalMessages - 1);

        batchPutItems(newItems);

        // Update and evict so the next Lambda invocation starts fresh from Dynamo
        state.lastPersistedIndex = nextIndex;
        cache.remove(conversationId);
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
        log.debug("deleteByConversationId called with conversationId {}", conversationId);
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
