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
public class NorwegianTest extends AbstractSmsLanguageTest {

    private final static Pattern YES = Pattern.compile("(ja |ja,)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    
    @Override
    protected Language getTestLanguage() {
        return Language.Norwegian;
    }

    @Override
    protected String getWhatIsYourName() {
       return "Hallo, hva heter du?";
    }

    @Override
    protected String getWhenDidStoreOpen() {
        return "Hvilket år åpnet butikken først?";
    }

    @Override
    protected String getThankYouAllDone() {
        return "Takk for all hjelpen, det var alt for i dag, farvel.";
    }

    @Override
    protected String getDoYouHaveCandlesInStock() {
        return "Sjekk butikkens lagerbeholdning for stearinlys, og svar ja hvis du har dem.";
    }

    @Override
    protected Pattern getYesPattern() {
        return YES;
    }

    @Override
    protected String getCityComplaintProcess() {
       return "Har byen en klageprosess? Svar med ja hvis det finnes en, og hva du skal gjøre videre.";
    }


    
}
