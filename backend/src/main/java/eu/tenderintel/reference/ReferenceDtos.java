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

    /**
     * {@code label} is in the requested language (falls back to English if missing). {@code parentLabel}: label of the
     * parent code (context for suggestions); {@code hasChildren}: the code can be browsed further;
     * {@code openTenders}: open tenders whose main code falls under this code (null where not computed).
     */
    public record CpvView(String code, String parentCode, int level, String label, String parentLabel,
            boolean hasChildren, Integer openTenders) {

        public CpvView(String code, String parentCode, int level, String label) {
            this(code, parentCode, level, label, null, false, null);
        }
    }

    public record CpvDetailView(String code, String parentCode, int level, Map<String, String> labels,
            List<CpvView> ancestors) {
    }
}
