package cloud.cleo.squareup;

import cloud.cleo.squareup.enums.ChannelPlatform;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Link;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Some basic tests to run after a deployment and on a schedule just to validate
 * we can talk to Square via API and test the path from Lex to Lambda to model.
 *
 * @author sjensen
 */
@Log4j2
@Epic("Voice Tests")
public class VoiceTests extends AbstractLexAwsTestSupport {

    // Use one random sessiond for this voice session
    private static final String SESSION_ID = UUID.randomUUID().toString();

    @Test
    @Order(1)
    @Feature("Store Knowledge")
    @DisplayName("Name Test")
    void nameTest() {

        final var res = sendToLex(
                "Hello what is your name?"
        );

        final var name = getBotResponse(res);

        boolean ok = name.toLowerCase().matches("(?s).*?(copper bot).*");
        log.info(ok ? "Name Test Passed" : "Chuckles Test FAILED");
        assertTrue(ok, "Name test failed, response was: " + name);
    }

    @Test
    @Order(2)
    @Feature("SquareAPI")
    @Feature("Tool Call")
    @DisplayName("Are you open Test")
    @Link("https://github.com/docwho2/java-squareup-spring-ai-ivr/blob/main/SpringAI/src/main/java/cloud/cleo/squareup/tools/StoreHours.java")
    void openTest() {

        final var res = sendToLex(
                "Are you open on Monday?"
        );

        final var open = getBotResponse(res);

        // Store is never open on Monday's
        boolean ok = open.toLowerCase().matches("(?s).*?(no|not|closed).*");
        log.info(ok ? "Open Test Passed" : "Muggs Restaurant Test FAILED");
        assertTrue(ok, "Open test failed, response was: " + open);
    }

    @Test
    @Order(3)
    @Feature("Tool Call")
    @Feature("Chime Voice Control")
    @DisplayName("Hang Up Test")
    void hangupTest() {

        final var res = sendToLex(
                "Thank you for all your help, that's it for today, good bye."
        );

        log.info("res = {}",res);
        
        // Bot should have called hangup action
        assertTrue("hangup_call".equals(getBotAction(res)),"Bot did not execute hangup_call action when told done");
        
        final var bye = getBotResponse(res);

        boolean ok = bye.toLowerCase().matches("(?s).*bye.*");
        assertTrue(ok, "Bot response did not contain bye as instructed to");
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
