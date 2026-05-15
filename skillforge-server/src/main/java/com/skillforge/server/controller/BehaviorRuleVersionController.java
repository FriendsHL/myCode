package com.skillforge.server.controller;

import com.skillforge.server.controller.dto.BehaviorRuleVersionResponse;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * V4 Phase 1.4 — REST endpoints for {@link BehaviorRuleVersionEntity}.
 *
 * <p>Separated from {@link BehaviorRuleController} (which serves the static
 * built-in rule definitions registry) because version data is row-backed,
 * agent-scoped, and queried with different filters than the rule registry.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/behavior-rules/versions?agentId={id}&status={status}}
 *       — list versions for an agent (newest-first by versionNumber).
 *       {@code status} is optional; when present filters to the matching
 *       subset ({@code candidate} / {@code active} / {@code retired} /
 *       {@code rejected}).</li>
 *   <li>{@code GET /api/behavior-rules/versions/{id}} — single version by id.
 *       Returns 404 when not found.</li>
 * </ul>
 *
 * <p>Auth: follows the existing {@link BehaviorRuleController} pattern (no
 * explicit owner check in the controller — relies on global Spring Security
 * filter chain if configured). When auth becomes required, the natural place
 * is a {@code @PreAuthorize} on each method using the {@code agentId} query
 * param / path variable.
 */
@RestController
@RequestMapping("/api/behavior-rules/versions")
public class BehaviorRuleVersionController {

    private final BehaviorRuleVersionRepository versionRepository;

    public BehaviorRuleVersionController(BehaviorRuleVersionRepository versionRepository) {
        this.versionRepository = versionRepository;
    }

    @GetMapping
    public ResponseEntity<List<BehaviorRuleVersionResponse>> list(
            @RequestParam String agentId,
            @RequestParam(required = false) String status) {
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        List<BehaviorRuleVersionEntity> rows = (status == null || status.isBlank())
                ? versionRepository.findByAgentIdOrderByVersionNumberDesc(agentId)
                : versionRepository.findByAgentIdAndStatusOrderByVersionNumberDesc(agentId, status);
        List<BehaviorRuleVersionResponse> body = rows.stream()
                .map(BehaviorRuleVersionResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BehaviorRuleVersionResponse> getOne(@PathVariable String id) {
        return versionRepository.findById(id)
                .map(BehaviorRuleVersionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
