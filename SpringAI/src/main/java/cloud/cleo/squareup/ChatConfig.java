package cloud.cleo.squareup;



import java.util.List;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 *
 * @author sjensen
 */
@Configuration
public class ChatConfig {

    @Bean
    OpenAiApi openAiApi(@Value("${spring.ai.openai.api-key}") String apiKey) {
        return OpenAiApi.builder()
                .apiKey(apiKey)
                .build();
    }

    @Bean
    public OpenAiChatOptions openAiChatOptions(@Value("${spring.ai.openai.chat.options.model}") String model) {
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
    public BedrockRuntimeClient bedrockRuntimeClient() {
        // Uses Lambda/EC2 role credentials automatically
        return BedrockRuntimeClient.builder()
                .httpClient(AwsCrtHttpClient.create()) 
                .build();
    }
    
    
    /**
     * Not used since not streaming responses as the moment.
     * 
     * @return 
     */
    public BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient() {
    return BedrockRuntimeAsyncClient.builder()
            .httpClient(AwsCrtAsyncHttpClient.create())
            .build();
}

    @Bean
    public BedrockChatOptions bedrockChatOptions() {
        return BedrockChatOptions.builder()
                .model("amazon.nova-lite-v1:0")
                .build();
    }

  
    @Primary
    @Bean(name = "bedrockChatModel")
    public ChatModel bedrockChatModel(BedrockRuntimeClient client, BedrockChatOptions options) {
        return BedrockProxyChatModel.builder()
                .bedrockRuntimeClient(client)
                //.bedrockRuntimeAsyncClient(asyncClient)
                .defaultOptions(options)
                .build();
    }

    @Bean
    @Primary
    public ChatClient chatClient(ChatModel model, ChatMemory memory, List<ChatBotTool> toolBeans) {
        return ChatClient.builder(model)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory).build(),
                        // call advisor LAST so the chain actually invokes the model
                        ChatModelCallAdvisor.builder().chatModel(model).build(),
                        new SimpleLoggerAdvisor()
                )
                .defaultTools(toolBeans.toArray())
                .build();
    }
}
