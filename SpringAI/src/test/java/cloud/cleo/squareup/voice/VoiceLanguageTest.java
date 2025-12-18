package cloud.cleo.squareup.voice;

import static cloud.cleo.squareup.tools.AbstractTool.SWITCH_LANGUAGE_FUNCTION_NAME;
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
@Epic("Voice Tests")
public class VoiceLanguageTest extends AbstractVoiceTest {
    
    @Test
    @Order(1)
    @Feature("Tool Call")
    @Feature("Chime Call Control")
    @DisplayName("Language Switch Test")
    void hangupTest() {

        final var res = sendToLex(
                "Let's converse in Spanish now please"
        );
        
        // Bot should have called transfer action
        assertTrue(SWITCH_LANGUAGE_FUNCTION_NAME.equals(getBotAction(res)),
                "Bot did not execute " + SWITCH_LANGUAGE_FUNCTION_NAME + " action when told done");
       
    }
}
