package com.skillforge.server.sessionlabel;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PROD-LABEL-CLUSTER V1 — Phase 1.0 red test (signal stage).
 *
 * <p>Spec reference: {@code docs/requirements/active/PROD-LABEL-CLUSTER/tech-design.md}
 * §7 实施计划, Phase 1.0 第 3 行 ("写红测试：fake session + tool error trace → 跑
 * signal stage 期望 tool_failure 标签出现"). Phase 1.0 deliberately produces no
 * production code; the test stays red until Phase 1.2 implements
 * {@code SignalAnnotationJob}.
 *
 * <p>Methodology: see {@code .claude/rules/verification-before-completion.md}
 * Red-Green-Refactor — write a failing test first, then implement to green,
 * then refactor. This file lives in the {@code com.skillforge.server.sessionlabel}
 * package which is the predetermined home for V1 code (Phase 1.1+ entities,
 * Phase 1.2+ jobs, Phase 1.4 controller). Creating only the test sub-package
 * keeps Phase 1.0 scope discipline ({@code think-before-coding.md} rule 6):
 * no production package yet, no entity, no service, no migration.
 *
 * <p>{@code @Disabled} choice over compile-fail:
 * <ul>
 *   <li>Compile-fail (referencing {@code SignalAnnotationJob.class} symbolically)
 *       would block {@code mvn -pl skillforge-server -am test} entirely until
 *       Phase 1.2 lands the class, gating any other dogfood / regression test
 *       runs in the meantime.</li>
 *   <li>{@code @Disabled} keeps the class compiling, the test discoverable by
 *       JUnit, and surfaces clearly in test reports as
 *       "1 skipped — Phase 1.0 placeholder". When Phase 1.2 implements the
 *       job, dev simply removes {@code @Disabled} + writes the Given/When/Then
 *       body; CI flips skip → fail (red) → pass (green).</li>
 * </ul>
 *
 * <p>This Phase 1.0 test stub is the <b>only</b> file the BE-Dev creates;
 * no production code, no entity, no migration, no repository, no service.
 * See tech-design.md §0.2 "不动的核心文件清单" + §7 Phase 1.0 boundaries.
 */
class SignalAnnotationJobRedTest {

    /**
     * Phase 1.0 red placeholder for the signal-stage end-to-end happy path.
     *
     * <p><b>Given</b> (Phase 1.2 test setup):
     * <ul>
     *   <li>One row in {@code t_session} (id="sess-1", runtime_status="idle",
     *       completed_at = now() - 30m, agent_id=42).</li>
     *   <li>One row in {@code t_llm_trace} (trace_id="trace-1",
     *       root_trace_id="trace-1", session_id="sess-1", agent_id=42,
     *       status="ok", origin="production").</li>
     *   <li>One row in {@code t_llm_span} (span_id="span-1",
     *       trace_id="trace-1", kind="tool", error="Tool execution failed").
     *       The {@code error} field being non-null is what
     *       {@code TraceScenarioImportService#isToolFailure} (line 290-292)
     *       uses to flag {@code tool_failure}.</li>
     * </ul>
     *
     * <p><b>When</b>:
     * <pre>
     *     signalAnnotationJob.runOnce("sess-1");
     * </pre>
     *
     * <p><b>Then</b>:
     * <ul>
     *   <li>Exactly one row appears in {@code t_session_annotation} with
     *       {@code session_id="sess-1"}, {@code annotation_type="tool_failure"},
     *       {@code annotation_value="true"}, {@code source="signal"},
     *       {@code confidence=1.00}.</li>
     *   <li>Re-running {@code runOnce("sess-1")} is a no-op (idempotent —
     *       the {@code (session_id, annotation_type, annotation_value, source)}
     *       UNIQUE constraint from V72 migration prevents duplicates).</li>
     * </ul>
     *
     * <p><b>Why this stays red until Phase 1.2</b>: the symbol
     * {@code SignalAnnotationJob} does not exist yet. Phase 1.1 will land
     * the entity + repository + migration; Phase 1.2 will land the job + cron
     * + flip this test green by deleting {@code @Disabled} and filling in the
     * Given/When/Then body.
     */
    @Test
    @Disabled("Phase 1.0 red test — implementation not yet written; will turn green at Phase 1.2 "
            + "(SignalAnnotationJob landing + t_session_annotation entity from Phase 1.1). "
            + "See tech-design.md §7 实施计划.")
    @DisplayName("signal stage writes tool_failure annotation when trace has tool error span")
    void signalStage_writesToolFailureAnnotation_whenTraceHasToolErrorSpan() {
        // Intentionally empty for Phase 1.0. Body filled in at Phase 1.2 once
        // SignalAnnotationJob + SessionAnnotationEntity + SessionAnnotationRepository
        // exist. See class-level Javadoc for the full Given/When/Then this test
        // is intended to encode.
        //
        // Phase 1.2 dev: remove @Disabled, autowire SignalAnnotationJob +
        // SessionAnnotationRepository (likely @SpringBootTest + @Transactional rollback,
        // or stub-mock the trace/span repositories the way TraceScenarioImportServiceTest
        // does at line 102-128).
    }
}
