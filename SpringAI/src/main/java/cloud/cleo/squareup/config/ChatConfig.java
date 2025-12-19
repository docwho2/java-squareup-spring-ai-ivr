package cloud.cleo.squareup.config;

import cloud.cleo.squareup.memory.DynamoDbChatMemoryRepository;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import tools.jackson.databind.json.JsonMapper;

/**
 *
 * @author sjensen
 */
@Configuration
public class ChatConfig {

    /**
     * Which provider to use at runtime. Set via env var SPRING_AI_PROVIDER in GitHub workflow: OPENAI | BEDROCK
     */
    public enum Provider {
        OPENAI, BEDROCK
    }

    @Bean
    public Provider springAiProvider(@Value("${SPRING_AI_PROVIDER:BEDROCK}") String provider) {
        try {
            return Provider.valueOf(provider.trim().toUpperCase());
        } catch (Exception ex) {
            // Default to BEDROCK if unset or invalid
            return Provider.BEDROCK;
        }
    }

    @Primary
    @Bean
    public ChatModel activeChatModel(Provider springAiProvider,
            @Qualifier("bedrockChatModel") ChatModel bedrockModel,
            @Qualifier("customOpenAiChatModel") ChatModel openAiModel) {

        return switch (springAiProvider) {
            case OPENAI ->
                openAiModel;
            case BEDROCK ->
                bedrockModel;
        };
    }

    @Bean
    public OpenAiChatOptions openAiChatOptions(@Value("${spring.ai.openai.chat.options.model:gpt-5-nano}") String model) {
        var builder = OpenAiChatOptions.builder()
                .model(model)
                .parallelToolCalls(true)
                // We want cache to work across different Lambda IPs(AZ) and across regions
                .promptCacheKey("cloud-cleo-squareup-spring-ai")
                .N(1);  // We only ever want 1 response

        if (model.startsWith("gpt-4")) {
            // GPT-4 family still supports temp/topP (no reasoning)
            builder = builder
                    .temperature(0.2)
                    .topP(.9)
                    .maxTokens(100);
        } else if (model.startsWith("gpt-5")) {
            // GPT-5 family is just completion tokens, no temp or topP
            builder = builder
                    .reasoningEffort("low")  // need lowest latency response
                    .maxCompletionTokens(100);
        }

        return builder.build();
    }

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
                .temperature(.1)
                .topP(0.9)
                .maxTokens(100)
                .build();
    }

    @Bean(name = "bedrockChatModel")
    public ChatModel bedrockChatModel(
            @Qualifier("crtAsync") SdkAsyncHttpClient sdkAsyncHttpClient,
            @Qualifier("crt") SdkHttpClient sdkHttpClient,
            BedrockChatOptions options
    ) {

        return BedrockProxyChatModel.builder()
                .bedrockRuntimeClient(BedrockRuntimeClient.builder().httpClient(sdkHttpClient).build())
                .bedrockRuntimeAsyncClient(BedrockRuntimeAsyncClient.builder().httpClient(sdkAsyncHttpClient).build())
                .defaultOptions(options)
                .build();
    }

    @Bean
    public ChatMemoryRepository chatMemoryRepository(DynamoDbEnhancedClient enhancedClient, JsonMapper objectMapper,
            @Value("${chat.memory.dynamo.ttl:24h}") Duration ttlDuration,
            @Value("${chat.memory.dynamo.table-name:spring-ai-chat-memory}") String tableName
    ) {
        return new DynamoDbChatMemoryRepository(enhancedClient, objectMapper,
                ttlDuration,
                tableName);
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(50)
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

//      @Bean
//    public OpenAiSdkChatOptions openAiSdkChatOptions(
//            @Value("${spring.ai.openai-sdk.chat.options.model:gpt-5-nano}") String model,
//            @Value("${spring.ai.openai-sdk.api-key}") String api_key
//    ) {
//
//        var builder = OpenAiSdkChatOptions.builder()
//                .apiKey(api_key)
//                .model(model)
//                .parallelToolCalls(true)
//                .serviceTier("priority")
//                .N(1);
//
//        // IMPORTANT: do NOT set temperature for GPT-5 models, Spring AI will pass it
//        if (model.startsWith("gpt-4")) {
//            builder = builder.temperature(0.2);
//        }
//
//        return builder.build();
//    }
//
//    @Bean(name = "customOpenAiSdkChatModel")
//    public ChatModel chatModelOpenAiSDK(OpenAiSdkChatOptions options) {
//        return new OpenAiSdkChatModel(options);
//    }
}
