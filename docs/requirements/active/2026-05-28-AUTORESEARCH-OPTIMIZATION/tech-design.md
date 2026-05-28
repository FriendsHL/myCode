# Tech Design — AUTORESEARCH-OPTIMIZATION V1

> 状态：V0 draft，等 PRD ratify D1-D5 + Q1-Q5 后深化。

## 1. Schema — t_research_finding

```sql
CREATE TABLE t_research_finding (
  id                    BIGSERIAL PRIMARY KEY,
  discovered_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  source_type           VARCHAR(16) NOT NULL,        -- 'arxiv' | 'github'
  source_url            VARCHAR(512) NOT NULL,
  title                 VARCHAR(512) NOT NULL,
  abstract_text         TEXT,                         -- raw abstract (truncated to 8K)
  idea_summary          TEXT,                         -- LLM Stage 1 output (≤2K)
  novelty_json          JSONB,                        -- {core_novelty, differentiators: [...]}
  borrowable_pattern    TEXT,                         -- LLM Stage 2 output (≤1K)
  gap_in_skillforge     TEXT,                         -- LLM Stage 2 output (≤1K)
  recommended_action    TEXT,                         -- LLM Stage 2 output (≤500)
  importance_score      SMALLINT,                     -- 0-10
  status                VARCHAR(16) NOT NULL DEFAULT 'pending',
                                                       -- 'pending' | 'approved' | 'rejected' | 'backlogged'
  reviewer_id           VARCHAR(64),
  review_reason         TEXT,
  reviewed_at           TIMESTAMP WITH TIME ZONE,
  trace_id              VARCHAR(64),                  -- root t_llm_trace.id
  created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

  CONSTRAINT chk_status CHECK (status IN ('pending', 'approved', 'rejected', 'backlogged')),
  CONSTRAINT chk_source_type CHECK (source_type IN ('arxiv', 'github')),
  CONSTRAINT chk_importance CHECK (importance_score BETWEEN 0 AND 10)
);

-- Idempotency:7 天内同 URL 不重复抓
CREATE UNIQUE INDEX uq_research_finding_dedup
  ON t_research_finding (source_url, date_trunc('week', discovered_at));

-- Dashboard 主查询
CREATE INDEX idx_research_finding_status_importance
  ON t_research_finding (status, importance_score DESC, discovered_at DESC);

-- Rejected buffer 14 天 lookup
CREATE INDEX idx_research_finding_rejected_recent
  ON t_research_finding (status, reviewed_at)
  WHERE status = 'rejected';

-- trace 关联
CREATE INDEX idx_research_finding_trace
  ON t_research_finding (trace_id);
```

**Status 状态机**：
```
       ┌─────────┐
       │ pending │ ← (insert from AutoResearchJob)
       └────┬────┘
            │
   ┌────────┼────────┐
   ▼        ▼        ▼
approved  rejected   (no other transition)
   │         │
   │         └──→ buffer (14 天防重复抓)
   │
   ▼
backlogged (终态，auto-built backlog item)
```

## 2. Component Diagram

```
┌──────────────────────────────────────────────────────────────┐
│ Quartz @Scheduled (weekly Monday 03:00 UTC+8)                │
│ cron: 0 0 3 ? * MON                                          │
└──────────────────┬───────────────────────────────────────────┘
                   ▼
┌──────────────────────────────────────────────────────────────┐
│ AutoResearchJob.java                                         │
│  ├─ acquireLock (复用 autoDream consolidationLock 模式)      │
│  ├─ root_trace = startTrace("autoresearch_job")              │
│  ├─ for keyword in keywords_yaml:                            │
│  │    SerperAdapter.searchArxiv(keyword, last_7d)            │
│  │    SerperAdapter.searchGithub(keyword, stars>=50)         │
│  ├─ for candidate in raw_results (max 100):                  │
│  │    skip if dedup_index hit (source_url, week)             │
│  │    skip if in rejected_buffer (recent 14d)                │
│  │    LlmExtractor.stage1(title, abstract) → idea + novelty  │
│  │    LlmExtractor.stage2(stage1, gap_doc) → borrowable +    │
│  │                                            gap + action +  │
│  │                                            importance       │
│  │    PreInsertHook.check (Iron Law)                          │
│  │    ResearchFindingRepository.save (status='pending')       │
│  ├─ releaseLock                                              │
│  └─ tengu_research_job_completed event                       │
└──────────────────┬───────────────────────────────────────────┘
                   ▼
┌──────────────────────────────────────────────────────────────┐
│ Dashboard "Research Findings" tab (React)                    │
│  GET  /api/research-findings?status=pending&importance_min=5 │
│  GET  /api/research-findings/{id}                            │
│  POST /api/research-findings/{id}/approve  → 建 backlog item  │
│  POST /api/research-findings/{id}/reject   → 进 rejected buf  │
│  POST /api/research-findings/{id}/interested                  │
│  POST /api/admin/research/trigger          (admin only)       │
└──────────────────────────────────────────────────────────────┘
```

## 3. 类设计

### 3.1 Java 类清单（新加）

```
skillforge-server/src/main/java/com/skillforge/autoresearch/
├── AutoResearchJob.java                  # Quartz job 主入口
├── AutoResearchOrchestrator.java         # 核心 orchestration logic
├── adapter/
│   ├── SerperAdapter.java                # SerperAPI 客户端
│   └── SearchResult.java                 # POJO
├── extractor/
│   ├── LlmExtractor.java                 # 2-stage LLM extraction
│   ├── Stage1Output.java                 # idea + novelty POJO
│   └── Stage2Output.java                 # borrowable + gap + action POJO
├── entity/
│   └── ResearchFindingEntity.java
├── repository/
│   └── ResearchFindingRepository.java    # Spring Data JPA
├── service/
│   ├── ResearchFindingService.java
│   └── BacklogIntegrationService.java    # 写 docs/todo.md
├── controller/
│   └── ResearchFindingController.java    # REST endpoints
├── dto/
│   ├── ResearchFindingDto.java
│   ├── ApproveRequest.java
│   └── RejectRequest.java
└── hook/
    └── ResearchFindingPreInsertHook.java # Iron Law 检查
```

### 3.2 关键类接口

```java
// AutoResearchOrchestrator.java
public class AutoResearchOrchestrator {
    public void run(TriggerSource trigger) {
        try (var lock = consolidationLock.acquire("autoresearch")) {
            var rootTrace = traceService.start("autoresearch_job");
            var keywords = configService.getResearchKeywords();
            var candidates = parallelSearch(keywords);  // SerperAdapter fanout
            var deduped = dedupAgainstHistory(candidates);
            var extracted = parallelExtract(deduped, rootTrace);  // LlmExtractor fanout
            extracted.forEach(finding -> {
                preInsertHook.check(finding);
                repository.save(finding);
                analyticsService.emit("tengu_research_finding_discovered", finding);
            });
        }
    }
}

// LlmExtractor.java
public class LlmExtractor {
    public CompletableFuture<ResearchFindingEntity> extract(
            SearchResult candidate, String rootTraceId) {
        var ctx = LlmCallContext.builder().rootTraceId(rootTraceId).build();
        return Stage1.run(candidate, ctx)
            .thenCompose(s1 -> Stage2.run(s1, gapSummaryDoc, ctx))
            .thenApply(s2 -> ResearchFindingEntity.from(candidate, s1, s2));
    }
}
```

## 4. REST API

| Method | Path | Body | Response | Auth |
|---|---|---|---|---|
| GET | `/api/research-findings` | query: `status / importance_min / source_type / date_from / date_to / page / size` | `Page<ResearchFindingDto>` | user |
| GET | `/api/research-findings/{id}` | — | `ResearchFindingDto` (含 `traceUrl`) | user |
| POST | `/api/research-findings/{id}/approve` | `{reason?: string}` | `{status: 'backlogged', backlogItemId: string}` | user |
| POST | `/api/research-findings/{id}/reject` | `{reason: string}` (required) | `{status: 'rejected'}` | user |
| POST | `/api/research-findings/{id}/interested` | `{note?: string}` | `{status: 'approved'}` | user |
| POST | `/api/admin/research/trigger` | — | `{jobId: string, startedAt: timestamp}` | admin only |
| GET | `/api/admin/research/jobs` | query: `limit?` | `List<JobRunDto>` | admin only |

## 5. Config（application.yml）

```yaml
skillforge:
  autoresearch:
    enabled: true
    schedule: "0 0 3 ? * MON"   # Monday 03:00 UTC+8
    timeout_minutes: 30
    max_findings_per_job: 100
    keywords:
      - "skill evolution"
      - "agent harness"
      - "self-evolving agent"
      - "skill optimization"
      - "iterative agent improvement"
    sources:
      arxiv:
        enabled: true
        max_results_per_keyword: 10
        recency_days: 7
      github:
        enabled: true
        max_results_per_keyword: 10
        min_stars: 50
        max_age_days: 14
    llm:
      stage1_model: "${LLM_MODEL_FAST}"     # Haiku-tier
      stage2_model: "${LLM_MODEL_SMART}"    # Sonnet-tier
      max_retries: 1
      timeout_seconds: 60
    serper:
      api_key: "${SERPER_API_KEY}"          # 新增 .env 变量
      base_url: "https://google.serper.dev"
    gap_summary_doc_path: "classpath:autoresearch/skillforge_gap_summary.md"
    rejected_buffer_days: 14
```

## 6. Iron Law PreInsertHook

```java
@Component
public class ResearchFindingPreInsertHook implements LifecycleHook<ResearchFindingEntity> {

    private static final List<String> FORBIDDEN_PATTERNS = List.of(
        "(?i)ignore (all )?previous instructions",
        "(?i)disregard (all )?(your |the )?(prior |previous )?(instructions|rules)",
        "(?i)system override",
        "(?i)\\[PROMPT_INJECTION\\]",
        "(?i)you (are|'re) now",
        "(?i)forget (everything|all) (you|that)"
    );

    private static final Map<String, Integer> FIELD_MAX_LENGTH = Map.of(
        "idea_summary",      2000,
        "abstract_text",     8000,
        "borrowable_pattern", 1000,
        "gap_in_skillforge",  1000,
        "recommended_action",  500,
        "title",              512
    );

    public HookResult preInsert(ResearchFindingEntity finding) {
        // 1. 长度检查
        for (var entry : FIELD_MAX_LENGTH.entrySet()) {
            if (lengthOf(finding, entry.getKey()) > entry.getValue()) {
                return HookResult.block("field " + entry.getKey() + " exceeds " + entry.getValue());
            }
        }
        // 2. Forbidden pattern 检查
        var allText = String.join(" ",
            finding.getIdeaSummary(),
            finding.getAbstractText(),
            finding.getBorrowablePattern(),
            finding.getGapInSkillforge());
        for (var pattern : FORBIDDEN_PATTERNS) {
            if (allText.matches(".*" + pattern + ".*")) {
                analyticsService.emit("tengu_research_finding_blocked_by_hook",
                    Map.of("pattern", pattern, "source_url", finding.getSourceUrl()));
                return HookResult.block("forbidden pattern: " + pattern);
            }
        }
        return HookResult.allow();
    }
}
```

## 7. Migration 顺序

| Sprint | 顺序 | 工作 |
|---|---|---|
| **Sprint 1** | 1 | Flyway `V1XX__autoresearch_finding.sql` 建表 + 4 索引 |
| **Sprint 1** | 2 | Entity / Repository / Service Java 类 + LlmExtractor 2-stage |
| **Sprint 1** | 3 | `SerperAdapter` + `application.yml` config + `.env.example` 加 `SERPER_API_KEY` |
| **Sprint 1** | 4 | `AutoResearchJob` + Quartz scheduling + consolidationLock |
| **Sprint 1** | 5 | Manual trigger endpoint + 跑 1 次 dogfood 验证 |
| **Sprint 2** | 6 | REST endpoints + DTO + `BacklogIntegrationService` (写 docs/todo.md) |
| **Sprint 2** | 7 | Dashboard React tab "Research Findings" + 卡片 UI + filter |
| **Sprint 2** | 8 | dashboard 顶级导航 + routing |
| **Sprint 3** | 9 | `ResearchFindingPreInsertHook` Iron Law 检查 |
| **Sprint 3** | 10 | `tengu_research_*` GrowthBook events |
| **Sprint 3** | 11 | README 加表说明 + dashboard 截图 |
| **Sprint 4** | 12 | 单测覆盖：LLM extraction quality (SkillOpt golden) + idempotency |
| **Sprint 4** | 13 | dogfood 4 周 + metrics 报告 (accept rate / cost / backlog 转化率) |

## 8. 复用 SkillForge 现有模块清单

| 复用 | 来自 | 用途 |
|---|---|---|
| `LlmCallContext` | `skillforge-core` `AgentLoopEngine` (跨线程 explicit pass) | LLM 调用 + trace 关联 |
| `LifecycleHookDispatcher` | `skillforge-core` hook 框架 | PreInsertHook Iron Law |
| `ConsolidationLock` 模式 | `autoDream` 等价 Java 实现 | 防 job 并发 |
| `t_llm_trace + t_llm_span` | `skillforge-observability` | 每 finding 追到 trace 树 |
| `tengu_*` GrowthBook events | `skillforge-server` `AnalyticsService` | 一致 analytics pipeline |
| Dashboard 顶级导航 / 卡片样式 | `skillforge-dashboard` React 组件库 | UI 一致性 |
| Spring Data JPA | 全栈 | Repository |
| Flyway | 全栈 | DB migration |
| Quartz | 现有 cron 框架 | scheduling |

## 9. gap_summary 文档（Stage 2 输入）

新建 `skillforge-server/src/main/resources/autoresearch/skillforge_gap_summary.md`，内容大纲：

```markdown
# SkillForge 现状 + 已有零件 + 已知 gap（Stage 2 LLM 输入）

> 最后更新：YYYY-MM-DD，下次 review：YYYY-MM-DD

## SkillForge 核心能力（已有零件）

- production data 飞轮：t_llm_trace + t_llm_span 3-kind + origin partial index
- 14-stage state machine：proposal_pending → ... → promoted/rolled_back
- 4 surface 独立 A/B：skill / prompt / behaviorRule / sandbox
- Iron Law 人审 gate（业内独家）
- AUTO_ROLLBACK + canary
- EVOLUTION_FORK + 8 SkillSource

## SkillForge 当前已知 gap（V2-V4 计划）

- K-1 optimizer_program.md 拆出（策略写死在 Java）
- K-2 complexity_delta 维度
- K-3 v_experiment_ledger view
- K-4 outer epoch loop + falsification

## 已知反模式（不要再借）

- 自动 generated skill 直接进生产（SkillsBench +0pp）
- 多 surface 并改（归因失败）
- ExecutionLapse 误归 SkillDefect
- pass_rate 唯一目标

## 不感兴趣的方向（filter 掉）

- pure RL skill discovery（跟 LLM 路线远）
- embodied robotics 专用（不可迁移）
- 单一 benchmark 调优（不通用）
```

> 这个文档每月 review + 跟 wiki [`concept/iterative-skill-optimization.md`](../../../../../research-docs/research/agent-harness-wiki/concept/iterative-skill-optimization.md) §4 SkillForge 零件清单同步

## 10. 待 Plan 时深化

- Q1-Q5 拍板后定关键词清单具体内容
- SerperAPI vs Tavily 成本 spike（cost / latency / 召回率对比）
- `BacklogIntegrationService` 写 `docs/todo.md` 的具体格式（每行 schema / 自动 PR 还是直接 commit / 分支策略）
- Dashboard React tab 详细 wireframe（Q3 拍板后做）
- Stage 2 prompt 完整版（含 few-shot example：SkillOpt expected output）
- `gap_summary` doc 初版内容 + maintainer 流程
- 单测 golden case 完整 expected JSON（SkillOpt 2605.23904 当唯一 golden V0）
