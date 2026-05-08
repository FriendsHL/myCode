package com.skillforge.server.improve;

/**
 * Thrown when {@code SkillDraftService.approveDraft} detects an exact-name collision
 * with an existing enabled skill row for the same owner, OR when a save attempt
 * hits the V64 partial unique index {@code uq_t_skill_owner_name_enabled} on
 * {@code (COALESCE(owner_id, -1), name) WHERE enabled=true}.
 *
 * <p>Two construction paths:
 * <ul>
 *   <li>SKILL-DASHBOARD-POLISH-V2 §H — pre-flight exact-name check before the
 *       artifact write happens, populates {@code existingSkillId} so the FE can
 *       offer "Update existing" → {@code POST /skill-drafts/{id}/merge}.</li>
 *   <li>Legacy fallback — DataIntegrityViolationException catch in the save path,
 *       in which case {@code existingSkillId} is null because PostgreSQL aborts
 *       the tx on unique violation and a follow-up SELECT in the same tx would
 *       itself fail.</li>
 * </ul>
 *
 * <p>The controller maps this to HTTP 409 Conflict with structured body
 * ({@code code: "NAME_CONFLICT"} + {@code existingSkillId} when present) so the
 * FE can surface the duplicate to the operator and drive the merge flow.
 */
public class SkillNameConflictException extends RuntimeException {

    private final String existingSkillName;
    private final Long existingSkillId;

    /** SKILL-DASHBOARD-POLISH-V2 §H — pre-flight check populates existingSkillId. */
    public SkillNameConflictException(String message, String existingSkillName, Long existingSkillId) {
        super(message);
        this.existingSkillName = existingSkillName;
        this.existingSkillId = existingSkillId;
    }

    /** Legacy DB-violation fallback — id unknown because tx is aborted. */
    public SkillNameConflictException(String message, String existingSkillName) {
        this(message, existingSkillName, null);
    }

    public String getExistingSkillName() {
        return existingSkillName;
    }

    public Long getExistingSkillId() {
        return existingSkillId;
    }
}
