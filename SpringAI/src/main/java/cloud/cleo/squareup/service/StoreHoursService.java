package cloud.cleo.squareup.service;

import cloud.cleo.squareup.service.SquareLocationService;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.squareup.square.types.BusinessHoursPeriod;
import com.squareup.square.types.Location;
import lombok.AccessLevel;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Locale;

import static com.squareup.square.types.DayOfWeek.Value.*;
import static java.time.DayOfWeek.*;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
public class StoreHoursService {

    private final SquareLocationService locationService;

    public StoreHoursService(SquareLocationService locationService) {
        this.locationService = locationService;
    }

    public boolean isEnabled() {
        return locationService.isEnabled();
    }

    public StoreHoursResult getStoreHours() {
        try {
            Location loc = locationService.getLocation();
            BusinessHours bh = new BusinessHours(loc);

            ZoneId tz = locationService.getZoneId();
            ZonedDateTime now = ZonedDateTime.now(tz);

            boolean open = bh.isOpen(now);
            String status = open ? "OPEN" : "CLOSED";

            String dow = now.getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, Locale.US)
                    .toUpperCase(Locale.US);

            String message;
            if (bh.isEmpty()) {
                message = open
                        ? "We are currently open, but detailed schedule data is temporarily unavailable."
                        : "We are currently closed. Store hours are temporarily unavailable, but we will re-open soon.";
            } else {
                message = open
                        ? "We are currently OPEN. See 'open_hours' for today's and upcoming hours."
                        : "We are currently CLOSED. See 'open_hours' for today's and upcoming hours.";
            }

            return new StoreHoursResult(
                    status,
                    now.toString(),
                    dow,
                    message,
                    bh.isEmpty() ? null : bh
            );
        } catch (Exception e) {
            return new StoreHoursResult(
                    "UNKNOWN",
                    null,
                    null,
                    "Unable to determine store hours at the moment.",
                    null
            );
        }
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
            Object openHours // BusinessHours serialized
    ) { }

    public static class BusinessHours extends ArrayList<OpenPeriod> {

        @JsonIgnore
        private final Location loc;

        public BusinessHours(Location loc) {
            this.loc = loc;
            loc.getBusinessHours()
               .flatMap(bh -> bh.getPeriods())
               .ifPresent(periods ->
                       periods.forEach(p -> add(new OpenPeriod(p)))
               );
        }

        @JsonIgnore
        public boolean isOpen(ZonedDateTime now) {
            ZoneId tz = ZoneId.of(loc.getTimezone().get());
            LocalDate today = now.toLocalDate();

            return stream()
                    .filter(p -> p.getDow().equals(now.getDayOfWeek()))
                    .anyMatch(p -> {
                        ZonedDateTime start = LocalDateTime.of(today, p.getStart()).atZone(tz);
                        ZonedDateTime end = LocalDateTime.of(today, p.getEnd()).atZone(tz);
                        return !now.isBefore(start) && !now.isAfter(end);
                    });
        }
    }

    @Getter(AccessLevel.PUBLIC)
    public static class OpenPeriod {

        final DayOfWeek dow;
        final LocalTime start;
        final LocalTime end;

        public OpenPeriod(BusinessHoursPeriod bhp) {
            dow = switch (bhp.getDayOfWeek().get().getEnumValue()) {
                case SUN -> SUNDAY;
                case MON -> MONDAY;
                case TUE -> TUESDAY;
                case WED -> WEDNESDAY;
                case THU -> THURSDAY;
                case FRI -> FRIDAY;
                case SAT -> SATURDAY;
                case UNKNOWN -> throw new RuntimeException("Day of Week cannot be matched " + bhp.getDayOfWeek());
            };

            start = LocalTime.parse(bhp.getStartLocalTime().get());
            end = LocalTime.parse(bhp.getEndLocalTime().get());
        }
    }
}