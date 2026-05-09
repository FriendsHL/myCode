# P9-5 技术方案

---
id: P9-5
status: design-ratified
prd: ./prd.md
risk: Full
created: 2026-04-28
updated: 2026-05-09
ratified: 2026-05-09
---

## TL;DR

Full compact 完成后，往 summary 之后追加一段 recovery payload，让 LLM 在"失忆"后知道最近碰过哪些文件 / 可用工具 / 注册 skill。数据来源用内存 FileStateCache（候选 C），由 FileTool 在 read/write/edit 时主动 put。

**P9-4 已暂缓**——partial_head/partial_tail 痛点已被 microcompact 更精确覆盖，详见 [deferred/P9-4-partial-compact](../../deferred/P9-4-partial-compact/index.md)。本方案不再涉及 partial compact 机制。

## 关键决策（待用户 ratify）

| 决策 | 推荐 | 理由（2026-05-09 调研后修正） | 替代方案 |
| --- | --- | --- | --- |
| 最近文件数据来源 | **C: 内存 FileStateCache** | 0 schema 增量 / FileTool 在 `execute()` 末尾 hook（SkillContext 已有 sessionId） | A trace spans 反查（间接 + OBS retention） / B 新表（多 migration） |
| FileStateCache 存活范围 | **全局 `@Component` ConcurrentMap，session 终态仅 evict 该 session entries** | 跨 session 命中需要全局视图；LoopContext per-run 太短 | 挂 LoopContext（无法跨 session）/ 持久化（价值低） |
| Recovery 注入时点 | `CompactionService.persistCompactResult` Line 559 后（appendMessages 前） | 4 种 full compact 触发路径都汇聚到此 | Phase 4 新增（多一次 DB 写入） |
| Recovery 注入范围 | **仅最近文件** | 调研：tool schema + skill listing 已每次随 request/tool description 重发，recovery 不需重注 | 文件 + tool delta + skill listing（重复发，浪费） |
| Recovery 注入格式 | plain user message 前缀 | 不依赖 system-reminder 框架（独立需求包推进中） | `<system-reminder>` 包装（等 system-reminder 落地后迁移） |

## 架构

```
[CompactionService.persistCompactResult line 559 (4 路径汇聚点)]
            │
            ├─→ [LLM summary message] (已有，BUG-F-2 已 forced USER role)
            │
            ├─→ [retained young-gen messages] (已有)
            │
            └─→ [recoveryPayload user message] ← 新增
                    │
                    └─ RecoveryPayloadBuilder.build(sessionId)
                          └─ FileStateCache.snapshot(sessionId, top=5, budgetTokens=25K)
                                └─ 每文件: { path, head 5K token, lineCount, lastReadAt }

[FileReadTool/Write/Edit.execute(Map input, SkillContext context)]
            │
            └─→ fileStateCache.put(context.getSessionId(), filePath, content)

[AgentLoopEngine.run() afterLoop hook (line 1305-1312)]
            │
            └─→ fileStateCache.evictSession(loopCtx.getSessionId())
```

**FileStateCache 挂载**：独立 `@Component`（**不挂 LoopContext**——per-run 生命周期太短，跨 session 命中需要全局视图）。

## 后端改动

### 1. FileStateCache（新建，全局 `@Component`）

`skillforge-core/src/main/java/com/skillforge/core/compact/recovery/FileStateCache.java`：

```java
@Component
public class FileStateCache {
    // sessionId -> (filePath -> FileEntry)，按 lastReadAt 排序在 snapshot 时做
    private final ConcurrentMap<String, ConcurrentMap<String, FileEntry>> sessionCaches
        = new ConcurrentHashMap<>();

    public record FileEntry(String path, String headContent, int lineCount, Instant lastReadAt) {}

    public void put(String sessionId, String path, String content) { ... }   // 写入时 truncateToHead 5K token
    public List<FileEntry> snapshot(String sessionId, int topN, int budgetTokens) { ... }  // 按 lastReadAt DESC + budget
    public void evictSession(String sessionId) { sessionCaches.remove(sessionId); }
}
```

线程安全：双层 `ConcurrentMap`，写入幂等（同 path 覆盖）。

### 2. RecoveryPayloadBuilder（新建）

`skillforge-core/src/main/java/com/skillforge/core/compact/recovery/RecoveryPayloadBuilder.java`：

- 接收 `FileStateCache` + budget config（默认 5 文件 / 25K token）
- 调 `FileStateCache.snapshot(sessionId, 5, 25_000)` 拿文件
- 拼成单一 user message：

```
[Recovery payload — 5 most recently accessed files at ${compactTime}]

### /path/to/file1 (lastRead 14:32, lines 1-128)
```typescript
... head 5K token ...
```

### /path/to/file2 ...
```

返回 `LlmMessage(role=USER, content=text)` 或 null（cache 空）。

### 3. CompactionService 接入（Phase 3 内 line 559 后）

调研确认：`CompactionService.persistCompactResult()` (line 522-603) 是 4 种 full compact 触发路径（B2 hard / Preemptive / Post-overflow / SessionMemory）的**汇聚点**。具体接入：

```java
// CompactionService.java line 553-559（已有，append retained messages）
for (...) {
    appends.add(new SessionService.AppendMessage(retained, ...));
}

// === 新增（P9-5）：在 line 559 后、line 560 appendMessages 前插入 ===
if (recoveryPayloadBuilder != null) {
    LlmMessage recovery = recoveryPayloadBuilder.build(sessionId);
    if (recovery != null) {
        appends.add(new SessionService.AppendMessage(
            recovery, MSG_TYPE_RECOVERY, metadata));
    }
}

// 已有
sessionService.appendMessages(sessionId, appends);  // line 560
```

> 顺序：boundary → summary（USER role，BUG-F-2 已 fix line 537）→ retained young-gen → **recoveryPayload (新)** → appendMessages 一次落库。

### 4. FileTool hook 写入（精确接入位置）

各 tool 的 `execute(Map input, SkillContext context)` 末尾、`return SkillResult.success(...)` 之前：

| 文件 | 接入行号 | 操作 |
| --- | --- | --- |
| `FileReadTool.java` | line 100（return 前） | `fileStateCache.put(context.getSessionId(), filePath, content)` |
| `FileWriteTool.java` | line 75（return 前） | `fileStateCache.put(context.getSessionId(), filePath, content)` |
| `FileEditTool.java` | line 114（return 前） | `fileStateCache.put(context.getSessionId(), filePath, updatedContent)` |

`SkillContext` 已有 `getSessionId()` getter（line 42-47），**不需要改 SkillContext**。各 tool 构造器加 `FileStateCache` 依赖即可。

### 5. Session 终态清理

`AgentLoopEngine.run()` afterLoop hook（line 1305-1312）末尾追加：

```java
if (fileStateCache != null) {
    fileStateCache.evictSession(loopCtx.getSessionId());
}
```

或在 run() 方法整体加 try-finally（line 1320 return 前），保证异常路径也清理。

## 数据来源已决项（2026-05-09 调研后）

- **toolSchemaDelta**：**不做**。`ClaudeProvider.buildRequestBody:508-525` 每次 LLM 调用都完整重发 tools 字段，cache_control 标记 ephemeral 命中 prompt cache。Recovery 重注会冗余。
- **skillListing**：**不做**。`AgentLoopEngine:1582-1656` 通过 `skillLoaderToolSchema()` 把 skill 列表注入 SkillLoaderTool 的 description，每次 LLM 调用都自动包含。Recovery 重注会冗余。
- **activePlan**：**本期不做**。SkillForge 当前没有 plan-mode 概念（无 `Plan` entity、无 plan-mode tool）。如未来引入 plan-mode 再加 recovery 注入。

## 数据模型 / Migration

无（FileStateCache 仅内存）。

## 前端改动

无（recovery payload 是 user message 不需要专门展示，FE 已能渲染所有 message 类型）。

## 错误处理 / 安全

- FileStateCache snapshot 失败：log warn，recovery payload 为 null，compact 继续（不阻塞）。
- 超预算：按 LRU 截断，不破坏 tool_use ↔ tool_result 配对（recovery payload 是独立 user message，与配对无关）。
- 文件内容里的敏感信息：不做特殊处理（文件本身就在历史里被读过）。

## 实施计划

- [ ] 等用户 ratify D1-D5 决策
- [ ] FileStateCache 实现 + 单测
- [ ] RecoveryPayloadBuilder 实现 + 单测
- [ ] FileTool hook 接入
- [ ] CompactionService Phase 3 拼接
- [ ] Session 终态清理接入
- [ ] Integration test：触发 full compact 后验证 recovery payload 出现
- [ ] Full Pipeline review

## 测试计划

- [ ] FileStateCache LRU + budget 截断单测
- [ ] RecoveryPayloadBuilder 拼接格式单测
- [ ] 4 路径触发（B2 / Preemptive / Post-overflow / SessionMemory）integration test
- [ ] Session 终态清理单测
- [ ] 真实长 session sanity：手动 `/compact full`，观察下轮 LLM 知道刚才文件

## 风险

- FileStateCache 内存占用：5 文件 × 5K token × N session。N 多时考虑全局 LRU。
- 文件被外部修改后 cache stale：snapshot 时不重读 disk，仅用 cache 内容；标 `lastReadAt` 让 LLM 知道是 N 分钟前的快照。
- recovery payload 太长撞下次 compact：与 LLM summary 一起算入 young-gen 之前，下次 compact 时一并被压。

## 评审记录

待用户 ratify 决策后进 Full Pipeline review。
