package com.skillforge.server.tool.memorysynth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListActiveUsersTool")
class ListActiveUsersToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SessionRepository sessionRepository;

    @Test
    @DisplayName("default lookbackDays=7 → queries with now-7d cutoff")
    void execute_default_lookback() throws Exception {
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any()))
                .thenReturn(List.of(1L, 2L, 3L));
        ListActiveUsersTool tool = new ListActiveUsersTool(sessionRepository, objectMapper);

        Instant before = Instant.now().minus(Duration.ofDays(7));
        SkillResult result = tool.execute(Map.of(), new SkillContext());
        Instant after = Instant.now().minus(Duration.ofDays(7));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("count").asInt()).isEqualTo(3);
        assertThat(root.path("lookbackDays").asInt()).isEqualTo(7);
        assertThat(root.path("userIds")).hasSize(3);

        ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(sessionRepository).findDistinctUserIdsWithRecentUserMessage(sinceCap.capture());
        Instant since = sinceCap.getValue();
        // Allow a 10-second tolerance for test execution scheduling.
        assertThat(since).isBetween(
                before.minusSeconds(10), after.plusSeconds(10));
    }

    @Test
    @DisplayName("custom lookbackDays is respected and clamped to [1, 90]")
    void execute_custom_lookback_clamped() throws Exception {
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any()))
                .thenReturn(List.of(7L));
        ListActiveUsersTool tool = new ListActiveUsersTool(sessionRepository, objectMapper);

        // 30 days within range
        SkillResult ok = tool.execute(Map.of("lookbackDays", 30), new SkillContext());
        JsonNode okRoot = objectMapper.readTree(ok.getOutput());
        assertThat(okRoot.path("lookbackDays").asInt()).isEqualTo(30);

        // 9999 days clamps to 90
        SkillResult tooBig = tool.execute(Map.of("lookbackDays", 9999), new SkillContext());
        assertThat(objectMapper.readTree(tooBig.getOutput()).path("lookbackDays").asInt()).isEqualTo(90);

        // 0 clamps to 1
        SkillResult tooSmall = tool.execute(Map.of("lookbackDays", 0), new SkillContext());
        assertThat(objectMapper.readTree(tooSmall.getOutput()).path("lookbackDays").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("empty result returns count=0 and empty userIds")
    void execute_empty() throws Exception {
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any()))
                .thenReturn(List.of());
        ListActiveUsersTool tool = new ListActiveUsersTool(sessionRepository, objectMapper);

        SkillResult result = tool.execute(Map.of(), new SkillContext());

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("count").asInt()).isEqualTo(0);
        assertThat(root.path("userIds")).isEmpty();
    }

    @Test
    @DisplayName("isReadOnly=true so dogfood pipeline doesn't gate it on write approval")
    void isReadOnly_true() {
        ListActiveUsersTool tool = new ListActiveUsersTool(sessionRepository, objectMapper);
        assertThat(tool.isReadOnly()).isTrue();
    }

    @Test
    @DisplayName("Gap-1: SYSTEM_USER_ID=0 filtered out of active users")
    void execute_filtersSystemUserId() throws Exception {
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any()))
                .thenReturn(List.of(0L, 1L, 2L));
        ListActiveUsersTool tool = new ListActiveUsersTool(sessionRepository, objectMapper);

        SkillResult result = tool.execute(Map.of(), new SkillContext());

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("count").asInt()).isEqualTo(2);
        JsonNode ids = root.path("userIds");
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0).asLong()).isEqualTo(1L);
        assertThat(ids.get(1).asLong()).isEqualTo(2L);
    }
}
