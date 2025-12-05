package cloud.cleo.squareup.cloudfunctions;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;

import org.springframework.stereotype.Component;

/**
 *
 * @author sjensen
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class LexFunction implements Function<LexV2Event, LexV2Response> {

    private final ChatClient chatClient;
    private final List<AbstractTool> tools;

    @Override
    public LexV2Response apply(LexV2Event lexRequest) {
        final var eventWrapper = new LexV2EventWrapper(lexRequest);

        // Handle case where there is no input (Caller Silence not saying anything)
        if (eventWrapper.getBlankCounter() > 2 && eventWrapper.isVoice()) {
            // Call is just hanging there with nothing said over 2 times, so hang up and stop calling model with 'blank'
            return buildTerminatingResponse(Map.of("action", HANGUP_FUNCTION_NAME, "bot_response", eventWrapper.getLangString(LangUtil.LanguageIds.GOODBYE)));
        }

        try {
            final CallResponseSpec chatCall = chatClient.prompt()
                    .system(eventWrapper.getSystemPrompt())
                    .user(eventWrapper.getInputTranscript())
                    // Use Lex Session ID for the conversation ID for Chat Memory
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, eventWrapper.getChatMemorySessionId()))
                    .toolContext(Map.of("eventWrapper", eventWrapper))
                    .tools(tools.stream().filter(t -> t.isValidForRequest(eventWrapper)).toArray())
                    .call();

            final ChatResponse resp = chatCall.chatResponse();     // <-- single terminal call
            String botResponse = resp.getResult().getOutput().getText();
            log.debug("Raw Bot Text Response is: {}", botResponse);
            botResponse = sanitizeAssistantText(botResponse);
            log.debug("Sanitized Bot Text Response is: {}", botResponse);

            // We now need to determine if we should end Lex session for Chime to take back control
            if (eventWrapper.hasSessionAttributeAction()) {
                // The only FB action is to stop the Bot and transfer conversation to Inbox
                if (eventWrapper.isFacebook()) {
                    return buildResponse(eventWrapper, botResponse, buildTransferCard());
                } else {
                    // Since not FB, this will be for Voice calls to take action on the call (Hangup, Language Change, Transfer,etc.)
                    eventWrapper.putSessionAttributeBotResponse(botResponse);
                    return buildTerminatingResponse(eventWrapper.getSessionAttributes());
                }
            } else {
                if (eventWrapper.isNewSession() && eventWrapper.isFacebook()) {
                    // If this a new Session send back a Welcome card for Facebook Channel
                    // This works for Twilio/SMS, but sends a MMS and costs more money (it sends logo, but of course doesn't support the buttons)
                    return buildResponse(eventWrapper, botResponse, buildWelcomeCard());
                } else {
                    // Just a normal turn 
                    return buildResponse(eventWrapper, botResponse);
                }
            }
        } catch (Exception e) {
            log.error(e);
            return buildResponse(eventWrapper, eventWrapper.getLangString(LangUtil.LanguageIds.UNHANDLED_EXCEPTION));
        }
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
                .withMessages(messages)
                .build();
        log.debug("Lex Response is {}", lexV2Res);
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
                ))
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
                ))
                .build();
    }

    private static final Pattern RESPONSE_BLOCK_PATTERN
            = Pattern.compile("(?is)<response>(.*?)</response>");

    private static final Pattern THINKING_BLOCK_PATTERN
            = Pattern.compile("(?is)<thinking>(.*?)</thinking>");

    public static String sanitizeAssistantText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String original = text;

        // 1) If there's a <response> block, prefer that content.
        Matcher responseMatcher = RESPONSE_BLOCK_PATTERN.matcher(text);
        if (responseMatcher.find()) {
            String inside = responseMatcher.group(1);

            // Strip any thinking that might be inside the response block
            inside = THINKING_BLOCK_PATTERN.matcher(inside).replaceAll("");
            inside = inside.trim();

            if (!inside.isBlank()) {
                return inside;
            }
            // fall through if somehow empty
        }

        // 2) No usable <response>. Strip thinking and see what's left.
        String withoutThinking = THINKING_BLOCK_PATTERN.matcher(text)
                .replaceAll("")
                .trim();

        if (!withoutThinking.isBlank()) {
            return withoutThinking;
        }

        // 3) If we got here, it was *only* thinking. Return the thinking content.
        Matcher thinkingMatcher = THINKING_BLOCK_PATTERN.matcher(text);
        if (thinkingMatcher.find()) {
            String thinkingContent = thinkingMatcher.group(1).trim();
            if (!thinkingContent.isBlank()) {
                return thinkingContent;
            }
        }

        // 4) Last resort – never return completely blank.
        return original.trim();
    }

}
