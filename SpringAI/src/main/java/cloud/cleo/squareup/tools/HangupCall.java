package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Hang up the caller when the interaction is complete.
 */
@Component
public class HangupCall extends AbstractTool {

    @Tool(
            name = HANGUP_FUNCTION_NAME,
            description = """
            Should be called when the interaction is done and the caller 
            no longer needs any further assistance. This ends the current 
            voice session so the call can be disconnected.
            """
    )
    public StatusMessageResult hangup(ToolContext ctx) {
        // Inform Chime we want to terminate the call
        getEventWrapper(ctx).putSessionAttributeAction(HANGUP_FUNCTION_NAME);

        // The actual hangup is handled by your app / IVR logic when it sees this tool result.
        return new StatusMessageResult(
                "SUCCESS",
                "The caller is now ready to hang up. Session ended."
        );
    }

    /**
     * Hangup is only applicable for voice interactions.
     */
    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return event.isVoice();
    }
}
