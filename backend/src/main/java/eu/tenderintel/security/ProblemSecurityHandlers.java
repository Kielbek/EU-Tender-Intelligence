package eu.tenderintel.security;

import eu.tenderintel.common.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 401/403 raised by the security filter chain rendered as RFC 9457 problems, like every other API error. */
@Component
public class ProblemSecurityHandlers {

    private final JsonMapper mapper;

    ProblemSecurityHandlers(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> write(response,
                GlobalExceptionHandler.problem(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required"));
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> write(response,
                GlobalExceptionHandler.problem(HttpStatus.FORBIDDEN, "forbidden",
                        "You do not have access to this resource"));
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (problem.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
