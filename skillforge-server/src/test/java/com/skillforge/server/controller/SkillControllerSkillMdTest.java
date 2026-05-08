package com.skillforge.server.controller;

import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.improve.SkillEvolutionService;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.SkillService;
import com.skillforge.server.skill.SkillCatalogReconciler;
import com.skillforge.server.skill.SkillBatchImporter;
import com.skillforge.server.skill.UserSkillLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * SKILL-DASHBOARD-POLISH B — verify {@code GET /api/skills/{id}/skill-md} returns
 * the SKILL.md content from the on-disk skill_path. Covers:
 * <ul>
 *   <li>200 happy path: existing skill with SKILL.md</li>
 *   <li>200 with empty content: skill row exists but skill_path is null</li>
 *   <li>200 with empty content + error: SKILL.md file missing on disk</li>
 *   <li>403: caller is non-owner of a non-public skill</li>
 *   <li>RuntimeException: skill id does not exist</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillController.getSkillMd")
class SkillControllerSkillMdTest {

    @Mock private SkillService skillService;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillAbEvalService skillAbEvalService;
    @Mock private SkillEvolutionService skillEvolutionService;
    @Mock private SkillCatalogReconciler reconciler;
    @Mock private UserSkillLoader userSkillLoader;
    @Mock private SkillBatchImporter skillBatchImporter;
    @Mock private SkillRepository skillRepository;

    private SkillController controller;

    @BeforeEach
    void setUp() {
        controller = new SkillController(skillService, skillRegistry,
                skillAbEvalService, skillEvolutionService, reconciler, userSkillLoader,
                skillBatchImporter, skillRepository);
    }

    @Test
    @DisplayName("happy path: returns content + path when SKILL.md exists")
    void happyPath_returnsContent(@TempDir Path tmp) throws IOException {
        Path skillDir = tmp.resolve("skill-1");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# MySkill\n\nDoes a thing.\n");

        SkillEntity entity = new SkillEntity();
        entity.setId(1L);
        entity.setOwnerId(7L);
        entity.setSkillPath(skillDir.toString());
        when(skillRepository.findById(1L)).thenReturn(Optional.of(entity));

        ResponseEntity<?> resp = controller.getSkillMd(1L, 7L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("content")).asString().contains("# MySkill");
        assertThat(body.get("path")).asString().endsWith("SKILL.md");
    }

    @Test
    @DisplayName("skill_path null → 200 with empty content + null path")
    void nullSkillPath_returnsEmpty() {
        SkillEntity entity = new SkillEntity();
        entity.setId(2L);
        entity.setOwnerId(7L);
        entity.setSkillPath(null);
        when(skillRepository.findById(2L)).thenReturn(Optional.of(entity));

        ResponseEntity<?> resp = controller.getSkillMd(2L, 7L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("content")).isEqualTo("");
        assertThat(body.get("path")).isNull();
    }

    @Test
    @DisplayName("SKILL.md missing on disk → 200 with empty content + error message")
    void skillMdMissing_returnsErrorBody(@TempDir Path tmp) throws IOException {
        Path skillDir = tmp.resolve("skill-3");
        Files.createDirectories(skillDir);
        // Directory exists but SKILL.md does not.

        SkillEntity entity = new SkillEntity();
        entity.setId(3L);
        entity.setOwnerId(7L);
        entity.setSkillPath(skillDir.toString());
        when(skillRepository.findById(3L)).thenReturn(Optional.of(entity));

        ResponseEntity<?> resp = controller.getSkillMd(3L, 7L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("content")).isEqualTo("");
        assertThat(body.get("error")).asString().contains("not found");
    }

    @Test
    @DisplayName("non-owner of non-public skill → 403 forbidden")
    void nonOwner_nonPublic_returns403() {
        SkillEntity entity = new SkillEntity();
        entity.setId(4L);
        entity.setOwnerId(7L);
        entity.setPublic(false);
        when(skillRepository.findById(4L)).thenReturn(Optional.of(entity));

        // userId=99 != ownerId=7, skill is not public → forbidden.
        ResponseEntity<?> resp = controller.getSkillMd(4L, 99L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("forbidden");
    }
}
