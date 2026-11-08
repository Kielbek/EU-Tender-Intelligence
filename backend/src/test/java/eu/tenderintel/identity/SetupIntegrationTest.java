package eu.tenderintel.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SetupIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;

    @Test
    void newAccountsSeeTheSetupWizardOnce() throws Exception {
        Session s = api.registerVerifyLogin("nowy-" + System.nanoTime() + "@setup.test");
        mvc.perform(get("/api/v1/me").with(s.bearer())).andExpect(jsonPath("$.setupCompleted").value(false));
        mvc.perform(post("/api/v1/me/setup-complete").with(s.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setupCompleted").value(true));
        // Idempotent.
        mvc.perform(post("/api/v1/me/setup-complete").with(s.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").with(s.bearer())).andExpect(jsonPath("$.setupCompleted").value(true));
    }
}
