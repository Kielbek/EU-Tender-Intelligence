package eu.tenderintel.ai;

import eu.tenderintel.tender.TenderDtos.LotView;
import eu.tenderintel.tender.TenderDtos.SourceView;
import eu.tenderintel.tender.TenderDtos.TenderDetail;
import java.util.Collection;
import java.util.Map;

/**
 * The notice itself as a citable text document, built from the official notice data (TED API fields; for BZP the
 * full notice text is appended by AnalysisService). The TED website blocks automated PDF downloads, so it is not fetched. Field labels are in English; values are
 * copied verbatim from the notice.
 */
final class NoticeDocument {

    private NoticeDocument() {
    }

    static String title(String lang) {
        return "pl".equals(lang) ? "Ogłoszenie o zamówieniu (oficjalna treść)" : "Contract notice (official text)";
    }

    static String text(TenderDetail t) {
        StringBuilder b = new StringBuilder();
        line(b, "Title", t.title());
        if (t.sources() != null) {
            for (SourceView s : t.sources()) {
                line(b, "Notice", s.dataSource() + " " + s.externalId() + (s.noticeType() == null ? ""
                        : " (" + s.noticeType() + ")"));
            }
        }
        if (t.authority() != null) {
            line(b, "Contracting authority", t.authority().name());
        }
        line(b, "Reference number", t.referenceNumber());
        line(b, "Country", t.countryCode());
        line(b, "Place of performance", join(t.city(), t.nutsCode()));
        line(b, "Type of contract", t.contractType());
        line(b, "Procedure", t.procedureType());
        line(b, "Main CPV", t.mainCpv());
        if (t.cpvCodes() != null && !t.cpvCodes().isEmpty()) {
            line(b, "CPV codes", String.join(", ", t.cpvCodes()));
        }
        if (t.estimatedValue() != null) {
            line(b, "Estimated value", t.estimatedValue().toPlainString() + " " + nz(t.currency()));
        }
        if (t.submissionDeadline() != null) {
            line(b, "Time limit for receipt of tenders", t.submissionDeadline() + " (UTC)"
                    + (t.submissionDeadlineTz() == null ? "" : ", notice time zone " + t.submissionDeadlineTz()));
        }
        if (t.openingAt() != null) {
            line(b, "Opening of tenders", t.openingAt() + " (UTC)");
        }
        line(b, "Procurement documents", t.documentsUrl());
        section(b, "Description", t.description());
        section(b, "Conditions for participation", flatten(t.participationConditions()));
        section(b, "Award criteria", flatten(t.awardCriteria()));
        if (t.lots() != null && !t.lots().isEmpty()) {
            b.append("\nLots:\n");
            for (LotView l : t.lots()) {
                b.append("- ").append(nz(l.lotNumber())).append(": ").append(nz(l.title()));
                if (l.estimatedValue() != null) {
                    b.append(" – estimated value ").append(l.estimatedValue().toPlainString()).append(' ')
                            .append(nz(l.currency()));
                }
                b.append('\n');
            }
        }
        return b.toString().strip();
    }

    private static void line(StringBuilder b, String label, String value) {
        if (value != null && !value.isBlank()) {
            b.append(label).append(": ").append(value.strip()).append('\n');
        }
    }

    private static void section(StringBuilder b, String label, String value) {
        if (value != null && !value.isBlank()) {
            b.append('\n').append(label).append(":\n").append(value.strip()).append('\n');
        }
    }

    /** Notice JSON fields (multilingual maps, lists) as plain text, values only. */
    static String flatten(Object o) {
        if (o == null) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        flatten(o, b);
        return b.toString().strip();
    }

    private static void flatten(Object o, StringBuilder b) {
        if (o instanceof Map<?, ?> m) {
            m.values().forEach(v -> flatten(v, b));
        } else if (o instanceof Collection<?> c) {
            c.forEach(v -> flatten(v, b));
        } else if (o != null && !o.toString().isBlank()) {
            b.append(o.toString().strip()).append('\n');
        }
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        return b == null || b.isBlank() ? a : a + " (" + b + ")";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
