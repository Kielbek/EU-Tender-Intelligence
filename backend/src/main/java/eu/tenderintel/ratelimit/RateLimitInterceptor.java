package eu.tenderintel.ratelimit;

import eu.tenderintel.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Global per-client limits for the whole API. Endpoint-specific, stricter limits (login, registration, password
 * reset) are applied in the corresponding services.
 */
@Component
class RateLimitInterceptor implements HandlerInterceptor {

    static final int AUTHENTICATED_PER_MINUTE = 600;
    static final int ANONYMOUS_PER_MINUTE = 120;

    private final RateLimiter limiter;

    RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CurrentUser.optionalId().ifPresentOrElse(
                id -> limiter.check("api-user", id.toString(), AUTHENTICATED_PER_MINUTE, Duration.ofMinutes(1)),
                () -> limiter.check("api-ip", request.getRemoteAddr(), ANONYMOUS_PER_MINUTE, Duration.ofMinutes(1)));
        return true;
    }
}
