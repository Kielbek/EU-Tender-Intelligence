package eu.tenderintel.tender;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TenderDtos {

    private TenderDtos() {
    }

    public enum SortField { PUBLISHED, DEADLINE, VALUE, RELEVANCE }

    public enum SortDirection { ASC, DESC }

    /**
     * Search criteria (query parameters). The same structure will be produced by the natural-language interpreter
     * (Stage 5) and stored in saved searches.
     */
    public record TenderSearchCriteria(
            @Size(max = 200) String q,
            List<@Pattern(regexp = "^[A-Z]{2}$") String> country,
            List<@Pattern(regexp = "^\\d{2,8}$") String> cpv,
            List<@Pattern(regexp = "^(PLANNED|OPEN|CLOSED|AWARDED|CANCELLED|UNKNOWN)$") String> status,
            List<@Pattern(regexp = "^(WORKS|SUPPLIES|SERVICES|MIXED)$") String> contractType,
            List<@Size(max = 40) String> procedureType,
            @Size(max = 200) String authority,
            @Size(max = 100) String city,
            @Pattern(regexp = "^[A-Z0-9]{2,5}$") String nuts,
            @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @Pattern(regexp = "^[a-z]{2,3}$") String language,
            @PositiveOrZero BigDecimal valueMinEur,
            @PositiveOrZero BigDecimal valueMaxEur,
            Instant publishedFrom,
            Instant publishedTo,
            Instant deadlineFrom,
            Instant deadlineTo,
            SortField sort,
            SortDirection direction,
            @Min(0) @Max(499) Integer page,
            @Min(1) @Max(100) Integer size) {

        public int pageOrDefault() {
            return page == null ? 0 : page;
        }

        public int sizeOrDefault() {
            return size == null ? 20 : size;
        }
    }

    public record TenderSummary(UUID id, String title, String countryCode, String city, String authorityName,
            String mainCpv, String contractType, String procedureType, BigDecimal estimatedValue, String currency,
            BigDecimal estimatedValueEur, Instant publishedAt, Instant submissionDeadline, String status,
            String originalUrl) {
    }

    public record SourceView(String dataSource, String externalId, String externalUrl, String noticeType,
            Instant firstSeenAt, Instant lastSeenAt) {
    }

    public record AuthorityView(UUID id, String name, String nationalId, String countryCode, String city,
            String website) {
    }

    public record TenderDetail(UUID id, String title, String titleLang, String description, String countryCode,
            String nutsCode, String city, AuthorityView authority, String referenceNumber, String contractType,
            String procedureType, String mainCpv, List<String> cpvCodes, BigDecimal estimatedValue, String currency,
            BigDecimal estimatedValueEur, Instant publishedAt, Instant submissionDeadline, String submissionDeadlineTz,
            Instant openingAt, String status, String noticeLanguage, String originalUrl, String documentsUrl,
            Object participationConditions, Object awardCriteria, int currentVersionNo, Instant lastChangedAt,
            List<SourceView> sources) {
    }

    public record VersionView(int versionNo, String changeKind, String noticeType, Instant publishedAt, Object diff) {
    }
}
