package eu.tenderintel.admin;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class AdminIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;

    @Test
    void onlyPlatformAdminsReachAdminApi() throws Exception {
        Session user = api.registerVerifyLogin("regular@admin.test");
        mvc.perform(get("/api/v1/admin/users").with(user.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        // admin@eti.test is listed in PLATFORM_ADMIN_EMAILS and promoted once the address is verified.
        Session admin = api.registerVerifyLogin("admin@eti.test");
        mvc.perform(get("/api/v1/me").with(admin.bearer())).andExpect(jsonPath("$.platformRole")
                .value("PLATFORM_ADMIN"));
        mvc.perform(get("/api/v1/admin/users").param("q", "regular@").with(admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].email").value("regular@admin.test"));

        mvc.perform(get("/api/v1/admin/data-sources").with(admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='TED')].status").value("INACTIVE"))
                .andExpect(jsonPath("$[?(@.code=='PL_EZAMOWIENIA')].status").value("PENDING_CONFIGURATION"));
        mvc.perform(get("/api/v1/admin/audit-logs").param("action", "PLATFORM_ADMIN_GRANTED").with(admin.bearer()))
                .andExpect(jsonPath("$.totalItems", greaterThanOrEqualTo(1)));
        mvc.perform(get("/api/v1/admin/email-outbox").with(admin.bearer()))
                .andExpect(jsonPath("$.smtpConfigured").value(false))
                .andExpect(jsonPath("$.pending", greaterThanOrEqualTo(1)));

        // Locking a user kills their sessions immediately at the next refresh.
        mvc.perform(post("/api/v1/admin/users/" + user.userId() + "/lock").with(admin.bearer()))
                .andExpect(status().isNoContent());
        int refresh = mvc.perform(post("/api/v1/auth/refresh").cookie(user.refreshCookie())
                .header("X-ETI-CSRF", "1").with(TestApi.newIp())).andReturn().getResponse().getStatus();
        assertThat(refresh).isEqualTo(401);
        assertThat(api.postJson("/api/v1/auth/login", Map.of("email", "regular@admin.test", "password",
                TestApi.PASSWORD), TestApi.newIp()).getStatus()).isEqualTo(403);
        mvc.perform(post("/api/v1/admin/users/" + admin.userId() + "/lock").with(admin.bearer()))
                .andExpect(status().isBadRequest());
    }
}
