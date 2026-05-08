package com.skillforge.server.service;

import com.skillforge.server.dto.DashboardSkillSummaryDto;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.entity.SkillEvalHistoryEntity;
import com.skillforge.server.repository.SkillAbRunRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillEvolutionRunRepository;
import com.skillforge.server.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SKILL-DASHBOARD-POLISH-V2 §G — verify per-owner aggregation for the
 * dashboard SkillSummaryCard. The service combines 5 repositories; each
 * test isolates one slice and stubs the rest at default.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillSummaryService.getSummaryStats")
class SkillSummaryServiceTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillDraftRepository skillDraftRepository;
    @Mock private SkillEvolutionRunRepository skillEvolutionRunRepository;
    @Mock private SkillAbRunRepository skillAbRunRepository;
    @Mock private SkillEvalHistoryRepository skillEvalHistoryRepository;

    private SkillSummaryService service;

    @BeforeEach
    void setUp() {
        service = new SkillSummaryService(skillRepository, skillDraftRepository,
                skillEvolutionRunRepository, skillAbRunRepository, skillEvalHistoryRepository);
    }

    private SkillEntity skill(Long id, boolean enabled, boolean system) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName("Skill" + id);
        s.setOwnerId(7L);
        s.setEnabled(enabled);
        s.setSystem(system);
        return s;
    }

    private SkillEvalHistoryEntity evalRow(Double score) {
        SkillEvalHistoryEntity h = new SkillEvalHistoryEntity();
        h.setCompositeScore(score);
        return h;
    }

    @Test
    @DisplayName("happy path: returns aggregated 5 fields for owner with mixed skills")
    void happyPath_returnsAllFields() {
        when(skillAbRunRepository.countAutoPromotedByOwnerSince(eq(7L), any(Instant.class)))
                .thenReturn(3L);
        when(skillDraftRepository.countByOwnerIdAndStatus(7L, "draft")).thenReturn(5L);
        when(skillEvolutionRunRepository.countByOwnerAndStatusAndCreatedAtAfter(
                eq(7L), eq("FAILED"), any(Instant.class))).thenReturn(1L);

        // 3 enabled (one low, one OK, one no-history) + 1 disabled + 1 system → totalEnabled=3, low=1
        SkillEntity enabledLow = skill(10L, true, false);
        SkillEntity enabledOk = skill(11L, true, false);
        SkillEntity enabledNoHistory = skill(12L, true, false);
        SkillEntity disabled = skill(13L, false, false);
        SkillEntity systemRow = skill(14L, true, true);
        when(skillRepository.findByOwnerId(7L))
                .thenReturn(List.of(enabledLow, enabledOk, enabledNoHistory, disabled, systemRow));

        when(skillEvalHistoryRepository.findFirstBySkillIdOrderByCreatedAtDesc(10L))
                .thenReturn(Optional.of(evalRow(45.0)));   // low
        when(skillEvalHistoryRepository.findFirstBySkillIdOrderByCreatedAtDesc(11L))
                .thenReturn(Optional.of(evalRow(80.0)));   // OK
        when(skillEvalHistoryRepository.findFirstBySkillIdOrderByCreatedAtDesc(12L))
                .thenReturn(Optional.empty());              // no history → not low

        DashboardSkillSummaryDto dto = service.getSummaryStats(7L);

        assertThat(dto.autoUpgradedThisWeek()).isEqualTo(3L);
        assertThat(dto.pendingDraftsCount()).isEqualTo(5L);
        assertThat(dto.failedEvolveThisWeek()).isEqualTo(1L);
        assertThat(dto.totalEnabledSkills()).isEqualTo(3L);
        assertThat(dto.lowScoreSkillsCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("empty owner: zeros across the board")
    void emptyOwner_allZeros() {
        when(skillAbRunRepository.countAutoPromotedByOwnerSince(eq(7L), any(Instant.class)))
                .thenReturn(0L);
        when(skillDraftRepository.countByOwnerIdAndStatus(7L, "draft")).thenReturn(0L);
        when(skillEvolutionRunRepository.countByOwnerAndStatusAndCreatedAtAfter(
                eq(7L), eq("FAILED"), any(Instant.class))).thenReturn(0L);
        when(skillRepository.findByOwnerId(7L)).thenReturn(List.of());

        DashboardSkillSummaryDto dto = service.getSummaryStats(7L);

        assertThat(dto.autoUpgradedThisWeek()).isZero();
        assertThat(dto.pendingDraftsCount()).isZero();
        assertThat(dto.failedEvolveThisWeek()).isZero();
        assertThat(dto.totalEnabledSkills()).isZero();
        assertThat(dto.lowScoreSkillsCount()).isZero();
    }

    @Test
    @DisplayName("low-score boundary: composite=60 is NOT low (strict <60)")
    void boundary_60IsNotLow() {
        when(skillAbRunRepository.countAutoPromotedByOwnerSince(anyLong(), any(Instant.class)))
                .thenReturn(0L);
        when(skillDraftRepository.countByOwnerIdAndStatus(anyLong(), eq("draft"))).thenReturn(0L);
        when(skillEvolutionRunRepository.countByOwnerAndStatusAndCreatedAtAfter(
                anyLong(), eq("FAILED"), any(Instant.class))).thenReturn(0L);
        SkillEntity sk = skill(20L, true, false);
        when(skillRepository.findByOwnerId(7L)).thenReturn(List.of(sk));
        when(skillEvalHistoryRepository.findFirstBySkillIdOrderByCreatedAtDesc(20L))
                .thenReturn(Optional.of(evalRow(60.0)));   // boundary

        DashboardSkillSummaryDto dto = service.getSummaryStats(7L);
        assertThat(dto.totalEnabledSkills()).isEqualTo(1L);
        assertThat(dto.lowScoreSkillsCount()).isZero();
    }

    @Test
    @DisplayName("disabled skills excluded from totalEnabled and lowScore")
    void disabledSkills_excluded() {
        when(skillAbRunRepository.countAutoPromotedByOwnerSince(anyLong(), any(Instant.class)))
                .thenReturn(0L);
        when(skillDraftRepository.countByOwnerIdAndStatus(anyLong(), eq("draft"))).thenReturn(0L);
        when(skillEvolutionRunRepository.countByOwnerAndStatusAndCreatedAtAfter(
                anyLong(), eq("FAILED"), any(Instant.class))).thenReturn(0L);
        SkillEntity disabled = skill(30L, false, false);
        when(skillRepository.findByOwnerId(7L)).thenReturn(List.of(disabled));

        DashboardSkillSummaryDto dto = service.getSummaryStats(7L);
        assertThat(dto.totalEnabledSkills()).isZero();
        assertThat(dto.lowScoreSkillsCount()).isZero();
    }

    @Test
    @DisplayName("null compositeScore is treated as not-low (graceful)")
    void nullCompositeScore_notLow() {
        when(skillAbRunRepository.countAutoPromotedByOwnerSince(anyLong(), any(Instant.class)))
                .thenReturn(0L);
        when(skillDraftRepository.countByOwnerIdAndStatus(anyLong(), eq("draft"))).thenReturn(0L);
        when(skillEvolutionRunRepository.countByOwnerAndStatusAndCreatedAtAfter(
                anyLong(), eq("FAILED"), any(Instant.class))).thenReturn(0L);
        SkillEntity sk = skill(40L, true, false);
        when(skillRepository.findByOwnerId(7L)).thenReturn(List.of(sk));
        when(skillEvalHistoryRepository.findFirstBySkillIdOrderByCreatedAtDesc(40L))
                .thenReturn(Optional.of(evalRow(null)));

        DashboardSkillSummaryDto dto = service.getSummaryStats(7L);
        assertThat(dto.totalEnabledSkills()).isEqualTo(1L);
        assertThat(dto.lowScoreSkillsCount()).isZero();
    }

    @Test
    @DisplayName("null ownerId rejected with IllegalArgumentException")
    void nullOwnerId_rejected() {
        assertThatThrownBy(() -> service.getSummaryStats(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
