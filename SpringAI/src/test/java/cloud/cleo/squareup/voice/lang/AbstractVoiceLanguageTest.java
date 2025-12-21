package cloud.cleo.squareup.voice.lang;

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
import org.opentest4j.TestAbortedException;
import static software.amazon.awssdk.services.lexruntimev2.model.DialogActionType.CLOSE;

/**
 * Start conversation in a particular language and then send some simple tests that will contain english chars to
 * validate, then sub classes can do a couple more language tests in the target language.
 *
 * @author sjensen
 */
@Log4j2
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

        Allure.description("""
                           ## Ask to speak in the target language
                           - Assert that proper tool is called to switch languages
                           - Assert that the exact language (enum) was passed correctly to the tool call
                           - Assert that the lex Dialog has closed (guarantees Chime is back in control of the call)
                           Upon Success, Chime engages the proper LexBot in the target Locale.
                           If this test fails, remaining tests are skipped since they are predicated on speaking the target language
                           """);

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

            assertTrue(res.sessionState().dialogAction().type().equals(CLOSE),
                    "Dialog state is not closed [" + res.sessionState().dialogAction().type() + "]"
            );

            Allure.addAttachment("Dialog Action", res.sessionState().dialogAction().toString());

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

        Allure.description("""
                           ## Ask what the Bot's name is
                           - Assert that response contains "Copper Bot"
                           - Assert that response does not contain English words (name,store,helpful,assistant,etc..)
                           """);

        final var res = sendToLex(
                getWhatIsYourName()
        );

        assertMatchesRegex(COPPER_BOT_PATTERN, getBotResponse(res));

        assertNotMatchesRegex(Pattern.compile("(name\\b|store|assistant\\b)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), SESSION_ID);
    }

    // Some english words that come back, should never be seen in different languages
    private final static Pattern ENGLISH_CHECK = Pattern.compile("(open\\b|opened\\b|started\\b|year\\b|store\\b|doors\\b|october\\b)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @Test
    @Order(-50)
    @DisplayName("Store Open Year")
    public void storeOpenYearTest() {
        Allure.feature(ALLURE_FEATURE_STORE_KNOWLEDGE);
        Allure.parameter("Language", getTestLanguage().toString());

        Allure.description("""
                           ## Ask what year the store opened
                           - Assert that response contains "2021" 
                           - Assert that response does not contain English words (Yes,open,started,october,etc.)
                           """);

        final var res = sendToLex(
                getWhenDidStoreOpen()
        );

        assertMatchesRegex(COPPER_FOX_OPEN_YEAR, getBotResponse(res));

        // Should Not respond in English, so check for english words
        assertNotMatchesRegex(ENGLISH_CHECK, getBotResponse(res));
    }

    @Test
    @Order(-25)
    @DisplayName("Inventory Candles")
    public void candlesTest() {
        Allure.feature(ALLURE_FEATURE_TOOL_CALL);
        Allure.feature(ALLURE_FEATURE_SQUARE_API);
        Allure.parameter("Language", getTestLanguage().toString());

        Allure.description("""
                           ## Ask if Candles are in stock
                           - Assert that response contains "yes" in the naitive language
                             - This ensures the query search term was translated to English properly for the search to succeed
                           - Assert that response does not contain English words (Yes,Candle,in stock,etc.)
                           """);

        final var res = sendToLex(
                getDoYouHaveCandlesInStock()
        );

        assertMatchesRegex(getYesPattern(), getBotResponse(res));

        assertNotMatchesRegex(Pattern.compile("(yes\\b|candle\\b|stock\\b)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), SESSION_ID);
    }

    @Test
    @Order(-10)
    @DisplayName("City RAG Complaint Search")
    public void cityRagTest() {
        Allure.feature(ALLURE_FEATURE_TOOL_CALL);
        Allure.feature(ALLURE_FEATURE_RAG);
        Allure.parameter("Language", getTestLanguage().toString());

        Allure.description("""
                           ## Ask if the City has a complaint process
                           The model must translate "complaint process" to English before calling the tool for RAG query
                           - Assert that response contains "yes" in the naitive language 
                           - Assert that response does not contain English words (Yes,complaint,found,etc.)
                           """);

        final var res = sendToLex(
                getCityComplaintProcess()
        );

        assertMatchesRegex(getYesPattern(), getBotResponse(res));

        assertNotMatchesRegex(Pattern.compile("(yes\\b|complaint|found\\b)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), SESSION_ID);
    }

    @Test
    @Order(Integer.MAX_VALUE - 100)
    @DisplayName("Hang Up")
    void hangupTest() {
        Allure.feature(ALLURE_FEATURE_TOOL_CALL);
        Allure.feature(ALLURE_FEATURE_CHIME_CC);
        Allure.parameter("Language", getTestLanguage().toString());

        
          Allure.description("""
                           ## Indicate we are all done with the call
                           - Assert that proper tool is called to end the call
                           - Assert that the lex Dialog has closed (guarantees Chime is back in control of the call)
                             - Chime would then hang up on the caller
                           """);
        
        final var res = sendToLex(
                getThankYouAllDone()
        );

        // Bot should have called hangup action
        assertTrue(HANGUP_FUNCTION_NAME.equals(getBotAction(res)),
                "Bot did not execute " + HANGUP_FUNCTION_NAME + " action when told done in " + getTestLanguage());

        assertTrue(res.sessionState().dialogAction().type().equals(CLOSE),
                "Dialog state is not closed [" + res.sessionState().dialogAction().type() + "]"
        );

        Allure.addAttachment("Lex Dialog Action", res.sessionState().dialogAction().toString());
    }

    /**
     * The language we will be switching to.
     *
     * @return
     */
    protected abstract Language getTestLanguage();

    // Hello, what is your name?
    protected abstract String getWhatIsYourName();

    // What year did the store first open?
    protected abstract String getWhenDidStoreOpen();

    // Thank you for all your help, that's all for today, goodbye.
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
