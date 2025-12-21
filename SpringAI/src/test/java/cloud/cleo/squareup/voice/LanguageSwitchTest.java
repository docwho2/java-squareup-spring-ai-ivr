package cloud.cleo.squareup.voice;

import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_VOICE;
import cloud.cleo.squareup.enums.Language;
import static cloud.cleo.squareup.tools.AbstractTool.SWITCH_LANGUAGE_FUNCTION_NAME;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import static software.amazon.awssdk.services.lexruntimev2.model.DialogActionType.CLOSE;

/**
 * Ensure bot switches language and closes dialog
 *
 * @author sjensen
 */
@Epic(ALLURE_EPIC_VOICE)
public class LanguageSwitchTest extends AbstractVoiceTest {

    @Test
    @Order(1)
    @Feature(ALLURE_FEATURE_TOOL_CALL)
    @Feature(ALLURE_FEATURE_CHIME_CC)
    @DisplayName("Language Switch")
    void languageSwitchTest() {

        Allure.description("""
                           ## Ask to speak in Spanish
                           - Assert that proper tool is called to switch languages
                           - Assert that the exact language (enum) was passed correctly to the tool call for Spanish
                           - Assert that the lex Dialog has closed (guarantees Chime is back in control of the call)
                             - Chime would then start a new LexSession with Spanish Locale
                           """);

        final var res = sendToLex(
                "Let's converse in Spanish now please"
        );

        // Bot should have called transfer action
        assertTrue(SWITCH_LANGUAGE_FUNCTION_NAME.equals(getBotAction(res)),
                "Bot did not execute " + SWITCH_LANGUAGE_FUNCTION_NAME + " action when told to speak Spanish");

        // Make sure they switched to the correct language 
        assertTrue(Language.Spanish.toString().equalsIgnoreCase(getSessionAttribute(res, "language")),
                "Bot did not switch to " + Language.Spanish);

        assertTrue(res.sessionState().dialogAction().type().equals(CLOSE),
                "Dialog state is not closed [" + res.sessionState().dialogAction().type() + "]"
        );

        Allure.addAttachment("Lex Dialog Action", res.sessionState().dialogAction().toString());

    }
}
