package eu.tenderintel.document;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Downloads the documents link published in a notice – only when it is a direct, publicly accessible file. Pages that
 * need a login, a CAPTCHA or a browser (HTML) are never scraped or worked around: the user downloads the files from
 * the portal and uploads them. Requests to private networks are refused (SSRF protection).
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

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public Fetched fetch(String url, long maxBytes) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "INVALID_LINK");
        }
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                String refusal = refuse(uri);
                if (refusal != null) {
                    return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, refusal);
                }
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                        .header("User-Agent", USER_AGENT).GET().build();
                HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = res.statusCode();
                if (code >= 300 && code < 400 && res.headers().firstValue("location").isPresent()) {
                    res.body().close();
                    uri = uri.resolve(res.headers().firstValue("location").get());
                    continue;
                }
                try (InputStream body = res.body()) {
                    if (code == 401 || code == 403) {
                        return new Fetched(Outcome.REQUIRES_LOGIN, null, null, "LOGIN_REQUIRED");
                    }
                    if (code == 202 || res.headers().firstValue("x-amzn-waf-action").isPresent()) {
                        return new Fetched(Outcome.REQUIRES_LOGIN, null, null,
                                "BROWSER_CHECK");
                    }
                    if (code != 200) {
                        return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "HTTP_ERROR");
                    }
                    String type = res.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
                    if (type.startsWith("text/html") || type.startsWith("application/xhtml")) {
                        return new Fetched(Outcome.NOT_ACCESSIBLE, null, null,
                                "PORTAL_PAGE");
                    }
                    byte[] data = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
                    if (data.length > maxBytes) {
                        return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "TOO_LARGE");
                    }
                    return new Fetched(Outcome.FETCHED, fileName(uri, res), data, null);
                }
            }
            return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "TOO_MANY_REDIRECTS");
        } catch (IOException e) {
            return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "UNREACHABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "UNREACHABLE");
        }
    }

    /** Only http(s) to public addresses. */
    static String refuse(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return "INVALID_LINK";
        }
        if (uri.getHost() == null) {
            return "INVALID_LINK";
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || isUniqueLocalV6(a) || isCarrierGradeNat(a)) {
                    return "PRIVATE_NETWORK";
                }
            }
        } catch (UnknownHostException e) {
            return "UNREACHABLE";
        }
        return null;
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private static boolean isCarrierGradeNat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }

    private static String fileName(URI uri, HttpResponse<?> res) {
        String cd = res.headers().firstValue("content-disposition").orElse("");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").matcher(cd);
        String name = null;
        if (m.find()) {
            name = java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (name == null || name.isBlank()) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            name = path.substring(path.lastIndexOf('/') + 1);
        }
        name = TextExtractor.baseName(name);
        return name.isBlank() ? "document" : name;
    }
}
