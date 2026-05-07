package com.skillforge.server.service.command;

import com.skillforge.server.dto.CommandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SlashCommandService — dispatch + parsing + INV-15 precise match")
class SlashCommandServiceTest {

    private RecordingHandler newH;
    private RecordingHandler modelH;
    private RecordingHandler modelsH;
    private SlashCommandService service;

    @BeforeEach
    void setUp() {
        newH = new RecordingHandler("new", "create new");
        modelH = new RecordingHandler("model", "switch model");
        modelsH = new RecordingHandler("models", "list models");
        service = new SlashCommandService(List.of(newH, modelH, modelsH));
    }

    @Test
    @DisplayName("exact match: /new dispatches to NewCommandHandler with empty args")
    void exactMatch_new_noArgs() {
        CommandResult r = service.execute(7L, "sess-1", "/new", ExecutionContext.web());
        assertThat(r.success()).isTrue();
        assertThat(newH.lastArgs).isEqualTo("");
        assertThat(modelH.lastArgs).isNull();
        assertThat(modelsH.lastArgs).isNull();
    }

    @Test
    @DisplayName("exact match: /new <agent> passes the rest as args")
    void exactMatch_new_withArgs() {
        service.execute(7L, "sess-1", "/new researcher", ExecutionContext.web());
        assertThat(newH.lastArgs).isEqualTo("researcher");
    }

    @Test
    @DisplayName("INV-15: /model gpt-4 routes to ModelCommandHandler, NOT ModelsCommandHandler")
    void inv15_modelGptIsNotAbsorbedByModels() {
        service.execute(7L, "sess-1", "/model gpt-4o", ExecutionContext.web());
        assertThat(modelH.lastArgs).isEqualTo("gpt-4o");
        assertThat(modelsH.lastArgs).as("/models must NOT see this — exact-match only")
                .isNull();
    }

    @Test
    @DisplayName("INV-15: /models routes to ModelsCommandHandler, NOT ModelCommandHandler")
    void inv15_modelsRoutesCorrectly() {
        service.execute(7L, "sess-1", "/models", ExecutionContext.web());
        assertThat(modelsH.lastArgs).isEqualTo("");
        assertThat(modelH.lastArgs).as("/model must NOT trigger when user types /models")
                .isNull();
    }

    @Test
    @DisplayName("INV-15: /model and /models are independent — both can be invoked back-to-back")
    void inv15_bothInvocableInSequence() {
        service.execute(7L, "sess-1", "/model deepseek:deepseek-chat", ExecutionContext.web());
        service.execute(7L, "sess-1", "/models", ExecutionContext.web());
        assertThat(modelH.lastArgs).isEqualTo("deepseek:deepseek-chat");
        assertThat(modelsH.lastArgs).isEqualTo("");
    }

    @Test
    @DisplayName("INV-9: unknown command returns success=false, does NOT throw")
    void inv9_unknownCommandIsStructuredError() {
        CommandResult r = service.execute(7L, "sess-1", "/foo", ExecutionContext.web());
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("/foo");
        assertThat(newH.lastArgs).isNull();
    }

    @Test
    @DisplayName("blank / null command returns structured error")
    void blankCommandIsError() {
        assertThat(service.execute(7L, "sess-1", null, ExecutionContext.web()).success()).isFalse();
        assertThat(service.execute(7L, "sess-1", "", ExecutionContext.web()).success()).isFalse();
        assertThat(service.execute(7L, "sess-1", "no-slash", ExecutionContext.web()).success()).isFalse();
    }

    @Test
    @DisplayName("case-insensitive token match: /MODEL gpt → routes to /model")
    void caseInsensitiveDispatch() {
        service.execute(7L, "sess-1", "/MODEL gpt-4o", ExecutionContext.web());
        assertThat(modelH.lastArgs).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("handler RuntimeException is converted into structured error (INV-9)")
    void handlerThrows_convertedToError() {
        AtomicReference<RuntimeException> hold = new AtomicReference<>(new RuntimeException("boom"));
        SlashCommandHandler bad = new SlashCommandHandler() {
            @Override public String getName() { return "bad"; }
            @Override public String getDescription() { return ""; }
            @Override public String getUsage() { return "/bad"; }
            @Override
            public CommandResult execute(Long u, String s, String a, ExecutionContext c) {
                throw hold.get();
            }
        };
        SlashCommandService svc = new SlashCommandService(List.of(bad));
        CommandResult r = svc.execute(7L, "s", "/bad", ExecutionContext.web());
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("boom");
    }

    @Test
    @DisplayName("registry rejects duplicate command names at construction")
    void duplicateName_isRejectedAtBoot() {
        RecordingHandler a = new RecordingHandler("dup", "");
        RecordingHandler b = new RecordingHandler("dup", "");
        assertThatThrownBy(() -> new SlashCommandService(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate slash command name");
    }

    @Test
    @DisplayName("registry rejects blank command name at construction")
    void blankName_isRejectedAtBoot() {
        RecordingHandler bad = new RecordingHandler("", "");
        assertThatThrownBy(() -> new SlashCommandService(List.of(bad)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("registeredHandlers exposes all handlers for /help")
    void registeredHandlers_returnsAll() {
        assertThat(service.registeredHandlers()).hasSize(3);
        assertThat(service.registeredHandlers().stream().map(SlashCommandHandler::getName))
                .containsExactlyInAnyOrder("new", "model", "models");
    }

    /** Test fixture: records dispatch context. */
    private static final class RecordingHandler implements SlashCommandHandler {
        private final String name;
        private final String desc;
        String lastArgs;
        Long lastUserId;
        String lastSessionId;

        RecordingHandler(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return desc; }
        @Override public String getUsage() { return "/" + name; }

        @Override
        public CommandResult execute(Long userId, String sessionId, String args, ExecutionContext context) {
            this.lastUserId = userId;
            this.lastSessionId = sessionId;
            this.lastArgs = args;
            return CommandResult.toast("ok");
        }
    }
}
