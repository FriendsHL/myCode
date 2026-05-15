package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V4 Phase 1.4 — {@link BehaviorRuleVersionController} REST shape + status
 * code mapping tests. MockMvc standalone setup (same pattern as
 * {@code CanaryRolloutControllerTest}).
 */
@EnableWebMvc
@DisplayName("BehaviorRuleVersionController")
class BehaviorRuleVersionControllerTest {

    private BehaviorRuleVersionRepository versionRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        versionRepository = mock(BehaviorRuleVersionRepository.class);
        // java.md footgun #1: register JavaTimeModule so Instant fields
        // serialize as ISO-8601 strings instead of epoch arrays.
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        BehaviorRuleVersionController controller =
                new BehaviorRuleVersionController(versionRepository);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private BehaviorRuleVersionEntity version(String id, String agentId, int versionNumber, String status) {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId(id);
        v.setAgentId(agentId);
        v.setVersionNumber(versionNumber);
        v.setStatus(status);
        v.setSource(BehaviorRuleVersionEntity.SOURCE_ATTRIBUTION);
        v.setRulesJson("[{\"id\":\"ALWAYS_USE_BASH\"}]");
        v.setImprovementRationale("curator rationale");
        v.setSourceEventId(42L);
        v.setBaselineVersionId("br-prev-uuid");
        v.setCreatedAt(Instant.parse("2026-05-15T10:00:00Z"));
        if (BehaviorRuleVersionEntity.STATUS_ACTIVE.equals(status)) {
            v.setPromotedAt(Instant.parse("2026-05-15T11:00:00Z"));
        }
        return v;
    }

    // ───────────────────────── GET list ────────────────────────

    @Test
    @DisplayName("GET /api/behavior-rules/versions?agentId=42 returns all versions newest-first")
    void list_happyPath_returnsAllVersionsForAgent() throws Exception {
        BehaviorRuleVersionEntity active = version("br-v2-uuid", "42", 2, BehaviorRuleVersionEntity.STATUS_ACTIVE);
        BehaviorRuleVersionEntity retired = version("br-v1-uuid", "42", 1, BehaviorRuleVersionEntity.STATUS_RETIRED);
        when(versionRepository.findByAgentIdOrderByVersionNumberDesc("42"))
                .thenReturn(List.of(active, retired));

        mvc.perform(get("/api/behavior-rules/versions").param("agentId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // First row = active (versionNumber=2)
                .andExpect(jsonPath("$[0].id").value("br-v2-uuid"))
                .andExpect(jsonPath("$[0].versionNumber").value(2))
                .andExpect(jsonPath("$[0].status").value("active"))
                .andExpect(jsonPath("$[0].agentId").value("42"))
                .andExpect(jsonPath("$[0].source").value("attribution"))
                .andExpect(jsonPath("$[0].rulesJson").value("[{\"id\":\"ALWAYS_USE_BASH\"}]"))
                .andExpect(jsonPath("$[0].promotedAt").exists())
                // Second row = retired (versionNumber=1)
                .andExpect(jsonPath("$[1].id").value("br-v1-uuid"))
                .andExpect(jsonPath("$[1].status").value("retired"));

        // status= not supplied → unfiltered finder used, not the status one.
        verify(versionRepository, never())
                .findByAgentIdAndStatusOrderByVersionNumberDesc(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("GET /api/behavior-rules/versions?agentId=42&status=active uses status-filter finder")
    void list_withStatusFilter_callsStatusOrderedFinder() throws Exception {
        BehaviorRuleVersionEntity active = version("br-v2-uuid", "42", 2, BehaviorRuleVersionEntity.STATUS_ACTIVE);
        when(versionRepository.findByAgentIdAndStatusOrderByVersionNumberDesc("42", "active"))
                .thenReturn(List.of(active));

        mvc.perform(get("/api/behavior-rules/versions")
                        .param("agentId", "42")
                        .param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("br-v2-uuid"))
                .andExpect(jsonPath("$[0].status").value("active"));

        // Unfiltered finder NOT called when status param supplied.
        verify(versionRepository, never())
                .findByAgentIdOrderByVersionNumberDesc(org.mockito.ArgumentMatchers.anyString());
    }

    // ───────────────────────── GET detail ──────────────────────

    @Test
    @DisplayName("GET /api/behavior-rules/versions/{id} returns single version when present")
    void getOne_happyPath_returnsVersion() throws Exception {
        BehaviorRuleVersionEntity v = version("br-v2-uuid", "42", 2, BehaviorRuleVersionEntity.STATUS_ACTIVE);
        when(versionRepository.findById("br-v2-uuid")).thenReturn(Optional.of(v));

        mvc.perform(get("/api/behavior-rules/versions/{id}", "br-v2-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("br-v2-uuid"))
                .andExpect(jsonPath("$.agentId").value("42"))
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.source").value("attribution"))
                .andExpect(jsonPath("$.rulesJson").value("[{\"id\":\"ALWAYS_USE_BASH\"}]"))
                .andExpect(jsonPath("$.improvementRationale").value("curator rationale"))
                .andExpect(jsonPath("$.sourceEventId").value(42))
                .andExpect(jsonPath("$.baselineVersionId").value("br-prev-uuid"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.promotedAt").exists());
    }

    @Test
    @DisplayName("GET /api/behavior-rules/versions/{id} returns 404 when not found")
    void getOne_notFound_returns404() throws Exception {
        when(versionRepository.findById("ghost-uuid")).thenReturn(Optional.empty());

        mvc.perform(get("/api/behavior-rules/versions/{id}", "ghost-uuid"))
                .andExpect(status().isNotFound());
    }
}
