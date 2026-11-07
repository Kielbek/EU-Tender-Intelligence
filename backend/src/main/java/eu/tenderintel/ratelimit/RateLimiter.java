package eu.tenderintel.ratelimit;

import eu.tenderintel.common.error.RateLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiter backed by Redis (shared by all instances). If Redis is unavailable the limiter fails
 * open and logs a warning: availability is preferred, and brute force is additionally stopped by account lockout.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    RateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Throws {@link RateLimitExceededException} when {@code key} exceeded {@code limit} calls within {@code window}. */
    public void check(String rule, String key, int limit, Duration window) {
        long windowMs = window.toMillis();
        long now = clock.millis();
        long windowIndex = now / windowMs;
        String redisKey = "rl:" + rule + ":" + key + ":" + windowIndex;
        Long count;
        try {
            count = redis.execute(INCREMENT, List.of(redisKey), String.valueOf(windowMs));
        } catch (RuntimeException ex) {
            log.warn("Rate limiter unavailable, allowing request (rule {})", rule, ex);
            return;
        }
        if (count != null && count > limit) {
            long retryAfter = Math.max(1, ((windowIndex + 1) * windowMs - now) / 1000);
            throw new RateLimitExceededException(retryAfter);
        }
    }
}
