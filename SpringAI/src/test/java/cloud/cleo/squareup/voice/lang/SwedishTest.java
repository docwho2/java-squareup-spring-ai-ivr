/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.voice.lang;

import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_LANGUAGE;
import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_VOICE;
import cloud.cleo.squareup.enums.Language;
import io.qameta.allure.Epic;
import java.util.regex.Pattern;

/**
 *
 * @author sjensen
 */
@Epic(ALLURE_EPIC_LANGUAGE)
@Epic(ALLURE_EPIC_VOICE)
public class SwedishTest extends AbstractVoiceLanguageTest {

    private final static Pattern YES = Pattern.compile("(ja |ja,)");

    
    @Override
    protected Language getTestLanguage() {
        return Language.Swedish;
    }

    @Override
    protected String getWhatIsYourName() {
       return "Hallå, vad heter du?";
    }

    @Override
    protected String getWhenDidStoreOpen() {
        return "Vilket år öppnade butiken först?";
    }

    @Override
    protected String getThankYouAllDone() {
        return "Tack för all hjälp, det var allt för idag, adjö.";
    }

    @Override
    protected String getDoYouHaveCandlesInStock() {
        return "Kontrollera butikens lager för ljus, svara ja om du har några.";
    }

    @Override
    protected Pattern getYesPattern() {
        return YES;
    }

    @Override
    protected String getCityComplaintProcess() {
       return "Har staden en process för klagomål? Svara ja om det finns en och vad man ska göra härnäst.";
    }


    
}
