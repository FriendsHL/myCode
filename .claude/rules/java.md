---
paths:
  - "**/*.java"
---
# Java Rules for SkillForge

Java 17 + Spring Boot 3.2 + JPA/Hibernate + Maven multi-module 项目规范。

---

## ⚠️ Known Footguns（踩过的坑，触碰相关代码必读）

### 1. ObjectMapper 必须注册 JavaTimeModule

项目中存在大量 `new ObjectMapper()`，但 `SessionEntity` 等实体使用了 `Instant` / `LocalDateTime`。
未注册 `JavaTimeModule` 的 `ObjectMapper` 序列化这些类型时**不会报错，而是输出错误的时间戳格式**，极难排查。

```java
// BAD — 无法正确处理 Instant / LocalDateTime
private final ObjectMapper objectMapper = new ObjectMapper();

// GOOD — 必须注册 JavaTimeModule
private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
```

在 Spring context 内优先注入 Spring 管理的 `ObjectMapper` Bean（已配好所有模块），不要手动 `new`。

### 2. 时间字段统一用 Instant

数据库持久化字段统一使用 `Instant`，不要混用 `LocalDateTime`。`LocalDateTime` 不带时区，跨时区环境会出错。

```java
// BAD — 混用
private Instant lastCompactedAt;
private LocalDateTime createdAt;   // ← 不一致

// GOOD — 统一 Instant（新增字段）
private Instant createdAt;
private Instant updatedAt;
```

> 注：`createdAt` / `updatedAt` 字段当前是 `LocalDateTime`，历史遗留，**不要扩大**，新字段一律用 `Instant`。

### 3. LlmProvider 新实现必须处理 SocketTimeoutException 重试

参照 `ClaudeProvider`：`chat()` 方法只对 `SocketTimeoutException` 重试，其他 `IOException` 快速失败。
流式路径（`chatStream`）**不重试**——重试会导致已推送的 delta 重复交付给 handler。

### 4. 持久化层 vs Engine 内存层 Message 形态必须字节一致

ChatService 持久化进 `t_session_message.content_json` 的 Message 跟 AgentLoopEngine 内存 `messages` 列表里的同一条 logical message，**JSON 序列化字节必须完全相等**。否则 `SessionService.updateSessionMessages` 内部 `commonPrefixSize + messageEquals`（字节比较）对账时会把发散点之后的内容当 delta silently dup-append（Q2 commit `bdb0453` 这就是这条 invariant 的反面教材，Q3 `cc87776` 修；commit `b2c7039` 加 mid-prefix divergence guard 兜底）。

```java
// BAD — Q2 era 半残品（修于 Q3 cc87776）
ChatService.chatAsync:
    Message userMsg = buildUserMessageWithReminder(...);   // array-shape
    sessionService.appendNormalMessages(..., userMsg);       // 持久化 array
    chatLoopExecutor.execute(() -> runLoop(sessionId, userMessage, ...));   // ← 只透传 String

AgentLoopEngine.runInternal(... String userMessage ...):
    messages.add(Message.user(userMessage));   // ← 重建 String-shape！跟持久化形态不一致

// GOOD — Q3 后
ChatService.chatAsync:
    Message userMsg = buildUserMessageWithReminder(...);
    sessionService.appendNormalMessages(..., userMsg);
    final Message userMsgWithReminder = userMsg;             // ← capture object reference
    chatLoopExecutor.execute(() -> runLoop(..., userMsgWithReminder, ...));

AgentLoopEngine.run(AgentDef, String, Message userMessageBlock, List, ..., LoopContext):
    if (userMessageBlock != null) {
        messages.add(userMessageBlock);   // ← 同一个 Java 对象引用，字节必然一致
    } else if (userMessage != null) {
        messages.add(Message.user(userMessage));
    }
```

**完整 invariant + 4 触发条件 + roundtrip 测试模板**见 [`persistence-shape-invariant.md`](persistence-shape-invariant.md)（触碰 ChatService / AgentLoopEngine / CompactionService / SessionService.rewriteMessages / Message / ContentBlock 时必读）。

### 5. 给 t_session_message 加 identity 列后必须扩展 rewriteMessages preserve 逻辑

`SessionService.rewriteMessages` 走 DELETE+INSERT 模式。3-arg `AppendMessage` 默认所有 identity 列（`controlId` / `answeredAt` / `traceId` / 未来候选 `origin_span_id`）为 null。Q1 commit `a4100f7` 给 `trace_id` 加了 `snapshotTraceIds + patchTraceIds`，但**只覆盖 trace_id 一列**。新加 identity 列必须扩展同样模式，否则 rewrite 后该列被 silently 清空。

```java
// 加新 identity column 必经 4 步（详细 checklist 见独立 rule）：
// 1) Repository 加 XView projection + findNonNullXProjections JPQL
// 2) SessionService 加 snapshotX() 私有方法
// 3) updateSessionMessages 内调 patchX(messages, oldX)
// 4) AppendMessage record 加新字段 + 7-arg constructor 接受 + 3-arg null 默认
// 5) SessionServiceXxxPreservationIT 4 case（auto-preserve / caller-wins / no-op / tail 查询）
```

判断"是不是 identity 列"：**这条列回答"row 来自哪里 / 关联哪个上层概念" → 是；回答"row 现在内容是啥" → 不是**。

完整列分类表 + Light compact index-alignment limitation 见 [`identity-column-on-rewrite.md`](identity-column-on-rewrite.md)。

---

## 依赖注入

**始终使用构造器注入**，禁止字段注入（`@Autowired` 直接打在字段上）。

```java
// GOOD
@Service
public class SessionService {
    private final SessionRepository sessionRepository;
    private final AgentService agentService;

    public SessionService(SessionRepository sessionRepository, AgentService agentService) {
        this.sessionRepository = sessionRepository;
        this.agentService = agentService;
    }
}

// BAD
@Service
public class SessionService {
    @Autowired
    private SessionRepository sessionRepository;
}
```

---

## JPA Entity 规范

- Entity 类必须有无参构造器（JPA 要求）
- 字段默认可变（JPA 需要 setter）；业务逻辑中避免直接操作字段，通过 Service 方法修改
- 表名用 `t_` 前缀（已有惯例：`t_session`, `t_agent` 等）
- 时间审计字段用 `@EntityListeners(AuditingEntityListener.class)` + `@CreatedDate` / `@LastModifiedDate`
- 枚举状态字段用 `String` 存储（如 `status`, `runtimeStatus`），加 `@Column(length = 32)` 限制长度
- `TEXT` / `CLOB` 类字段加 `@Column(columnDefinition = "TEXT")` 或 `"CLOB"`，避免默认 255 截断

```java
@Entity
@Table(name = "t_example")
@EntityListeners(AuditingEntityListener.class)
public class ExampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 32)
    private String status = "active";

    @Column(columnDefinition = "TEXT")
    private String content;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public ExampleEntity() {}
    // getters + setters...
}
```

---

## @Transactional 使用规则

- `@Transactional` 放在 **Service 层**，不放在 Controller 或 Repository
- 只读操作加 `@Transactional(readOnly = true)` 提升性能
- 涉及多个 Repository 写操作的方法**必须**加 `@Transactional`，保证原子性
- 不要在 `private` 方法上加 `@Transactional`（Spring AOP 不生效）

```java
@Service
public class SessionService {

    @Transactional(readOnly = true)
    public SessionEntity getSession(String id) { ... }

    @Transactional
    public SessionEntity createSession(CreateSessionRequest req) { ... }
}
```

---

## Tool 接口实现规范

> 术语说明：原 `Skill` 接口（deprecated alias）已删除。SkillForge 里 "Tool" 指 Java 内置 function-calling 工具（A 类）；"Skill" 仅保留给 zip 包资产（B 类，如 `SkillDefinition` / `SkillRegistry` / `SkillPackageLoader` / `SkillContext` / `SkillResult` 等核心 API surface 命名）。

新增 Tool 必须实现 `com.skillforge.core.skill.Tool` 接口：

```java
public class MyTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(MyTool.class);

    // 依赖通过构造器注入，不用 @Autowired
    private final SomeDependency dep;

    public MyTool(SomeDependency dep) {
        this.dep = dep;
    }

    @Override
    public String getName() { return "MyTool"; }   // 全局唯一，驼峰

    @Override
    public String getDescription() { return "..."; } // 清晰描述用途，LLM 会读这个

    @Override
    public ToolSchema getToolSchema() { ... }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) { ... }
}
```

- `getName()` 返回值全局唯一，用 PascalCase
- `getDescription()` 面向 LLM，要准确描述功能和使用时机
- 在 `SkillForgeConfig` 中通过 `skillRegistry.registerTool(new MyTool(...))` 注册（注意：`SystemSkillLoader` 装载的是 B 类 zip 包资产，不是 Java Tool）

---

## LlmProvider 接口扩展规范

新增 LLM Provider 必须实现 `com.skillforge.core.llm.LlmProvider`，并在 `LlmProviderFactory` 中注册。
`chat()` 方法对超时重试，`chatStream()` 单次尝试不重试（参见 `ClaudeProvider` 实现）。

---

## 命名规范

- 类/接口：`PascalCase`（`SessionService`, `LlmProvider`）
- 方法/字段/变量：`camelCase`（`findById`, `runtimeStatus`）
- 常量：`SCREAMING_SNAKE_CASE`（`MAX_RETRIES`, `JSON_MEDIA_TYPE`）
- 包：全小写，`com.skillforge.<module>.<layer>`（如 `com.skillforge.server.service`）
- Entity 类名后缀 `Entity`（`SessionEntity`）
- Repository 接口后缀 `Repository`（`SessionRepository`）
- DTO 无强制后缀，但 Request/Response 类加后缀（`ChatRequest`, `ChatResponse`）

---

## 错误处理

- 域错误抛 `RuntimeException` 子类（unchecked），不要 checked exception
- 创建语义明确的异常类：`SessionNotFoundException`, `AgentNotFoundException` 等
- Controller / WebSocket handler 层统一 catch，返回结构化错误响应，**不暴露** stack trace 给客户端
- 日志记录用 SLF4J：`private static final Logger log = LoggerFactory.getLogger(Xxx.class);`

```java
// Domain exception
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(String id) {
        super("Session not found: " + id);
    }
}

// Service 层抛
public SessionEntity getSession(String id) {
    return sessionRepository.findById(id)
            .orElseThrow(() -> new SessionNotFoundException(id));
}
```

---

## Optional 使用规则

- Repository `findBy*` 方法返回 `Optional<T>`
- 用 `.orElseThrow()` 而不是 `.get()`（不先 `isPresent()` 就 `get()` 是 NPE 定时炸弹）
- 不要把 `Optional` 用作字段类型或方法参数

---

## 安全规范

- API Key / 密钥从环境变量或 `application.yml` 读取，**绝不硬编码**
- 日志中不打印 API Key、用户 token、会话内容等敏感信息
- 用户输入（Tool 参数、Chat 内容）在执行系统命令前必须校验，防止命令注入
- JPQL / 原生 SQL 使用参数化查询，禁止字符串拼接

---

## 测试规范

- 测试框架：JUnit 5 + Mockito + AssertJ
- 测试命名：`methodName_scenario_expectedBehavior()`，加 `@DisplayName` 说明
- 单元测试用 `@ExtendWith(MockitoExtension.class)`，构造器注入依赖 Mock
- Spring Boot 集成测试用 `@SpringBootTest`，数据库测试优先用 H2（项目默认配置）
- 测试包结构镜像 `src/main/java`

```java
@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private SessionRepository sessionRepository;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(sessionRepository, ...);
    }

    @Test
    @DisplayName("getSession throws when session not found")
    void getSession_notFound_throws() {
        when(sessionRepository.findById("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> sessionService.getSession("x"))
                .isInstanceOf(SessionNotFoundException.class);
    }
}
```
