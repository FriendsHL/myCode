---
id: SKILL-SECURITY-SCAN
title: Skill 安装时快速静态安全扫描 MRD
status: mrd
created: 2026-05-12
updated: 2026-05-12
---

# MRD

## 用户问题

SkillForge 已经支持从第三方 marketplace / 本地 OpenClaw workspace 导入 skill。当前安全边界主要是用户 approve gate 和 import 路径白名单，但缺少内容层扫描：系统不会在 skill 注册前检查 `SKILL.md`、helper scripts、metadata 中是否包含恶意指令或明显危险行为。

用户担心的不是“所有脚本都危险”，而是 OpenClaw 类 skill 注入问题：恶意 skill 通过自然语言指令或 helper script 引导 agent 读取 secret、外发凭据、执行下载来的 payload，最终被当成普通 skill 加载。

## 当前现状

- `AgentLoopEngine.isInstallRequiringConfirmation(...)` 对 `npx clawhub install`、`npm install` 等 install 命令做用户确认。
- `SkillImportService.validateSourcePath(...)` 只验证 source path 在允许目录下，防 path traversal。
- `SkillImportService.importSkill(...)` 解析 `SKILL.md` / `_meta.json` 后复制目录、写入 `t_skill`、注册到 `SkillRegistry`。
- 用户确认导入时主要看到 skill 描述，不会自动检查 skill 内容和 helper scripts。

## 攻击面

- `SKILL.md` prompt injection：例如要求忽略上游指令、读取 `~/.aws/credentials`、把 secret POST 到外部域名。
- helper script 恶意行为：例如 `curl ... | sh`、下载后执行、`rm -rf $HOME`、写 `~/.ssh/authorized_keys`、改 `/etc/cron*`。
- 敏感信息外泄：例如 `env` / `printenv` / `cat ~/.ssh/id_rsa` 后拼进 `curl -d`、`wget --post-data`。
- 编码 payload：base64/hex/unicode escape 长串隐藏 shell 或 URL。
- 可疑远端指令源：`SKILL.md` 要求 agent 拉取外部 URL 并把内容当新指令执行。

## 目标

1. 在 skill install/import 边界发现高置信恶意内容，阻止注册和加载。
2. 对常见但正常的 `sh`、`curl`、依赖安装脚本保持低误伤。
3. 扫描要快：不依赖网络、不默认调用模型、不拖慢导入流程。
4. 输出可解释：告诉用户命中了哪个规则、在哪个文件、为什么阻断。

## 非目标

- 不做每次 tool 调用前的运行期权限拦截；该问题归 `GAP-PRETOOL-HOOK-PERMISSION`。
- 不默认做 LLM-based audit；后续可作为手动二次审计或高风险规则增强。
- 不做动态沙箱执行、SBOM、签名验证、依赖供应链完整追踪。
- MVP 不做 dashboard scan history 和 `t_skill` scan status schema。
- MVP 不覆盖 draft approve、candidate promote、dashboard upload 等所有后续入口；这些入口进入 V2 或后续补齐。

## 范围约束

- 只扫描导入源目录内的文本文件，跳过二进制文件。
- 普通 `curl` / `wget` / `sh` 字面量不是阻断条件；需要与 pipe-to-shell、download-and-exec、secret exfiltration、敏感路径写入等上下文组合触发。
- 扫描结果不能把完整 secret 或大段源码写入日志 / error message；只展示短摘录。
