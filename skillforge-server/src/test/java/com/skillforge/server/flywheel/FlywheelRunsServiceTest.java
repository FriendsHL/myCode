package com.skillforge.server.flywheel;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FLYWHEEL-PER-RUN — unit tests for {@link FlywheelRunsService}.
 *
 * <p>Covers:
 * <ol>
 *   <li>happy path → events → patterns + agents joined → DTOs match</li>
 *   <li>{@code hideTerminal=true} → repo gets the terminal-stage list as the
 *       NOT IN parameter; {@code hideTerminal=false} → passes {@code null}
 *       so the JPQL guard short-circuits</li>
 *   <li>{@code agentType} non-blank → resolves agentIds first then calls the
 *       agentIds-scoped finder; empty agentIds early-returns without
 *       touching OptEvent finder</li>
 *   <li>error label maps the three failed stages correctly</li>
 *   <li>empty result returns {@code []} without doing dependent lookups</li>
 *   <li>signature snippet truncates at {@code SIGNATURE_SNIPPET_MAX} with
 *       ellipsis and stays UTF-16 surrogate-safe</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlywheelRunsService")
class FlywheelRunsServiceTest {

    @Mock private OptimizationEventRepository optimizationEventRepository;
    @Mock private SessionPatternRepository sessionPatternRepository;
    @Mock private AgentRepository agentRepository;

    private FlywheelRunsService service;

    @BeforeEach
    void setUp() {
        service = new FlywheelRunsService(
                optimizationEventRepository, sessionPatternRepository, agentRepository);
    }

    @Test
    @DisplayName("happy path — DTOs joined with pattern signature + agent name")
    void listRecentRuns_happyPath_dtosJoined() {
        OptimizationEventEntity e1 = event(101L, 11L, 21L, "skill",
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                "draft-uuid-a", null,
                Instant.parse("2026-05-19T10:00:00Z"),
                Instant.parse("2026-05-20T11:00:00Z"));
        OptimizationEventEntity e2 = event(102L, 12L, 22L, "prompt",
                OptimizationEventEntity.STAGE_AB_RUNNING,
                null, 999L,
                Instant.parse("2026-05-19T11:00:00Z"),
                Instant.parse("2026-05-20T10:30:00Z"));

        when(optimizationEventRepository.findRecentRunsForFlywheel(
                isNull(), eq(FlywheelRunsService.TERMINAL_HAPPY_STAGES), any(Pageable.class)))
                .thenReturn(List.of(e1, e2));
        // r2 W1 fix: ensure the unused-when-hideTerminal=true finder is NOT called.
        when(sessionPatternRepository.findAllById(any()))
                .thenReturn(List.of(
                        pattern(11L, "outcome=fail|surface=skill|tool=Bash|agent=21"),
                        pattern(12L, "outcome=timeout|surface=prompt|tool=null|agent=22")));
        when(agentRepository.findAllById(any()))
                .thenReturn(List.of(
                        agent(21L, "Code Assistant", "user"),
                        agent(22L, "Doc Search", "user")));

        List<FlywheelRunDto> dtos = service.listRecentRuns(null, null, 20, true);

        assertThat(dtos).hasSize(2);
        FlywheelRunDto d1 = dtos.get(0);
        assertThat(d1.optEventId()).isEqualTo(101L);
        assertThat(d1.agentId()).isEqualTo(21L);
        assertThat(d1.agentName()).isEqualTo("Code Assistant");
        assertThat(d1.surface()).isEqualTo("skill");
        assertThat(d1.patternId()).isEqualTo(11L);
        assertThat(d1.patternSignature()).isEqualTo("outcome=fail|surface=skill|tool=Bash|agent=21");
        assertThat(d1.currentStage()).isEqualTo("proposal_pending");
        assertThat(d1.errorLabel()).isNull();
        assertThat(d1.candidateSkillDraftUuid()).isEqualTo("draft-uuid-a");
        assertThat(d1.abRunId()).isNull();
        assertThat(d1.lastUpdatedAt()).isEqualTo(Instant.parse("2026-05-20T11:00:00Z"));
        FlywheelRunDto d2 = dtos.get(1);
        assertThat(d2.abRunId()).isEqualTo(999L);
        assertThat(d2.candidateSkillDraftUuid()).isNull();
    }

    @Test
    @DisplayName("hideTerminal=false → routes to *AllStages finder, terminal finder not called")
    void listRecentRuns_hideTerminalFalse_usesAllStagesFinder() {
        // r2 W1 fix: split into 4 finders. hideTerminal=false must route to the
        // AllStages variant (no NOT IN clause) so Hibernate 6 doesn't see a
        // null collection parameter.
        when(optimizationEventRepository.findRecentRunsForFlywheelAllStages(
                isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        List<FlywheelRunDto> dtos = service.listRecentRuns(null, null, 20, false);

        assertThat(dtos).isEmpty();
        verify(optimizationEventRepository).findRecentRunsForFlywheelAllStages(
                isNull(), any(Pageable.class));
        verify(optimizationEventRepository, never()).findRecentRunsForFlywheel(
                any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("hideTerminal=false + agentType → routes to byAgentIdsAllStages finder")
    void listRecentRuns_hideTerminalFalseWithAgentType_usesByAgentIdsAllStages() {
        when(agentRepository.findByAgentType("user")).thenReturn(List.of(
                agent(21L, "A", "user")));
        when(optimizationEventRepository.findRecentRunsByAgentIdsAllStages(
                isNull(), any(Collection.class), any(Pageable.class)))
                .thenReturn(List.of());

        service.listRecentRuns("user", null, 20, false);

        verify(optimizationEventRepository).findRecentRunsByAgentIdsAllStages(
                isNull(), any(Collection.class), any(Pageable.class));
        verify(optimizationEventRepository, never()).findRecentRunsByAgentIds(
                any(), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("agentType non-blank → resolves agentIds then calls agentIds-scoped finder")
    void listRecentRuns_withAgentType_usesAgentIdsFinder() {
        when(agentRepository.findByAgentType("user")).thenReturn(List.of(
                agent(21L, "A", "user"),
                agent(22L, "B", "user")));
        when(optimizationEventRepository.findRecentRunsByAgentIds(
                isNull(), any(Collection.class), any(Collection.class), any(Pageable.class)))
                .thenReturn(List.of());

        service.listRecentRuns("user", null, 20, true);

        verify(agentRepository).findByAgentType("user");
        ArgumentCaptor<Collection<Long>> idsCap = ArgumentCaptor.forClass(Collection.class);
        verify(optimizationEventRepository).findRecentRunsByAgentIds(
                isNull(), idsCap.capture(), eq(FlywheelRunsService.TERMINAL_HAPPY_STAGES), any(Pageable.class));
        assertThat(idsCap.getValue()).containsExactlyInAnyOrder(21L, 22L);
        verify(optimizationEventRepository, never()).findRecentRunsForFlywheel(
                anyString(), any(Collection.class), any(Pageable.class));
    }

    @Test
    @DisplayName("agentType resolves to empty agent set → early return [] without OptEvent query")
    void listRecentRuns_emptyAgentIds_earlyReturnsEmpty() {
        when(agentRepository.findByAgentType("system")).thenReturn(List.of());

        List<FlywheelRunDto> dtos = service.listRecentRuns("system", null, 20, true);

        assertThat(dtos).isEmpty();
        verify(optimizationEventRepository, never())
                .findRecentRunsByAgentIds(any(), any(), any(), any(Pageable.class));
        verify(optimizationEventRepository, never())
                .findRecentRunsByAgentIdsAllStages(any(), any(), any(Pageable.class));
        verify(optimizationEventRepository, never())
                .findRecentRunsForFlywheel(any(), any(), any(Pageable.class));
        verify(optimizationEventRepository, never())
                .findRecentRunsForFlywheelAllStages(any(), any(Pageable.class));
    }

    @Test
    @DisplayName("error label maps stage in {proposal_rejected, candidate_failed, ab_failed}")
    void errorLabelFor_failedStages_mapped() {
        assertThat(FlywheelRunsService.errorLabelFor(OptimizationEventEntity.STAGE_PROPOSAL_REJECTED))
                .isEqualTo("Proposal rejected");
        assertThat(FlywheelRunsService.errorLabelFor(OptimizationEventEntity.STAGE_CANDIDATE_FAILED))
                .isEqualTo("Candidate generation failed");
        assertThat(FlywheelRunsService.errorLabelFor(OptimizationEventEntity.STAGE_AB_FAILED))
                .isEqualTo("A/B test failed");
        assertThat(FlywheelRunsService.errorLabelFor(OptimizationEventEntity.STAGE_PROPOSAL_PENDING))
                .isNull();
        assertThat(FlywheelRunsService.errorLabelFor(OptimizationEventEntity.STAGE_PROMOTED))
                .isNull();
        assertThat(FlywheelRunsService.errorLabelFor(null)).isNull();
    }

    @Test
    @DisplayName("empty result → no dependent queries fired (defensive N+1 guard)")
    void listRecentRuns_emptyEvents_noDependentQueries() {
        when(optimizationEventRepository.findRecentRunsForFlywheel(
                isNull(), eq(FlywheelRunsService.TERMINAL_HAPPY_STAGES), any(Pageable.class)))
                .thenReturn(List.of());

        List<FlywheelRunDto> dtos = service.listRecentRuns(null, null, 20, true);

        assertThat(dtos).isEmpty();
        verify(sessionPatternRepository, never()).findAllById(any());
        verify(agentRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("signature snippet truncates at SIGNATURE_SNIPPET_MAX with ellipsis")
    void snippet_longSignature_truncatedWithEllipsis() {
        String longSig = "x".repeat(FlywheelRunsService.SIGNATURE_SNIPPET_MAX + 50);
        String out = FlywheelRunsService.snippet(longSig, FlywheelRunsService.SIGNATURE_SNIPPET_MAX);
        assertThat(out).hasSize(FlywheelRunsService.SIGNATURE_SNIPPET_MAX + 1);   // +1 for ellipsis
        assertThat(out).endsWith("…");
        assertThat(out).startsWith("x".repeat(FlywheelRunsService.SIGNATURE_SNIPPET_MAX));
    }

    @Test
    @DisplayName("signature snippet stays UTF-16 surrogate-safe at cut boundary")
    void snippet_surrogatePairAtBoundary_backsOffByOne() {
        // Build a string where the SIGNATURE_SNIPPET_MAX-th char is a high
        // surrogate (start of an emoji codepoint). Naive substring would split
        // the pair and emit a broken codepoint.
        String prefix = "x".repeat(FlywheelRunsService.SIGNATURE_SNIPPET_MAX - 1);
        String emoji = "🚀";  // 🚀 — 2 UTF-16 chars
        String input = prefix + emoji + "tail";
        String out = FlywheelRunsService.snippet(input, FlywheelRunsService.SIGNATURE_SNIPPET_MAX);
        // The high surrogate at index MAX-1 should be backed off, so the cut
        // happens at MAX-1 → length is MAX-1 + 1 (ellipsis) = MAX.
        assertThat(out).hasSize(FlywheelRunsService.SIGNATURE_SNIPPET_MAX);
        assertThat(out).endsWith("…");
        // No orphan high surrogate at the end of the prefix.
        char last = out.charAt(out.length() - 2);   // before ellipsis
        assertThat(Character.isHighSurrogate(last)).isFalse();
    }

    @Test
    @DisplayName("signature snippet returns null when input is null (e.g. orphan event)")
    void snippet_nullInput_returnsNull() {
        assertThat(FlywheelRunsService.snippet(null, 80)).isNull();
    }

    // ───────────────────────── helpers ──────────────────────────

    private static OptimizationEventEntity event(Long id, Long patternId, Long agentId,
                                                 String surface, String stage,
                                                 String draftUuid, Long abRunId,
                                                 Instant created, Instant updated) {
        OptimizationEventEntity e = new OptimizationEventEntity();
        e.setId(id);
        e.setPatternId(patternId);
        e.setAgentId(agentId);
        e.setSurfaceType(surface);
        e.setStage(stage);
        e.setCandidateSkillDraftUuid(draftUuid);
        e.setAbRunId(abRunId);
        e.setCreatedAt(created);
        e.setUpdatedAt(updated);
        return e;
    }

    private static SessionPatternEntity pattern(Long id, String signature) {
        SessionPatternEntity p = new SessionPatternEntity();
        p.setId(id);
        p.setSignature(signature);
        return p;
    }

    private static AgentEntity agent(Long id, String name, String agentType) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        a.setAgentType(agentType);
        return a;
    }
}
