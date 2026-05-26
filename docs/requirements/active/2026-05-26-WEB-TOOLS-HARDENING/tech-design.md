# Tech Design — WEB-TOOLS-HARDENING V1

> 创建：2026-05-26
> 更新：2026-05-27
> 状态：implementing

## Architecture

```
SkillForgeConfig
  └─ WebToolsProperties (@ConfigurationProperties "skillforge")
       ├─ websearch.backendPriority: [tavily, exa, duckduckgo]
       ├─ websearch.tavily.apiKey: ${TAVILY_API_KEY:}
       ├─ websearch.exa.apiKey: ${EXA_API_KEY:}
       └─ webfetch.robots.hostAllowlist: [localhost, 127.0.0.1]

WebSearchTool
  ├─ WebSearchConfig
  ├─ TavilyBackend
  ├─ ExaBackend
  └─ DuckDuckGoHtmlBackend (@Deprecated fallback)

WebFetchTool
  ├─ FlexmarkHtmlConverter
  ├─ Caffeine fetch cache
  └─ RobotsRuleService + crawler-commons + Caffeine robots cache
```

## Files

| Area | Files |
|---|---|
| Tool deps | `skillforge-tools/pom.xml` |
| WebSearch tool | `skillforge-tools/src/main/java/com/skillforge/tools/WebSearchTool.java` |
| WebSearch model/config | `skillforge-tools/src/main/java/com/skillforge/tools/websearch/*` |
| WebSearch backends | `skillforge-tools/src/main/java/com/skillforge/tools/websearch/backend/*` |
| WebFetch tool | `skillforge-tools/src/main/java/com/skillforge/tools/WebFetchTool.java` |
| WebFetch config/robots | `skillforge-tools/src/main/java/com/skillforge/tools/webfetch/*` |
| Server binding | `skillforge-server/src/main/java/com/skillforge/server/config/WebToolsProperties.java` |
| Registry wiring | `skillforge-server/src/main/java/com/skillforge/server/config/SkillForgeConfig.java` |
| YAML | `skillforge-server/src/main/resources/application.yml` |
| Tests | `skillforge-tools/src/test/java/com/skillforge/tools/WebSearchToolPriorityTest.java`, `WebFetchToolHardeningTest.java`, `WebSearchToolLiveSmokeTest.java` |

## Key Choices

- Keep `skillforge-tools` Spring-free. YAML binding lives in server via `WebToolsProperties`; tools receive plain Java configs.
- Use YAML list for backend priority so ordering is explicit and reviewable.
- Keep API keys in YAML as env placeholders, not literal secrets.
- Default to text output for backward compatibility; add `output_format=json` as opt-in.
- Use provider native filters where available, then local domain filtering for consistent output.
- Do not expose `bypass_robots`; use host allowlist only.
- Do not modify core trace/tool result protocols for span attributes in V1.
- SearXNG stays backlog, not a hidden V1 backend.

## Provider Mapping

| SkillForge input | Tavily | Exa | DuckDuckGo HTML |
|---|---|---|---|
| `query` | `query` | `query` | `q` |
| `maxResults` | `max_results` | `numResults` | local max parse |
| `include_domains` | `include_domains` | `includeDomains` | local filter |
| `exclude_domains` | `exclude_domains` | `excludeDomains` | local filter |
| `time_range` | `time_range` | converted to `startPublishedDate` | not native |
| `start_date` | `start_date` | `startPublishedDate` | not native |
| `end_date` | `end_date` | `endPublishedDate` | not native |
| `topic` | `topic` | `category` | not native |
| `search_depth` | `search_depth` | ignored | ignored |
| `search_type` | ignored | `type` | ignored |
| `country` | `country` for general topic | ignored | ignored |
| `user_location` | ignored | `userLocation` | ignored |

## Risk Notes

- Tavily `advanced` depth costs more than `basic`; default remains `basic`.
- Exa category `company` / `people` has filter restrictions; V1 passes user params through and surfaces provider errors.
- DuckDuckGo HTML fallback is inherently brittle and marked deprecated.
- `WebSearchToolLiveSmokeTest` is gated by both env vars and `-Dskillforge.websearch.live=true` to avoid accidental quota use.

## Verification

- Mocked priority/filter/JSON tests:
  `mvn -pl skillforge-tools test -Dtest='WebSearchToolPriorityTest,WebFetchToolHardeningTest'`
- Server binding compile:
  `mvn -pl skillforge-server -am -DskipTests compile`
- Live smoke:
  `mvn -pl skillforge-tools test -Dtest='WebSearchToolLiveSmokeTest' -Dskillforge.websearch.live=true`
