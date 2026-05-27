package com.skillforge.server.tool.memorycontext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.memory.context.MemoryContextProvider;
import com.skillforge.server.memory.context.MemoryContextSnapshot;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ListRelevantMemoriesTool implements Tool {

    public static final String NAME = "ListRelevantMemories";
    private static final Logger log = LoggerFactory.getLogger(ListRelevantMemoriesTool.class);

    private final MemoryContextProvider provider;
    private final ObjectMapper objectMapper;

    public ListRelevantMemoriesTool(MemoryContextProvider provider, ObjectMapper objectMapper) {
        this.provider = provider;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Read-only. Return relevant active memories for a user and task context, "
                + "including memoryIds and a stable contextHash for audit.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("userId", Map.of(
                "type", "integer",
                "description", "Required. User id whose relevant active memories to list."
        ));
        properties.put("taskContext", Map.of(
                "type", "string",
                "description", "Required. Query, pattern summary, or proposed change context."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("userId", "taskContext"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                return SkillResult.validationError("input is required");
            }
            Long userId = SkillInputUtils.toLong(input.get("userId"));
            if (userId == null || userId <= 0) {
                return SkillResult.validationError("userId is required and must be a positive integer");
            }
            Object taskContextValue = input.get("taskContext");
            String taskContext = taskContextValue == null ? null : String.valueOf(taskContextValue);
            if (taskContext == null || taskContext.isBlank()) {
                return SkillResult.validationError("taskContext is required and must be a non-blank string");
            }

            MemoryContextSnapshot snapshot = provider.load(userId, taskContext);
            return SkillResult.success(objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            log.error("ListRelevantMemoriesTool execute failed", e);
            return SkillResult.error("ListRelevantMemories failed; see server logs");
        }
    }
}
