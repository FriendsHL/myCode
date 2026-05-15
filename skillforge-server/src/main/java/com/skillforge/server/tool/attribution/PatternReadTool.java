package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.PatternSessionMemberEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.PatternSessionMemberRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.2 — STEP 1 of the {@code attribution-curator}
 * agent's pipeline. Returns the pattern metadata + the latest N member session
 * ids so STEP 2 can drill into them.
 *
 * <p>Wire shape:
 * <ul>
 *   <li>input: {@code { "patternId": long (required), "memberLimit": int (optional, default 5) }}</li>
 *   <li>output: {@code { "patternId", "signature", "outcome", "suspectSurface",
 *       "topFailingTool", "agentId", "memberCount", "firstSeenAt", "lastSeenAt",
 *       "memberSessionIds": [string] }}</li>
 * </ul>
 *
 * <p>{@code memberLimit} clamps to [1, 20] — the system prompt caps STEP 2
 * sampling at 5; higher values waste tokens without improving attribution.
 */
public class PatternReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PatternReadTool.class);

    static final int DEFAULT_MEMBER_LIMIT = 5;
    static final int MIN_MEMBER_LIMIT = 1;
    static final int MAX_MEMBER_LIMIT = 20;

    private final SessionPatternRepository patternRepository;
    private final PatternSessionMemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    public PatternReadTool(SessionPatternRepository patternRepository,
                           PatternSessionMemberRepository memberRepository,
                           ObjectMapper objectMapper) {
        this.patternRepository = patternRepository;
        this.memberRepository = memberRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "PatternRead";
    }

    @Override
    public String getDescription() {
        return "STEP 1 of the attribution-curator pipeline. Given a patternId, "
                + "returns the V1 t_session_pattern metadata (signature, outcome, "
                + "suspectSurface, topFailingTool, agentId, memberCount, "
                + "firstSeenAt, lastSeenAt) plus the most-recently-added "
                + "member session ids (cap memberLimit, default "
                + DEFAULT_MEMBER_LIMIT + ", clamped to [" + MIN_MEMBER_LIMIT
                + ", " + MAX_MEMBER_LIMIT + "]).";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("patternId", Map.of(
                "type", "integer",
                "description", "Required t_session_pattern.id."));
        properties.put("memberLimit", Map.of(
                "type", "integer",
                "description", "Optional cap on returned member session ids "
                        + "(default " + DEFAULT_MEMBER_LIMIT + ", clamped to ["
                        + MIN_MEMBER_LIMIT + ", " + MAX_MEMBER_LIMIT + "])."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("patternId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required (patternId at minimum)");
            }
            Long patternId = SkillInputUtils.toLong(input.get("patternId"));
            if (patternId == null) {
                return SkillResult.validationError("patternId must be a positive integer");
            }
            int memberLimit = SkillInputUtils.toInt(input.get("memberLimit"), DEFAULT_MEMBER_LIMIT);
            if (memberLimit < MIN_MEMBER_LIMIT) memberLimit = MIN_MEMBER_LIMIT;
            if (memberLimit > MAX_MEMBER_LIMIT) memberLimit = MAX_MEMBER_LIMIT;

            Optional<SessionPatternEntity> opt = patternRepository.findById(patternId);
            if (opt.isEmpty()) {
                return SkillResult.validationError("pattern not found: " + patternId);
            }
            SessionPatternEntity p = opt.get();

            List<PatternSessionMemberEntity> members = memberRepository
                    .findByPatternIdOrderByAddedAtDesc(patternId, PageRequest.of(0, memberLimit));
            List<String> memberSessionIds = new ArrayList<>(members.size());
            for (PatternSessionMemberEntity m : members) {
                memberSessionIds.add(m.getSessionId());
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("patternId", p.getId());
            response.put("signature", p.getSignature());
            response.put("outcome", p.getOutcome());
            response.put("suspectSurface", p.getSuspectSurface());
            response.put("topFailingTool", p.getTopFailingTool());
            response.put("agentId", p.getAgentId());
            response.put("memberCount", p.getMemberCount());
            response.put("firstSeenAt", p.getFirstSeenAt() == null ? null : p.getFirstSeenAt().toString());
            response.put("lastSeenAt", p.getLastSeenAt() == null ? null : p.getLastSeenAt().toString());
            response.put("memberSessionIds", memberSessionIds);
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.error("PatternRead execute failed", e);
            return SkillResult.error("PatternRead error: " + e.getMessage());
        }
    }
}
