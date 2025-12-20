/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.voice.language;

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
public class VoiceLanguageSpanishTest extends AbstractVoiceLanguageTest {

    private final static Pattern YES = Pattern.compile("(si|sí)");

    
    @Override
    protected Language getTestLanguage() {
        return Language.Spanish;
    }

    @Override
    protected String getWhatIsYourName() {
       return "Hola, ¿cómo te llamas?";
    }

    @Override
    protected String getWhenDidStoreOpen() {
        return "¿Cuándo abrió la tienda por primera vez?";
    }

    @Override
    protected String getThankYouAllDone() {
        return "Gracias por toda vuestra ayuda, eso es todo por hoy, adiós.";
    }

    @Override
    protected String getDoYouHaveCandlesInStock() {
        return "Por favor, comprueben el inventario de la tienda para ver si tienen velas y respondan con sí en caso afirmativo.";
    }

    @Override
    protected Pattern getYesPattern() {
        return YES;
    }

    @Override
    protected String getCityComplaintProcess() {
       return "¿Tiene la ciudad un procedimiento para presentar quejas? Responda con sí si lo tiene e indique qué pasos seguir a continuación.";
    }


    
}
