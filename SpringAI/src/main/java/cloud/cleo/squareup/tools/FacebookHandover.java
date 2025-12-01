package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.service.FaceBookService;

import cloud.cleo.squareup.LexV2EventWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * End bot session and pass control of the Facebook messaging thread to a human Inbox.
 */
@Component
@RequiredArgsConstructor
public class FacebookHandover extends AbstractTool {

    private final FaceBookService faceBookOperations;
    
    /**
     * Tool the model can call when it has decided a human should take over.
     * No request parameters needed.
     *
     * The caller (your Lex handler / Lambda) can treat this as a terminating action
     * and perform the actual Facebook handover protocol.
     * @param ctx
     * @return 
     */
    @Tool(
        name = FACEBOOK_HANDOVER_FUNCTION_NAME,
        description = """
            Should be called when the interaction requires a person to take over 
            the conversation on the Facebook channel. The assistant must NOT guess 
            and should only use this when it clearly cannot resolve the user's issue or
            the user asks to talk to a real person.
            """
    )
    public StatusMessageResult facebookHandover(ToolContext ctx) {
        final var event = getEventWrapper(ctx);
        
        // Set the action so we short circut and send back card with response
        event.putSessionAttributeAction(FACEBOOK_HANDOVER_FUNCTION_NAME);
        
        // Anything the user types now will go to general inbox and staff will see
        faceBookOperations.transferToInbox(event.getSessionId());

        return new StatusMessageResult(
                "SUCCESS",
                "Conversation has been moved to the Inbox, a person will respond shortly."
        );
    }

    /**
     * Only valid when the incoming channel is Facebook.
     */
    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return event.isFacebook();
    }

}
