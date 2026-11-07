package eu.tenderintel.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import eu.tenderintel.IntegrationTest;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ReferenceIntegrationTest {

    static HttpServer sparql;

    @DynamicPropertySource
    static void stubSparql(DynamicPropertyRegistry registry) throws Exception {
        sparql = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sparql.createContext("/sparql", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            boolean firstPage = query.contains("OFFSET 0");
            String body = firstPage ? (query.contains("broader") ? codesPage() : labelsPage()) : empty();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/sparql-results+json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        sparql.start();
        registry.add("app.reference.cpv-sparql-url",
                () -> "http://127.0.0.1:" + sparql.getAddress().getPort() + "/sparql");
    }

    /** 1 200 synthetic codes 9900xxxx (outside the real CPV range) plus one real-looking hierarchy for assertions. */
    static String codesPage() {
        List<String> rows = new ArrayList<>();
        rows.add(row("n", "45000000", null, null));
        rows.add("{\"n\":{\"type\":\"literal\",\"value\":\"45200000\"},\"bn\":{\"type\":\"literal\",\"value\":\"45000000\"}}");
        for (int i = 0; i < 1200; i++) {
            rows.add(row("n", "99%06d".formatted(i * 10 + 10), null, null));
        }
        return "{\"results\":{\"bindings\":[" + String.join(",", rows) + "]}}";
    }

    static String labelsPage() {
        return "{\"results\":{\"bindings\":["
                + label("45000000", "pl", "Roboty\u00a0budowlane") + "," + label("45000000", "en", "Construction work") + ","
                + label("45200000", "en", "Works for complete or part construction") + "]}}";
    }

    static String empty() {
        return "{\"results\":{\"bindings\":[]}}";
    }

    static String row(String var, String value, String var2, String value2) {
        return "{\"" + var + "\":{\"type\":\"literal\",\"value\":\"" + value + "\"}}";
    }

    static String label(String code, String lang, String text) {
        return "{\"n\":{\"type\":\"literal\",\"value\":\"" + code + "\"},\"l\":{\"type\":\"literal\",\"xml:lang\":\""
                + lang + "\",\"value\":\"" + text + "\"}}";
    }

    @Autowired
    CpvImportService cpvImport;
    @Autowired
    ExchangeRateService rates;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;

    @Test
    void importsCpvHierarchyAndLabelsIdempotently() throws Exception {
        CpvImportService.Result first = cpvImport.importAll();
        CpvImportService.Result second = cpvImport.importAll();
        assertThat(first.codes()).isEqualTo(1202);
        assertThat(second.codes()).isEqualTo(1202);
        assertThat(jdbc.queryForObject("select parent_code from cpv_codes where code = '45200000'", String.class))
                .isEqualTo("45000000");

        mvc.perform(get("/api/v1/cpv").param("query", "budowlane").param("lang", "pl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("45000000"))
                .andExpect(jsonPath("$[0].label").value("Roboty budowlane"));
        mvc.perform(get("/api/v1/cpv/45200000").param("lang", "pl"))
                .andExpect(jsonPath("$.level").value(2))
                .andExpect(jsonPath("$.ancestors", hasSize(1)))
                .andExpect(jsonPath("$.ancestors[0].label").value("Roboty budowlane"));
        mvc.perform(get("/api/v1/cpv/abc")).andExpect(status().isBadRequest());
        jdbc.update("delete from cpv_codes where code like '99%'");
    }

    @Test
    void convertsToEurWithRecentEcbRateOnly() {
        jdbc.update("insert into exchange_rates (currency, rate_date, rate_per_eur) values ('SEK', '2026-11-02', 11.0)"
                + " on conflict do nothing");
        assertThat(rates.toEur(new BigDecimal("1100000"), "SEK", LocalDate.of(2026, 11, 4)))
                .contains(new BigDecimal("100000.00"));
        assertThat(rates.toEur(new BigDecimal("100"), "SEK", LocalDate.of(2026, 12, 31))).isEmpty();
        assertThat(rates.toEur(new BigDecimal("12.345"), "EUR", LocalDate.of(2026, 1, 1)))
                .contains(new BigDecimal("12.35"));
    }

    @Test
    void publicReferenceEndpoints() throws Exception {
        mvc.perform(get("/api/v1/countries")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(27)));
        mvc.perform(get("/api/v1/currencies")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='PLN')].minorUnits").value(2));
    }
}
