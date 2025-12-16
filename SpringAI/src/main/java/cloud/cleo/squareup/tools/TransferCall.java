package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Transfer the caller to a permitted internal or store number.
 * The actual transfer is performed by the IVR/Chime logic.
 */
@Component
public class TransferCall extends AbstractTool {

    @Tool(
        name = TRANSFER_FUNCTION_NAME,
        description = """
            Transfer the caller to an employee or the main store number. 
            The assistant MUST NOT transfer to arbitrary numbers. 
            Only valid internal/employee/store numbers are permitted.
            """
    )
    public StatusMessageResult transfer(
            @ToolParam(description = "The internal or store phone number in E164 format to transfer the caller to.", required = true) String transferNumber, 
            ToolContext ctx) {
        
       
        if ( transferNumber == null || transferNumber.isBlank() ) {
            return logAndReturnError("transferNumber must be provided");
        }
        
        final var wrapper = getEventWrapper(ctx);
        wrapper.putSessionAttributeAction(TRANSFER_FUNCTION_NAME);
        wrapper.putSessionAttribute("transfer_number", transferNumber);
        
        return logAndReturnSuccess(
                "The caller is now ready to be transferred to " + transferNumber + ".  Do not ask further questions, just say you will now be transferred."
        );
    }

    /**
     * Transfers only apply to voice calls, never text channels.
     */
    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return event.isVoice();
    }
}
