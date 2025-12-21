package cloud.cleo.squareup.sms.lang;

import cloud.cleo.squareup.AbstractLexAwsTestSupport;
import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_FEATURE_TOOL_CALL;
import cloud.cleo.squareup.enums.ChannelPlatform;
import cloud.cleo.squareup.enums.Language;
import io.qameta.allure.Allure;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * For SMS, language detection is all done at the model via prompting to detect
 * and maintain that language during the whole session.  So we start the test 
 * with the inventory search which should make it very clear what language we 
 * are speaking.
 *
 * @author sjensen
 */
@Log4j2
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractSmsLanguageTest extends AbstractLexAwsTestSupport {

    // Use one random session ID for this voice session
    private final String SESSION_ID = UUID.randomUUID().toString();


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

        assertMatchesRegex(getYesPattern(), getBotResponse(res));
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

        assertMatchesRegex(getYesPattern(), getBotResponse(res));
    }

    // Some english words that come back, should never be seen in different languages
    private final static Pattern ENGLISH_CHECK = Pattern.compile("(open |opened|started|year|store|doors)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    
    @Test
    @Order(50)
    @DisplayName("Store Open Year")
    public void storeOpenYearTest() {
        Allure.feature(ALLURE_FEATURE_STORE_KNOWLEDGE);
        Allure.parameter("Language", getTestLanguage().toString());

        final var res = sendToLex(
                getWhenDidStoreOpen()
        );
        final var botResponse = getBotResponse(res);
        
        // Should have the year
        assertMatchesRegex(COPPER_FOX_OPEN_YEAR, botResponse);
        
        // Should Not respond in English, so check for english words
        assertNotMatchesRegex(ENGLISH_CHECK, botResponse);
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
        return ChannelPlatform.TWILIO;
    }
}
