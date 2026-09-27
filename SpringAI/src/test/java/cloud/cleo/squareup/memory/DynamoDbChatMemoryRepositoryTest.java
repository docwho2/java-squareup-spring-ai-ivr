package cloud.cleo.squareup.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteResult;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamoDbChatMemoryRepositoryTest {

    @Test
    void excludesEmptyAssistantGenerationCreatedBesideBedrockToolUse() {
        AssistantMessage emptyAssistant = AssistantMessage.builder().content(null).build();
        AssistantMessage toolUseAssistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("tool-1", "function", "lookup", "{}")))
                .build();

        assertFalse(DynamoDbChatMemoryRepository.isStorableMessage(emptyAssistant));
        assertTrue(DynamoDbChatMemoryRepository.isStorableMessage(toolUseAssistant));
        assertTrue(DynamoDbChatMemoryRepository.isStorableMessage(UserMessage.builder().text("question").build()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void keepsTheNewestCompleteTurnsInOneBatchWritePerTurn() {
        var rows = new TreeMap<Long, DynamoChatMemoryItem>();
        var requestsPerTurn = new ArrayList<Integer>();
        int[] queryCount = {0};
        DynamoDbEnhancedClient client = mock(DynamoDbEnhancedClient.class);
        DynamoDbTable<DynamoChatMemoryItem> table = mock(DynamoDbTable.class);
        when(client.table(eq("chat"), any(TableSchema.class))).thenReturn(table);
        when(table.tableSchema()).thenReturn(TableSchema.fromBean(DynamoChatMemoryItem.class));
        when(table.tableName()).thenReturn("chat");
        when(table.query(any(Consumer.class))).thenAnswer(ignored -> {
            queryCount[0]++;
            return PageIterable.create(() -> List.of(Page.create(new ArrayList<>(rows.values()))).iterator());
        });
        when(client.batchWriteItem(any(BatchWriteItemEnhancedRequest.class))).thenAnswer(invocation -> {
            BatchWriteItemEnhancedRequest request = invocation.getArgument(0);
            int count = 0;
            for (var batch : request.writeBatches()) {
                for (var write : batch.writeRequests()) {
                    count++;
                    if (write.deleteRequest() != null) {
                        rows.remove(Long.valueOf(write.deleteRequest().key().get("messageIndex").n()));
                    } else {
                        var attributes = write.putRequest().item();
                        var item = new DynamoChatMemoryItem();
                        item.setConversationId(attributes.get("conversationId").s());
                        item.setMessageIndex(Long.valueOf(attributes.get("messageIndex").n()));
                        item.setMessageType(attributes.get("messageType").s());
                        if (attributes.containsKey("text")) {
                            item.setText(attributes.get("text").s());
                        }
                        rows.put(item.getMessageIndex(), item);
                    }
                }
            }
            requestsPerTurn.add(count);
            return BatchWriteResult.builder().build();
        });

        var repository = new DynamoDbChatMemoryRepository(client, JsonMapper.builder().build(),
                Duration.ofHours(24), "chat");
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(50)
                .build();
        memory.add("conversation", SystemMessage.builder().text("system").build());
        for (int turn = 0; turn < 28; turn++) {
            memory.add("conversation", UserMessage.builder().text("user " + turn).build());
            memory.add("conversation", AssistantMessage.builder().content("assistant " + turn).build());
        }

        assertEquals(28, queryCount[0]);
        var messages = memory.get("conversation");
        assertEquals(49, messages.size()); // Spring AI evicts a complete turn to stay below 50.
        assertEquals("system", messages.getFirst().getText());
        assertEquals("assistant 27", messages.getLast().getText());
        assertFalse(messages.stream().anyMatch(message -> "user 0".equals(message.getText())));
        assertEquals(28, requestsPerTurn.size());
        assertEquals(3, requestsPerTurn.getFirst());
        assertTrue(requestsPerTurn.stream().skip(1).anyMatch(count -> count == 4));
    }
}
