package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.squareup.square.types.BusinessHoursPeriod;
import static com.squareup.square.types.DayOfWeek.Value.FRI;
import static com.squareup.square.types.DayOfWeek.Value.MON;
import static com.squareup.square.types.DayOfWeek.Value.SAT;
import static com.squareup.square.types.DayOfWeek.Value.SUN;
import static com.squareup.square.types.DayOfWeek.Value.THU;
import static com.squareup.square.types.DayOfWeek.Value.TUE;
import static com.squareup.square.types.DayOfWeek.Value.UNKNOWN;
import static com.squareup.square.types.DayOfWeek.Value.WED;
import com.squareup.square.types.GetLocationsRequest;
import com.squareup.square.types.Location;
import java.time.DayOfWeek;
import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import org.springframework.ai.chat.model.ToolContext;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Tool that returns the store's current open/closed status and hours. The model should treat these fields as ground
 * truth and not guess.
 */
@Component
public class StoreHours extends AbstractTool {

    private static volatile Location cachedLocation; // Cache for the last successful location data

    
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
        try {
            // Reuse your existing logic – assume this comes from your base class or service
            final var loc = getLocation();                // from your existing code
            final var bh = new BusinessHours(loc);       // from your existing code

            final var tz = ZoneId.of(loc.getTimezone().get());
            final var now = ZonedDateTime.now(tz);

            final boolean isOpen = bh.isOpen();
            final String status = isOpen ? "OPEN" : "CLOSED";

            final String dow = now.getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, Locale.US)
                    .toUpperCase(Locale.US);

            final String message;
            if (bh.isEmpty()) {
                // Your “temporarily closed” wording
                message = switch (status) {
                    case "OPEN" ->
                        "We are currently open, but detailed schedule data is temporarily unavailable.";
                    default ->
                        "We are currently closed. Store hours are temporarily unavailable, but we will re-open soon.";
                };
            } else {
                // Let the model read details from open_hours, but give it a strong hint
                message = switch (status) {
                    case "OPEN" ->
                        "We are currently OPEN. See 'open_hours' for today's and upcoming hours.";
                    default ->
                        "We are currently CLOSED. See 'open_hours' for today's and upcoming hours.";
                };
            }

            // Return your structured summary; Spring/Jackson will serialize BusinessHours
            return new StoreHoursResult(
                    status,
                    now.toString(),
                    dow,
                    message,
                    bh.isEmpty() ? null : bh
            );

        } catch (Exception ex) {
            //log.error("Unhandled error in get_store_hours tool", ex);
            // Emit a safe, structured error result
            return new StoreHoursResult(
                    "UNKNOWN",
                    null,
                    null,
                    "Unable to determine store hours at the moment.",
                    null
            );
        }
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        // Valid if square is enabled
         return isSquareEnabled();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StoreHoursResult(
            @JsonProperty("open_closed_status")
            String openClosedStatus, // "OPEN" or "CLOSED"

            @JsonProperty("current_date_time")
            String currentDateTime, // ISO string in local TZ

            @JsonProperty("current_day_of_week")
            String currentDayOfWeek, // "MONDAY", "TUESDAY", etc.

            @JsonProperty("message")
            String message, // human-ready summary sentence

            @JsonProperty("open_hours")
            Object openHours // your BusinessHours object serialized
            ) {

    }

    /**
     * Gets the location data, using cache if the Square API call fails.
     *
     * @return the Location object
     * @throws Exception if an error occurs and no cached data is available
     */
    private Location getLocation() throws Exception {
        try {
            Location loc = getSquareClient().locations().get(GetLocationsRequest.builder().locationId(System.getenv("SQUARE_LOCATION_ID")).build()).get().getLocation().get();
            cachedLocation = loc;
            return loc;
        } catch (Exception ex) {
            //log.error("Failed to retrieve location from Square API, using cached data if available", ex);
            if (cachedLocation != null) {
                return cachedLocation;
            } else {
                throw new Exception("No cached data available and failed to retrieve from Square API", ex);
            }
        }
    }

    private static class BusinessHours extends ArrayList<OpenPeriod> {

        @JsonIgnore
        private final Location loc;

        public BusinessHours(Location loc) {
            this.loc = loc;
            loc.getBusinessHours().get().getPeriods().get().forEach(p -> add(new OpenPeriod(p)));
        }

        @JsonIgnore
        public boolean isOpen() {
            // The current time in the TZ
            final var tz = ZoneId.of(loc.getTimezone().get());
            final var now = ZonedDateTime.now(tz);
            final var today = now.toLocalDate();

            return stream()
                    .filter(p -> p.getDow().equals(now.getDayOfWeek()))
                    .anyMatch(p -> {
                        final var start = LocalDateTime.of(today, p.getStart()).atZone(tz);
                        final var end = LocalDateTime.of(today, p.getEnd()).atZone(tz);
                        return now.isAfter(start) && now.isBefore(end);
                    });
        }
    }

    @Getter(AccessLevel.PUBLIC)
    private static class OpenPeriod {

        final DayOfWeek dow;
        final LocalTime start;
        final LocalTime end;

        public OpenPeriod(BusinessHoursPeriod bhp) {
            dow = switch (bhp.getDayOfWeek().get().getEnumValue()) {
                case SUN ->
                    SUNDAY;
                case MON ->
                    MONDAY;
                case TUE ->
                    TUESDAY;
                case WED ->
                    WEDNESDAY;
                case THU ->
                    THURSDAY;
                case FRI ->
                    FRIDAY;
                case SAT ->
                    SATURDAY;
                case UNKNOWN ->
                    throw new RuntimeException("Day of Week Cannot be matched " + bhp.getDayOfWeek());
            };

            start = LocalTime.parse(bhp.getStartLocalTime().get());
            end = LocalTime.parse(bhp.getEndLocalTime().get());
        }
    }
}
