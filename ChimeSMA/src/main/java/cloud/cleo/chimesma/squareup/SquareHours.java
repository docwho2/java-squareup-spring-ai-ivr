package cloud.cleo.chimesma.squareup;

import com.squareup.square.LocationsClient;
import com.squareup.square.SquareClient;
import com.squareup.square.core.Environment;
import com.squareup.square.types.BusinessHoursPeriod;
import com.squareup.square.types.GetLocationsRequest;
import com.squareup.square.types.Location;

import java.time.DayOfWeek;
import static java.time.DayOfWeek.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import lombok.Data;
import lombok.extern.log4j.Log4j2;

/**
 * Determine whether open or closed based on Square Hours from API call. Cache and hold last result, so if API is down,
 * we always have a value to return.
 *
 * Also distinguishes an "extended closed" period when the location has no business hours configured at all.
 *
 * SnapStart-friendly, no Spring.
 *
 * @author sjensen
 */
@Log4j2
public class SquareHours {

    public enum StoreStatus {
        OPEN,
        CLOSED,
        EXTENDED_CLOSED
    }

    private static final String SQUARE_LOCATION_ID = System.getenv("SQUARE_LOCATION_ID");
    private static final String SQUARE_API_KEY = System.getenv("SQUARE_API_KEY");
    private static final String SQUARE_ENVIRONMENT = System.getenv("SQUARE_ENVIRONMENT");

    // If Square doesn't give us a TZ, fall back to this
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Chicago");

    // How long we consider the location cache valid
    private static final Duration LOCATION_TTL = Duration.ofHours(24);

    private static final SquareClient client = SquareClient.builder()
            .token(SQUARE_API_KEY)
            .environment(switch (SQUARE_ENVIRONMENT) {
        default ->
            Environment.PRODUCTION;
        case "SANDBOX", "sandbox" ->
            Environment.SANDBOX;
    })
            .build();

    private static final LocationsClient locationsApi = client.locations();

    private final boolean squareEnabled;

    // Cached location result and metadata
    private volatile Location loc;
    private volatile ZoneId tz;
    private volatile Instant locLastFetched;

    // Lock for synchronizing access to location data
    private final ReentrantLock lock = new ReentrantLock();

    private static final SquareHours me = new SquareHours();

    private SquareHours() {
        // Enabled if we have what looks like key and location set
        squareEnabled = !(SQUARE_LOCATION_ID == null || SQUARE_LOCATION_ID.isBlank() || "DISABLED".equalsIgnoreCase(SQUARE_LOCATION_ID)
                || SQUARE_API_KEY == null || SQUARE_API_KEY.isBlank() || "DISABLED".equalsIgnoreCase(SQUARE_API_KEY));
        log.info("Square Enabled check = {}" ,squareEnabled);

        // Warm cache once at startup (good for SnapStart),
        // but don't die if Square is unavailable.
        if (squareEnabled) {
            try {
                getLocation();
            } catch (Exception e) {
               log.error("Initial Square location warmup failed; will retry lazily.",e);
            }
        }
    }

    public static SquareHours getInstance() {
        return me;
    }

    /**
     * Load and cache location; may return null if unable to retrieve and we have no previous cached value.
     */
    private Location getLocation() {
        if (!squareEnabled) {
            return null;
        }

        final Instant now = Instant.now();
        Location current = loc;

        // If we have a cached location and it's still within TTL, return it.
        if (current != null && locLastFetched != null
                && Duration.between(locLastFetched, now).compareTo(LOCATION_TTL) <= 0) {
            return current;
        }

        // Cache missing or expired – try to refresh.
        lock.lock();
        try {
            // Re-check after acquiring lock to avoid double refresh
            current = loc;
            if (current != null && locLastFetched != null
                    && Duration.between(locLastFetched, now).compareTo(LOCATION_TTL) <= 0) {
                return current;
            }

            final var res = locationsApi.get(
                    GetLocationsRequest.builder().locationId(SQUARE_LOCATION_ID).build()
            );

            if (res.getLocation() != null && res.getLocation().isPresent()) {
                Location loaded = res.getLocation().get();
                loc = loaded;
                locLastFetched = now;

                // Resolve timezone; fall back if missing
                var tzOpt = loaded.getTimezone();
                if (tzOpt != null && tzOpt.isPresent() && !tzOpt.get().isBlank()) {
                    tz = ZoneId.of(tzOpt.get());
                } else {
                    log.error("Square Location has no timezone; using default {}", DEFAULT_ZONE);
                    tz = DEFAULT_ZONE;
                }

                return loaded;
            } else {
                log.error("Square returned empty Location for id {}", SQUARE_LOCATION_ID);
            }

        } catch (Exception e) {
            log.error("Error retrieving Square location {}",SQUARE_LOCATION_ID);
            log.error(e);
        } finally {
            lock.unlock();
        }

        // If we got here, the refresh failed. Use previous cached value if any.
        if (loc != null) {
            log.error("Using previously cached Square location after refresh failure");
            return loc;
        }

        // Nothing cached and cannot fetch.
        return null;
    }

    /**
     * Is the store currently open?
     *
     * If we don't have a valid key/location or cannot reach Square, this returns false.
     * @return 
     */
    public boolean isOpen() {
        return getStatus() == StoreStatus.OPEN;
    }

    /**
     * Is the store in an "extended closed" state (no business hours defined)?
     * @return 
     */
    public boolean isExtendedClosed() {
        return getStatus() == StoreStatus.EXTENDED_CLOSED;
    }

    /**
     * Overall status based on Square location + business hours.
     *
     * @return
     */
    public StoreStatus getStatus() {
        if (!squareEnabled) {
            return StoreStatus.OPEN;  // If no Square, just say open
        }

        Location location = getLocation();
        if (location == null) {
            // Can't reach Square and no cached location
            return StoreStatus.CLOSED;
        }

        BusinessHours bh = new BusinessHours(location);

        if (!bh.hasAny()) {
            // Location exists but has no business hours defined
            return StoreStatus.EXTENDED_CLOSED;
        }

        return bh.isOpen() ? StoreStatus.OPEN : StoreStatus.CLOSED;
    }

    /**
     * Wrapper for business hours periods.
     */
    private class BusinessHours extends ArrayList<OpenPeriod> {

        BusinessHours(List<BusinessHoursPeriod> periods) {
            if (periods != null) {
                periods.forEach(p -> add(new OpenPeriod(p)));
            }
        }

        BusinessHours(Location location) {
            this(
                    location.getBusinessHours()
                            .flatMap(bh -> bh.getPeriods())
                            .orElse(null)
            );
        }

        boolean hasAny() {
            return !isEmpty();
        }

        boolean isOpen() {
            if (isEmpty()) {
                return false;
            }

            // The current time in the TZ
            final ZoneId zone = (tz != null) ? tz : DEFAULT_ZONE;
            final var now = ZonedDateTime.now(zone);
            final var today = now.toLocalDate();

            return stream()
                    .filter(p -> p.getDow().equals(now.getDayOfWeek()))
                    .anyMatch(p -> {
                        final var start = LocalDateTime.of(today, p.getStart()).atZone(zone);
                        final var end = LocalDateTime.of(today, p.getEnd()).atZone(zone);
                        return now.isAfter(start) && now.isBefore(end);
                    });
        }
    }

    @Data
    private static class OpenPeriod {

        final DayOfWeek dow;
        final LocalTime start;
        final LocalTime end;

        OpenPeriod(BusinessHoursPeriod bhp) {
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
                    throw new RuntimeException("Day of Week cannot be matched " + bhp.getDayOfWeek().get());
            };

            start = LocalTime.parse(bhp.getStartLocalTime().get());
            end = LocalTime.parse(bhp.getEndLocalTime().get());
        }
    }
}
