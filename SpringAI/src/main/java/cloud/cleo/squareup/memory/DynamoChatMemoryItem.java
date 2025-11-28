package cloud.cleo.squareup.memory;

import lombok.Data;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Single chat message stored in Dynamo as part of a conversation window.
 */
@DynamoDbBean
@Data
public class DynamoChatMemoryItem {

    private String conversationId;
    private Long messageIndex;   // 0..N-1 in the current window
    private String messageType;  // org.springframework.ai.chat.messages.MessageType.name()
    private String text;         // Message.getText()
    
    
        // Optional JSON payloads:
    private String toolCallsJson;   // List<AssistantMessage.ToolCall> as JSON
    private String toolResponseJson; // ToolResponseMessage.ToolResponse list or payload
    private String metadataJson;    // optional message metadata

    
    
    private Long ttl;            // Epoch seconds for Dynamo TTL

    @DynamoDbPartitionKey
    public String getConversationId() {
        return conversationId;
    }

    @DynamoDbSortKey
    public Long getMessageIndex() {
        return messageIndex;
    }
}
