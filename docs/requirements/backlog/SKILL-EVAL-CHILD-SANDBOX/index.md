---
id: SKILL-EVAL-CHILD-SANDBOX
mode: mid
status: backlog
priority: P1
risk: Mid
created: 2026-05-19
follows: SKILL-CREATOR-WITH-EVAL, SKILL-CREATOR-PHASE-1.6
---

# SKILL-EVAL-CHILD-SANDBOX — Eval child SubAgent 工具沙盒 (修 Phase 1.6 dogfood 破坏 working tree bug)

## 痛点 (2026-05-19 真活 dogfood 暴露)

`SKILL-CREATOR-WITH-EVAL` Phase 1.1-1.3 + Phase 1.6 用 `SubAgentTool.handleDispatch` 派 child SubAgent 跑 with_skill / without_skill scenario. **Child agent 继承 target agent (user agent) 完整 tools 含 Edit / Write / Bash**.

**Dogfood 5/19 真活后果**: User trigger evaluation 选 source session `a556183c-...` (title "现在增加了很多 tab 页 是用来进行自动化归因优化的", 含 393 messages dashboard 改动指令). 4 个 child SubAgent (跨 2 个 draft) 真活拿 session first user message 当 scenario task → 调 Edit tool → **直接 modified working tree 真 `SkillList.tsx`**:

```
SkillList.tsx modified at May 19 19:39:18
git status: M skillforge-dashboard/src/pages/SkillList.tsx
child session 3ad30c54 真 messages 含 "SkillList" 字串 multiple hits
```

完全没 sandbox isolation. 跟 cc agentskills.io 经验 ("eval needs sandbox") 同款 lesson.

## 范围 (Mid pipeline, ~1d)

### Layer 1: Tool override (本 backlog)

- **Disable Edit + Write** for eval child agent (file 直接修改)
- **保留 Bash / Read / Grep / Glob / WebFetch / Memory** (read-only-ish ops, child agent 仍能模拟真活流程, ls/cat/curl 取环境信息)
- **Bash 内 destructive 命令** (rm/mv/sed -i/redirect >) 留为 known risk, 真活防需 separate backlog (`SKILL-EVAL-BASH-SAFETY-WHITELIST`)

### 实施 path

1. **V93 migration**: `t_session.tools_overrides_json TEXT NULL` (跟 V92 `skill_overrides_json` 同款 nullable additive column)
2. **SessionEntity**: 加 `@Column toolsOverridesJson` 字段
3. **SkillCreatorService.dispatchOne**: spawn child 时 stamp `tools_overrides_json = json_dumps(agent.toolIds - ['Edit', 'Write'])`
4. **ChatService.runLoop**: line ~610 加同 skill_overrides_json 同款 pattern, 读 freshSession.toolsOverridesJson 非空时 override agentDef.toolIds (~10 行红 Iron Law audit, footgun #4#5 同款不适用 because column 在 t_session 不在 t_session_message)
5. **测试**: `SkillCreatorServiceDispatchToolOverrideTest` (verify spawn 真 stamp - Edit/Write)
6. **regression test**: `ChatServiceToolOverrideTest` 4 case (null fallback / Edit removed / Write removed / both)

## 关联

- 真因: [SKILL-CREATOR-WITH-EVAL archive](../../archive/2026-05-18-SKILL-CREATOR-WITH-EVAL-phase-1.1-1.3/index.md)
- 借鉴方法论: [cc agentskills.io eval sandbox](https://agentskills.io/skill-creation/evaluating-skills)
- 互补: [SKILL-EVAL-DESTRUCTIVE-SOURCE-FILTER backlog](../SKILL-EVAL-DESTRUCTIVE-SOURCE-FILTER/index.md) (Layer 2 source session 筛选)

## 触发条件

继续 SKILL-CREATOR-PHASE-1.6 dogfood 之前必修. Phase 1.7 候选.
