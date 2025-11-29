package cloud.cleo.squareup.config;

import cloud.cleo.squareup.memory.DynamoDbChatMemoryRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
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
    OpenAiApi openAiApi(@Value("${spring.ai.openai.api-key:}") String apiKey) {
        return OpenAiApi.builder()
                .apiKey(apiKey)
                .build();
    }

    @Bean
    public OpenAiChatOptions openAiChatOptions(@Value("${spring.ai.openai.chat.options.model:gpt-5-nano}") String model) {
        return OpenAiChatOptions.builder()
                .model(model)
                //.temperature(.2) Not supported in GOT_5 models, only default value of 1
                .N(1)
                .build();
    }

    //@Primary - using Bedrock for now
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
                ? "global.anthropic.claude-sonnet-4-5-20250929-v1:0"
                : model;

        return BedrockChatOptions.builder()
                .model(resolved)
                .build();
    }

    @Primary
    @Bean(name = "bedrockChatModel")
    public ChatModel bedrockChatModel(BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient, BedrockRuntimeClient bedrockRuntimeClient, BedrockChatOptions options) {
        return BedrockProxyChatModel.builder()
                .bedrockRuntimeClient(bedrockRuntimeClient)
                .bedrockRuntimeAsyncClient(bedrockRuntimeAsyncClient)
                .defaultOptions(options)
                .build();
    }

    @Bean
    public ChatMemoryRepository chatMemoryRepository(DynamoDbEnhancedClient enhancedClient) {
        return new DynamoDbChatMemoryRepository(
                enhancedClient,
                System.getenv().getOrDefault("CHAT_MEMORY_TABLE", "chat-memory"),
                Duration.ofDays(1),
                Clock.systemUTC());
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(30) // whatever you like
                .build();
    }

    @Bean
    @Primary
    public ChatClient chatClient(ChatModel model, ChatMemory memory) {
        return ChatClient.builder(model)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory).build(),
                        // call advisor LAST so the chain actually invokes the model
                        ChatModelCallAdvisor.builder().chatModel(model).build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }
}
