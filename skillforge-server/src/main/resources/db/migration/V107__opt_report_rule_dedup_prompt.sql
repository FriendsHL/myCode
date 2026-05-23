-- OPT-REPORT-V1.5 (2026-05-23): 强制 report-generator 对照现有 customRules /
-- skills / prompt 段，区分 issue 是真"新加"还是"改现有"还是"已存在的 duplicate"。
--
-- 背景:
--   V1.4 加了 GetAgentConfig tool 让 report-generator 看 agent 当前配置
--   （systemPrompt / skills / behaviorRules.customRules / lifecycleHooks）。
--   但 V1.4 之后跑的报告还是出现"建议加 cwd 检查规则"——而 customRules
--   里早就有等价 rule（agent 没遵守但 rule 存在）。
--
--   LLM 没主动对照。需要 schema + prompt 强制要求：每个 issue 显式标记
--   actionType ∈ {"new", "modify", "duplicate"}，modify/duplicate 必须 verbatim
--   引用现有 rule/skill/prompt 段 (targetRuleText)。
--
-- 兼容性:
--   actionType / targetRuleText 字段全 optional——旧报告 / LLM 没区分时为 null,
--   BE Parser 不 throw, FE 按 "new" 处理保持向后兼容。
--
-- 不变量守护:
--   - V104 fixSurface 段不动 (V1.5 是叠加，不是替换)
--   - PostgreSQL replace() 用 dollar-quoted anchor (V102/V104/V105 风格)
--   - anchor 引用要 verbatim 匹配 V104 留下的字符 (Chinese 全角逗号/句号)

-- ─────────────────────────────────────────────────────────────────────────
-- Step 1: schema 段加 actionType + targetRuleText 字段说明
--
-- 找 V102 留下的最后一行字段 (expectedImpact)，往里塞两个新字段。
-- ─────────────────────────────────────────────────────────────────────────

UPDATE t_agent
SET system_prompt = replace(
    system_prompt,
    $old$          "expectedImpact": "<选填: 预期影响>"             // 选填$old$,
    $new$          "expectedImpact": "<选填: 预期影响>",            // 选填
          "actionType": "new" | "modify" | "duplicate",  // V1.5+: 建议落点
                                                          // - "new": 真新加 (无现有 rule/skill 等价)
                                                          // - "modify": 改现有 customRules 或 prompt 段 (必须填 targetRuleText 引用原文)
                                                          // - "duplicate": 想建议的 rule/skill 已存在 (FE 会折叠，operator 不需要看)
          "targetRuleText": "<现有 rule 全文 / skill 描述 / prompt 段原文,verbatim>"
                                                          // 当 actionType=modify 或 duplicate 时必填
                                                          // 当 actionType=new 时省略$new$
),
    updated_at = NOW()
WHERE name = 'report-generator';

-- ─────────────────────────────────────────────────────────────────────────
-- Step 2: STEP 6 推理规则加强 — 强制 actionType 判断
--
-- anchor 用 V105 STEP 5.5 末尾的"actionable"段 (Chinese 全角逗号/破折号/句号
-- 必须 verbatim) → 在它后面加 V1.5 actionType 强制检查段。
-- ─────────────────────────────────────────────────────────────────────────

UPDATE t_agent
SET system_prompt = replace(
    system_prompt,
    $old$  这一步是为了让报告"actionable"——operator 看完 convert OptEvent 后，
  生成的 candidate 是真有差异的改动，不是重复已存在的配置。$old$,
    $new$  这一步是为了让报告"actionable"——operator 看完 convert OptEvent 后，
  生成的 candidate 是真有差异的改动，不是重复已存在的配置。

  **每个 issue 必须显式判断 actionType (V1.5+ 强制)**:
    1. 先在 GetAgentConfig 返回的 behaviorRules.customRules / skills / systemPrompt 里 grep,
       semantic match (不要求字面一致),看是否已存在等价配置
    2. 三种 case 决定 actionType:
       - 找不到任何相关现有配置 → actionType="new" / targetRuleText 省略
       - 找到一条相关但不完全 cover → actionType="modify" / targetRuleText 引用现有那条全文 / suggestion 改成"在现有 rule '...' 基础上加 XYZ"
       - 找到一条已经完全等价 → actionType="duplicate" / targetRuleText 引用 / suggestion 改成"现有 rule '...' 似未被 agent 遵守,可能需要 prompt 强化或提高 severity 而非新加"
    3. 不要全标 "new" 偷懒——operator 会发现重复建议就不再相信报告

  反例 (V1.4 dogfood 真实出现):
    - issue 标 "加 cwd 检查规则",但 customRules 已有 SHOULD 级 "git 操作前确认目录"
      → 应该标 actionType="duplicate" + targetRuleText="git 操作前确认目录..."
      + suggestion="现有 rule 'git 操作前确认目录' 似未生效,建议提升 severity 到 MUST 或在 prompt 中复述"
    - issue 标 "加 token 预算约束规则",但 customRules 已有 "工具结果回来后简要复述关键信息"
      → 应该标 actionType="modify" + targetRuleText 引用 + suggestion="在现有 '工具结果回来后简要复述' rule 中增加 '单次结果超 3000 字必须先提取要点'"$new$
),
    updated_at = NOW()
WHERE name = 'report-generator';
