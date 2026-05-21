package com.skillforge.server.sessionannotation;

import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MULTI-DIM-ATTRIBUTION 2026-05-21: regression tests for the
 * {@code annotateOne} early-bail change. When a production session completes
 * with {@code message_count=0} AND {@code runtime_status='error'} AND zero
 * production traces, we now write a synthetic {@code agent_error=true} signal
 * so the LLM annotator can later classify it as
 * {@code outcome=infrastructure_failure}. The prior implementation silently
 * returned 0 (no annotation), starving the curator of any visibility into
 * platform-level failures.
 *
 * <p>Edge cases: idle sessions, sessions mid-creation, and sessions with
 * messages but no traces all stay unchanged — they don't qualify for the
 * synthetic write.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionAnnotationSignalService — infrastructure_failure early-bail")
class SessionAnnotationSignalServiceInfraFailureTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private LlmTraceRepository llmTraceRepository;
    @Mock private LlmSpanRepository llmSpanRepository;
    @Mock private SessionAnnotationRepository sessionAnnotationRepository;

    private SessionAnnotationSignalService service;

    @BeforeEach
    void setUp() {
        service = new SessionAnnotationSignalService(
                sessionRepository, llmTraceRepository, llmSpanRepository, sessionAnnotationRepository);
    }

    @Test
    @DisplayName("0-message + runtime_status='error' + empty traces → writes agent_error=true signal")
    void zeroMessageErrorEmptyTraces_writesSyntheticAgentError() {
        SessionEntity sess = infraFailureSession("sess-infra-1");

        when(sessionRepository.findCompletedByOriginSince(eq("production"), any(Instant.class), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(sess));
        when(llmTraceRepository.findBySessionIdAndOriginOrderByStartedAtDesc("sess-infra-1", "production"))
                .thenReturn(List.of());
        when(sessionAnnotationRepository.upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any()))
                .thenReturn(7777L);

        int written = service.detectAndPersist(Duration.ofHours(1));

        assertThat(written).isEqualTo(1);
        ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sourceCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> confCap = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(sessionAnnotationRepository, times(1)).upsertSkipDuplicate(
                eq("sess-infra-1"),
                typeCap.capture(),
                valueCap.capture(),
                sourceCap.capture(),
                confCap.capture(),
                reasonCap.capture());
        assertThat(typeCap.getValue()).isEqualTo("agent_error");
        assertThat(valueCap.getValue()).isEqualTo("true");
        assertThat(sourceCap.getValue()).isEqualTo("signal");
        assertThat(confCap.getValue().toPlainString()).isEqualTo("1.00");
        assertThat(reasonCap.getValue()).isNull();
        // Span lookup never happens because traces are empty (skipped before the
        // sum + reason-detection flow).
        verify(llmSpanRepository, never()).findByTraceIdInOrderByStartedAtAsc(any());
    }

    @Test
    @DisplayName("rerun is idempotent — UNIQUE conflict (repository returns null) counts as 0 writes")
    void zeroMessageErrorEmptyTraces_idempotentOnRerun() {
        SessionEntity sess = infraFailureSession("sess-infra-2");

        when(sessionRepository.findCompletedByOriginSince(eq("production"), any(Instant.class), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(sess));
        when(llmTraceRepository.findBySessionIdAndOriginOrderByStartedAtDesc("sess-infra-2", "production"))
                .thenReturn(List.of());
        // ON CONFLICT DO NOTHING returned null = row already present from prior cron run.
        when(sessionAnnotationRepository.upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any()))
                .thenReturn(null);

        int written = service.detectAndPersist(Duration.ofHours(1));

        assertThat(written).isEqualTo(0);
        // Still called once — the call itself is idempotent at the DB layer.
        verify(sessionAnnotationRepository, times(1)).upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any());
    }

    @Test
    @DisplayName("0-message + runtime_status='idle' (not error) + empty traces → no annotation written")
    void zeroMessageIdleEmptyTraces_doesNotTrigger() {
        SessionEntity sess = baseSession("sess-idle-1");
        sess.setMessageCount(0);
        sess.setRuntimeStatus("idle");  // critical: NOT 'error'

        when(sessionRepository.findCompletedByOriginSince(eq("production"), any(Instant.class), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(sess));
        when(llmTraceRepository.findBySessionIdAndOriginOrderByStartedAtDesc("sess-idle-1", "production"))
                .thenReturn(List.of());

        int written = service.detectAndPersist(Duration.ofHours(1));

        assertThat(written).isEqualTo(0);
        verify(sessionAnnotationRepository, never()).upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any());
    }

    @Test
    @DisplayName("non-zero message + runtime_status='error' + empty traces → no synthetic write (traces still ingesting)")
    void nonZeroMessageErrorEmptyTraces_doesNotTrigger() {
        SessionEntity sess = baseSession("sess-mid-1");
        sess.setMessageCount(3);  // critical: had user/assistant exchange before crash
        sess.setRuntimeStatus("error");

        when(sessionRepository.findCompletedByOriginSince(eq("production"), any(Instant.class), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(sess));
        when(llmTraceRepository.findBySessionIdAndOriginOrderByStartedAtDesc("sess-mid-1", "production"))
                .thenReturn(List.of());

        int written = service.detectAndPersist(Duration.ofHours(1));

        // The non-zero message gating ensures we don't false-positive on
        // sessions that crashed mid-flight (where traces may still be ingesting)
        // — only the genuine 0-message+error case writes the synthetic signal.
        assertThat(written).isEqualTo(0);
        verify(sessionAnnotationRepository, never()).upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any());
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────

    private static SessionEntity infraFailureSession(String id) {
        SessionEntity s = baseSession(id);
        s.setMessageCount(0);
        s.setRuntimeStatus("error");
        return s;
    }

    private static SessionEntity baseSession(String id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setAgentId(9L);
        s.setOrigin("production");
        s.setStatus("active");
        s.setCompletedAt(Instant.parse("2026-05-20T08:00:00Z"));
        return s;
    }

    @SuppressWarnings("unused")  // helper kept for symmetry with sibling tests if needed
    private static LlmTraceEntity dummyTrace() {
        LlmTraceEntity t = new LlmTraceEntity();
        t.setTraceId("trace-x");
        t.setRootTraceId("trace-x");
        return t;
    }
}
