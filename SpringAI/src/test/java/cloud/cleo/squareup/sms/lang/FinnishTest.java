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
public class FinnishTest extends AbstractSmsLanguageTest {

    private final static Pattern YES = Pattern.compile("(kyllä |kyllä,|kylla |kylla,|meillä)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    
    @Override
    protected Language getTestLanguage() {
        return Language.Finnish;
    }

    @Override
    protected String getWhatIsYourName() {
       return "Hei, mikä sinun nimesi on?";
    }

    @Override
    protected String getWhenDidStoreOpen() {
        return "Minä vuonna myymälä avattiin ensimmäisen kerran?";
    }

    @Override
    protected String getThankYouAllDone() {
        return "Kiitos avustanne, siinä kaikki tältä päivältä, näkemiin.";
    }

    @Override
    protected String getDoYouHaveCandlesInStock() {
        return "Tarkista myymälän varastotilanne kynttilöiden varalta ja vastaa kyllä, jos sinulla on niitä.";
    }

    @Override
    protected Pattern getYesPattern() {
        return YES;
    }

    @Override
    protected String getCityComplaintProcess() {
       return "Onko kaupungilla valitusprosessia? Vastaa kyllä, jos sellainen on, ja kerro, mitä tehdä seuraavaksi.";
    }


    
}
