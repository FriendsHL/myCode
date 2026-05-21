package com.skillforge.server.attribution;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.sessionannotation.SessionAnnotationLlmService.SessionAnnotationConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MULTI-DIM-ATTRIBUTION 2026-05-21: regression coverage for the
 * {@link AttributionDispatcherService} Filter 1 infrastructure-failure bypass.
 *
 * <p>Pre-fix Filter 1 (surface allowlist = \{skill, prompt\}) would skip every
 * infra-failure pattern because their {@code suspect_surface='other'} → no
 * dispatch ever happened → operator never saw the rejection landing on
 * the timeline. The Filter 1 fix bypasses the allowlist when
 * {@code outcome='infrastructure_failure'}, allowing the curator to fast-reject
 * via {@code WriteOptimizationEvent(proposal_rejected)} with a 24h cooldown.
 *
 * <p>Also pins that the regular surface-skip behavior (other outcomes,
 * surface='other') stays unchanged — no regression.
 */
@ExtendWith(MockitoExtension.class)
class AttributionDispatcherMultiDimFilterTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-21T10:00:00Z");

    @Mock private SessionPatternRepository patternRepository;
    @Mock private OptimizationEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private SessionService sessionService;
    @Mock private ChatService chatService;
    @Mock private AttributionEventBroadcaster broadcaster;

    private AttributionDispatcherService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new AttributionDispatcherService(
                patternRepository, eventRepository, agentRepository,
                sessionService, chatService, fixed, broadcaster);

        AgentEntity curator = new AgentEntity();
        curator.setId(777L);
        curator.setName(AttributionDispatcherService.CURATOR_AGENT_NAME);
        Mockito.lenient().when(agentRepository.findFirstByName(
                AttributionDispatcherService.CURATOR_AGENT_NAME)).thenReturn(Optional.of(curator));

        Mockito.lenient().when(eventRepository.existsByPatternIdAndStageIn(
                anyLong(), Mockito.<java.util.Collection<String>>any())).thenReturn(false);
        Mockito.lenient().when(eventRepository.findByPatternIdAndCooldownExpiresAtAfter(anyLong(), any()))
                .thenReturn(List.of());

        SessionEntity sess = new SessionEntity();
        sess.setId("sess-curator-stub");
        Mockito.lenient().when(sessionService.createSession(anyLong(), anyLong())).thenReturn(sess);

        Mockito.lenient().when(eventRepository.save(any(OptimizationEventEntity.class)))
                .thenAnswer(inv -> {
                    OptimizationEventEntity arg = inv.getArgument(0);
                    if (arg.getId() == null) arg.setId(999L);
                    return arg;
                });
    }

    @Test
    @DisplayName("infrastructure_failure pattern with surface='other' bypasses Filter 1 and dispatches")
    void infraFailureSurfaceOther_bypassesFilterOne_andDispatches() {
        SessionPatternEntity p = pattern(101L,
                SessionAnnotationConstants.OUTCOME_INFRASTRUCTURE_FAILURE,
                SessionAnnotationConstants.SURFACE_OTHER, 2);
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(p));

        AttributionDispatcherService.DispatchResult result = service.dispatchPendingPatterns(5);

        assertThat(result.scanned()).isEqualTo(1);
        // The critical assertion: infra-failure does NOT increment skippedSurface.
        assertThat(result.skippedSurface()).isZero();
        assertThat(result.dispatched()).isEqualTo(1);
        // composeDispatchPrompt embeds outcome so the curator can fast-reject at STEP 1.
        verify(chatService).chatAsync(anyString(),
                org.mockito.ArgumentMatchers.contains("outcome=infrastructure_failure"),
                eq(AttributionDispatcherService.SYSTEM_USER_ID));
    }

    @Test
    @DisplayName("regression: outcome=failure with surface='other' still gets skippedSurface (no regression)")
    void regularFailureSurfaceOther_stillSkipped() {
        SessionPatternEntity p = pattern(102L, "failure",
                SessionAnnotationConstants.SURFACE_OTHER, 5);
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(p));

        AttributionDispatcherService.DispatchResult result = service.dispatchPendingPatterns(5);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.skippedSurface()).isEqualTo(1);
        assertThat(result.dispatched()).isZero();
        verify(chatService, Mockito.never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("cost_high with surface='skill' uses the standard path, not infra bypass — still dispatches normally")
    void costHighSurfaceSkill_dispatchesViaStandardPath() {
        SessionPatternEntity p = pattern(103L,
                SessionAnnotationConstants.OUTCOME_COST_HIGH,
                OptimizationEventEntity.SURFACE_SKILL, 3);
        when(patternRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(p));

        ArgumentCaptor<String> promptCap = ArgumentCaptor.forClass(String.class);

        AttributionDispatcherService.DispatchResult result = service.dispatchPendingPatterns(5);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.skippedSurface()).isZero();
        assertThat(result.dispatched()).isEqualTo(1);
        verify(chatService).chatAsync(anyString(), promptCap.capture(), anyLong());
        // composeDispatchPrompt embeds outcome so curator STEP 3 picks the cost_high mode.
        assertThat(promptCap.getValue()).contains("outcome=cost_high");
        assertThat(promptCap.getValue()).contains("patternId=103");
    }

    // ---------- helpers ----------

    private SessionPatternEntity pattern(Long id, String outcome, String surface, int memberCount) {
        SessionPatternEntity p = new SessionPatternEntity();
        p.setId(id);
        p.setSignature("sig-" + id);
        p.setOutcome(outcome);
        p.setSuspectSurface(surface);
        p.setMemberCount(memberCount);
        p.setAgentId(42L);
        p.setFirstSeenAt(FIXED_NOW.minusSeconds(7200));
        p.setLastSeenAt(FIXED_NOW.minusSeconds(60));
        return p;
    }
}
