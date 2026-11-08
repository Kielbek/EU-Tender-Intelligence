package eu.tenderintel.ingestion.ted;

import eu.tenderintel.config.AppProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client of the TED Search API v3 ({@code POST /v3/notices/search}, anonymous). Uses ITERATION pagination, which has
 * no depth limit. Retries rate limiting (429) and server errors with exponential backoff; client errors (4xx) fail
 * fast because retrying an invalid query cannot succeed.
 */
@Component
public class TedSearchClient {

    static final int MAX_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(TedSearchClient.class);

    private final RestClient http;
    private final JsonMapper json;
    private final AppProperties.Ted props;
    private final Sleeper sleeper;

    /** Abstraction over Thread.sleep so tests do not wait for real backoff. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    @org.springframework.beans.factory.annotation.Autowired
    TedSearchClient(RestClient.Builder builder, JsonMapper json, AppProperties props) {
        this(builder, json, props.ted(), d -> Thread.sleep(d));
    }

    TedSearchClient(RestClient.Builder builder, JsonMapper json, AppProperties.Ted props, Sleeper sleeper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.http = builder.requestFactory(factory).defaultHeader("User-Agent", "EU-Tender-Intelligence/1.0").build();
        this.json = json;
        this.props = props;
        this.sleeper = sleeper;
    }

    public record Page(JsonNode notices, String nextToken, long total) {
    }

    public Page search(String query, String iterationToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("fields", TedNoticeParser.FIELDS);
        body.put("limit", Math.min(props.pageSize(), 10_000 / TedNoticeParser.FIELDS.size()));
        body.put("paginationMode", "ITERATION");
        body.put("scope", "ALL");
        if (iterationToken != null) {
            body.put("iterationNextToken", iterationToken);
        }
        String payload = json.writeValueAsString(body);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String response = http.post().uri(props.searchUrl()).contentType(MediaType.APPLICATION_JSON)
                        .body(payload).retrieve().body(String.class);
                JsonNode root = json.readTree(response);
                if (root.path("timedOut").asBoolean(false)) {
                    throw new TedApiException("TED search timed out", true);
                }
                JsonNode token = root.get("iterationNextToken");
                return new Page(root.path("notices"), token == null || token.isNull() ? null : token.asString(),
                        root.path("totalNoticeCount").asLong(0));
            } catch (HttpStatusCodeException e) {
                int code = e.getStatusCode().value();
                if (code != 429 && code < 500) {
                    throw new TedApiException("TED API rejected the request (" + code + "): "
                            + truncate(e.getResponseBodyAsString()), false);
                }
                last = new TedApiException("TED API returned " + code, true);
            } catch (ResourceAccessException e) {
                last = new TedApiException("TED API unreachable: " + e.getMessage(), true);
            } catch (TedApiException e) {
                last = e;
            }
            if (attempt < MAX_ATTEMPTS) {
                Duration backoff = Duration.ofSeconds(2L << (attempt - 1));
                log.warn("TED request failed (attempt {}/{}), retrying in {}", attempt, MAX_ATTEMPTS, backoff);
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new TedApiException("Interrupted", false);
                }
            }
        }
        throw last;
    }

    private static String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), 300));
    }

    public static class TedApiException extends RuntimeException {
        private final boolean transientFailure;

        TedApiException(String message, boolean transientFailure) {
            super(message);
            this.transientFailure = transientFailure;
        }

        public boolean isTransient() {
            return transientFailure;
        }
    }
}
