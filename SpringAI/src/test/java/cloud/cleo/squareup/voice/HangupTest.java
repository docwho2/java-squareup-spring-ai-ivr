package cloud.cleo.squareup.voice;

import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_VOICE;
import static cloud.cleo.squareup.tools.AbstractTool.HANGUP_FUNCTION_NAME;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import static software.amazon.awssdk.services.lexruntimev2.model.DialogActionType.CLOSE;

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
        
         Allure.description("""
                           ## Indicate we are all done with the call
                           - Assert that proper tool is called to end the call
                           - Assert that the lex Dialog has closed (guarantees Chime is back in control of the call)
                             - Chime would then hang up on the caller
                           """);

        final var res = sendToLex(
                "Thank you for all your help, that's it for today, good bye."
        );
        
        // Bot should have called hangup action
        assertTrue(HANGUP_FUNCTION_NAME.equals(getBotAction(res)),
                "Bot did not execute " + HANGUP_FUNCTION_NAME + " action when told done");
        
        assertTrue(res.sessionState().dialogAction().type().equals(CLOSE),
                "Dialog state is not closed [" + res.sessionState().dialogAction().type() + "]"
        );

        Allure.addAttachment("Lex Dialog Action", res.sessionState().dialogAction().toString());
        
    }
}
