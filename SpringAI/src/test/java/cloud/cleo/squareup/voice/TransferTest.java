package cloud.cleo.squareup.voice;

import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_VOICE;
import static cloud.cleo.squareup.tools.AbstractTool.TRANSFER_FUNCTION_NAME;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Ensure Bot calls transfer when caller wants to speak to real person.
 * 
 * @author sjensen
 */
@Epic(ALLURE_EPIC_VOICE)
public class TransferTest extends AbstractVoiceTest {
    
    @Test
    @Order(1)
    @Feature(ALLURE_FEATURE_TOOL_CALL)
    @Feature(ALLURE_FEATURE_CHIME_CC)
    @DisplayName("Transfer to Person")
    void hangupTest() {

        final var res = sendToLex(
                "Please transfer me to a real person."
        );
        
        // Bot should have called transfer action
        assertTrue(TRANSFER_FUNCTION_NAME.equals(getBotAction(res)),
                "Bot did not execute " + TRANSFER_FUNCTION_NAME + " action when told speak to real person");
       
    }
}
