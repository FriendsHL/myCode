package com.skillforge.core.skill;

import com.skillforge.core.model.SkillDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SKILL-DATA-HYGIENE B1 (2026-05-26) — strict-mode regression tests for
 * {@link SkillPackageLoader#loadFromDirectory(Path)}.
 *
 * <p>The loader supports two valid skill-package shapes:
 * <ul>
 *   <li>SKILL.md with YAML frontmatter (Claude Code standard)</li>
 *   <li>plain SKILL.md + separate {@code skill.yaml} (legacy)</li>
 * </ul>
 *
 * <p>The previous markdown-auto-extract fallback (parse {@code # Title}
 * heading + first paragraph) was removed because it silently produced
 * garbage name/description for malformed packages — both inputs let the
 * package slip into the catalog where it appeared "loaded" but couldn't
 * be triggered. Strict mode forces the package author to fix the file.
 */
class SkillPackageLoaderTest {

    private final SkillPackageLoader loader = new SkillPackageLoader();

    @Test
    @DisplayName("loadFromDirectory_frontmatter_parsesNameAndDescription")
    void loadFromDirectory_frontmatter_parsesNameAndDescription(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: example-skill\ndescription: An example.\n---\n\n# Body\nContent.\n",
                StandardCharsets.UTF_8);

        SkillDefinition def = loader.loadFromDirectory(dir);

        assertThat(def.getName()).isEqualTo("example-skill");
        assertThat(def.getDescription()).isEqualTo("An example.");
        assertThat(def.getPromptContent()).contains("# Body");
    }

    @Test
    @DisplayName("loadFromDirectory_skillYamlFallback_parsesNameAndDescription")
    void loadFromDirectory_skillYamlFallback_parsesNameAndDescription(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("SKILL.md"),
                "# Legacy Skill\n\nNo frontmatter — relies on skill.yaml.\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("skill.yaml"),
                "name: legacy-skill\ndescription: Legacy package.\n",
                StandardCharsets.UTF_8);

        SkillDefinition def = loader.loadFromDirectory(dir);

        assertThat(def.getName()).isEqualTo("legacy-skill");
        assertThat(def.getDescription()).isEqualTo("Legacy package.");
        // Body content still surfaced through promptContent.
        assertThat(def.getPromptContent()).contains("Legacy Skill");
    }

    @Test
    @DisplayName("loadFromDirectory_noFrontmatterNoSkillYaml_throwsIOException")
    void loadFromDirectory_noFrontmatterNoSkillYaml_throwsIOException(@TempDir Path dir) throws IOException {
        // SKILL.md present but missing frontmatter and no skill.yaml fallback.
        // Strict mode (SKILL-DATA-HYGIENE B1): must throw, not auto-extract.
        Files.writeString(dir.resolve("SKILL.md"),
                "# Bare Title\n\nSome paragraph that looks like a description.\n",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> loader.loadFromDirectory(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no YAML frontmatter")
                .hasMessageContaining("skill.yaml");
    }

    @Test
    @DisplayName("loadFromDirectory_missingSkillMd_throwsIOException")
    void loadFromDirectory_missingSkillMd_throwsIOException(@TempDir Path dir) {
        // Empty directory — no SKILL.md at all.
        assertThatThrownBy(() -> loader.loadFromDirectory(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("SKILL.md");
    }
}
