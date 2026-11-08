package eu.tenderintel.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FixedRateTest {

    @Test
    void bulgarianLevUsesTheFixedEuroConversionRate() {
        ExchangeRateService rates = new ExchangeRateService(null); // fixed rates never touch the database
        assertThat(rates.toEur(new BigDecimal("1955830.00"), "BGN", LocalDate.of(2026, 11, 5)))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1000000.00"));
        assertThat(rates.toEur(new BigDecimal("100"), "EUR", LocalDate.of(2026, 11, 5)))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("100.00"));
    }
}
