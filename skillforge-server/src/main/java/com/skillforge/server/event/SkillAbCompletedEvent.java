package com.skillforge.server.event;

/**
 * SKILL-EVOLVE-LOOP Phase 5 (INV-10): published when a skill A/B run finishes
 * (whether {@code promoted} or rejected). Decouples the evolve coordinator
 * ({@link com.skillforge.server.improve.SkillAbEvalService}) from the
 * post-completion side effects (WS toast, future hooks) so cron-driven
 * self-improve and dashboard-driven evolve share the same notification
 * pathway without polling.
 *
 * <p>Listener:
 * {@link com.skillforge.server.improve.SkillSelfImproveLoop#onAbCompleted}
 * — registered with {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Transactional(REQUIRES_NEW)} (Spring 6.1+; P11 lesson — using
 * {@code REQUIRED} on AFTER_COMMIT silently no-ops because the original TX
 * is already committed and the suspended TX is unavailable).
 *
 * <p>Immutable — fields set once at construction. Score values come from the
 * {@link com.skillforge.server.entity.SkillAbRunEntity} pass-rate snapshot
 * (NOT the {@code t_skill_eval_history} composite). {@code oldVersion} /
 * {@code newVersion} mirror parent vs candidate {@code SkillEntity.semver}.
 */
public final class SkillAbCompletedEvent {

    private final Long skillId;
    private final String evolutionAbRunId;
    private final boolean promoted;
    private final double baselineScore;
    private final double candidateScore;
    private final String oldVersion;
    private final String newVersion;

    public SkillAbCompletedEvent(Long skillId,
                                 String evolutionAbRunId,
                                 boolean promoted,
                                 double baselineScore,
                                 double candidateScore,
                                 String oldVersion,
                                 String newVersion) {
        this.skillId = skillId;
        this.evolutionAbRunId = evolutionAbRunId;
        this.promoted = promoted;
        this.baselineScore = baselineScore;
        this.candidateScore = candidateScore;
        this.oldVersion = oldVersion;
        this.newVersion = newVersion;
    }

    public Long getSkillId() { return skillId; }

    public String getEvolutionAbRunId() { return evolutionAbRunId; }

    public boolean isPromoted() { return promoted; }

    public double getBaselineScore() { return baselineScore; }

    public double getCandidateScore() { return candidateScore; }

    public String getOldVersion() { return oldVersion; }

    public String getNewVersion() { return newVersion; }
}
