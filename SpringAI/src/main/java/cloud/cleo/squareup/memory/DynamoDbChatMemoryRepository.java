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
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.Key;

/**
 * Spring AI ChatMemoryRepository backed by DynamoDB Enhanced Client.
 *
 * Table schema (Dynamo):
 *   PK: conversationId (String)
 *   SK: messageIndex   (Number, 0..N-1)
 *   ttl: epoch seconds for TTL
 */
public class DynamoDbChatMemoryRepository implements ChatMemoryRepository {

    private final DynamoDbTable<DynamoChatMemoryItem> table;
    private final Duration ttlDuration;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DynamoDbChatMemoryRepository(DynamoDbEnhancedClient enhancedClient,
                                        String tableName,
                                        Duration ttlDuration,
                                        Clock clock) {

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
        PageIterable<DynamoChatMemoryItem> pages =
                table.scan(ScanEnhancedRequest.builder().build());

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

        return table.query(r -> r.queryConditional(condition))
                .items()
                .stream()
                .sorted(Comparator.comparing(DynamoChatMemoryItem::getMessageIndex))
                .map(this::toMessage)
                .toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        long ttlEpochSeconds = clock.instant().plus(ttlDuration).getEpochSecond();

        // Load existing items so we can delete stale tail indexes
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(conversationId).build());

        List<DynamoChatMemoryItem> existing = table
                .query(r -> r.queryConditional(condition))
                .items()
                .stream()
                .toList();

        int newSize = messages.size();

        // Upsert per index
        for (int i = 0; i < newSize; i++) {
            Message msg = messages.get(i);

            DynamoChatMemoryItem item = new DynamoChatMemoryItem();
            item.setConversationId(conversationId);
            item.setMessageIndex((long) i);
            item.setMessageType(msg.getMessageType().name());
            item.setTtl(ttlEpochSeconds);

            // clear optional fields
            item.setToolCallsJson(null);
            item.setToolResponseJson(null);
            item.setMetadataJson(null);

            // Basic text & tool storage
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
                    // Tool responses; messageType is TOOL already but enforce for clarity
                    item.setMessageType(MessageType.TOOL.name());
                    item.setText(null);
                    writeToolResponses(item, trm);
                    writeMetadata(item, trm.getMetadata());
                }
                default -> {
                    // Fallback: just persist text
                    item.setText(msg.getText());
                    writeMetadata(item, msg.getMetadata());
                }
            }

            table.putItem(item); // upsert, NOT delete-all + rewrite
        }

        // Delete any existing items whose index is now outside the window
        if (!existing.isEmpty()) {
            for (DynamoChatMemoryItem e : existing) {
                if (e.getMessageIndex() >= newSize) {
                    table.deleteItem(e);
                }
            }
        }
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
    // Helpers – sanitize, JSON (de)serialization, builders
    // -------------------------------------------------------------------------


    private void writeToolCalls(DynamoChatMemoryItem item, AssistantMessage am) {
        List<ToolCall> toolCalls = am.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        try {
            item.setToolCallsJson(objectMapper.writeValueAsString(toolCalls));
        } catch (Exception e) {
            // fail-soft: just skip toolCalls serialization
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
                    new TypeReference<Map<String, Object>>() {});
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
                    new TypeReference<List<ToolCall>>() {});
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
                    new TypeReference<List<ToolResponse>>() {});
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
                case USER -> UserMessage.builder()
                        .text(text)
                        //.metadata(metadata)
                        .build();

                case SYSTEM -> SystemMessage.builder()
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
        }
        catch (Exception e) {
            // As a last resort, fall back to a system message capturing the error.
            return SystemMessage.builder()
                    .text("[memory-deser-error " + type + "]: " + (text == null ? "" : text))
                    .metadata(metadata)
                    .build();
        }
    }
}
