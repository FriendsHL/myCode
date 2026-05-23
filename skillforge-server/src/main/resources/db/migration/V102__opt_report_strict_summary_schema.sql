-- OPT-REPORT-V1.2 (2026-05-23): tighten the report-generator's STEP 6
-- system_prompt so summary_json.topIssues follows the V1.2 fixed schema.
--
-- V1.0/V1.1 left summary_json "free-form (suggested)". V1.2 needs each
-- issue to carry a stable id + structured fields (severity / confidence /
-- suspectSurface / suggestion / expectedImpact) so the FE can render a
-- "Convert to Event" button per issue with idempotency keyed on issue id,
-- and so OptReportToEventBridge can pluck the fields it needs to build
-- a t_optimization_event row.
--
-- The OLD block (V97-era):
--   同时构造 summaryJson（自由结构，但建议含）：
--     {
--       "totalSessions": <int>,
--       ...
--       "topIssues": [
--         {"title": "...", "sessionCount": N, "exampleSessionIds": [...], "suspectSurface": "skill|prompt|..."}
--       ],
--       "batchesTotal": <int>,
--       "batchesSucceeded": <int>
--     }
--
-- Replaced with a STRICT schema for topIssues + a one-line example. Outer
-- fields (totalSessions / successCount / ...) stay free-form because
-- they're only consumed by extractSummaryHighlight (no parser).
--
-- Why replace() and not full UPDATE: V97 + V90 already swapped/inlined
-- the prompt body once; doing a full re-seed risks dropping any operator
-- hand-edits applied to t_agent.system_prompt between then and now.
-- replace() is surgical — leaves the rest of the 250-line prompt intact.

UPDATE t_agent
SET system_prompt = replace(
    system_prompt,
    $old$  同时构造 summaryJson（自由结构，但建议含）：
    {
      "totalSessions": <int>,
      "successCount": <int>,
      "failureCount": <int>,
      "successRate": <float>,
      "topIssues": [
        {"title": "...", "sessionCount": N, "exampleSessionIds": [...], "suspectSurface": "skill|prompt|..."}
      ],
      "batchesTotal": <int>,
      "batchesSucceeded": <int>
    }$old$,
    $new$  同时构造 summaryJson。**topIssues 子字段必须严格按下面 schema 输出**
  （V1.2 — FE 渲染 "Convert to Event" 按钮 + BE OptReportToEventBridge 解析依赖此 schema）：

    {
      "totalSessions": <int>,
      "successCount": <int>,
      "failureCount": <int>,
      "successRate": <float>,
      "topIssues": [
        {
          "id": "issue-1",                              // 必填: 稳定 ID，"issue-1" / "issue-2" 风格，不要换名
          "title": "<问题简述>",                          // 必填: 非空
          "severity": "high" | "medium" | "low",        // 必填: 三选一
          "sessionCount": <int>,                        // 必填: ≥1，跟 exampleSessionIds 数量一致或更多
          "exampleSessionIds": ["sess-abc", ...],       // 必填: ≥1 个真实 sessionId
          "suspectSurface": "skill" | "prompt" | "behavior_rule" | "other" | "unclear",
          "confidence": 0.85,                           // 必填: 0.0 - 1.0 之间
          "suggestion": "<一句话改进方向>",                 // 必填: 非空，会被 operator 看到决定要不要 convert
          "expectedImpact": "<选填: 预期影响>"             // 选填
        }
      ],
      "batchesTotal": <int>,
      "batchesSucceeded": <int>
    }

  Schema 硬约束：
    - 每个 issue 的 id 必须唯一（同一份 summary 内不重复）
    - severity / suspectSurface 必须是上述枚举之一，不要新造
    - confidence 是数字，不是字符串
    - 若你判断 surface 不清楚，写 "unclear"（不是空字符串）
    - sessionCount ≥ exampleSessionIds.length
    - 不要省任何必填字段——FE 解析失败会导致整个报告页面降级显示$new$
),
    updated_at = NOW()
WHERE name = 'report-generator';
