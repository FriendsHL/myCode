package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.repository.SessionAnnotationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionAnnotationReadToolTest {

    @Mock private SessionAnnotationRepository annotationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private SessionAnnotationReadTool tool;

    @BeforeEach
    void setUp() {
        tool = new SessionAnnotationReadTool(annotationRepository, objectMapper);
    }

    private SessionAnnotationEntity ann(String type, String value, String source) {
        SessionAnnotationEntity a = new SessionAnnotationEntity();
        a.setSessionId("sess-x");
        a.setAnnotationType(type);
        a.setAnnotationValue(value);
        a.setSource(source);
        a.setConfidence(new BigDecimal("0.85"));
        a.setReasoning("agent attempted X then Y");
        a.setCreatedAt(Instant.parse("2026-05-14T10:00:00Z"));
        return a;
    }

    @Test
    @DisplayName("happy path: returns all annotation rows for the given session")
    void execute_happyPath_returnsAnnotations() throws Exception {
        when(annotationRepository.findBySessionId("sess-x"))
                .thenReturn(List.of(
                        ann("outcome", "failure", SessionAnnotationEntity.SOURCE_LLM),
                        ann("suspect_surface", "skill", SessionAnnotationEntity.SOURCE_LLM),
                        ann("tool_failure", "true", SessionAnnotationEntity.SOURCE_SIGNAL)));

        SkillResult result = tool.execute(Map.of("sessionId", "sess-x"), null);

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> payload = objectMapper.readValue(
                result.getOutput(), new TypeReference<Map<String, Object>>() {});
        assertThat(payload).containsEntry("sessionId", "sess-x");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("annotations");
        assertThat(rows).hasSize(3);
        Map<String, Object> first = rows.get(0);
        assertThat(first).containsEntry("type", "outcome");
        assertThat(first).containsEntry("value", "failure");
        assertThat(first).containsEntry("source", SessionAnnotationEntity.SOURCE_LLM);
        assertThat(first.get("confidence").toString()).startsWith("0.85");
    }

    @Test
    @DisplayName("missing sessionId returns validation error")
    void execute_missingSessionId_returnsValidationError() {
        SkillResult result = tool.execute(Map.of(), null);

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("blank sessionId rejected explicitly (not just absent)")
    void execute_blankSessionId_returnsValidationError() {
        SkillResult result = tool.execute(Map.of("sessionId", "   "), null);

        assertThat(result.isSuccess()).isFalse();
    }
}
