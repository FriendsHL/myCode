-- V131__seed_evolve_orchestrator_agent.sql
--
-- AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C1) — seed the top-level
-- 'evolve-orchestrator' system agent that drives the agent-driven auto-evolving
-- loop. EvolveController (POST /api/evolve/agents/{agentId}/run) creates a
-- FlywheelRun(loop_kind=evolve) + a system session bound to THIS agent +
-- chatService.chatAsync, mirroring FlywheelController.runLoop +
-- AttributionDispatcherService.dispatchOne (system session + chatAsync).
--
-- Modeled on V93 (attribution-dispatcher) — the existing LLM-orchestrated
-- dispatch-loop agent. The orchestrator runs TOP-LEVEL (its own C0 session),
-- NOT as a workflow sub-agent, so its A/B fan-out via TriggerAbEval stays 2
-- layers deep (same as the existing flywheel) — no 3-layer recursion trap.
--
-- tool_ids (both the top-level column for V81 column-based filtering AND the
-- config JSON for ChatService:742 runtime allowlist — both must stay in sync):
--   RunWorkflow        (Module A — run opt-report to get the attribution report)
--   GenerateCandidate  (Module C — wrap improver one-shot generation)
--   TriggerAbEval      (Module B — async A/B against baseline)
--   GetAbResult        (Module B — poll A/B scores)
--   RecordIteration    (Module C — append an evolve_iteration ledger step)
--   PromoteCandidate   (Module B — guarded promote,末尾 human-定夺 path)
--
-- model_id = xiaomi-mimo:mimo-v2.5-pro: the configured provider in the
-- reference deployment (ANTHROPIC_API_KEY blank → claude provider skipped at
-- startup; see V129 + SkillForgeConfig#llmProviderFactory). The other system
-- agents (V69/V75/V79/V81/V85/V93) use this same default.
--
-- enabled / triggering: there is no t_scheduled_task row for the orchestrator —
-- it is fired on-demand by EvolveController, so this migration only seeds the
-- t_agent row. Idempotent: WHERE NOT EXISTS guards re-runs / flyway repair.
--
-- NOTE: the system_prompt below is a FIRST-CUT loop strategy. 主会话 will refine
-- it (the precise gate/科学判断 wording, maxIter default, summary shape). The
-- placeholder is intentionally self-contained (no SEE_FILE bootstrap swap) so a
-- fresh install can run the loop without a companion classpath prompt file.

INSERT INTO t_agent (
    name,
    description,
    model_id,
    system_prompt,
    skill_ids,
    tool_ids,
    config,
    lifecycle_hooks,
    owner_id,
    is_public,
    status,
    execution_mode,
    agent_type,
    created_at,
    updated_at
)
SELECT
    'evolve-orchestrator',
    'System agent: top-level driver of the agent-driven auto-evolving flywheel. '
        || 'Given a targetAgentId + evolveRunId, runs the opt-report workflow to get an '
        || 'attribution report, then iterates (generate candidate → A/B → science gate → '
        || 'record iteration) up to maxIter, recording every iteration to the evolve run '
        || 'ledger and producing a summary for a human to adopt at the end. '
        || 'Drives AUTOEVOLVE-AGENT-FLYWHEEL Module C.',
    'xiaomi-mimo:mimo-v2.5-pro',
    -- FIRST-CUT loop策略 (主会话 will refine). Self-contained inline prompt.
    '你是自动进化编排 agent（evolve-orchestrator），顶层运行。' || chr(10) ||
    '输入：每条 kickoff 用户消息会带 targetAgentId 与 evolveRunId（形如 "targetAgentId=<id> evolveRunId=<uuid>"），以及可选的 maxIter（默认 10）。' || chr(10) ||
    '你的任务是科学地、自动地改进 targetAgentId 这个目标 agent，并把每一轮记账，绝不直接 promote。' || chr(10) ||
    '' || chr(10) ||
    '流程：' || chr(10) ||
    '(1) 调 RunWorkflow(mode="name", name="opt-report", args={agentId: <targetAgentId>}) 跑归因 workflow，拿到 report。report 里列出若干 issue（每个含 surface ∈ {prompt, skill, behavior_rule} + 描述）。' || chr(10) ||
    '(2) 对 report 里的每个 issue，最多 maxIter 轮（用计数器自己数，达到 maxIter 就停）：' || chr(10) ||
    '    a. GenerateCandidate(surface=<issue.surface>, issue=<issue 描述/json>, targetAgentId=<targetAgentId>) → 拿 candidateId。' || chr(10) ||
    '    b. TriggerAbEval(surface=<surface>, candidateId=<candidateId>, targetAgentId=<targetAgentId>, evolveRunId=<evolveRunId>) → 拿 abRunId。（务必带 evolveRunId，服务端会按 evolve-run 的 A/B 预算上限 gate，超限会拒。）' || chr(10) ||
    '    c. GetAbResult(surface=<surface>, abRunId=<abRunId>, targetAgentId=<targetAgentId>) 轮询，直到 status 为 terminal（completed / failed）。terminal 才有完整 baselineScore / candidateScore / delta。' || chr(10) ||
    '    d. 科学判断是否值得保留（kept）：看 delta 是否有有意义的正向提升（不要只看单点噪声；若有多场景/显著性信息一并考虑）。这是"保留=记账候选"，不是 promote。' || chr(10) ||
    '    e. RecordIteration(evolveRunId=<evolveRunId>, iteration=<本轮序号>, surface=<surface>, changeDesc=<这轮改了什么>, candidateId=<candidateId>, baselineScore=<..>, candidateScore=<..>, delta=<..>, kept=<true/false>, abRunId=<abRunId>) 落账。' || chr(10) ||
    '(3) 全程不要直接 promote。把所有 kept=true 的候选（candidateId + abRunId + delta）汇总成一段清单，作为最终输出，交给人定夺是否采纳。' || chr(10) ||
    '' || chr(10) ||
    '约束：A/B 是真实算力，不要无意义重复触发同一候选；每次 TriggerAbEval 都带 evolveRunId 让服务端 gate 预算；遇到工具返回 error 时读懂原因再决定重试或换 issue，不要死循环；达到 maxIter 或没有更多 issue 时停止并产出汇总。',
    '[]',
    '["RunWorkflow","GenerateCandidate","TriggerAbEval","GetAbResult","RecordIteration","PromoteCandidate"]',
    -- config JSON inner tool_ids gates the AgentLoop engine allowlist
    -- (ChatService reads config.tool_ids). maxTokens generous for a multi-tool
    -- iteration loop (1 RunWorkflow + N×{GenerateCandidate,TriggerAbEval,
    -- GetAbResult poll×k,RecordIteration} + final summary).
    '{"maxTokens": 8192, "temperature": 0.0, "execution_mode": "auto", "tool_ids": ["RunWorkflow","GenerateCandidate","TriggerAbEval","GetAbResult","RecordIteration","PromoteCandidate"]}',
    NULL,
    1,
    TRUE,
    'active',
    'auto',
    'system',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'evolve-orchestrator'
);
