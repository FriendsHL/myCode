package com.skillforge.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "skillforge.websearch.live", matches = "true")
class WebSearchToolLiveSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @EnabledIfEnvironmentVariable(named = "TAVILY_API_KEY", matches = ".+")
    void tavilyLiveSearchReturnsJsonResult() throws Exception {
        WebSearchTool tool = new WebSearchTool();

        SkillResult result = tool.execute(Map.of(
                "query", "SkillForge web search smoke test",
                "backend", "tavily",
                "maxResults", 1,
                "output_format", "json",
                "search_depth", "basic"), null);

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> payload = OBJECT_MAPPER.readValue(result.getOutput(), new TypeReference<>() {});
        assertThat(payload).containsEntry("backend", "tavily");
        assertThat((Iterable<?>) payload.get("results")).isNotEmpty();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EXA_API_KEY", matches = ".+")
    void exaLiveSearchReturnsJsonResult() throws Exception {
        WebSearchTool tool = new WebSearchTool();

        SkillResult result = tool.execute(Map.of(
                "query", "SkillForge web search smoke test",
                "backend", "exa",
                "maxResults", 1,
                "output_format", "json",
                "search_type", "fast"), null);

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> payload = OBJECT_MAPPER.readValue(result.getOutput(), new TypeReference<>() {});
        assertThat(payload).containsEntry("backend", "exa");
        assertThat((Iterable<?>) payload.get("results")).isNotEmpty();
    }
}
