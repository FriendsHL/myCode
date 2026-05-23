# MRD — EVAL-DATASET-LAYER

## 用户原话

> "我觉得应该是这样的：如果基于一些 base 的 case 我们叫 scenario。然后从 session 里面增加的我们叫 dataBase 还是叫 dataset？然后一些基础的 scenario 我们应该从一些 benchmark 里面找一找抽出来作为通用的。这块也应该好好想想了。之前只是想有这个功能，但是实际上并没有进行深度思考。"
>
> "命名的这个我同意了，按照你说的来。关于测试集原始使用可以先按照短期来，但是我感觉可以挑选 30 题，稍微多一点。然后你来负责挑选。然后我们之前的 Scenario 这个需要干掉吗？或者你重新开个需求包，然后给个完整的 PRD。"

## 当前痛点

### 痛点 1：baseline 0% — 飞轮拿不到改善信号

2026-05-23 Event 122 A/B 修通 V108 / timeout / race 三个 bug 后跑通，但 6 个 scenario：

| Scenario | baseline score | candidate score |
|---|---|---|
| SkillForge Tool Capability Audit | 0 | 0 |
| Research Agent Design Survey | 0 | 12 |
| Open Source Eval Platform Review | 30 | 0 |
| Session Analysis Skill Benchmarking | 13 | 0 |
| session-derived-cf289f45 | 30 | 30 |
| session-derived-a556183c | 0 | 0 |

baseline pass rate = 0%，candidate pass rate = 0%，delta = 0 → flywheel **永不 promote**。

根因：6 个 scenarios 全是从 agent 失败 session 抽出来的难题（source_type = session_derived）。用 agent 历史踩坑的最难任务考通用助手 baseline → 必然 0%。

### 痛点 2：scenario 跟 agent 1:1 强绑

`t_eval_scenario.agent_id` NOT NULL，scenario 只能服务一个 agent。换 agent 就得复制 scenario，没有"通用 baseline 题"概念。

### 痛点 3：scenario 集没有版本 / 命名

加一条 scenario 立刻改变所有 A/B run 的比较基准。两次 A/B run 之间如果 scenarios 改了，结果不可比。也没办法说"agent3-regression-v2"这种 named set。

### 痛点 4：缺通用 benchmark 基线题

没法回答 "我这个 agent 在 GAIA Lv1 / τ-bench 这种公认 benchmark 上表现如何"。

## 用户期望

1. **不同 source_type 区分清楚**：benchmark 题（业界公认 baseline）vs session-derived 题（agent 自己历史 case）
2. **挑 30 题 benchmark 种子**：覆盖 SkillForge 主要 agent 场景，让 baseline 不再 0%
3. **保留现有 session_derived scenarios**：不干掉，用来当回归测试
4. **深度想清楚数据层结构**：不再"只是想有这个功能"

## 不在范围

- 不要现在自动 crawl benchmark URL —— 手动挑 + seed migration
- 不要做 dataset 跨 owner 共享 —— V1 单 tenant
- 不要把 EvalScenario / EvalTask / PromptAbRun 推倒重做 —— 增量 + 不破坏现有 API

## 验收点

1. ✅ 6 个现有 scenarios 全部标 `source_type='session_derived'`，零数据丢失
2. ✅ 至少 30 个 benchmark scenarios 入 t_eval_scenario，覆盖 ≥3 个公开 benchmark 来源
3. ✅ EvalDataset 实体可命名 + 可版本化，A/B run 关联 dataset_version_id
4. ✅ Agent 3 跑一次 A/B 用新 benchmark dataset，baseline pass rate ≥ 30%（不再 0%）
5. ✅ FE 能按 source_type filter / 按 dataset 分组浏览 scenarios
6. ✅ Iron Law: 核心 7+1 BE + 核心 3 FE 文件 git diff = 0
