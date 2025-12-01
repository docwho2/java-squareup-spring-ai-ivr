package cloud.cleo.squareup.service;

import cloud.cleo.squareup.config.SquareConfig.SquareProperties;
import com.squareup.square.AsyncSquareClient;
import com.squareup.square.types.GetLocationsRequest;
import com.squareup.square.types.Location;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

@Log4j2
@Service
public class SquareLocationService {

    private final SquareProperties props;
    private final AsyncSquareClient client;
    private final AtomicReference<Location> cachedLocation = new AtomicReference<>();

    public SquareLocationService(SquareProperties props, AsyncSquareClient client) {
        this.props = props;
        this.client = client;
    }

    public boolean isEnabled() {
        return props.enabled() && client != null;
    }

    public String getLocationId() {
        return props.locationId();
    }

    public Location getLocation() {
        if (!isEnabled()) {
            throw new IllegalStateException("Square is disabled; cannot retrieve location");
        }

        Location existing = cachedLocation.get();
        if (existing != null) {
            return existing;
        }

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
            cachedLocation.compareAndSet(null, loc);
            return loc;
        } catch (CompletionException e) {
            log.error("Error retrieving Square location {}", props.locationId(), e);
            Location fallback = cachedLocation.get();
            if (fallback != null) {
                log.warn("Using cached Square location {}", fallback.getId());
                return fallback;
            }
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving Square location {}", props.locationId(), e);
            Location fallback = cachedLocation.get();
            if (fallback != null) {
                log.warn("Using cached Square location {}", fallback.getId());
                return fallback;
            }
            throw new IllegalStateException("No cached data available and failed to retrieve from Square API", e);
        }
    }

    public ZoneId getZoneId() {
        var loc = getLocation();
        var tzOpt = loc.getTimezone();
        if (tzOpt.isEmpty() || tzOpt.get().isBlank()) {
            throw new IllegalStateException("Square Location has no timezone configured");
        }
        return ZoneId.of(tzOpt.get());
    }
}
