# SKILL-EVOLVE-LOOP — PRD

---
id: SKILL-EVOLVE-LOOP
status: delivered
owner: youren
priority: P0
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-08
updated: 2026-05-08
delivered: 2026-05-08
---

## 摘要

打通 skill 自进化闭环 5 phase：生产 cron + 单 skill 评测 + 评测定时 + 历史表 + 优化用 failures + 自循环 cron + 通知 + dashboard 展示。

## 5 Phase 范围

### Phase 1: 生产端 — 凌晨 cron 自动 extract from session
- 新建 `SkillDraftScheduledExtractor` @Component @Scheduled `cron = "0 0 3 * * *"`
- 扫描 24h 内有新 session 的 agent
- 沿用现有 `SkillDraftService.extractFromRecentSessions(agentId, userId)`
- 沿用 `already_has_drafts` 跳过逻辑
- yaml 开关 `skillforge.skill-extraction.scheduled-enabled: true`
- 失败处理：单 agent 失败 log warn 继续（不阻塞整个 cron）

### Phase 2: 评测端 — 单 skill 直评 + history 表
- V63 migration 加 `t_skill_eval_history`（id / skill_id / eval_run_id / composite_score / quality_score / efficiency_score / latency_score / cost_score / triggered_by `manual|scheduled` / created_at）
- 新建 `POST /api/skills/{id}/evaluate` endpoint — 单 skill 直评（不 fork，跑当前 skill on agent's dataset 取 score 落 history）
- 改造 `SkillAbEvalService` 暴露 `runBaselineOnly(skillId, agentId, datasetId)` 方法（现有 runAbTest 改装提取 baseline 跑），不动 fork 流程
- FE `/api/skills/{id}/eval-history?limit=N` 返历史曲线

### Phase 3: 评测端 — 周期定时评测
- 新建 `SkillScheduledEvaluator` @Scheduled `cron = "0 0 4 * * MON"`（周一凌晨 4 点错峰避开 3 点的 extract）
- 扫所有 enabled skill
- 跳过最近 7 天内已有 history 的 skill（避免重复跑）
- 每个 skill 调 `runBaselineOnly` 跑评测落 history（triggered_by='scheduled'）
- yaml 开关 `skillforge.skill-evaluation.scheduled-enabled: true`
- 失败处理：单 skill 失败 log warn 继续

### Phase 4: 优化端 — SkillEvolution 用 EVAL failures
- 改造 `SkillEvolutionService.callLlmForImprovement(skill, currentSkillMd)` 加查询逻辑：
  - 取最近 5 个该 skill 相关失败 scenario（通过 `agent.skillIds LIKE '%skillName%'` 反查 EvalTask + 取 EvalTaskItem composite_score < 60）
  - 拼进 LLM prompt：task / attribution / agent_final_output / score / score_breakdown
  - 参考 PromptImproverService.generateCandidatePrompt 现有 failures 注入模式
- 新建 `EvalTaskRepository.findRecentFailuresForSkill(skillName, limit)` 方法
- 失败定义：`composite_score < 60` 或 `attribution != 'PASS'`
- 单测覆盖：取 5 / 取 0（无失败 fallback 现有 prompt）/ skillName fuzzy match 边界

### Phase 5: 自循环 + 通知
- 新建 `SkillSelfImproveLoop` @Component @Scheduled `cron = "0 0 5 * * TUE"`（周二凌晨 5 点错峰）
- 对每个 enabled skill：
  ```
  latest_score = t_skill_eval_history.findLatestBySkillId(skillId)
  if latest_score < threshold (default 60):
      run = skillEvolutionService.createAndTrigger(skillId, agentId, systemUserId)
      // SkillEvolution 已自动 fork + improve + A/B（现有能力）
      // 监听 A/B 结果通过 callback / event 或 polling
      onAbResult: if (PASS): WS push "skill_auto_upgraded"; else log + 标 evolve_failed
  ```
- yaml 开关 `skillforge.skill-self-improve.scheduled-enabled: true` + threshold 配置
- WS event `skill_auto_upgraded { skillId, oldVersion, newVersion, baselineScore, candidateScore }`
- FE WS handler 在 SkillList / Dashboard 上 toast 提示

### Phase 6（FE）— Dashboard 展示
- SkillList 表加 "Latest Score" 列 + 颜色 Tag（绿 ≥ 80 / 黄 60-80 / 红 < 60）
- SkillList 表加 "Trend" 列 sparkline（最近 N 次 score 趋势）
- SkillDetail drawer 加 "Evaluation History" 段：曲线图（recharts 或 antd-charts）
- SkillDetail drawer 加 "Auto-Evolve Runs" 段：列出该 skill 历史 evolve runs（baseline → candidate → promoted/rejected）
- WS handler 监听 `skill_auto_upgraded` toast + 刷新列表

## 验收标准

### 后端
- [ ] V63 migration 创建 `t_skill_eval_history` 表（5 维 score + triggered_by + 索引 idx_seh_skill_created）
- [ ] `SkillDraftScheduledExtractor` cron 0 3 * * * 跑通（手动调 `runOnce()` 验证）
- [ ] `POST /api/skills/{id}/evaluate` endpoint 落 history 行（单 skill 直评）
- [ ] `SkillScheduledEvaluator` cron 0 4 * * MON 跑通 + 跳过 7 天已评 skill
- [ ] `SkillEvolutionService.callLlmForImprovement` 拼 EVAL failures 进 prompt（grep prompt 含 "Recent failures" 段）
- [ ] `EvalTaskRepository.findRecentFailuresForSkill` agent.skillIds 反查 + composite_score < 60 过滤
- [ ] `SkillSelfImproveLoop` cron 0 5 * * TUE 跑通：score < 60 trigger evolve → A/B → promote/reject
- [ ] WS push `skill_auto_upgraded` event 在 promote 时触发
- [ ] 4 个 cron yaml 开关都生效（关掉后不跑）
- [ ] `mvn test` 全套绿（baseline 1060 + 新增）

### 前端
- [ ] SkillList "Latest Score" 列 + 颜色 Tag
- [ ] SkillList "Trend" sparkline（recharts MiniLineChart 或类似）
- [ ] SkillDetail drawer "Evaluation History" 曲线图
- [ ] SkillDetail drawer "Auto-Evolve Runs" 列表（baseline / candidate / promoted_at / 决策原因）
- [ ] WS handler `skill_auto_upgraded` 接收 + toast + 列表刷新
- [ ] `tsc --noEmit` + `npm run build` 通过

### 整体
- [ ] Phase Final 浏览器 e2e：手动调 `POST /api/skills/{id}/evaluate` → history 落库 → dashboard 看曲线
- [ ] 模拟 score < 60 → 手动调 SkillSelfImproveLoop.runOnce() → 看 evolve trigger → A/B → promote → WS push → dashboard toast
- [ ] yaml 关闭某 cron → 时间到不跑

## 验证预期

- 单测：4 cron component（覆盖 happy / yaml off / 跳过逻辑 / 失败继续）+ EvalTaskRepository.findRecentFailuresForSkill + SkillEvolution failures injection
- IT：V63 migration 实跑 + 真 cron trigger 验证 history 落库
- 浏览器 e2e：dashboard 显示 + WS push 收到 + auto-upgrade 流程跑通
