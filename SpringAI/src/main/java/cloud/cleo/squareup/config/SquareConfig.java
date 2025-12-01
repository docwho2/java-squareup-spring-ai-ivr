/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.config;

import cloud.cleo.squareup.service.SquareLocationService;
import com.squareup.square.AsyncSquareClient;
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
    public AsyncSquareClient asyncSquareClient(SquareProperties props) {
        if (!props.enabled()) {
            return null; // tools should check for null or use SquareXXXXXService.isEnabled()
        }

        Environment environment = switch (props.environment().toUpperCase()) {
            case "SANDBOX" ->
                Environment.SANDBOX;
            default ->
                Environment.PRODUCTION;
        };

        return AsyncSquareClient.builder()
                .token(props.apiKey())
                .environment(environment)
                .build();
    }
    

    /**
     * Store timezone from Square location – convenient to inject directly.
     * @param squareLocationService
     * @return 
     */
    @Bean
    public ZoneId storeZoneId(SquareLocationService squareLocationService) {
        return squareLocationService.isEnabled()
                ? squareLocationService.getZoneId()
                : ZoneId.of("America/Chicago");  // or a sensible default, e.g. America/Chicago
    }

    
}
