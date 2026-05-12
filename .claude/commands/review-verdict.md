---
description: SkillForge pipeline.md 流派 review verdict 模板（PASS / blocker / warning / nit + Stage 1/2 + r2+ prior items verification）
argument-hint: [round-number e.g. r1 / r2 / r3 | default r1]
---

# Review Verdict Template (SkillForge pipeline)

> 跟 `/code-review`（PRPs 流派 CRITICAL/HIGH/MEDIUM/LOW）**不是**同一套。
> 本模板对应 [`.claude/rules/pipeline.md`](../rules/pipeline.md) 第 11 条「Reviewer 两阶段评审」 + 对抗约束 B severity 标准。
> reviewer agent prompt 调用本模板可省去每次手写格式。

**Round**: $ARGUMENTS （默认 r1）

---

## 用法

### A. Reviewer prompt 引用本模板

reviewer 任务（java-reviewer / typescript-reviewer / code-reviewer / architect 等）prompt 末尾贴：

```
按 SkillForge pipeline.md 「Reviewer 两阶段评审」走，输出按 `.claude/commands/review-verdict.md`
模板，写到 /tmp/review-<area>-<round>.md。SendMessage 一句回执 + 路径。
```

### B. Judge 主会话引用本模板做仲裁

收集多个 reviewer report 后，主会话写 `/tmp/judge-<round>.md` 也按本模板的「Judge 综合裁决」节。

---

## Reviewer 输出格式（Stage 1 + Stage 2）

```markdown
# <Reviewer 名> Review — <需求 ID> <round>

## Stage 1 — Spec Compliance

> 对照 brief / prd.md / tech-design.md 验收点，逐项 ✓ / ✗。
> "要的没做" 或 "做了 plan 没要求的（scope creep）" = **blocker**。
> Stage 1 ✗ 必须先解决再评 Stage 2，不允许跳到 quality。

- [ ] 验收点 1：✓ / ✗ + 一句话证据
- [ ] 验收点 2：...
- 内部一致性：✓ / ✗
- 决策完整性：D1-D? 是否全部 ratified

## Stage 2 — Code Quality (Anticipated / Diff-based)

> 仅在 Stage 1 通过的前提下，按对抗约束 B severity 标准评通用质量。

按文件 / 模块列出 finding，每条标 severity：

- **<finding 标题>** — severity: blocker / warning / nit
  - 证据（行号 / 函数 / 类）
  - 修复建议（具体到 spec 段落 / 代码改法）

## Severity 标准（pipeline.md 对抗约束 B）

| Severity | 必须满足至少一条 |
|---|---|
| **blocker** | 数据丢失 / 错误计算 / 违反不变量（tool_use↔tool_result 配对 / persistence-shape / identity-column 等）/ 编译运行时错误 / 安全权限认证 bug / 明文 plan 要求未实现 / 静默失败 |
| **warning** | 性能 / 可读性 / 可维护性 / 命名 / 测试薄 |
| **nit** | style / 格式 / 文档 / 变量名小改 |

## 综合结论

- **PASS** / **PASS_WITH_WARNINGS** / **NEEDS_FIX_R<n+1>** / **BLOCKED**
- Blocker 列表（按编号）
- Warning 列表（按编号）
- Nit 列表（折叠到 `/tmp/nits-followup-<需求 ID>.md`，不回传循环 — 见对抗约束 C）

## 跑了什么命令（verification 证据 — 见 verification-before-completion.md）

- `mvn -pl ... test`：Tests run X, Failures 0, ...
- `npm run build`：EXIT=0
- 抽样 Read 文件 N 个
- spot-check 某行 / 某 invariant：...
```

---

## r2+ 轮次：Prior Items Verification

> r2 / r3 reviewer **只重审 r1 raised 的 blocker + warning** + 任何 r1 修订**新引入**的问题。
> 不允许 moving goalposts（对抗约束「不要加无关 nit」）。

r2+ 输出**先加** "Prior Items Verification" 节，**再写** Stage 1/2：

```markdown
# <Reviewer> Review — <需求 ID> r2

## Prior Items Verification (r1 → r2)

- **r1 BLOCKER-1 <标题>**：✓ FIXED / ✗ NOT FIXED / ⚠ PARTIAL
  - 现状证据：spec 第 N 节已加 ... / 代码改在 ...
- **r1 W-1 <标题>**：✓ / ✗ / ⚠
- ...

## Stage 1 — New Issues from r2 Changes
（只列 r2 修订引入的新问题，不再翻 r1 旧账）

## Stage 2 — New Issues from r2 Changes
...

## 综合结论
- 如果 prior items 全 ✓ 且无新 blocker → PASS
- 如果 prior items 有 ✗ / 新 blocker → NEEDS_FIX_R3
```

---

## Judge 综合裁决（主会话 Opus 写）

收集多个 reviewer report 后写 `/tmp/judge-<round>.md`：

```markdown
# Judge Ruling — <需求 ID> <round>

**Judge**: Opus (主会话)
**Reviewer 报告**:
- <reviewer A>: <verdict> (path)
- <reviewer B>: <verdict> (path)
- <reviewer C>: <verdict> (path)

## Verdict 综合

- 整体 PASS / NEEDS_FIX_R<n+1>
- Blocker 去重合并（不同 reviewer 提的同一 blocker 合一条）
- Warning 去重合并
- Nit 折叠

## 不变量复核

- pipeline.md 「2 round limit」 是否触达？触达就回主会话决策，不跑 r<n+1>
- 「对抗约束 C」 nit 是否 fold to follow-up
- 「对抗约束 B」 reviewer severity 是否标对

## 决策

- 进 Phase 2 Dev / 回主会话用户决策 / r<n+1> 启动
- 修复责任人：Dev agent / Judge 主会话自己改
```

---

## 与 `/code-review` 的关系

| | `/review-verdict` (本命令) | `/code-review` |
|---|---|---|
| 流派 | SkillForge `pipeline.md` 对抗约束 B | PRPs-agentic-eng (Wirasm) |
| Severity | PASS / blocker / warning / nit | APPROVE / CRITICAL / HIGH / MEDIUM / LOW |
| 用途 | reviewer agent 的对抗 review + Judge 仲裁 | 单次 PR / 本地 diff 整体审查 |
| 适用 | Full / Mid pipeline 内的 reviewer 角色 | 一次性 code review 任务，不进 r1/r2 循环 |

互不替代。日常 SkillForge 内部 reviewer agent → `/review-verdict`；PR / 一次性 review → `/code-review`。
