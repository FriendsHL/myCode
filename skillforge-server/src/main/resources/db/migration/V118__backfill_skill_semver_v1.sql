-- Backfill semver='v1' for all active skills whose semver is null.
-- Root cause: SkillImportService never wrote semver on import; only fork path
-- (SkillService.cloneToFork) set it. UI fallback in SkillDrawer.tsx renders
-- 'v0.0.0' when semver is null, which misleads users.
--
-- Scope: all artifact_status='active' rows (both system and user skills).
-- System skills (typically id 1-16) also get v1 here so their UI version label
-- is consistent with user-imported skills — they previously also displayed
-- v0.0.0 in the drawer because semver was null. Transient AB / eval-only
-- rows (artifact_status != 'active', e.g. 'transient_ab' / 'eval_transient')
-- are excluded to keep their metrics clean.
UPDATE t_skill
SET semver = 'v1'
WHERE semver IS NULL
  AND artifact_status = 'active';
