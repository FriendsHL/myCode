# Persistence Shape Invariant

> **触发路径**：`**/CompactionService.java` / `**/SessionService.java` / `**/ChatService.java` / `**/AgentLoopEngine.java` / `**/Message.java` / `**/ContentBlock.java`
>
> **来源**：Q2 commit `bdb0453` 引入 + Q3 commit `cc87776` 修 + commit `b2c7039` 加结构性 guard 之后总结成 invariant，2026-05-10。

## Iron Law

```
ChatService 持久化的 Message 跟 AgentLoopEngine 内存 messages 列表里的同一条
logical message —— content_json **JSON 字节必须完全一致**。否则
SessionService.updateSessionMessages 的 commonPrefixSize + messageEquals
对账机制会把发散点之后的内容当 "delta" silently dup-append，产生重复行。
```

## 为什么这条必须

`SessionService.updateSessionMessages` 在 engine.run() 返回后处理 `result.getMessages()`：

1. `getContextMessages(sessionId)` 从 DB load `persistedContext`
2. `commonPrefixSize(persistedContext, finalMessages)` 用 `messageEquals` 逐位比较
3. `messageEquals` 实现：`objectMapper.writeValueAsString(m1.content) == writeValueAsString(m2.content)`
4. 字节比较失败 → prefix 在该位置断 → 后面 messages **当 delta append**

**关键**：mid-prefix 发散（`0 < prefixLen < persistedContext.size()`）旧逻辑 silent append（commit `b2c7039` 之前），新 guard 改成 log.warn + rewrite 但**这是兜底，不是首选**。首选是从源头保证两边字节一致。

## 触发条件清单（每加一条都必须自查）

### 条件 A — 用户消息加 transformer（reminder / RAG context / metadata envelope）

**反面案例**：Q2 `bdb0453` ChatService 构造 array-shape `userMsg` 含 reminder ContentBlock，**持久化对的**，但 engine `runInternal` 拿 `String userMessage` 后用 `Message.user(userMessage)` 重建 String-shape。两侧 content_json 不一致 → DUP。

**正确做法**（Q3 `cc87776` 起）：

```java
// ChatService.chatAsync
Message userMsg = buildUserMessageWithReminder(...);   // 构造 array-shape
sessionService.appendNormalMessages(..., List.of(userMsg), ...);
final Message userMsgWithReminder = userMsg;            // ← capture
chatLoopExecutor.execute(() -> runLoop(..., userMsgWithReminder, ...));

// AgentLoopEngine.run 7-arg overload 接受 Message userMessageBlock
public LoopResult run(AgentDefinition, String userMessage, Message userMessageBlock, ...)

// runInternal
if (userMessageBlock != null) {
    messages.add(userMessageBlock);          // ← 同一个 Java 对象引用
} else if (userMessage != null) {
    messages.add(Message.user(userMessage));  // 兼容 legacy 调用
}
```

→ ChatService 持久化的 Message + engine 内存 messages 列表用**同一个对象引用**，序列化字节必然一致。

### 条件 B — Compactor mutate 内存 messages 列表的某条 message

**反面案例 (假设)**：未来加一条 light compact rule "truncate-long-user-msg"，就地 `messageList.get(N).setContent(truncated)`。但 N 这条 message 是 ChatService 持久化进 DB 的同一个对象引用 → 内存改了但 DB 还是老 content → 下次 updateSessionMessages 对账时持久化 vs engine 不一致。

**正确做法**：compactor 修改任何 user-msg text block content 时，必须先做 defensive shallow-copy：

```java
// AgentLoopEngine.runInternal line ~410 invariant 注释里有详细说明
Message safeBlock = new Message();
safeBlock.setRole(userMessageBlock.getRole());
Object c = userMessageBlock.getContent();
safeBlock.setContent(c instanceof List<?> list ? new ArrayList<>(list) : c);
messages.add(safeBlock);
```

当前 `LightCompactStrategy` Rule 1 用 `replaceContent` (创建新 ContentBlock 替换 list 中位置) 不动原对象，**安全**。Rules 2-4 (`dedup` / `fold` / `drop`) 用 `working.remove()` 删元素不动 content，**安全**。但加新 rule 时必须 audit。

### 条件 C — ContentBlock 加 Jackson 注解或字段

**反面案例 (假设)**：给 `ContentBlock` 加 `@JsonInclude(NON_NULL)` 让 `is_error: null` 序列化时省略。但 deserialize 路径如果不对称（field 默认值 null 没有 @JsonSetter null 处理），同一个对象 round-trip 后字节不一致。

**正确做法**：任何 ContentBlock / Message JSON 注解变更必须加 roundtrip 测试：

```java
@Test
void contentBlock_jsonRoundtrip_byteIdentical() {
    ContentBlock b = ContentBlock.text("...");
    String s1 = objectMapper.writeValueAsString(b);
    ContentBlock b2 = objectMapper.readValue(s1, ContentBlock.class);
    String s2 = objectMapper.writeValueAsString(b2);
    assertThat(s2).isEqualTo(s1);
}
```

### 条件 D — 加新 caller 调 ChatService 持久化路径

`enqueueUserMessage` / `answerAsk` / `answerConfirmation` 已知 partial-coverage（不走 reminder 注入）—— 这 3 条目前不触发该 invariant 因为它们持久化的 String content 跟 engine 重建的 `Message.user(text)` 也是 String，**byte-identical 凑巧 hold 住**。但任何新加 user-msg path 必须显式 audit：要么走 `buildUserMessageWithReminder` 拿到的 Message 透传到 engine，要么持久化 + engine 都用最简 String content。

## 兜底（不是首选）

`SessionService.updateSessionMessages` 内 mid-prefix divergence guard (commit `b2c7039`)：发现持久化跟 engine 不一致 → log.warn + 走 full rewrite（保 identity 列）。**这条是兜底**：会触发 + log 报警 + 不污染 DB，但每次触发意味着上面 4 条 invariant 之一被破坏，需要溯源修。

诊断字段（log.warn 输出）：`divergeAt` / `persistedSize` / `engineSize` / `persistRole` / `engineRole` / **`persistContentType`** / **`engineContentType`** —— 后两个字段是 Q3 后加的，看到 `persistContentType=ArrayList, engineContentType=String` 即 Q2-style shape mismatch 复现。

## Self-Check 清单

触碰 ChatService 持久化路径 / AgentLoopEngine.runInternal messages 拼装 /
LightCompactStrategy / FullCompactStrategy / Message / ContentBlock 之前问自己：

- [ ] 我的改动会让两侧 Message 对象引用断开吗？
- [ ] 我的改动会让两侧 content JSON 序列化字节漂移吗？
- [ ] 我的改动会让 compactor mutate 共享对象吗？
- [ ] 我有 roundtrip 测试覆盖新加的 ContentBlock 字段 / 注解吗？
- [ ] 我的新 caller 走 main path（buildUserMessageWithReminder + 7-arg engine.run）还是绕过？

任意一条 **是** / **不确定** → 必须加 IT 测试覆盖 + reviewer 显式审 invariant。

## 与其它 rule 关系

- 跟 [`identity-column-on-rewrite.md`](identity-column-on-rewrite.md) 配合：那条管 rewrite 时丢列，本条管对账时丢内容；都关 SessionService.rewriteMessages 的安全性
- 跟 [`pipeline.md`](pipeline.md) 红灯触发器配合：本条覆盖的文件全在核心文件清单 → 触碰必走 Full pipeline
