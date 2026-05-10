package com.skillforge.core.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.CompactThresholds;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;

import java.util.Collections;
import java.util.List;

/**
 * REMINDER-MVP: per-iteration context handed to every {@link ReminderSource} so each source
 * can decide whether to emit and what to render.
 *
 * <p>Q2 (cache-friendly migration, 2026-05-10): reminder injection moved from
 * AgentLoopEngine system-prompt suffix to ChatService user-message ContentBlock at user-message
 * boundary. As a consequence the context no longer carries a {@code LoopContext} reference —
 * sources read/write per-source debounce state directly through {@link ReminderBuilder}'s
 * per-session map (see {@link ReminderBuilder#getLastEmitted} /
 * {@link ReminderBuilder#setLastEmitted}).
 *
 * <p>Carried fields:
 * <ul>
 *   <li>{@code sessionId} / {@code userId} — for source DB queries (memory age) and per-session
 *       debounce key</li>
 *   <li>{@code currentTurnIndex} = {@code messages.size()} when ChatService is composing the
 *       new user message — used as the debounce baseline (PRD D3)</li>
 *   <li>{@code messages} — pre-userMsg history (used by ContextUsageSource to estimate the
 *       current ratio).  Defensive empty default when caller passes {@code null}</li>
 *   <li>{@code maxTokens} — provider context window in tokens (denominator for usage ratio)</li>
 *   <li>{@code systemPrompt} / {@code tools} / {@code requestMaxTokens} / {@code jsonMapper}
 *       — feed {@link com.skillforge.core.compact.RequestTokenEstimator#estimate} so
 *       ContextUsageSource matches the engine compact-trigger ratio (system prompt + messages
 *       + tool schemas + LLM output reservation, not just message tokens)</li>
 *   <li>{@code compactThresholds} — per-provider thresholds resolved by the engine; sources
 *       use them for hint text ("soft compact at 60%" etc.)</li>
 *   <li>{@code reminderBuilder} — back-reference so sources can read/write per-session
 *       debounce state without a LoopContext</li>
 * </ul>
 *
 * <p>Immutable from the source's perspective — sources MUST NEVER mutate {@code messages}.
 */
public final class ReminderContext {

    private final String sessionId;
    private final Long userId;
    private final int currentTurnIndex;
    private final List<Message> messages;
    private final int maxTokens;
    private final String systemPrompt;
    private final List<ToolSchema> tools;
    private final int requestMaxTokens;
    private final ObjectMapper jsonMapper;
    private final CompactThresholds compactThresholds;
    private final ReminderBuilder reminderBuilder;

    /**
     * Full constructor.
     *
     * @param sessionId         session id (passes through to file-cache lookups + debounce key)
     * @param userId            user id (memory queries); may be null on background paths
     * @param currentTurnIndex  {@code messages.size()} at request build time (debounce baseline)
     * @param messages          outgoing message list (defensive empty when null)
     * @param maxTokens         provider context window (denominator for usage ratio)
     * @param systemPrompt      built system prompt at the time of reminder injection
     * @param tools             collected tool schemas
     * @param requestMaxTokens  agent's per-request max_tokens (output reservation)
     * @param jsonMapper        Jackson mapper for tool-schema serialisation in
     *                          {@link com.skillforge.core.compact.RequestTokenEstimator}
     * @param compactThresholds per-provider thresholds (soft / hard / preemptive)
     * @param reminderBuilder   back-reference for per-session debounce state; may be null on
     *                          legacy / unit test paths (sources fall back to "always allow")
     */
    public ReminderContext(String sessionId,
                           Long userId,
                           int currentTurnIndex,
                           List<Message> messages,
                           int maxTokens,
                           String systemPrompt,
                           List<ToolSchema> tools,
                           int requestMaxTokens,
                           ObjectMapper jsonMapper,
                           CompactThresholds compactThresholds,
                           ReminderBuilder reminderBuilder) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.currentTurnIndex = Math.max(0, currentTurnIndex);
        this.messages = messages != null ? messages : Collections.emptyList();
        this.maxTokens = Math.max(0, maxTokens);
        this.systemPrompt = systemPrompt;
        this.tools = tools != null ? tools : Collections.emptyList();
        this.requestMaxTokens = Math.max(0, requestMaxTokens);
        this.jsonMapper = jsonMapper;
        this.compactThresholds = compactThresholds != null ? compactThresholds : CompactThresholds.DEFAULTS;
        this.reminderBuilder = reminderBuilder;
    }

    /**
     * Compact constructor for tests / legacy callers — fills the request-envelope fields with
     * neutral defaults (empty system prompt, no tools, 0 request max tokens, no jsonMapper,
     * DEFAULTS thresholds, no builder back-ref).
     */
    public ReminderContext(String sessionId,
                           Long userId,
                           int currentTurnIndex,
                           List<Message> messages,
                           int maxTokens) {
        this(sessionId, userId, currentTurnIndex, messages, maxTokens,
                "", Collections.emptyList(), 0, null, CompactThresholds.DEFAULTS, null);
    }

    public String getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public int getCurrentTurnIndex() { return currentTurnIndex; }
    public List<Message> getMessages() { return messages; }
    public int getMaxTokens() { return maxTokens; }
    public String getSystemPrompt() { return systemPrompt; }
    public List<ToolSchema> getTools() { return tools; }
    public int getRequestMaxTokens() { return requestMaxTokens; }
    public ObjectMapper getJsonMapper() { return jsonMapper; }
    public CompactThresholds getCompactThresholds() { return compactThresholds; }
    public ReminderBuilder getReminderBuilder() { return reminderBuilder; }
}
