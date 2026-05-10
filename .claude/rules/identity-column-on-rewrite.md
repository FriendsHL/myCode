# Identity Column on Rewrite

> **触发路径**：`**/SessionService.java` / `**/SessionMessageEntity.java` / `**/db/migration/V*.sql` 加列影响 `t_session_message`
>
> **来源**：Q1 commit `a4100f7` 修 P9-5 `fda2374` 引入的 trace_id wipe regression 时总结。2026-05-10。

## Iron Law

```
给 t_session_message 加 identity / 关联列时（即"指向其它表 PK 或表达业务身份"
而不是"派生 / 审计"的列），**必须同步扩展 SessionService.rewriteMessages 内
部的 snapshot+patch 逻辑**，否则 rewrite 路径（DELETE+INSERT 模式）会默默
把该列清成 null。
```

## 为什么

`SessionService.rewriteMessages` 的现行实现：

```java
// 见 SessionService.java updateSessionMessages 内
deleteBySessionId(id);                       // 删整段
appendRowsOnce(id, patchedMessages);         // 重新写
```

`AppendMessage` 默认 3-arg 构造器 `(message, msgType, metadata)` —— 缺省的字段（`controlId` / `answeredAt` / `traceId`）全是 null。除非 caller 显式用 7-arg full constructor 或 SessionService 内部加 patch 逻辑回填，否则 rewrite 后这些列变 null。

Q1 fix 给 `trace_id` 加了显式 patch（`snapshotTraceIds` + `patchTraceIds`，commit `a4100f7`），但只针对 trace_id 一列。**未来加新 identity 列必须扩展同样的逻辑**，否则历史复现。

## 列分类（决定是否要 preserve）

| 列类型 | 例子 | 加列时 rewrite preserve？ |
|---|---|---|
| **identity** / 业务身份 / 关联外键语义 | `trace_id` / 候选：`origin_span_id` / `root_trace_id` | **必须 preserve** —— 否则 rewrite 后丢失关联 |
| **business** / 业务字段 | `content_json` / `metadata_json` / `msg_type` | 由 caller 传，rewrite 用最新值（不需 preserve）|
| **derived / audit** | `created_at` / `updated_at` | rewrite 写新值（创建新行视角）|
| **counter** | `seq_no` | 由 appendRowsOnce 按 base+i 重算 |

判断标准：**这条列回答"这条 row 来自哪里 / 关联哪个上层概念" 还是"这条 row 现在内容是啥"**？前者属 identity，后者属 business。

## 加列时 checklist

加新列到 `t_session_message`（V<n>__add_<column>.sql migration + Entity 字段）时：

1. **判断列类型**（按上表）。derived / audit / counter 跳过下面所有步骤。

2. **如果是 identity 列**：

   2a. 在 `SessionService` 加 `snapshot<列>(sessionId)` 私有方法，参考 `snapshotTraceIds`：
   ```java
   private Map<Long, X> snapshotXByseqNo(String sessionId) {
       List<XView> rows = sessionMessageRepository.findNonNullXProjections(sessionId);
       Map<Long, X> map = new HashMap<>();
       for (XView v : rows) map.put(v.getSeqNo(), v.getX());
       return map;
   }
   ```

   2b. 在 `SessionMessageRepository` 加 projection interface + 查询：
   ```java
   interface XView {
       long getSeqNo();
       X getX();
   }
   @Query("SELECT m.seqNo AS seqNo, m.x AS x FROM SessionMessageEntity m " +
          "WHERE m.sessionId = :sessionId AND m.x IS NOT NULL")
   List<XView> findNonNullXProjections(@Param("sessionId") String sessionId);
   ```

   2c. 在 `updateSessionMessages` 内 rewrite 入口扩展 patch：
   ```java
   Map<Long, String> oldTraceIds = snapshotTraceIds(id);
   Map<Long, X> oldX = snapshotXByseqNo(id);
   patched = patchTraceIds(messages, oldTraceIds);
   patched = patchX(patched, oldX);    // ← 新加
   ```

   2d. 如果该列 caller 在某些场景显式传值（非 rewrite 路径），扩展 `AppendMessage` record 加新字段 + 7-arg constructor 接受 + 3-arg backward-compat null 默认。

3. **加 IT 测试** `SessionServiceXxxPreservationIT`（参考 `SessionServiceTraceIdPreservationIT`，4 case 模板）：
   - rewriteMessages 自动 preserve（caller 传 null 时回填旧值）
   - 不覆盖 caller-provided 非空（caller 显式传新值时优先）
   - no-op when 没历史数据
   - findTailXxxIds（如果需要 N 行 tail 查询，参考 `findTailTraceIds`）

4. **更新 [`docs/todo.md`](../../docs/todo.md) 暂缓表**：如果新列对应 backlog 候选（如 `DATA-LAYER-ORIGIN-SPAN-LINK` 提的 `origin_span_id`），勾掉相应行。

## 已知列状态

| 列 | identity? | rewrite preserve 实现位置 | 状态 |
|---|---|---|---|
| `trace_id` | ✅ | `snapshotTraceIds` + `patchTraceIds` | Q1 a4100f7 已修 |
| `seq_no` | counter | `appendRowsOnce` 重算 | 设计如此 |
| `created_at` | audit | now() 重写 | 设计如此 |
| `content_json` / `metadata_json` | business | caller 传 | 设计如此 |
| `msg_type` / `message_type` / `role` / `control_id` / `answered_at` | business | caller 传 | 设计如此 |
| **候选 `origin_span_id`** | identity | 未实施 | DATA-LAYER-ORIGIN-SPAN-LINK backlog |
| **候选 `root_trace_id`** | identity | 未实施 | 待真做时建 |

## Light compact 路径的 known limitation

Q1 fix 的 `patchTraceIds` 用 **index alignment**（`newList[i] ↔ oldSeqNo[i]`），仅在 light compact Rule 1（`truncate-large-tool-output`，in-place 不删元素）下精确。Rules 2-4（`dedupConsecutiveTools` / `foldFailedRetries` / `dropEmptyNarration`）会 `working.remove()` 让列表 shrink → newList[i] 实际可能来自 oldSeqNo>i，trace_id 误填。

**Accepted trade-off vs 100% NULL wipe**。彻底解决要把 seq_no identity 携带到 `CompactResult.getMessages()` 而不是靠 list index —— ROI 暂不够，记 backlog `DATA-LAYER-ORIGIN-SPAN-LINK` 项的扩展子任务。

加新 identity 列时**继承同样的 limitation** —— 不要自己发明更复杂的 alignment 算法（除非有人决定一并解决根本问题）。

## 与其它 rule 关系

- 跟 [`persistence-shape-invariant.md`](persistence-shape-invariant.md) 配合：那条管对账时 content 字节，本条管 rewrite 时其它列。两条都关 `SessionService.rewriteMessages` 路径安全
- 跟 [`pipeline.md`](pipeline.md) 红灯触发器配合：触碰 SessionService 是核心文件 → 必走 Full pipeline + reviewer 显式审本条 invariant
