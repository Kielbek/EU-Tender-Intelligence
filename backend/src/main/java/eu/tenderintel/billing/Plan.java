package eu.tenderintel.billing;

import java.util.Arrays;
import java.util.Optional;

/**
 * Subscription plans and their limits – the single source of truth, enforced by the backend (the frontend only
 * displays them). Prices are not here: they live in Stripe and are read from the configured price ids, so the app
 * never shows an amount that differs from what Stripe charges.
 *
 * <p>{@code null} limit = unlimited. Limits apply to new actions: after a downgrade existing members, saved searches
 * and data stay, only adding more is blocked.
 */
public enum Plan {
    //  seats  saved searches/user  AI/month  exports/month  instant e-mails  purchasable  market history (months)  competitors
    FREE(1, 3, 2, 3, false, false, 3, 1),
    STARTER(3, 25, 30, 100, true, true, 24, 10),
    PRO(10, 100, 100, 500, true, true, 24, 50),
    BUSINESS(25, 100, 300, 2000, true, true, 24, 200),
    ENTERPRISE(null, 100, 1000, null, true, false, 24, 500);

    private final Integer seats;
    private final int savedSearchesPerUser;
    private final int aiAnalysesPerMonth;
    private final Integer exportsPerMonth;
    private final boolean instantEmails;
    private final boolean purchasable;
    private final int marketHistoryMonths;
    private final int watchedCompetitors;

    Plan(Integer seats, int savedSearchesPerUser, int aiAnalysesPerMonth, Integer exportsPerMonth,
            boolean instantEmails, boolean purchasable, int marketHistoryMonths, int watchedCompetitors) {
        this.marketHistoryMonths = marketHistoryMonths;
        this.watchedCompetitors = watchedCompetitors;
        this.seats = seats;
        this.savedSearchesPerUser = savedSearchesPerUser;
        this.aiAnalysesPerMonth = aiAnalysesPerMonth;
        this.exportsPerMonth = exportsPerMonth;
        this.instantEmails = instantEmails;
        this.purchasable = purchasable;
    }

    /** Unknown or missing codes fall back to FREE (never to more than the customer paid for). */
    public static Plan of(String code) {
        return parse(code).orElse(FREE);
    }

    public static Optional<Plan> parse(String code) {
        return Arrays.stream(values()).filter(p -> p.name().equals(code)).findFirst();
    }

    public Integer seats() {
        return seats;
    }

    public int savedSearchesPerUser() {
        return savedSearchesPerUser;
    }

    public int aiAnalysesPerMonth() {
        return aiAnalysesPerMonth;
    }

    public Integer exportsPerMonth() {
        return exportsPerMonth;
    }

    /** Without it, "instant" e-mails are sent in the daily digest instead. */
    public boolean instantEmails() {
        return instantEmails;
    }

    /** How far back award statistics, winners and company/authority profiles reach. */
    public int marketHistoryMonths() {
        return marketHistoryMonths;
    }

    /** Competitors the organization can follow. */
    public int watchedCompetitors() {
        return watchedCompetitors;
    }

    /** Can be bought through Stripe Checkout (ENTERPRISE is a contract set by a platform administrator). */
    public boolean purchasable() {
        return purchasable;
    }
}
