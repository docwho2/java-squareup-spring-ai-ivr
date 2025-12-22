/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.enums;

import java.util.Locale;
import lombok.Getter;

/**
 * The Voice Languages we support.
 *
 * @author sjensen
 */
@Getter
public enum Language {
    English("en-US"),
    Spanish("es-US"),
    German("de-DE"),
    Finnish("fi-FI"),
    French("fr-CA"),
    Dutch("nl-NL"),
    Norwegian("no-NO"),
    Polish("pl-PL"),
    Swedish("sv-SE");

    private final Locale locale;

    Language(String localeTag) {
        this.locale = Locale.forLanguageTag(localeTag);
    }


}
