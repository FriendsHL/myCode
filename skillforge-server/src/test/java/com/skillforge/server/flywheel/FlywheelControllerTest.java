package com.skillforge.server.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FLYWHEEL-PER-RUN — {@link FlywheelController} REST shape + param validation.
 *
 * <p>r2 W2 coverage: agentType must be one of {@code user} / {@code system} —
 * unknown values fail fast with 400 (was previously silent empty []).
 *
 * <p>MockMvc standaloneSetup mirrors {@code AttributionEventControllerTest}.
 */
@EnableWebMvc
@DisplayName("FlywheelController")
class FlywheelControllerTest {

    private FlywheelRunsService runsService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        runsService = mock(FlywheelRunsService.class);
        // r2 W3: mirror Spring autoconfigured ObjectMapper (findAndRegisterModules
        // discovers JavaTimeModule via SPI same way Spring Boot does).
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        FlywheelController controller = new FlywheelController(runsService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("GET /api/flywheel/runs → 200 with defaults (limit=20, hideTerminal=true)")
    void list_defaults_returns200() throws Exception {
        when(runsService.listRecentRuns(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.hideTerminal").value(true));

        verify(runsService).listRecentRuns(eq(null), eq(null), eq(20), eq(true));
    }

    @Test
    @DisplayName("agentType=invalid → 400 (r2 W2 — fail-fast, not silent [])")
    void list_invalidAgentType_returns400() throws Exception {
        mvc.perform(get("/api/flywheel/runs").param("agentType", "robot"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("agentType=user → 200 + service receives 'user'")
    void list_userAgentType_returns200AndPassesThrough() throws Exception {
        when(runsService.listRecentRuns(eq("user"), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs").param("agentType", "user"))
                .andExpect(status().isOk());

        verify(runsService).listRecentRuns(eq("user"), eq(null), eq(20), eq(true));
    }

    @Test
    @DisplayName("agentType=system → 200 + service receives 'system'")
    void list_systemAgentType_returns200AndPassesThrough() throws Exception {
        when(runsService.listRecentRuns(eq("system"), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs").param("agentType", "system"))
                .andExpect(status().isOk());

        verify(runsService).listRecentRuns(eq("system"), eq(null), eq(20), eq(true));
    }

    @Test
    @DisplayName("agentType=  (blank) → 200, treated as no filter (not 400)")
    void list_blankAgentType_treatedAsNoFilter() throws Exception {
        when(runsService.listRecentRuns(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs").param("agentType", ""))
                .andExpect(status().isOk());

        verify(runsService).listRecentRuns(eq(null), eq(null), eq(20), eq(true));
    }

    @Test
    @DisplayName("limit clamping: limit=999 → 100; limit=-5 → 1")
    void list_limitClamping_minMaxEnforced() throws Exception {
        when(runsService.listRecentRuns(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs").param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(100));

        mvc.perform(get("/api/flywheel/runs").param("limit", "-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(1));
    }

    @Test
    @DisplayName("hideTerminal=false → service called with false")
    void list_hideTerminalFalse_passesThrough() throws Exception {
        when(runsService.listRecentRuns(any(), any(), anyInt(), eq(false)))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs").param("hideTerminal", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hideTerminal").value(false));

        verify(runsService).listRecentRuns(eq(null), eq(null), eq(20), eq(false));
    }
}
