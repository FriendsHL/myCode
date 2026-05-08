-- SKILL-EVOLVE-LOOP follow-up: relax uq_t_skill_owner_name to a partial
-- unique index that only enforces uniqueness for enabled rows. This unblocks
-- the evolve / fork / promote chain (SkillService.forkSkill creates a
-- disabled candidate row sharing the parent's name; SkillAbEvalService
-- promotes by enabling the candidate after disabling the parent, so at most
-- one (owner_id, name) row is enabled at any time).
--
-- The original V31 unconditional unique constraint silently broke the entire
-- evolve flow the moment any caller actually tried to fork a real skill.
--
-- ON CONFLICT inference must now include the WHERE clause to bind to this
-- partial index. SkillRepository.insertImportedSkillIgnoreConflict and
-- SkillConflictResolver.upsertSystemSkill have been updated accordingly.

DROP INDEX IF EXISTS uq_t_skill_owner_name;

CREATE UNIQUE INDEX uq_t_skill_owner_name_enabled
    ON t_skill (COALESCE(owner_id, -1), name)
    WHERE enabled = TRUE;
