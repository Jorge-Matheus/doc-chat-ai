package com.docchat.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI docChatOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("DocChat AI API")
                        .version("v1")
                        .description("REST API of DocChat AI, a platform to query PDF documents"
                                + " in natural language with verifiable citations."));
    }
}
