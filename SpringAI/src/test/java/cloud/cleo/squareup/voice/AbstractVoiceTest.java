package cloud.cleo.squareup.voice;


import cloud.cleo.squareup.AbstractLexAwsTestSupport;
import cloud.cleo.squareup.enums.ChannelPlatform;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Simple voice flow where we ask a couple questions.
 *
 * @author sjensen
 */
@Log4j2
@Epic("Voice Tests")
public abstract class AbstractVoiceTest extends AbstractLexAwsTestSupport {

    // Use one random sessiond for this voice session
    private static final String SESSION_ID = UUID.randomUUID().toString();

    @Test
    @Order(-100)
    @Feature("Store Knowledge")
    @DisplayName("Name Test")
    void nameTest() {

        final var res = sendToLex(
                "Hello what is your name?"
        );

        final var name = getBotResponse(res);

        boolean ok = name.toLowerCase().matches("(?s).*?(copper bot).*");
        log.info(ok ? "Name Test Passed" : "Name Test FAILED");
        assertTrue(ok, "Name test failed, response was: " + name);
    }

    @Test
    @Order(-50)
    @Feature("Store Knowledge")
    @DisplayName("Open Year Test")
    void openTest() {

        final var res = sendToLex(
                "when did the store first open?"
        );

        final var open = getBotResponse(res);

        // Store is never open on Monday's
        boolean ok = open.toLowerCase().matches("(?s).*?(2021|october).*");
        log.info(ok ? "Open Year Test Passed" : "Open Test FAILED");
        assertTrue(ok, "Open Yeara test failed, response was: " + open);
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
