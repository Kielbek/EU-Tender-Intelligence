package eu.tenderintel.identity;

import java.util.Set;

/** The 24 official EU languages (ISO 639-1). The UI is translated progressively; e-mails fall back to English. */
public final class SupportedLocales {

    public static final Set<String> EU_LANGUAGES = Set.of("bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr",
            "ga", "hr", "hu", "it", "lt", "lv", "mt", "nl", "pl", "pt", "ro", "sk", "sl", "sv");

    private SupportedLocales() {
    }
}
