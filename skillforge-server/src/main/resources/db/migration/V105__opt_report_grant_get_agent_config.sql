-- OPT-REPORT-V1.4 (2026-05-23): grant report-generator access to GetAgentConfig
-- so its STEP 6 attribution LLM can inspect the target agent's existing
-- system_prompt / skills / behavior_rules / lifecycle_hooks before producing
-- "建议" issues — avoiding the failure mode where it suggests "add cwd check
-- rule" when the rule is already present, or "create monorepo git skill"
-- when an equivalent skill is already attached.
--
-- GetAgentConfigTool already exists; this migration just plumbs it into the
-- agent's tool_ids array + extends the prompt with a new STEP 5.5 instruction.

-- Step 1: add "GetAgentConfig" to tool_ids array (use jsonb operations because
-- tool_ids is a JSON-array string but we want safe array append).
UPDATE t_agent
SET tool_ids = jsonb_set(
        '[]'::jsonb,
        '{0}',
        '"GetAgentConfig"'::jsonb
    ) || COALESCE(NULLIF(tool_ids, '')::jsonb, '[]'::jsonb)::text::jsonb
  -- the above produces ["GetAgentConfig", ...existing]
WHERE name = 'report-generator'
  AND tool_ids NOT LIKE '%GetAgentConfig%';

-- Step 2: insert STEP 5.5 instruction. Anchor on the start of STEP 6
-- (归因分析) so the new block sits between STEP 5 (re-read summary) and
-- STEP 6 (LLM 归因).
UPDATE t_agent
SET system_prompt = replace(
    system_prompt,
    $old$STEP 6 — LLM 归因分析$old$,
    $new$STEP 5.5 — 拉取 target agent 的当前配置（避免给已存在的建议）：
  调 GetAgentConfig(targetAgentId=<agentId>) 一次。
  返回字段（按需读，不必全用）：
    - systemPrompt: 当前 system prompt 全文
    - skills: 已绑定 skill 列表
    - tools: 已绑定 tool 列表
    - behaviorRules: { builtinRuleIds: [...], customRules: [{severity, text}, ...] }
    - userLifecycleHooksRaw: 已配 hook 配置 (raw JSON)
    - modelId / maxLoops / executionMode / thinkingMode / reasoningEffort

  **STEP 6 LLM 归因时强制做以下检查**：
    - 若建议"加 behavior_rule X" → 先扫 behaviorRules.customRules 看是不是已有等价 rule；如已有，把 issue 改成"现有 rule 'XXX' 似未生效，可能需要 prompt 层强化"或干脆 drop 这条 issue
    - 若建议"加 skill Y" → 先扫 skills 列表；已有 → 改 issue 措辞 "skill Y 已绑定但调用不当"
    - 若建议"改 prompt 加 Z" → 引用 systemPrompt 里**具体段落**，写成 "将现有 'XX' 段改成 'YY'"，不要只说"加规则"
    - 若 issue 是行为类（loop / overuse / token 失控）：
        * 优先查 behaviorRules.customRules 看是否已 cover
        * 没 cover 时 fixSurface 标 behavior_rule
    - 引用现有配置时尽量准确（不要瞎编不存在的 rule/skill 名）

  这一步是为了让报告"actionable"——operator 看完 convert OptEvent 后，
  生成的 candidate 是真有差异的改动，不是重复已存在的配置。

STEP 6 — LLM 归因分析$new$
),
    updated_at = NOW()
WHERE name = 'report-generator';
