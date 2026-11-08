package eu.tenderintel.ingestion;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ISO 3166 / ISO 639 code conversion (TED uses alpha-3 country codes and ISO 639-2/T language codes) based on the JDK
 * tables, and on-demand registration of non-EU countries that appear in notices.
 */
@Component
public class CountryResolver {

    private static final Map<String, String> ALPHA3_TO_ALPHA2 = new HashMap<>();
    private static final Map<String, String> LANG3_TO_LANG2 = new HashMap<>();

    static {
        for (String alpha2 : Locale.getISOCountries()) {
            ALPHA3_TO_ALPHA2.put(Locale.of("", alpha2).getISO3Country(), alpha2);
        }
        for (String lang2 : Locale.getISOLanguages()) {
            LANG3_TO_LANG2.put(Locale.of(lang2).getISO3Language(), lang2);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate newTx;
    private final Set<String> known = ConcurrentHashMap.newKeySet();

    CountryResolver(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static String alpha2(String alpha3) {
        return alpha3 == null ? null : ALPHA3_TO_ALPHA2.get(alpha3.toUpperCase(Locale.ROOT));
    }

    /** "deu" → "de". Returns null for unknown or special codes such as "mul". */
    public static String language2(String iso639_2t) {
        return iso639_2t == null ? null : LANG3_TO_LANG2.get(iso639_2t.toLowerCase(Locale.ROOT));
    }

    /**
     * Makes sure the country row exists (non-EU countries are inserted with their English name). Committed in its own
     * transaction, so the cache never remembers a row that was rolled back with a failing notice.
     */
    public void ensure(String alpha2) {
        if (alpha2 == null || known.contains(alpha2)) {
            return;
        }
        Locale locale = Locale.of("", alpha2);
        newTx.executeWithoutResult(status -> jdbc.update("""
                insert into countries (code, code3, name_en, is_eu) values (?, ?, ?, false)
                on conflict (code) do nothing
                """, alpha2, locale.getISO3Country(), locale.getDisplayCountry(Locale.ENGLISH)));
        known.add(alpha2);
    }
}
