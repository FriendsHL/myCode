# SKILL-EVOLVE-LOOP — 技术方案

---
id: SKILL-EVOLVE-LOOP
status: delivered
prd: ./prd.md
risk: Full
created: 2026-05-08
updated: 2026-05-08
delivered: 2026-05-08
---

## TL;DR

5 phase 整合：3 个 @Scheduled cron 错峰（3 点 extract / 周一 4 点 evaluate / 周二 5 点 self-improve）+ V63 migration 加 `t_skill_eval_history` 表 + SkillEvolution 用 EVAL failures + 自循环 + WS push 通知 + dashboard 历史曲线。

## 错峰 Cron 安排

| 时间 | Cron | 作用 |
|---|---|---|
| 凌晨 3 点（每天）| `0 0 3 * * *` | SkillDraftScheduledExtractor — 自动 extract from session |
| 周一凌晨 4 点 | `0 0 4 * * MON` | SkillScheduledEvaluator — 周期评测所有 enabled skill |
| 周二凌晨 5 点 | `0 0 5 * * TUE` | SkillSelfImproveLoop — score 低自动 evolve → A/B → promote |

**为什么错峰**：避免同时间 LLM 调用峰值；周期评测要在 self-improve 之前（self-improve 取 history.findLatest，必须有数据）。

## 12 Invariants（关键执行语义）

| # | INV | 实现要点 |
|---|---|---|
| INV-1 | 4 个 cron 全部 yaml 开关，默认 true 但可关 | `@ConditionalOnProperty` 或 service 内部读 properties guard |
| INV-2 | 单 skill / agent 失败 → log warn 继续，不阻塞整个 cron | try/catch per item + 计数 success / failed |
| INV-3 | SkillScheduledEvaluator 跳过 7 天已评 skill | `t_skill_eval_history.findLatestBySkillId.created_at + 7 days > now` 跳过 |
| INV-4 | SkillSelfImproveLoop 取 latest_score < 60 才触发 evolve | `t_skill_eval_history` 必须有数据；如果该 skill 从未评过 → 跳过（评测端先跑） |
| INV-5 | EVAL failures 通过 agent.skillIds 反查（不依赖 EvalTaskItem.skillId 字段，因为不存在）| `t_agent.skill_ids LIKE '%skillName%'` + `JOIN t_eval_task_item ON composite_score < 60` |
| INV-6 | SkillEvolution failures 注入失败 = 0 个时 → fallback 现有 prompt（不 fail） | `if (failures.isEmpty()) prompt = currentPrompt + statisticsOnly` |
| INV-7 | A/B promote 阈值复用 SkillAbEvalService.PROMOTION_DELTA_THRESHOLD_PP=15 + PROMOTION_MIN_CANDIDATE_RATE_PP=40 | 不 fork 这两个常量 |
| INV-8 | `t_skill_eval_history` schema 含 5 维 score + triggered_by + skill_id index | 索引 idx_seh_skill_created (skill_id, created_at DESC) for findLatest |
| INV-9 | WS push `skill_auto_upgraded` 仅 promote 成功时触发 | 失败的 evolve（A/B 不过）不推；用户去 dashboard 看 evolve runs 列表 |
| INV-10 | SelfImproveLoop 监听 A/B 结果 — 不 polling，用 ApplicationEventPublisher | `SkillAbCompletedEvent` + @TransactionalEventListener(AFTER_COMMIT, REQUIRES_NEW)（参考 P11 教训）|
| INV-11 | systemUserId（cron 触发的 evolve 用）= 0L 或专门保留值 | 不能用真实用户 ID（避免 ownership 误判） |
| INV-12 | findRecentFailuresForSkill 数据量上限：每 skill 最多 5 个 failure | 防 LLM context 爆 + 重要性最高的最近 |

## 模块设计

### 新建（BE，6 个新文件）

```
skillforge-server/src/main/java/com/skillforge/server/improve/
├── SkillDraftScheduledExtractor.java   // Phase 1 cron
├── SkillScheduledEvaluator.java         // Phase 3 cron
└── SkillSelfImproveLoop.java            // Phase 5 cron + ApplicationEventListener

skillforge-server/src/main/java/com/skillforge/server/entity/
└── SkillEvalHistoryEntity.java          // V63 落表

skillforge-server/src/main/java/com/skillforge/server/repository/
└── SkillEvalHistoryRepository.java      // findLatestBySkillId / findBySkillIdOrderByCreatedAtDesc

skillforge-server/src/main/java/com/skillforge/server/event/
└── SkillAbCompletedEvent.java           // INV-10 跨 service 通信
```

### 修改（BE，6 个改动）

```
SkillEvolutionService.java               // Phase 4 — callLlmForImprovement 加 failures 注入
EvalTaskRepository.java                  // 加 findRecentFailuresForSkill 查询
SkillAbEvalService.java                  // 提取 runBaselineOnly + 完成时 publish event
SkillController.java                     // 加 POST /api/skills/{id}/evaluate + GET /eval-history
SkillEntity / dto                        // 可能加 latest_score 缓存（视性能决定）
application.yml                          // 加 4 个 yaml 开关 + 阈值配置
```

### V63 Migration

```sql
-- V63__create_skill_eval_history.sql
CREATE TABLE t_skill_eval_history (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL,
    eval_run_id VARCHAR(64),
    composite_score DOUBLE PRECISION NOT NULL,
    quality_score DOUBLE PRECISION,
    efficiency_score DOUBLE PRECISION,
    latency_score DOUBLE PRECISION,
    cost_score DOUBLE PRECISION,
    triggered_by VARCHAR(16) NOT NULL,        -- 'manual' | 'scheduled'
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_seh_triggered_by CHECK (triggered_by IN ('manual','scheduled'))
);
CREATE INDEX idx_seh_skill_created ON t_skill_eval_history(skill_id, created_at DESC);
COMMENT ON TABLE t_skill_eval_history IS 'P0 SKILL-EVOLVE-LOOP: skill EVAL 历史，self-improve 取 latest_score 判断是否触发 evolve';
```

## REST API

| Method | Path | 说明 |
|---|---|---|
| POST | `/api/skills/{id}/evaluate?userId={id}` | 单 skill 直评（不 fork），落 history triggered_by='manual'，返 composite_score |
| GET | `/api/skills/{id}/eval-history?userId={id}&limit=N` | 返历史曲线（最近 N 条 history 行） |

## Phase 拆分给 dev

### BE-1（Schema + Phase 1+2 + 改 SkillAbEvalService 暴露 baseline）— ~250-350 行
- V63 migration
- SkillEvalHistoryEntity / Repository / Service
- SkillAbEvalService.runBaselineOnly() 提取（不 fork 跑当前 skill 评测）
- SkillDraftScheduledExtractor cron + yaml + 测试
- SkillScheduledEvaluator cron + 7 天跳过 + yaml + 测试
- POST /api/skills/{id}/evaluate + GET eval-history endpoint
- application.yml 加 4 yaml 开关

### BE-2（Phase 3+4+5 — failures injection + self-improve loop + event）— ~250-300 行
- EvalTaskRepository.findRecentFailuresForSkill (skillName 通过 agent.skillIds LIKE 反查 + composite_score < 60)
- SkillEvolutionService.callLlmForImprovement 改造拼 failures 进 prompt（参考 PromptImproverService 现有 模式）
- SkillAbCompletedEvent + SkillAbEvalService 完成时 publish
- SkillSelfImproveLoop @Scheduled cron + @TransactionalEventListener(AFTER_COMMIT, REQUIRES_NEW) 监听 SkillAbCompletedEvent
- WS push skill_auto_upgraded
- 测试覆盖：取 5 / 取 0 fallback / yaml off / score < 60 trigger / score >= 60 跳过 / A/B PASS promote / A/B 失败 log

依赖：BE-1 schema + Repository / SkillAbEvalService 改造完才能开干。BE-1 完成后立刻 spawn BE-2。

### FE — ~150-200 行
- SkillList 表加 Latest Score 列（颜色 Tag）+ Trend sparkline
- SkillDetail drawer 加 Evaluation History 曲线（recharts / antd-charts，grep 项目已有依赖）
- SkillDetail drawer 加 Auto-Evolve Runs 段
- src/api 加 evaluateSkill + getEvalHistory
- WS handler skill_auto_upgraded toast + 刷新

可与 BE-1 / BE-2 并行（FE 用 brief 的 wire shape，不依赖 BE 落地）。

## 风险

- **Full 红灯**：触碰 SkillAbEvalService / SkillEvolutionService 核心 + 多 cron + 跨 service event publish
- **A/B 监听**：用 Spring event 而非 polling（参考 P11 / P12 @TransactionalEventListener AFTER_COMMIT REQUIRES_NEW 教训）
- **LLM 调用峰值**：周一 4 点同时跑所有 skill EVAL 可能撞 chatLoopExecutor 上限 → 接受 V2 加 EVAL-PARALLEL-SCENARIO
- **systemUserId**：cron 触发的 evolve 用什么 user_id（用户表有 ID 1 的 admin？还是用 0L 当 system？）→ 用 0L，加注释
- **fork 后 promote**：SkillAbEvalService 已有 promotion 逻辑（达阈值时 promote），需 verify 触发路径与新 cron 兼容

## 测试策略

- 单元测试（Mockito）：每个 cron 4 case（happy / yaml off / 跳过 / 失败继续）/ failures injection / event publish + listen
- IT：V63 migration 实跑 + cron 真触发 + history 落库
- 浏览器 e2e（Phase Final）：手动 evaluate → 看 history → 模拟 low score → trigger self-improve → A/B → promote → WS toast → dashboard 列表更新

## 实施计划

- [x] 完成 scope（用户 2026-05-08 ratify "你来安排"）
- [ ] BE-1 + FE 并行启动（schema + Phase 1/2 + dashboard）
- [ ] BE-1 schema 落地后 spawn BE-2
- [ ] r1 + r2 对抗审查
- [ ] Phase Final 真 e2e（手动 trigger 全闭环）
- [ ] commit + 归档
