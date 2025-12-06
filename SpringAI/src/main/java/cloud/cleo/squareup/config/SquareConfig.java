package cloud.cleo.squareup.config;

import cloud.cleo.squareup.service.SquareLocationService;
import com.squareup.square.SquareClient;
import com.squareup.square.core.Environment;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 *
 * @author sjensen
 */
@Configuration
public class SquareConfig {

    public record SquareProperties(
            boolean enabled,
            String apiKey,
            String locationId,
            String environment
            ) {

    }

    @Bean
    public SquareProperties squareProperties(
            @Value("${square.api-key:${SQUARE_API_KEY:DISABLED}}") String apiKey,
            @Value("${square.location-id:${SQUARE_LOCATION_ID:DISABLED}}") String locationId,
            @Value("${square.environment:${SQUARE_ENVIRONMENT:PRODUCTION}}") String env) {

        boolean enabled
                = apiKey != null && !apiKey.isBlank() && !"DISABLED".equalsIgnoreCase(apiKey)
                && locationId != null && !locationId.isBlank() && !"DISABLED".equalsIgnoreCase(locationId);

        return new SquareProperties(enabled, apiKey, locationId, env);
    }

    @Bean
    public SquareClient squareClient(SquareProperties props) {
        if (!props.enabled()) {
            return null; // tools should check for null or use SquareXXXXXService.isEnabled()
        }

        Environment environment = switch (props.environment().toUpperCase()) {
            case "SANDBOX" ->
                Environment.SANDBOX;
            default ->
                Environment.PRODUCTION;
        };

        return SquareClient.builder()
                .token(props.apiKey())
                .environment(environment)
                .build();
    }

    /**
     * Store timezone from Square location – convenient to inject directly.
     *
     * @param squareLocationService
     * @return
     */
    @Bean
    public ZoneId storeZoneId(SquareLocationService squareLocationService) {
        // This is now completely safe; failure path is DEFAULT_ZONE.
        return squareLocationService.getZoneId();
    }

}
