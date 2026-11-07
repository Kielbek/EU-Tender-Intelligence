package eu.tenderintel.organization;

import java.text.Normalizer;
import java.util.Locale;

final class Slugs {

    private Slugs() {
    }

    /** "Łódzka Budowa Sp. z o.o." → "lodzka-budowa-sp-z-o-o". Diacritics folded, max 48 chars. */
    static String of(String name) {
        String folded = Normalizer.normalize(name.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        String slug = folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 48) {
            slug = slug.substring(0, 48).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "org" : slug;
    }
}
