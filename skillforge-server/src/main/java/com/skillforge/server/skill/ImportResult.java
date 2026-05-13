package com.skillforge.server.skill;

import com.skillforge.server.security.skill.SkillScanFinding;

import java.util.List;

/**
 * SKILL-IMPORT — return value of {@link SkillImportService#importSkill}.
 *
 * <p>Serialised as JSON and returned to the agent inside the {@code ImportSkill}
 * tool's tool_result block.
 *
 * @param id              {@code t_skill.id} of the upserted row
 * @param name            registered skill name (slug)
 * @param skillPath       absolute on-disk runtime path the artifact was copied to
 * @param source          {@link SkillSource#wireName()} of the originating marketplace
 * @param conflictResolved {@code true} when an existing row was overwritten;
 *                        {@code false} when a new row was created
 * @param scanWarnings     low/medium scan findings that did not block import
 */
public record ImportResult(
        Long id,
        String name,
        String skillPath,
        String source,
        boolean conflictResolved,
        List<SkillScanFinding> scanWarnings) {

    public ImportResult(Long id, String name, String skillPath, String source, boolean conflictResolved) {
        this(id, name, skillPath, source, conflictResolved, List.of());
    }

    public ImportResult {
        scanWarnings = scanWarnings == null ? List.of() : List.copyOf(scanWarnings);
    }
}
