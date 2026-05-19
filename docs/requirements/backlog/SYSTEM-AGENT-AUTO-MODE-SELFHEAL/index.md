---
id: SYSTEM-AGENT-AUTO-MODE-SELFHEAL
mode: light
status: backlog
priority: P1
risk: Low
created: 2026-05-18
updated: 2026-05-18
follows: SYSTEM-AGENT-TYPING
---

# SYSTEM-AGENT-AUTO-MODE-SELFHEAL — 5 Bootstrap 加 executionMode='auto' self-heal 防 cron task hang on ASK

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
