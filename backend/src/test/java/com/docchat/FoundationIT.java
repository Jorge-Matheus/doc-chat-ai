package com.docchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Runs against a real PostgreSQL with pgvector, started by Testcontainers. Requires Docker. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FoundationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesTheMigrations() {
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        Integer enablePgvector = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history"
                        + " WHERE description = 'enable pgvector' AND success = true",
                Integer.class);

        assertThat(failed).isZero();
        assertThat(enablePgvector).isEqualTo(1);
    }

    @Test
    void pgvectorExtensionIsUsable() {
        Double distance = jdbcTemplate.queryForObject(
                "SELECT '[1,2,3]'::vector <=> '[1,2,3]'::vector", Double.class);

        assertThat(distance).isZero();
    }

    @Test
    void statusEndpointReportsEveryDependencyAsUp() throws Exception {
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.vectorExtension").value("UP"));
    }

    @Test
    void readinessIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
