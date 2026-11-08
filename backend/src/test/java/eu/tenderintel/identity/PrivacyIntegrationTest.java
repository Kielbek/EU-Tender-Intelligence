package eu.tenderintel.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class PrivacyIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;
    @Autowired
    JdbcTemplate jdbc;

    private String org(Session s) throws Exception {
        return api.readJson(mvc.perform(get("/api/v1/organizations").with(s.bearer())).andReturn().getResponse())
                .get(0).get("id").asString();
    }

    @Test
    void exportContainsThePersonsData() throws Exception {
        String email = "ewa-" + System.nanoTime() + "@privacy.test";
        Session ewa = api.registerVerifyLogin(email);
        mvc.perform(get("/api/v1/me/export").with(ewa.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.account.email").value(email))
                .andExpect(jsonPath("$.account.password_hash").doesNotExist())
                .andExpect(jsonPath("$.organizations[0].role").value("COMPANY_ADMIN"))
                .andExpect(jsonPath("$.sessions").isArray())
                .andExpect(jsonPath("$.securityLog").isArray())
                .andExpect(jsonPath("$.emails[0].template").exists());
        mvc.perform(get("/api/v1/me/export")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'PERSONAL_DATA_EXPORTED' "
                + "and actor_user_id = ?::uuid", Integer.class, ewa.userId())).isEqualTo(1);
    }

    @Test
    void deletionRemovesTheAccountAndSoleOrganizations() throws Exception {
        String email = "adam-" + System.nanoTime() + "@privacy.test";
        Session adam = api.registerVerifyLogin(email);
        String org = org(adam);
        String body = "{\"password\":\"wrong password here\"}";
        mvc.perform(delete("/api/v1/me").with(adam.bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_current_password"));

        // Only admin of an organization with another member: must hand over first.
        Session ola = api.registerVerifyLogin("ola-" + System.nanoTime() + "@privacy.test");
        jdbc.update("insert into organization_members (organization_id, user_id, role) values (?::uuid, ?::uuid, "
                + "'TEAM_MEMBER')", org, ola.userId());
        String ok = "{\"password\":\"" + TestApi.PASSWORD + "\"}";
        mvc.perform(delete("/api/v1/me").with(adam.bearer()).contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("transfer_admin_first"));
        jdbc.update("delete from organization_members where organization_id = ?::uuid and user_id = ?::uuid", org,
                ola.userId());

        mvc.perform(delete("/api/v1/me").with(adam.bearer()).contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from organizations where id = ?::uuid", Integer.class, org))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where to_address = ?", Integer.class,
                email)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where actor_user_id = ?::uuid "
                + "and (ip is not null or user_agent is not null)", Integer.class, adam.userId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ACCOUNT_DELETED' "
                + "and target_id = ?", Integer.class, adam.userId())).isEqualTo(1);
        // The old access token no longer works.
        mvc.perform(get("/api/v1/me").with(adam.bearer())).andExpect(status().isNotFound());
        assertThat(api.postJson("/api/v1/auth/login", Map.of("email", email, "password", TestApi.PASSWORD),
                TestApi.newIp()).getStatus()).isEqualTo(401);
    }

    @Test
    void legalInformationIsPublicAndNeverInvented() throws Exception {
        mvc.perform(get("/api/v1/system/legal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.operatorName").doesNotExist())
                .andExpect(jsonPath("$.termsVersion").exists())
                .andExpect(jsonPath("$.processors[0].code").value("HOSTING"));
    }
}
