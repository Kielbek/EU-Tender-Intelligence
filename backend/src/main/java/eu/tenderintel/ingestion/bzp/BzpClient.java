package eu.tenderintel.ingestion.bzp;

import eu.tenderintel.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client of the public BZP notice API ({@code GET /mo-board/api/v1/notice}): anonymous, free, max 500 per page,
 * results sorted by publication time. Pagination is a sliding window – the next page starts at the publication time
 * of the last notice received (inclusive), duplicates are removed by the caller. Retries 429/5xx with backoff.
 */
@Component
public class BzpClient {

    static final int MAX_ATTEMPTS = 5;
    /** API limit. */
    public static final int MAX_PAGE = 500;
    private static final Logger log = LoggerFactory.getLogger(BzpClient.class);

    private final RestClient http;
    private final JsonMapper json;
    private final AppProperties.Bzp props;
    private final Sleeper sleeper;

    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    @org.springframework.beans.factory.annotation.Autowired
    BzpClient(RestClient.Builder builder, JsonMapper json, AppProperties props) {
        this(builder, json, props.bzp(), d -> Thread.sleep(d));
    }

    BzpClient(RestClient.Builder builder, JsonMapper json, AppProperties.Bzp props, Sleeper sleeper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.http = builder.requestFactory(factory).defaultHeader("User-Agent", "EU-Tender-Intelligence/1.0").build();
        this.json = json;
        this.props = props;
        this.sleeper = sleeper;
    }

    /**
     * Notices of {@code type} published from {@code from} (inclusive, exact time) up to the end of {@code toDate}.
     * An empty list means the window is exhausted.
     */
    public List<JsonNode> page(String type, Instant from, LocalDate toDate) {
        return page(type, from, toDate, props.pageSize());
    }

    public List<JsonNode> page(String type, Instant from, LocalDate toDate, int pageSize) {
        String url = UriComponentsBuilder.fromUriString(props.searchUrl())
                .queryParam("NoticeType", type)
                .queryParam("PublicationDateFrom", from.toString())
                .queryParam("PublicationDateTo", toDate.plusDays(1).toString())
                .queryParam("PageSize", Math.min(MAX_PAGE, Math.max(1, pageSize)))
                .build().toUriString();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String body = http.get().uri(url).retrieve().body(String.class);
                JsonNode root = json.readTree(body == null ? "[]" : body);
                if (!root.isArray()) {
                    throw new BzpApiException("Unexpected BZP response (not a list)", false);
                }
                List<JsonNode> out = new ArrayList<>(root.size());
                root.forEach(out::add);
                return out;
            } catch (HttpStatusCodeException e) {
                int code = e.getStatusCode().value();
                if (code != 429 && code < 500) {
                    throw new BzpApiException("BZP API rejected the request (" + code + "): "
                            + truncate(e.getResponseBodyAsString()), false);
                }
                last = new BzpApiException("BZP API returned " + code, true);
            } catch (ResourceAccessException e) {
                last = new BzpApiException("BZP API unreachable: " + e.getMessage(), true);
            }
            if (attempt < MAX_ATTEMPTS) {
                Duration backoff = Duration.ofSeconds(2L << (attempt - 1));
                log.warn("BZP request failed (attempt {}/{}), retrying in {}", attempt, MAX_ATTEMPTS, backoff);
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BzpApiException("Interrupted", false);
                }
            }
        }
        throw last;
    }

    private static String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), 300));
    }

    public static class BzpApiException extends RuntimeException {
        private final boolean transientFailure;

        BzpApiException(String message, boolean transientFailure) {
            super(message);
            this.transientFailure = transientFailure;
        }

        public boolean isTransient() {
            return transientFailure;
        }
    }
}
