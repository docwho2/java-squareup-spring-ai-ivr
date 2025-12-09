package cloud.cleo.squareup;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.lexruntimev2.LexRuntimeV2Client;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeTextRequest;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeTextResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LexE2eTests {

    private static String botId;
    private static String botAliasId;
    private static String sessionId;
    private static LexRuntimeV2Client lex;
    private static DefaultCredentialsProvider credProvider;

    @BeforeAll
    static void preconditions() {
        sessionId = System.getenv().getOrDefault("LEX_TEST_SESSION_ID", UUID.randomUUID().toString());

        // 1) Require AWS creds (skip if none)
        boolean hasAwsCreds;
        try {
            credProvider = DefaultCredentialsProvider.builder().build();
            credProvider.resolveCredentials();
            hasAwsCreds = true;
        } catch (SdkClientException e) {
            hasAwsCreds = false;
            credProvider = null;
        }
        Assumptions.assumeTrue(hasAwsCreds, "Skipping LexE2eTests: no AWS credentials");

        // 2) Get stack name (for /stack/BOT_ID etc)
        String stackName = System.getenv().getOrDefault("STACK_NAME","cfox-squareup-spring-ai-ivr");
        Assumptions.assumeTrue(stackName != null && !stackName.isBlank(),
                "Skipping LexE2eTests: STACK_NAME not set");

        // 3) Pull BOT_ID and BOT_ALIAS_ID from SSM Parameter Store
        try (var ssm = SsmClient.builder()
                .credentialsProvider(credProvider)
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build()) {

            botId = getParam(ssm, "/" + stackName + "/BOT_ID");
            botAliasId = getParam(ssm, "/" + stackName + "/BOT_ALIAS_ID");
        }

        Assumptions.assumeTrue(botId != null && !botId.isBlank()
                && botAliasId != null && !botAliasId.isBlank(),
                "Skipping LexE2eTests: BOT_ID/BOT_ALIAS_ID missing in SSM");

        // 4) Build Lex client using URLConnection (no CRT)
        lex = LexRuntimeV2Client.builder()
                .credentialsProvider(credProvider)
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }

    private static String getParam(SsmClient ssm, String name) {
        var resp = ssm.getParameter(GetParameterRequest.builder()
                .name(name)
                .withDecryption(true)
                .build());
        return resp.parameter().value();
    }

    @Test
    void chucklesCandyTest() {
        RecognizeTextRequest req = RecognizeTextRequest.builder()
                .botId(botId)
                .botAliasId(botAliasId)
                .localeId("en_US")
                .sessionId(sessionId)
                .text("Do you have Chuckles Candy in stock?")
                .build();

        RecognizeTextResponse resp = lex.recognizeText(req);
        String content = resp.messages().isEmpty()
                ? ""
                : resp.messages().get(0).content();

        assertThat(content)
                .containsIgnoringCase("chuckles")
                .matches("(?is).*\\b(yes|we have|in stock)\\b.*");
    }
}
