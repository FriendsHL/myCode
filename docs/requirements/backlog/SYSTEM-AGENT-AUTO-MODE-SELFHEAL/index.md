---
id: SYSTEM-AGENT-AUTO-MODE-SELFHEAL
mode: light
status: partially-resolved
priority: P2
risk: Low
created: 2026-05-18
updated: 2026-05-18
follows: SYSTEM-AGENT-TYPING
---

# SYSTEM-AGENT-AUTO-MODE-SELFHEAL — 5 Bootstrap 加 executionMode='auto' self-heal 防 cron task hang on ASK

## 2026-05-18 当下 partial 解决 (PATCH API 路径)

User 拍板用更轻量的 path 当下 fix: **直接 PUT /api/agents/{id} body 含 executionMode='auto'** 改现 5 个 system agent (实际只改 4 个, memory-curator 本来就是 auto). 不写 Bootstrap self-heal code.

**已 fix (2026-05-18)**:
- agentId=7 session-annotator: ask → auto ✓
- agentId=8 metrics-collector: ask → auto ✓
- agentId=9 attribution-curator: ask → auto ✓
- agentId=10 user-simulator: ask → auto ✓
- agentId=6 memory-curator: 已 auto (无需改)

**仍是 backlog 的部分 (未来防护)**:
- 未来新加第 6+ 个 system agent (e.g. V93 加 cluster-curator) 仍需手动 PATCH executionMode='auto', **无 lock-in**
- AgentEntity 默认 executionMode 是 `"ask"`, 新 system agent 创建时如果不显式 set 'auto' 会默认 ask → hang
- 真要长期防护回到本期 backlog 原方案 (5 Bootstrap setExecutionMode self-heal pattern, 跟 V7 setAgentType 一致)

**触发重启条件**: 未来加 ≥6 个 system agent 或 cron task hang 又复现时, 重启本 backlog 实施 Bootstrap pattern.

## 痛点

System agent (V7 `agentType='system'`) 通常被 cron schedule task 触发, **没人**能 interactive answer ASK confirmation. 但实地查 `GET /api/agents/9` (attribution-curator) `executionMode="ask"`. 如果 LLM 在 STEP 3 决策时决定 ASK confirmation, 会创 `t_session_ask_record` 等待人 confirm → cron task **真 hang** (永远 waiting_user) → 飞轮 layer 2/3 chain 断.

V7 Phase 1 加了 5 Bootstrap idempotent self-heal `setAgentType('system')`, 但**没顺手 self-heal `setExecutionMode('auto')`** — V7 当时 scope 严控只 fix agent_type, executionMode 留漏.

**真活验证 (2026-05-18)**:
- attribution-curator (id=9, V7 已设 agentType=system) 现 `executionMode = "ask"` (BE API 真返)
- 跑 attribution-dispatcher-hourly cron 时如 LLM 决定问"该 surface 改 skill 还是 prompt?", 会卡死 (cron 不会自动 confirm)
- 实测 V6 / V7 dogfood 时 attribution-curator session 真出现过 `runtime_status=waiting_user`, 当时人工 confirm 才推进 — Phase 1.6 真启自动 evaluation 后这种 hang 会高频出现, 必须 fix

## 范围

Light 档, ~0.3d.

### F1 5 Bootstrap 加 setExecutionMode self-heal

5 Bootstrap (V7 Phase 1 已加 setAgentType pattern):
- `MemoryCuratorBootstrap`
- `SessionAnnotatorBootstrap`
- `MetricsCollectorBootstrap`
- `AttributionCuratorBootstrap`
- `UserSimulatorBootstrap`

在 `findFirstByName + opt.isPresent()` 之后, prompt-swap 短路 return 之前加:

```java
if (!"auto".equals(agent.getExecutionMode())) {
    agent.setExecutionMode("auto");
    agentRepository.save(agent);
    log.info("[XBootstrap] agentId={} executionMode self-healed to 'auto' (was '{}')",
            agent.getId(), agent.getExecutionMode());
}
```

跟 V7 `setAgentType('system')` 同款 idempotent self-heal pattern, **跟 prompt-swap 解耦** (operator hand-edit prompt 后仍回填).

### F2 测试

- `MemoryCuratorBootstrapAutoModeTest` 等 5 个 (each tests setExecutionMode self-heal)
- 或合并 `SystemAgentBootstrapsAutoModeTest` 1 个 parametrised 5 case

### F3 真活验证

BE restart → SQL 查 `SELECT id, name, execution_mode FROM t_agent WHERE agent_type='system'` → 5 个全 `'auto'` (Bootstrap self-heal 跑过)

## 不在范围内

- 不动 ChatService.runLoop ASK confirmation 路径 (用户 agent 走 ASK 是正常 UX)
- 不动 AgentDrawer ASK toggle UX (W3 system agent 已 fieldset disable 整片表单, ASK toggle 也 disabled)
- 不动 schedule task ASK auto-confirm logic (那是更大的 backlog candidate, 这个先 fix 5 system agent default 即可)

## 链接

- 前置: [V7 SYSTEM-AGENT-TYPING archive](../../archive/2026-05-17-SYSTEM-AGENT-TYPING/index.md) (setAgentType pattern 参考)
- 发现日期: 2026-05-18 (SOP S1 dogfood 真活验证)
- 跟 [SYSTEM-AGENT-DEEPLINK-NAME-FIX](../SYSTEM-AGENT-DEEPLINK-NAME-FIX/index.md) 同期 backlog (V7 followup 2 个)
