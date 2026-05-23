# Tech Design — EVAL-DATASET-LAYER

## 0. 已知 reviewer findings 处置（r3 + r4）

### r4 (2026-05-24) — Full plan-stage adversarial review，3 reviewer 跑完，2 BLOCKER + 7 mandatory warning + 4 discretionary 全融入：

| # | Severity | Finding | 处置 |
|---|---|---|---|
| **B1** | 🔴 BLOCKER | `EvalScenarioEntity.origin` 字段名跟 `SessionEntity.origin` 同名不同语义维度 (session=production/eval; scenario=benchmark/session_derived/manual) → JPQL/log/FE filter 长期混淆 | **r4 已修**: 字段名 `origin` 全文 rename 为 `source_type` (跟同表 `source_ref` 形成 type-instance 配对)，57 处 touchpoint 全替换 |
| **B2** | 🔴 BLOCKER | tech-design §1.2/§1.3 用 `TIMESTAMP` 但 Hibernate `@CreatedDate Instant` 映射要 `TIMESTAMPTZ` (跟 t_prompt_ab_run convention 一致)，非 UTC 环境时区漂移 | **r4 已修**: §1.2/§1.3 schema 全改 `TIMESTAMPTZ NOT NULL DEFAULT now()` |
| **W1** | 🟡 mandatory | `EvalDatasetVersionEntity` 漏 `compositionHash` Java 字段 (DB 列加了 Entity 没声明 → silent 写 null → SHA256 diff 不 work) | **r4 已修**: §2.3 加 `@Column(name="composition_hash", length=64) private String compositionHash;` |
| **W2** | 🟡 mandatory | V109 缺 `IF NOT EXISTS` idempotency guard (V106/V103 项目 convention) | **r4 已修**: §1.1 SQL 加 IF NOT EXISTS 守护 ADD COLUMN + ADD CONSTRAINT + CREATE INDEX |
| **W3** | 🟡 mandatory | `ON DELETE RESTRICT` 没 soft-delete 路径 → operator 删不掉 published scenarios | **r4 已修**: §1.4 加 §"ON DELETE 含意 + V1 不可删 trap" 明确文档化 |
| **W4** | 🟡 mandatory | `publishVersion(空 scenarioIds)` 行为未定义 (SHA256("") collision 风险) | **r4 已修**: §3.1 EvalDatasetService.publishVersion 明确 throw IllegalArgumentException |
| **W5** | 🟡 mandatory | V112 seed 完后所有现有 scenario 都被 version 引用 → 实务上不可删 | **r4 已修**: §1.4 + §6 migration 顺序段加 trap note |
| **D1** | 🟡 discretionary | `expected_baseline_pass_rate` 公式硬编码无 feedback loop | **r4 已修**: §2.3 EvalDatasetVersionEntity 加 `actualBaselinePassRate` 字段，第一次 A/B run 完反写真值 |
| **D2** | 🟡 discretionary | AbEvalPipeline 新旧 overload 并存无 deprecation 路径 | **r4 已修**: §3.2 旧 overload 标 `@Deprecated` + log.warn 警告 caller 迁移 |
| **D3** | 🟡 discretionary | `runAbTestAgainst` 5-arg 中 4 个 String → 引入 `AbEvalRunRequest` record param object | **r4 已修**: §3.3 加 AbEvalRunRequest record |
| **D4** | 🟡 discretionary | `computeExpectedBaselinePassRate` 静态 switch → 抽 `BaselinePassRateHeuristic` interface | **r4 已修**: §3.1 加 BaselinePassRateHeuristic interface + StaticHeuristic 默认实现 |

### r3 (历史) — pre-Write self-check 4 finding：

| Finding | Severity | 处置 |
|---|---|---|
| V109 缺 `ALTER agent_id DROP NOT NULL` | 🔴 真 blocker | **§1.1 已修** — 加 ALTER 让 benchmark agent_id=null 合法 |
| `category` (开放 user-tag, e.g. "trace_import") vs `source_type` (closed enum) 重叠风险 | 🟡 WARNING | **§1.1 已加注释明确语义区分** — category 保持开放 user-tag 不动，source_type 是新 closed enum 维度，两者并存不冲突 |
| V112 `gen_random_uuid()` 兼容性 | ⚪ NIT | **不动** — embedded PostgreSQL 14 (zonky) 原生支持 (PG 13+ 内置)，不需 pgcrypto |
| `EvalDatasetEntity.agentId VARCHAR vs AgentEntity.id Long` type mismatch | 🟡 WARNING | **§2.2 加 known-tech-debt 注释** — 跟现有 EvalScenarioEntity.agentId 保持一致，未来 v2 统一 ID 类型时一起改 |

## 1. Schema 设计

### 1.1 t_eval_scenario 扩展（V109）

```sql
-- ★ r3 BLOCKER fix: 让 benchmark source_type scenarios 的 agent_id 合法 null
-- 现有 6 行 agent_id 全非空（agent_id='3'），DROP NOT NULL 不影响历史数据
ALTER TABLE t_eval_scenario ALTER COLUMN agent_id DROP NOT NULL;

-- ★ r4 W2 fix: IF NOT EXISTS guards (V106/V103 项目 convention，dev DB reset 重跑 idempotent)
ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS source_type VARCHAR(32);
UPDATE t_eval_scenario SET source_type='session_derived' WHERE source_type IS NULL;
ALTER TABLE t_eval_scenario ALTER COLUMN source_type SET NOT NULL;
-- PostgreSQL 不支持 ADD CONSTRAINT IF NOT EXISTS 直接语法，用 DO $$ ... $$ 守护
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_eval_scenario_source_type') THEN
    ALTER TABLE t_eval_scenario ADD CONSTRAINT chk_eval_scenario_source_type
      CHECK (source_type IN ('benchmark','session_derived','manual'));
  END IF;
END $$;

ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS source_ref VARCHAR(256);
-- source_ref nullable，老数据留 NULL，新 benchmark / manual / session_derived 必填

-- ★ wiki r2 新加: purpose 字段 (跟 source_type 正交，对齐 SWE-bench regression-aware)
ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS purpose VARCHAR(32);
UPDATE t_eval_scenario SET purpose='regression' WHERE purpose IS NULL;
ALTER TABLE t_eval_scenario ALTER COLUMN purpose SET NOT NULL;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_eval_scenario_purpose') THEN
    ALTER TABLE t_eval_scenario ADD CONSTRAINT chk_eval_scenario_purpose
      CHECK (purpose IN ('baseline_anchor','regression','ablation'));
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_eval_scenario_source_type ON t_eval_scenario(source_type);
CREATE INDEX IF NOT EXISTS idx_eval_scenario_purpose ON t_eval_scenario(purpose);
CREATE INDEX IF NOT EXISTS idx_eval_scenario_source_ref ON t_eval_scenario(source_ref) WHERE source_ref IS NOT NULL;
```

**关于 `category` vs `source_type` 字段语义区分** (r3 reviewer finding 处置)：

| 字段 | 类型 | 语义 | 现有用法 |
|---|---|---|---|
| `category` | 开放 VARCHAR(64) | **用户自定义 tag**（任意字符串）| `TraceScenarioImportService` 用 `"trace_import"`，FE `AddBaseScenarioModal` 让用户输入任意值，`SkillCreatorServiceScenarioExtractionTest` 测 `"skill-creator-eval"`，**实际是用户自由分类标签** |
| `source_type` | 封闭 enum VARCHAR(32) | **数据来源严格分类**（benchmark / session_derived / manual）| 本包新加，跟 dataset composition policy 关联 |

**两者并存不冲突，语义正交**：
- `category` 给 operator/用户 自由打标用（"trace_import" / "skill-creator-eval" / "edge-case-test" 任意）
- `source_type` 给 dataset composition / A/B run 决策用（业界对齐 Opik "Datasets vs Benchmark datasets" 二分）

历史上 `category='session_derived'` 是巧合（人工填的字符串恰好跟新 enum 重名），不是 schema-level 重叠。V109 retroactive `UPDATE source_type='session_derived'` 不动 category，**两个字段独立演进**。

未来如有人想干掉 category 简化 schema，需独立需求包评估 BC 影响（FE filter / 用户标签数据迁移）。

**关于 purpose 字段**（wiki r2）：
- `baseline_anchor` — 公平 baseline 题（benchmark source_type 默认这个）
- `regression` — 防再次踩坑（session_derived source_type 默认这个）
- `ablation` — 调研用 / 验证某个改动是否有效（manual source_type 可选这个）

source_type × purpose 正交矩阵（业界对齐 SWE-bench F2P/F2F/P2P/P2F regression-aware 思路）：

|  | benchmark | session_derived | manual |
|---|---|---|---|
| **baseline_anchor** | GAIA Lv1 主用 ★ | 极少 | dogfood 关键题 |
| **regression** | 偶尔（SWE-bench Verified 风格）| 现有 6 个 ★ | session 衍生但手改 |
| **ablation** | 罕见 | 罕见 | 调研用 |

**注意**：现有 6 行先 UPDATE 再 ALTER NOT NULL，避免 NOT NULL constraint failure。`agent_id` 字段保留（向后兼容），但 benchmark source_type 的 scenario `agent_id` 可为 null。

### 1.2 t_eval_dataset（V110）

```sql
CREATE TABLE t_eval_dataset (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    owner_id BIGINT NOT NULL,
    agent_id VARCHAR(36),  -- nullable: null = 通用集（跨 agent 共享）
    tags JSONB,            -- ["gaia", "lv1", "baseline"]
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    -- ★ r4 B2 fix: TIMESTAMPTZ 对齐项目 convention (t_prompt_ab_run / t_session 等)
    -- Hibernate @CreatedDate Instant 映射要 timestamptz，TIMESTAMP 非 UTC 环境会时区漂移
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_eval_dataset_owner_name ON t_eval_dataset(owner_id, name);
CREATE INDEX idx_eval_dataset_agent ON t_eval_dataset(agent_id) WHERE agent_id IS NOT NULL;
```

### 1.3 t_eval_dataset_version（V110）

```sql
CREATE TABLE t_eval_dataset_version (
    id VARCHAR(36) PRIMARY KEY,
    dataset_id VARCHAR(36) NOT NULL REFERENCES t_eval_dataset(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    composition_stats JSONB,
    -- composition_stats 含字段:
    --   {benchmark: N, session_derived: M, manual: K, total: N+M+K,
    --    purpose_baseline_anchor: X, purpose_regression: Y, purpose_ablation: Z,
    --    expected_baseline_pass_rate: 0.30}  -- ★ wiki r2: 预估区间 FE 显示用
    composition_hash VARCHAR(64),  -- ★ wiki r2: SHA256 of sorted scenario_ids，参考 τ-bench gt_data_hash
    actual_baseline_pass_rate DOUBLE PRECISION,  -- ★ r4 D1 fix: 第一次 A/B run 完反写真值，给 FE 优先显示而不是 expected
    -- ★ r4 B2 fix: TIMESTAMPTZ 对齐 Hibernate Instant 映射
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT
);

CREATE UNIQUE INDEX uq_eval_dataset_version ON t_eval_dataset_version(dataset_id, version_number);
CREATE INDEX idx_eval_dataset_version_hash ON t_eval_dataset_version(composition_hash) WHERE composition_hash IS NOT NULL;
```

**关于 composition_hash 计算 + 空 list 行为**（wiki r2 + r4 W4 fix）：

```java
// EvalDatasetService.publishVersion 内部
public EvalDatasetVersionEntity publishVersion(String datasetId, List<String> scenarioIds, Long userId) {
    // ★ r4 W4 fix: 空 list 显式拒绝，避免 SHA256("") collision 风险 + 跟 Langfuse "Dataset 可无 Item 但不能发 0-item version" 对齐
    if (scenarioIds == null || scenarioIds.isEmpty()) {
        throw new IllegalArgumentException(
            "publishVersion: scenarioIds is empty; 空 dataset 用例由 dataset 行存在 + 0 version 表示，不允许发布空 version"
        );
    }
    String compositionHash = DigestUtils.sha256Hex(
        scenarioIds.stream().sorted().collect(Collectors.joining(","))
    );
    version.setCompositionHash(compositionHash);
    // ... rest of publish logic
}
```

用途：
- **跨 version diff detection**：v1 跟 v2 改了什么 scenarios 一眼看出（hash 变了说明 scenario_ids 集合变了）
- **A/B run 启动时 verify**：PromptAbRun.compositionHashSnapshot 跟当前 version.compositionHash 比对，不一致警告（防 race condition 中途 version 被改）
- **跨 dataset 同款检测**：两个 dataset 如果挑同一组 scenarios 凑出来，hash 会一样

**关于 expected_baseline_pass_rate**（wiki r2 + r4 D4 fix —— 抽 Strategy interface 提升可测试性 + V2 扩展性）：

```java
// ★ r4 D4 fix: 抽 interface，V2 加 historical-regression heuristic 时不动 Service
public interface BaselinePassRateHeuristic {
    /** 返回 [0.0, 1.0] 预估区间均值；FE 显示加 ±30% 容差区间 */
    double estimate(List<EvalScenarioEntity> scenarios);
}

@Component
public class StaticSourceTypeHeuristic implements BaselinePassRateHeuristic {
    // V1 启发式 — 拍脑袋值，跟实际偏差 ±30% 内 acceptable
    // V2 backlog: 用历史 ≥10 个 A/B run 真实 baseline pass rate 拟合系数
    @Override
    public double estimate(List<EvalScenarioEntity> scenarios) {
        double sum = 0.0;
        for (EvalScenarioEntity s : scenarios) {
            sum += switch (s.getSourceType()) {
                case "benchmark" -> 0.40;       // GAIA Lv1 类预估 30-50%
                case "session_derived" -> 0.05; // failure session 抽出，基本 0-10%
                case "manual" -> 0.30;          // dogfood 设计时知道难度，预估 20-40%
                default -> 0.0;
            };
        }
        return sum / scenarios.size();
    }
}
```

FE Dataset 选择器显示策略（**优先级**：实际 > 预估）：

| 状态 | 显示 |
|---|---|
| `actual_baseline_pass_rate` 已写（≥1 次 A/B run 完成）| `Baseline: 32% (actual, last run 2026-05-24)` |
| 仅 `expected_baseline_pass_rate` 有值 | `Baseline: 30-50% (estimated, may be ±30% off)` ⚠️ |
| 都为 null | 不显示，让 operator 自己跑一次拿真值 |

**为什么这样设计**：D1 finding —— 启发式硬编码无校正机制 → 第一次 A/B run 完后反写 `actual_baseline_pass_rate`，FE 优先用真值，避免长期 stale estimate 消耗 operator 信任。

### 1.4 t_eval_dataset_version_scenario（V110）

```sql
CREATE TABLE t_eval_dataset_version_scenario (
    dataset_version_id VARCHAR(36) NOT NULL REFERENCES t_eval_dataset_version(id) ON DELETE CASCADE,
    scenario_id VARCHAR(36) NOT NULL REFERENCES t_eval_scenario(id) ON DELETE RESTRICT,
    -- ★ r4 W3/W5 fix: ON DELETE RESTRICT 含意 + V1 不可删 trap 文档化
    -- 1) RESTRICT 意图：不允许删被 version 引用的 scenario，保 dataset version immutable snapshot 完整
    -- 2) V112 seed 完后：所有现有 scenario (6 session_derived + 30 benchmark) 都被 v1 dataset version 引用
    --    → V1 实务上 scenario 完全不可 delete (FK violation)
    -- 3) V1 替代方案：用 EvalScenarioEntity.status='archived' 软删 (避免 RESTRICT)
    -- 4) V2 backlog：加 EvalDatasetVersion.status='deprecated' 字段，dataset version 标 deprecated 后允许 cascade 清理引用
    PRIMARY KEY (dataset_version_id, scenario_id)
);

CREATE INDEX idx_evds_scenario ON t_eval_dataset_version_scenario(scenario_id);
```

### 1.5 t_prompt_ab_run 扩展（V111）

```sql
ALTER TABLE t_prompt_ab_run ADD COLUMN dataset_version_id VARCHAR(36) REFERENCES t_eval_dataset_version(id);
CREATE INDEX idx_prompt_ab_run_dataset ON t_prompt_ab_run(dataset_version_id) WHERE dataset_version_id IS NOT NULL;
```

nullable 让老 run（attribution path 没 dataset 概念时建的）不报错。新 run 必填。

## 2. Java Entity 设计

### 2.1 EvalScenarioEntity 改动

```java
@Entity
@Table(name = "t_eval_scenario")
public class EvalScenarioEntity {
    // ... existing fields ...

    @Column(name = "source_type", nullable = false, length = 32)
    private String source_type;  // benchmark / session_derived / manual

    @Column(name = "source_ref", length = 256)
    private String sourceRef;

    public static final String ORIGIN_BENCHMARK = "benchmark";
    public static final String ORIGIN_SESSION_DERIVED = "session_derived";
    public static final String ORIGIN_MANUAL = "manual";
    public static final Set<String> ALLOWED_ORIGINS =
        Set.of(ORIGIN_BENCHMARK, ORIGIN_SESSION_DERIVED, ORIGIN_MANUAL);

    // getters/setters
}
```

### 2.2 EvalDatasetEntity（新）

```java
@Entity
@Table(name = "t_eval_dataset")
@EntityListeners(AuditingEntityListener.class)
public class EvalDatasetEntity {
    @Id
    @Column(length = 36)
    private String id;  // UUID

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Long ownerId;

    // KNOWN TECH DEBT (r3 reviewer finding 处置):
    // agentId 用 VARCHAR(36) 跟现有 EvalScenarioEntity.agentId 一致。
    // 跟 AgentEntity.id (BIGINT) 类型不匹配是历史包袱 — V1 不修，V2 统一 ID
    // 类型时一起改 (跟 EvalScenarioEntity / TraceScenarioImportService /
    // EvalOrchestrator 等所有现有 agent_id VARCHAR 调用一并迁)。
    // 当前 join 模式: WHERE CAST(a.id AS VARCHAR) = ed.agent_id (or JPQL
    // 的 Long.parseLong(ed.agentId)) - 同 EvalScenario path 已 verified work。
    @Column(length = 36)
    private String agentId;  // nullable

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> tags;

    @Column(nullable = false)
    private boolean isPublic = false;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    // getters/setters/无参构造
}
```

### 2.3 EvalDatasetVersionEntity（新）

```java
@Entity
@Table(name = "t_eval_dataset_version")
public class EvalDatasetVersionEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "dataset_id", nullable = false, length = 36)
    private String datasetId;

    @Column(nullable = false)
    private Integer versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> compositionStats;

    // ★ r4 W1 fix: 漏字段 (DB column 加了 Entity 没声明 → silent null → SHA256 diff 不 work)
    @Column(name = "composition_hash", length = 64)
    private String compositionHash;

    // ★ r4 D1 fix: 第一次 A/B run 完反写真值，FE 优先于 expected 显示
    @Column(name = "actual_baseline_pass_rate")
    private Double actualBaselinePassRate;

    @CreatedDate
    private Instant createdAt;

    @Column
    private Long createdBy;

    // getters/setters (含 getCompositionHash/setCompositionHash + getActualBaselinePassRate/setActualBaselinePassRate)
}
```

### 2.4 EvalDatasetVersionScenarioEntity（关联表，新）

```java
@Entity
@Table(name = "t_eval_dataset_version_scenario")
@IdClass(EvalDatasetVersionScenarioId.class)
public class EvalDatasetVersionScenarioEntity {
    @Id @Column(name = "dataset_version_id", length = 36)
    private String datasetVersionId;

    @Id @Column(name = "scenario_id", length = 36)
    private String scenarioId;

    // getters/setters
}

public class EvalDatasetVersionScenarioId implements Serializable {
    private String datasetVersionId;
    private String scenarioId;
    // equals/hashCode
}
```

### 2.5 PromptAbRunEntity 扩展

```java
@Column(name = "dataset_version_id", length = 36)
private String datasetVersionId;
```

## 3. Service 设计

### 3.1 EvalDatasetService（新）

```java
@Service
public class EvalDatasetService {
    private final BaselinePassRateHeuristic heuristic;  // ★ r4 D4: 注入 strategy
    // ...

    // 核心 CRUD
    public EvalDatasetEntity createDataset(CreateDatasetRequest req);
    public List<EvalDatasetEntity> listDatasets(Long ownerId, String agentId);
    public List<EvalDatasetVersionEntity> listVersions(String datasetId);

    // Version 编排
    public EvalDatasetVersionEntity publishVersion(String datasetId, List<String> scenarioIds, Long userId);
    // ★ r4 W4 fix: 空 scenarioIds → throw IllegalArgumentException (见 §1.3)
    public List<EvalScenarioEntity> getScenariosForVersion(String datasetVersionId);

    // 分析 (V2 可拆出 EvalDatasetAnalyzer)
    public Map<String, Integer> computeCompositionStats(List<String> scenarioIds);
    public DatasetHealthAssessment assessHealth(String datasetVersionId);
    // 返回 {isHealthy, warnings: [...]}, 警告示例："dataset 100% session_derived, baseline likely 0%"
}
```

**SRP 注释** (r4 java-design W3): 当前 7 方法跨 3 簇（CRUD / version 编排 / 分析），V1 单 Service 接受。V2 backlog: 拆 `EvalDatasetAnalyzer` (assessHealth + computeCompositionStats + computeExpectedRate 三方法搬出去)。

### 3.2 AbEvalPipeline 改动

新加 `run` overload + 旧 overload 标 @Deprecated（r4 D2 fix）：

```java
// ★ 新 path (V1 prompt surface caller 全走这个)
public void run(PromptAbRunEntity abRun,
                PromptVersionEntity candidate,
                PromptVersionEntity baselineVersion,
                AgentEntity agent,
                String datasetVersionId);

// ★ r4 D2 fix: 旧 path 标 @Deprecated，log.warn 提示 caller 迁移
// 攻击面：attribution path (ephemeral scenarios) 暂留旧 path，V2 干掉
@Deprecated(forRemoval = true, since = "EVAL-DATASET-LAYER V1")
public void run(PromptAbRunEntity abRun,
                PromptVersionEntity candidate,
                PromptVersionEntity baselineVersion,
                AgentEntity agent,
                List<EvalScenarioEntity> scenarios) {
    log.warn("AbEvalPipeline.run(scenarios) 旧 overload 调用 — V2 将删除，请迁到 datasetVersionId 路径 (caller: {})",
        Thread.currentThread().getStackTrace()[2]);
    // ... 原逻辑保持
}
```

新 path 内部：
```java
List<EvalScenarioEntity> scenarios = datasetService.getScenariosForVersion(datasetVersionId);
abRun.setDatasetVersionId(datasetVersionId);
abRun.setCompositionHashSnapshot(datasetVersionEntity.getCompositionHash());  // verify A/B 跑的版本完整
// ... 跑 baseline + candidate
// ★ r4 D1 fix: A/B run 完成后反写 datasetVersion.actualBaselinePassRate (移动平均如果 ≥2 次 run)
if (baselinePassRate != null) {
    datasetVersionEntity.setActualBaselinePassRate(
        movingAverage(datasetVersionEntity.getActualBaselinePassRate(), baselinePassRate)
    );
    datasetVersionRepository.save(datasetVersionEntity);
}
```

### 3.3 PromptImproverService.runAbTestAgainst 改动（r4 D3 fix — Param Object）

5-arg 改为 record param object 防参数膨胀：

```java
// ★ r4 D3 fix: 引入 AbEvalRunRequest record，PromptImproverService 已 1051 行 — plan 阶段是最便宜引入时机
public record AbEvalRunRequest(
    String agentId,                    // 必填
    String baselineVersionId,          // null = agent.activePromptVersionId
    String candidateVersionId,         // 必填
    List<String> evalScenarioIds,      // 跟 datasetVersionId 互斥
    String datasetVersionId            // 跟 evalScenarioIds 互斥；二者皆 null 走 ephemeral fallback
) {
    public AbEvalRunRequest {
        Objects.requireNonNull(agentId, "agentId required");
        Objects.requireNonNull(candidateVersionId, "candidateVersionId required");
        // 互斥校验
        if (evalScenarioIds != null && !evalScenarioIds.isEmpty()
                && datasetVersionId != null && !datasetVersionId.isBlank()) {
            throw new IllegalArgumentException(
                "evalScenarioIds 跟 datasetVersionId 互斥（要么显式 scenarios 列表，要么走 dataset_version；都给会导致歧义）"
            );
        }
    }
}

public String runAbTestAgainst(AbEvalRunRequest req);
```

逻辑：
- `req.datasetVersionId()` 非 null → 从 dataset 读 scenarios + 锁 dataset_version_id 到 abRun
- `req.evalScenarioIds()` 非空 → 直接用
- 都 null → 现有 fallback (held_out + ephemeral)

**向后兼容**: 5-arg `runAbTestAgainst(String, String, String, List<String>, String)` 标 @Deprecated 留 V1 → V2 删除。Controller / 测试逐步迁移。

## 4. Controller / REST

### 4.1 新 endpoint

```
POST   /api/eval/datasets                   创建 dataset
GET    /api/eval/datasets?ownerId=&agentId= 列 dataset
GET    /api/eval/datasets/{id}              get dataset
POST   /api/eval/datasets/{id}/versions     发新版（body: scenario_ids）
GET    /api/eval/datasets/{id}/versions     列 version
GET    /api/eval/dataset-versions/{id}      get version + scenarios
GET    /api/eval/dataset-versions/{id}/health   composition health
GET    /api/eval/scenarios?source_type=          按 source_type filter scenarios
```

### 4.2 现有 endpoint 扩展

- `GET /api/eval/scenarios` 加 `source_type` / `source_ref` 查询参数
- `POST /api/agents/{agentId}/prompt-versions/{versionId}/run-ab` body 加 `datasetVersionId` 字段

## 5. FE 改动

### 5.1 EvalScenarios page 重构

- 顶部加 source_type tab: All / Benchmark / Session-derived / Manual
- 顶部加 dataset selector (dropdown)，选某 dataset 后只看属于它的 scenarios
- 列表加 source_type column + source_ref column

### 5.2 新 page: Datasets

```
/eval/datasets
  ├─ list （name / agent / version count / scenario count / latest health badge）
  ├─ create button
  └─ detail page → versions table → version detail → scenarios in version
```

### 5.3 A/B run UI 改动

- 飞轮 SkillAbPanel / PromptAbPanel：dataset_version_id 显示 + selector
- 触发新 A/B 必须选 dataset version
- 跑出来 results 表头加 "Dataset: main-assistant-baseline-v1@v1" 标识

## 6. Migration 顺序

```
V109 — t_eval_scenario.source_type + source_ref + idx + 现有 6 行 retroactive
V110 — t_eval_dataset + t_eval_dataset_version + t_eval_dataset_version_scenario
V111 — t_prompt_ab_run.dataset_version_id
V112 — seed 30 benchmark scenarios + 3 named datasets + 各自 v1
```

## 7. 测试策略

### 7.1 Unit (Mockito)
- EvalDatasetService.createDataset / publishVersion / getScenariosForVersion / assessHealth
- EvalScenarioEntity ORIGIN enum constraint
- AbEvalPipeline run with datasetVersionId

### 7.2 IT (Testcontainers / embedded PG)
- V109 migration idempotent + 现有 6 行真活 retroactive
- t_eval_dataset_version_scenario.ON DELETE RESTRICT 真活
- EvalDatasetService.publishVersion(scenario1, scenario2) 真活，跑 getScenariosForVersion 返还原集合
- t_prompt_ab_run.dataset_version_id 关联完整

### 7.3 真活 dogfood
- 跑一个 dataset_version=baseline-v1 v1 的 A/B 真活：baseline ≥ 30% pass rate
- composition policy 警告真触发

## 8. Iron Law 守住

本包**完全不动**：
- 核心 7+1 BE 文件 (SessionEntity / SessionMessageEntity / ChatService / SessionService / CompactionService / AgentLoopEngine / Message / ContentBlock)
- 核心 3 FE 文件 (Chat.tsx / ChatWindow.tsx / Layout.tsx)
- 已有 LlmProvider / Hook / Skill registry / SubAgent 子系统

改动范围 = 全在 eval 子系统 + 飞轮 A/B 路径 + FE EvalScenarios + 新 Datasets page，跟核心持久化/Engine 解耦。

## 9. 风险与 mitigation

| 风险 | mitigation |
|---|---|
| Benchmark scenarios 跟 SkillForge agent 实际能力差距太大，全 0% pass | 先挑 10 试跑看分布 → 调题 → 再扩到 30 |
| Migration V109 让 NOT NULL UPDATE 撞历史脏数据 | UPDATE WHERE source_type IS NULL 先跑，ALTER NOT NULL 跟后 |
| Iron Law 触碰 | 设计上不动 — code reviewer 显式审 |
| 30 benchmark scenarios 数量大，seed migration 巨大 | 拆分：V112 + V113 各 15 题，更可读 |
| Dataset 删了关联 version 还在 | t_eval_dataset_version ON DELETE CASCADE 自动级联清理 |
