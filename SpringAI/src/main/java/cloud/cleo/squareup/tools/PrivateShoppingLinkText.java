package cloud.cleo.squareup.tools;

import static cloud.cleo.squareup.enums.ChannelPlatform.FACEBOOK;

import cloud.cleo.squareup.FaceBookOperations;
import cloud.cleo.squareup.LexV2EventWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Returns a URL for direct booking of Private Shopping (text interface).
 */
@Component
@RequiredArgsConstructor
public class PrivateShoppingLinkText extends AbstractTool {

    private final FaceBookOperations faceBookOperations;
    
    @Tool(
        name = PRIVATE_SHOPPING_TEXT_FUNCTION_NAME,
        description = """
            Returns a URL for direct booking of private shopping. \
            Use this when a text user asks about private shopping \
            or wants to book a private appointment.
            """
    )
    public PrivateShoppingLinkTextResult getPrivateShoppingLinkText(ToolContext ctx) {
        LexV2EventWrapper event = getEventWrapper(ctx);

        // Preserve old behavior: for Facebook, persist as a menu choice
        if (event != null && event.getChannelPlatform().equals(FACEBOOK)) {
            faceBookOperations.addPrivateShoppingMenu(event.getSessionId());
        }

        return new PrivateShoppingLinkTextResult(PRIVATE_SHOPPING_URL);
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        // Text only
        return event.isText();
    }

    public record PrivateShoppingLinkTextResult(
            @JsonProperty("url")
            String url
    ) { }
}
