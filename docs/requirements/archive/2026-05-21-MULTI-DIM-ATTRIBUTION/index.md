# MULTI-DIM-ATTRIBUTION — 飞轮多维归因

---
id: MULTI-DIM-ATTRIBUTION
mode: full
status: active
priority: P2
risk: Medium
created: 2026-05-20
updated: 2026-05-20
---

## User Request

> "一定是失败的session里面提取问题吗？"
> "按照 Pipeline 直接开发吧"

复盘 Optimization Loop dogfood：

1. **当前 attribution 只看 outcome=failure** — `AttributionDispatcherService` filter cluster `WHERE outcome='failure'`，忽略大量其它优化机会
2. **2 个 5/19 Design Agent 空壳 error session 完全没 annotate** —— `message_count=0 / token=0`，session-annotator 看到没东西可标直接 skip → 这些"基础设施失败"信号被吞了

实际可优化空间远不止 failure：

| 信号 | 当前 | 应做 |
|---|---|---|
| `outcome=success + high_token=true` | 忽略 | 成本优化 candidate |
| `outcome=success + multi_turn=true 且 turn>N` | 忽略 | 效率优化（让对话更短）|
| 慢 session (avg_latency > P95) | 没标 | 性能优化 |
| skill 失败率高但整体 outcome=success | 忽略 | skill 改进 candidate |
| **基础设施失败**（0-message error session, LLM provider 401 / network timeout）| **annotator 直接 skip** | 应该标 `outcome=infrastructure_failure` 让 attribution 知道是 infra 不是 skill bug |

## Goals (待 Plan phase 拍板细化)

- **G1**：扩展 attribution 输入维度，不只 outcome=failure，加 cost / efficiency / quality / infrastructure_failure 至少 1-2 维（具体哪些维 Plan 阶段拍）
- **G2**：修 annotator 0-message session skip bug，empty error session 至少标 `outcome=infrastructure_failure / surface=other` 让下游有信号
- **G3**：attribution-curator agent prompt 扩展处理多维 attribution case（不只 failure 的 reject/approve 模式）

## Out of Scope（明确不做）

- FE Insight Loop UI 加新 ENTRY 节点（cost / latency entry）—— 留后续独立立项 FE-DIM-ENTRY-NODES
- 多维 attribution 的 cost dashboard / latency dashboard（独立 P3 backlog）
- 实时 alert（multi-dim 触发阈值 → 推送 WS）
- 跨 tenant aggregation（待 USER-SSO-MULTITENANT 落地后再讨论）

## Implementation Decisions (前置拍板，Plan 时可调)

| 决策 | 当前倾向 | 备注 |
|---|---|---|
| 是否改 schema | **避免**（用现有 t_session_annotation 的 annotation_type 字段扩值即可）| 可加 cost_high / latency_high / efficiency_low / infrastructure_failure 等新 annotation_value，不需新表 |
| Cluster signature 是否扩展 | **倾向扩** | 当前 signature `failure\|surface\|tool\|agent`，可改 `<outcome-class>\|surface\|tool\|agent`，其中 outcome-class ∈ {failure, cost_high, efficiency_low, infrastructure_failure} |
| Dispatcher cooldown 复用 | 是 | 24h cooldown 机制对多维 cluster 一样适用 |
| 改 agent 还是新建 agent | **改 attribution-curator system_prompt** | 一个 agent 处理多维 cluster，prompt 加 case 例子区分 attribution 推理 |
| BE 改动模块 | `skillforge-server`（AttributionDispatcherService + SessionAnnotationSignalService + Bootstrap agent prompt）| 不动 core 7+1 BE |
| 测试范围 | Repo 加 unit test + 1-2 dogfood end-to-end (manual trigger cron 后看新维 cluster + new OptEvent stage='proposal_pending') | 用现有 `/api/attribution/admin/trigger-dispatch` smoke test |

## Iron Law 自检（Plan 后再 finalize）

- ✅ 核心 7+1 BE 不触碰（AttributionDispatcherService + SessionAnnotationSignalService 都不在核心清单）
- ✅ 无 schema migration（用现有列 + 扩 annotation_value 字符串集合）
- 🟡 `t_session_pattern.signature` 写入逻辑变 → 老 pattern 不影响（不动旧 row），但新 pattern 用新 signature 格式 → 对账 / FE 解析签名需兼容
- ✅ 无 @Transactional 既有路径改动
- ✅ Jackson 契约 footgun #6 不触碰（不改 DTO）

## 候选 phase 拆分（Plan 阶段细化）

Phase A — annotator 扩展 + 0-message session fix（基础信号入口）  
Phase B — cluster signature 扩展（聚类按多维 outcome-class 分组）  
Phase C — dispatcher filter 扩展（按所有可优化 outcome-class 选 cluster）  
Phase D — curator prompt 升级（处理多维 attribution case，输出 proposal）  
Phase E — 真活 e2e dogfood 验证

A 跟 B/C/D 可独立 ship。E 是必须最后做。Plan 决定拆几个 commit。

## Pipeline

Full 档（schema 边界 + 协议 + 跨模块）—— 走 Full 流程：

- TeamCreate `flywheel-multi-dim-attr`
- **Plan phase enabled**（多种合理实现方向 — annotator 该新增哪些 type / cluster signature 怎么改 / curator prompt 怎么写）
- BE-Dev Opus 单 dev（pure BE 改动）
- java-reviewer + code-reviewer Sonnet 对抗循环最多 2 轮
- Judge (主会话 Opus) 仲裁
- Phase Final mvn test + 真活 curl + commit

## 验证

- `mvn -pl skillforge-server -am test` BUILD SUCCESS + 新 test PASS
- BE 重启 + manual trigger session-annotator + dispatcher cron
- 验证：
  - 5/19 那 2 个 0-message Design Agent session **被标注**（之前 annotate=0）
  - 现有 high_token + multi_turn 等信号 cluster 出现新 OptEvent (stage=proposal_pending)
  - `/api/flywheel/runs?hideTerminal=false` 返结果含非 failure outcome cluster 的 OptEvent
- Insight Loop panel sidebar 显新 run（非 5/15 stale pattern 的）
