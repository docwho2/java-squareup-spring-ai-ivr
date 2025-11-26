package cloud.cleo.squareup.cloudfunctions;

import cloud.cleo.squareup.ClearChatMemory;
import cloud.cleo.squareup.LexV2Event;
import cloud.cleo.squareup.LexV2Response;
import static cloud.cleo.squareup.enums.LexMessageContentType.PlainText;
import cloud.cleo.squareup.tools.ChatBotTool;


import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

import org.springframework.stereotype.Component;

/**
 *
 * @author sjensen
 */
@Component
@RequiredArgsConstructor
public class LexFunction implements Function<LexV2Event, LexV2Response> {

    // Initialize the Log4j logger
    public static final Logger log = LogManager.getLogger(LexFunction.class);


    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final ClearChatMemory clearChatMemory;
    private final List<ChatBotTool> tools;


    @Override
    public LexV2Response apply(LexV2Event lexRequest) {
        final String sessionId = lexRequest.getSessionId();

        if ("Quit".equals(lexRequest.getSessionState().getIntent().getName())) {
            log.debug("Quit called");
            clearChatMemory.clearMemory(lexRequest.getSessionId());
            //userContextCache.remove(sessionId);
            return buildQuitResponse(lexRequest, null);
        }

        

        String systemText = """
    I am interacting as a user of Telephone Timesheets, a time tracking system for employees.
    Please be a helpful and friendly support assistant.
    Do not display employee_id, supervisor_id, job_id fields from function calling and always display the start_date and end_date.
    My first name is Steve. I am a supervisor and my supervisor_id is 1.
        Very important:
            - Keep responses short and focused.
            - Never exceed 1,000 characters in your response.
            - Prefer summaries, bullet points, or step-by-step instructions.
            - Only include details that directly answer the user's question.
                            
        Avoid:
            - Long explanations.
            - Rambling or repeating yourself.
            - Large code blocks or large data dumps.
                            
        If more detail is needed:
            - Provide a short summary.
            - Offer to explain more only if the user asks.                        
    """;

        
        try {
            final CallResponseSpec chatCall = chatClient.prompt()
                    .system(systemText)
                    .user(lexRequest.getInputTranscript())
                    // Use Lex Session ID for the conversation ID for Chat Memory
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .toolContext(Map.of("some","thing"))
                    .tools(tools.toArray())
                    .call();

            final ChatResponse resp = chatCall.chatResponse();     // <-- single terminal call
            String botResponse = resp.getResult().getOutput().getText();
            log.debug("Bot Text Response is: " + botResponse);
            final String model = getModel(resp);               // reuse the same response

       

            return buildResponse(lexRequest, botResponse);
        } catch (Exception e) {
            // Spring AI / Bedrock will throw this for unsupported media like application/zip
            if (e instanceof IllegalArgumentException) {
                log.warn("Unsupported media from Lex attachment", e);
                return buildResponse(lexRequest, "You have attached an unsupported media type, please try another file type.");
            } else {
                log.error(e);
                return buildResponse(lexRequest, e.getMessage());
            }
        }
    }

    


 


    /**
     * Response that sends you to the Quit intent so the call can be ended
     *
     * @param lexRequest
     * @param response
     * @return
     */
    private LexV2Response buildQuitResponse(LexV2Event lexRequest, String response) {

        // State to return
        final var ss = LexV2Event.SessionState.builder()
                // Retain the current session attributes
                .withSessionAttributes(lexRequest.getSessionState().getSessionAttributes())
                // Send back Quit Intent
                .withIntent(LexV2Event.Intent.builder().withName("Quit").withState("Fulfilled").build())
                // Indicate the state is Delegate
                .withDialogAction(LexV2Event.DialogAction.builder().withType("Close").build())
                .build();

        final var lexV2Res = LexV2Response.builder()
                .withSessionState(ss)
                .withMessages(new LexV2Response.Message[]{new LexV2Response.Message(PlainText,
            response == null ? "Session Closed, Thank You" : response, null)})
                .build();
        //log.debug("Response is " + mapper.valueToTree(lexV2Res));
        return lexV2Res;
    }

    /**
     * General Response used to send back a message and Elicit Intent again at LEX
     *
     * @param lexRequest
     * @param response
     * @return
     */
    private LexV2Response buildResponse(LexV2Event lexRequest, String response) {

        // State to return
        final var ss = LexV2Event.SessionState.builder()
                // Retain the current session attributes
                .withSessionAttributes(lexRequest.getSessionState().getSessionAttributes())
                // Always ElictIntent, so you're back at the LEX Bot looking for more input
                .withDialogAction(LexV2Event.DialogAction.builder().withType("ElicitIntent").build())
                .build();

        final var lexV2Res = LexV2Response.builder()
                .withSessionState(ss)
                // We are using plain text responses
                .withMessages(new LexV2Response.Message[]{new LexV2Response.Message(PlainText, response, null)})
                .build();
        //log.debug("Response is " + mapper.valueToTree(lexV2Res));
        return lexV2Res;
    }

    /**
     * Obtain the model used from the response if possible.
     *
     * @param resp
     * @return
     */
    private String getModel(ChatResponse resp) {
        // 1) Prefer the configured model on the active ChatModel (works for Bedrock + OpenAI)
        try {
            String configured = Optional.ofNullable(chatModel)
                    .map(ChatModel::getDefaultOptions)
                    .map(ChatOptions::getModel)
                    .orElse(null);
            if (notBlank(configured)) {
                return configured;
            }
        } catch (Exception e) {
            log.debug("getModel(): unable to read model from ChatModel options", e);
        }

        // 2) Provider/response metadata (may be empty for Bedrock)
        String fromResp = extractModelFromMetadata(resp != null ? resp.getMetadata() : null);
        if (notBlank(fromResp)) {
            return fromResp;
        }

        // 3) Some providers stash metadata on each result/output
        if (resp != null && resp.getResults() != null) {
            for (var r : resp.getResults()) {
                try {
                    var out = r.getOutput();
                    String fromOut = extractModelFromMetadata(out != null ? out.getMetadata() : null);
                    if (notBlank(fromOut)) {
                        return fromOut;
                    }
                } catch (Exception ignore) {
                    /* no output metadata */ }
            }
        }

        // 4) Last resort
        return "unknown";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @SuppressWarnings("unchecked")
    private String extractModelFromMetadata(Object md) {
        if (md == null) {
            return null;
        }

        // Helpful debug once while tuning (disable after you confirm)
        log.debug("metadata class={}, value={}", md.getClass().getName(), md);

        // A) Map-like metadata
        if (md instanceof Map<?, ?> map) {
            for (String key : List.of(
                    "model", "modelId", "model_id", "bedrockModelId", "providerModelId", "modelName")) {
                Object v = map.get(key);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v);
                }
            }
        }

        // B) POJO: try common getters via reflection
        for (String getter : List.of(
                "getModel", "getModelId", "modelId", "getBedrockModelId", "getModelName")) {
            try {
                Method m = md.getClass().getMethod(getter);
                Object v = m.invoke(md);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v);
                }
            } catch (ReflectiveOperationException ignore) {
            }
        }

        // C) Fields (some metadata objects are simple records)
        for (String field : List.of("model", "modelId", "model_id", "bedrockModelId", "providerModelId")) {
            try {
                Field f = md.getClass().getDeclaredField(field);
                f.setAccessible(true);
                Object v = f.get(md);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v);
                }
            } catch (ReflectiveOperationException ignore) {
            }
        }

        return null;
    }

}
