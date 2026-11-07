package eu.tenderintel.reference;

import eu.tenderintel.config.AppProperties;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports the CPV 2008 vocabulary (codes, hierarchy and labels in all official EU languages) from the EU Publications
 * Office SPARQL endpoint. Idempotent upsert; safe to re-run.
 */
@Service
public class CpvImportService {

    static final String SCHEME = "http://data.europa.eu/cpv/cpv";
    private static final Logger log = LoggerFactory.getLogger(CpvImportService.class);
    private static final int PAGE = 5000;
    /** Official EU languages (ISO 639-1) – CPV labels exist in all of them. */
    static final List<String> LANGUAGES = List.of("bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "ga",
            "hr", "hu", "it", "lt", "lv", "mt", "nl", "pl", "pt", "ro", "sk", "sl", "sv");

    private final RestClient http;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final String endpoint;
    private final TransactionTemplate tx;

    CpvImportService(RestClient.Builder builder, JdbcTemplate jdbc, JsonMapper json, AppProperties props,
            TransactionTemplate tx) {
        this.tx = tx;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.http = builder.requestFactory(factory).defaultHeader("User-Agent", "EU-Tender-Intelligence/1.0").build();
        this.jdbc = jdbc;
        this.json = json;
        this.endpoint = props.reference().cpvSparqlUrl();
    }

    public record Result(int codes, int languages) {
    }

    record Concept(String code, String parent, Map<String, String> labels) {
    }

    /** Downloads everything first, then writes in one transaction, so a network failure leaves the table intact. */
    public Result importAll() {
        Map<String, Concept> concepts = fetch();
        int languages = concepts.values().stream().mapToInt(c -> c.labels().size()).max().orElse(0);
        write(concepts);
        log.info("CPV import finished: {} codes, up to {} languages", concepts.size(), languages);
        return new Result(concepts.size(), languages);
    }

    Map<String, Concept> fetch() {
        Map<String, Concept> concepts = new TreeMap<>();
        page("""
                PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
                SELECT ?n ?bn WHERE {
                  ?c skos:inScheme <%s> ; skos:notation ?n .
                  OPTIONAL { ?c skos:broader ?b . ?b skos:inScheme <%s> ; skos:notation ?bn }
                } ORDER BY ?n
                """.formatted(SCHEME, SCHEME), row -> {
            String code = value(row, "n");
            if (code != null && code.matches("\\d{8}")) {
                concepts.put(code, new Concept(code, value(row, "bn"), new HashMap<>()));
            }
        });
        // One query per language: the endpoint refuses sorted result windows beyond 10 000 rows, and all labels
        // together are ~9 500 codes x 24 languages.
        for (String lang : LANGUAGES) {
            page("""
                    PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
                    SELECT ?n ?l WHERE {
                      ?c skos:inScheme <%s> ; skos:notation ?n ; skos:prefLabel ?l .
                      FILTER(lang(?l) = "%s")
                    } ORDER BY ?n
                    """.formatted(SCHEME, lang), row -> {
                Concept c = concepts.get(value(row, "n"));
                JsonNode label = row.get("l");
                if (c != null && label != null && label.hasNonNull("xml:lang")) {
                    // Official labels use non-breaking spaces (typography); normalise so plain-text search matches.
                    c.labels().put(label.get("xml:lang").asString().toLowerCase(),
                            label.get("value").asString().replace('\u00a0', ' ').strip());
                }
            });
        }
        if (concepts.size() < 1000) {
            throw new IllegalStateException("CPV endpoint returned only " + concepts.size() + " codes – aborting");
        }
        return concepts;
    }

    void write(Map<String, Concept> concepts) {
        List<Object[]> rows = new ArrayList<>(concepts.size());
        for (Concept c : concepts.values()) {
            String parent = c.parent() != null && concepts.containsKey(c.parent()) ? c.parent() : null;
            rows.add(new Object[] {c.code(), parent, level(c.code()), json.writeValueAsString(new TreeMap<>(c.labels()))});
        }
        tx.executeWithoutResult(status -> jdbc.batchUpdate("""
                insert into cpv_codes (code, parent_code, level, labels, updated_at)
                values (?, ?, ?, ?::jsonb, now())
                on conflict (code) do update set parent_code = excluded.parent_code, level = excluded.level,
                    labels = excluded.labels, updated_at = now()
                """, rows));
    }

    /**
     * CPV hierarchy depth from the code itself: divisions XX000000 (1), groups XXX00000 (2), classes XXXX0000 (3),
     * categories XXXXX000 (4), and anything more specific (5).
     */
    static int level(String code) {
        String significant = code.replaceAll("0+$", "");
        int digits = Math.max(significant.length(), 2);
        return Math.min(digits - 1, 5);
    }

    private void page(String query, java.util.function.Consumer<JsonNode> consumer) {
        for (int offset = 0; ; offset += PAGE) {
            URI uri = UriComponentsBuilder.fromUriString(endpoint)
                    .queryParam("query", "{q}")
                    .encode()
                    .buildAndExpand(Map.of("q", query + " LIMIT " + PAGE + " OFFSET " + offset))
                    .toUri();
            String body = http.get().uri(uri).header("Accept", "application/sparql-results+json").retrieve()
                    .body(String.class);
            JsonNode bindings = json.readTree(body).path("results").path("bindings");
            bindings.forEach(consumer);
            if (bindings.size() < PAGE) {
                return;
            }
        }
    }

    private static String value(JsonNode row, String var) {
        JsonNode node = row.get(var);
        return node == null ? null : node.get("value").asString();
    }

}
