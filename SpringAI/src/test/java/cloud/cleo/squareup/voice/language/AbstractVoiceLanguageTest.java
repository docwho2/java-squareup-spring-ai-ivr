package cloud.cleo.squareup.voice.language;

import cloud.cleo.squareup.AbstractLexAwsTestSupport;
import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_FEATURE_CHIME_CC;
import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_FEATURE_TOOL_CALL;
import cloud.cleo.squareup.enums.ChannelPlatform;
import cloud.cleo.squareup.enums.Language;
import static cloud.cleo.squareup.tools.AbstractTool.HANGUP_FUNCTION_NAME;
import static cloud.cleo.squareup.tools.AbstractTool.SWITCH_LANGUAGE_FUNCTION_NAME;
import io.qameta.allure.Allure;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import lombok.extern.log4j.Log4j2;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.TestAbortedException;

/**
 * Start conversation in a particular language and then send some simple tests that will contain english chars to
 * validate, then sub classes can do a couple more language tests in the target language.
 *
 * @author sjensen
 */
@Log4j2
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractVoiceLanguageTest extends AbstractLexAwsTestSupport {

    private static final String INDICATE_TEST_METHOD = "indicateLanguageTest";

    // Use one random session ID for this voice session
    private final String SESSION_ID = UUID.randomUUID().toString();

    // We always start in English
    private Language lang = Language.English;

    // Gate: if the “indicate language” test fails, skip everything else in this class
    private final AtomicBoolean languageReady = new AtomicBoolean(true);

    @BeforeEach
    void abortIfLanguageNotReady(TestInfo testInfo) {
        // Don't block the gating test itself
        var isGateTest = testInfo.getTestMethod()
                .map(m -> INDICATE_TEST_METHOD.equals(m.getName()))
                .orElse(false);

        if (!isGateTest && !languageReady.get()) {
            throw new TestAbortedException(
                    "Skipping remaining tests: language setup failed for " + getTestLanguage()
            );
        }
    }

    @Test
    @Order(-1000)
    @DisplayName("Indicate Starting Language")
    void indicateLanguageTest() {

        Allure.feature(ALLURE_FEATURE_TOOL_CALL);
        Allure.feature(ALLURE_FEATURE_CHIME_CC);

        try {

            final var res = sendToLex(
                    getTestLanguage() + " language please"
            );

            // Bot should have called language action
            assertTrue(SWITCH_LANGUAGE_FUNCTION_NAME.equals(getBotAction(res)),
                    "Bot did not execute " + SWITCH_LANGUAGE_FUNCTION_NAME + " action when asked to speak " + getTestLanguage());

            // Make sure they switched to the correct language 
            assertTrue(getTestLanguage().toString().equalsIgnoreCase(getSessionAttribute(res, "language")),
                    "Bot did not switch to the correct language " + getTestLanguage());

            // Language switched, so now we must use the target locale for remaining tests in that language
            lang = getTestLanguage();

        } catch (AssertionError | RuntimeException e) {
            languageReady.set(false);

            // Optional: make the skip reason super obvious in Allure
            Allure.addAttachment("Language gate failed", "text/plain",
                    "Language setup failed for " + getTestLanguage() + "\n" + e);

            throw e; // keep THIS test as FAILED (red)
        }
    }

    @Test
    @Order(-100)
    @DisplayName("Bot Name")
    public void botNameTest() {
        Allure.feature(ALLURE_FEATURE_STORE_KNOWLEDGE);
        Allure.parameter("Language", getTestLanguage().toString());

        final var res = sendToLex(
                getWhatIsYourName()
        );

        final var name = getBotResponse(res);

        assertTrue(name.toLowerCase().matches("(?s).*?(copper bot|copper fox).*"),
                "Name test failed, response was: " + name);
    }

    @Test
    @Order(-50)
    @DisplayName("Store Open Year")
    public void storeOpenYearTest() {
        Allure.feature(ALLURE_FEATURE_STORE_KNOWLEDGE);
        Allure.parameter("Language", getTestLanguage().toString());

        final var res = sendToLex(
                getWhenDidStoreOpen()
        );

        final var open = getBotResponse(res);

        assertTrue(open.toLowerCase().matches("(?s).*?(2021).*"),
                "Open Year test failed, response was: " + open);
    }

    @Test
    @Order(-25)
    @DisplayName("Inventory Jackets")
    public void candlesTest() {
        Allure.feature(ALLURE_FEATURE_TOOL_CALL);
        Allure.feature(ALLURE_FEATURE_SQUARE_API);
        Allure.parameter("Language", getTestLanguage().toString());

        final var res = sendToLex(
                getDoYouHaveCandlesInStock()
        );

        final var jackets = getBotResponse(res);

        assertTrue(getYesPattern().matcher(jackets.toLowerCase()).find(),
                "Jacket response was: " + jackets);
    }

    @Test
    @Order(-10)
    @DisplayName("City RAG Complaint Search")
    public void cityRagTest() {
        Allure.feature(ALLURE_FEATURE_TOOL_CALL);
        Allure.feature(ALLURE_FEATURE_RAG);
        Allure.parameter("Language", getTestLanguage().toString());

        final var res = sendToLex(
                getCityComplaintProcess()
        );

        final var city = getBotResponse(res);

        assertTrue(getYesPattern().matcher(city.toLowerCase()).find(),
                "City RAG Complaint Serch response was: " + city);
    }

    @Test
    @Order(Integer.MAX_VALUE - 100)
    @DisplayName("Hang Up")
    void hangupTest() {
        Allure.feature(ALLURE_FEATURE_TOOL_CALL);
        Allure.feature(ALLURE_FEATURE_CHIME_CC);
        Allure.parameter("Language", getTestLanguage().toString());

        final var res = sendToLex(
                getThankYouAllDone()
        );

        // Bot should have called hangup action
        assertTrue(HANGUP_FUNCTION_NAME.equals(getBotAction(res)),
                "Bot did not execute " + HANGUP_FUNCTION_NAME + " action when told done in " + getTestLanguage());
    }

    /**
     * The language we will be switching to.
     *
     * @return
     */
    protected abstract Language getTestLanguage();

    protected abstract String getWhatIsYourName();

    protected abstract String getWhenDidStoreOpen();

    protected abstract String getThankYouAllDone();

    // Please check the store inventory for candles, respond with yes if you have them.
    protected abstract String getDoYouHaveCandlesInStock();
    
    // Does the city have a process for complaints?  respond with yes if there is one and what to do next.
    protected abstract String getCityComplaintProcess();

    protected abstract Pattern getYesPattern();

    /**
     * will start with english, then assuming first test passes, we will return the new testing language.
     *
     * @return
     */
    @Override
    protected final Language getLanguage() {
        return lang;
    }

    @Override
    protected String getSessionId() {
        return SESSION_ID;
    }

    /**
     * Voice channel testing
     *
     * @return
     */
    @Override
    protected ChannelPlatform getChannel() {
        return ChannelPlatform.CHIME;
    }
}
