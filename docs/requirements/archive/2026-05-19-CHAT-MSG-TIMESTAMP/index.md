# CHAT-MSG-TIMESTAMP

> **类型**: Lite 需求包（仅 index.md，无独立 PRD / tech-design）
> **完成日期**: 2026-05-19
> **Pipeline**: Full（触碰 `SessionService.java` 核心文件红灯触发；实际改动是 pure additive audit 字段透传）
> **关联**: 无前置需求；ad-hoc dashboard UX 改善

---

## User Request

> "chat 页面当中 用户的 message、agent 的回复都没有具体的时间。我想在页面上加上。"
> 后续澄清：**HH:MM:SS** 格式 + **hover bubble 才显示**

## Acceptance

- 用户消息 / agent 回复气泡 hover 时**右上角显示 HH:MM:SS**（24h，浏览器本地时区）
- 默认 opacity:0 隐藏但**占位保留**（不抢布局）
- 显示的时间来源是**服务器持久化 row 的 `t_session_message.created_at`**（不是浏览器时钟，刷新页面历史消息仍有正确时间）
- 键盘 / 触摸用户也能看到（`:focus-within` 兜底，A11y）

## Implementation Decisions

| 决策 | 选择 | 理由 |
|---|---|---|
| 格式 | HH:MM:SS（24h locale-independent，`hour12: false`）| 用户调试场景需要秒精度；24h 避免 en-US AM/PM |
| 显示策略 | hover + focus-within reveal（opacity 0→1 + 150ms transition）| 不抢视觉空间；layout space 保留避免 jump；A11y 兼顾 |
| 时区 | 浏览器本地（`toLocaleTimeString`，BE 推 ISO-8601 UTC）| 用户想看"在我这是几点"的常规 chat UX |
| BE→FE 通路 | `SessionMessageDto.createdAt` (REST history) + `messageAppended`/`messagesSnapshot` envelope (WS push)| 两路覆盖：刷新有 row.createdAt，实时有 broadcast timestamp（sub-ms 等价） |
| WS envelope createdAt 语义 | broadcast `Instant.now()` 不是 row.createdAt | per-message timestamp 要 ripple `ChatEventBroadcaster` 接口，scope 外；秒级显示精度等价。`messagesSnapshot` 单 envelope 同理，FE 走 REST history fallback |
| `created_at` 列分类 | audit（per [identity-column-on-rewrite.md](../../../../.claude/rules/identity-column-on-rewrite.md)）| 不需 rewrite preserve 逻辑（rewrite=新建语义符合现状） |

## Implementation Notes

**BE (4 files)**:
- `dto/SessionMessageDto.java` — 加 `Instant createdAt` 10th 字段，3 个 backward-compat 构造器（9/8/5-arg）默认 null
- `service/SessionService.java` — `StoredMessage` record 加 9th 字段，`toStoredMessages` 填 entity.getCreatedAt()，`getFullHistoryDtos` 透传到 DTO；legacy CLOB 路径留 null
- `websocket/ChatWebSocketHandler.java` — `messageAppended` + `messagesSnapshot` payload 加 envelope `createdAt: Instant.now()`，注释诚实标注语义
- `test/dto/SessionMessageDtoTest.java` — Jackson roundtrip 6 cases（per [java.md footgun #6](../../../../.claude/rules/java.md)）：ISO-8601 + camelCase key + Instant 保留 + null roundtrip + 3 构造器默认

**FE (5 files)**:
- `types/messages.ts` — `RawMessage.createdAt?: string` 显式字段 + JSDoc
- `hooks/useChatMessages.ts` — `normalizeMessages` 在 4 个 push site (user/assistant/summary/ask_user&confirmation) 透传 `m.createdAt` → `ChatMessage.timestamp`
- `components/ChatWindow.tsx` — `formatTime` 改 `{hour,minute,second:'2-digit',hour12:false}`；Invalid-Date guard 返 ''
- `index.css` — `.msg-time { opacity:0; transition: opacity 150ms ease }`，merged selector `.msg:hover .msg-time, .msg:focus-within .msg-time { opacity: 1 }`
- `hooks/__tests__/useChatMessages.test.ts`（new）— 5 cases：user/assistant happy path + undefined fallback + non-string defense + ask_user

**Iron Law audit (PASS)**:
- ✅ `persistence-shape-invariant.md` — 0 触发；不动 Message/ContentBlock/ChatService 持久化/Engine 内存装配/Compactor
- ✅ `identity-column-on-rewrite.md` — `created_at` 为 audit 列，**不**加 snapshot/patch preserve 逻辑（设计如此）
- ✅ `java.md footgun #6` — roundtrip 测 + 真活 curl 双重证 FE-BE Jackson 契约
- ✅ `java.md footgun #1` — 测试 ObjectMapper 显式 `registerModule(new JavaTimeModule())` + disable timestamps
- ✅ 无 @Transactional 改动 / 无 schema migration / 无新依赖

## Pipeline Notes

- **Full pipeline 1 轮 r1**：BE-Dev Opus + FE-Dev Opus 并行 → java-reviewer Sonnet PASS 0 blocker / 3 warning + typescript-reviewer Sonnet PASS 0 blocker / 3 warning → Judge (主会话 Opus) 仲裁 → r2 1 round mandatory fix bundle（BE W1 注释诚实 + FE W3 `:focus-within` A11y）
- **529 Overloaded incident**：fe-dev 中途撞 Anthropic Opus 网络过载，SendMessage nudge retry 成功；记账 backlog 候选 "Opus reviewer 大 diff 降级 Sonnet" 选项已在 SYSTEM-AGENT-TYPING Phase 2 学过 — 本次 diff 小（FE 134 行）所以照样 Opus 跑通
- **Other warnings ratified (folded to nits-followup)**: BE W2 envelope-snapshot tradeoff / BE W3 test ObjectMapper 非 Spring-managed / FE W1 spread + index signature passthrough / FE W2 h23/h24 边界（极低概率）

## Verification

- `mvn -pl skillforge-server -am test` Tests run **1853** / Failures 0 / Errors 0 / Skipped 104 → BUILD SUCCESS (baseline 1847 + 6 新 DTO roundtrip)
- `cd skillforge-dashboard && npx tsc --noEmit` EXIT=0 (pre-existing 3 tsc errors on main 与本期无关)
- `cd skillforge-dashboard && npx vitest run src/hooks/__tests__/useChatMessages.test.ts` 5/5 PASS
- **BE 真活 curl 验**：`/api/chat/sessions/<id>/messages` 返 row 含 `'createdAt': '2026-05-19T10:44:38.505346Z'`（ISO-8601 + camelCase + 微秒），user / assistant 双方都有
- `git diff` 7 文件 / +106 / -13 + 2 新测试文件

## MVP 不做

- per-message createdAt on `messagesSnapshot`（需 ripple `ChatEventBroadcaster` 接口，scope 外；FE 走 REST history fallback 覆盖）
- 相对时间（"2 min ago"）+ tooltip 全时间 — 当前需求只要 HH:MM:SS hover
- 跨天显示 MM/DD HH:MM 智能切换 — 同上
- Spring-managed `@WebMvcTest` roundtrip — 当前测试用 mirror ObjectMapper 配置已足够 cover 序列化语义
