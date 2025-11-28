package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import com.squareup.square.AsyncSquareClient;
import com.squareup.square.core.Environment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.chat.model.ToolContext;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Base for all Tools.
 *
 * @author sjensen
 */
public abstract class AbstractTool {

    // Initialize the Log4j logger.
    protected static final Logger log = LogManager.getLogger(AbstractTool.class);

    @Getter
    private final static boolean squareEnabled;
    @Getter
    private final static AsyncSquareClient squareClient;

    protected static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

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

    static {
        final var key = System.getenv("SQUARE_API_KEY");
        final var loc = System.getenv("SQUARE_LOCATION_ID");
        final var senv = System.getenv("SQUARE_ENVIRONMENT");

        squareEnabled = !((loc == null || loc.isBlank() || loc.equalsIgnoreCase("DISABLED")) || (key == null || key.isBlank() || key.equalsIgnoreCase("DISABLED")));
        //log.debug("Square Enabled = " + squareEnabled);

        // If square enabled, then configure the client
        if (squareEnabled) {
            squareClient = AsyncSquareClient.builder()
                    .token(key)
                    .environment(switch (senv) {
                default ->
                    Environment.PRODUCTION;
                case "SANDBOX", "sandbox" ->
                    Environment.SANDBOX;
            }).build();
        } else {
            squareClient = null;
        }
    }

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
     * Given a String with several words, return all combinations of that in
     * specific order for passing to searches.
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
     * Given an incoming LEX event, should this tool be included in the Chat
     * Request. Some tools are not relevant for certain channels (Like Voice vs
     * Text).
     *
     * @param event
     * @return
     */
    public abstract boolean isValidForRequest(LexV2EventWrapper event);

    /**
     * Used to send SMS to the caller's cell phone. We will also validate the
     * number as part of the request before attempting send.
     *
     * @param snsClient
     * @param event event that contains the number to send the message to
     * @param message body to send
     * @return
     */
    public static StatusMessageResult sendSMS(SnsClient snsClient, LexV2EventWrapper event, String message) {
        try {

            if (!event.hasValidUSE164Number()) {
                return new StatusMessageResult(
                        "FAILED",
                        "Calling number is not a valid US phone number."
                );
            }

            if (!event.hasValidUSMobileNumber()) {
                return new StatusMessageResult(
                        "FAILED",
                        "Caller is not calling from a mobile device."
                );
            }

            final var result = snsClient.publish(b -> b.phoneNumber(event.getPhoneE164()).message(message));
            log.info("SMS [" + message + "] sent to " + event.getPhoneE164() + " with SNS id of " + result.messageId());
            return new StatusMessageResult(
                    "SUCCESS",
                    "The SMS message was successfuly sent to the caller"
            );
        } catch (Exception e) {
            log.error("Could not send message via SMS to caller", e);
            return new StatusMessageResult(
                    "FAILED",
                    "An error has occurred, this function may be down"
            );
        }

    }

    /**
     * Simple result that will be used by many tools to report back whether
     * something succeeded to failed with a message describing the success or
     * failure.
     */
    public record StatusMessageResult(String status, String message) {

    }

}
