package cloud.cleo.squareup.fb;

import cloud.cleo.squareup.AbstractLexAwsTestSupport;
import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_FACEBOOK;
import static cloud.cleo.squareup.cloudfunctions.LexFunction.CLEAR_CHAT_HISTORY;
import cloud.cleo.squareup.enums.ChannelPlatform;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.regex.Pattern;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.awssdk.services.lexruntimev2.model.DialogActionType.CLOSE;

/**
 * Some simple tests that validate Facebook Channel.
 *
 * @author sjensen
 */
@Log4j2
@Epic(ALLURE_EPIC_FACEBOOK)
public class FacebookTests extends AbstractLexAwsTestSupport {

    // My own FB ID (Steve Jensen)
    private final static String FACEBOOK_ID = "854474112";

    private final static Pattern MY_NMAE_PATTERN = Pattern.compile("steve", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @Test
    @Order(1)
    @Feature(ALLURE_FEATURE_FACEBOOK)
    @DisplayName("Facebook Clear Memory")
    public void facebookClearTest() {
        Allure.description("""
                           ## Ensure Session is cleared for Facebook tests
                           - Clear all chat memory so tests are clean
                             - assert that response contains "cleared"
                           - We assert that lex dialog is CLOSED
                           """);

        final var res = sendToLex(
                CLEAR_CHAT_HISTORY
        );

        assertMatchesRegex(Pattern.compile("cleared"), getBotResponse(res));

        assertTrue(res.sessionState().dialogAction().type().equals(CLOSE),
                "Dialog state is not closed [" + res.sessionState().dialogAction().type() + "]"
        );

        Allure.addAttachment("Dialog Action", res.sessionState().dialogAction().toString());
    }

    @Test
    @Order(2)
    @Feature(ALLURE_FEATURE_FACEBOOK)
    @Feature(ALLURE_FEATURE_STORE_KNOWLEDGE)
    @DisplayName("Facebook Hello")
    public void facebookStoreNameTest() {
        Allure.description("""
                           ## Ask Store  name and ensure personalized response
                           This test has 3 assertions
                           - Copper Bot name must be in the response
                           - System prompt says to greet with name, so first name should also be in this initial response
                           - Since this a new Facebook session a welcome card should also be present in the response
                           """);

        final var res = sendToLex(
                "Hello, whats your name?"
        );

        // Validate Bot name
        assertMatchesRegex(COPPER_BOT_PATTERN, getBotResponse(res));

        // Since bot is instructed to use my first name, it should appear in the initial response as well
        assertMatchesRegex(MY_NMAE_PATTERN, getBotResponse(res));

        // Since this a new FB conversation, we should get a welcome card as well
        assertTrue(res.messages().stream().anyMatch(m -> m.imageResponseCard() != null),
                "Response did not contain the initial Welcome Image Response Card"
        );

        Allure.addAttachment("Welcome Card", res.messages().stream().filter(m -> m.imageResponseCard() != null).findAny().get().toString());
    }

    @Test
    @Order(3)
    @Feature(ALLURE_FEATURE_FACEBOOK)
    @DisplayName("Facebook Name")
    public void facebookUserNameTest() {
        Allure.description("""
                           ## Explicitly ask what my name
                           This test has 2 assertions
                           - It should repeat my name back (from FB API Call)
                           - Since this is NOT new Facebook session now, a welcome card should should not be in the response
                           """);

        final var res = sendToLex(
                "What is my name?  Just confirm it, not asking for any personal details."
        );

        assertMatchesRegex(MY_NMAE_PATTERN, getBotResponse(res));

        // Since this not a new FB conversation, we should not get a welcome card
        assertFalse(res.messages().stream().anyMatch(m -> m.imageResponseCard() != null),
                "Response contained Image Response Card"
        );
    }

    /**
     * Facebook uses your ID as the session, so hard coded to my own;
     *
     * @return
     */
    @Override
    protected String getSessionId() {
        return FACEBOOK_ID;
    }

    /**
     * Facebook channel testing
     *
     * @return
     */
    @Override
    protected ChannelPlatform getChannel() {
        return ChannelPlatform.FACEBOOK;
    }

}
