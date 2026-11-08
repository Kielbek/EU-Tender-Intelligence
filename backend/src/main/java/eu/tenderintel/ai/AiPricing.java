package eu.tenderintel.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Anthropic list prices (USD per million tokens) for cost reporting per organization. Cache writes are billed at
 * 1.25× input (5-minute cache); reads at the listed cache-read price. Unknown models are reported at Opus prices so
 * costs are never under-stated.
 */
public final class AiPricing {

    record Price(double input, double output, double cacheRead) {
    }

    static final Map<String, Price> PRICES = Map.of(
            "claude-opus-5-5", new Price(4.00, 20.00, 0.20),
            "claude-sonnet-5-5", new Price(2.00, 10.00, 0.20),
            "claude-haiku-5-5", new Price(0.10, 0.50, 0.01));
    private static final Price FALLBACK = PRICES.get("claude-opus-5-5");

    private AiPricing() {
    }

    public static BigDecimal costUsd(String model, ClaudeGateway.Usage u) {
        Price p = price(model);
        double usd = (u.inputTokens() * p.input() + u.outputTokens() * p.output() + u.cacheReadTokens() * p.cacheRead()
                + u.cacheCreationTokens() * p.input() * 1.25) / 1_000_000d;
        return BigDecimal.valueOf(usd).setScale(6, RoundingMode.HALF_UP);
    }

    static Price price(String model) {
        if (model == null) {
            return FALLBACK;
        }
        for (Map.Entry<String, Price> e : PRICES.entrySet()) {
            if (model.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return FALLBACK;
    }
}
