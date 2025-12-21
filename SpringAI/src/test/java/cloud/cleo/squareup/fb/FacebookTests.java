/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.fb;

import cloud.cleo.squareup.AbstractLexAwsTestSupport;
import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_FACEBOOK;
import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_FEATURE_STORE_KNOWLEDGE;
import cloud.cleo.squareup.enums.ChannelPlatform;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import lombok.extern.log4j.Log4j2;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Some simple tests that validate Facebook Channel.
 *
 * @author sjensen
 */
@Log4j2
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Epic(ALLURE_EPIC_FACEBOOK)
public class FacebookTests extends AbstractLexAwsTestSupport {

    private final static String FACEBOOK_ID = "854474112";

    @Test
    @Order(2)
    @Feature(ALLURE_FEATURE_STORE_KNOWLEDGE)
    @DisplayName("Restaurant Recommendation")
    public void facebookNameTest() {

        final var res = sendToLex(
                "What is my name?"
        );

        final var steve = getBotResponse(res);

        boolean ok = steve.toLowerCase().contains("steve");
        log.info(ok ? "FB Name Test Passed" : "Muggs Restaurant Test FAILED");
        assertTrue(ok, "FB Name test failed, response was: " + steve);
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
