package cloud.cleo.squareup.voice;

import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_VOICE;
import static cloud.cleo.squareup.tools.AbstractTool.HANGUP_FUNCTION_NAME;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Ensure Bot calls hangup when we're done.
 * 
 * @author sjensen
 */
@Epic(ALLURE_EPIC_VOICE)
public class HangupTest extends AbstractVoiceTest {
    
    @Test
    @Order(1)
    @Feature(ALLURE_FEATURE_TOOL_CALL)
    @Feature(ALLURE_FEATURE_CHIME_CC)
    @DisplayName("Hang Up")
    void hangupTest() {

        final var res = sendToLex(
                "Thank you for all your help, that's it for today, good bye."
        );
        
        // Bot should have called hangup action
        assertTrue(HANGUP_FUNCTION_NAME.equals(getBotAction(res)),
                "Bot did not execute " + HANGUP_FUNCTION_NAME + " action when told done");
        
        final var bye = getBotResponse(res);

        boolean ok = bye.toLowerCase().matches("(?s).*bye.*");
        assertTrue(ok, "Bot response did not contain bye as instructed to");
    }
}
