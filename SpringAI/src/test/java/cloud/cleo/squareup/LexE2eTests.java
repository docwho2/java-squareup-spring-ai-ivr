package cloud.cleo.squareup;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.UUID;
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
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lexruntimev2.LexRuntimeV2Client;
import software.amazon.awssdk.services.lexruntimev2.model.Message;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeTextRequest;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeTextResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.SsmException;

@ExtendWith(TimingExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LexE2ETests {

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

    private static final long INTER_TEST_DELAY_MS = 500L;

    @BeforeAll
    static void initAws() {
        region = Region.of(AWS_REGION);

        // 1) Check credentials provider
        var credsProvider = DefaultCredentialsProvider.create();
        try {
            credsProvider.resolveCredentials();
            System.out.println("AWS credentials resolved successfully for LexE2ETests");
        } catch (SdkClientException e) {
            System.out.println("Skipping LexE2ETests: cannot resolve AWS credentials");
            awsReady = false;
        }
        Assumptions.assumeTrue(awsReady,"Skipping LexE2ETests: cannot resolve AWS credentials");

        // 2) Fetch SSM params
        try (var ssm = SsmClient.builder()
                .region(region)
                .credentialsProvider(credsProvider)
                .build()) {

            botId = getParam(ssm, "/" + STACK_NAME + "/BOT_ID");
            botAliasId = getParam(ssm, "/" + STACK_NAME + "/BOT_ALIAS_ID");

        } catch (SsmException e) {
            System.out.println("Skipping LexE2ETests: failed to fetch SSM params for stack "
                    + STACK_NAME + " : " + e.awsErrorDetails().errorMessage());
            awsReady = false;
            return;
        } catch (SdkClientException e) {
            System.out.println("Skipping LexE2ETests: SSM client error: " + e.getMessage());
            awsReady = false;
            return;
        }

        // 3) Build Lex client
        try {
            lexClient = LexRuntimeV2Client.builder()
                    .region(region)
                    .credentialsProvider(credsProvider)
                    .build();
        } catch (SdkClientException e) {
            System.out.println("Skipping LexE2ETests: cannot create Lex client: " + e.getMessage());
            awsReady = false;
            return;
        }

        awsReady = true;
        System.out.printf(
                "LexE2ETests initialized for stack '%s' in region '%s' (botId=%s, aliasId=%s)%n",
                STACK_NAME, AWS_REGION, botId, botAliasId
        );
    }

    @AfterEach
    void delayBetweenTests() throws InterruptedException {
        // This will run after every test method
        Thread.sleep(INTER_TEST_DELAY_MS);
    }

    private static String getParam(SsmClient ssm, String name) {
        System.out.println("Fetching SSM parameter: " + name);
        var request = GetParameterRequest.builder()
                .name(name)
                .withDecryption(true)
                .build();
        return ssm.getParameter(request).parameter().value();
    }

    private String sendToLex(String label, String text) {
        System.out.printf(">>> [%s] request: \"%s\"%n", label, text);

        var request = RecognizeTextRequest.builder()
                .botId(botId)
                .botAliasId(botAliasId)
                .localeId(LOCALE_ID)
                .sessionId(SESSION_ID)
                .text(text)
                .build();

        RecognizeTextResponse response = lexClient.recognizeText(request);
        List<Message> messages = response.messages();
        assertNotNull(messages, "Lex returned null messages list");

        var content = messages.stream()
                .map(Message::content)
                .reduce("", (a, b) -> a + " " + b)
                .trim();

        System.out.printf("<<< [%s] response: \"%s\"%n", label, content);
        return content;
    }

    @Test
    @Order(1)
    @Tag("smoke")
    @Tag("e2e")
    @DisplayName("Chuckles Candy Test")
    void chucklesCandyTest() {
        assumeTrue(awsReady, "Skipping LexE2ETests because AWS is not configured/available");

        String chuckles = sendToLex(
                "Chuckles",
                "Do you have Chuckles Candy in stock?"
        );
        boolean ok = chuckles.matches("(?s).*?(Yes|We have|Chuckles).*");
        System.out.println(ok ? "Chuckles Test Passed" : "Chuckles Test FAILED");
        assertTrue(ok, "Chuckles test failed, response was: " + chuckles);
    }

    @Test
    @Order(2)
    @Tag("smoke")
    @Tag("e2e")
    @DisplayName("Restaurant Recommendation Test")
    void restaurantTest() {
        assumeTrue(awsReady, "Skipping LexE2ETests because AWS is not configured/available");

        String muggs = sendToLex(
                "Restaurant",
                "Please recommend a restaurant in the area?"
        );
        boolean ok = muggs.toLowerCase().contains("mugg");
        System.out.println(ok ? "Muggs Restaurant Test Passed" : "Muggs Restaurant Test FAILED");
        assertTrue(ok, "Muggs restaurant test failed, response was: " + muggs);
    }

    @Test
    @Order(3)
    @Tag("smoke")
    @Tag("e2e")
    @DisplayName("Address Test")
    void addressTest() {
        assumeTrue(awsReady, "Skipping LexE2ETests because AWS is not configured/available");

        String address = sendToLex(
                "Address",
                "What is your address?"
        );
        boolean ok = address.matches("(?s).*160\\s+Main.*");
        System.out.println(ok ? "Address Test Passed" : "Address Test FAILED");
        assertTrue(ok, "Address test failed, response was: " + address);
    }

    @Test
    @Order(4)
    @Tag("smoke")
    @Tag("e2e")
    @DisplayName("Staff Test")
    void staffTest() {
        assumeTrue(awsReady, "Skipping LexE2ETests because AWS is not configured/available");

        String staff = sendToLex(
                "Staff",
                "Does Steve work there?  If so, just say Yes"
        );
        boolean ok = staff.matches("(?is).*?(jensen|yes|copperfoxgifts|indeed|confirm).*");
        System.out.println(ok ? "Staff Test Passed" : "Staff Test FAILED");
        assertTrue(ok, "Staff test failed, response was: " + staff);
    }

}
