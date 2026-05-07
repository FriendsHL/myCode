package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.dto.ScheduledTaskRequest;
import com.skillforge.server.dto.ScheduledTaskResponse;
import com.skillforge.server.dto.ScheduledTaskRunResponse;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.ScheduledTaskRunEntity;
import com.skillforge.server.service.ScheduledTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST surface for P12 scheduled tasks.
 *
 * <p>Ownership pattern matches the rest of the project (e.g. SkillDraftController,
 * SessionSpansController): {@code userId} is a required query parameter; service
 * layer compares it against {@code creator_user_id} and throws
 * {@code AccessDeniedException} → HTTP 403 on mismatch.
 *
 * <p>The request body uses {@code Map<String, Object>} on PUT specifically because
 * {@link ScheduledTaskRequest} carries tri-state "field present" flags that Jackson's
 * default deserializer cannot populate — we walk the map manually and call the
 * {@code setX} methods which set the {@code present} flag as a side-effect.
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduledTaskController {

    private final ScheduledTaskService scheduledTaskService;
    private final ObjectMapper objectMapper;

    public ScheduledTaskController(ScheduledTaskService scheduledTaskService,
                                   ObjectMapper objectMapper) {
        this.scheduledTaskService = scheduledTaskService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestParam Long userId,
                                    @RequestBody Map<String, Object> body) {
        try {
            ScheduledTaskRequest req = parseRequest(body);
            ScheduledTaskEntity created = scheduledTaskService.create(userId, req);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ScheduledTaskResponse.from(created, objectMapper));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<ScheduledTaskResponse>> list(@RequestParam Long userId) {
        List<ScheduledTaskResponse> tasks = scheduledTaskService.listForUser(userId).stream()
                .map(e -> ScheduledTaskResponse.from(e, objectMapper))
                .toList();
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScheduledTaskResponse> get(@PathVariable Long id,
                                                     @RequestParam Long userId) {
        ScheduledTaskEntity task = scheduledTaskService.get(id, userId);
        return ResponseEntity.ok(ScheduledTaskResponse.from(task, objectMapper));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestParam Long userId,
                                    @RequestBody Map<String, Object> body) {
        try {
            ScheduledTaskRequest req = parseRequest(body);
            ScheduledTaskEntity updated = scheduledTaskService.update(id, userId, req);
            return ResponseEntity.ok(ScheduledTaskResponse.from(updated, objectMapper));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestParam Long userId) {
        scheduledTaskService.delete(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/trigger")
    public ResponseEntity<?> trigger(@PathVariable Long id,
                                     @RequestParam Long userId) {
        ScheduledTaskEntity task = scheduledTaskService.triggerNow(id, userId);
        // 202 Accepted — execution is asynchronous via BE-2's executor.
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "taskId", task.getId(),
                "status", "trigger_requested"
        ));
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<List<ScheduledTaskRunResponse>> listRuns(
            @PathVariable Long id,
            @RequestParam Long userId,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {
        List<ScheduledTaskRunEntity> rows = scheduledTaskService.listRuns(id, userId, limit, offset);
        return ResponseEntity.ok(rows.stream().map(ScheduledTaskRunResponse::from).toList());
    }

    /**
     * Walk a parsed JSON object body and produce a {@link ScheduledTaskRequest} that
     * preserves "field-present" semantics. Jackson can't auto-populate the tri-state
     * flags, so we route through the setters which set them as a side-effect.
     */
    @SuppressWarnings("unchecked")
    private ScheduledTaskRequest parseRequest(Map<String, Object> body) {
        ScheduledTaskRequest req = new ScheduledTaskRequest();
        if (body == null) return req;

        if (body.containsKey("name")) req.setName(asString(body.get("name")));
        if (body.containsKey("agentId")) req.setAgentId(asLong(body.get("agentId")));
        if (body.containsKey("cronExpr")) req.setCronExpr(asString(body.get("cronExpr")));
        if (body.containsKey("oneShotAt")) {
            Object raw = body.get("oneShotAt");
            req.setOneShotAt(raw != null ? Instant.parse(raw.toString()) : null);
        }
        if (body.containsKey("timezone")) req.setTimezone(asString(body.get("timezone")));
        if (body.containsKey("promptTemplate")) req.setPromptTemplate(asString(body.get("promptTemplate")));
        if (body.containsKey("sessionMode")) req.setSessionMode(asString(body.get("sessionMode")));
        if (body.containsKey("channelTarget")) {
            Object raw = body.get("channelTarget");
            if (raw == null) {
                req.setChannelTarget(null);
            } else if (raw instanceof Map<?, ?> m) {
                req.setChannelTarget((Map<String, Object>) m);
            } else {
                throw new IllegalArgumentException(
                        "channelTarget must be an object {channelType, channelId} or null");
            }
        }
        if (body.containsKey("enabled")) {
            Object raw = body.get("enabled");
            req.setEnabled(raw == null ? null : Boolean.parseBoolean(raw.toString()));
        }
        return req;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}
