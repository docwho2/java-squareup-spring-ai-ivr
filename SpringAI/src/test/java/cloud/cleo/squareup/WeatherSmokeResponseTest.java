package cloud.cleo.squareup;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class WeatherSmokeResponseTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "Sorry, the weather information is currently unavailable. Would you like me to check anything else for you, like our current hours or products?",
        "Weather is temporarily unavailable. Please try again later.",
        "The weather data is not available right now.",
        "WEATHER FORECAST UNAVAILABLE. Please try again later."
    })
    void acceptsExplicitWeatherFallback(String response) {
        assertTrue(WeatherSmokeResponse.isUnavailable(response));
        assertFalse(WeatherSmokeResponse.hasTemperature(response));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "It's 21.5°F at the store.",
        "Currently twenty degrees Fahrenheit.",
        "It is -5 F at the store."
    })
    void preservesTemperatureResponses(String response) {
        assertTrue(WeatherSmokeResponse.hasTemperature(response));
        assertFalse(WeatherSmokeResponse.isUnavailable(response));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        " ",
        "Unavailable.",
        "The store is currently unavailable.",
        "Weather is sunny. Product information is unavailable.",
        "Weather is not unavailable.",
        "Weather is available.",
        "Request timed out.",
        "An unexpected error occurred.",
        "Would you like to know our store hours?"
    })
    void rejectsUnrelatedOrInvalidResponses(String response) {
        assertFalse(WeatherSmokeResponse.isUnavailable(response));
        assertFalse(WeatherSmokeResponse.hasTemperature(response));
    }
}
