package cloud.cleo.squareup.voice;

import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_VOICE;
import static cloud.cleo.squareup.tools.AbstractTool.TRANSFER_FUNCTION_NAME;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import static software.amazon.awssdk.services.lexruntimev2.model.DialogActionType.CLOSE;

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
    void transferTest() {
        
         Allure.description("""
                           ## Ask to talk to a real person
                           - Assert that proper tool is called to transfer the call
                           - Assert that the lex Dialog has closed (guarantees Chime is back in control of the call)
                             - Chime would then transfer the call to the target number (store main number)
                           """);


        final var res = sendToLex(
                "Please transfer me to a real person."
        );
        
        // Bot should have called transfer action
        assertTrue(TRANSFER_FUNCTION_NAME.equals(getBotAction(res)),
                "Bot did not execute " + TRANSFER_FUNCTION_NAME + " action when told speak to real person");
        
        assertTrue(res.sessionState().dialogAction().type().equals(CLOSE),
                "Dialog state is not closed [" + res.sessionState().dialogAction().type() + "]"
        );

        Allure.addAttachment("Dialog Action", res.sessionState().dialogAction().toString());
       
    }
}
