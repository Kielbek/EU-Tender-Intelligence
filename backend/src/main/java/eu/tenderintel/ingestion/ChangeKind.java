package eu.tenderintel.ingestion;

/** What a notice means for its procedure. eForms has no separate corrigendum form: changes are flagged in-notice. */
public enum ChangeKind {
    PRIOR_INFORMATION,
    COMPETITION,
    CHANGE,
    CANCELLATION,
    AWARD,
    MODIFICATION,
    OTHER
}
