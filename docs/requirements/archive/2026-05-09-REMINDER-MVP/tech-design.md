# REMINDER-MVP 技术方案

---
id: REMINDER-MVP
status: design-ratified
prd: ./prd.md
risk: Full
created: 2026-05-09
updated: 2026-05-09
ratified: 2026-05-09
---

## TL;DR

新建 `skillforge-core/.../reminder/` 包，含 `ReminderBuilder` + `ReminderSource` 接口 + 3 个 source 实现。`AgentLoopEngine.runInternal` 在 LlmRequest 构建前调 builder 拼 reminder 文本 append 到 `promptSuffix`。`RecoveryPayloadBuilder.build` 输出加 `<system-reminder>` 包装。yaml 加 per-source 配置。

## 关键决策（已 ratified）

见 [PRD D1-D8](prd.md#决策清单已-ratified-2026-05-09)。

## 架构

```
[AgentLoopEngine.runInternal() line 691-758 promptSuffix 段]
            │
            └─→ ReminderBuilder.build(loopCtx, sessionContext)
                    │
                    ├─ 1. ContextUsageSource (interval=1, threshold 70%)
                    │       └─ 用 RequestTokenEstimator + CompactThresholds
                    │
                    ├─ 2. MemoryAgeSource (interval=5, stale ≥ 7 天)
                    │       └─ 查 t_memory.lastRecalledAt + status=ACTIVE
                    │
                    ├─ 3. FileActivitySource (interval=5, top 5 文件 / age > 30s)
                    │       └─ FileStateCache.snapshot（P9-5 复用）
                    │
                    └─ 4. P9-5 recovery payload (在 CompactionService 调用，
                          通过 RecoveryPayloadBuilder.build 输出已加 <system-reminder>)

每个 source：
  - shouldEmit(ctx, currentTurn) → boolean (含 Turn Count Debounce + threshold 检查)
  - emit(ctx) → ReminderEntry { text, tokenCount }
  - per-source state: lastEmittedAtTurn (持有在 LoopContext 或 ReminderBuilder)

ReminderBuilder：
  - 按 D7 顺序遍历 source
  - 累计 tokenCount，超 5K budget 后续 source 跳过
  - 拼接：所有 entry text 用 \n 连接，外层包 <system-reminder>...</system-reminder>
  - 全空时返回空字符串
```

## 后端改动

### 1. 新建 reminder 包

`skillforge-core/src/main/java/com/skillforge/core/reminder/`：

#### 1.1 `ReminderSource.java`（接口）

```java
public interface ReminderSource {
    String getName();  // 用于 yaml 配置 key 和 LoopContext state key
    boolean shouldEmit(ReminderContext ctx);  // 含 Turn Count Debounce + threshold
    ReminderEntry emit(ReminderContext ctx);  // 实际生成文本
}

public record ReminderEntry(String text, int estimatedTokens) {}

public class ReminderContext {
    String sessionId;
    Long userId;
    int currentTurnIndex;  // messages.size()
    LoopContext loopCtx;  // 拿 lastEmittedAtTurn map
    // ... 其他可选 dependencies
}
```

#### 1.2 `ReminderBuilder.java`

`@Bean` POJO（framework-free，与 P9-5 同模式）：

```java
public class ReminderBuilder {
    private final List<ReminderSource> sources;  // 注入 ordered list (D7)
    private final int totalBudgetTokens;  // 默认 5000
    private final boolean globalEnabled;  // skillforge.reminder.enabled

    // 构造器接 sources + budget + globalEnabled
    public String build(ReminderContext ctx) {
        if (!globalEnabled) return "";
        List<ReminderEntry> entries = new ArrayList<>();
        int totalTokens = 0;
        for (ReminderSource s : sources) {
            if (!s.shouldEmit(ctx)) continue;
            ReminderEntry e = s.emit(ctx);
            if (e == null) continue;
            if (totalTokens + e.estimatedTokens() > totalBudgetTokens) break;
            entries.add(e);
            totalTokens += e.estimatedTokens();
        }
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n<system-reminder>\n");
        for (ReminderEntry e : entries) {
            sb.append(e.text()).append("\n");
        }
        sb.append("</system-reminder>\n");
        return sb.toString();
    }
}
```

#### 1.3 `MemoryAgeSource.java`

```java
public class MemoryAgeSource implements ReminderSource {
    private final MemoryService memoryService;
    private final int intervalTurns;       // 默认 5
    private final int staleDaysThreshold;  // 默认 7
    private final boolean enabled;

    @Override
    public String getName() { return "memory-age"; }

    @Override
    public boolean shouldEmit(ReminderContext ctx) {
        if (!enabled) return false;
        Integer lastEmitted = ctx.getLoopCtx().getReminderLastEmitted("memory-age");
        if (lastEmitted != null && ctx.getCurrentTurnIndex() - lastEmitted < intervalTurns) {
            return false;
        }
        // 查是否有 stale memory
        long staleCount = memoryService.countStaleMemories(ctx.getUserId(), staleDaysThreshold);
        return staleCount > 0;  // 没 stale 就不必提
    }

    @Override
    public ReminderEntry emit(ReminderContext ctx) {
        long activeCount = memoryService.countActive(ctx.getUserId());
        long staleCount = memoryService.countStaleMemories(ctx.getUserId(), staleDaysThreshold);
        Optional<Instant> lastRecalled = memoryService.findLastRecalledAt(ctx.getUserId());
        ctx.getLoopCtx().setReminderLastEmitted("memory-age", ctx.getCurrentTurnIndex());

        String text = String.format("Memory: %d active entries, %d stale (>%d days)%s",
            activeCount, staleCount, staleDaysThreshold,
            lastRecalled.map(t -> ", last recalled " + formatAge(t)).orElse(""));
        return new ReminderEntry(text, estimateTokens(text));
    }
}
```

`MemoryService` 需要补 `countStaleMemories(userId, days) / countActive / findLastRecalledAt`（如果还没有）。

#### 1.4 `ContextUsageSource.java`

```java
public class ContextUsageSource implements ReminderSource {
    private final RequestTokenEstimator estimator;
    private final CompactThresholds thresholds;
    private final int intervalTurns;        // 默认 1（每 turn 都算）
    private final int pctThreshold;         // 默认 70（>= 70% 才注）
    private final boolean enabled;

    @Override
    public boolean shouldEmit(ReminderContext ctx) {
        if (!enabled) return false;
        // interval=1 实际不需要 debounce，但走通用逻辑兜底
        Integer lastEmitted = ctx.getLoopCtx().getReminderLastEmitted("context-usage");
        if (lastEmitted != null && ctx.getCurrentTurnIndex() - lastEmitted < intervalTurns) {
            return false;
        }
        double ratio = estimator.currentRatio(ctx.getMessages(), ctx.getMaxTokens());
        return ratio * 100 >= pctThreshold;
    }

    @Override
    public ReminderEntry emit(ReminderContext ctx) {
        double ratio = estimator.currentRatio(ctx.getMessages(), ctx.getMaxTokens());
        int used = estimator.currentTokens(ctx.getMessages());
        int max = ctx.getMaxTokens();
        ctx.getLoopCtx().setReminderLastEmitted("context-usage", ctx.getCurrentTurnIndex());
        // 距三档阈值
        String nextThresholdHint = nextThresholdHint(ratio, thresholds);  // "soft compact at 60%" 已过 / "hard at 80%"
        String text = String.format("Context: %.0f%% used (%d/%d tokens)%s",
            ratio * 100, used, max,
            nextThresholdHint.isEmpty() ? "" : ", " + nextThresholdHint);
        return new ReminderEntry(text, estimateTokens(text));
    }
}
```

#### 1.5 `FileActivitySource.java`

```java
public class FileActivitySource implements ReminderSource {
    private final FileStateCache fileStateCache;  // P9-5 复用
    private final int intervalTurns;       // 默认 5
    private final int maxFiles;            // 默认 5
    private final long minAgeSeconds;      // 默认 30（lastReadAt 至少多久前才纳入）
    private final boolean enabled;

    @Override
    public boolean shouldEmit(ReminderContext ctx) {
        if (!enabled) return false;
        Integer lastEmitted = ctx.getLoopCtx().getReminderLastEmitted("file-activity");
        if (lastEmitted != null && ctx.getCurrentTurnIndex() - lastEmitted < intervalTurns) {
            return false;
        }
        return !fileStateCache.snapshot(ctx.getSessionId(), maxFiles, Integer.MAX_VALUE).isEmpty();
    }

    @Override
    public ReminderEntry emit(ReminderContext ctx) {
        var entries = fileStateCache.snapshot(ctx.getSessionId(), maxFiles, Integer.MAX_VALUE);
        if (entries.isEmpty()) return null;
        ctx.getLoopCtx().setReminderLastEmitted("file-activity", ctx.getCurrentTurnIndex());
        StringBuilder sb = new StringBuilder("Recent files: ");
        boolean first = true;
        Instant now = Instant.now();
        for (var e : entries) {
            long ageSec = Duration.between(e.lastReadAt(), now).getSeconds();
            if (ageSec < minAgeSeconds) continue;
            if (!first) sb.append(", ");
            sb.append(e.path()).append(" (").append(formatAge(e.lastReadAt())).append(")");
            first = false;
        }
        if (first) return null;  // 全部太新跳过
        return new ReminderEntry(sb.toString(), estimateTokens(sb.toString()));
    }
}
```

### 2. LoopContext 加 reminder state map

`LoopContext.java`：

```java
private final Map<String, Integer> reminderLastEmitted = new ConcurrentHashMap<>();

public Integer getReminderLastEmitted(String sourceName) { ... }
public void setReminderLastEmitted(String sourceName, int turnIndex) { ... }
```

per-session 自动清理（LoopContext 本身就是 per-run 的）。

### 3. AgentLoopEngine.runInternal 接入

`AgentLoopEngine.java:691-758`（promptSuffix 段）：

在构建 LlmRequest 之前（line ~750 左右，promptSuffix 已有 deprecation warnings / loop-end reminders 等内容追加完之后），加：

```java
// === REMINDER-MVP: append <system-reminder> to dynamic prompt segment ===
if (reminderBuilder != null) {
    try {
        ReminderContext rCtx = new ReminderContext(sessionId, userId, messages.size(), loopCtx, ...);
        String reminderText = reminderBuilder.build(rCtx);
        if (!reminderText.isEmpty()) {
            promptSuffix.append(reminderText);
        }
    } catch (Exception e) {
        log.warn("ReminderBuilder failed for session={}: {}", sessionId, e.getMessage());
        // 不阻塞 LLM 请求
    }
}
```

构造器或 setter 注入 `ReminderBuilder`（@Autowired required=false 兼容）。

### 4. RecoveryPayloadBuilder.build 改输出

`skillforge-core/.../compact/recovery/RecoveryPayloadBuilder.java`：

```java
public LlmMessage build(String sessionId) {
    // ... 已有逻辑拼 sb 文本 ...
    if (sb.length() == 0) return null;
    // 旧：return Message.user(sb.toString());
    // 新：用 <system-reminder> 包装
    String wrapped = "<system-reminder>\n" + sb.toString() + "\n</system-reminder>";
    return Message.user(wrapped);
}
```

P9-5 既有测试更新断言：`recoveryRow.getContent().startsWith("<system-reminder>")` + `.endsWith("</system-reminder>")`。

### 5. SkillForgeConfig 注入

```java
@Bean
public ReminderBuilder reminderBuilder(
        MemoryAgeSource memoryAgeSource,
        ContextUsageSource contextUsageSource,
        FileActivitySource fileActivitySource,
        @Value("${skillforge.reminder.enabled:true}") boolean enabled,
        @Value("${skillforge.reminder.total-budget-tokens:5000}") int budget) {
    return new ReminderBuilder(
        List.of(contextUsageSource, memoryAgeSource, fileActivitySource),  // D7 顺序
        budget,
        enabled);
}

@Bean
public MemoryAgeSource memoryAgeSource(
        MemoryService memoryService,
        @Value("${skillforge.reminder.memory-age.enabled:true}") boolean enabled,
        @Value("${skillforge.reminder.memory-age.interval-turns:5}") int interval,
        @Value("${skillforge.reminder.memory-age.stale-days-threshold:7}") int staleDays) {
    return new MemoryAgeSource(memoryService, interval, staleDays, enabled);
}

// ... ContextUsageSource / FileActivitySource 同样

// AgentLoopEngine 注入
engine.setReminderBuilder(reminderBuilder);
```

### 6. application.yml

```yaml
skillforge:
  reminder:
    enabled: true                      # 全局总开关
    total-budget-tokens: 5000          # 总 token 预算
    memory-age:
      enabled: true
      interval-turns: 5
      stale-days-threshold: 7
    context-usage:
      enabled: true
      interval-turns: 1
      pct-threshold: 70
    file-activity:
      enabled: true
      interval-turns: 5
      max-files: 5
      min-age-seconds: 30
```

## 测试计划

### 单元测试（≥ 25 个）

1. `ReminderBuilderTest` — 拼接顺序 / budget 截断 / per-source disable / globalEnabled=false / 全空返回 ""
2. `MemoryAgeSourceTest` — Turn Count Debounce / 无 stale memory 不 emit / lastRecalledAt formatted
3. `ContextUsageSourceTest` — < 70% 不 emit / >= 70% emit 含三档阈值距离 hint / interval=1 验真
4. `FileActivitySourceTest` — empty cache / 全部 < 30s 跳过 / 拼 path + age 格式
5. `RecoveryPayloadBuilderTest`（更新）— 输出 `<system-reminder>` 包装

### Integration 测试

1. `AgentLoopEngine` 真启动 + 撞 70% context → LLM request 的 system prompt 含 `<system-reminder>` 段含 `Context:` 文本
2. `globalEnabled=false` → reminder 段消失（feature flag 兜底）
3. P9-5 4 路径 full compact 测试更新断言（recovery row content 含 `<system-reminder>` 包装）

## 风险

- ContextUsageSource interval=1 每 turn 都算 token —— 性能敏感，但 RequestTokenEstimator 是已有现成（P9-5 已用过），cost 可控
- per-source 默认 enabled=true，用户没注意时部分 reminder 一直注 —— 用户可在 yaml 关；总 budget 5K 兜底
- MemoryAgeSource 查 DB 算 staleCount —— 加 cache 或简单 SQL `COUNT(*) WHERE last_recalled_at < ?` 索引应可
- P9-5 既有测试更新断言可能漏改 —— integration test 兜底

## 实施顺序

1. LoopContext 加 reminder state map
2. ReminderSource 接口 + ReminderEntry / ReminderContext
3. ReminderBuilder
4. 3 个 source 实现 + 测试
5. AgentLoopEngine.runInternal 接入
6. RecoveryPayloadBuilder.build 改输出 + P9-5 测试更新
7. SkillForgeConfig + application.yml
8. Integration tests

## 评审记录

D1-D8 已 ratified 2026-05-09，进 Full Pipeline。
