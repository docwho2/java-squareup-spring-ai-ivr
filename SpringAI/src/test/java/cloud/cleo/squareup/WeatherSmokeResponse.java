package cloud.cleo.squareup;

import java.util.regex.Pattern;

/** Response checks shared by the live weather smoke test and its local regression tests. */
final class WeatherSmokeResponse {

    private static final Pattern TEMPERATURE = Pattern.compile("(?i)(°f|degrees|fahrenheit| f )");
    private static final Pattern UNAVAILABLE = Pattern.compile(
            "(?i)\\bweather(?:\\s+(?:information|data|forecast))?"
            + "\\s+(?:(?:is|are)\\s+)?(?:(?:currently|temporarily)\\s+)?"
            + "(?:unavailable|not\\s+available)\\b");

    private WeatherSmokeResponse() {
    }

    static boolean hasTemperature(String response) {
        return response != null && TEMPERATURE.matcher(response).find();
    }

    static boolean isUnavailable(String response) {
        return response != null && UNAVAILABLE.matcher(response).find();
    }
}
