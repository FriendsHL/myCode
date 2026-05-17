# SYSTEM-AGENT-TYPING MRD

---
id: SYSTEM-AGENT-TYPING
status: design-draft
created: 2026-05-16
---

## 痛点

V1-V5 累计 5 个 system agent（owner_id=0 or 1 / is_public=TRUE 模式）跟 user-created agent 共表共界面：

### 现状 t_agent (2026-05-16 grep)

| id | name | owner_id | is_public | 类型 |
|---|---|---|---|---|
| 1 | Design Agent | 1 | TRUE | **user** 创建 |
| 2 | Code Agent | 1 | TRUE | **user** 创建 |
| 3 | Main Assistant | 1 | TRUE | **user** 创建 |
| 4 | Session Analyzer | 1 | TRUE | **user** 创建 |
| 5 | Research Agent | 1 | TRUE | **user** 创建 |
| 6 | memory-curator | NULL | TRUE | **system** (V69) |
| 7 | session-annotator | 1 | TRUE | **system** (V75) |
| 8 | metrics-collector | 1 | TRUE | **system** (V79) |
| 9 | attribution-curator | 1 | TRUE | **system** (V81) |
| 10 | user-simulator | 1 | TRUE | **system** (V85) |

**System agent 跟 user agent 在 owner_id / is_public 两个字段上无法可靠区分** (owner_id=1 用户也可以创建公开 agent)。

### 三个具体痛点

#### 1. AgentList 视觉混淆

dashboard `/agents` page 列 10 个 agent，用户每次找自己的 Main Assistant 都要在 system agent 中翻找。

#### 2. 编辑安全性

用户可以打开 `attribution-curator` AgentDrawer 改它的 system_prompt / 删 tool_id / disable / 删除整 agent。一旦改动可能让飞轮链路 (V3 attribution → candidate 生成) 直接坏掉。Bootstrap 启动时虽会 idempotent 修，但运行期手改可能 race。

#### 3. System agent 跑出的 session 看不到 / 不集中

5 个 system agent 各自跑出 session 散落不同 page:
- session-annotator session → `/sessions` (origin='production', 但被 cron 触发的 user_id=0 SYSTEM session)
- attribution-curator session → 通过 `/insights/optimization-events` drill down 才能看
- metrics-collector session → embed in canary metric
- memory-curator session → embed in memory consolidation
- user-simulator session → `/insights/dynamic-sim` 跳 ChatWindow

**没集中入口看"过去 7 天哪个 cron 跑了几次 / 失败几次 / 当前哪个 system agent active"**。运维痛。

## 用户场景

### 场景 A: stakeholder demo 一眼看清平台 agent 还是自己的 agent

```
用户开 dashboard "你这平台有几个 agent？"
现状: AgentList 10 个，混杂；解释 5 分钟才说清楚哪 5 个是 system

期望: 默认看到 5 个 user agent 一目了然；toggle "Show system agents" 再
展开看 cron 跑的那 5 个
```

### 场景 B: 用户误改 system agent 配置

```
用户翻 AgentList 觉得 attribution-curator system_prompt 太长想精简
→ 改成 30 字 简单 prompt
→ 下次 cron 跑发现 curator 不会发 ProposeOptimization 了
→ 半天找不到原因
```

期望: AgentDrawer 检测 system agent 时锁关键字段 + 显式警告 "System agent
managed by V81 bootstrap，编辑请先 disable bootstrap"。

### 场景 C: 运维想看哪些 cron 跑得对

```
用户想知道: 过去 7 天:
- session-annotator 跑了多少次？annotation 增加多少？
- attribution-curator 跑了几次？proposal 写了几条？
- metrics-collector cron 漏跑没？

现状: 要分别去 ScheduledTask page 看 last_run / 分别看 t_session_annotation
增量 / 分别看 t_optimization_event 跨表 join。
```

期望: 集中 system agent 监控面板，5 个 cron 一行 + last_run_at + 7d 触发计数
+ 7d 产出实体计数 (annotations / proposals / metric snapshots / consolidations /
trials)。

## 不在 MRD 范围内

- **不重命名 / 删除已有 agent**（只加字段不改 owner）
- **不动 Bootstrap idempotent 逻辑**（V69/V75/V79/V81/V85 idempotent path 不动）
- **不破 V1-V5 现有 system agent cron 路径**
- **不动 Iron Law 核心 7+1 文件**

## 用户 quote / 决策来源

- 2026-05-16 用户原话: "用户使用的几个 agent 都是我之前创建的几个，比如 main
  agent design agent research agent session analysis，还有一些是平台相关的
  agent 偏向 system agent，然后我们对于 system agent 的 session 没有明显的
  展示。都是从其他通道进去的。所以我们的 agent 是不是应该进行一些相关的
  区分，system agent 可以通过管理端或者 tool 工具进行配置，但是可能不能
  对话，用户可以看相关的 chat 信息，session 信息。"
- 推荐"列到需求里面"
