package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import static cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status.FAILED;
import static cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status.SUCCESS;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.AccessLevel;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.InitializingBean;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Base for all Tools.
 *
 * @author sjensen
 */
@Log4j2(access = AccessLevel.PROTECTED)
public abstract class AbstractTool implements InitializingBean {

    public final static String TRANSFER_FUNCTION_NAME = "transfer_call";
    public final static String HANGUP_FUNCTION_NAME = "hangup_call";
    public final static String FACEBOOK_HANDOVER_FUNCTION_NAME = "send_to_facebook_inbox";
    public final static String SWITCH_LANGUAGE_FUNCTION_NAME = "switch_language";
    public final static String DRIVING_DIRECTIONS_VOICE_FUNCTION_NAME = "send_driving_directions";
    public final static String SEND_EMAIL_FUNCTION_NAME = "send_email_message";
    public final static String CITY_SEARCH_FUNCTION_NAME = "local_wahkon_knowledge";
    public final static URI WEBSITE_URL = URI.create("https://CopperFoxGifts.com");
    public final static URI PRIVATE_SHOPPING_URL = WEBSITE_URL.resolve("book");
    public final static String PRIVATE_SHOPPING_VOICE_FUNCTION_NAME = "send_private_shopping_url";

    /**
     * URL for driving directions with Place ID so it comes up as Copper Fox properly for the pin.
     */
    public static final URI DRIVING_DIRECTIONS_URL
            = URI.create("https://google.com/maps/dir/?api=1&destination=160+Main+St+Wahkon+MN+56386&destination_place_id=ChIJWxVcpjffs1IRcSX7D8pJSUY");

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
        String[] tokens = input.trim().split("\\s+");

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
                    "The SMS message was successfully sent to the caller"
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

        List<String> missing = new ArrayList<>();

        for (RequiredFieldMeta meta : getRequiredFields(request.getClass())) {
            Field field = meta.field();
            String displayName = meta.jsonName();

            Object value;
            try {
                value = field.get(request);
            } catch (IllegalAccessException e) {
                // If we somehow can't read it, treat as missing
                missing.add(displayName);
                continue;
            }

            if (value == null) {
                missing.add(displayName);
            } else if (value instanceof String s && s.isBlank()) {
                missing.add(displayName);
            }
        }

        if (!missing.isEmpty()) {
            String msg = "Missing or empty required fields: " + String.join(", ", missing);
            return logAndReturnError(msg);
        }

        return null; // no errors
    }

    private record RequiredFieldMeta(Field field, String jsonName) {

    }

    private static final Map<Class<?>, List<RequiredFieldMeta>> REQUIRED_FIELDS_CACHE = new ConcurrentHashMap<>();

    protected static List<RequiredFieldMeta> getRequiredFields(Class<?> clazz) {
        return REQUIRED_FIELDS_CACHE.computeIfAbsent(clazz, cls -> {
            var list = new ArrayList<RequiredFieldMeta>();

            for (Field f : cls.getDeclaredFields()) {
                JsonProperty jp = f.getAnnotation(JsonProperty.class);
                if (jp != null && jp.required()) {
                    f.setAccessible(true);

                    // Compute display name once
                    final String jsonName = (jp.value() != null && !jp.value().isBlank())
                            ? jp.value()
                            : f.getName();

                    list.add(new RequiredFieldMeta(f, jsonName));
                }
            }

            return Collections.unmodifiableList(list);
        });
    }

    protected Class<?> requestPayloadType() {
        return null; // default: no payload / no priming
    }

    @Override
    public void afterPropertiesSet() {
        Class<?> type = requestPayloadType();
        if (type != null) {
            // prime cache at bean creation time for DTO-based tools
            log.debug("Priming cache for class [{}]", type.toString());
            getRequiredFields(type);
        }
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
    public record UrlResult(URI url) {

    }

    protected StatusMessageResult logAndReturnSuccess(String mesg) {
        final var sm = new StatusMessageResult(SUCCESS, mesg);
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
