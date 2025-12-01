package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.service.StoreHoursService;
import cloud.cleo.squareup.service.StoreHoursService.StoreHoursResult;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Tool that returns the store's current open/closed status and hours. The model should treat these fields as ground
 * truth and not guess.
 */
@Component
@RequiredArgsConstructor
public class StoreHours extends AbstractTool {

    private final StoreHoursService storeHoursService;

    
    /**
     * No request parameters needed; all context comes from your Location / BusinessHours.
     *
     * The returned JSON looks like: { "open_closed_status": "OPEN", "current_date_time":
     * "2025-11-26T14:10:31-06:00[America/Chicago]", "current_day_of_week": "WEDNESDAY", "message": "We are currently
     * open. See 'open_hours' for today's and upcoming hours.", "open_hours": { ... BusinessHours serialized ... } }
     * @param ctx
     * @return 
     */
    @Tool(
            name = "get_store_hours",
            description = """
            Get whether the store is currently OPEN or CLOSED, the current local date and time, \
            the current day of week, and detailed business hours. \
            Do NOT guess the status or hours. Use the returned fields as the source of truth \
            when answering questions about whether the store is open, closing soon, or what \
            today's hours are.
            """
    )
    public StoreHoursResult getStoreHours(ToolContext ctx) {
        return storeHoursService.getStoreHours();
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        // Only valid when Square is enabled
        return storeHoursService.isEnabled();
    }

}
