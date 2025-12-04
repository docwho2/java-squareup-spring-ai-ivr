package cloud.cleo.squareup.config;

import cloud.cleo.squareup.memory.DynamoDbChatMemoryRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.bedrock.converse.api.BedrockCacheOptions;
import org.springframework.ai.bedrock.converse.api.BedrockCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 *
 * @author sjensen
 */
@Configuration
public class ChatConfig {

    @Bean
    public OpenAiChatOptions openAiChatOptions(@Value("${spring.ai.openai.chat.options.model:gpt-5-nano}") String model) {
        var builder = OpenAiChatOptions.builder()
                .model(model)
                .parallelToolCalls(true)
                // We want cache to work across different Lambda IPs(AZ) and across regions
                .promptCacheKey("cloud-cleo-squareup-spring-ai")
                .N(1);  // We only ever want 1 response

        if (model.startsWith("gpt-4")) {
            //.temperature(.2) Not supported in GPT_5 models, only default value of 1
            builder = builder.temperature(.2);
        }

        return builder.build();
    }

    @Primary
    @Bean(name = "customOpenAiChatModel")
    public ChatModel chatModel(OpenAiApi api, OpenAiChatOptions options) {
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    @Bean
    public BedrockChatOptions bedrockChatOptions(@Value("${spring.ai.bedrock.chat.options.model:}") String model) {
        String resolved = (model == null || model.isBlank())
                ? "us.amazon.nova-2-lite-v1:0"
                : model;

        return BedrockChatOptions.builder()
                .model(resolved)
                //.cacheOptions(BedrockCacheOptions.builder().strategy(BedrockCacheStrategy.SYSTEM_AND_TOOLS).build())
                .build();
    }

    
    @Bean(name = "bedrockChatModel")
    public ChatModel bedrockChatModel(BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient, BedrockRuntimeClient bedrockRuntimeClient, BedrockChatOptions options) {
        return BedrockProxyChatModel.builder()
                .bedrockRuntimeClient(bedrockRuntimeClient)
                .bedrockRuntimeAsyncClient(bedrockRuntimeAsyncClient)
                .defaultOptions(options)
                .build();
    }
    
    @Bean
    @ConfigurationProperties(prefix = "chat.memory")
    public ChatMemoryProperties chatMemoryProperties() {
        return new ChatMemoryProperties();
    }

    public static class ChatMemoryProperties {
        /**
         * Max messages to keep in active window per conversation.
         */
        private int maxMessages = 30;

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }
    }

    @Bean
    public ChatMemoryRepository chatMemoryRepository(DynamoDbEnhancedClient enhancedClient, ChatMemoryProperties props) {
        return new DynamoDbChatMemoryRepository(
                enhancedClient,
                System.getenv().getOrDefault("CHAT_MEMORY_TABLE", "chat-memory"),
                Duration.ofDays(1),
                Clock.systemUTC(),
                props.getMaxMessages());
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(30)
                .build();
    }

    @Bean
    @Primary
    public ChatClient chatClient(ChatModel model, ChatMemory memory) {
        return ChatClient.builder(model)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory).build(),
                        // Ensures SystemMessage is always first for model compatibility
                        new SystemFirstSortingAdvisor(),
                        // call advisor LAST so the chain actually invokes the model
                        ChatModelCallAdvisor.builder().chatModel(model).build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    /**
     * Fix until Spring AI fixes ordering such that system prompt is always sent first.
     * 
     * @see https://github.com/spring-projects/spring-ai/issues/4170
     */
    private static class SystemFirstSortingAdvisor implements BaseAdvisor {

        @Override
        public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
            List<Message> processedMessages = chatClientRequest.prompt().getInstructions();
            processedMessages.sort(Comparator.comparing(m -> m.getMessageType() == MessageType.SYSTEM ? 0 : 1));
            return chatClientRequest.mutate()
                    .prompt(chatClientRequest.prompt().mutate().messages(processedMessages).build())
                    .build();
        }

        @Override
        public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
            return chatClientResponse; // no-op
        }

        @Override
        public int getOrder() {
            return 0; // larger than MessageChatMemoryAdvisor so it runs afterwards
        }
    }
}
