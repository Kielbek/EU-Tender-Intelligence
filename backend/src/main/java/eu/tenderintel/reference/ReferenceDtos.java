package eu.tenderintel.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ReferenceDtos {

    private ReferenceDtos() {
    }

    public record CountryView(String code, String code3, String nameEn, boolean isEu, String defaultCurrency,
            String defaultLanguage) {
    }

    public record CurrencyView(String code, String nameEn, int minorUnits) {
    }

    public record ExchangeRateView(String currency, LocalDate rateDate, BigDecimal ratePerEur, String source) {
    }

    /** {@code label} is in the requested language (falls back to English if missing). */
    public record CpvView(String code, String parentCode, int level, String label) {
    }

    public record CpvDetailView(String code, String parentCode, int level, Map<String, String> labels,
            List<CpvView> ancestors) {
    }
}
