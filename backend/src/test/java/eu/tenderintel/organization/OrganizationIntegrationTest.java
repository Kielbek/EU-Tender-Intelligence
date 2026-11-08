package eu.tenderintel.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OrganizationIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void tenantIsolationInvitationsAndRoles() throws Exception {
        Session alice = api.registerVerifyLogin("alice@org.test");
        Session bob = api.registerVerifyLogin("bob@org.test");
        String aliceOrg = orgId(alice);
        String bobOrg = orgId(bob);
        // Teams need a paid plan (FREE has one seat – see BillingIntegrationTest).
        jdbc.update("update organizations set plan_code = 'PRO' where id = ?::uuid", aliceOrg);

        // Bob cannot see or modify Alice's organization – and cannot even tell it exists.
        mvc.perform(get("/api/v1/organizations/" + aliceOrg).with(bob.bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/organizations/" + aliceOrg + "/members").with(bob.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/organizations/" + aliceOrg).with(bob.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(patch("/api/v1/organizations/" + aliceOrg).with(alice.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alice GmbH\",\"countryCode\":\"DE\",\"vatId\":\"DE123456789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice GmbH"))
                .andExpect(jsonPath("$.countryCode").value("DE"));

        // Invitation: Alice invites Bob as TEAM_MEMBER; only the invited address can accept.
        mvc.perform(post("/api/v1/organizations/" + aliceOrg + "/invitations").with(alice.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@org.test\",\"role\":\"TEAM_MEMBER\"}"))
                .andExpect(status().isCreated());
        String token = api.tokenFromEmail("bob@org.test", "ORGANIZATION_INVITATION");

        Session carol = api.registerVerifyLogin("carol@org.test");
        mvc.perform(post("/api/v1/invitations/accept").with(carol.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/invitations/accept").with(bob.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TEAM_MEMBER"));
        mvc.perform(get("/api/v1/organizations").with(bob.bearer())).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/v1/organizations/" + aliceOrg + "/members").with(bob.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));

        // TEAM_MEMBER cannot invite or change roles.
        mvc.perform(post("/api/v1/organizations/" + aliceOrg + "/invitations").with(bob.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"eve@org.test\",\"role\":\"COMPANY_ADMIN\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/organizations/" + aliceOrg + "/members/" + bob.userId() + "/role")
                .with(bob.bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"COMPANY_ADMIN\"}"))
                .andExpect(status().isForbidden());

        // The last admin cannot be demoted or removed.
        mvc.perform(put("/api/v1/organizations/" + aliceOrg + "/members/" + alice.userId() + "/role")
                        .with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TEAM_MEMBER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("last_admin"));

        // Bob leaves on his own.
        mvc.perform(delete("/api/v1/organizations/" + aliceOrg + "/members/" + bob.userId()).with(bob.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/" + aliceOrg).with(bob.bearer())).andExpect(status().isNotFound());
        assertThat(bobOrg).isNotEqualTo(aliceOrg);
    }

    @Test
    void invitationsCanBeListedAndRevoked() throws Exception {
        Session admin = api.registerVerifyLogin("dana@org.test");
        String org = orgId(admin);
        jdbc.update("update organizations set plan_code = 'STARTER' where id = ?::uuid", org);
        var res = mvc.perform(post("/api/v1/organizations/" + org + "/invitations").with(admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"later@org.test\",\"role\":\"TEAM_MEMBER\"}")).andReturn().getResponse();
        String invitationId = api.readJson(res).get("id").asString();
        String token = api.tokenFromEmail("later@org.test", "ORGANIZATION_INVITATION");

        mvc.perform(get("/api/v1/organizations/" + org + "/invitations").with(admin.bearer()))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(delete("/api/v1/organizations/" + org + "/invitations/" + invitationId).with(admin.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/" + org + "/invitations").with(admin.bearer()))
                .andExpect(jsonPath("$", hasSize(0)));

        Session later = api.registerVerifyLogin("later@org.test");
        mvc.perform(post("/api/v1/invitations/accept").with(later.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_token"));
    }

    private String orgId(Session s) throws Exception {
        return api.readJson(mvc.perform(get("/api/v1/organizations").with(s.bearer())).andReturn().getResponse())
                .get(0).get("id").asString();
    }
}
