package com.docchat.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    /**
     * The server is relative, so Swagger UI calls the origin it was opened from. A generated one
     * lost the port behind the frontend's proxy: X-Forwarded-Proto arrives without a port, so the
     * backend assumes 80, and every "Try it out" failed.
     */
    @Bean
    OpenAPI docChatOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("DocChat AI API")
                        .version("v1")
                        .description("REST API of DocChat AI, a platform to query PDF documents"
                                + " in natural language with verifiable citations."))
                .servers(List.of(new Server().url("/")));
    }
}
