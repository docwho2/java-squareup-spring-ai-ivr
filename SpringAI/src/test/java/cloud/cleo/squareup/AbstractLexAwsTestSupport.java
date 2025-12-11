package cloud.cleo.squareup;

import cloud.cleo.squareup.enums.ChannelPlatform;
import static cloud.cleo.squareup.enums.ChannelPlatform.CHIME;
import cloud.cleo.squareup.enums.LexInputMode;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Step;
import io.qameta.allure.junit5.AllureJunit5;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lexruntimev2.LexRuntimeV2Client;
import software.amazon.awssdk.services.lexruntimev2.model.Message;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeTextRequest;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeTextResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

@ExtendWith({AllureJunit5.class, TimingExtension.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Log4j2
abstract class AbstractLexAwsTestSupport {

    private static final boolean RUN_TESTS
            = Boolean.parseBoolean(System.getenv().getOrDefault("RUN_TESTS", "false"));

    private static final String AWS_REGION
            = System.getenv().getOrDefault("AWS_REGION", "us-east-1");

    private static final String STACK_NAME
            = System.getProperty(
                    "stack.name",
                    System.getenv().getOrDefault("STACK_NAME", "cfox-squareup-spring-ai-ivr")
            );

    private static final String LOCALE_ID = "en_US";

    private static final String SESSION_ID
            = System.getenv().getOrDefault("LEX_SESSION_ID", UUID.randomUUID().toString());

    // Initialized in @BeforeAll
    private static Region region;
    private static LexRuntimeV2Client lexClient;
    private static String botId;
    private static String botAliasId;
    private static volatile boolean awsReady = false;
    // Model being used
    public static String SPRING_AI_MODEL = System.getenv("SPRING_AI_MODEL");

    public static long INTER_TEST_DELAY_MS = 500L;

    static {
        if (RUN_TESTS) {
            try {
                var credsProvider = DefaultCredentialsProvider.builder().build();
                credsProvider.resolveCredentials();
                log.info("AWS credentials resolved successfully for LexE2ETests");

                try (var ssm = SsmClient.builder()
                        .region(Region.of(AWS_REGION))
                        .credentialsProvider(credsProvider)
                        .build()) {

                    botId = getParam(ssm, "/" + STACK_NAME + "/BOT_ID");
                    botAliasId = getParam(ssm, "/" + STACK_NAME + "/BOT_ALIAS_ID");
                }

                lexClient = LexRuntimeV2Client.builder()
                        .region(Region.of(AWS_REGION))
                        .credentialsProvider(credsProvider)
                        .build();

                awsReady = true;

                log.info(
                        "Lex test support fully initted for stack '{}' in region '{}' (botId={}, aliasId={})",
                        STACK_NAME, AWS_REGION, botId, botAliasId
                );

            } catch (Exception e) {
                log.warn("Lex test support could not init, tests will be skipped: {}", e.toString());
                awsReady = false;
            }
        } else {
            log.info("RUN_TESTS=false; skipping Lex stack initialization");
        }
    }

    @BeforeAll
    static void init() {
        // For pipelines, sam build will always try and run tests, so unless RUN_TESTS is true, don't run
        Assumptions.assumeTrue(RUN_TESTS, "RUN_TESTS env var not true, skipping all tests");

        Assumptions.assumeTrue(awsReady, "Skipping LexE2ETests: cannot init the runtime stack");
    }

    @AfterEach
    void delayBetweenTests() throws InterruptedException {
        // This will run after every test method
        Thread.sleep(INTER_TEST_DELAY_MS);
        log.debug("Waiting {} ms", INTER_TEST_DELAY_MS);
    }

    private static String getParam(SsmClient ssm, String name) {
        log.info("Fetching SSM parameter: {}", name);
        var request = GetParameterRequest.builder()
                .name(name)
                .withDecryption(true)
                .build();
        return ssm.getParameter(request).parameter().value();
    }

    @Step("Send to Lex")
    protected final RecognizeTextResponse sendToLex(String text, ChannelPlatform channel, String sessionId) {
        // Default to text channel (from Twillio) if not set
        channel = channel != null ? channel : ChannelPlatform.TWILIO;
        Allure.addAttachment("Lex Request", "text/plain", text);
        Allure.parameter("Channel", channel.name());
        Allure.parameter("SessionId", sessionId);

        if (SPRING_AI_MODEL != null) {
            Allure.label("tag", SPRING_AI_MODEL);
        }

        log.info(">>> request: \"{}\"", text);

        Map<String, String> sessionAttrs = new HashMap<>(2);
        // If Chime, then we add bogus calling number
        switch (channel) {
            case CHIME ->
                sessionAttrs.put("callingNumber", "+18004444444");
        }
        
        
        var request = RecognizeTextRequest.builder()
                .botId(botId)
                .botAliasId(botAliasId)
                .localeId(LOCALE_ID)
                .sessionId(sessionId != null ? sessionId : getSessionId())
                // Always set a channel
                .requestAttributes(Map.of("x-amz-lex:channels:platform", channel.getChannel(),"testing_input",channel.equals(CHIME) ? LexInputMode.SPEECH.getMode(): LexInputMode.TEXT.getMode()))
                .sessionState(b -> b.sessionAttributes(sessionAttrs).build())
                .text(text)
                .build();
        
        RecognizeTextResponse response = Allure.step(
                "lexClient RecognizeText Call",
                () -> lexClient.recognizeText(request)
        );

        final var content = getBotResponse(response);
        assertNotNull(content);

        Allure.addAttachment("Lex Response", "text/plain", content);
        log.info("<<< response: \"{}\"", content);
        return response;
    }

    /**
     * Given a Lex Response, extract what the BOT response was. In some cases it
     * will be in the session state for a Close Dialog action.
     *
     * @param response
     * @return
     */
    protected String getBotResponse(RecognizeTextResponse response) {

        if (response.hasMessages()) {
            // Normal response, return the message
            return response.messages().stream()
                    .map(Message::content)
                    .reduce("", (a, b) -> a + " " + b)
                    .trim();
        }

        if (response.sessionState().hasSessionAttributes()) {
            // Our tools put responses in bot_response in the session attributes
            return response.sessionState().sessionAttributes().get("bot_response");
        }

        // There was no message or no bot_response in session (shouldn't happen)
        return null;
    }

    protected final RecognizeTextResponse sendToLex(String text, String sessionId) {
        return sendToLex(text, getChannel(), sessionId);
    }

    protected final RecognizeTextResponse sendToLex(String text, ChannelPlatform channel) {
        return sendToLex(text, channel, getSessionId());
    }

    protected final RecognizeTextResponse sendToLex(String text) {
        return sendToLex(text, getChannel(), getSessionId());
    }

    protected final String getBotAction(RecognizeTextResponse response) {
        return getSessionAttribute(response, "action");
    }

    protected final String getSessionAttribute(RecognizeTextResponse response, String attribute) {
        if (response.sessionState().hasSessionAttributes()) {
            // Our tools put their name in the action attribute when called
            return response.sessionState().sessionAttributes().get(attribute);
        }
        return null;
    }

    /**
     * Override when you don't want to use the default session ID provided. IE,
     * to perform at set of tests with your own unique session ID.
     *
     * @return
     */
    protected String getSessionId() {
        return SESSION_ID;
    }

    /**
     * Override in a Test class to use that channel for all requests.
     *
     * @return
     */
    protected ChannelPlatform getChannel() {
        return ChannelPlatform.TWILIO;
    }

    @Test
    @Order(Integer.MIN_VALUE)          // runs before all other @Order'd tests
    @Epic("Warmup")   // keeps all warmup tests in their own Allure group
    @Tag("warmup")      // so the TimingExtension can recognize it
    @DisplayName("Warm Up the Stack")
    void warmupStack() {
        // Warm up the lex path and lambda so everything is hot and use a distinct session ID
        assertNotNull(getBotResponse(sendToLex("Hello, what is your name?", UUID.randomUUID().toString())));
    }

    @Test
    @Order(Integer.MAX_VALUE)  // Always Last
    @Epic("Summary")
    @Tag("summary")
    @DisplayName("Performance Summary")
    void performanceSummary() {
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

}
