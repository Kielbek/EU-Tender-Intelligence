package eu.tenderintel.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Client IP and user agent of the current request. The IP comes from {@code getRemoteAddr()}, which Spring rewrites
 * from {@code X-Forwarded-For} only for trusted proxies ({@code server.forward-headers-strategy=framework}).
 */
public record ClientInfo(String ip, String userAgent) {

    private static final int MAX_UA = 512;

    public static ClientInfo current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return of(attrs.getRequest());
        }
        return new ClientInfo(null, null);
    }

    public static ClientInfo of(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua != null && ua.length() > MAX_UA) {
            ua = ua.substring(0, MAX_UA);
        }
        return new ClientInfo(request.getRemoteAddr(), ua);
    }
}
