package com.skillforge.server.improve.surface;

import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.2 — unit test for
 * {@link SkillSurface#injectForSandbox} + the session-scoped registry it
 * populates. Pure unit-level: no Spring, no DB, no actual sandbox build —
 * the registry semantic is just "put-and-get keyed by sessionId".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillSurface.injectForSandbox")
class SkillSurfaceInjectTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillAbEvalService abEvalService;

    private SkillSurface surface;

    @BeforeEach
    void setUp() {
        surface = new SkillSurface(skillRepository, abEvalService);
    }

    @Test
    @DisplayName("inject stashes version under sessionId; getInjectedVersion retrieves it")
    void inject_stashesAndRetrieves() {
        SkillEntity v1 = new SkillEntity();
        v1.setId(7L);
        SkillEntity v2 = new SkillEntity();
        v2.setId(8L);
        SandboxContext ctx1 = new SandboxContext(1L, "session-a", null);
        SandboxContext ctx2 = new SandboxContext(1L, "session-b", null);

        surface.injectForSandbox(ctx1, v1);
        surface.injectForSandbox(ctx2, v2);

        // Different sessions map to different stashed versions.
        assertThat(surface.getInjectedVersion("session-a")).isSameAs(v1);
        assertThat(surface.getInjectedVersion("session-b")).isSameAs(v2);
        // Unknown session returns null (no entry).
        assertThat(surface.getInjectedVersion("session-c")).isNull();
        // null sessionId returns null without NPE.
        assertThat(surface.getInjectedVersion(null)).isNull();
    }

    @Test
    @DisplayName("inject with version=null removes the existing entry (sandbox tear-down)")
    void inject_nullVersion_removes() {
        SkillEntity v1 = new SkillEntity();
        v1.setId(7L);
        SandboxContext ctx = new SandboxContext(1L, "session-a", null);

        surface.injectForSandbox(ctx, v1);
        assertThat(surface.getInjectedVersion("session-a")).isSameAs(v1);

        // Tear-down: passing null removes the entry.
        surface.injectForSandbox(ctx, null);
        assertThat(surface.getInjectedVersion("session-a")).isNull();
    }

    @Test
    @DisplayName("inject with blank or null sessionId throws (defensive)")
    void inject_blankSessionId_throws() {
        SkillEntity v = new SkillEntity();
        v.setId(7L);
        assertThatThrownBy(() ->
                surface.injectForSandbox(new SandboxContext(1L, "", null), v))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() ->
                surface.injectForSandbox(new SandboxContext(1L, null, null), v))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> surface.injectForSandbox(null, v))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
