package cloud.cleo.squareup.service;

import cloud.cleo.squareup.config.SquareConfig.SquareProperties;
import com.squareup.square.AsyncSquareClient;
import com.squareup.square.types.GetLocationsRequest;
import com.squareup.square.types.Location;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@RequiredArgsConstructor
public class SquareLocationService {

    private static final Duration CACHE_TTL = Duration.ofDays(1);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Chicago");

    private final SquareProperties props;
    private final @Nullable AsyncSquareClient client;

    private final AtomicReference<CachedLocation> cachedLocation = new AtomicReference<>();
    private final AtomicReference<ZoneId> cachedZoneId = new AtomicReference<>();

    private static final class CachedLocation {
        final Location location;
        final Instant fetchedAt;

        CachedLocation(Location location, Instant fetchedAt) {
            this.location = location;
            this.fetchedAt = fetchedAt;
        }
    }

    public boolean isEnabled() {
        return props.enabled() && client != null;
    }

    public String getLocationId() {
        return props.locationId();
    }

    /**
     * Get (and cache with TTL) the Square Location object.
     * This can still throw if Square is enabled but completely unreachable
     * and we have no previous cached value.
     * @return 
     */
    public Location getLocation() {
        if (!isEnabled()) {
            throw new IllegalStateException("Square is disabled; cannot retrieve location");
        }

        final Instant now = Instant.now();
        CachedLocation current = cachedLocation.get();

        // If we have a cached value and it is still within TTL, return it.
        if (current != null && Duration.between(current.fetchedAt, now).compareTo(CACHE_TTL) <= 0) {
            return current.location;
        }

        // Cache is missing or expired – attempt refresh.
        return refreshLocation();
    }

    private Location refreshLocation() {
        final CachedLocation before = cachedLocation.get();

        try {
            var request = GetLocationsRequest.builder()
                    .locationId(props.locationId())
                    .build();

            var response = client.locations()
                    .get(request)
                    .get();

            var locOpt = response.getLocation();
            if (locOpt.isEmpty()) {
                throw new IllegalStateException("Square returned empty Location for id " + props.locationId());
            }

            var loc = locOpt.get();
            var updated = new CachedLocation(loc, Instant.now());
            cachedLocation.set(updated);

            log.debug("Square location refreshed for id {} at {}", props.locationId(), updated.fetchedAt);
            return loc;

        } catch (CompletionException e) {
            log.error("Error retrieving Square location {}", props.locationId(), e.getCause());
            if (before != null) {
                log.warn("Using previously cached Square location {} after refresh failure",
                        before.location.getId());
                return before.location;
            }
            throw new IllegalStateException("No cached data available and failed to retrieve from Square API", e.getCause());
        } catch (Exception e) {
            log.error("Unexpected error retrieving Square location {}", props.locationId(), e);
            if (before != null) {
                log.warn("Using previously cached Square location {} after unexpected refresh failure",
                        before.location.getId());
                return before.location;
            }
            throw new IllegalStateException("No cached data available and failed to retrieve from Square API", e);
        }
    }

    /**
     * Bullet-proof timezone resolution:
     *  - Tries once to get TZ from Square
     *  - On any failure or missing TZ, logs and returns DEFAULT_ZONE
     *  - Caches the ZoneId so future calls never touch Square again
     * @return 
     */
    public ZoneId getZoneId() {
        // If we've already resolved it once, just return the cached value.
        ZoneId existing = cachedZoneId.get();
        if (existing != null) {
            return existing;
        }

        ZoneId resolved = DEFAULT_ZONE;

        if (isEnabled()) {
            try {
                Location loc = getLocation();
                var tzOpt = loc.getTimezone();
                if (tzOpt.isPresent() && !tzOpt.get().isBlank()) {
                    resolved = ZoneId.of(tzOpt.get());
                    log.debug("Resolved Square timezone {} for location {}", resolved, props.locationId());
                } else {
                    log.warn("Square Location {} has no timezone configured; using default {}",
                            props.locationId(), DEFAULT_ZONE);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve Square timezone for location {}; using default {}",
                        props.locationId(), DEFAULT_ZONE, e);
            }
        } else {
            log.debug("Square disabled; using default timezone {}", DEFAULT_ZONE);
        }

        // Cache the resolved or default zone exactly once
        cachedZoneId.compareAndSet(null, resolved);
        return cachedZoneId.get();
    }
}