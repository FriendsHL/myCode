package com.skillforge.server.service.command;

import com.skillforge.server.dto.CommandResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HelpCommandHandler — INV-11 registry-based")
class HelpCommandHandlerTest {

    @Test
    @DisplayName("INV-11: lists every registered handler — no hardcoded names")
    void inv11_listsRegisteredHandlers() {
        SlashCommandHandler a = stubHandler("alpha", "first cmd", "/alpha");
        SlashCommandHandler b = stubHandler("beta", "second cmd", "/beta <arg>");
        // The Help handler itself is NOT under test, but it pulls from the
        // service.registeredHandlers() — so wire it up the same way.
        HelpCommandHandler[] helpRef = new HelpCommandHandler[1];
        SlashCommandService service = buildService(List.of(a, b), helpRef);
        helpRef[0] = (HelpCommandHandler) findByName(service, "help");

        CommandResult r = helpRef[0].execute(7L, "sess-1", "", ExecutionContext.web());
        assertThat(r.success()).isTrue();
        assertThat(r.displayMode()).isEqualTo("modal");
        assertThat(r.markdownBody()).contains("alpha").contains("beta");
        assertThat(r.markdownBody()).contains("first cmd").contains("second cmd");
        assertThat(r.message()).contains("3"); // alpha + beta + help itself
    }

    @Test
    @DisplayName("INV-11: adding a new handler at boot extends help output")
    void inv11_newHandlerExtendsHelp() {
        SlashCommandHandler a = stubHandler("alpha", "a", "/alpha");
        SlashCommandHandler b = stubHandler("beta", "b", "/beta");
        SlashCommandHandler gamma = stubHandler("gamma", "g", "/gamma");

        HelpCommandHandler[] helpRef = new HelpCommandHandler[1];
        SlashCommandService svc = buildService(List.of(a, b, gamma), helpRef);
        helpRef[0] = (HelpCommandHandler) findByName(svc, "help");
        CommandResult r = helpRef[0].execute(7L, "sess-1", "", ExecutionContext.web());

        assertThat(r.markdownBody()).contains("alpha").contains("beta").contains("gamma");
    }

    @Test
    @DisplayName("help is sorted last in its own output")
    void helpIsLastInListing() {
        SlashCommandHandler a = stubHandler("zebra", "z", "/zebra");
        HelpCommandHandler[] helpRef = new HelpCommandHandler[1];
        SlashCommandService svc = buildService(List.of(a), helpRef);
        helpRef[0] = (HelpCommandHandler) findByName(svc, "help");

        CommandResult r = helpRef[0].execute(7L, "sess-1", "", ExecutionContext.web());
        String body = r.markdownBody();
        // zebra row comes before help row even though "z" > "h" alphabetically.
        int zIdx = body.indexOf("`/zebra`");
        int hIdx = body.indexOf("`/help`");
        assertThat(zIdx).isPositive();
        assertThat(hIdx).isPositive();
        assertThat(zIdx).isLessThan(hIdx);
    }

    private static SlashCommandHandler findByName(SlashCommandService svc, String name) {
        return svc.registeredHandlers().stream()
                .filter(h -> h.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /** Build a service that contains the given handlers + a HelpCommandHandler wired with a manual provider. */
    private SlashCommandService buildService(List<SlashCommandHandler> others,
                                              HelpCommandHandler[] helpRef) {
        SlashCommandService[] svcRef = new SlashCommandService[1];
        ObjectProvider<SlashCommandService> provider = new TestProvider(() -> svcRef[0]);
        HelpCommandHandler help = new HelpCommandHandler(provider);
        helpRef[0] = help;
        java.util.List<SlashCommandHandler> all = new java.util.ArrayList<>(others);
        all.add(help);
        SlashCommandService svc = new SlashCommandService(all);
        svcRef[0] = svc;
        return svc;
    }

    private static SlashCommandHandler stubHandler(String name, String desc, String usage) {
        return new SlashCommandHandler() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return desc; }
            @Override public String getUsage() { return usage; }
            @Override
            public CommandResult execute(Long u, String s, String a, ExecutionContext c) {
                return CommandResult.toast("ok");
            }
        };
    }

    /** Minimal ObjectProvider for tests — only {@link #getObject()} is called. */
    private static final class TestProvider implements ObjectProvider<SlashCommandService> {
        private final java.util.function.Supplier<SlashCommandService> supplier;

        TestProvider(java.util.function.Supplier<SlashCommandService> supplier) {
            this.supplier = supplier;
        }

        @Override public SlashCommandService getObject() { return supplier.get(); }
        @Override public SlashCommandService getObject(Object... args) { return supplier.get(); }
        @Override public SlashCommandService getIfAvailable() { return supplier.get(); }
        @Override public SlashCommandService getIfUnique() { return supplier.get(); }
    }
}
