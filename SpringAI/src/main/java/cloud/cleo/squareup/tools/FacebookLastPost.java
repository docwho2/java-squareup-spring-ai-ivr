package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.service.FaceBookService;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.service.FaceBookService.FbPost;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Get the most recent Facebook post for the Store.
 */
@Component
@RequiredArgsConstructor
public class FacebookLastPost extends AbstractTool {

    private final FaceBookService faceBookOperations;

    @Tool(
            name = FACEBOOK_MOST_RECENT_POST_FUNCTION_NAME,
            description = """
    Retrieves the single most recent Facebook post published by the business page.
    
    This tool should be used when the user asks about current sales, promotions,
    store events, announcements, or "what's going on right now" at the store.
    
    Only the latest post is returned to avoid confusion with older or expired
    promotions. The assistant must not summarize older Facebook content or guess.
    
    Use this tool instead of knowledge retrieval when the question is specifically
    about the most up-to-date Facebook announcement.
    """
    )
    public FbPost facebookHandover(ToolContext ctx) {
        final var event = getEventWrapper(ctx);

        // Set the action so we short circut and send back card with response
        event.putSessionAttributeAction(FACEBOOK_HANDOVER_FUNCTION_NAME);

        // Anything the user types now will go to general inbox and staff will see
        var post = faceBookOperations.fetchLatestPost();
        if (post.isPresent()) {
            return post.get();
        } else {
            return new FbPost("", "No Current Post is available", "", "");
        }
    }

    /**
     * Available if FB has page ID and token setup
     */
    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return faceBookOperations.isEnabled();
    }

}
