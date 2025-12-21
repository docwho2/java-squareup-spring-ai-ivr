package cloud.cleo.squareup.fb;

import cloud.cleo.squareup.AbstractLexAwsTestSupport;
import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_FACEBOOK;
import static cloud.cleo.squareup.cloudfunctions.LexFunction.CLEAR_CHAT_HISTORY;
import cloud.cleo.squareup.enums.ChannelPlatform;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.regex.Pattern;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Some simple tests that validate Facebook Channel.
 *
 * @author sjensen
 */
@Log4j2
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Epic(ALLURE_EPIC_FACEBOOK)
public class FacebookTests extends AbstractLexAwsTestSupport {

    // My own FB ID (Steve Jensen)
    private final static String FACEBOOK_ID = "854474112";

    
    @Test
    @Order(1)
    @Feature(ALLURE_FEATURE_FACEBOOK)
    @DisplayName("Facebook Clear Memory")
    public void facebookClearTest() {

        final var res = sendToLex(
                CLEAR_CHAT_HISTORY
        );

        assertMatchesRegex(Pattern.compile("cleared"), getBotResponse(res));
    }
    
    @Test
    @Order(2)
    @Feature(ALLURE_FEATURE_FACEBOOK)
    @Feature(ALLURE_FEATURE_STORE_KNOWLEDGE)
    @DisplayName("Facebook Hello")
    public void facebookStoreNameTest() {

        final var res = sendToLex(
                "Hello, whats your name?"
        );
        
        // Validate name
        assertMatchesRegex(COPPER_BOT_PATTERN, getBotResponse(res));
        
        // Since this a new FB conversation, we should get a welcome card
        assertTrue(  res.messages().stream().anyMatch(m -> m.imageResponseCard() != null),
                "Response did not contain the initial Welcome Image Response Card"
                );
    }
    
    @Test
    @Order(3)
    @Feature(ALLURE_FEATURE_FACEBOOK)
    @DisplayName("Facebook Name")
    public void facebookUserNameTest() {

        final var res = sendToLex(
                "What is my name?  Just confirm it, not asking for any personal details."
        );

        assertMatchesRegex(Pattern.compile("steve"), getBotResponse(res));
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
