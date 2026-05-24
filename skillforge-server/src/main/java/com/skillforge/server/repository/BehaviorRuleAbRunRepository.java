package com.skillforge.server.repository;

import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BehaviorRuleAbRunRepository extends JpaRepository<BehaviorRuleAbRunEntity, String> {

    List<BehaviorRuleAbRunEntity> findByAgentIdAndStatus(String agentId, String status);

    List<BehaviorRuleAbRunEntity> findByCandidateVersionIdOrderByStartedAtDesc(String candidateVersionId);

    /**
     * BEHAVIOR-RULE-AB-EVAL V1: retry-guard lookup (prd.md INV-6). Used by
     * {@code BehaviorRuleAbEvalService.startAbForVersion} to detect an
     * already-in-flight A/B run for the same candidate version and mark it
     * SUPERSEDED before starting a fresh run.
     */
    Optional<BehaviorRuleAbRunEntity> findFirstByCandidateVersionIdAndStatusIn(
            String candidateVersionId, Collection<String> statuses);

    /**
     * BEHAVIOR-RULE-AB-EVAL V1: latest COMPLETED run lookup used by
     * {@code BehaviorRulePromotionService.promoteManual} to check dual-criteria
     * before atomic promote.
     */
    Optional<BehaviorRuleAbRunEntity> findFirstByCandidateVersionIdAndStatusOrderByCompletedAtDesc(
            String candidateVersionId, String status);

    /**
     * BEHAVIOR-RULE-AB-EVAL V1 r2-BE-4: most-recent run for a candidate version
     * (any status). Used by {@code GET /latest-ab-run} so the controller stays
     * thin — sorting belongs at the data-access layer, not in a Comparator
     * inside the controller method.
     *
     * <p>Returns {@link Optional#empty()} when the version has no A/B runs yet
     * (initial state); endpoint maps that to 200 + null body per C4 contract.
     */
    Optional<BehaviorRuleAbRunEntity> findFirstByCandidateVersionIdOrderByStartedAtDesc(
            String candidateVersionId);
}
