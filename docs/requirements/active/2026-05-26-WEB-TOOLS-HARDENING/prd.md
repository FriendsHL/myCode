# PRD — WEB-TOOLS-HARDENING V1

> 创建：2026-05-26
> 更新：2026-05-27
> 状态：implementing

## 目标

1. WebSearch 从单一 DuckDuckGo HTML scraping 升级为 Tavily > Exa > DuckDuckGo HTML priority chain。
2. WebSearch 支持结构化 JSON 输出和常用 provider 原生过滤参数。
3. WebFetch 从 `Jsoup.text()` 升级为 `flexmark-html2md-converter`，默认保留 Markdown 结构。
4. WebFetch 增加 15min in-memory cache 和 robots.txt hard block。

## 非目标

- 不做 Brave backend。
- 不做 SearXNG V1；列 backlog，等使用量/费用或隐私/内网需求触发。
- 不默认暴露 `bypass_robots` 给 agent。
- 不改 core trace/tool result 协议来写 span attributes。
- 不做 Redis cache / crawl-delay / DB secrets。

## 功能需求

### FR-1 WebSearch Priority Chain

`application.yml` 使用有序 list 表达优先级：

```yaml
skillforge:
  websearch:
    backend-priority:
      - tavily
      - exa
      - duckduckgo
    tavily:
      api-key: ${TAVILY_API_KEY:}
    exa:
      api-key: ${EXA_API_KEY:}
```

运行规则：

- `backend=auto` 或不传时，按 list 顺序尝试。
- backend key 缺失时跳过。
- backend 调用失败时 warn log 后尝试下一个。
- `backend=tavily|exa|duckduckgo` 时强制指定，失败不再 fallback。

### FR-2 WebSearch Input/Output

基础参数：

- `query` required
- `maxResults`
- `backend`
- `output_format=text|json`
- `include_domains` / `exclude_domains`

时间和 provider 参数：

- `time_range=day|week|month|year|d|w|m|y`
- `start_date` / `end_date`，格式 `YYYY-MM-DD`
- `topic`
- `search_depth`，Tavily：`basic|advanced`
- `search_type`，Exa：`instant|fast|auto|deep-lite|deep|deep-reasoning`
- `country`，Tavily general search country boost
- `user_location`，Exa two-letter country code

JSON output shape：

```json
{
  "query": "string",
  "backend": "tavily",
  "latencyMs": 123,
  "includeDomains": ["example.com"],
  "excludeDomains": ["spam.example"],
  "results": [
    {
      "rank": 1,
      "title": "Title",
      "url": "https://example.com",
      "snippet": "Snippet",
      "publishedDate": "optional",
      "score": 0.9
    }
  ]
}
```

### FR-3 WebSearch Provider Mapping

Tavily request：

- `query`
- `max_results`
- `search_depth`
- `topic`
- `time_range`
- `start_date`
- `end_date`
- `include_domains`
- `exclude_domains`
- `country` only when topic is absent or `general`

Exa request：

- `query`
- `numResults`
- `contents.highlights=true`
- `startPublishedDate`
- `endPublishedDate`
- `type`
- `category`
- `includeDomains`
- `excludeDomains`
- `userLocation`

DuckDuckGo HTML：

- Only `q` is sent to DDG.
- Domain filters are enforced locally after parsing.
- Time filters are not native for DDG V1.

### FR-4 WebFetch Markdown / Formats

Input：

- `url` required
- `maxLength`
- `bypass_cache`
- `content_format=markdown|text|raw`
- `output_format=text|json`
- `timeout_ms`

Behavior：

- HTML defaults to Markdown via flexmark.
- `content_format=text` uses Jsoup plain text.
- `content_format=raw` returns raw body.
- JSON responses keep pretty-print behavior.

### FR-5 WebFetch Cache / Robots

- Fetch cache: Caffeine, 15min TTL, max 1000 entries.
- Cache key: URL + content format.
- Robots cache: Caffeine, 1h TTL, max 500 entries.
- Host allowlist comes from YAML:

```yaml
skillforge:
  webfetch:
    robots:
      host-allowlist:
        - localhost
        - 127.0.0.1
```

## 验证

- `mvn -pl skillforge-tools test -Dtest='WebSearchToolPriorityTest,WebFetchToolHardeningTest'`
- `mvn -pl skillforge-server -am -DskipTests compile`
- Optional live smoke:
  `mvn -pl skillforge-tools test -Dtest='WebSearchToolLiveSmokeTest' -Dskillforge.websearch.live=true`
