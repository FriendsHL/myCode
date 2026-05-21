package com.skillforge.server.sessionannotation;

import com.skillforge.server.repository.SessionAnnotationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MULTI-DIM-ATTRIBUTION 2026-05-21: regression tests for the two new outcome
 * values ({@code infrastructure_failure}, {@code cost_high}). Ensures both
 * pass the 3 admission gates (null / OUTCOME_SUCCESS exclude / confidence
 * range) and persist to {@code t_session_annotation} like any other outcome.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionAnnotationLlmService — multi-dim outcome support")
class SessionAnnotationLlmServiceMultiDimOutcomeTest {

    @Mock private SessionAnnotationRepository sessionAnnotationRepository;

    private SessionAnnotationLlmService service;

    @BeforeEach
    void setUp() {
        service = new SessionAnnotationLlmService(sessionAnnotationRepository);
    }

    @Test
    @DisplayName("outcome=infrastructure_failure persists with high confidence + 'other' surface")
    void annotateSession_infrastructureFailure_persists() {
        AtomicLong idGen = new AtomicLong(300L);
        when(sessionAnnotationRepository.upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any()))
                .thenAnswer(inv -> idGen.getAndIncrement());

        List<Long> ids = service.annotateSession(
                "sess-infra",
                SessionAnnotationLlmService.SessionAnnotationConstants.OUTCOME_INFRASTRUCTURE_FAILURE,
                SessionAnnotationLlmService.SessionAnnotationConstants.SURFACE_OTHER,
                new BigDecimal("0.90"),
                "0-trace + 0-message + runtime_status=error → platform / network crash",
                null);

        assertThat(ids).hasSize(2).containsExactly(300L, 301L);
        ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
        verify(sessionAnnotationRepository, times(2)).upsertSkipDuplicate(
                eq("sess-infra"),
                typeCap.capture(),
                valueCap.capture(),
                eq("llm"),
                eq(new BigDecimal("0.90")),
                anyString());
        assertThat(typeCap.getAllValues()).containsExactly("outcome", "suspect_surface");
        assertThat(valueCap.getAllValues()).containsExactly("infrastructure_failure", "other");
    }

    @Test
    @DisplayName("outcome=cost_high persists with surface=skill + tool name as top_failing_tool")
    void annotateSession_costHigh_persists() {
        AtomicLong idGen = new AtomicLong(400L);
        when(sessionAnnotationRepository.upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any()))
                .thenAnswer(inv -> idGen.getAndIncrement());

        List<Long> ids = service.annotateSession(
                "sess-cost",
                SessionAnnotationLlmService.SessionAnnotationConstants.OUTCOME_COST_HIGH,
                SessionAnnotationLlmService.SessionAnnotationConstants.SURFACE_SKILL,
                new BigDecimal("0.70"),
                "high_token signal + no error / failure / span_error — agent over-consumed tokens",
                "GetTrace");

        // 3 rows: outcome + suspect_surface + top_failing_tool
        assertThat(ids).hasSize(3).containsExactly(400L, 401L, 402L);
        verify(sessionAnnotationRepository).upsertSkipDuplicate(
                eq("sess-cost"),
                eq("outcome"),
                eq("cost_high"),
                eq("llm"),
                eq(new BigDecimal("0.70")),
                anyString());
        verify(sessionAnnotationRepository).upsertSkipDuplicate(
                eq("sess-cost"),
                eq("top_failing_tool"),
                eq("GetTrace"),
                eq("llm"),
                eq(new BigDecimal("0.70")),
                anyString());
    }
}
