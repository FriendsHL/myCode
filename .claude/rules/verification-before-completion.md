# Verification Before Completion

> 来源：[superpowers/skills/verification-before-completion](https://github.com/obra/superpowers)（Jesse Vincent）。SkillForge 适配：与 [`pipeline.md`](pipeline.md) Phase Final 的"项目级 e2e 验证"互补，本规则管 **agent 单条 claim 级别的 Iron Law** —— 防 dev / reviewer 报"成功"被默认信任。

## The Iron Law

```
NO COMPLETION CLAIMS WITHOUT FRESH VERIFICATION EVIDENCE
```

如果你**在本 message 里没跑过验证命令**，就不能说"通过 / 完成 / 修好了"。

## Gate Function

任何"声称成功 / 表达满意"之前：

1. **IDENTIFY**：什么命令能证明这个 claim？
2. **RUN**：完整跑（fresh，不是看老输出）
3. **READ**：完整读 stdout/stderr，看 exit code，数 failure 数
4. **VERIFY**：输出真的支持这个 claim 吗？
   - 否 → 实事求是说当前状态 + 证据
   - 是 → 说 claim + 附证据
5. **THEN ONLY**：再说

跳过任何一步 = 撒谎，不是验证。

## SkillForge 常见 claim → 必须的证据

| Claim | 必须 | 不够 |
|---|---|---|
| Java 测试通过 | `mvn test` 输出 + `BUILD SUCCESS` | "应该过 / linter 过了" |
| 前端构建通过 | `cd skillforge-dashboard && npm run build` 退出 0 | "tsc 通过 / lint 过" |
| Bug 修了 | 跑原 reproduce 步骤 → 症状消失 | "代码改了 / 看着对" |
| Regression test 真有效 | red-green：写测试 → 跑通过 → 回退 fix → 跑必须红 → 恢复 → 跑通过 | "我加了一个 test" |
| Agent 完成了 | `git diff` 看到改动 + 抽样 Read 几个改的文件 | "agent 报告 success" |
| 数据真落库 | `curl /api/...` 或 SQL 查实际值 | "UI 按钮变绿了" |
| 整批需求做完 | 对照 `prd.md` / `tech-design.md` 验收点逐条 ✓ | "测试过了 = 完成" |
| 前端功能能用 | `npx agent-browser goto <url>` + `eval "document.body.innerText"` 断言 DOM | "build 过了 / 看截图对" |
| 新加 / 改 FE-BE 契约（API DTO / WS event payload） | (1) grep BE DTO 字段名 == FE TS interface 字段名；(2) **grep Controller `return ResponseEntity.ok(...)` 内 outer shape (裸 List / Map envelope / 单对象) == FE `api.get<T>` T 类型 match**；(3) roundtrip IT 跑过（ObjectMapper write → FE 期望 JSON 比对）；(4) **真活 curl 至少跑过一次拿 raw JSON shape 对照 FE TS interface 行对行** | "我对照了名字 / BE/FE 单测分别过了 / test mock 都 pass" — 跨栈不匹配单测+test mock 都抓不到（mock 跟 FE-Dev 同款错 shape 自洽 echo chamber） |

> **FE-BE 契约 footgun**：DTO 字段重命名 / 类型变更（如 `ExecuteRequest.commandLine` vs FE `command/args`）本地 BE 单测 + FE tsc 都过得了，但跨栈调用时反序列化 silent 失败 / 字段 null。**Outer envelope shape 也得验**（FLYWHEEL-PER-RUN commit `538b828` 反例：BE 返 `{items, limit, hideTerminal}` envelope, FE 当裸 array 用 → `[...runs]` 抛 "runs is not iterable"; java-reviewer 检 inner 12 字段 PASS 但漏 outer shape, ts-reviewer 跟 test mock 走 — mock 用同款错 shape 自洽 echo chamber, hotfix `5e25067` 修）。**新加 / 改 DTO 字段必须按上表 4 步 grep + envelope + roundtrip + 真活 curl 四重验证**。详见 [`java.md` known footgun #6 + #6b](java.md)。

## Completion Gate（声明任务完成前必跑）

Agent / Claude 主会话报告"任务完成"之前，**三件套必须全绿**：

| 改动类型 | 必跑命令 | 必须报告 |
|---|---|---|
| BE 改动 | `mvn -pl skillforge-server -am test`（或 `mvn -pl skillforge-core,skillforge-tools,skillforge-server -am test` 跨模块）| `Tests run: N, Failures: 0, Errors: 0, Skipped: M → BUILD SUCCESS` |
| FE 改动 | `cd skillforge-dashboard && npx tsc --noEmit && npm run build` | tsc 0 错 + `npm run build` EXIT=0 |
| BE + FE 跨栈 | 上面两套都跑 | 双绿 |

**规则**：
- 跑测试前先 `git status` 看有没有 untracked 漏 stage
- 报"完成"必须**贴当次输出**（不要"应该 baseline 过"，必须本 message 真跑过）
- 出现 regression 先核实**是否 pre-existing**（`git stash && mvn test` 对照）—— 不是 pre-existing 才算回归
- 跨栈契约改动还需 grep + roundtrip 验证（上表）

## Red Flags（看到立刻停）

- 你正打"应该 / 大概 / 看着 / probably / seems to / should work"
- 你还没跑命令就在输出 "Great! / Perfect! / Done! / 完成了"
- 你正要 commit / push / 创建 PR，但本 message 没有验证证据
- 你在信 sub-agent 的 success report 而没 `git diff` 抽查
- 你累了想"就这一次免了"
- 你在用"看起来 / 估计"那种**不带证据的肯定句**

## Rationalization Prevention

| 借口 | 现实 |
|---|---|
| "应该好了" | RUN 验证命令 |
| "我有信心" | 信心 ≠ 证据 |
| "就这一次" | 不许例外 |
| "Linter 过了" | Linter ≠ compiler |
| "Agent 说成功" | 自己独立验 |
| "我累了" | 累 ≠ 借口 |
| "部分检查够了" | 部分检查证明不了什么 |

## 与 pipeline.md 的关系

- **本规则 = agent 单 claim 级**：每次说"通过"都要有证据
- **`pipeline.md` Phase Final = 项目级 e2e**：commit 前的整体验证清单
- 两者**互补**，不能用 Phase Final 替代单 claim 验证

特别针对 SkillForge：

- **Mid 档**：dev agent 报"功能做完，测试过了" → Claude 主会话**必须** `git diff` + 跑一次 `mvn test` 自己验，不能 SendMessage 后立刻进 Phase Final
- **Full 档**：Reviewer 报"PASS" → Judge 不能直接接受，要看 reviewer report 里的 evidence（"我跑了 X 命令，输出 Y"），没 evidence 的 PASS 当 NEEDS_CONTEXT 处理
- **对抗约束 B**（[`pipeline.md`](pipeline.md) severity checklist）已部分覆盖"blocker 必须满足至少一条"，本规则补"reviewer 真跑过命令吗"那一层

## Bottom Line

**No shortcuts for verification.**

跑命令 → 读输出 → 再说结果。non-negotiable.
