---
id: SKILL-SECURITY-SCAN
title: Skill 安装时快速静态安全扫描技术方案
status: done
created: 2026-05-12
updated: 2026-05-12
---

# 技术方案

## 设计结论

在 server 侧新增 `SkillSecurityScanner`，由 `SkillImportService.importSkill(...)` 在 source path 白名单验证后、解析/复制/持久化/注册前调用。MVP 不加 DB migration，不做 dashboard UI，不默认调用 LLM。

## 集成点

目标顺序：

1. `validateSourcePath(sourcePath)` 确保 source 在允许根目录下。
2. `SkillSecurityScanner.scan(realSource)` 扫描真实 source 目录。
3. 根据 `SkillScanDecision` 决定继续或抛 `SkillSecurityException`。
4. 通过后才解析 metadata、复制目录、upsert `t_skill`、注册 `SkillRegistry`。

这样可以保证被阻断的 skill 不会进入本地 skill root、数据库或运行时 registry。

## 新增后端模型

建议包名：`com.skillforge.server.security.skill`

- `SkillSecurityScanner`
- `SkillScanProperties`
- `SkillScanResult`
- `SkillScanFinding`
- `SkillScanSeverity`：`HIGH` / `MEDIUM` / `LOW`
- `SkillScanDecision`：`ALLOW` / `ALLOW_WITH_WARNINGS` / `BLOCK`
- `SkillSecurityException`

规则可以先以内置列表实现，不急着做可配置 DSL。每条规则至少包含：

- `ruleId`
- `severity`
- `message`
- `fileMatcher`
- `pattern` 或 `predicate`
- `excerptSanitizer`

## Service / Tool 改动

- `SkillImportService.importSkill(Path sourcePath, SkillSource source, Long ownerId)` 保持兼容，内部委托到 `allowMediumRisk=false` 的 overload。
- 新增 overload：`importSkill(Path sourcePath, SkillSource source, Long ownerId, boolean allowMediumRisk)`。
- `ImportSkillTool` schema 增加 optional boolean `allowMediumRisk`，默认 false。
- `ImportResult` 可追加 `scanWarnings` 字段；append-only record 变更，避免破坏现有调用方。
- `SkillBatchImporter` 默认不传 medium override；被阻断 skill 进入 failed result，继续处理其它目录。

## 规则实现要点

### contextual shell 规则

不要把 `curl`、`wget`、`sh` 单独判恶意。高危需要命中组合：

- network fetch + pipe/interpreter：`curl ... | sh`、`wget -O- ... | bash`
- network fetch + file write + immediate exec：`curl -o /tmp/x ... && chmod +x /tmp/x && /tmp/x`
- sensitive read + network send：`cat ~/.ssh/id_rsa | curl -d @- ...`
- destructive command + broad target：`rm -rf /`、`rm -rf $HOME`

### prompt injection 规则

`SKILL.md` 中出现 `ignore previous instructions` 这类短语时先按 `MEDIUM`，只有和 secret 读取、外发、越权执行组合时升为 `HIGH`。

### encoded payload 规则

长 base64/hex/unicode escape 只作为 `MEDIUM` 启发式；附近同时有 `eval`、`exec`、shell interpreter、URL 时提高置信度。

### 文件遍历安全

扫描递归目录时不跟随 symlink；如果遇到 symlink，跳过并产生 `LOW` 或 `MEDIUM` finding。任何读取文件的 real path 必须保持在 source root 下。

## 错误处理

- `HIGH`：抛 `SkillSecurityException`，导入失败。
- `MEDIUM` 且无 override：抛 `SkillSecurityException`，提示可在理解风险后用 `allowMediumRisk=true` 重试。
- `LOW`：导入成功，warnings 返回给 tool 输出并打结构化日志。
- scanner 内部 unexpected exception：fail closed，导入失败，错误消息说明 scanner 失败且 skill 未导入。
- 摘录和日志必须 redaction，不能打印完整 secret。

## 配置

Spring properties：

```yaml
skillforge:
  skill-security-scan:
    enabled: true
    allow-medium-risk-by-default: false
    max-file-bytes: 262144
    max-total-bytes: 1048576
```

测试环境可通过 property 关闭 scanner，用于隔离旧测试；默认仍开启。

## 测试计划

- `SkillSecurityScannerTest`
  - 普通 `curl` 不阻断。
  - `curl | sh` 高危阻断。
  - sensitive read + HTTP exfil 高危阻断。
  - prompt injection 单独命中 medium。
  - encoded payload 启发式 medium。
  - symlink escape 被跳过并产生 finding。
- `SkillImportServiceTest`
  - scanner 在 copy/upsert/register 前执行。
  - high finding 后没有 target copy、没有 repo upsert、没有 registry register。
  - medium 默认阻断，override 后导入成功且返回 warning。
- `ImportSkillToolTest`
  - schema 含 `allowMediumRisk`。
  - security exception 输出 rule id / file / line / message。
- `SkillBatchImporterTest`
  - 单个 blocked skill 失败，其它 skill 继续导入。

## 风险与取舍

- 静态规则会有绕过空间；MVP 接受这个边界，用低延迟换安装路径可用性。
- 误伤风险通过 contextual rule、`MEDIUM` override、普通 `curl` 不阻断来降低。
- 不做 DB scan history 会降低审计回溯能力；MVP 先保留在 tool 输出和日志，后续 V2 再持久化。
- 不做运行期权限意味着已安装 skill 仍可能在执行阶段触发危险 tool；这由 `GAP-PRETOOL-HOOK-PERMISSION` 承接。
