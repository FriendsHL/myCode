package com.skillforge.server.memory.llmsynth;

import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LlmMemorySynthesisScheduler")
class LlmMemorySynthesisSchedulerTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private LlmMemorySynthesizer synthesizer;

    private MemoryProperties props;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
    }

    private LlmMemorySynthesisScheduler newScheduler(boolean enabled) {
        props.getLlmSynthesis().setScheduledEnabled(enabled);
        return new LlmMemorySynthesisScheduler(sessionRepository, synthesizer, props);
    }

    @Test
    @DisplayName("scheduled gate off: cron path short-circuits without query")
    void scheduledRun_gateOff_skipsAll() {
        LlmMemorySynthesisScheduler s = newScheduler(false);
        LlmMemorySynthesisScheduler.SchedulerSummary summary = s.runOnce(null);
        verify(sessionRepository, never()).findDistinctUserIdsWithRecentUserMessage(any());
        verify(synthesizer, never()).synthesize(anyLong());
        assertThat(summary.eligible()).isZero();
    }

    @Test
    @DisplayName("R2-1: 1-arg runOnce(userId) enforces the gate (backward-compat default)")
    void manualRun_singleArg_enforcesGate() {
        LlmMemorySynthesisScheduler s = newScheduler(false);

        LlmMemorySynthesisScheduler.SchedulerSummary summary = s.runOnce(42L);

        // Gate fires before the synthesizer is invoked.
        verify(synthesizer, never()).synthesize(anyLong());
        assertThat(summary.eligible()).isZero();
    }

    @Test
    @DisplayName("R2-1: admin path with bypassGate=true + userId runs even when disabled")
    void manualRun_explicitBypass_runsWhenDisabled() {
        LlmMemorySynthesisScheduler s = newScheduler(false);
        when(synthesizer.synthesize(42L))
                .thenReturn(SynthesisRunResult.success("r1", 1, 1, 0, 0, 0, 100, 50, 0.001));

        LlmMemorySynthesisScheduler.SchedulerSummary summary = s.runOnce(42L, true);

        verify(synthesizer).synthesize(42L);
        assertThat(summary.eligible()).isEqualTo(1);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.dedupProposals()).isEqualTo(1);
    }

    @Test
    @DisplayName("R2-1 fix: admin legacy fallback (userId=null + bypassGate=true) runs even when disabled")
    void runOnce_legacyAdminPath_bypassesGate_evenWhenDisabled() {
        LlmMemorySynthesisScheduler s = newScheduler(false);
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of(11L));
        when(synthesizer.synthesize(11L))
                .thenReturn(SynthesisRunResult.success("r1", 1, 1, 0, 0, 0, 100, 50, 0.001));

        LlmMemorySynthesisScheduler.SchedulerSummary summary = s.runOnce(null, true);

        // Bypass means we DON'T return SchedulerSummary.disabled() even though
        // scheduled-enabled=false. Active-user query runs, synthesizer is called.
        verify(synthesizer).synthesize(11L);
        assertThat(summary.eligible()).isEqualTo(1);
        assertThat(summary.succeeded()).isEqualTo(1);
    }

    @Test
    @DisplayName("per-user failure: WARN + continue to next user (INV-2)")
    void runOnce_perUserFailure_continues() {
        LlmMemorySynthesisScheduler s = newScheduler(true);
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of(11L, 22L));
        doThrow(new RuntimeException("LLM down")).when(synthesizer).synthesize(11L);
        when(synthesizer.synthesize(22L))
                .thenReturn(SynthesisRunResult.success("r2", 2, 0, 1, 0, 0, 200, 100, 0.0));

        LlmMemorySynthesisScheduler.SchedulerSummary summary = s.runOnce();

        assertThat(summary.eligible()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.reflectionProposals()).isEqualTo(1);
        verify(synthesizer, times(1)).synthesize(11L);
        verify(synthesizer, times(1)).synthesize(22L);
    }

    @Test
    @DisplayName("0 eligible users: summary is empty, no synthesize call")
    void runOnce_noUsers_empty() {
        LlmMemorySynthesisScheduler s = newScheduler(true);
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of());

        LlmMemorySynthesisScheduler.SchedulerSummary summary = s.runOnce();

        verify(synthesizer, never()).synthesize(anyLong());
        assertThat(summary.eligible()).isZero();
    }

    @Test
    @DisplayName("normal aggregation: counts roll up across users")
    void runOnce_aggregatesAcrossUsers() {
        LlmMemorySynthesisScheduler s = newScheduler(true);
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of(1L, 2L));
        when(synthesizer.synthesize(1L))
                .thenReturn(SynthesisRunResult.success("r1", 1, 2, 0, 1, 0, 100, 50, 0.001));
        when(synthesizer.synthesize(2L))
                .thenReturn(SynthesisRunResult.success("r2", 1, 0, 1, 0, 1, 200, 100, 0.002));

        LlmMemorySynthesisScheduler.SchedulerSummary summary = s.runOnce();

        assertThat(summary.eligible()).isEqualTo(2);
        assertThat(summary.dedupProposals()).isEqualTo(2);
        assertThat(summary.reflectionProposals()).isEqualTo(1);
        assertThat(summary.optimizeProposals()).isEqualTo(1);
        assertThat(summary.contradictionProposals()).isEqualTo(1);
        assertThat(summary.inputTokens()).isEqualTo(300);
        assertThat(summary.outputTokens()).isEqualTo(150);
        assertThat(summary.estimatedUsd()).isEqualTo(0.003, org.assertj.core.api.Assertions.within(0.000001));
    }
}
