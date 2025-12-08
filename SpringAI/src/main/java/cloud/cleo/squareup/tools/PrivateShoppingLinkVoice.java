package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Sends the caller the direct URL to book private shopping via SMS (voice).
 */
@Component
@RequiredArgsConstructor
public class PrivateShoppingLinkVoice extends AbstractTool {

    private final SnsClient snsClient;

    @Tool(
        name = PRIVATE_SHOPPING_VOICE_FUNCTION_NAME,
        description = """
            Sends the caller an SMS containing the direct URL to book private shopping.
            """
    )
    public StatusMessageResult sendPrivateShoppingLinkVoice(ToolContext ctx) {
        LexV2EventWrapper event = getEventWrapper(ctx);

        // Reuse the shared SMS helper on AbstractTool
        return sendSMS(snsClient, event, PRIVATE_SHOPPING_URL.toString());
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        // Voice only
        return event.isVoice();
    }
}
