package com.skillforge.server.controller;

import com.skillforge.server.improve.SkillDraftScheduledExtractor;
import com.skillforge.server.improve.SkillScheduledEvaluator;
import com.skillforge.server.improve.SkillSelfImproveLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Temporary admin endpoints for manually triggering the 3 SKILL-EVOLVE-LOOP cron
 * components without waiting for the daily / weekly schedule. Useful for E2E
 * testing the closed loop in dev. Each endpoint runs the same code path the
 * cron would, including yaml gate guard.
 *
 * <p>SKILL-EVOLVE-LOOP follow-up: replace with proper admin auth + UI buttons.
 */
@RestController
@RequestMapping("/api/admin/skill-evolve-loop")
public class AdminSkillEvolveLoopController {

    private static final Logger log = LoggerFactory.getLogger(AdminSkillEvolveLoopController.class);

    private final SkillDraftScheduledExtractor draftExtractor;
    private final SkillScheduledEvaluator scheduledEvaluator;
    private final SkillSelfImproveLoop selfImproveLoop;

    public AdminSkillEvolveLoopController(SkillDraftScheduledExtractor draftExtractor,
                                          SkillScheduledEvaluator scheduledEvaluator,
                                          SkillSelfImproveLoop selfImproveLoop) {
        this.draftExtractor = draftExtractor;
        this.scheduledEvaluator = scheduledEvaluator;
        this.selfImproveLoop = selfImproveLoop;
    }

    @PostMapping("/extract/run-once")
    public ResponseEntity<?> triggerExtract() {
        log.info("Admin manual trigger: SkillDraftScheduledExtractor.runOnce()");
        try {
            draftExtractor.runOnce();
            return ResponseEntity.ok(Map.of("ok", true, "ran", "draft-extract", "at", Instant.now().toString()));
        } catch (Exception e) {
            log.error("Admin trigger draft-extract failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/evaluate/run-once")
    public ResponseEntity<?> triggerEvaluate() {
        log.info("Admin manual trigger: SkillScheduledEvaluator.runOnce()");
        try {
            scheduledEvaluator.runOnce();
            return ResponseEntity.ok(Map.of("ok", true, "ran", "scheduled-evaluate", "at", Instant.now().toString()));
        } catch (Exception e) {
            log.error("Admin trigger scheduled-evaluate failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/self-improve/run-once")
    public ResponseEntity<?> triggerSelfImprove() {
        log.info("Admin manual trigger: SkillSelfImproveLoop.runOnce()");
        try {
            selfImproveLoop.runOnce();
            return ResponseEntity.ok(Map.of("ok", true, "ran", "self-improve", "at", Instant.now().toString()));
        } catch (Exception e) {
            log.error("Admin trigger self-improve failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }
}
