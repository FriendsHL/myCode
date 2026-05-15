package com.skillforge.server.improve.surface;

import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.PromptVersionRepository;
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
 * {@link PromptSurface#injectForSandbox} + the session-scoped registry it
 * populates. Mirrors {@code SkillSurfaceInjectTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PromptSurface.injectForSandbox")
class PromptSurfaceInjectTest {

    @Mock private PromptVersionRepository versionRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private PromptImproverService improverService;

    private PromptSurface surface;

    @BeforeEach
    void setUp() {
        surface = new PromptSurface(versionRepository, agentRepository, improverService);
    }

    @Test
    @DisplayName("inject stashes version under sessionId; getInjectedVersion retrieves it")
    void inject_stashesAndRetrieves() {
        PromptVersionEntity v1 = new PromptVersionEntity();
        v1.setId("ver-1");
        PromptVersionEntity v2 = new PromptVersionEntity();
        v2.setId("ver-2");
        SandboxContext ctx1 = new SandboxContext(1L, "session-a", null);
        SandboxContext ctx2 = new SandboxContext(1L, "session-b", null);

        surface.injectForSandbox(ctx1, v1);
        surface.injectForSandbox(ctx2, v2);

        assertThat(surface.getInjectedVersion("session-a")).isSameAs(v1);
        assertThat(surface.getInjectedVersion("session-b")).isSameAs(v2);
        assertThat(surface.getInjectedVersion("session-c")).isNull();
        assertThat(surface.getInjectedVersion(null)).isNull();
    }

    @Test
    @DisplayName("inject with version=null removes the existing entry")
    void inject_nullVersion_removes() {
        PromptVersionEntity v = new PromptVersionEntity();
        v.setId("ver-1");
        SandboxContext ctx = new SandboxContext(1L, "session-a", null);

        surface.injectForSandbox(ctx, v);
        assertThat(surface.getInjectedVersion("session-a")).isSameAs(v);

        surface.injectForSandbox(ctx, null);
        assertThat(surface.getInjectedVersion("session-a")).isNull();
    }

    @Test
    @DisplayName("inject with blank or null sessionId throws (defensive)")
    void inject_blankSessionId_throws() {
        PromptVersionEntity v = new PromptVersionEntity();
        v.setId("ver-1");
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
