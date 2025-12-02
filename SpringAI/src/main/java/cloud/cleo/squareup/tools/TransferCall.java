package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
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
            Only valid internal/store numbers are permitted.
            """
    )
    public StatusMessageResult transfer(TransferCallRequest r, ToolContext ctx) {
        
        // Centralized validation of required fields
        StatusMessageResult validationError = validateRequiredFields(r);
        if (validationError != null) {
            return validationError;
        }
        
        final var wrapper = getEventWrapper(ctx);
        wrapper.putSessionAttributeAction(TRANSFER_FUNCTION_NAME);
        wrapper.putSessionAttribute("transfer_number", r.transferNumber);
        
        return logAndReturnSuccess(
                "The caller is now ready to be transferred to " + r.transferNumber() + ".  Do not ask further questions, just say you will now be transferred."
        );
    }

    /**
     * Transfers only apply to voice calls, never text channels.
     */
    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return event.isVoice();
    }


    /**
     * The request payload the model must provide.
     */
    public record TransferCallRequest(
            @JsonPropertyDescription("The internal or store phone number in E164 format to transfer the caller to.")
            @JsonProperty(value = "transfer_number", required = true)
            String transferNumber
    ) {}
}
