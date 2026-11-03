package com.docchat.system;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemStatusController.class)
class SystemStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemStatusService service;

    @Test
    void returnsTheContractUsedByTheFrontend() throws Exception {
        when(service.currentStatus()).thenReturn(SystemStatus.of(ComponentStatus.UP, ComponentStatus.UP));

        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.vectorExtension").value("UP"));
    }

    @Test
    void reportsAFailedDependencyInTheBodyWithStatus200() throws Exception {
        when(service.currentStatus()).thenReturn(SystemStatus.of(ComponentStatus.DOWN, ComponentStatus.UNKNOWN));

        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("DOWN"))
                .andExpect(jsonPath("$.vectorExtension").value("UNKNOWN"));
    }
}
