/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.enums;

import java.util.Locale;

/**
 * The Voice Languages we support.
 *
 * @author sjensen
 */
public enum Language {
    English("en-US", "Tell us how we can help today?"),
    Spanish("es-US", "Cuéntanos ¿cómo podemos ayudar hoy?"),
    German("de-DE", "Sagen Sie uns, wie wir heute helfen können?"),
    Finnish("fi-FI", "Kerro meille, kuinka voimme auttaa tänään?"),
    French("fr-CA", "Dites-nous comment nous pouvons vous aider aujourd'hui ?"),
    Dutch("nl-NL", "Vertel ons hoe we vandaag kunnen helpen?"),
    Norwegian("no-NO", "Fortell oss hvordan vi kan hjelpe i dag?"),
    Polish("pl-PL", "Powiedz nam, jak możemy dziś pomóc?"),
    Swedish("sv-SE", "Berätta för oss hur vi kan hjälpa till idag?");

    private final Locale locale;
    private final String initialPrompt;

    Language(String localeTag, String initialPrompt) {
        this.locale = Locale.forLanguageTag(localeTag);
        this.initialPrompt = initialPrompt;
    }

    public Locale getLocale() {
        return locale;
    }

    public String getInitialPrompt() {
        return initialPrompt;
    }
}
