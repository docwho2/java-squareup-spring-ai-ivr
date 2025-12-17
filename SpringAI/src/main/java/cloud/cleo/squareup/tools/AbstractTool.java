package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import static cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status.FAILED;
import static cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status.SUCCESS;
import java.net.URI;
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
 *
 * TODO – Tool Parameter Validation Refactor (Post–Spring AI 2.x Maturity)
 *
 * Current state: - Tool inputs are a mix of: - DTO-based request objects (Theo-era pattern) - Flattened method
 * parameters using @ToolParam - Required-field validation is centralized in AbstractTool using
 *
 * @JsonProperty(required = true) + reflection + caching. - This works well for DTO-based tools but is not compatible
 * with flattened tool parameters.
 *
 * Planned refactor: 1. Introduce a custom annotation for required tool parameters:
 * @RequiredParam(name = "...")
 *
 * 2. Add an AOP interceptor (@Aspect) around methods annotated with @Tool that: - Inspects method parameters for
 * @RequiredParam - Validates null / blank values - Returns StatusMessageResult(FAILED, "...") when required params are
 * missing - Caches per-method metadata (similar to REQUIRED_FIELDS_CACHE today)
 *
 * 3. Migrate tools incrementally: - Flatten simple tools (1–3 params) to method parameters - Use @ToolParam for
 * model-visible schema metadata - Use @RequiredParam for runtime validation - Keep ToolContext (and other framework
 * context) as the last parameter
 *
 * 4. Retain DTO-based tools temporarily where: - Many parameters exist - Nested structures are required - Centralized
 * DTO validation is still valuable
 *
 * 5. When Spring AI provides: - Native per-parameter validation hooks, OR - A tool-specific @Param/@Required annotation
 * reassess whether the custom @RequiredParam + Aspect is still needed.
 *
 * Rationale: - Models frequently omit required fields despite schema hints - Validation must remain centralized and
 * cheap (cached reflection) - Flattened tools produce cleaner schemas and better tool-call accuracy - This decouples
 * validation from Jackson annotations and JSON DTOs - Future-proofs the tool layer for Jackson 2 → 3 and Spring AI
 * evolution
 *
 * @author sjensen
 */
@Log4j2(access = AccessLevel.PROTECTED)  // Allow all tools to use common logger here if desired
public abstract class AbstractTool {

    public final static String CTX_EVENT_WRAPPER = "eventWrapper";
    public final static String TRANSFER_FUNCTION_NAME = "transfer_call";
    public final static String HANGUP_FUNCTION_NAME = "hangup_call";
    public final static String FACEBOOK_HANDOVER_FUNCTION_NAME = "send_to_facebook_inbox";
    public final static String FACEBOOK_MOST_RECENT_POST_FUNCTION_NAME = "facebook_most_recent_post";
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
        // Always placeed in the Tool Context for every request and never null
        return (LexV2EventWrapper) ctx.getContext().get(CTX_EVENT_WRAPPER);
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
