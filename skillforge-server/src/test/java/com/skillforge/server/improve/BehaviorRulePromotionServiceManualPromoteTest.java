package com.skillforge.server.improve;

import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.improve.BehaviorRulePromotionService.PromoteResult;
import com.skillforge.server.repository.BehaviorRuleAbRunRepository;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BehaviorRulePromotionService.promoteManual")
class BehaviorRulePromotionServiceManualPromoteTest {

    @Mock private BehaviorRuleVersionRepository versionRepository;
    @Mock private BehaviorRuleAbRunRepository abRunRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private BehaviorRulePromotionService service;

    @BeforeEach
    void setUp() {
        service = new BehaviorRulePromotionService(
                versionRepository, abRunRepository, eventPublisher);
    }

    @DisplayName("dual-criteria satisfied → promotes + marks run as promoted")
    @Test
    void dual_criteria_satisfied_promotes() {
        BehaviorRuleVersionEntity v = candidate("v1");
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        when(versionRepository.findByAgentIdAndStatus("100", BehaviorRuleVersionEntity.STATUS_ACTIVE))
                .thenReturn(Optional.empty());
        BehaviorRuleAbRunEntity run = completedRun("ab-1", 15.0, -1.0);
        when(abRunRepository.findFirstByCandidateVersionIdAndStatusOrderByCompletedAtDesc(
                "v1", BehaviorRuleAbRunEntity.STATUS_COMPLETED))
                .thenReturn(Optional.of(run));

        PromoteResult result = service.promoteManual("v1", 42L);

        assertThat(result.status()).isEqualTo("promoted");
        assertThat(v.getStatus()).isEqualTo(BehaviorRuleVersionEntity.STATUS_ACTIVE);
        ArgumentCaptor<BehaviorRuleAbRunEntity> runCaptor =
                ArgumentCaptor.forClass(BehaviorRuleAbRunEntity.class);
        verify(abRunRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().isPromoted()).isTrue();
        assertThat(runCaptor.getValue().getTriggeredByUserId()).isEqualTo(42L);
    }

    @DisplayName("target_delta below threshold → throws (not promoted)")
    @Test
    void target_delta_too_low_blocks() {
        BehaviorRuleVersionEntity v = candidate("v1");
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        BehaviorRuleAbRunEntity run = completedRun("ab-1", 5.0, 0.0);  // target=5pp < 10pp
        when(abRunRepository.findFirstByCandidateVersionIdAndStatusOrderByCompletedAtDesc(
                anyString(), anyString())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.promoteManual("v1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dual-criteria not satisfied");
        verify(versionRepository, never()).save(v);
    }

    @DisplayName("regression_delta below floor → throws (not promoted)")
    @Test
    void regression_delta_too_negative_blocks() {
        BehaviorRuleVersionEntity v = candidate("v1");
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        BehaviorRuleAbRunEntity run = completedRun("ab-1", 20.0, -5.0); // regression=-5pp < -3pp
        when(abRunRepository.findFirstByCandidateVersionIdAndStatusOrderByCompletedAtDesc(
                anyString(), anyString())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.promoteManual("v1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dual-criteria not satisfied");
    }

    @DisplayName("fallback mode (target_delta null, regression OK) → promotes")
    @Test
    void fallback_mode_promotes_on_regression_alone() {
        BehaviorRuleVersionEntity v = candidate("v1");
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        when(versionRepository.findByAgentIdAndStatus("100", BehaviorRuleVersionEntity.STATUS_ACTIVE))
                .thenReturn(Optional.empty());
        // target=null (no targeting set on candidate), regression=0pp (acceptable)
        BehaviorRuleAbRunEntity run = completedRun("ab-1", null, 0.0);
        when(abRunRepository.findFirstByCandidateVersionIdAndStatusOrderByCompletedAtDesc(
                anyString(), anyString())).thenReturn(Optional.of(run));

        PromoteResult result = service.promoteManual("v1", null);

        assertThat(result.status()).isEqualTo("promoted");
    }

    @DisplayName("INV-6 idempotency: already-active version → noop, no exception")
    @Test
    void already_active_is_noop() {
        BehaviorRuleVersionEntity v = candidate("v1");
        v.setStatus(BehaviorRuleVersionEntity.STATUS_ACTIVE);
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));

        PromoteResult result = service.promoteManual("v1", null);

        assertThat(result.status()).isEqualTo("noop");
        assertThat(result.reason()).isEqualTo("already active");
        verify(abRunRepository, never())
                .findFirstByCandidateVersionIdAndStatusOrderByCompletedAtDesc(anyString(), anyString());
    }

    @DisplayName("no completed A/B run → throws")
    @Test
    void no_completed_run_throws() {
        BehaviorRuleVersionEntity v = candidate("v1");
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        when(abRunRepository.findFirstByCandidateVersionIdAndStatusOrderByCompletedAtDesc(
                anyString(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.promoteManual("v1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No completed A/B run");
    }

    @DisplayName("non-candidate (retired) version → throws")
    @Test
    void retired_version_throws() {
        BehaviorRuleVersionEntity v = candidate("v1");
        v.setStatus(BehaviorRuleVersionEntity.STATUS_RETIRED);
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.promoteManual("v1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-candidate");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private BehaviorRuleVersionEntity candidate(String id) {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId(id);
        v.setAgentId("100");
        v.setVersionNumber(1);
        v.setStatus(BehaviorRuleVersionEntity.STATUS_CANDIDATE);
        v.setRulesJson("[]");
        v.setSource(BehaviorRuleVersionEntity.SOURCE_MANUAL);
        return v;
    }

    private BehaviorRuleAbRunEntity completedRun(String id, Double targetDelta, Double regressionDelta) {
        BehaviorRuleAbRunEntity r = new BehaviorRuleAbRunEntity();
        r.setId(id);
        r.setAgentId("100");
        r.setCandidateVersionId("v1");
        r.setBaselineVersionId("");
        r.setStatus(BehaviorRuleAbRunEntity.STATUS_COMPLETED);
        r.setTargetDeltaPp(targetDelta);
        r.setRegressionDeltaPp(regressionDelta);
        return r;
    }
}
