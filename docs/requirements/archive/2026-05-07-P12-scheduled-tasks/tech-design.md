# P12 技术方案

---
id: P12
status: done
prd: ./prd.md
risk: Full
created: 2026-04-28
updated: 2026-05-07
---

## TL;DR

使用 Spring `ThreadPoolTaskScheduler` 实现单机动态 user schedules，并持久化到 PostgreSQL。system jobs 和高级可靠性推迟到 V2。

## 关键决策

| 决策 | 理由 | 替代方案 |
| --- | --- | --- |
| 使用 `ThreadPoolTaskScheduler` | 当前单机部署下简单、足够。 | Quartz 等集群调度方案推迟。 |
| 只做 user tasks | 降低范围，不混入现有 system jobs。 | SystemJobRegistry 进入 V2。 |
| 只支持 `skip-if-running` | 首版最可预测。 | queue / parallel 进入 V2。 |

## 架构

- `ScheduledTaskService` 负责 CRUD 和持久化。
- `UserTaskScheduler` 负责注册、注销和触发任务。
- `ScheduledTaskRun` 记录执行结果。
- Dashboard `/schedules` 管理任务和执行历史。

## 后端改动

- 新增 scheduled task / run entity 和 repository。
- 新增 scheduler service 和 startup registration。
- 新增 REST API。
- 集成 `ChatService.chatAsync`。

## 前端改动

- 新增 schedules 页面。
- 新增任务列表、编辑 drawer、cron preview、enable toggle、manual trigger、run history timeline。

## 数据模型 / Migration

- `t_scheduled_task`
- `t_scheduled_task_run`

`t_scheduled_task` 字段：

- `id`
- `name`
- `cron_expr`
- `one_shot_at`
- `timezone`
- `agent_id`
- `prompt_template`
- `channel_target`
- `enabled`
- `concurrency_policy`
- `next_fire_at`
- `last_fire_at`
- `status`

`t_scheduled_task_run` 字段：

- `task_id`
- `triggered_at`
- `finished_at`
- `status`
- `error_message`
- `triggered_session_id`

## 调度语义

- 应用启动时从 DB 全量注册 enabled task。
- CRUD 修改后同步 schedule / unschedule。
- cron 使用任务自己的 timezone。
- one-shot 执行成功后不再重复触发。
- shutdown 时优雅等待正在执行的 user task。
- 首版只做 user 型任务；system job 仍保留现有 `@Scheduled`。

## 错误处理 / 安全

- 校验 cron / timezone。
- 执行失败记录脱敏后的错误信息。
- 实现前必须明确 identity / ownership 模型。

## 实施计划

- [x] 完成前置决策（P12-PRE 2026-05-04 闭环）。
- [x] Full Pipeline 实施（2026-05-07，r1+r2 对抗循环 PASS）。
- [x] 实现后端 schema / service / scheduler / 5 Tool / channel push。
- [x] 实现 dashboard `/schedules` 页面。
- [x] 验证 startup registration / manual trigger / cron 互转 / one-shot 自动 disabled。

## 测试计划

- [x] cron next-fire 计算（`UserTaskSchedulerTest`）。
- [x] skip-if-running 行为（`UserTaskSchedulerTest`）。
- [x] CRUD registration update 行为（`UserTaskSchedulerTest`、`ScheduledTaskServiceTest`）。
- [x] run history 持久化（`ScheduledTaskExecutorTest`、`ScheduledTaskServiceTest`）。
- [x] dashboard 浏览器 workflow 检查（Phase Final 由用户执行）。

## 风险

- 时区 / DST bug → INV-8 timezone 校验 + `CronTrigger(cronExpr, TimeZone.getTimeZone(tz))` 落地。
- 长时间运行的 Agent session 与新触发重叠 → INV-4 skip-if-running（`runningTaskIds` ConcurrentHashMap.newKeySet）。
- 身份和 cost accounting 边界不清 → ratify Q1：`creator_user_id` 作为 task ownership + 触发 session.owner_id；cost accounting 沿用现有 `pages/ModelUsage` 不单独区分 schedule 触发 vs 用户触发（P12-PRE 决策）。
- AFTER_COMMIT 监听器：`@TransactionalEventListener(AFTER_COMMIT)` 防 unschedule 早于 commit 的 race（r1+r2 修复）。
- channel push 失败永久污染 `runningTaskIds`：`catch (Throwable)` + INV-9 严格化（r2 修复）。

## 评审记录

- 2026-04-29 design-draft：等 P12-PRE 前置决策。
- 2026-05-04 P12-PRE 闭环 → 解锁。
- 2026-05-07 实施完成（commit 待定），Full Pipeline r1 + r2 两轮对抗审查 PASS（BE / FE reviewer 两阶段评审 + team-lead 仲裁）。

## Ratified Decisions（2026-05-07 实施前用户 ratify）

实施前与用户对齐 11 项决策，最终落地：

| # | 决策点 | 选择 | 实现位置 |
|---|---|---|---|
| Q1 | 触发 session 的 owner_id | task `creator_user_id` 复制到 session.owner_id | `ScheduledTaskExecutor.openSessionForTask` |
| Q2 | 每次 cron 触发 new vs reuse | 用户在 task 上选 `session_mode` enum | `t_scheduled_task.session_mode` + `reused_session_id` 字段 |
| Q2-extra | reuse 撞 token 窗口 | 走现有 CompactionService（不写额外逻辑） | 复用现有 |
| Q3.1 | channel_target wire 格式 | nested object `{channelType, channelId}`（camelCase）+ entity TEXT 存 JSON string | DTO `Map<String, Object>` + service ObjectMapper 序列化（参考 EvalController 模式） |
| Q3.2 | channel 推什么 | 只推 final assistant message 文本 | `ScheduledTaskExecutor.composeChannelMessage` |
| Q3.3 | 失败 / ask_user 推什么 | 失败 → `⚠️ 定时任务【{name}】失败：{error}`；ask_user → `⚠️ 定时任务【{name}】暂停，需要人工输入。查看：{dashboardUrl}/schedules/{id}` | 同上 |
| Q4 (a) | one-shot 完成处理 | 自动 `enabled=false / status=completed`，task 行保留（dashboard 显示已完成） | `ScheduledTaskExecutor.handleOneShotCompletion` |
| Q4-互转 | cron ↔ one-shot 互转 | DB CHECK XOR + UpdateTool 允许同 patch 清旧字段 + 设新字段 + reschedule | `V59__create_scheduled_tasks.sql` + `ScheduledTaskService.applyTriggerFields` |
| Q5-tz | 默认 timezone | `Asia/Shanghai`（`application.yml` `app.dashboard-url` 配套配置化） | DB default + `ScheduledTaskService.validateTimezone` |
| Q5-tpl | prompt_template 变量替换 | MVP 纯文本不做 | — |
| Tool | Agent 操作 Tool 集 | 5 件套 silent + owner 隔离：CreateScheduledTask / UpdateScheduledTask / DeleteScheduledTask / ListScheduledTasks / GetScheduledTask；agentId 缺省回退当前 session.agentId | `tool/scheduling/*.java` |

**MVP 不做**（V2）：SystemJobRegistry / queue+parallel / 告警推送 / admin 权限 / cron 跨实例分布式 / prompt 变量替换 / cron next-5-fires preview（FE r1 标 blocker → team-lead ratify 简化为"raw cron + nextFireAt"，避免 cronstrue 新依赖）/ listRuns 非对齐 offset 分页 / channel push 异步化（current sync ~10s 阻塞 acceptable per brief）。

## r1 → r2 对抗审查 fix 记录

**r1 reviewer（BE-1 Sonnet / FE-1 Sonnet）**：
- BE r1：6 warnings（W1 startup missed one-shot / W2 listRuns 分页 / W3 @TransactionalEventListener / W4 dashboard URL 相对 / W5 catch Exception not Throwable / W6 sync listener latency）
- FE r1：2 blocker + 2 warning（B-1 missing userId / B-2 cron next-5-fires / W-1 共享 spinner / W-2 return type 错）

**Judge 仲裁（team-lead 直接做，judge agent 超时）**：
- must-fix-r2: BE W1/W3/W4/W5 + FE B-1/W-1/W-2
- accept-as-is: FE B-2（spec 矛盾，task description 优先）+ BE W6（brief ack）+ BE W2（FE 不触发）

**r2 reviewer 复审**：BE PASS / FE PASS，0 new blocker / 0 new warning。
