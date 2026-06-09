package cloud.cleo.squareup.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatConfigTest {

    @Test
    void executesToolWithUnifiedSpecAndContext() {
        var model = new ToolCallingModel();
        var memory = MessageWindowChatMemory.builder().build();
        var tool = new ContextAwareTool();
        String conversationId = "tool-memory-test";

        ChatResponse response = new ChatConfig().chatClient(model, memory)
                .prompt()
                .user("Find the item")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .options(ToolCallingChatOptions.builder())
                .tools(tool)
                .toolContext(Map.of("request-id", "request-123"))
                .call()
                .chatResponse();

        assertEquals("Item found", response.getResult().getOutput().getText());
        assertEquals("request-123", tool.requestId);
        assertEquals(2, model.prompts.size());
    }

    private static final class ContextAwareTool {

        private String requestId;

        @Tool(description = "Find an item in stock")
        String lookup(ToolContext context) {
            requestId = (String) context.getContext().get("request-id");
            return "found";
        }
    }

    private static final class ToolCallingModel implements ChatModel {

        private final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (prompts.size() == 1) {
                return response(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("tool-1", "function", "lookup", "{}")))
                        .build());
            }
            return response(new AssistantMessage("Item found"));
        }

        private ChatResponse response(AssistantMessage message) {
            return new ChatResponse(List.of(new Generation(message)));
        }
    }
}
