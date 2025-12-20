package cloud.cleo.squareup.voice;

import cloud.cleo.squareup.AbstractLexAwsTestSupport;
import cloud.cleo.squareup.enums.ChannelPlatform;
import io.qameta.allure.Allure;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Simple voice flow where we ask a couple questions.
 *
 * @author sjensen
 */
@Log4j2
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractVoiceTest extends AbstractLexAwsTestSupport {

    // Use one random session ID for this voice session
    private final String SESSION_ID = UUID.randomUUID().toString();

    @Test
    @Order(-100)
    @DisplayName("Bot Name")
    public void botNameTest() {
        Allure.feature(ALLURE_FEATURE_STORE_KNOWLEDGE);
        
        final var res = sendToLex(
                "Hello what is your name?"
        );

        final var name = getBotResponse(res);

        assertTrue(name.toLowerCase().matches("(?s).*?(copper bot).*"),
                "Name test failed, response was: " + name);
    }

    @Test
    @Order(-50)
    @DisplayName("Store Open Year")
    public void storeOpenYearTest() {
        Allure.feature(ALLURE_FEATURE_STORE_KNOWLEDGE);

        final var res = sendToLex(
                "when did the store first open?"
        );

        final var open = getBotResponse(res);

        assertTrue( open.toLowerCase().matches("(?s).*?(2021|october).*"),
                "Open Year test failed, response was: " + open);
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
