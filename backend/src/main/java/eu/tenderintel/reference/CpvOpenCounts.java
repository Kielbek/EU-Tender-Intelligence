package eu.tenderintel.reference;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Number of open tenders whose main CPV code falls under a given code ("45000000" covers the whole division 45).
 * Shown next to CPV suggestions so users can see which codes actually have tenders. Counts are cached for a few
 * minutes: one grouped query over open tenders, then prefix sums in memory.
 */
@Component
public class CpvOpenCounts {

    static final Duration TTL = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Map<String, Integer> byMainCode = Map.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    CpvOpenCounts(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Open tenders whose main code starts with the significant prefix of {@code code}. */
    public int openTenders(String code) {
        String prefix = significant(code);
        int sum = 0;
        for (Map.Entry<String, Integer> e : counts().entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    /** "45210000" → "4521"; at least the two digits of the division. */
    static String significant(String code) {
        String s = code.replaceAll("0+$", "");
        return s.length() < 2 ? code.substring(0, 2) : s;
    }

    private Map<String, Integer> counts() {
        Instant now = clock.instant();
        if (now.isAfter(loadedAt.plus(TTL))) {
            synchronized (this) {
                if (now.isAfter(loadedAt.plus(TTL))) {
                    Map<String, Integer> m = new HashMap<>();
                    jdbc.query("""
                            select tc.cpv_code, count(*) from tender_cpv tc join tenders t on t.id = tc.tender_id
                            where tc.is_main and t.status = 'OPEN' group by tc.cpv_code
                            """, rs -> {
                        m.put(rs.getString(1), rs.getInt(2));
                    });
                    byMainCode = Map.copyOf(m);
                    loadedAt = now;
                }
            }
        }
        return byMainCode;
    }
}
