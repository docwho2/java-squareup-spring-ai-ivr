package cloud.cleo.squareup.cloudfunctions;

import cloud.cleo.squareup.ClearChatMemory;
import cloud.cleo.squareup.LexV2Event;
import cloud.cleo.squareup.LexV2Event.Intent;
import cloud.cleo.squareup.LexV2Event.SessionState;
import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.LexV2Response;
import cloud.cleo.squareup.LexV2Response.ImageResponseCard;
import static cloud.cleo.squareup.enums.LexDialogAction.Close;
import static cloud.cleo.squareup.enums.LexDialogAction.ElicitIntent;
import static cloud.cleo.squareup.enums.LexMessageContentType.ImageResponseCard;
import static cloud.cleo.squareup.enums.LexMessageContentType.PlainText;
import cloud.cleo.squareup.lang.LangUtil;
import cloud.cleo.squareup.tools.AbstractTool;
import static cloud.cleo.squareup.tools.AbstractTool.HANGUP_FUNCTION_NAME;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
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

    private static final Pattern THINKING_PATTERN
            = Pattern.compile("<thinking>.*?</thinking>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final ClearChatMemory clearChatMemory;
    private final List<AbstractTool> tools;

    @Override
    public LexV2Response apply(LexV2Event lexRequest) {
        final var eventWrapper = new LexV2EventWrapper(lexRequest);

        final String sessionId = lexRequest.getSessionId();

        // Handle case where there is no input (Caller Silence not saying anything)
        if (eventWrapper.getBlankCounter() > 2 && eventWrapper.isVoice()) {
            // Call is just hanging there with nothing said so hang up
            return buildTerminatingResponse(Map.of("action", HANGUP_FUNCTION_NAME, "bot_response", eventWrapper.getLangString(LangUtil.LanguageIds.GOODBYE)));
        } else if (eventWrapper.getBlankCounter() > 0) {
            // Don't bother calling Model, no input
            return buildResponse(eventWrapper, eventWrapper.getLangString(LangUtil.LanguageIds.BLANK_RESPONSE));
        }

       
        try {
            final CallResponseSpec chatCall = chatClient.prompt()
                    .system(eventWrapper.getSystemPrompt())
                    .user(lexRequest.getInputTranscript())
                    // Use Lex Session ID for the conversation ID for Chat Memory
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .toolContext(Map.of("eventWrapper", eventWrapper))
                    .tools(tools.stream().filter(t -> t.isValidForRequest(eventWrapper)).toArray())
                    .call();

            final ChatResponse resp = chatCall.chatResponse();     // <-- single terminal call
            String botResponse = resp.getResult().getOutput().getText();
            log.debug("Bot Text Response is: " + botResponse);
            final String model = getModel(resp);               // reuse the same response

            // We now need to determine if we should end Lex session for Chime to take back control
            if (eventWrapper.hasSessionAttributeAction()) {
                // The only FB action is to stop the Bot and transfer conversation to Inbox
                if ( eventWrapper.isFacebook() ) {
                    return buildResponse(eventWrapper, "CopperBot will be removed from this conversation after clicking below.", buildTransferCard());
                } else {
                    // Since not FB, this will be for Voice calls to take action on the call (Hangup, Language Change, Transfer,etc.)
                    eventWrapper.putSessionAttributeBotResponse(sanitizeOutput(botResponse));
                    return buildTerminatingResponse(eventWrapper.getSessionAttributes());
                }
            } else {
                if (eventWrapper.isNewSession() && eventWrapper.isFacebook()) {
                    // If this a new Session send back a Welcome card for Facebook Channel
                    // This works for Twilio/SMS, but sends a MMS and costs more money (it sends logo, but of course doesn't support the buttons)
                    return buildResponse(eventWrapper, botResponse, buildWelcomeCard());
                } else {
                    // Just a normal turn 
                    return buildResponse(eventWrapper, sanitizeOutput(botResponse));
                }
            }
        } catch (Exception e) {
            // Spring AI / Bedrock will throw this for unsupported media like application/zip
            if (e instanceof IllegalArgumentException) {
                log.warn("Unsupported media from Lex attachment", e);
                return buildResponse(eventWrapper, "You have attached an unsupported media type, please try another file type.");
            } else {
                log.error(e);
                return buildResponse(eventWrapper, e.getMessage());
            }
        }
    }

    public static String sanitizeOutput(String txt) {
        return THINKING_PATTERN.matcher(txt).replaceAll("").trim();
    }

    /**
     * Tell Lex we are done so Chime can process terminating the Bot and hanging
     * up or switching language or transferring the call for example.
     *
     * @param attrs
     * @return
     */
    private LexV2Response buildTerminatingResponse(Map<String, String> attrs) {

        // State to return
        final var ss = SessionState.builder()
                // Send all Session Attrs
                .withSessionAttributes(attrs)
                // We are always using Fallback, and let Lex know everything is fulfilled
                .withIntent(Intent.builder().withName("FallbackIntent").withState("Fulfilled").build())
                // Indicate we are closing things up, IE we are done here
                .withDialogAction(Close.getDialogAction())
                .build();

        final var lexV2Res = LexV2Response.builder()
                .withSessionState(ss)
                .build();
        log.debug("Lex Response is {}", lexV2Res);
        return lexV2Res;
    }

    /**
     * General Response used to send back a message and Elicit Intent again at
     * LEX. IE, we are sending back GPT response, and then waiting for Lex to
     * collect speech and once again call us so we can send to GPT, effectively
     * looping until we call a terminating event like Quit or Transfer.
     *
     * @param lexRequest
     * @param response
     * @return
     */
    private LexV2Response buildResponse(LexV2EventWrapper lexRequest, String response, ImageResponseCard card) {

        final var messages = new ArrayList<LexV2Response.Message>(card != null ? 2 : 1);

        // Always send a plain text response
        //  If this is not first in the list, Lex will error
        messages.add(LexV2Response.Message.builder()
                .withContentType(PlainText)
                .withContent(response)
                .build());

        if (card != null) {
            // Add a card if present
            messages.add(LexV2Response.Message.builder()
                    .withContentType(ImageResponseCard)
                    .withImageResponseCard(card)
                    .build());
        }

        // State to return
        final var ss = SessionState.builder()
                // Retain the current session attributes
                .withSessionAttributes(lexRequest.getSessionAttributes())
                // Always ElictIntent, so you're back at the LEX Bot looking for more input
                .withDialogAction(ElicitIntent.getDialogAction())
                .build();

        final var lexV2Res = LexV2Response.builder()
                .withSessionState(ss)
                // List of messages to send back
                .withMessages(messages.toArray(LexV2Response.Message[]::new))
                .build();
        log.debug("Response is " + lexV2Res);
        return lexV2Res;
    }

    /**
     * Send a response without a card.
     *
     * @param lexRequest
     * @param response
     * @return
     */
    protected LexV2Response buildResponse(LexV2EventWrapper lexRequest, String response) {
        return buildResponse(lexRequest, response, null);
    }

    /**
     * Welcome card which would be displayed for FaceBook Users in Messenger.
     *
     * @return
     */
    private LexV2Response.ImageResponseCard buildWelcomeCard() {
        return LexV2Response.ImageResponseCard.builder()
                .withTitle("Welcome to Copper Fox Gifts")
                .withImageUrl("https://www.copperfoxgifts.com/logo.png")
                .withSubtitle("Ask us anything or use a quick action below")
                // Messenger will only display 3 buttons
                .withButtons(List.of(
                        LexV2Response.Button.builder().withText("Hours").withValue("What are you business hours?").build(),
                        //Button.builder().withText("Location").withValue("What is your address and driving directions?").build(),
                        LexV2Response.Button.builder().withText("Person").withValue("Please hand this conversation over to a person").build(),
                        LexV2Response.Button.builder().withText("Private Shopping").withValue("Info about Private Shopping and link").build()
                ).toArray(LexV2Response.Button[]::new))
                .build();
    }

    /**
     * Transfer from Bot to Inbox Card for Facebook Messenger.
     *
     * @return
     */
    private LexV2Response.ImageResponseCard buildTransferCard() {
        return LexV2Response.ImageResponseCard.builder()
                .withTitle("Conversation will move to Inbox")
                .withImageUrl("https://www.copperfoxgifts.com/logo.png")
                .withSubtitle("Please tell us how Copper Bot did?")
                .withButtons(List.of(
                        LexV2Response.Button.builder().withText("Epic Fail").withValue("Chatbot was not Helpful.").build(),
                        LexV2Response.Button.builder().withText("Needs Work").withValue("Chatbot needs some work.").build(),
                        LexV2Response.Button.builder().withText("Great Job!").withValue("Chatbot did a great job!").build()
                ).toArray(LexV2Response.Button[]::new))
                .build();
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
