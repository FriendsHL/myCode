# WEB-TOOLS-HARDENING — WebSearch / WebFetch 稳定性 + 信息保留升级

> 创建：2026-05-26
> 更新：2026-05-27（按用户最终拍板进入 Mid 实现）
> 状态：implementing
> 模式：Mid

## 决策

| # | 决策 | 当前口径 |
|---|---|---|
| D1 | 范围 | WebSearch backend priority chain + WebFetch html2md/cache/robots；Bash 不动 |
| D2 | WebSearch backend | `application.yml` 有序 list：Tavily > Exa > DuckDuckGo HTML |
| D3 | API key | YAML 中写 env placeholder：`${TAVILY_API_KEY:}` / `${EXA_API_KEY:}`；真实 key 不进 repo、不写日志 |
| D4 | SearXNG | V1 不做，移 ToDo/backlog；每周搜索调用量/费用明显升高或隐私/内网诉求出现再启动 |
| D5 | WebFetch html2md | 使用 `flexmark-html2md-converter`，默认 `content_format=markdown` |
| D6 | robots | hard block；不暴露 `bypass_robots`；只通过 operator hostAllowlist 跳过，默认 `localhost` / `127.0.0.1` |
| D7 | trace span | V1 不改 core 协议；用 output header + log，span attributes 留后续 core 协议变更 |

## 交付内容

### WebSearch

- `WebSearchBackend` 抽象 + `TavilyBackend` / `ExaBackend` / `DuckDuckGoHtmlBackend`。
- `skillforge.websearch.backend-priority` 是 YAML list，顺序就是 fallback 优先级。
- Tavily / Exa 都走官方 JSON API；DuckDuckGo HTML 只作为 last-resort fallback。
- 工具入参支持：
  - `backend=auto|tavily|exa|duckduckgo`
  - `output_format=text|json`
  - `include_domains` / `exclude_domains`
  - `time_range` / `start_date` / `end_date`
  - `topic` / `search_depth` / `search_type` / `country` / `user_location`
- Tavily 原生下推：`time_range/start_date/end_date/topic/search_depth/include_domains/exclude_domains/country`。
- Exa 原生下推：`startPublishedDate/endPublishedDate/type/category/includeDomains/excludeDomains/userLocation`。
- JSON 输出由 Tool 统一包装为 `{query, backend, latencyMs, results[]}`。

### WebFetch

- HTML 默认转换为 Markdown，保留 heading / link / list / code 等结构。
- 可选 `content_format=markdown|text|raw`，文本和原文可回退。
- 可选 `output_format=text|json`，JSON 输出包含 `url/statusCode/contentType/cache/robots/contentFormat/truncated/maxLength/content`。
- Caffeine in-memory cache：15min TTL，cache key 包含 URL + content format，`bypass_cache=true` 强制重新 fetch。
- robots.txt 使用 crawler-commons，robots 规则缓存 1h，404/5xx/timeout fail-open；Disallow 命中 hard block。

## 配置

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

  webfetch:
    robots:
      host-allowlist:
        - localhost
        - 127.0.0.1
```

## 验收点

- `TAVILY_API_KEY` 存在时默认优先走 Tavily。
- Tavily 未配置或失败时尝试 Exa；Exa 未配置或失败时兜底 DuckDuckGo HTML。
- 显式 `backend` 入参可以强制某个 backend。
- `output_format=json` 返回可解析 JSON。
- WebFetch HTML 输出默认含 Markdown 结构，不再是扁平 text。
- robots Disallow 返回 `SkillResult.error("blocked by robots.txt...")`。
- SearXNG 已列 backlog，不进入 V1。

## 关联

- [`mrd.md`](mrd.md)
- [`prd.md`](prd.md)
- [`tech-design.md`](tech-design.md)
