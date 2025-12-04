package cloud.cleo.squareup.tools;

import static cloud.cleo.squareup.enums.ChannelPlatform.FACEBOOK;

import cloud.cleo.squareup.service.FaceBookService;
import cloud.cleo.squareup.LexV2EventWrapper;
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

    private final FaceBookService faceBookOperations;
    
    @Tool(
        name = PRIVATE_SHOPPING_TEXT_FUNCTION_NAME,
        description = """
            Returns a URL for direct booking of private shopping. \
            Use this when a text user asks about private shopping \
            or wants to book a private appointment.
            """
    )
    public UrlResult getPrivateShoppingLinkText(ToolContext ctx) {
        LexV2EventWrapper event = getEventWrapper(ctx);

        // Preserve old behavior: for Facebook, persist as a menu choice
        if (event != null && event.getChannelPlatform().equals(FACEBOOK)) {
            faceBookOperations.addPrivateShoppingMenu(event.getSessionId());
        }

        return new UrlResult(PRIVATE_SHOPPING_URL);
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        // Text only
        return event.isText();
    }

   
}
