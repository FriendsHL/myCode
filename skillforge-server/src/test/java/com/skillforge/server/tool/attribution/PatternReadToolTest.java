package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.PatternSessionMemberEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.PatternSessionMemberRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatternReadToolTest {

    @Mock private SessionPatternRepository patternRepository;
    @Mock private PatternSessionMemberRepository memberRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PatternReadTool tool;

    @BeforeEach
    void setUp() {
        tool = new PatternReadTool(patternRepository, memberRepository, objectMapper);
    }

    private SessionPatternEntity samplePattern() {
        SessionPatternEntity p = new SessionPatternEntity();
        p.setId(42L);
        p.setSignature("failure|skill|Bash|7");
        p.setOutcome("failure");
        p.setSuspectSurface("skill");
        p.setTopFailingTool("Bash");
        p.setAgentId(7L);
        p.setMemberCount(4);
        Instant t = Instant.parse("2026-05-14T10:00:00Z");
        p.setFirstSeenAt(t.minusSeconds(7200));
        p.setLastSeenAt(t);
        return p;
    }

    private PatternSessionMemberEntity member(String sessionId) {
        PatternSessionMemberEntity m = new PatternSessionMemberEntity();
        m.setPatternId(42L);
        m.setSessionId(sessionId);
        m.setAddedAt(Instant.now());
        return m;
    }

    @Test
    @DisplayName("happy path: returns full metadata + capped member session ids")
    void execute_happyPath_returnsMetadataAndMembers() throws Exception {
        when(patternRepository.findById(42L)).thenReturn(Optional.of(samplePattern()));
        when(memberRepository.findByPatternIdOrderByAddedAtDesc(eq(42L), any(Pageable.class)))
                .thenReturn(List.of(member("sess-1"), member("sess-2"), member("sess-3")));

        SkillResult result = tool.execute(Map.of("patternId", 42, "memberLimit", 5), null);

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> payload = objectMapper.readValue(
                result.getOutput(), new TypeReference<Map<String, Object>>() {});
        assertThat(payload).containsEntry("patternId", 42);
        assertThat(payload).containsEntry("signature", "failure|skill|Bash|7");
        assertThat(payload).containsEntry("outcome", "failure");
        assertThat(payload).containsEntry("suspectSurface", "skill");
        assertThat(payload).containsEntry("topFailingTool", "Bash");
        assertThat(payload).containsEntry("agentId", 7);
        assertThat(payload).containsEntry("memberCount", 4);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) payload.get("memberSessionIds");
        assertThat(ids).containsExactly("sess-1", "sess-2", "sess-3");
    }

    @Test
    @DisplayName("missing patternId in input returns validation error")
    void execute_missingPatternId_returnsValidationError() {
        SkillResult result = tool.execute(Map.of(), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getOutput()).isNull();
        // validationError messages flow through getError on the SkillResult contract.
    }

    @Test
    @DisplayName("pattern not found returns validation error (clean LLM message, no exception)")
    void execute_patternNotFound_returnsValidationError() {
        when(patternRepository.findById(anyLong())).thenReturn(Optional.empty());

        SkillResult result = tool.execute(Map.of("patternId", 999), null);

        assertThat(result.isSuccess()).isFalse();
    }
}
