# P10 技术方案

---
id: P10
status: done
prd: ./prd.md
risk: Full
created: 2026-04-28
updated: 2026-05-07
---

## TL;DR

新增一个小型 command registry，前端用于补全，后端用于执行。MVP 固定四条命令。

## 关键决策

| 决策 | 理由 | 替代方案 |
| --- | --- | --- |
| 只做四条命令 | 覆盖当前主要价值，范围可控。 | 自定义命令推迟。 |
| `/compact` 只触发 full compact | 避免依赖 P9-4。 | partial compact 等 P9-4。 |
| `/model` 只作用于 session | 避免误改持久化 Agent 配置。 | Agent 配置更新由其他能力处理。 |

## 架构

- 前端 command parser + completion menu。
- 后端 command execution endpoint 或复用现有 endpoint。
- command registry 维护 metadata 和执行映射。

## 后端改动

- 新增 command execution API 或 service。
- 将 `/new`、`/compact`、`/clear`、`/model` 路由到现有服务。

## 命令语义

| 命令 | 行为 | 约束 |
| --- | --- | --- |
| `/new` | 新建 session。 | 旧 ToDo 原文范围。 |
| `/compact` | 触发 full compact。 | 不等 P9-4 就绪。 |
| `/clear` | 清空当前对话显示。 | 旧 ToDo 原文范围。 |
| `/model` | 改 session 级临时模型。 | 不持久化到 agent 配置。 |

## 前端改动

- 增加 slash 检测。
- 增加命令补全 popup。
- 回车执行选中命令。
- 显示 loading / error 状态。

## 数据模型 / Migration

- MVP 预计不需要 migration。

## 错误处理 / 安全

- 校验命令参数。
- `/model` 只能使用已配置 model options。
- 不直接向用户暴露内部 compaction 错误。

## 实施计划

- [x] Full Pipeline 实施（2026-05-07，r1+r2 对抗循环 PASS）。
- [x] 前端 parser / completion（CommandPopup + ChatInput 改造 + COMMAND_NAME_REGEX 仅首字符 / 触发）。
- [x] 后端 execution（SlashCommandService + 8 handler + REST + ChannelSessionRouter 拦截）。
- [x] 浏览器验证（Phase Final API e2e：7 命令真过 curl，含 INV-15 / B2 fire-and-forget 0.008s）。

## 测试计划

- [x] Parser / popup unit tests（52 个 FE tests：fuzzy match / 键盘 / displayMode 三分支 / INV-15）。
- [x] Command endpoint tests（57 个 BE tests + IT 真过 Jackson + LocalValidatorFactoryBean）。
- [x] Browser command workflow checks（7 命令 curl e2e 全过 + DB 写入 runtime_model_override 验证）。

## 风险

- `/compact` 触碰核心 context 行为 → 用 chatLoopExecutor.submit 异步触发，handler 立即返 toast；CompactionService 的 lockFor + fullCompactInFlight 提供 race-safe（r2 修复）。
- 不同渠道命令语义漂移 → `SlashCommandService` 共用业务逻辑；`ChannelSessionRouter` BE 拦截 + dashboard FE popup 共用同一 REST endpoint，避免漂移（Q3 (b) 双端共用 service）。
- 斜杠命令替代已存在的 UI 路径不是新能力 → 主要价值是 channel 端用户没 GUI 的便利；dashboard popup 是 UX 增强。

## 评审记录

- 2026-04-29 design-draft：因为 /compact 触碰核心 compaction 路径，实施前需要 Full Pipeline。
- 2026-05-07 实施完成（commit 待定），Full Pipeline r1+r2 两轮对抗审查 PASS（BE / FE reviewer 两阶段评审 + team-lead 仲裁）。

## Ratified Decisions（2026-05-07 实施前用户 ratify）

实施前与用户对齐 9 项决策，最终落地：

| # | 决策点 | 选择 | 实现位置 |
|---|---|---|---|
| Q1 | `/clear` 语义 | **删除合并到 `/new`**（用户 ratify "/clear 跟 /new 差不多") | — |
| Q2 | `/model` 实现 | session 内切换，新建走默认（schema 字段 `t_session.runtime_model_override`） | V60 migration + ChatService.runLoop / completeConfirmedTool 两处覆盖 |
| Q3 | 命令解析位置 | **(b) 双端共用 service**：dashboard FE popup → REST + channel BE `ChannelSessionRouter` 拦截 → 共用 `SlashCommandService` | `controller/SlashCommandController` + `channel/router/ChannelSessionRouter` |
| Q4 | `/new` 后行为 | dashboard 跳新 session URL；channel close-and-create `t_channel_conversation` 映射 | `ChannelConversationRebindService` (r2 抽 @Transactional) |
| Q5 | popup 触发条件 | (a) **仅 input 首字符 `/` 触发** | `COMMAND_NAME_REGEX = /^\/[A-Za-z]*$/` |
| Q6 | 命令清单 | 8 条：/new /compact /model /models /skill /tool /context /help（用户中途增加 /skill /tool /context /help /models 5 条只读命令） | 8 个 handler |
| Tool | Tool 注册 | `SlashCommandHandler` 接口 + Spring `List<SlashCommandHandler>` 注入构造 SlashCommandService | `service/command/SlashCommandService.java` |
| /context | 显示内容 | total / window / 占比 / 按 type 分布 / top 5 大 message | 用现有 `TokenEstimator` (CTX-1) + `ContextBreakdownService` |
| /help | 注册逻辑 | **registry-based**（不硬编码命令名清单，自动列出注册 handler） | HelpCommandHandler 用 `ObjectProvider<SlashCommandService>` 懒注入避免循环依赖 |

**MVP 不做**（V2）：
- 自定义命令注册 / 命令参数 fuzzy 补全（如 `/model g` → 候选 gpt-4o, gemini）
- chat header model chip 显示当前 runtime_model_override（FE 已 wire `refreshCompactStats` 让未来 chip 自动接入；现在用 toast 替代）
- `Tool.isSystem()` 接口（当前 ToolCommandHandler.SYSTEM_TOOL_NAMES 是 best-effort 硬清单，cosmetic only）
- 专用 compactExecutor bean（当前 `/compact` 共享 chatLoopExecutor 与 P12 ScheduledTaskExecutor 同 pattern；高负载时 RejectedExecutionException 转 "服务器繁忙" 错误）
- `/agent` `/session` `/cancel` `/branch` `/cost` 等 5 个边缘价值命令

## r1 → r2 对抗审查 fix 记录

**r1 reviewer（BE-1 Sonnet / FE-1 Sonnet）**：
- BE r1：**2 blocker** + 2 warning：
  - **B1 (CRITICAL silent failure)**: FE 发 `{command, args}` body 但 BE `ExecuteRequest` 用 `commandLine` 字段 → Jackson 静默 deserialize 成 null → controller 对每个 FE 真实调用都返 400。测试通过是因为 BE test 直接 build ExecuteRequest 绕过 Jackson + FE test mock api.post 不验证真发请求
  - **B2**: /compact 同步阻塞 5-30s，brief §9 写默认 fire-and-forget
  - W1 ChannelSessionRouter.rebindChannelConversation 非原子（close + insert 中间无 @Transactional）
  - W2 @Valid 缺失（@NotBlank/@NotNull annotations 无效 noop）
- FE r1：0 blocker + 2 warning（slashCommandConfig 内联 object 破坏 React.memo / refreshCompactStats 注释不准）

**Judge 仲裁（team-lead 直接做）**：
- must-fix-r2: BE B1/B2/W1/W2 + FE W1/W2
- accept-as-is: BE C1 t_model_provider（用 LlmProperties YAML 替代）/ BE C2 SYSTEM_TOOL_NAMES cosmetic / BE C5 broadcaster mock noise / FE C1 model header chip（V2）

**r2 reviewer 复审**：BE PASS（4 fix 全 ✓ + 新增 IT 真过 Jackson + LocalValidatorFactoryBean）/ FE PASS（2 fix 全 ✓ + 顺手删 userId params duplication）。BE r2 reviewer 新发现 W3（chatLoopExecutor 共享潜在线程争用）但是 P12 已有 pattern carry forward，不算 r2 regression，V2 加 dedicated compactExecutor。
