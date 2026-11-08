package eu.tenderintel.ingestion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Source-independent representation of one published notice. Every adapter (TED now, national portals later)
 * produces this; {@link TenderUpsertService} turns it into tenders, versions, lots and results. Fields the source does
 * not provide are {@code null} – never guessed.
 */
public record NormalizedNotice(
        String externalId,
        long orderKey,
        String externalUrl,
        String noticeType,
        FormType formType,
        ChangeKind changeKind,
        String changeReason,
        Instant publishedAt,
        LocalDate publicationDate,
        String procedureKey,
        String title,
        String titleLang,
        String description,
        Party buyer,
        String countryCode,
        String nutsCode,
        String city,
        String contractType,
        String procedureType,
        String mainCpv,
        List<String> cpvCodes,
        BigDecimal estimatedValue,
        String currency,
        List<Instant> deadlines,
        String deadlineTz,
        String referenceNumber,
        String documentsUrl,
        List<Lot> lots,
        List<Winner> winners,
        Integer tendersReceived,
        LocalDate awardDate,
        String rawJson) {

    public enum FormType { PLANNING, COMPETITION, RESULT, DIRECT_AWARD, CONTRACT_MODIFICATION, OTHER }

    public record Party(String name, String nationalId, String countryCode, String city, String website) {
    }

    public record Lot(String identifier, String title, BigDecimal estimatedValue, String currency, Instant deadline) {
    }

    /** {@code value} is set only when the source unambiguously links it to this winner. */
    public record Winner(String name, String nationalId, String countryCode, String size, BigDecimal value,
            String currency) {
    }
}
