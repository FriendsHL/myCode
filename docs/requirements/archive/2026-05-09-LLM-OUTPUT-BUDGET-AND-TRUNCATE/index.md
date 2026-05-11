# LLM-OUTPUT-BUDGET-AND-TRUNCATE

---
id: LLM-OUTPUT-BUDGET-AND-TRUNCATE
mode: full
status: done
priority: P0
risk: Full
created: 2026-05-09
updated: 2026-05-11
---

## 用户原话

> "max_tokens 4096 太小，每次都会报 'Output truncated and continuation recovery exhausted'…整体改下"
> "fix 2 summary 这个是用模型来 summary 吗？我感觉 直接 truncate 这种就行了"

## 实测证据

**Session Analyzer (a3b1d118) iteration_2**: input 19,938 / **output 4,096** / latency 63s →
hit `LlmRequest.maxTokens` hard-coded default 4096 → continuation 再 hit 4096 → 报错。

**Design Agent (66addbf8) trace 1a61e545**: 7 个 LLM calls 共 4.5 分钟，最慢单 span input
44,163 / output 6,542 / 119s。Light compact 已 fire 2 次但只省 2K-3K tokens，
效果不够：原因是 LARGE_TOOL_OUTPUT_BYTES = 5KB 阈值偏小 + 按行截断而非按 bytes，
单条 42K char tool_result 没被有效压缩。

## 根因

- `LlmRequest.java:20` `private int maxTokens = 4096` ← Claude 历史默认值，未跟进
- `AgentDefinition.java:183` fallback `return 4096` ← 同上
- `LightCompactStrategy.LARGE_TOOL_OUTPUT_BYTES = 5 * 1024` ← 太小
- `truncateLines()` 按行数（10 头 + 10 尾），单行可能 1000+ chars，效果不可控

## 范围

### Fix 1 — 全局默认 output max_tokens 4096 → 16384

兼容性表：
- Claude Sonnet/Opus 默认 output cap = 8192（extended thinking 64K）
- GPT-4o = 16384
- Qwen Max / glm-5 / DeepSeek = 8192
- mimo-v2.5-pro = 16384

**取 16384** 作为新默认（向上兼容；Claude 真用得起更高）。Per-provider yaml override 留 V2。

改动：
- `skillforge-core/src/main/java/com/skillforge/core/llm/LlmRequest.java`
  - line 20: `private int maxTokens = 4096` → `private int maxTokens = DEFAULT_MAX_TOKENS`
  - 加 `public static final int DEFAULT_MAX_TOKENS = 16384` 常量
- `skillforge-core/src/main/java/com/skillforge/core/model/AgentDefinition.java`
  - line 183: `return 4096` → `return LlmRequest.DEFAULT_MAX_TOKENS`
  - 注释更新

### Fix 2 — Light compact 截断阈值 + 算法改进

不引入 LLM summary（用户拒绝），保留纯 truncate；改进现有逻辑：

- `LightCompactStrategy.LARGE_TOOL_OUTPUT_BYTES`: 5K → **10K bytes**
- `truncateLines()` 改为 `truncateByBytes()`：保留头 4K + 尾 2K（之前按 10 行 + 10 行）
- middle marker 升级：
  ```
  ...[N chars truncated, original size: M chars (N% reduced)]...
  ```
- 测试 cover：
  - 头部完整性（前 4K 不变）
  - 尾部完整性（后 2K 不变）
  - marker 正确（含 truncated chars / original size）
  - 单行很长时（一行 50K chars）也能被截
  - boundary：刚好 10K → 不截
  - 已 truncated 的不重复 truncate（marker 自识别）

### V2 推迟（不做）

- per-provider yaml `skillforge.llm.providers.*.maxOutputTokens`
- per-model `KNOWN_MODEL_OUTPUT_CAPS` map（按 model id 自动选 8K/16K/32K）
- LLM-based summary（除非长文本是 assistant text）
- 非 whitelist tool 的 hard-cap truncate（V2，需评估 break risk）

## 验收

- [x] `mvn -pl skillforge-core,skillforge-server test` 全套绿（1159/0/0/60）
- [x] 默认 `max_tokens` 4096 → 16384，per-agent override 仍优先
- [x] Light compact 纯 truncate 算法落地（10KB 阈值、头尾保留、marker 幂等）
- [x] Claude 模型感知 clamp 落地，避免老模型因 16384 上限 HTTP 400

## 交付状态

已交付并归档。主实现 commit 为 `715c386`；交付事实见 [delivery-index.md](../../../delivery-index.md) 的 2026-05-09 LLM-OUTPUT-BUDGET-AND-TRUNCATE 行。

## 风险

- **`tool_use ↔ tool_result` 配对不变**：truncate 只改 content_json 内的 text，不动
  message structure（已有 LightCompactStrategy 不变量；reviewer 重点 verify）
- 16384 max_tokens 对小型 provider（如 vLLM 自部署小模型）可能溢出 → 让 agent 配
  `max_tokens` per-agent override（已支持，AgentDefinition.getMaxTokens 读 config 优先）
- byte 截断中文字符可能切坏多字节序列 → 用 `String.substring` 按 char 而非 byte 截，
  marker 显示 char 数（"chars"，非 "bytes"）

## 实施

- BE 1 dev (Full 档：Plan 跳过因为方案明确，直接 Dev → r1+r2 adversarial review →
  Phase Final)
- 完成后主会话 commit + push
