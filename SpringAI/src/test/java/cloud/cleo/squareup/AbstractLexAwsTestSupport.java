package cloud.cleo.squareup;

import cloud.cleo.squareup.enums.ChannelPlatform;
import static cloud.cleo.squareup.enums.ChannelPlatform.*;
import cloud.cleo.squareup.enums.Language;
import cloud.cleo.squareup.enums.LexInputMode;
import io.qameta.allure.Allure;
import io.qameta.allure.junit5.AllureJunit5;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.log4j.Log4j2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lexruntimev2.LexRuntimeV2Client;
import software.amazon.awssdk.services.lexruntimev2.model.Message;
import static software.amazon.awssdk.services.lexruntimev2.model.MessageContentType.PLAIN_TEXT;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeTextRequest;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeTextResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

@ExtendWith({AllureJunit5.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Log4j2
public abstract class AbstractLexAwsTestSupport {
    
    protected final Map<String, String> sessionAttrs = new HashMap<>();

    // Statics for Allure features
    public final static String ALLURE_FEATURE_STORE_KNOWLEDGE = "Store Knowledge";
    public final static String ALLURE_FEATURE_TOOL_CALL = "Tool Call";
    public final static String ALLURE_FEATURE_SQUARE_API = "Square API";
    public final static String ALLURE_FEATURE_WEATHER_API = "Weather API";
    public final static String ALLURE_FEATURE_CHIME_CC = "Chime Call Control";
    public final static String ALLURE_FEATURE_RAG = "RAG Vector Search";
    public final static String ALLURE_FEATURE_FACEBOOK = "Facebook";
    public final static String ALLURE_FEATURE_GENERAL = "General";

    public final static String ALLURE_EPIC_SMOKE = "Smoke Tests";
    public final static String ALLURE_EPIC_WARM_UP = "Warm Up Tests";
    public final static String ALLURE_EPIC_VOICE = "Voice Tests";
    public final static String ALLURE_EPIC_SMS = "SMS Tests";
    public final static String ALLURE_EPIC_FACEBOOK = "Facebook Tests";
    public final static String ALLURE_EPIC_LANGUAGE = "Language Tests";
    public final static String ALLURE_EPIC_PERF_SUM = "Performance Summary";

    public final static String JUNIT_TAG_WARM_UP = "WarmUp";

    private static final boolean RUN_TESTS
            = Boolean.parseBoolean(System.getenv().getOrDefault("RUN_TESTS", "false"));

    private static final String AWS_REGION
            = System.getenv().getOrDefault("AWS_REGION", "us-east-1");

    private static final String STACK_NAME
            = System.getProperty(
                    "stack.name",
                    System.getenv().getOrDefault("STACK_NAME", "cfox-squareup-spring-ai-ivr")
            );

    private static final String SESSION_ID
            = System.getenv().getOrDefault("LEX_SESSION_ID", UUID.randomUUID().toString());

    private static LexRuntimeV2Client lexClient;
    private static String botId;
    private static String botAliasId;
    private static volatile boolean awsReady = false;

    // Model being used
    public static String SPRING_AI_MODEL = System.getenv("SPRING_AI_MODEL");

    public static long INTER_TEST_DELAY_MS = 500L;

    // used to detect MD in repsonses
    private static final Pattern MARKDOWN_PATTERN = Pattern.compile(
            "(?m)("
            + "^\\s{0,3}#{1,6}\\s+.+$"
            + // headings
            "|^\\s{0,3}([-*+]\\s+|\\d+\\.\\s+).+$"
            + // lists
            "|```|~~~"
            + // fenced code
            "|\\[[^\\]]+\\]\\([^)]+\\)"
            + // links
            "|`[^`]+`"
            + // inline code
            "|\\*\\*[^*]+\\*\\*"
            + // bold
            "|__[^_]+__"
            + // bold underscore
            ")"
    );

    // Common Patterns used to validate knowledge responses
    public final static Pattern COPPER_BOT_PATTERN = Pattern.compile("(copper bot|copper fox|bot )", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    public final static Pattern COPPER_FOX_OPEN_YEAR = Pattern.compile("(2021|21)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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

        
        // If Chime, then we add bogus calling number
        switch (channel) {
            case CHIME ->
                // Send invalid E164 so our session ID will be used and no actual SMS will be sent on tests (because hasValidE164 will be false)
                sessionAttrs.put("callingNumber", "+10000000000");
        }
        
        // Always clear bot_response before sending
        sessionAttrs.remove("bot_response");

        var request = RecognizeTextRequest.builder()
                .botId(botId)
                .botAliasId(botAliasId)
                .localeId(getLanguage().getLocale().toString())
                .sessionId(sessionId != null ? sessionId : getSessionId())
                // Always set a channel
                .requestAttributes(Map.of("x-amz-lex:channels:platform", channel.getChannel(), "testing_input", channel.equals(CHIME) ? LexInputMode.SPEECH.getMode() : LexInputMode.TEXT.getMode()))
                .sessionState(b -> b.sessionAttributes(sessionAttrs).build())
                .text(text)
                .build();

        RecognizeTextResponse response = Allure.step(
                "lexClient RecognizeText Call",
                () -> lexClient.recognizeText(request)
        );
        
        // Maintain session attributes across requests
        sessionAttrs.clear();
        if ( response.sessionState().hasSessionAttributes() ) {
            sessionAttrs.putAll(response.sessionState().sessionAttributes());
        }

        final var content = getBotResponse(response);
        assertNotNull(content);

        // Check for Markdown in responses
        if (!channel.equals(FACEBOOK) && MARKDOWN_PATTERN.matcher(content).find()) {
            Allure.label("tag", "Markdown Detected");
        }

        Allure.addAttachment("Lex Response", "text/plain", content);
        log.info("<<< response: \"{}\"", content);
        return response;
    }

    protected final void assertMatchesRegex(Pattern pattern, String actual) {
        if (actual == null) {
            fail("Actual was null (pattern=" + pattern.pattern() + ")");
            return;
        }

        if (!pattern.matcher(actual).find()) {
            Allure.addAttachment("Expected regex", "text/plain", pattern.pattern());

            fail("Did not match regex.\nRegex: " + pattern.pattern() + "\n"
                    + "Actual: " + actual);
        }
    }

    protected final void assertNotMatchesRegex(Pattern pattern, String actual) {
        if (actual == null) {
            fail("Actual was null (pattern=" + pattern.pattern() + ")");
            return;
        }

        if (pattern.matcher(actual).find()) {
            Allure.addAttachment("Unexpected regex", "text/plain", pattern.pattern());

            fail("Matched regex.\nRegex: " + pattern.pattern() + "\n"
                    + "Actual: " + actual);
        }
    }

    /**
     * Given a Lex Response, extract what the BOT response was. In some cases it will be in the session state for a
     * Close Dialog action.
     *
     * @param response
     * @return
     */
    protected String getBotResponse(RecognizeTextResponse response) {

        if (response.hasMessages()) {
            // Normal response, return the message
            return response.messages().stream()
                    // Should not count image response cards or anything else
                    .filter(m -> m.contentType().equals(PLAIN_TEXT))
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
     * Override when you don't want to use the default session ID provided. IE, to perform at set of tests with your own
     * unique session ID.
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

    /**
     * Override when testing other languages.
     *
     * @return
     */
    protected Language getLanguage() {
        return Language.English;
    }

}
