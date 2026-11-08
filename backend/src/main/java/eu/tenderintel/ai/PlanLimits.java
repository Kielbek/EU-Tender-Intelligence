package eu.tenderintel.ai;

import java.util.Map;

/**
 * Monthly AI document analyses per plan (calendar month, UTC). Re-opening an analysis that already exists is free and
 * does not count. Stage 6 (billing) makes plans purchasable; the limits are enforced here already.
 */
public final class PlanLimits {

    static final Map<String, Integer> ANALYSES_PER_MONTH = Map.of(
            "FREE", 2,
            "STARTER", 30,
            "PRO", 100,
            "BUSINESS", 300,
            "ENTERPRISE", 1000);

    private PlanLimits() {
    }

    public static int analysesPerMonth(String planCode) {
        return ANALYSES_PER_MONTH.getOrDefault(planCode == null ? "FREE" : planCode, 2);
    }
}
