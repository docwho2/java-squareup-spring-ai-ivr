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
public class FrenchTest extends AbstractSmsLanguageTest {
    

private final static Pattern YES = Pattern.compile("(oui |oui,)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    
    @Override
    protected Language getTestLanguage() {
        return Language.French;
    }

    @Override
    protected String getWhatIsYourName() {
       return "Bonjour, quel est votre nom ?";
    }

    @Override
    protected String getWhenDidStoreOpen() {
        return "En quelle année le magasin a-t-il ouvert ses portes pour la première fois ?";
    }

    @Override
    protected String getThankYouAllDone() {
        return "Merci pour toute votre aide, c'est tout pour aujourd'hui, au revoir.";
    }

    @Override
    protected String getDoYouHaveCandlesInStock() {
        return "Veuillez vérifier le stock du magasin pour les bougies et répondre par oui si vous en avez.";
    }

    @Override
    protected Pattern getYesPattern() {
        return YES;
    }

    @Override
    protected String getCityComplaintProcess() {
        return "La ville dispose-t-elle d'une procédure de traitement des plaintes ? Répondez par oui si c'est le cas et indiquez la marche à suivre.";
    }
    
}
