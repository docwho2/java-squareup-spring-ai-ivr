package cloud.cleo.squareup.tools;


import cloud.cleo.squareup.LexV2EventWrapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Driving directions when the user is interacting via a text interface.
 */
@Component
public class DrivingDirectionsText extends AbstractTool {

    @Tool(
        name = DRIVING_DIRECTIONS_TEXT_FUNCTION_NAME,
        description = """
            Returns a URL for driving directions to the store. \
            Use this when a text user asks how to get to the store \
            or requests directions.
            """
    )
    public DrivingDirectionsTextResult getDrivingDirectionsText(ToolContext ctx) {
        // No need to inspect the event here – it's always the same URL.
        return new DrivingDirectionsTextResult(DRIVING_DIRECTIONS_URL);
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        // Text only, like the original isText() = true / isVoice() = false.
        return event.isText();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DrivingDirectionsTextResult(
            @JsonProperty("url")
            String url
    ) { }
}
