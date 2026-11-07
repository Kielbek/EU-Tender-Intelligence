package eu.tenderintel.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI tenderIntelOpenApi(ObjectProvider<BuildProperties> build) {
        BuildProperties props = build.getIfAvailable();
        return new OpenAPI().info(new Info()
                .title("EU Tender Intelligence API")
                .version(props != null ? props.getVersion() : "dev")
                .description("REST API platformy EU Tender Intelligence. Wszystkie błędy zwracane są w formacie "
                        + "RFC 9457 (application/problem+json) z polem correlationId.")
                .license(new License().name("Proprietary")));
    }
}
