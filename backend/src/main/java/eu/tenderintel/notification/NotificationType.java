package eu.tenderintel.notification;

/**
 * What a notification is about. The first three are configured in the user's preferences; saved-search alerts are
 * switched on per saved search (with their own e-mail frequency).
 */
public enum NotificationType {
    /** A newly published open tender scores at least the user's threshold for the organization's profile. */
    NEW_MATCH(true, "DAILY", 70),
    /** A watched tender changed: deadline, value, documents, status, cancellation or award. */
    WATCHED_CHANGED(true, "INSTANT", null),
    /** The submission deadline of a watched tender is near. */
    DEADLINE_APPROACHING(true, "INSTANT", null),
    /** A followed competitor won a contract (new award notice). */
    COMPETITOR_WON(true, "DAILY", null),
    /** New tenders for a saved search with an alert. */
    SAVED_SEARCH(true, null, null);

    private final boolean defaultInApp;
    private final String defaultEmail;
    private final Integer defaultMinScore;

    NotificationType(boolean defaultInApp, String defaultEmail, Integer defaultMinScore) {
        this.defaultInApp = defaultInApp;
        this.defaultEmail = defaultEmail;
        this.defaultMinScore = defaultMinScore;
    }

    public boolean defaultInApp() {
        return defaultInApp;
    }

    public String defaultEmail() {
        return defaultEmail;
    }

    public Integer defaultMinScore() {
        return defaultMinScore;
    }

    public boolean configurable() {
        return this != SAVED_SEARCH;
    }
}
