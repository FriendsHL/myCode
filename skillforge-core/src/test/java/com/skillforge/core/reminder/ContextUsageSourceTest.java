package com.skillforge.core.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.compact.RequestTokenEstimator;
import com.skillforge.core.llm.CompactThresholds;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextUsageSourceTest {

    private ReminderBuilder builder;
    private ObjectMapper mapper;
    private CompactThresholds thresholds;

    @BeforeEach
    void setUp() {
        // Q2: per-session debounce state lives on the builder, not LoopContext.
        // Use a real builder with no sources — only its lastEmitted map is exercised.
        builder = new ReminderBuilder(List.of(), 5_000, true);
        mapper = new ObjectMapper();
        thresholds = CompactThresholds.DEFAULTS; // 0.60 / 0.80 / 0.85
    }

    /** A varied-text message produces a stable token estimate via cl100k_base. */
    private List<Message> messagesWithVariedText() {
        List<Message> msgs = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("hello world the quick brown fox jumps over the lazy dog ");
        }
        msgs.add(Message.user(sb.toString()));
        return msgs;
    }

    /**
     * Build a context with the FULL request envelope. We size {@code maxTokens} so the actual
     * ratio sits at {@code targetPct}.
     */
    private ReminderContext ctxWithRatio(int turn, int targetPct) {
        return ctxWithRatio(turn, targetPct, "system prompt content", List.of(),
                4096 /* requestMaxTokens reservation */);
    }

    private ReminderContext ctxWithRatio(int turn, int targetPct,
                                         String systemPrompt,
                                         List<ToolSchema> tools,
                                         int requestMaxTokens) {
        List<Message> msgs = messagesWithVariedText();
        int actual = RequestTokenEstimator.estimate(systemPrompt, msgs, tools, requestMaxTokens, mapper);
        int maxTokens = Math.max(1, (int) Math.round(actual * 100.0 / Math.max(1, targetPct)));
        return new ReminderContext("s1", 1L, turn, msgs, maxTokens,
                systemPrompt, tools, requestMaxTokens, mapper, thresholds, builder);
    }

    private ReminderContext ctxBelow70() { return ctxWithRatio(0, 30); }
    private ReminderContext ctxAt80() { return ctxWithRatio(0, 80); }

    @Test
    @DisplayName("disabled → never emits")
    void disabled_skip() {
        ContextUsageSource src = new ContextUsageSource(false, 1, 70);
        assertThat(src.shouldEmit(ctxAt80())).isFalse();
    }

    @Test
    @DisplayName("shouldEmit is O(1) — does NOT call RequestTokenEstimator (W3: deferred to emit)")
    void shouldEmit_skipsEstimate() {
        ContextUsageSource src = new ContextUsageSource(true, 1, 70);
        ReminderContext c = ctxBelow70();
        assertThat(src.shouldEmit(c)).isTrue();
        assertThat(src.emit(c)).isNull();
    }

    @Test
    @DisplayName("below threshold → emit returns null; debounce state NOT written")
    void belowThreshold_returnsNull() {
        ContextUsageSource src = new ContextUsageSource(true, 1, 70);
        ReminderEntry e = src.emit(ctxBelow70());
        assertThat(e).isNull();
        assertThat(builder.getLastEmitted("s1", "context-usage")).isNull();
    }

    @Test
    @DisplayName("at/above threshold → emits with Context: text and estimated tokens")
    void aboveThreshold_emits() {
        ContextUsageSource src = new ContextUsageSource(true, 1, 70);
        ReminderContext c = ctxAt80();
        assertThat(src.shouldEmit(c)).isTrue();
        ReminderEntry e = src.emit(c);
        assertThat(e).isNotNull();
        assertThat(e.text()).contains("Context");
        assertThat(e.text()).contains("used");
        assertThat(e.estimatedTokens()).isGreaterThan(0);
    }

    @Test
    @DisplayName("W3: estimate matches RequestTokenEstimator (system prompt + tools count too)")
    void estimateUsesRequestTokenEstimator() {
        ContextUsageSource src = new ContextUsageSource(true, 1, 70);
        String bigSystemPrompt = "system: " + "boilerplate ".repeat(100);
        ToolSchema tool = new ToolSchema();
        tool.setName("ExampleTool");
        tool.setDescription("a sample tool with description text");
        tool.setInputSchema(Map.of("type", "object", "properties", Map.of()));
        ReminderContext c = ctxWithRatio(0, 80, bigSystemPrompt, List.of(tool), 4096);

        ReminderEntry e = src.emit(c);
        assertThat(e).isNotNull();

        String text = e.text();
        int slash = text.indexOf('/');
        int paren = text.indexOf('(');
        assertThat(slash).isPositive();
        int reportedUsed = Integer.parseInt(text.substring(paren + 1, slash));

        int requestEstimate = RequestTokenEstimator.estimate(
                bigSystemPrompt, c.getMessages(), c.getTools(), c.getRequestMaxTokens(), mapper);
        assertThat(reportedUsed).isEqualTo(requestEstimate);

        int justMessages = com.skillforge.core.compact.TokenEstimator.estimate(c.getMessages());
        assertThat(reportedUsed).isGreaterThan(justMessages);
    }

    @Test
    @DisplayName("interval=1 → emit allowed every turn (no debounce gap)")
    void interval1_everyTurn() {
        ContextUsageSource src = new ContextUsageSource(true, 1, 70);
        ReminderContext c0 = ctxWithRatio(0, 80);
        assertThat(src.shouldEmit(c0)).isTrue();
        src.emit(c0);
        ReminderContext c1 = ctxWithRatio(1, 80);
        assertThat(src.shouldEmit(c1)).isTrue();
    }

    @Test
    @DisplayName("interval=3 → debounce holds for 2 turns then re-allows")
    void interval3_debounces() {
        ContextUsageSource src = new ContextUsageSource(true, 3, 70);
        ReminderContext c0 = ctxWithRatio(0, 80);
        assertThat(src.shouldEmit(c0)).isTrue();
        src.emit(c0);
        for (int t = 1; t < 3; t++) {
            assertThat(src.shouldEmit(ctxWithRatio(t, 80)))
                    .as("turn %d should be debounced", t).isFalse();
        }
        assertThat(src.shouldEmit(ctxWithRatio(3, 80))).isTrue();
    }

    @Test
    @DisplayName("hint mentions 'soft compact' below soft ratio (60% default)")
    void hint_belowSoft() {
        String hint = ContextUsageSource.nextThresholdHint(55, thresholds);
        assertThat(hint).contains("soft compact");
        assertThat(hint).contains("60");
    }

    @Test
    @DisplayName("hint mentions 'hard' once soft already crossed (between soft and hard)")
    void hint_betweenSoftAndHard() {
        String hint = ContextUsageSource.nextThresholdHint(72, thresholds);
        assertThat(hint).contains("soft compact at 60");
        assertThat(hint).contains("hard");
    }

    @Test
    @DisplayName("hint mentions 'preemptive' once hard already crossed")
    void hint_betweenHardAndPreemptive() {
        String hint = ContextUsageSource.nextThresholdHint(82, thresholds);
        assertThat(hint).contains("hard");
        assertThat(hint).contains("preemptive");
    }

    @Test
    @DisplayName("hint mentions 'wrap up' once preemptive already crossed")
    void hint_abovePreemptive() {
        String hint = ContextUsageSource.nextThresholdHint(95, thresholds);
        assertThat(hint).contains("wrap up");
    }

    @Test
    @DisplayName("emit updates per-session lastEmitted state on the builder when above threshold")
    void emit_updatesBuilderState() {
        ContextUsageSource src = new ContextUsageSource(true, 1, 70);
        ReminderContext c = ctxWithRatio(11, 80);
        assertThat(builder.getLastEmitted("s1", "context-usage")).isNull();
        src.emit(c);
        assertThat(builder.getLastEmitted("s1", "context-usage")).isEqualTo(11);
    }

    @Test
    @DisplayName("maxTokens<=0 → shouldEmit=false (defensive)")
    void zeroMaxTokens_skip() {
        ContextUsageSource src = new ContextUsageSource(true, 1, 70);
        ReminderContext zero = new ReminderContext("s1", 1L, 0,
                messagesWithVariedText(), 0, "sys", List.of(), 4096, mapper, thresholds, builder);
        assertThat(src.shouldEmit(zero)).isFalse();
    }
}
