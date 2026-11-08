package eu.tenderintel.ingestion;

import eu.tenderintel.ingestion.NormalizedNotice.FormType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Derives tender status and the effective submission deadline from a notice. */
public final class TenderStatusRules {

    private TenderStatusRules() {
    }

    /**
     * Effective deadline across lots: the earliest one still in the future (what a bidder must act on next); if all
     * have passed, the latest one.
     */
    public static Instant effectiveDeadline(List<Instant> deadlines, Instant now) {
        if (deadlines == null || deadlines.isEmpty()) {
            return null;
        }
        return deadlines.stream().filter(Objects::nonNull).filter(d -> d.isAfter(now)).min(Comparator.naturalOrder())
                .orElseGet(() -> deadlines.stream().filter(Objects::nonNull).max(Comparator.naturalOrder())
                        .orElse(null));
    }

    /**
     * Status implied by a single notice. {@code previous} is the tender's status before this notice (null if new).
     */
    public static String derive(NormalizedNotice n, Instant deadline, String previous, Instant now) {
        if (n.changeKind() == ChangeKind.CANCELLATION) {
            return "CANCELLED";
        }
        return switch (n.formType()) {
            case PLANNING -> previous == null || "PLANNED".equals(previous) || "UNKNOWN".equals(previous)
                    ? "PLANNED" : previous;
            case COMPETITION -> deadline == null || deadline.isAfter(now) ? "OPEN" : "CLOSED";
            case RESULT -> n.winners().isEmpty() ? "CLOSED" : "AWARDED";
            case DIRECT_AWARD, CONTRACT_MODIFICATION -> "AWARDED";
            case OTHER -> previous == null ? "UNKNOWN" : previous;
        };
    }
}
