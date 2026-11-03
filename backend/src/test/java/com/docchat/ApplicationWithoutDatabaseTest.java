package com.docchat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Loads the full application with an unreachable database. It proves the context is wired
 * correctly and that a database outage is reported honestly, and it does not require Docker.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
            "spring.datasource.username=nobody",
            "spring.datasource.password=nothing",
            "spring.datasource.hikari.initialization-fail-timeout=-1",
            "spring.datasource.hikari.connection-timeout=250",
            "spring.flyway.enabled=false"
        })
@AutoConfigureMockMvc
class ApplicationWithoutDatabaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusEndpointIsPublicAndReportsTheOutage() throws Exception {
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("DOWN"))
                .andExpect(jsonPath("$.vectorExtension").value("UNKNOWN"))
                .andExpect(jsonPath("$.ai").value("DOWN"));
    }

    @Test
    void livenessStaysUpWhileReadinessFails() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    void healthEndpointDoesNotExposeInternalDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposedEvenToSignedInUsers() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env").with(user("someone"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans").with(user("someone"))).andExpect(status().isNotFound());
    }

    @Test
    void publishesTheOpenApiDocumentAndSwaggerUi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.info.title").value("DocChat AI API"))
                .andExpect(jsonPath("$.paths['/api/system/status'].get").exists())
                .andExpect(jsonPath("$.paths['/api/auth/register'].post").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/documents'].post").exists())
                .andExpect(jsonPath("$.paths['/api/conversations'].post").exists())
                .andExpect(jsonPath("$.paths['/api/documents/{id}/pages/{pageNumber}'].get").exists());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger")));
    }

    @Test
    void protectedRoutesRejectAnonymousRequestsInTheStandardFormat() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void unknownRoutesUseTheStandardErrorFormat() throws Exception {
        mockMvc.perform(get("/api/does-not-exist").with(user("someone")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
