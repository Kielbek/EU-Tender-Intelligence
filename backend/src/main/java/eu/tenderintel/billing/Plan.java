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
    //          seats  saved searches/user  AI analyses/month  exports/month  instant e-mails  purchasable
    FREE(1, 3, 2, 3, false, false),
    STARTER(3, 25, 30, 100, true, true),
    PRO(10, 100, 100, 500, true, true),
    BUSINESS(25, 100, 300, 2000, true, true),
    ENTERPRISE(null, 100, 1000, null, true, false);

    private final Integer seats;
    private final int savedSearchesPerUser;
    private final int aiAnalysesPerMonth;
    private final Integer exportsPerMonth;
    private final boolean instantEmails;
    private final boolean purchasable;

    Plan(Integer seats, int savedSearchesPerUser, int aiAnalysesPerMonth, Integer exportsPerMonth,
            boolean instantEmails, boolean purchasable) {
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

    /** Can be bought through Stripe Checkout (ENTERPRISE is a contract set by a platform administrator). */
    public boolean purchasable() {
        return purchasable;
    }
}
