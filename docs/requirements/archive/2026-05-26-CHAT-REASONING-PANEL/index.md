# CHAT-REASONING-PANEL

> **类型**: Lite 需求包（仅 index.md，无独立 PRD / tech-design）
> **完成日期**: 2026-05-26
> **Pipeline**: Full（触碰 `skillforge-core/llm/**` 核心文件 + 新加 SSE event 协议 + 跨 3+ 模块）
> **关联**: 无前置需求；Chat UX 改善（reasoning_content 独立 streaming + per-agent collapse 偏好）

---

## User Request

> "chat 过程中 reason_content 展示了一下 然后就立刻没了。这块有没有更好的优化方式。"

**根因定位**：`OpenAiProvider.java:1043` 把 `reasoning_content` delta 灌进 `handler.onText(...)` 跟正文混流 →
FE 流式期间渲染（"展示了一下"）→ 最终 Message 把 `reasoningContent` 拆到独立字段持久化但 `ChatWindow.tsx` 从未渲染该字段 → 完成后消失（"立刻没了"）。

## Acceptance

1. ✅ **流式期间**：reasoning 内容显示在 assistant 气泡上方的独立 "Thinking…" panel，带 live spinner
2. ✅ **完成后**：panel 自动 collapse 成 `Thought for N.Ns ▾`，点击可展开看完整 reasoning（Linear / Cursor 风格）
3. ✅ **历史消息加载**：刷新页面后历史 reasoning 仍可展开查看（`t_session_message.reasoning_content` 已存，FE normalizeMessages 透传）
4. ✅ **per-agent 偏好**：Agent 配置里可设"默认折叠 / 展开"，agent 维度生效；新 agent 默认折叠（`thinkingVisible` 字段 null → FE `?? false`）
5. ⚠️ **hover tooltip token 拆分**：MVP 不做（plan Q5 decision，BE LlmResponse.Usage 未分离 `reasoning_tokens`，独立 follow-up）
6. ✅ **OpenAI-compatible provider 覆盖**：DeepSeek / Qwen / mimo 全部生效（依赖既有 `OpenAiProvider:1043` reasoning_content delta 解析）

## Implementation Decisions

| 决策 | 选择 | 理由 |
|---|---|---|
| Provider scope | OpenAI-compatible only（DeepSeek/Qwen/mimo）| Claude extended thinking 既有 gap 本期不动（另开需求）；OpenAiProvider:1043 已经在拿 reasoning_content delta，改造成本最小 |
| 偏好范围 + 存储 | per-agent + BE `AgentEntity.thinkingVisible` Boolean nullable + Flyway V119 | 跨设备一致；agent 类型差异大；nullable 三态语义（null/false/true）保留 |
| SSE / WS event | 新加 `reasoning_delta` event type 跟 `text_delta` 平行 | 语义清晰；老 FE 看不懂忽略；跟 `Message.reasoningContent` 持久化字段天然对齐 |
| LlmStreamHandler 接口 | 加 `default void onReasoning(String reasoning) {}` no-op | 不破坏现有 provider 实现；OpenAiProvider:1043 从 `onText` 改 `onReasoning` |
| Q1 节流 | 200ms ThrottledMarkdown 模式（与 text 完全对齐） | 不升 500ms；FE 体感与 text streaming 一致 |
| Q2 思考时长 | FE wall clock（第一个 reasoning_delta → 第一个 text_delta） | 不动 BE LlmResponse 字段；精度差 ≤100ms 网络延迟，对"3.2s"显示粒度足够 |
| Q3 Panel CSS | sans-serif + ember `--accent` border-stripe + tinted bg | 不用 monospace（reasoning 是散文，不是终端输出）；planner 写紫色 `#6366f1` 是 stale，实际项目 `--accent` 是 `#d9633a` ember orange |
| Q4 字段命名 | `thinkingVisible` (与 `thinkingMode`/`reasoningEffort` 命名族对齐) | `reasoningVisible` 更 accurate 但与现有族不对齐 |
| Q5 token usage | MVP 不做 | BE LlmResponse.Usage 无 reasoningTokens；UsageNormalizer 未解析 `completion_tokens_details.reasoning_tokens`；独立 follow-up |
| Q6 历史兼容 | null/empty/whitespace → ReasoningPanel return null（不占空间）| 完全 graceful，无破坏存量 session |

## Implementation Notes

**BE (8 modified + 1 migration + 3 new test files, +80 / -2 lines)**:
- `skillforge-core/.../llm/LlmStreamHandler.java` — 加 `default void onReasoning(String reasoning)` no-op + JavaDoc 含 REG-3 wrapper-forward 警告
- `skillforge-core/.../llm/OpenAiProvider.java:1043` — `handler.onText(reasoning)` → `handler.onReasoning(reasoning)`；`fullReasoning.append` + `setReasoningContent on onComplete` 保留（REG-4 未 regression）
- `skillforge-core/.../llm/ClaudeProvider.java:807-810` — `ObservedStreamHandler` 加 `@Override public void onReasoning(String r) { inner.onReasoning(r); }` + REG-3 comment（避免 default no-op shadow inner）
- `skillforge-core/.../engine/ChatEventBroadcaster.java` — 加 `default void reasoningDelta(String sessionId, String delta)` no-op
- `skillforge-core/.../engine/AgentLoopEngine.java` — 两处 inline LlmStreamHandler（line 936 主 chatStream + line 1552 continuation）加 `onReasoning` override 转发 `broadcaster.reasoningDelta`
- `skillforge-server/.../websocket/ChatWebSocketHandler.java` — 加 `@Override reasoningDelta` 推 `{type:"reasoning_delta", sessionId, delta}` payload（mirror text_delta shape）
- `skillforge-server/.../entity/AgentEntity.java` — 加 `Boolean thinkingVisible` 字段 + `@Column(name="thinking_visible")` + getter/setter
- `skillforge-server/.../service/AgentService.java:90` — **Phase Final BLOCKER 修复**：partial-update pattern 漏 setter call → 加 `if (updated.getThinkingVisible() != null) existing.setThinkingVisible(updated.getThinkingVisible());` + 4 行 NOTE 标注 partial-update limitation
- `skillforge-server/.../db/migration/V119__add_agent_thinking_visible.sql` — `ALTER TABLE t_agent ADD COLUMN thinking_visible BOOLEAN;` (plan 写 V100 是占位，实际 V118 已存在 → V119)

**FE (8 modified + 3 new = ChatMessage interface + props + handler + UI + CSS, +472 / -21 lines)**:
- `skillforge-dashboard/src/components/ReasoningPanel.tsx` *(new)* — 3-mode renderer (streaming / completed / null) + `useId()` unique ARIA id（B-FE-1 r2 fix）+ ThrottledMarkdown 节流 streaming text + pure-CSS border-rotation spinner + `prefers-reduced-motion` fallback + click-toggle expand/collapse with `aria-expanded`
- `skillforge-dashboard/src/components/ThrottledMarkdown.tsx` *(new)* — B-FE-2 r2 fix：从 ChatWindow.tsx 拆出，消除 ChatWindow↔ReasoningPanel 循环 import
- `skillforge-dashboard/src/components/ChatWindow.tsx` — 加 `ChatMessage.reasoningContent`；3 new props (`streamingReasoningText` / `reasoningDurationMs` / `agentThinkingVisible`)；streaming 区上层 panel + 下层 text bubble；每条 completed assistant message bubble 含 `ReasoningPanel`；`forceStreaming={durationMs===null}` flip in-place 不 remount
- `skillforge-dashboard/src/hooks/useChatWsEventHandler.ts` — 加 `reasoning_delta` case + `reasoningStartTsRef` 计时；第一个 `text_delta` 触发 `setReasoningDurationMs = Date.now() - ref` + 清 ref；`session_status` idle/error + `message_appended` 双清理路径
- `skillforge-dashboard/src/hooks/useChatMessages.ts` — `normalizeMessages` 在 assistant push site 透传 `reasoningContent`；filter relaxed 让仅含 reasoning 的 row 也保留
- `skillforge-dashboard/src/pages/Chat.tsx` — 加 `streamingReasoningText` + `reasoningDurationMs` state；传 setter 给 handler；`agentThinkingVisible = activeAgent?.thinkingVisible ?? null` 透传 ChatWindow
- `skillforge-dashboard/src/components/agents/AgentDrawer.tsx` — C3 完整 5-spot mirror（state+initial / useEffect[agent.id] / overviewDirty / save payload / UI Switch in Overview card 紧邻 ReasoningEffort）；isSystemAgent 时 disabled
- `skillforge-dashboard/src/api/index.ts` + `schemas.ts` — `CreateAgentRequest.thinkingVisible?: boolean | null` + Zod `.optional().nullable()`
- `skillforge-dashboard/src/index.css` — `.reasoning-panel` + `--streaming` / `--completed` modifiers + `__header--toggle` designed hover/focus-visible/active states + pure-CSS spinner

**Iron Law audit (PASS)**:
- ✅ `persistence-shape-invariant.md` — 不动 ChatService 持久化 / Engine.runInternal messages 拼装 / Compactor / SessionService.rewriteMessages / Message / ContentBlock；`reasoning_delta` 是流式广播事件不进 messages list；`Message.reasoningContent` 字段早已在不变
- ✅ `identity-column-on-rewrite.md` — `thinking_visible` 在 `t_agent` 不在 `t_session_message`，不在 rewrite 路径
- ✅ `java.md` footgun #1 ObjectMapper JavaTimeModule — r2 fix 给 AgentEntityThinkingVisibleTest 加了 `registerModule + disable WRITE_DATES_AS_TIMESTAMPS`
- ✅ `java.md` footgun #6/#6b FE-BE 契约 — grep 字段名对齐（thinkingVisible / reasoning_delta payload）；BE Jackson roundtrip test 覆盖；**真活 curl smoke 在 Phase Final 跑了 GET + PUT true/false 都 roundtrip verified**（救了 1 个 BLOCKER）
- ✅ `frontend.md` footgun #3 流式渲染节流 — reasoning_delta 走 ThrottledMarkdown 200ms 模式
- ✅ `llm-provider-compat-reviewer` REG-3 ObservedStreamHandler forward + REG-4 SSE 解析无 regression — 显式 audit 过
- ✅ `design.md` Anti-Template — ember accent border-stripe + tinted bg（非 Ant 默认）+ designed hover/focus-visible/active + 视觉层次 + semantic color

## Pipeline Notes

**Full pipeline 完整跑了 r1（plan + dev + 4 reviewer 对抗 1 轮）+ r2（mandatory fix bundle 5 项）+ Phase Final（真活 curl 救 1 BLOCKER + fix）**：

- **Plan phase**：planner sonnet explore 6 个 pre-questions → 写 `/tmp/chat-reasoning-plan-r1.md`；主会话审 plan 质量足够直接 ratify，不起 plan-review 对抗循环（节省 30+ min token）
- **Dev phase**：be-dev opus + fe-dev opus 并行；be-dev 主动校正 V100→V119 数字 + 显式 grep AgentController shape（C2）；fe-dev 主动 override planner 紫色 → 项目实际 ember accent；fe-dev 第一次 idle 没汇报（命中 pipeline.md 第 6 条"idle 不发内容"坑），nudge SendMessage 后补汇报
- **Review phase r1（对抗 1 轮）**：java-reviewer sonnet + ts-reviewer sonnet + llm-provider-compat-reviewer sonnet + database-reviewer sonnet 4 并行；3 个 silent idle 没 SendMessage（主会话读 /tmp/review-\*.md 取 verdict）；4 reviewer 全 PASS 0 blocker，**双 reviewer 重合发现 hardcoded ARIA id → 升 r2 mandatory fix**
- **r2 mandatory fix bundle**：be-dev (2 item) + fe-dev (3 item) 并行，全部 DONE 无 regression
- **Phase Final**：mvn test 三件套全绿 → **真活 curl smoke 发现 BLOCKER**（AgentService.updateAgent partial-update 漏 setThinkingVisible call）→ be-dev 1 行 fix + 4 行 NOTE 注释 + 2175/0/0/117 baseline 持平 → 重启 server re-curl PUT true/false 都 roundtrip verified

**关键学习**：4 reviewer 全过的代码在 Phase Final 真活 curl 仍发现 silent failure (service partial-update missing setter)，**这正是 [`java.md` footgun #6b](`.claude/rules/java.md`) 真活 curl smoke 必跑的存在意义**。单测 + Jackson roundtrip + grep 字段名对照都覆盖不到这种 "BE 接收字段但 service 层不持久化"。Nits-followup 已加 N11 建议补 AgentControllerIT 防回归。

## Verification

```bash
# BE 全跨模块 test（baseline 2162 → 现 2175，+13 新测试）
$ mvn -pl skillforge-core,skillforge-server -am test
Tests run: 2175, Failures: 0, Errors: 0, Skipped: 117 → BUILD SUCCESS

# FE tsc + vitest + vite build
$ cd skillforge-dashboard && npx tsc --noEmit  # exit 0
$ npx vitest run src/components/__tests__/ReasoningPanel.test.tsx src/hooks/__tests__/useChatWsEventHandler.test.ts
Test Files  2 passed (2)  Tests  14 passed (14)
$ npx vite build  # exit 0, dist/index-*.js 4068 kB

# 真活 curl smoke（Phase Final 救 BLOCKER 的关键）
$ TOKEN=cd5b2ed6b92348069e749790c559e92e
$ curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/api/agents?userId=1' | jq '.[0] | keys' | grep thinkingVisible
"thinkingVisible"   # ✓ 字段存在
$ jq '.thinkingVisible = true' /tmp/agent2-orig.json | curl -X PUT --data-binary @- 'http://localhost:8080/api/agents/2'  # HTTP 200
$ curl -s 'http://localhost:8080/api/agents/2' | jq '.thinkingVisible'  # → true (持久化 verified)
$ # PUT false 同样 verified
```

## MVP 不做（已折叠到 [nits-followup](/tmp/nits-followup-chat-reasoning-panel.md)）

- Q5 hover tooltip 显示 reasoning_tokens（需 `LlmResponse.Usage.reasoningTokens` + UsageNormalizer 解析 `completion_tokens_details.reasoning_tokens` + WS payload 携带）— 独立 follow-up
- Q6 历史消息无秒数显示 `Thought ▾`（无 wall-clock 时序数据）— accepted MVP limitation
- Claude extended thinking content block 接入 — 既有 gap，另开需求
- AgentService.updateAgent partial-update PUT 无法 reset thinkingVisible 为 null — N6+N11 留 backlog；FE Switch 当前只产生 true/false 所以 UX 上不触发
- AgentControllerIT 集成测试覆盖 thinkingVisible PUT 防回归 — N11 backlog
- BE Jackson PUT response systemPrompt 含 literal LF（pre-existing JSON spec 违反）— N12 backlog
