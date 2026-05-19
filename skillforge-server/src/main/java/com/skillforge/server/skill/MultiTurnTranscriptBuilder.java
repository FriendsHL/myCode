package com.skillforge.server.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.eval.MultiTurnTranscript;
import com.skillforge.server.repository.SessionMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SKILL-CREATOR-PHASE-1.6 Phase 1.1 (2026-05-19) — builds a
 * {@link MultiTurnTranscript} from a persisted child {@code SessionEntity}'s
 * {@code t_session_message} rows, ready for
 * {@link com.skillforge.server.eval.EvalJudgeTool#judgeMultiTurnConversation}.
 *
 * <p>Why a separate component vs. inline in the coordinator: the transcript
 * build needs to filter pure conversation turns (user / assistant text)
 * from operational rows (tool_use / tool_result / system / pruned). Keeping
 * that filter logic separate lets us unit-test it in isolation and reuse
 * for future eval paths (e.g. attribution A/B v2 may want the same view).
 *
 * <p>Row filter strategy (verified Phase 1.0 — see
 * {@code SessionMessageEntity}):
 * <ul>
 *   <li>Keep: rows where {@code role == "user"} or {@code role == "assistant"}
 *       AND the role-bearing content has a non-blank text block.</li>
 *   <li>Skip: {@code role == "system"} (instructions, not conversation),
 *       {@code msgType == "tool_use"} / {@code msgType == "tool_result"}
 *       (operational, not transcript), pruned rows
 *       ({@code prunedAt != null} — already excluded from compact view).</li>
 *   <li>For multimodal {@code List<ContentBlock>} content shapes, render
 *       only the first {@code type=="text"} block as the turn body; ignore
 *       image / pdf refs (the LLM judge prompt is text-only).</li>
 * </ul>
 *
 * <p>Edge cases:
 * <ul>
 *   <li>Empty session (no messages) → returns an empty transcript;
 *       caller decides how to score (judge degrades to 0 score per
 *       {@code EvalJudgeTool} contract).</li>
 *   <li>Malformed {@code content_json} → log warn + skip that row; other
 *       turns still render.</li>
 * </ul>
 */
@Component
public class MultiTurnTranscriptBuilder {

    private static final Logger log = LoggerFactory.getLogger(MultiTurnTranscriptBuilder.class);

    /** Safety cap: skill-eval child sessions shouldn't exceed this many turns. */
    private static final int MAX_TURNS = 1000;

    private final SessionMessageRepository sessionMessageRepository;
    private final ObjectMapper objectMapper;

    public MultiTurnTranscriptBuilder(SessionMessageRepository sessionMessageRepository,
                                      ObjectMapper objectMapper) {
        this.sessionMessageRepository = sessionMessageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Build a {@link MultiTurnTranscript} from the child session's
     * {@code t_session_message} rows. Returns an empty transcript on
     * unknown / empty session — caller decides how to handle.
     *
     * @param sessionId child session id (typically a SubAgent run's
     *                  child session created by
     *                  {@code SkillCreatorService.dispatchOne})
     */
    public MultiTurnTranscript fromSession(String sessionId) {
        MultiTurnTranscript transcript = new MultiTurnTranscript();
        if (sessionId == null || sessionId.isBlank()) {
            return transcript;
        }
        Page<SessionMessageEntity> rows = sessionMessageRepository
                .findBySessionIdOrderBySeqNoAsc(sessionId, PageRequest.of(0, MAX_TURNS));
        if (rows == null || rows.getContent().isEmpty()) {
            return transcript;
        }
        for (SessionMessageEntity row : rows.getContent()) {
            if (!isConversationRow(row)) continue;
            String text = extractText(row);
            if (text == null || text.isBlank()) continue;
            transcript.add(row.getRole(), text);
        }
        return transcript;
    }

    /** True iff this row is a user / assistant conversational turn (not tool / system / pruned). */
    private boolean isConversationRow(SessionMessageEntity row) {
        if (row == null) return false;
        if (row.getPrunedAt() != null) return false;
        String role = row.getRole();
        if (role == null) return false;
        if (!"user".equals(role) && !"assistant".equals(role)) return false;
        String msgType = row.getMsgType();
        // Per Phase 1.0 verify: msgType "tool_use" / "tool_result" are operational
        // rows, not conversation. msgType == null OR "text" is the dialog turn.
        if ("tool_use".equals(msgType) || "tool_result".equals(msgType)) return false;
        return true;
    }

    /**
     * Pull a plain-text body from the row's {@code content_json}. Handles both:
     * <ul>
     *   <li>Plain {@code String} content (legacy / simple user turn)</li>
     *   <li>{@code List<ContentBlock>} content (multimodal / array-shape) —
     *       returns the first {@code type=="text"} block's text.</li>
     * </ul>
     */
    private String extractText(SessionMessageEntity row) {
        String contentJson = row.getContentJson();
        if (contentJson == null || contentJson.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(contentJson);
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.isArray()) {
                for (JsonNode block : node) {
                    JsonNode type = block.path("type");
                    JsonNode text = block.path("text");
                    if (type.isTextual() && "text".equals(type.asText())
                            && text.isTextual() && !text.asText().isBlank()) {
                        return text.asText();
                    }
                }
                return null;
            }
            // Fallback for object-shape content (rare): try a top-level "text" field.
            JsonNode text = node.path("text");
            if (text.isTextual()) return text.asText();
            // Last resort — stringify so the judge at least sees something.
            return contentJson;
        } catch (Exception e) {
            log.warn("MultiTurnTranscriptBuilder: failed to parse content_json for session_message {}: {}",
                    row.getId(), e.getMessage());
            return null;
        }
    }
}
