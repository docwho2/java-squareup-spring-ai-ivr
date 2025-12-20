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
public class PolishTest extends AbstractVoiceLanguageTest {

    private final static Pattern YES = Pattern.compile("(tak |tak,)");

    
    @Override
    protected Language getTestLanguage() {
        return Language.Polish;
    }

    @Override
    protected String getWhatIsYourName() {
       return "Cześć, jak masz na imię?";
    }

    @Override
    protected String getWhenDidStoreOpen() {
        return "Kiedy po raz pierwszy otwarto ten sklep?";
    }

    @Override
    protected String getThankYouAllDone() {
        return "Dziękuję za całą pomoc, to wszystko na dziś, do widzenia.";
    }

    @Override
    protected String getDoYouHaveCandlesInStock() {
        return "Proszę sprawdzić, czy w sklepie są świece i odpowiedzieć „tak”, jeśli są dostępne.";
    }

    @Override
    protected Pattern getYesPattern() {
        return YES;
    }

    @Override
    protected String getCityComplaintProcess() {
       return "Czy miasto posiada procedurę składania skarg? Odpowiedz „tak”, jeśli taka procedura istnieje, i opisz, co należy zrobić w następnej kolejności.";
    }


    
}
