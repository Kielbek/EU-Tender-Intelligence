package eu.tenderintel.reference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Converts amounts to EUR using the ECB rate published on, or most recently before, the given date. */
@Service
public class ExchangeRateService {

    /** Rates older than this relative to the requested date are not used (avoids silently stale conversions). */
    static final int MAX_RATE_AGE_DAYS = 10;

    private final JdbcTemplate jdbc;

    ExchangeRateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BigDecimal> toEur(BigDecimal amount, String currency, LocalDate date) {
        if (amount == null || currency == null) {
            return Optional.empty();
        }
        if ("EUR".equals(currency)) {
            return Optional.of(amount.setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal rate = jdbc.query("""
                select rate_per_eur from exchange_rates
                where currency = ? and rate_date <= ? and rate_date >= ?
                order by rate_date desc limit 1
                """, rs -> rs.next() ? rs.getBigDecimal(1) : null, currency, date, date.minusDays(MAX_RATE_AGE_DAYS));
        return Optional.ofNullable(rate).map(r -> amount.divide(r, 2, RoundingMode.HALF_UP));
    }
}
