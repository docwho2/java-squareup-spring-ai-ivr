package cloud.cleo.squareup.voice;

import static cloud.cleo.squareup.tools.AbstractTool.TRANSFER_FUNCTION_NAME;
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
public class VoiceTransferTest extends AbstractVoiceTest {
    
    @Test
    @Order(1)
    @Feature("Tool Call")
    @Feature("Chime Call Control")
    @DisplayName("Transfer Test")
    void hangupTest() {

        final var res = sendToLex(
                "Please transfer me to a real person."
        );
        
        // Bot should have called transfer action
        assertTrue(TRANSFER_FUNCTION_NAME.equals(getBotAction(res)),
                "Bot did not execute " + TRANSFER_FUNCTION_NAME + " action when told done");
       
    }
}
