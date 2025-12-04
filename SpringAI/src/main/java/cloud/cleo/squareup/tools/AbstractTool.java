package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import static cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status.FAILED;
import static cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status.SUCCESS;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.model.ToolContext;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Base for all Tools.
 *
 * @author sjensen
 */
@Log4j2(access = AccessLevel.PROTECTED)
public abstract class AbstractTool {


    public final static String TRANSFER_FUNCTION_NAME = "transfer_call";
    public final static String HANGUP_FUNCTION_NAME = "hangup_call";
    public final static String FACEBOOK_HANDOVER_FUNCTION_NAME = "facebook_inbox";
    public final static String SWITCH_LANGUAGE_FUNCTION_NAME = "switch_language";
    public final static String DRIVING_DIRECTIONS_TEXT_FUNCTION_NAME = "driving_directions_text";
    public final static String DRIVING_DIRECTIONS_VOICE_FUNCTION_NAME = "driving_directions_voice";

    public final static String WEBSITE_URL = "CopperFoxGifts.com";
    public final static String PRIVATE_SHOPPING_URL = WEBSITE_URL + "/book";
    public final static String PRIVATE_SHOPPING_TEXT_FUNCTION_NAME = "private_shopping_url_text";
    public final static String PRIVATE_SHOPPING_VOICE_FUNCTION_NAME = "private_shopping_url_voice";

    /**
     * URL for driving directions with Place ID so it comes up as Copper Fox properly for the pin.
     */
    protected static final String DRIVING_DIRECTIONS_URL
            = "google.com/maps/dir/?api=1&destination=160+Main+St+Wahkon+MN+56386&destination_place_id=ChIJWxVcpjffs1IRcSX7D8pJSUY";

    /**
     * Get the wrapper from the tool Context
     *
     * @param ctx
     * @return
     */
    LexV2EventWrapper getEventWrapper(ToolContext ctx) {
        return (LexV2EventWrapper) ctx.getContext().get("eventWrapper");
    }

    /**
     * Given a String with several words, return all combinations of that in specific order for passing to searches.
     *
     * @param input
     * @return
     */
    protected final static List<String> allCombinations(String input) {
        String[] tokens = input.split(" ");

        // Generate combinations of the tokens
        List<String> combinations = new ArrayList<>();

        // Start with the full search term
        combinations.add(input);

        // Generate combinations from longest to shortest
        for (int length = tokens.length - 1; length > 0; length--) {
            for (int start = 0; start + length <= tokens.length; start++) {
                String combination = String.join(" ", Arrays.copyOfRange(tokens, start, start + length));
                combinations.add(combination);
            }
        }

        return combinations;
    }

    /**
     * Given an incoming LEX event, should this tool be included in the Chat Request. Some tools are not relevant for
     * certain channels (Like Voice vs Text).
     *
     * @param event
     * @return
     */
    public abstract boolean isValidForRequest(LexV2EventWrapper event);

    /**
     * Used to send SMS to the caller's cell phone. We will also validate the number as part of the request before
     * attempting send.
     *
     * @param snsClient
     * @param event event that contains the number to send the message to
     * @param message body to send
     * @return
     */
    public StatusMessageResult sendSMS(SnsClient snsClient, LexV2EventWrapper event, String message) {
        try {

            if (!event.hasValidUSE164Number()) {
                return logAndReturnError("Calling number is not a valid US phone number.");
            }

            if (!event.hasValidUSMobileNumber()) {
                return logAndReturnError(
                        "Caller is not calling from a mobile device."
                );
            }

            final var result = snsClient.publish(b -> b.phoneNumber(event.getPhoneE164()).message(message));
            log.info("SMS [" + message + "] sent to " + event.getPhoneE164() + " with SNS id of " + result.messageId());
            return logAndReturnSuccess(
                    "The SMS message was successfuly sent to the caller"
            );
        } catch (Exception e) {
            return logAndReturnError(
                    "An error has occurred, this function may be down", e
            );
        }

    }

    /**
     * Validates that all fields annotated with @JsonProperty(required = true) are present (non-null and, for Strings,
     * non-blank).
     *
     * @param request the request object coming from the model
     * @return a FAILED StatusMessageResult describing missing fields, or null if everything is valid.
     */
    protected StatusMessageResult validateRequiredFields(Object request) {
        if (request == null) {
            return logAndReturnError("Request object not present, please populate all required parameters.");
        }

        Class<?> clazz = request.getClass();
        List<String> missing = new ArrayList<>();

        for (Field field : clazz.getDeclaredFields()) {
            JsonProperty jp = field.getAnnotation(JsonProperty.class);
            if (jp != null && jp.required()) {
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(request);
                } catch (IllegalAccessException e) {
                    // If we can't read it, treat as missing
                    missing.add(resolveFieldName(field, jp));
                    continue;
                }

                if (value == null) {
                    missing.add(resolveFieldName(field, jp));
                } else if (value instanceof String s && s.isBlank()) {
                    missing.add(resolveFieldName(field, jp));
                }
            }
        }

        if (!missing.isEmpty()) {
            String msg = "Missing or empty required fields: " + String.join(", ", missing);
            return logAndReturnError(msg);
        }

        return null; // no errors
    }

    private String resolveFieldName(Field field, JsonProperty jp) {
        // Prefer the JSON name if provided, otherwise Java field name
        return (jp.value() != null && !jp.value().isBlank())
                ? jp.value()
                : field.getName();
    }

    /**
     * Simple result that will be used by many tools to report back whether something succeeded to failed with a message
     * describing the success or failure.
     */
    public record StatusMessageResult(Status status, String message) {

        public enum Status {
            SUCCESS,
            FAILED
        }
    }
    
    /**
     * Used when returning a single URL as result (Driving directions, Private Shopping, etc.
     * 
     */
    public record UrlResult(String url) {
        
    }

    
    protected StatusMessageResult logAndReturnSuccess(String mesg) {
        final var sm =  new StatusMessageResult(SUCCESS, mesg);
        log.info(sm);
        return sm;
    }
    
    protected StatusMessageResult logAndReturnError(Throwable ex) {
        return logAndReturnError(ex.getMessage(), ex);
    }

    protected StatusMessageResult logAndReturnError(String errorMesg) {
        return logAndReturnError(errorMesg, null);
    }

    protected StatusMessageResult logAndReturnError(String errorMessage, Throwable ex) {
        if (ex != null) {
            log.error(errorMessage, ex);
        } else {
            log.error(errorMessage);
        }
        return new StatusMessageResult(FAILED, errorMessage);
    }

}
