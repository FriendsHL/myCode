package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.repository.SessionAnnotationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.2 — STEP 2a of the {@code attribution-curator}
 * pipeline. Reads the V1 annotator's outcome / suspect_surface / signal labels
 * for one session so the curator can correlate the pattern signature with
 * grounded per-session evidence.
 *
 * <p>Wire shape:
 * <ul>
 *   <li>input: {@code { "sessionId": string (required) }}</li>
 *   <li>output: {@code { "sessionId", "annotations": [{ "type", "value",
 *       "source", "confidence", "reasoning", "createdAt" }] }}</li>
 * </ul>
 *
 * <p>Read-only — the curator never overwrites V1 annotator labels; if the
 * curator disagrees, that disagreement surfaces as a {@code description} field
 * on the {@code t_optimization_event} row written by
 * {@link ProposeOptimizationTool}.
 */
public class SessionAnnotationReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SessionAnnotationReadTool.class);

    private final SessionAnnotationRepository annotationRepository;
    private final ObjectMapper objectMapper;

    public SessionAnnotationReadTool(SessionAnnotationRepository annotationRepository,
                                     ObjectMapper objectMapper) {
        this.annotationRepository = annotationRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "SessionAnnotationRead";
    }

    @Override
    public String getDescription() {
        return "STEP 2 of the attribution-curator pipeline. Given a sessionId, "
                + "returns all V1 t_session_annotation rows for that session — "
                + "outcome / suspect_surface / signal labels written by the V1 "
                + "session-annotator agent. Read-only.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sessionId", Map.of(
                "type", "string",
                "description", "Required t_session.id (36-char UUID) to read annotations for."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("sessionId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required (sessionId at minimum)");
            }
            Object raw = input.get("sessionId");
            String sessionId = raw == null ? null : raw.toString().trim();
            if (sessionId == null || sessionId.isEmpty()) {
                return SkillResult.validationError("sessionId is required and must be non-blank");
            }

            List<SessionAnnotationEntity> rows = annotationRepository.findBySessionId(sessionId);
            List<Map<String, Object>> annotations = new ArrayList<>(rows.size());
            for (SessionAnnotationEntity row : rows) {
                Map<String, Object> ann = new LinkedHashMap<>();
                ann.put("type", row.getAnnotationType());
                ann.put("value", row.getAnnotationValue());
                ann.put("source", row.getSource());
                ann.put("confidence", row.getConfidence());
                ann.put("reasoning", row.getReasoning());
                ann.put("createdAt", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
                annotations.add(ann);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("sessionId", sessionId);
            response.put("annotations", annotations);
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.error("SessionAnnotationRead execute failed", e);
            return SkillResult.error("SessionAnnotationRead error: " + e.getMessage());
        }
    }
}
