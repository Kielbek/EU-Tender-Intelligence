package eu.tenderintel.common;

import static org.assertj.core.api.Assertions.assertThat;

import eu.tenderintel.common.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void keepsWellFormedClientId() throws Exception {
        assertThat(run("abc-12345.XYZ")).isEqualTo("abc-12345.XYZ");
    }

    @Test
    void replacesMissingOrMaliciousIds() throws Exception {
        assertThat(run(null)).hasSize(36);
        assertThat(run("bad\nlog-injection")).isNotEqualTo("bad\nlog-injection").hasSize(36);
        assertThat(run("x".repeat(65))).hasSize(36);
    }

    private String run(String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader(CorrelationIdFilter.HEADER, header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getHeader(CorrelationIdFilter.HEADER);
    }
}
