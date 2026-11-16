package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.service.StoreWeatherService;
import cloud.cleo.squareup.service.StoreWeatherService.StoreWeatherResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoreWeather extends AbstractTool {

    private final StoreWeatherService storeWeatherService;

    @Tool(
            description = """
            Get current weather and a short daily forecast for Wahkon, MN, USA,
            where the store is located.
            If weather is unavailable, say so briefly. Do not invent temperature or wind readings.
            """
    )
    public StoreWeatherResponse getStoreWeather() {
        return storeWeatherService.getStoreWeather();
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return true;
    }
}
