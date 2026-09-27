package cloud.cleo.squareup.memory;

import static cloud.cleo.squareup.cloudfunctions.LexFunction.sanitizeAssistantText;

import java.time.Duration;
import java.time.Instant;
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

import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.*;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring AI ChatMemoryRepository backed by DynamoDB Enhanced Client.
 *
 * Table schema (Dynamo): PK: conversationId (String), SK: monotonically increasing messageIndex (Number),
 * ttl: epoch seconds for per-message TTL. Window eviction can leave gaps in the sort keys.
 */
@Log4j2
public class DynamoDbChatMemoryRepository implements ChatMemoryRepository {

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbTable<DynamoChatMemoryItem> table;

    /**
     * How long to leave ChatMemoryItems around. Saves calling delete and let Dynamo clean up later with no write unit
     * impact.
     */
    private final Duration ttlDuration;

    private final JsonMapper objectMapper;

    // Simple per-JVM cache, keyed by conversationId.
    // Thread-safe because a single Lambda container can handle concurrent requests.
    private final Map<String, ConversationState> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public DynamoDbChatMemoryRepository(DynamoDbEnhancedClient enhancedClient, JsonMapper objectMapper, Duration ttlDuration, String tableName) {
        this.enhancedClient = enhancedClient;
        this.objectMapper = objectMapper;
        this.ttlDuration = ttlDuration;
        this.table = enhancedClient.table(
                tableName,
                TableSchema.fromBean(DynamoChatMemoryItem.class));
    }

    private record IndexedMessage(long index, Message message) { }

    private static final class ConversationState {
        List<Message> messages;
        // Null only when saveAll was called before a read. Load Dynamo before the final write.
        final List<IndexedMessage> persistedMessages;
        final List<Long> rowIndexes;
        final long lastPersistedIndex;

        ConversationState(List<Message> messages, List<IndexedMessage> persistedMessages,
                List<Long> rowIndexes, long lastPersistedIndex) {
            this.messages = messages;
            this.persistedMessages = persistedMessages;
            this.rowIndexes = rowIndexes;
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

        state = loadState(conversationId);
        cache.put(conversationId, state);
        return state.messages;
    }

    private ConversationState loadState(String conversationId) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(conversationId).build());

        List<DynamoChatMemoryItem> items = table.query(r -> r
                .queryConditional(condition)
                .scanIndexForward(true)) // ascending messageIndex
                .items()
                .stream()
                .toList();

        List<IndexedMessage> persistedMessages = new ArrayList<>();
        List<Long> rowIndexes = new ArrayList<>(items.size());
        for (DynamoChatMemoryItem item : items) {
            rowIndexes.add(item.getMessageIndex());
            Message message = toMessage(item);
            if (isStorableMessage(message)) {
                persistedMessages.add(new IndexedMessage(item.getMessageIndex(), message));
            }
        }
        List<Message> messages = persistedMessages.stream().map(IndexedMessage::message).toList();

        long lastPersistedIndex = items.isEmpty()
                ? -1L
                : items.get(items.size() - 1).getMessageIndex();

        log.debug("findByConversationId({}) loaded {} usable messages from {} Dynamo items, lastPersistedIndex={}",
                conversationId, messages.size(), items.size(), lastPersistedIndex);

        return new ConversationState(new ArrayList<>(messages), persistedMessages, rowIndexes, lastPersistedIndex);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null) {
            return;
        }
        if (messages.isEmpty()) {
            deleteByConversationId(conversationId);
            return;
        }

        List<Message> storableMessages = messages.stream()
                .filter(DynamoDbChatMemoryRepository::isStorableMessage)
                .toList();
        if (storableMessages.size() != messages.size()) {
            log.debug("saveAll({}) omitted {} empty assistant messages from conversation history",
                    conversationId, messages.size() - storableMessages.size());
        }
        if (storableMessages.isEmpty()) {
            return;
        }

        Message last = storableMessages.getLast();

        // Only skip the pre-call, only ASSISTANT messages will trigger a real write
        if (last.getMessageType() != MessageType.ASSISTANT) {
            log.debug("saveAll({}) called with last message type {}, caching only (no persistence this turn)",
                    conversationId, last.getMessageType());
            cache.compute(conversationId, (id, state) -> {
                if (state == null) {
                    // No prior findByConversationId in this container; treat as new
                    return new ConversationState(new ArrayList<>(storableMessages), null, null, -1L);
                }
                state.messages = new ArrayList<>(storableMessages);
                return state;
            });
            return;
        }

        // The assistant completes the turn. Spring AI supplies the replacement window,
        // which may have evicted whole turns while retaining a system message.
        long ttlEpochSeconds = Instant.now()
                .plus(ttlDuration)
                .getEpochSecond();

        ConversationState state = cache.get(conversationId);
        if (state == null || state.persistedMessages == null) {
            state = loadState(conversationId);
        }

        List<Long> retainedIndexes = new ArrayList<>();
        List<Message> newMessages = new ArrayList<>();
        int oldCursor = 0;
        boolean sawNewMessage = false;
        boolean reorder = false;
        for (Message message : storableMessages) {
            int match = -1;
            for (int i = oldCursor; i < state.persistedMessages.size(); i++) {
                if (state.persistedMessages.get(i).message().equals(message)) {
                    match = i;
                    break;
                }
            }
            if (match >= 0) {
                if (sawNewMessage) {
                    reorder = true;
                    break;
                }
                retainedIndexes.add(state.persistedMessages.get(match).index());
                oldCursor = match + 1;
            } else {
                sawNewMessage = true;
                newMessages.add(message);
            }
        }
        if (reorder) {
            // A caller replaced or reordered the window; honor saveAll's replacement contract.
            retainedIndexes.clear();
            newMessages = storableMessages;
        }

        Set<Long> retained = new HashSet<>(retainedIndexes);
        List<Long> deletedIndexes = state.rowIndexes.stream()
                .filter(index -> !retained.contains(index))
                .toList();
        List<DynamoChatMemoryItem> newItems = new ArrayList<>(newMessages.size());
        long nextIndex = state.lastPersistedIndex;
        for (Message message : newMessages) {
            newItems.add(buildItem(conversationId, ++nextIndex, message, ttlEpochSeconds));
        }

        log.debug("saveAll({}) deleting {} evicted items and appending {} items",
                conversationId, deletedIndexes.size(), newItems.size());
        batchWriteItems(conversationId, deletedIndexes, newItems);
        cache.remove(conversationId);
    }

    /**
     * Bedrock tool-use responses can include an assistant generation with neither content nor tool calls. Persisting
     * that message produces an invalid Bedrock conversation on the next tool iteration.
     */
    static boolean isStorableMessage(Message message) {
        if (!(message instanceof AssistantMessage assistant)) {
            return true;
        }
        return (assistant.getText() != null && !assistant.getText().isBlank())
                || (assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty());
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        cache.remove(conversationId);
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
            long index,
            Message msg,
            long ttlEpochSeconds) {

        DynamoChatMemoryItem item = new DynamoChatMemoryItem();
        item.setConversationId(conversationId);
        item.setMessageIndex(index);
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
     * Delete evicted rows and append new rows in the same batch request where possible.
     */
    private void batchWriteItems(String conversationId, List<Long> deletedIndexes, List<DynamoChatMemoryItem> items) {
        if (deletedIndexes.isEmpty() && items.isEmpty()) {
            return;
        }

        final int batchSize = 25;
        int total = deletedIndexes.size() + items.size();
        for (int from = 0; from < total; from += batchSize) {
            int to = Math.min(from + batchSize, total);
            List<Key> deletes = new ArrayList<>();
            List<DynamoChatMemoryItem> puts = new ArrayList<>();
            for (int i = from; i < to; i++) {
                if (i < deletedIndexes.size()) {
                    deletes.add(Key.builder()
                            .partitionValue(conversationId)
                            .sortValue(deletedIndexes.get(i))
                            .build());
                } else {
                    puts.add(items.get(i - deletedIndexes.size()));
                }
            }

            for (int attempt = 0; attempt < 5; attempt++) {
                WriteBatch.Builder<DynamoChatMemoryItem> writeBatch = WriteBatch.builder(DynamoChatMemoryItem.class)
                        .mappedTableResource(table);
                deletes.forEach(writeBatch::addDeleteItem);
                puts.forEach(writeBatch::addPutItem);
                BatchWriteResult result = enhancedClient.batchWriteItem(
                        BatchWriteItemEnhancedRequest.builder().addWriteBatch(writeBatch.build()).build());
                puts = result.unprocessedPutItemsForTable(table);
                deletes = result.unprocessedDeleteItemsForTable(table);
                if (puts.isEmpty() && deletes.isEmpty()) {
                    break;
                }
                if (attempt == 4) {
                    throw new IllegalStateException("DynamoDB did not process every chat memory write");
                }
                try {
                    Thread.sleep(25L << attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while retrying chat memory write", e);
                }
            }
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
                    .metadata(metadata)
                    .build();

                case SYSTEM ->
                    SystemMessage.builder()
                    .text(text)
                    .metadata(metadata)
                    .build();

                case ASSISTANT -> {
                    List<ToolCall> toolCalls = readToolCalls(item);
                    AssistantMessage.Builder builder = AssistantMessage.builder()
                            .content(text)
                            .properties(metadata);

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
