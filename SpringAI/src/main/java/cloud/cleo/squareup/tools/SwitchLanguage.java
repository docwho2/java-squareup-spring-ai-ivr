package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.enums.Language;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Exit the current bot context and return with a new locale (language).
 * The actual language switch is handled by the IVR / Chime logic when
 * it sees this tool result.
 */
@Component
public class SwitchLanguage extends AbstractTool {

    @Tool(
        name = SWITCH_LANGUAGE_FUNCTION_NAME,
        description = """
            Change the language used to interact with the caller. 
            Use this only when the caller clearly requests to switch languages.
            """
    )
    public StatusMessageResult switchLanguage(SwitchLanguageRequest r, ToolContext ctx) {
        
        // Centralized validation of required fields
        StatusMessageResult validationError = validateRequiredFields(r);
        if (validationError != null) {
            return validationError;
        }
        
        final var wrapper = getEventWrapper(ctx);
        wrapper.putSessionAttributeAction(SWITCH_LANGUAGE_FUNCTION_NAME);
        wrapper.putSessionAttribute("language", r.language.toString());
        
        return logAndReturnSuccess(
                "The caller is now ready to interact in " + r.language()
        );
    }

    /**
     * Only applicable for voice — text channels are tied to a fixed Lex locale.
     */
    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return event.isVoice();
    }

    /**
     * Request payload the model must provide for this tool.
     */
    public record SwitchLanguageRequest(
            @JsonPropertyDescription("The language to switch to.")
            @JsonProperty(value = "language", required = true)
            Language language
    ) { }
}
