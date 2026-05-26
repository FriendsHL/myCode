# MRD — WEB-TOOLS-HARDENING

> 创建：2026-05-26
> 状态：mrd

## 用户原话（2026-05-26）

> "🔴 WebSearch 换 SearXNG/Brave API（稳定性） 小
> 🔴 WebFetch 用 Turndown/mdream 替换 .text()（信息保留） 小
> 🟡 WebFetch 增加缓存（15min） 小
> 🟡 WebFetch 增加 robots.txt 检查 中
> 🟡 Bash 增加超时控制和输出截断 小
>
> 你看下，这个是我分析 websearch、webfetch 工具之后 得到的信息"

> （5-26 后续 #1）"websearch 工具 brave 可以换成 tavily 和 exa 这两个工具 这样的话成本应该更低。SearXNG 的话 我们本地还要部署 docker"

> （5-26 后续 #2）"我希望是 有一个yml配置：以tavily 优先级最高，Exa 次之 然后是DuckDuckGo。SearXNG 这个东西后续列在ToDo里面吧，属于重要不紧急的事情，如果发现每周用的次数太多了 再说。然后所需要的api-key需要读取本地env环境变量。"

## 现状 grep 发现（5-26 晚跑过）

```
WebSearchTool.java:185 行
  └─ 抓 https://html.duckduckgo.com/html/?q=... DDG HTML 页面（不是 API）

WebFetchTool.java:151 行
  ├─ Jsoup.parse(html).body().text() — strip 所有结构
  ├─ 无缓存
  └─ 无 robots.txt 检查

BashTool.java:117 行
  ├─ DEFAULT_TIMEOUT_MS + user 可传 timeout (max 600s)
  ├─ process.waitFor(timeout, TimeUnit.MILLISECONDS) + destroyForcibly()
  └─ MAX_OUTPUT_LENGTH=50000 字符 + "[output truncated]" 标记
```

→ **Bash 两条已实现** → 用户拍板拿掉；剩下 4 件 WebSearch + WebFetch 改动入需求包

## 背景

### 触发场景（用户 + grep 反推）

SkillForge 现有 `WebSearchTool` 是 dogfood 阶段最早写的 — 当时为了快速 demo 直接抓 DuckDuckGo HTML 页面，没用任何 API。但生产 dogfood 后暴露问题：

1. **DDG 改 HTML 结构就崩** — 抓 HTML 是反向工程，Selector 写死后 DDG 任何小改就失效
2. **DDG rate limit 严** — 单 IP 高频被 403/429
3. **结果质量打折** — HTML 抓的结果数 + ranking 不如 API
4. **没有 query type 区分** — API 通常支持 web/news/video/image 分类，抓页面是 web only

`WebFetchTool` 同样是早期写的：用 Jsoup `text()` 一把扁平化 HTML — 简单但**丢所有结构**：
- 标题 / heading 层级丢失（agent 没法判断主题 vs 旁支）
- 链接的 anchor 文本和 url 分开放（agent 没法看出"点这个去 X"）
- 列表 / 表格变扁平段落
- 代码块变 prose
- 图片 alt text 丢失

对 agent reasoning 来说，**信息保留质量直接 → reasoning 质量**。换 html→markdown 是高 ROI 改动。

`WebFetchTool` 无缓存 → 同一 url 反复 fetch（agent 经常前一轮看了 URL 后一轮还要再看一次某段），浪费 latency + 上游带宽 + 万一上游限流就崩。15min TTL 是经验值（保 freshness 同时挡掉短期重复请求）。

`WebFetchTool` 无 robots.txt → SkillForge 作为爬虫不守规矩，agent 大规模跑时可能被网站 ban 整个出口 IP。robots.txt 是 web crawler 基本礼貌。

### 痛点

1. **WebSearch 抓 HTML 脆弱**（🔴 P1）— DDG 改一次 SkillForge 整套 agent 的 web search 能力就 down
2. **WebFetch text() 丢结构**（🔴 P1）— agent 拿到一段没 markdown 结构的 HTML 转 prose 极难做 reasoning
3. **WebFetch 无缓存**（🟡 P2）— 重复 fetch 浪费 + 上游限流风险
4. **WebFetch 不守 robots.txt**（🟡 P2）— 爬虫礼貌 + 防 IP ban
5. **依赖管理风险**（隐式） — 引入新搜索 backend 涉及 API key 怎么管 + flexmark / crawler-commons 等新依赖必须由 java-build-resolver 验过不冲突

## 限制

1. **架构边界**：`skillforge-tools/` 模块独立 jar，不引 Spring 依赖（grep `pom.xml` 确认）；Caffeine + flexmark + 可选 crawler-commons 都是 plain Java lib OK
2. **不学** Anthropic Managed Agents 走 hosted SaaS 路线 — SkillForge 自托管开源
3. **不破坏** 现有 tool API：`web_search` / `web_fetch` 现有 input/output schema 不变；新加参数都是 optional（`bypass_cache`）
4. **WebSearch backend key 管理**：`application.yml` 只配 backend priority；Tavily/Exa API key 读取本地 env 环境变量，不进 DB、不写日志（DB 加密后续 SEC-1 backlog 包再说）

## 未澄清问题（开 Plan 时确认）

| # | 问题 | 候选答案 |
|---|---|---|
| Q1 | WebSearch backend D3 = yml priority chain：Tavily > Exa > DuckDuckGo HTML；SearXNG 移 ToDo/backlog | 已澄清：V1 不做 SearXNG；`application.yml` 配 `backend-priority`，运行时按顺序挑第一个可用 backend；Tavily/Exa key 从本地 env 读取；DuckDuckGo HTML last-resort |
| Q2 | WebFetch 缓存 D5 = Caffeine in-memory — 多 BE 实例（容器复制 N 份）会各自缓存 → 命中率打折；要不要 Redis？ | 候选：V1 in-memory（SkillForge 当前是单 server 部署），Redis 留 V2 看 SkillForge 真扩到多实例时再做 |
| Q3 | robots.txt 检查 D6 = hard block — 但 SkillForge 内部 dogfood 场景（拉自家 dashboard / 内网 wiki）也会被挡，要不要白名单 host？ | 候选：V1 加 `skillforge.web.robots.allowlist` 配置（默认包含 `localhost` / `127.0.0.1`），生产环境可加内网域名；不默认暴露 `bypass_robots` 给 agent |
| Q4 | WebFetch html→md D4 = flexmark-html2md-converter — 输出质量 corner case（table / nested list / code block / blockquote）是否真够好？开 Plan 时是否要先做 spike？ | 候选：开 Plan 时 dev 用 5 个真实网页（含 GitHub README / Wikipedia / Stack Overflow / Medium / Anthropic docs）跑一次 flexmark + copydown 对照，决定最终选 |
| Q5 | WebFetch 缓存 key = SHA256(url + custom request headers) — 但 LLM agent 实际很少传 custom headers，简化成 url-only 是否够？ | 候选：V1 简化 url-only（90% case），V2 看是否真需要 header 维度 |

## 关联

- 现状代码：
  - `skillforge-tools/src/main/java/com/skillforge/tools/WebSearchTool.java` (185 行)
  - `skillforge-tools/src/main/java/com/skillforge/tools/WebFetchTool.java` (151 行)
  - `skillforge-tools/src/main/java/com/skillforge/tools/BashTool.java` (117 行 — 已实现 timeout + 截断，不动)
- 兄弟需求包：[`active/2026-05-26-DREAMING-MEMORY-EXTENSION/`](../2026-05-26-DREAMING-MEMORY-EXTENSION/index.md)
- 候选库：
  - html→md：`com.vladsch.flexmark:flexmark-html2md-converter`（D4 选）
  - cache：`com.github.ben-manes.caffeine:caffeine`
  - robots.txt: `com.github.crawler-commons:crawler-commons`（候选）或自实现简单 parser
  - search backend priority chain
    - **Tavily** ([docs.tavily.com](https://docs.tavily.com/)) — priority #1，AI-native；HTTPS POST + Bearer API key（env）
    - **Exa** ([docs.exa.ai](https://docs.exa.ai/)) — priority #2，semantic / neural search，AI agent 优化；HTTPS POST + `x-api-key` header（env）
    - **DuckDuckGo HTML** @Deprecated — priority #3 / last-resort fallback；无 API key
    - **SearXNG** ([docs.searxng.org](https://docs.searxng.org/dev/search_api.html)) — V1 不做，移 ToDo/backlog；每周调用量/费用升高后再启动自部署聚合路线
  - Java 无官方 SDK，Tavily / Exa / DuckDuckGo HTML 都直接 OkHttp 调（跟现有 LlmProvider HTTP 调用模式一致）；**已否决 Brave**（成本相对 Tavily/Exa 高）
