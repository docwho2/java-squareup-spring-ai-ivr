package cloud.cleo.squareup;

import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_SMOKE;
import static cloud.cleo.squareup.AbstractLexAwsTestSupport.SPRING_AI_MODEL;
import cloud.cleo.squareup.enums.ChannelPlatform;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Some basic tests to run after a deployment and on a schedule just to validate we can talk to Square via API and test
 * the path from Lex to Lambda to model.
 *
 * @author sjensen
 */
@Log4j2
@Epic(ALLURE_EPIC_SMOKE)
@ExtendWith({TimingExtension.class})
public class SmokeTests extends AbstractLexAwsTestSupport {

    @Test
    @Order(Integer.MIN_VALUE)          // runs before all other @Order'd tests
    @Epic(ALLURE_EPIC_WARM_UP)   // keeps all warmup tests in their own Allure group
    @Tag(JUNIT_TAG_WARM_UP)      // so the TimingExtension can recognize it
    @DisplayName("Warm Up the Stack")
    public void warmupStack() {
        // Warm up the lex path and lambda so everything is hot and use a distinct session ID
        assertNotNull(getBotResponse(sendToLex("Hello, what is your name?", UUID.randomUUID().toString())));
    }

    @Test
    @Order(1)
    @Feature(ALLURE_FEATURE_SQUARE_API)
    @Feature(ALLURE_FEATURE_TOOL_CALL)
    @DisplayName("Chuckles Candy")
    public void chucklesCandyTest() {

        final var res = sendToLex(
                "Do you have Chuckles Candy in stock?"
        );

        final var chuckles = getBotResponse(res);

        boolean ok = chuckles.matches("(?s).*?(Yes|We have|Chuckles).*");
        log.info(ok ? "Chuckles Test Passed" : "Chuckles Test FAILED");
        assertTrue(ok, "Chuckles test failed, response was: " + chuckles);
    }

    @Test
    @Order(2)
    @Feature(ALLURE_FEATURE_STORE_KNOWLEDGE)
    @DisplayName("Restaurant Recommendation")
    public void restaurantTest() {

        final var res = sendToLex(
                "Please recommend a restaurant in the area?"
        );

        final var muggs = getBotResponse(res);

        boolean ok = muggs.toLowerCase().contains("mugg");
        log.info(ok ? "Muggs Restaurant Test Passed" : "Muggs Restaurant Test FAILED");
        assertTrue(ok, "Muggs restaurant test failed, response was: " + muggs);
    }

    @Test
    @Order(3)
    @Feature(ALLURE_FEATURE_STORE_KNOWLEDGE)
    @DisplayName("Store Address")
    public void addressTest() {

        final var res = sendToLex(
                "What is your address?"
        );

        final var address = getBotResponse(res);

        boolean ok = address.matches("(?s).*160\\s+Main.*");
        log.info(ok ? "Address Test Passed" : "Address Test FAILED");
        assertTrue(ok, "Address test failed, response was: " + address);
    }

    @Test
    @Order(4)
    @Feature(ALLURE_FEATURE_SQUARE_API)
    @Feature(ALLURE_FEATURE_TOOL_CALL)
    @DisplayName("Store Staff")
    public void staffTest() {

        final var res = sendToLex(
                "Does Steve work there?  If so, just say Yes"
        );

        final var staff = getBotResponse(res);

        boolean ok = staff.matches("(?is).*?(jensen|yes|copperfoxgifts|indeed|confirm).*");
        log.info(ok ? "Staff Test Passed" : "Staff Test FAILED");
        assertTrue(ok, "Staff test failed, response was: " + staff);
    }

    @Test
    @Order(5)
    @Feature(ALLURE_FEATURE_WEATHER_API)
    @Feature(ALLURE_FEATURE_TOOL_CALL)
    @DisplayName("Weather Forecast")
    public void weatherTest() {

        final var res = sendToLex(
                "What's the weather like at the store today?"
        );

        final var weather = getBotResponse(res);

        boolean ok = weather.toLowerCase().matches("(?is).*?(°f|degrees|fahrenheit| f ).*");
        log.info(ok ? "Weather Test Passed" : "Weather Test FAILED");
        assertTrue(ok, "Weaather test failed, response was: " + weather);
    }

    @Test
    @Order(Integer.MAX_VALUE)  // Always Last
    @Epic(ALLURE_EPIC_PERF_SUM)
    @DisplayName("Performance Summary")
    public void performanceSummary() {
        var results = TimingExtension.getResults();
        String model = System.getenv("SPRING_AI_MODEL");
        String provider = System.getenv("SPRING_AI_PROVIDER");

        if (results.isEmpty()) {
            Allure.addAttachment("Performance Summary", "text/plain", "No timing data collected.");
            return;
        }

        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>")
                .append("body{font-family:Arial, sans-serif;font-size:13px;}")
                .append("table{border-collapse:collapse;margin-top:8px;}")
                .append("th,td{border:1px solid #ccc;padding:4px 8px;font-family:monospace;font-size:12px;}")
                .append("</style></head><body>");

        html.append("<h3>Test Performance Summary</h3>");

        if (provider != null || model != null) {
            html.append("<p><b>Provider/Model:</b> ");
            if (provider != null) {
                html.append(provider);
            }
            if (provider != null && model != null) {
                html.append(" / ");
            }
            if (model != null) {
                html.append(model);
            }
            html.append("</p>");
        }

        html.append("<table>")
                .append("<tr><th>#</th><th>Test</th><th>Duration (ms)</th><th>Approx RPS</th></tr>");

        int i = 1;
        for (TimingExtension.TestTiming t : results) {
            double seconds = t.getDurationMs() / 1000.0;
            double rps = seconds > 0 ? 1.0 / seconds : 0.0;

            html.append("<tr>")
                    .append("<td>").append(i++).append("</td>")
                    .append("<td>").append(t.getTestId()).append("</td>")
                    .append("<td>").append(t.getDurationMs()).append("</td>")
                    .append("<td>").append(String.format("%.2f", rps)).append("</td>")
                    .append("</tr>");
        }

        html.append("</table></body></html>");

        if (SPRING_AI_MODEL != null) {
            Allure.label("tag", SPRING_AI_MODEL);
        }

        Allure.getLifecycle().updateTestCase(tr -> tr.setDescriptionHtml(html.toString()));

    }

    /**
     * Assume voice channel for basic smoke tests, that's our most common use case.
     *
     * @return
     */
    @Override
    protected ChannelPlatform getChannel() {
        return ChannelPlatform.CHIME;
    }
}
