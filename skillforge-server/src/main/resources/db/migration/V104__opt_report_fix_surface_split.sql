-- OPT-REPORT-V1.3 (2026-05-23): split "suspectSurface" into 根因 vs 修复落点。
--
-- 背景：V1.2 一次 dogfood 暴露问题——LLM 把 suspectSurface 当成"修哪个 surface"
-- 来选，但同时它代表"根因 surface"。例如 agent 调 Bash 反复失败：
--   - 根因 surface = skill（Bash skill 没给目录提示）
--   - 修复落点 = behavior_rule（在 behavior 层加"连续 N 次同款失败后停"规则）
-- LLM 标 skill，bridge 转 OptEvent → 走 SkillDraftService 生成 skill draft，
-- 但根本没 skill 要 draft。错路。
--
-- V104 加 fixSurface 字段（optional，schema 跟 suspectSurface 同 enum），让
-- LLM 显式区分两者。Bridge.convertIssueToEvent 用 effectiveSurface
-- (fixSurface || suspectSurface) 决定 OptEvent.surfaceType。
--
-- 兼容性：fixSurface 字段是 optional——旧报告 / LLM 没区分时 null，
-- effectiveSurface() fallback 到 suspectSurface，行为同 V1.2。

UPDATE t_agent
SET system_prompt = replace(
    system_prompt,
    $old$          "suspectSurface": "skill" | "prompt" | "behavior_rule" | "other" | "unclear",
          "confidence": 0.85,                           // 必填: 0.0 - 1.0 之间$old$,
    $new$          "suspectSurface": "skill" | "prompt" | "behavior_rule" | "other" | "unclear",
                                                        // 必填: 根因 surface — agent *在调用哪个 surface* 时出错
          "fixSurface": "skill" | "prompt" | "behavior_rule" | "other" | "unclear",
                                                        // 选填: 修复落点 surface — 修这个 issue *应该改哪个 surface*
                                                        // 跟 suspectSurface 可不同；不写时下游 fallback 到 suspectSurface
                                                        // 例：agent 调 Bash 循环失败 → suspectSurface=skill, fixSurface=behavior_rule
                                                        //     (修法是加"连续 N 次同款失败后停"的行为规则，不是写新 skill)
          "confidence": 0.85,                           // 必填: 0.0 - 1.0 之间$new$
),
    updated_at = NOW()
WHERE name = 'report-generator';

-- 同时在 Schema 硬约束段补充一行 fixSurface 选用提示
UPDATE t_agent
SET system_prompt = replace(
    system_prompt,
    $old$    - 若你判断 surface 不清楚，写 "unclear"（不是空字符串）$old$,
    $new$    - 若你判断 surface 不清楚，写 "unclear"（不是空字符串）
    - **strong recommendation**：把 agent_loop_exception / max_tokens_exhausted / 循环重试这类问题的 fixSurface 标 "behavior_rule" 或 "prompt"，而不是 "skill"——这些通常是要在 agent 行为约束 / 任务收敛指引 层加东西，而不是写新工具
    - 当 suspectSurface = "other" / "unclear" (基础设施层、平台 bug、空 session 等) 时也写 fixSurface (一般也是 "other" / "unclear")，方便 bridge 一致 reject$new$
),
    updated_at = NOW()
WHERE name = 'report-generator';
