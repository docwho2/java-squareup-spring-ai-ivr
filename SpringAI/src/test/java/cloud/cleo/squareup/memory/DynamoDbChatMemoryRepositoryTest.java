package cloud.cleo.squareup.memory;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
