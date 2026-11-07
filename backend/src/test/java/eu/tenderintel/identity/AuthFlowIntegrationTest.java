package eu.tenderintel.identity;

import static eu.tenderintel.TestApi.PASSWORD;
import static eu.tenderintel.TestApi.newIp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class AuthFlowIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void registerVerifyLoginAndReadProfile() throws Exception {
        api.register("Anna.Nowak@Example.com", "Anna Nowak", "Budimex Test Sp. z o.o.");

        // Password is stored as Argon2id, never in plain text; the personal organization is created.
        String hash = jdbc.queryForObject("select password_hash from users where email = 'anna.nowak@example.com'",
                String.class);
        assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);

        MockHttpServletResponse unverified = api.postJson("/api/v1/auth/login",
                Map.of("email", "anna.nowak@example.com", "password", PASSWORD), newIp());
        assertThat(unverified.getStatus()).isEqualTo(403);
        assertThat(api.readJson(unverified).get("code").asString()).isEqualTo("email_not_verified");

        api.verify("anna.nowak@example.com");
        Session session = api.login("ANNA.NOWAK@example.com", PASSWORD);

        Cookie cookie = session.refreshCookie();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");

        mvc.perform(get("/api/v1/me").with(session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("anna.nowak@example.com"))
                .andExpect(jsonPath("$.locale").value("pl"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.platformRole").value("USER"));

        mvc.perform(get("/api/v1/organizations").with(session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Budimex Test Sp. z o.o."))
                .andExpect(jsonPath("$[0].slug").value("budimex-test-sp-z-o-o"))
                .andExpect(jsonPath("$[0].role").value("COMPANY_ADMIN"));

        mvc.perform(patch("/api/v1/me").with(session.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"de\",\"timezone\":\"Europe/Berlin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("de"));
        mvc.perform(patch("/api/v1/me").with(session.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\":\"Mars/Olympus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_timezone"));

        Integer audits = jdbc.queryForObject("select count(*) from audit_logs where actor_user_id = ?::uuid "
                + "and action in ('USER_REGISTERED','EMAIL_VERIFIED','LOGIN_SUCCEEDED')", Integer.class,
                session.userId());
        assertThat(audits).isEqualTo(3);
    }

    @Test
    void registrationDoesNotRevealExistingAccounts() throws Exception {
        api.register("taken@example.com", "First", null);
        api.register("taken@example.com", "Second", null);
        Integer users = jdbc.queryForObject("select count(*) from users where email = 'taken@example.com'",
                Integer.class);
        assertThat(users).isEqualTo(1);
        Integer notices = jdbc.queryForObject("select count(*) from email_outbox where to_address = "
                + "'taken@example.com' and template = 'ACCOUNT_ALREADY_EXISTS'", Integer.class);
        assertThat(notices).isEqualTo(1);
    }

    @Test
    void rejectsWeakPasswordsAndMissingTermsAcceptance() throws Exception {
        MockHttpServletResponse weak = api.postJson("/api/v1/auth/register", Map.of("email", "weak@example.com",
                "password", "short", "fullName", "Weak", "acceptTerms", true), newIp());
        assertThat(weak.getStatus()).isEqualTo(400);
        assertThat(api.readJson(weak).get("code").asString()).isEqualTo("weak_password");

        MockHttpServletResponse noTerms = api.postJson("/api/v1/auth/register", Map.of("email", "terms@example.com",
                "password", PASSWORD, "fullName", "No Terms", "acceptTerms", false), newIp());
        assertThat(noTerms.getStatus()).isEqualTo(400);
        assertThat(api.readJson(noTerms).get("code").asString()).isEqualTo("validation_failed");
    }

    @Test
    void refreshRotatesTokenAndDetectsReuse() throws Exception {
        Session session = api.registerVerifyLogin("rotate@example.com");
        Cookie first = session.refreshCookie();

        MockHttpServletResponse noCsrf = mvc.perform(post("/api/v1/auth/refresh").cookie(first))
                .andReturn().getResponse();
        assertThat(noCsrf.getStatus()).isEqualTo(403);

        MockHttpServletResponse refreshed = refresh(first);
        assertThat(refreshed.getStatus()).isEqualTo(200);
        Cookie second = refreshed.getCookie("eti_refresh");
        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        String newAccess = api.readJson(refreshed).get("accessToken").asString();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + newAccess)).andExpect(status().isOk());

        // Replaying the rotated token revokes the whole family – including the newest token.
        assertThat(refresh(first).getStatus()).isEqualTo(401);
        assertThat(refresh(second).getStatus()).isEqualTo(401);
        Integer reuse = jdbc.queryForObject("select count(*) from audit_logs where action = "
                + "'REFRESH_TOKEN_REUSE_DETECTED' and actor_user_id = ?::uuid", Integer.class, session.userId());
        assertThat(reuse).isEqualTo(1);
    }

    @Test
    void logoutRevokesSession() throws Exception {
        Session session = api.registerVerifyLogin("logout@example.com");
        MockHttpServletResponse res = mvc.perform(post("/api/v1/auth/logout").cookie(session.refreshCookie())
                .header("X-ETI-CSRF", "1")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(204);
        assertThat(res.getCookie("eti_refresh").getMaxAge()).isZero();
        assertThat(refresh(session.refreshCookie()).getStatus()).isEqualTo(401);
    }

    @Test
    void locksAccountTemporarilyAfterRepeatedFailures() throws Exception {
        api.registerVerifyLogin("brute@example.com");
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse res = api.postJson("/api/v1/auth/login",
                    Map.of("email", "brute@example.com", "password", "wrong password " + i), newIp());
            assertThat(res.getStatus()).isEqualTo(401);
            assertThat(api.readJson(res).get("code").asString()).isEqualTo("invalid_credentials");
        }
        MockHttpServletResponse locked = api.postJson("/api/v1/auth/login",
                Map.of("email", "brute@example.com", "password", PASSWORD), newIp());
        assertThat(locked.getStatus()).isEqualTo(429);
        assertThat(api.readJson(locked).get("code").asString()).isEqualTo("too_many_attempts");
    }

    @Test
    void unknownAccountGetsSameErrorAsWrongPassword() throws Exception {
        MockHttpServletResponse res = api.postJson("/api/v1/auth/login",
                Map.of("email", "nobody@example.com", "password", PASSWORD), newIp());
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(api.readJson(res).get("code").asString()).isEqualTo("invalid_credentials");
    }

    @Test
    void passwordResetChangesPasswordAndRevokesSessions() throws Exception {
        Session session = api.registerVerifyLogin("reset@example.com");
        assertThat(api.postJson("/api/v1/auth/password/forgot", Map.of("email", "reset@example.com"), newIp())
                .getStatus()).isEqualTo(202);
        assertThat(api.postJson("/api/v1/auth/password/forgot", Map.of("email", "ghost@example.com"), newIp())
                .getStatus()).isEqualTo(202);

        String token = api.tokenFromEmail("reset@example.com", "PASSWORD_RESET");
        String newPassword = "an entirely new passphrase";
        assertThat(api.postJson("/api/v1/auth/password/reset", Map.of("token", token, "newPassword", newPassword),
                newIp()).getStatus()).isEqualTo(204);
        // Single use.
        assertThat(api.postJson("/api/v1/auth/password/reset", Map.of("token", token, "newPassword", newPassword),
                newIp()).getStatus()).isEqualTo(400);

        assertThat(refresh(session.refreshCookie()).getStatus()).isEqualTo(401);
        assertThat(api.postJson("/api/v1/auth/login", Map.of("email", "reset@example.com", "password", PASSWORD),
                newIp()).getStatus()).isEqualTo(401);
        api.login("reset@example.com", newPassword);
    }

    @Test
    void rejectsTamperedAccessToken() throws Exception {
        Session session = api.registerVerifyLogin("tamper@example.com");
        String token = session.accessToken();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rateLimitsRegistrationsPerIpWithRetryAfter() throws Exception {
        var ip = newIp();
        MockHttpServletResponse last = null;
        for (int i = 0; i < 11; i++) {
            last = api.postJson("/api/v1/auth/register", Map.of("email", "flood" + i + "@example.com", "password",
                    PASSWORD, "fullName", "Flood", "acceptTerms", true), ip);
        }
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getHeader("Retry-After")).isNotBlank();
        assertThat(api.readJson(last).get("code").asString()).isEqualTo("rate_limited");
    }

    private MockHttpServletResponse refresh(Cookie cookie) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").cookie(cookie).header("X-ETI-CSRF", "1").with(newIp()))
                .andReturn().getResponse();
    }
}
