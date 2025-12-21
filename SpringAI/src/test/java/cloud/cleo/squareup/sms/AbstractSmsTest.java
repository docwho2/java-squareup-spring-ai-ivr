package cloud.cleo.squareup.sms;

import cloud.cleo.squareup.AbstractLexAwsTestSupport;
import cloud.cleo.squareup.enums.ChannelPlatform;
import io.qameta.allure.Allure;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Simple voice flow where we ask a couple questions.
 *
 * @author sjensen
 */
@Log4j2
public abstract class AbstractSmsTest extends AbstractLexAwsTestSupport {

    // Use one random session ID for this voice session
    private final String SESSION_ID = UUID.randomUUID().toString();

    @Test
    @Order(-100)
    @DisplayName("Bot Name")
    public void botNameTest() {
        Allure.feature(ALLURE_FEATURE_STORE_KNOWLEDGE);
        
        Allure.description("Assert response matches regex " + COPPER_BOT_PATTERN.pattern());

        final var res = sendToLex(
                "Hello, what is your name?"
        );

        assertMatchesRegex(COPPER_BOT_PATTERN, getBotResponse(res));
    }

    @Test
    @Order(-50)
    @DisplayName("Store Open Year")
    public void storeOpenYearTest() {
        Allure.feature(ALLURE_FEATURE_STORE_KNOWLEDGE);
        
        Allure.description("Assert response matches regex " + COPPER_FOX_OPEN_YEAR.pattern());

        final var res = sendToLex(
                "In what year did the store first open?"
        );

        final var open = getBotResponse(res);

        assertMatchesRegex(COPPER_FOX_OPEN_YEAR, getBotResponse(res));
    }

    @Override
    protected String getSessionId() {
        return SESSION_ID;
    }

    /**
     * SMS channel testing
     *
     * @return
     */
    @Override
    protected ChannelPlatform getChannel() {
        return ChannelPlatform.TWILIO;
    }
}
