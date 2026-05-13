---
id: SKILL-SECURITY-SCAN
title: Skill 安装时快速静态安全扫描
status: done
mode: Full
priority: P1
risk: Full
created: 2026-05-12
updated: 2026-05-12
---

# SKILL-SECURITY-SCAN

## 摘要

在第三方 skill 通过 `ImportSkill` / batch import 进入 SkillForge 之前，先执行一次快速、同步、规则优先的静态安全扫描。MVP 只覆盖安装 / 导入时机，不做每次 tool 调用时的运行期拦截，也不默认调用模型做审计。

核心目标是挡住高置信恶意 skill：`SKILL.md` 中的 prompt injection、helper script 中的敏感信息外泄、下载后执行、破坏性命令、持久化后门等。普通 `curl`、`sh`、依赖安装脚本不能被简单视为恶意，规则必须看上下文组合和行为意图。

## 阅读顺序

1. [MRD](mrd.md) - 背景、用户问题、范围约束。
2. [PRD](prd.md) - 产品行为、规则分级、验收标准。
3. [技术方案](tech-design.md) - 后端集成点、模型、测试计划。

## 当前结论

- MVP 触发时机：只在 install/import 边界扫描。
- MVP 技术路线：静态规则优先；不默认走 LLM audit。
- MVP 决策：`HIGH` 阻断，`MEDIUM` 需要显式 override，`LOW` 允许通过并提示。
- MVP 不做：运行期 `PRE_TOOL_USE` 权限、dashboard 扫描历史、schema migration、LLM 二次审计、动态沙箱。

## 相关需求

- `GAP-PRETOOL-HOOK-PERMISSION`：运行期 tool 调用前权限拦截。它防止已安装 skill 在执行危险 tool 时越权，本需求防止恶意 skill 在安装时进入系统。两者互补但互不阻塞。
- [SKILL-IMPORT](../../archive/2026-05-01-SKILL-IMPORT-third-party-marketplace/index.md)：当前第三方 marketplace 导入能力，本需求在其导入边界补安全扫描。
- [SKILL-IMPORT-BATCH](../../archive/2026-05-01-SKILL-IMPORT-BATCH-rescan-marketplace/index.md)：batch import 需要复用同一 scanner，单个 skill 被阻断不能影响其它 skill 继续导入。

## 下一步

已交付，后续 V2 可补 dashboard scan history、全入口扫描和可选 LLM 二次审计。
