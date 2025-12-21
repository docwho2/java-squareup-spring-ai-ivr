/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.sms.lang;

import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_LANGUAGE;
import static cloud.cleo.squareup.AbstractLexAwsTestSupport.ALLURE_EPIC_SMS;
import cloud.cleo.squareup.enums.Language;
import io.qameta.allure.Epic;
import java.util.regex.Pattern;

/**
 *
 * @author sjensen
 */
@Epic(ALLURE_EPIC_LANGUAGE)
@Epic(ALLURE_EPIC_SMS)
public class GermanTest extends AbstractSmsLanguageTest {

    private final static Pattern YES = Pattern.compile("(ja |ja,)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    
    @Override
    protected Language getTestLanguage() {
        return Language.German;
    }

    @Override
    protected String getWhatIsYourName() {
       return "Hallo, wie heißt du?";
    }

    @Override
    protected String getWhenDidStoreOpen() {
        return "In welchem ​​Jahr wurde das Geschäft zum ersten Mal eröffnet?";
    }

    @Override
    protected String getThankYouAllDone() {
        return "Vielen Dank für Ihre Hilfe, das war's für heute, auf Wiedersehen.";
    }

    @Override
    protected String getDoYouHaveCandlesInStock() {
        return "Bitte überprüfen Sie den Lagerbestand des Geschäfts auf Kerzen und antworten Sie mit „Ja“, falls welche vorhanden sind.";
    }

    @Override
    protected Pattern getYesPattern() {
        return YES;
    }

    @Override
    protected String getCityComplaintProcess() {
       return "Gibt es in der Stadt ein Beschwerdeverfahren? Antworten Sie mit „Ja“, falls ein solches Verfahren existiert, und erklären Sie, wie man weiter vorgeht.";
    }


    
}
