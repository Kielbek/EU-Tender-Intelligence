package eu.tenderintel.document;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/**
 * Downloads the documents link published in a notice – only when it is a direct, publicly accessible file. Pages that
 * need a login, a CAPTCHA or a browser (HTML) are never scraped or worked around: the user downloads the files from
 * the portal and uploads them.
 *
 * <p>SSRF protection: the HTTP client resolves names only through {@link #SAFE_DNS}, which drops private, loopback,
 * link-local and similar addresses. The connection uses exactly the addresses that passed the check, so a DNS
 * rebinding answer cannot slip a private address in between the check and the connection. Redirects are followed
 * manually and every hop goes through the same resolver.
 */
@Component
public class PublicDocumentFetcher {

    static final String USER_AGENT = "EU-Tender-Intelligence document fetcher (+https://github.com/kielbek/eu-tender-intelligence)";
    private static final int MAX_REDIRECTS = 5;

    public enum Outcome { FETCHED, REQUIRES_LOGIN, NOT_ACCESSIBLE }

    /** {@code detail} is a stable reason code (INVALID_LINK, PRIVATE_NETWORK, UNREACHABLE, LOGIN_REQUIRED, BROWSER_CHECK,
     * HTTP_ERROR, PORTAL_PAGE, TOO_LARGE, TOO_MANY_REDIRECTS, UNSUPPORTED) translated by the UI. */
    public record Fetched(Outcome outcome, String fileName, byte[] content, String detail) {
    }

    /** Thrown by the resolver when a host has no public address. */
    static final class PrivateAddressException extends UnknownHostException {
        PrivateAddressException(String host) {
            super(host + " resolves only to non-public addresses");
        }
    }

    static final Dns SAFE_DNS = hostname -> {
        List<InetAddress> all = List.of(InetAddress.getAllByName(hostname));
        List<InetAddress> publicOnly = all.stream().filter(a -> !isNonPublic(a)).toList();
        if (publicOnly.isEmpty() || publicOnly.size() != all.size()) {
            // Mixed answers are refused too: the host is trying to point somewhere internal.
            throw new PrivateAddressException(hostname);
        }
        return publicOnly;
    };

    private final OkHttpClient http = new OkHttpClient.Builder()
            .dns(SAFE_DNS)
            // No proxy: a proxy would resolve names itself and bypass SAFE_DNS.
            .proxy(java.net.Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(90))
            .build();

    public Fetched fetch(String url, long maxBytes) {
        HttpUrl current = HttpUrl.parse(url == null ? "" : url.strip());
        if (current == null) {
            return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "INVALID_LINK");
        }
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (isLiteralNonPublic(current.host())) {
                    return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "PRIVATE_NETWORK");
                }
                Request req = new Request.Builder().url(current).header("User-Agent", USER_AGENT).get().build();
                try (Response res = http.newCall(req).execute()) {
                    int code = res.code();
                    if (res.isRedirect() && res.header("Location") != null) {
                        HttpUrl next = current.resolve(res.header("Location"));
                        if (next == null) {
                            return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "INVALID_LINK");
                        }
                        current = next;
                        continue;
                    }
                    if (code == 401 || code == 403) {
                        return new Fetched(Outcome.REQUIRES_LOGIN, null, null, "LOGIN_REQUIRED");
                    }
                    if (code == 202 || res.header("x-amzn-waf-action") != null) {
                        return new Fetched(Outcome.REQUIRES_LOGIN, null, null, "BROWSER_CHECK");
                    }
                    if (code != 200 || res.body() == null) {
                        return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "HTTP_ERROR");
                    }
                    String type = res.header("Content-Type", "").toLowerCase(Locale.ROOT);
                    if (type.startsWith("text/html") || type.startsWith("application/xhtml")) {
                        return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "PORTAL_PAGE");
                    }
                    try (InputStream body = res.body().byteStream()) {
                        byte[] data = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
                        if (data.length > maxBytes) {
                            return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "TOO_LARGE");
                        }
                        return new Fetched(Outcome.FETCHED, fileName(current, res), data, null);
                    }
                }
            }
            return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "TOO_MANY_REDIRECTS");
        } catch (PrivateAddressException e) {
            return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "PRIVATE_NETWORK");
        } catch (IOException e) {
            return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "UNREACHABLE");
        }
    }

    /** A host written as an IP literal (no DNS lookup involved). */
    private static boolean isLiteralNonPublic(String host) {
        if (!host.matches("[0-9.]+") && !host.contains(":")) {
            return false;
        }
        try {
            return isNonPublic(InetAddress.getByName(host));
        } catch (UnknownHostException e) {
            return true;
        }
    }

    static boolean isNonPublic(InetAddress a) {
        byte[] b = a.getAddress();
        boolean uniqueLocalV6 = b.length == 16 && (b[0] & 0xfe) == 0xfc;
        boolean carrierNat = b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        boolean thisNetwork = b.length == 4 && (b[0] & 0xff) == 0;
        return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                || a.isMulticastAddress() || uniqueLocalV6 || carrierNat || thisNetwork;
    }

    private static String fileName(HttpUrl url, Response res) {
        String cd = res.header("Content-Disposition", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").matcher(cd);
        String name = null;
        if (m.find()) {
            name = java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (name == null || name.isBlank()) {
            List<String> segments = url.pathSegments();
            name = segments.isEmpty() ? "" : segments.getLast();
        }
        name = TextExtractor.baseName(name);
        return name.isBlank() ? "document" : name;
    }
}
