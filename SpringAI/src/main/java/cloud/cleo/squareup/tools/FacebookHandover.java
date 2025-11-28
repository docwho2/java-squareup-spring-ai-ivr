package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.FaceBookOperations;
import static cloud.cleo.squareup.enums.ChannelPlatform.FACEBOOK;

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

    private final FaceBookOperations faceBookOperations;
    
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
            the user asks to talk to real person.
            """
    )
    public StatusMessageResult facebookHandover(ToolContext ctx) {
        final var event = getEventWrapper(ctx);
        
        faceBookOperations.transferToInbox(event.getSessionId());

        // The model will surface this message to the user; your app can also
        // detect this tool call and run the Facebook handover protocol.
        return new StatusMessageResult(
                "SUCCESS",
                "Conversation has been moved to the Inbox, a person will respond shortly."
        );
    }

    /**
     * Only valid when the incoming channel is Facebook (text).
     */
    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return event.getChannelPlatform().equals(FACEBOOK);
    }

}
