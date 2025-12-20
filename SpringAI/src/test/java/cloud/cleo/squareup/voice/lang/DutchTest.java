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
public class DutchTest extends AbstractVoiceLanguageTest {

    private final static Pattern YES = Pattern.compile("(ja |ja,)");

    
    @Override
    protected Language getTestLanguage() {
        return Language.Dutch;
    }

    @Override
    protected String getWhatIsYourName() {
       return "Hallo, wat is je naam?";
    }

    @Override
    protected String getWhenDidStoreOpen() {
        return "Wanneer is de winkel voor het eerst geopend?";
    }

    @Override
    protected String getThankYouAllDone() {
        return "Bedankt voor al jullie hulp, dat was het voor vandaag, tot ziens.";
    }

    @Override
    protected String getDoYouHaveCandlesInStock() {
        return "Controleer alstublieft de voorraad van de winkel op kaarsen en antwoord met 'ja' als u ze op voorraad heeft.";
    }

    @Override
    protected Pattern getYesPattern() {
        return YES;
    }

    @Override
    protected String getCityComplaintProcess() {
       return "Heeft de stad een procedure voor klachten? Antwoord met 'ja' als er een procedure is en leg uit wat de volgende stappen zijn.";
    }


    
}
