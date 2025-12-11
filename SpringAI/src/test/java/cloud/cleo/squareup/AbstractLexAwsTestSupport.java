package cloud.cleo.squareup;

import cloud.cleo.squareup.enums.ChannelPlatform;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Step;
import io.qameta.allure.junit5.AllureJunit5;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.log4j.Log4j2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lexruntimev2.LexRuntimeV2Client;
import software.amazon.awssdk.services.lexruntimev2.model.Message;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeTextRequest;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeTextResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.SsmException;

@ExtendWith({AllureJunit5.class, TimingExtension.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Log4j2
abstract class AbstractLexAwsTestSupport {

    // guard so we only init once per JVM
    private static final AtomicBoolean FULLY_INITTED = new AtomicBoolean(false);

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
    private static boolean awsReady = false;
    // Model being used
    public static String SPRING_AI_MODEL = System.getenv("SPRING_AI_MODEL");

    protected long INTER_TEST_DELAY_MS = 500L;

    @BeforeAll
    static void initAws() {
        region = Region.of(AWS_REGION);

        // For pipelines, sam build will always try and run tests, so unless RUN_TESTS is true, don't run
        Assumptions.assumeTrue(RUN_TESTS, "RUN_TESTS env var not true, skipping all tests");

        // fast path: if we already initted once, just bail out
        if (FULLY_INITTED.get()) {
            log.debug("Lex test support already fully initted; skipping init");
            return;
        }

        // slow path: only one thread does the real work
        synchronized (AbstractLexAwsTestSupport.class) {
            if (FULLY_INITTED.get()) {
                // someone else finished while we were waiting
                return;
            }

            // 1) Check credentials provider
            var credsProvider = DefaultCredentialsProvider.create();
            try {
                credsProvider.resolveCredentials();
                log.info("AWS credentials resolved successfully for LexE2ETests");
                awsReady = true;
            } catch (SdkClientException e) {
                log.warn("Skipping LexE2ETests: cannot resolve AWS credentials: {}", e.getMessage());
                awsReady = false;
            }
            Assumptions.assumeTrue(awsReady, "Skipping LexE2ETests: cannot resolve AWS credentials");

            // 2) Fetch SSM params
            try (var ssm = SsmClient.builder()
                    .region(region)
                    .credentialsProvider(credsProvider)
                    .build()) {

                botId = getParam(ssm, "/" + STACK_NAME + "/BOT_ID");
                botAliasId = getParam(ssm, "/" + STACK_NAME + "/BOT_ALIAS_ID");
                awsReady = true;
            } catch (SsmException e) {
                log.warn("Skipping LexE2ETests: failed to fetch SSM params for stack {} : {}",
                        STACK_NAME, e.awsErrorDetails().errorMessage());
                awsReady = false;
            } catch (SdkClientException e) {
                log.warn("Skipping LexE2ETests: SSM client error: {}", e.getMessage());
                awsReady = false;
            }
            Assumptions.assumeTrue(awsReady, "Skipping LexE2ETests: cannot fetch SSM parameters");

            // 3) Build Lex client
            try {
                lexClient = LexRuntimeV2Client.builder()
                        .region(region)
                        .credentialsProvider(credsProvider)
                        .build();
                awsReady = true;
            } catch (SdkClientException e) {
                log.warn("Skipping LexE2ETests: cannot create Lex client: {}", e.getMessage());
                awsReady = false;
            }

            Assumptions.assumeTrue(awsReady, "Skipping LexE2ETests: cannot init LexRuntimeV2 client");

            FULLY_INITTED.set(true);
            log.info(
                    "Lex test support fully initted for stack '{}' in region '{}' (botId={}, aliasId={})",
                    STACK_NAME, AWS_REGION, botId, botAliasId
            );
        }
    }

    @Test
    @Order(-1)          // runs before all other @Order'd tests
    @Epic("Warmup")   // keeps all warmup tests in their own Allure group
    @Tag("warmup")      // so the TimingExtension can recognize it
    void warmupStack() {
        // Warm up the lex path and lambda so everyhign is hot and use a distinct session ID
        sendToLex("Warmup", "Hello, what is your name?", UUID.randomUUID().toString());
    }

    @BeforeEach
    void addCommonAllureLabels() {
        if (SPRING_AI_MODEL != null) {
            Allure.label("SpringAIModel", SPRING_AI_MODEL);
            Allure.parameter("SpringAIModel", SPRING_AI_MODEL);
        }

        // Show up under "Labels" in the test Overview
        Allure.label("region", AWS_REGION);

        // Show up under "Parameters" in Execution
        Allure.parameter("Region", AWS_REGION);
        Allure.parameter("SessionId", SESSION_ID);
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
    protected final String sendToLex(String label, String text, String sessionId, ChannelPlatform channel) {
        Allure.addAttachment("Lex Request", "text/plain", text);
        log.info(">>> [{}] request: \"{}\"", label, text);

        var request = RecognizeTextRequest.builder()
                .botId(botId)
                .botAliasId(botAliasId)
                .localeId(LOCALE_ID)
                .sessionId(sessionId != null ? sessionId : getSessionId())
                .requestAttributes(channel != null ? Map.of("x-amz-lex:channels:platform", channel.getChannel()) : Map.of())
                .text(text)
                .build();

        RecognizeTextResponse response = Allure.step(
                "lexClient RecognizeText Call",
                () -> lexClient.recognizeText(request)
        );

        List<Message> messages = response.messages();
        assertNotNull(messages, "Lex returned null messages list");

        var content = messages.stream()
                .map(Message::content)
                .reduce("", (a, b) -> a + " " + b)
                .trim();

        Allure.addAttachment("Lex Response", "text/plain", content);
        log.info("<<< [{}] response: \"{}\"", label, content);
        return content;
    }

    protected final String sendToLex(String label, String text, String sessionId) {
        // Default channel to Twilio which will be text/SMS
        return sendToLex(label, text, sessionId, ChannelPlatform.TWILIO);
    }

    protected final String sendToLex(String label, String text) {
        return sendToLex(label, text, getSessionId(), null);
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

}
