# Benchmark Selection — 30 道 baseline scenarios 挑选清单

> 状态：draft（pending implementation, 真正写 task 文案进 V112 migration 时再终稿）
> 标准：每题 oracle 必须明确可判分；optional setup 文件清单清楚；maxLoops 合理
> 数量分布锁定：GAIA Lv1 12 + τ-bench 6 + AgentBench OS+DB 6 + SkillForge dogfood 6 = **30**

## 选题原则

1. **覆盖 SkillForge 5 个 user agent**（Main Assistant / Code Agent / Research Agent / Session Analyzer / Design Agent）的典型任务
2. **难度分布**：
   - 30% (~9 题) Lv1 - 简单题，baseline 应能 ≥ 50% pass — 防全 0% 死局
   - 50% (~15 题) Lv2 - 中等，baseline 30-50% — 给 candidate 改善空间
   - 20% (~6 题) Lv3 - 较难，baseline ≤ 20% — 捕捉 strong improvement
3. **oracle 必须可机器判分**：keyword/regex match / structured output schema / oracle LLM 给 specific 分项 — 避免主观 "好不好"
4. **不抄全文**：保留 source_ref 链回原 benchmark，task 文案为我重写/翻译版本

## 业界 benchmark 选型决策（wiki r2 加，对应 [wiki query](/Users/youren/myspace/research-docs/research/agent-harness-wiki/queries/eval-dataset-layer-design.md) §4）

### ✅ 采用（4 来源 = 30 题）

| 来源 | 数量 | 选用理由 |
|---|---|---|
| **GAIA Lv1** | 12 | 通用助手 baseline 公认；HuggingFace 标准 dataset；Mastra / deepagents 都用；oracle 设计明确（短回答 + 关键字判分）|
| **τ-bench** | 6 | tool-use 评测标杆；跟 SkillForge attribution 同方向（[wiki eval/tau-bench](/Users/youren/myspace/research-docs/research/agent-harness-wiki/eval/tau-bench.md) line 153 _"SkillForge 是 surface-level optimization 归因, tau-bench 是 fault-level 单次失败归因"_）；multi-turn tool 调用真活复杂场景 |
| **AgentBench OS+DB** | 6 | Bash + SQL 直接对应 Code Agent + Session Analyzer 能力；评测 deterministic（exit code / row count match）|
| **SkillForge dogfood** | 6 | 平台特定 use case（GetTrace / SubAgent / ProposeOptimization 等 SkillForge 独有 Tool）；外部 benchmark 不会覆盖 |

### ❌ 不采用（明确决策 + 理由）

#### SWE-bench / SWE-bench Verified

- ⛔ **wiki 直接论断**：[[eval/swe-bench]] line 124 _"SWE-bench 不是 SkillForge 该集成的 dataset"_
- **理由 1 — 太 coding-specific**：SWE-bench 是 _Given codebase + GitHub issue → LLM 生成 patch 解决_，跟通用 agent platform 评测目标不一致
- **理由 2 — 资源要求**：120GB+16GB+8CPU + Docker 全容器化评测，SkillForge dogfood 环境跑不了
- **理由 3 — 用户群偏差**：SkillForge 的 user agent 是 Main Assistant / Research Agent / Design Agent 等，**不是 Code Agent 单一画像**；用 SWE-bench 评等于用错的 KPI 衡量产品
- **替代**：Code Agent 评测走 AgentBench OS 子集 + SkillForge dogfood 内置代码题（已纳入 30 题）

#### WebArena / VisualWebArena

- ⛔ **理由 1 — 需要浏览器环境**：WebArena 评测要求跑 Playwright / Selenium 真实浏览器交互；SkillForge 没集成浏览器 Tool（只有 WebSearch / WebFetch）
- **理由 2 — 评测对象错位**：WebArena 评测 _agent 能否在真实网站上完成购物 / 订票_ 等任务，跟 SkillForge agent 主要场景（dev 工具 / 信息整理 / 报告生成）不一致
- **替代**：如果未来 SkillForge 加 Browser Tool（V3+ backlog），可考虑加 WebArena 子集

#### GSM8K / TruthfulQA / HaluEval（Opik 8 内置 benchmark 之一）

- ⏸️ **理由 — 通用性偏弱**：[[eval/opik]] line 173 _"SkillForge 应内置一组业内 benchmark, 用户 zero-config 跑"_ — 但 Opik 8 个 benchmark 偏 QA / reasoning 类，跟 _agent 完成多步任务_ 关联较弱
- **替代**：30 题已含 GAIA Lv1 + τ-bench（更适合 agent 评测）；GSM8K 这种纯数学题可作 V2+ 补充

#### Inspect AI 数据集（AISI 政府评测标杆）

- ⏸️ **理由 — 当前 wiki 没 ingest** ([wiki query 知识缺口](/Users/youren/myspace/research-docs/research/agent-harness-wiki/queries/eval-dataset-layer-design.md))；先不动 V1，**建议 V2 评估时补 ingest** 看是否值得纳入

## 来源 1: GAIA Lv1（12 题）

来源：[GAIA paper](https://arxiv.org/abs/2311.12983) + HuggingFace dataset `gaia-benchmark/GAIA`

挑选偏向：单步搜索 / 简单工具调用 / 短回答 — 对应 Main Assistant + Research Agent 能力

| # | source_ref | 任务类型 | 期望 agent | 难度 |
|---|---|---|---|---|
| 1 | `gaia/lv1/001` | "Wikipedia 查询：列出 2020 年诺贝尔物理学奖得主" | Main Assistant | Lv1 |
| 2 | `gaia/lv1/002` | "用 WebSearch 找 Python 3.12 最新 patch 版本" | Main Assistant | Lv1 |
| 3 | `gaia/lv1/003` | "计算：给定一段文字数其中 'the' 出现次数" | Main Assistant | Lv1 |
| 4 | `gaia/lv1/004` | "WebFetch 一个 GitHub repo README，提取 license 名" | Research Agent | Lv1 |
| 5 | `gaia/lv1/005` | "FileRead 一个 CSV，返回行数 + 第 1 列 unique values" | Code Agent | Lv1 |
| 6 | `gaia/lv1/006` | "把给定 JSON 转 YAML，保持字段顺序" | Code Agent | Lv1 |
| 7 | `gaia/lv1/007` | "比较 2 个 URL 的内容差异点 top 3" | Research Agent | Lv2 |
| 8 | `gaia/lv1/008` | "Bash: 给定目录，列出 size > 10MB 的文件" | Code Agent | Lv1 |
| 9 | `gaia/lv1/009` | "从一段 stack trace 中提取根因异常类名 + 行号" | Main Assistant | Lv1 |
| 10 | `gaia/lv1/010` | "把英文短文翻成中文，保留 markdown 格式" | Main Assistant | Lv1 |
| 11 | `gaia/lv1/011` | "搜 arXiv 找最近 3 个月 LLM agent 评测论文 top 5" | Research Agent | Lv2 |
| 12 | `gaia/lv1/012` | "用 WebFetch 一个 Wikipedia 页，提取 infobox 关键字段" | Research Agent | Lv2 |

## 来源 2: τ-bench (Anthropic)（6 题）

来源：[Anthropic tau-bench](https://github.com/sierra-research/tau-bench)

挑选偏向：tool 调用 multi-turn / 客服域 — 对应通用 tool use 能力

| # | source_ref | 任务类型 | 难度 |
|---|---|---|---|
| 13 | `tau-bench/airline/01` | "用户改签航班：查 booking → 检 cancel policy → 改新日期" | Lv2 |
| 14 | `tau-bench/airline/02` | "退款请求：判断 refund eligibility + 执行" | Lv2 |
| 15 | `tau-bench/retail/01` | "查订单状态 + 改邮寄地址（已发货时拒）" | Lv2 |
| 16 | `tau-bench/retail/02` | "退款 + reorder 替代商品" | Lv3 |
| 17 | `tau-bench/airline/03` | "组合任务：1 next-trip flight + 1 cancel + 1 refund" | Lv3 |
| 18 | `tau-bench/retail/03` | "处理 ambiguous 用户请求（要求 agent 主动 ask_user 澄清）" | Lv2 |

## 来源 3: AgentBench OS + DB（6 题）

来源：[AgentBench](https://github.com/THUDM/AgentBench) - OS / DB subsets

挑选偏向：Linux shell + SQL — 对应 Code Agent + Session Analyzer

| # | source_ref | 任务类型 | 难度 |
|---|---|---|---|
| 19 | `agentbench/os/001` | "用 Bash 找出系统中所有 .log 文件，按大小排序前 10" | Lv1 |
| 20 | `agentbench/os/002` | "杀掉占用 8080 端口的进程" | Lv2 |
| 21 | `agentbench/os/003` | "递归找出 git repo 中近 7 天修改的 .py 文件" | Lv2 |
| 22 | `agentbench/db/001` | "用 SQL 查 users 表中注册时间 top 10 用户" | Lv1 |
| 23 | `agentbench/db/002` | "找出 orders 表中重复的 (user_id, product_id) 组合" | Lv2 |
| 24 | `agentbench/db/003` | "JOIN 3 表查月度 GMV 走势，按月汇总" | Lv3 |

## 来源 4: SkillForge dogfood (6 题, manual)

> 我们自己写的，覆盖 SkillForge 平台特定场景。**所有题目 source_type=`benchmark` 不是 `manual`**（这是有名 benchmark suite 的一部分，跟来自外部 paper 同等地位）

| # | source_ref | 任务类型 | 期望 agent | 难度 |
|---|---|---|---|---|
| 25 | `skillforge/dogfood/001` | "给定一个 session id，用 GetTrace 查出 tool_use 总数 + 主要 tool 分布" | Session Analyzer | Lv1 |
| 26 | `skillforge/dogfood/002` | "用 SubAgent 派 3 个子任务分析 3 个 session，汇总 outcome 分布" | Main Assistant | Lv2 |
| 27 | `skillforge/dogfood/003` | "用 ProposeOptimization 写一个 OptEvent (surface=prompt, outcome=failure)" | Design Agent | Lv2 |
| 28 | `skillforge/dogfood/004` | "用 GetAgentConfig + ListAgents 比较 Main Assistant 跟 Code Agent 的 tool 集合差异" | Research Agent | Lv1 |
| 29 | `skillforge/dogfood/005` | "用 Memory tool 存一条 fact，下一 turn 用 Memory tool 读出来验证" | Main Assistant | Lv1 |
| 30 | `skillforge/dogfood/006` | "用 GetTrace 跨 3 个 session 找出 'tool_use=Bash' + 'tool_result=non-zero exit' 的组合" | Session Analyzer | Lv3 |

## 写成 V112 migration 的步骤

1. 把以上 30 道题的 task 文案逐条写清楚（中英文混合 OK，跟 agent prompt 一致）
2. 每道题写 oracle (oracle_type='llm_judge' 或 'keyword_match' / 'json_schema'，oracle_expected 字段填具体期望)
3. 每道题设 maxLoops（简单 5 / 中等 8 / 难 10）+ performance_threshold_ms (~60_000)
4. setup.files 字段（如果题目需要 fixture 文件，用 base64 / 直接 inline 字符串）
5. INSERT INTO t_eval_scenario 30 行，source_type='benchmark' + source_ref 填写

## 写完后的 v1 dataset 组装（V112 末尾）

```sql
-- 1) Create main-assistant-baseline-v1 dataset
INSERT INTO t_eval_dataset (id, name, description, owner_id, agent_id, tags, is_public) VALUES
  (gen_random_uuid()::text, 'main-assistant-baseline-v1', 'GAIA Lv1 + τ-bench + AgentBench + SkillForge dogfood mixed benchmark', 1, NULL, '["benchmark","baseline"]'::jsonb, false);

-- 2) Create version 1
INSERT INTO t_eval_dataset_version (id, dataset_id, version_number, composition_stats, created_at, created_by) VALUES
  (gen_random_uuid()::text, '${dataset_id}', 1, '{"benchmark":30,"session_derived":0,"manual":0,"total":30}'::jsonb, now(), 1);

-- 3) Associate all 30 scenarios with version 1
INSERT INTO t_eval_dataset_version_scenario (dataset_version_id, scenario_id)
SELECT '${version_id}', id FROM t_eval_scenario WHERE source_type='benchmark';

-- 4) main-assistant-regression-v1 = 现有 6 session_derived
-- 5) main-assistant-mixed-v1 = 30 benchmark + 6 session_derived
```

## 实施时 push back 点

- 30 题如果都用 LLM judge 跑成本高（30 × 2 × eval LLM call = 60 judge calls per A/B）。**可考虑**给 50% 题用 keyword/regex/json_schema 这种确定性 oracle，省钱 + 提速
- 不要直接复制 GAIA / τ-bench 原文 — 用 source_ref 链回去 + 自己重写 task 文案

## 验证 acceptance（V112 跑完后）

- Agent 3 用 main-assistant-baseline-v1 v1 跑 A/B
- baseline pass rate ≥ 30%（不再 0%）
- 候选 prompt 如果真改善，delta > 10%
- 候选 prompt 如果真倒退，delta < -5%
