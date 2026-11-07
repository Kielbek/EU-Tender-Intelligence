package eu.tenderintel.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import eu.tenderintel.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SystemStatusIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void reportsHealthyDependenciesAndMigratedReferenceData() throws Exception {
        mvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.application.defaultLocale").value("pl"))
                .andExpect(jsonPath("$.dependencies[?(@.name=='postgresql')].status").value("UP"))
                .andExpect(jsonPath("$.dependencies[?(@.name=='postgresql')].detail")
                        .value(hasItem(containsString("EU countries: 27"))))
                .andExpect(jsonPath("$.dependencies[?(@.name=='redis')].status").value("UP"))
                .andExpect(jsonPath("$.integrations", hasSize(5)))
                .andExpect(jsonPath("$.integrations[?(@.code=='TED_SEARCH_API')].status").value("NOT_IMPLEMENTED"));
    }

    @Test
    void echoesValidCorrelationIdAndSetsSecurityHeaders() throws Exception {
        mvc.perform(get("/api/v1/system/status").header("X-Request-Id", "test-correlation-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "test-correlation-123"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")));
    }

    @Test
    void deniesEndpointsThatAreNotExplicitlyPublic() throws Exception {
        mvc.perform(get("/api/v1/tenders").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void exposesHealthProbesAndOpenApi() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/system/status']").exists());
    }
}
