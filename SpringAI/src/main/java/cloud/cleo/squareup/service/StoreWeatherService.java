package cloud.cleo.squareup.service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
@Log4j2
public class StoreWeatherService {

    // Wahkon, MN (WGS84)
    private static final double WAHKON_LAT = 46.1182899;
    private static final double WAHKON_LON = -93.5210726;

    private final RestClient restClient;
    private final ZoneId storeTimezone;

    public StoreWeatherResponse getStoreWeather() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> json =
                    restClient.get()
                            .uri(uriBuilder -> uriBuilder
                                    .scheme("https")
                                    .host("api.open-meteo.com")
                                    .path("/v1/forecast")
                                    .queryParam("latitude", WAHKON_LAT)
                                    .queryParam("longitude", WAHKON_LON)
                                    .queryParam("current_weather", "true")
                                    .queryParam("daily", "weathercode,temperature_2m_max,temperature_2m_min")
                                    .queryParam("timezone", storeTimezone.toString())
                                    .queryParam("temperature_unit", "fahrenheit")
                                    .queryParam("windspeed_unit", "mph")
                                    .queryParam("forecast_days", 5)
                                    .build()
                            )
                            .retrieve()
                            .body(Map.class);

            if (json == null || json.isEmpty()) {
                log.warn("Weather API returned empty JSON");
                return unavailableResponse();
            }

            // ----- current conditions -----
            Object currentObj = json.get("current_weather");
            if (!(currentObj instanceof Map)) {
                log.warn("Weather API JSON missing or invalid 'current_weather' node: {}", currentObj);
                return unavailableResponse();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> current = (Map<String, Object>) currentObj;

            Object tempObj = current.get("temperature");
            Object windObj = current.get("windspeed");
            Object codeObj = current.get("weathercode");

            if (!(tempObj instanceof Number)
                    || !(windObj instanceof Number)
                    || !(codeObj instanceof Number)) {
                log.warn("Weather API JSON missing numeric current fields: temp={}, wind={}, code={}",
                        tempObj, windObj, codeObj);
                return unavailableResponse();
            }

            double temperatureF = ((Number) tempObj).doubleValue();
            double windSpeedMph = ((Number) windObj).doubleValue();
            int weatherCode = ((Number) codeObj).intValue();

            String currentSummary = mapWeatherCode(weatherCode);

            // ----- daily forecast -----
            Object dailyObj = json.get("daily");
            if (!(dailyObj instanceof Map)) {
                log.warn("Weather API JSON missing or invalid 'daily' node: {}", dailyObj);
                return unavailableResponseWithSummaryOnly(currentSummary, temperatureF, windSpeedMph);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> daily = (Map<String, Object>) dailyObj;

            @SuppressWarnings("unchecked")
            List<String> dates = (List<String>) daily.get("time");
            @SuppressWarnings("unchecked")
            List<Number> minTemps = (List<Number>) daily.get("temperature_2m_min");
            @SuppressWarnings("unchecked")
            List<Number> maxTemps = (List<Number>) daily.get("temperature_2m_max");
            @SuppressWarnings("unchecked")
            List<Number> codes = (List<Number>) daily.get("weathercode");

            if (dates == null || minTemps == null || maxTemps == null || codes == null) {
                log.warn("Weather API JSON daily arrays missing: dates={}, min={}, max={}, codes={}",
                        dates, minTemps, maxTemps, codes);
                return unavailableResponseWithSummaryOnly(currentSummary, temperatureF, windSpeedMph);
            }

            int size = Math.min(
                    Math.min(dates.size(), minTemps.size()),
                    Math.min(maxTemps.size(), codes.size())
            );

            List<StoreWeatherResponse.DailyForecast> forecast = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                forecast.add(new StoreWeatherResponse.DailyForecast(
                        dates.get(i),
                        minTemps.get(i).doubleValue(),
                        maxTemps.get(i).doubleValue(),
                        mapWeatherCode(codes.get(i).intValue())
                ));
            }

            var res = new StoreWeatherResponse(
                    "Wahkon, MN",
                    currentSummary,
                    temperatureF,
                    windSpeedMph,
                    forecast
            );
            log.debug(res);
            return res;
        } catch (Exception e) {
            log.warn("Failed to retrieve store weather", e);
            return unavailableResponse();
        }
    }

    private StoreWeatherResponse unavailableResponse() {
        return new StoreWeatherResponse(
                "Wahkon, MN",
                "No weather data available at this time.",
                0.0,
                0.0,
                List.of()
        );
    }

    private StoreWeatherResponse unavailableResponseWithSummaryOnly(
            String currentSummary,
            double temperatureF,
            double windSpeedMph
    ) {
        return new StoreWeatherResponse(
                "Wahkon, MN",
                currentSummary + " (forecast unavailable).",
                temperatureF,
                windSpeedMph,
                List.of()
        );
    }

    /**
     * Very simple mapping of Open-Meteo weather codes into human/LLM friendly summaries.
     */
    private String mapWeatherCode(int code) {
        return switch (code) {
            case 0 -> "Clear sky";
            case 1, 2 -> "Mostly clear";
            case 3 -> "Cloudy";
            case 45, 48 -> "Foggy";
            case 51, 53, 55 -> "Drizzle";
            case 61, 63, 65 -> "Rain";
            case 71, 73, 75 -> "Snow";
            case 80, 81, 82 -> "Rain showers";
            case 85, 86 -> "Snow showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Unknown conditions";
        };
    }

    public record StoreWeatherResponse(
            String locationName,
            String summary,
            double temperatureFahrenheit,
            double windSpeedMilesPerHour,
            List<DailyForecast> forecast
    ) {
        public record DailyForecast(
                String date,
                double minTempFahrenheit,
                double maxTempFahrenheit,
                String summary
        ) {}
    }
}