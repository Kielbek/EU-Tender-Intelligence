package eu.tenderintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the real HTTP API in tests. Each logical client gets its own IP address so that per-IP rate limits of
 * different tests do not interfere.
 */
@TestComponent
public class TestApi {

    public static final String PASSWORD = "correct horse battery staple";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    TestApi(MockMvc mvc, JdbcTemplate jdbc, JsonMapper json) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.json = json;
    }

    public static RequestPostProcessor newIp() {
        String ip = "10.%d.%d.%d".formatted((IP.get() >> 16) & 255, (IP.get() >> 8) & 255, IP.getAndIncrement() & 255);
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    public MockHttpServletResponse postJson(String url, Object body, RequestPostProcessor... processors)
            throws Exception {
        MockHttpServletRequestBuilder req = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        for (RequestPostProcessor p : processors) {
            req = req.with(p);
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    public void register(String email, String fullName, String orgName) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("fullName", fullName);
        body.put("organizationName", orgName);
        body.put("locale", "pl");
        body.put("acceptTerms", true);
        MockHttpServletResponse res = postJson("/api/v1/auth/register", body, newIp());
        assertThat(res.getStatus()).isEqualTo(202);
    }

    /** Last token link sent to {@code email} with the given template. */
    public String tokenFromEmail(String email, String template) {
        String body = jdbc.queryForObject("""
                select body_text from email_outbox where to_address = ? and template = ?
                order by created_at desc limit 1
                """, String.class, email, template);
        Matcher m = TOKEN.matcher(body);
        assertThat(m.find()).as("token link in e-mail").isTrue();
        return m.group(1);
    }

    public void verify(String email) throws Exception {
        String token = tokenFromEmail(email, "VERIFY_EMAIL");
        assertThat(postJson("/api/v1/auth/verify-email", java.util.Map.of("token", token), newIp()).getStatus())
                .isEqualTo(204);
    }

    public Session login(String email, String password) throws Exception {
        MockHttpServletResponse res = postJson("/api/v1/auth/login",
                java.util.Map.of("email", email, "password", password), newIp());
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        JsonNode node = json.readTree(res.getContentAsString());
        return new Session(node.get("accessToken").asString(), res.getCookie("eti_refresh"),
                node.get("user").get("id").asString());
    }

    public Session registerVerifyLogin(String email) throws Exception {
        register(email, "Test User", "Org of " + email);
        verify(email);
        return login(email, PASSWORD);
    }

    public JsonNode readJson(MockHttpServletResponse res) throws Exception {
        return json.readTree(res.getContentAsString());
    }

    public record Session(String accessToken, Cookie refreshCookie, String userId) {

        public RequestPostProcessor bearer() {
            return r -> {
                r.addHeader("Authorization", "Bearer " + accessToken);
                return r;
            };
        }
    }

    public static void expectStatus(MockMvc mvc, MockHttpServletRequestBuilder req, int code) throws Exception {
        mvc.perform(req).andExpect(status().is(code));
    }
}
