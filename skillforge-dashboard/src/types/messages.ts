/**
 * BE → FE 消息层类型。修复 REMINDER-FE-CONTENT-TYPE-DRIFT backlog（2026-05-10）：
 * 在 normalize 之前从 BE / WebSocket 进来的 raw message，content 字段 runtime 可能是
 * `string`（legacy 形态）或 `ContentBlock[]`（Q2 reminder 注入 / tool_result mixed
 * 形态）。`ChatMessage`（消费层 / 渲染层用，定义在 `components/ChatWindow.tsx`）保持
 * `content: string` —— 那是 `normalizeMessages` 出口已 strip + collapse 的形态。
 *
 * 这里定义 raw 阶段类型，让数据 ingress + normalize 入口显式标注，runtime 行为不变
 * （strip + collapse 由 `messageContent.ts` 处理，normalize 出 ChatMessage 仍是 string）。
 */

/**
 * Anthropic / SkillForge ContentBlock — 一条 message 的 content 数组里可以混
 * text / tool_use / tool_result（assistant 同时 narrate + 调 tool），其它 type
 * （image / 多模态）后续按需扩展。
 *
 * **设计选择 — flat permissive 而非 discriminated union**：原本想用 discriminated
 * union 严格区分各 type 的字段，但实际 normalize / strip / parse 代码大量做 ad-hoc
 * `b.type === '...'` 后访问字段（`b.text` / `b.tool_use_id` / `b.is_error` 等），
 * 严格 union 让 TS 反复要求 narrow。flat shape 允许所有已知字段在 ContentBlock 上
 * 直接 optional 访问，丢一点 type narrowing 精度换简洁。runtime 仍用 `b.type === ...`
 * 检查 + 字段缺失时 undefined 安全。
 *
 * 注意：tool_result 块的 toolUseId 在 BE 序列化里有两种 key（`tool_use_id` 是
 * Anthropic snake_case，`toolUseId` 是 Java POJO field 透出的 camelCase，部分
 * 老路径还会写成 `id`）。runtime 顺序 fallback 见 `useChatMessages.normalizeMessages`
 * 第一处取值。
 *
 * Index signature `[key: string]: unknown` 让 BE 加新字段（如 image media_type
 * 等）不破坏编译。
 */
export interface ContentBlock {
  type: string;
  // text block
  text?: string;
  // tool_use block
  id?: string;
  name?: string;
  input?: unknown;
  // tool_result block
  tool_use_id?: string;
  toolUseId?: string;
  content?: unknown;
  is_error?: boolean;
  isError?: boolean;
  // 允许未识别字段
  [key: string]: unknown;
}

/**
 * raw 阶段从 BE / WebSocket 接到的 message，**未** 经 `normalizeMessages` 处理。
 * 字段几乎全 optional，因为：
 *   - 不同 msg_type（NORMAL / COMPACT_BOUNDARY / SUMMARY / RECOVERY_PAYLOAD / SYSTEM_EVENT
 *     / ask_user / confirmation 等）携带的字段集合不同
 *   - WebSocket 推送有多种 event shape（message_appended / messages_snapshot 等）
 *   - 老 session 历史里某些字段可能缺失
 *
 * `content` 显式声明为 `string | ContentBlock[]` —— 这是修 drift 的核心：以前
 * `ChatMessage.content` 类型是 string 让消费方误以为 raw 也是 string，实际 raw 可能是
 * array（Q2 之后含 reminder 块）。
 *
 * Index signature `[key: string]: unknown` 允许 BE 加新字段时不破坏 FE 编译。
 */
export interface RawMessage {
  role?: string;
  content?: string | ContentBlock[];
  msgType?: string;
  messageType?: string;
  controlId?: string;
  answeredAt?: string;
  metadata?: Record<string, unknown>;
  reasoningContent?: string;
  toolCalls?: unknown[];
  traceId?: string;
  seqNo?: number;
  // 允许 BE 加新字段（如 origin_span_id 等 backlog 候选列）不破坏编译。
  [key: string]: unknown;
}
