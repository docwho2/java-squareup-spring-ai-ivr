package cloud.cleo.squareup.tools;


import cloud.cleo.squareup.LexV2EventWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Driving directions when the user is interacting via a voice interface.
 * Sends the caller a URL with driving directions via SMS.
 */
@Component
@RequiredArgsConstructor
public class DrivingDirectionsVoice extends DrivingDirections {

    private final SnsClient snsClient;
    
    @Tool(
        name = DRIVING_DIRECTIONS_VOICE_FUNCTION_NAME,
        description = """
            Sends the caller an SMS containing a URL with driving directions \
            to the store. Use this only for voice calls when the caller is \
            using a valid US mobile number.
            """
    )
    public StatusMessageResult sendDrivingDirectionsVoice(ToolContext ctx) {
        LexV2EventWrapper event = getEventWrapper(ctx);
        if (event == null) {
            return new StatusMessageResult(
                    "FAILED",
                    "No event context is available; cannot determine caller phone number."
            );
        }

        return sendSMS(snsClient,event, DRIVING_DIRECTIONS_URL);
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        // Voice only, like original isVoice() = true / isText() = false.
        return event.isVoice();
    }

}
