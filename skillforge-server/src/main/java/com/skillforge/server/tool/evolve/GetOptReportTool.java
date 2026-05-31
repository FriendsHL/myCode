package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.optreport.dto.OptReportIssueDto;
import com.skillforge.server.optreport.dto.OptReportSummaryJson;
import com.skillforge.server.optreport.dto.OptReportSummaryParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C — agent-callable READ tool that returns the
 * {@code topIssues} of a completed opt-report so the evolve orchestrator can
 * drive its iteration loop.
 *
 * <p><b>Why a read tool exists.</b> {@code RunWorkflow('opt-report')} starts the
 * report ASYNC and returns only a {@code runId} (= the report id); it does not
 * return the finished summary inline. The orchestrator therefore reads the
 * report back through this tool once it is {@code completed}. In the focused-loop
 * e2e the orchestrator is handed a pre-existing {@code reportId} directly.
 *
 * <p><b>Thin / reuse.</b> No new persistence and no re-implementation: the report
 * is a {@link FlywheelRunEntity} (loop_kind={@code opt_report}) and parsing reuses
 * the EXISTING {@link OptReportSummaryParser} — the same validated parse the
 * {@code OptReportController} read endpoints use. Output is shaped so each issue
 * is directly threadable into {@code GenerateCandidate} (reportId + issueId +
 * surface).
 *
 * <p><b>Access pin.</b> The optional {@code expectedAgentId} lets the orchestrator
 * pin the report to the agent it is evolving — a mismatch is a clean validation
 * error so an orchestrator evolving agent A cannot drive its loop off agent B's
 * report.
 *
 * <p><b>Recursion guard (invariant).</b> Registered ONLY in the main
 * {@code SkillRegistry} (see {@code SkillForgeConfig}); deliberately ABSENT from
 * {@code WorkflowSkillRegistryFactory} (the workflow sub-agent registry) — same
 * isolation invariant as the Module A/B/C tools. The orchestrator runs top-level.
 */
public class GetOptReportTool implements Tool {

    public static final String NAME = "GetOptReport";

    private static final Logger log = LoggerFactory.getLogger(GetOptReportTool.class);

    private final FlywheelRunRepository runRepository;
    private final OptReportSummaryParser summaryParser;
    private final ObjectMapper objectMapper;

    public GetOptReportTool(FlywheelRunRepository runRepository,
                            OptReportSummaryParser summaryParser,
                            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.summaryParser = summaryParser;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Read a completed opt-report's topIssues so you can drive the evolve loop. "
                + "Inputs:\n"
                + "- \"reportId\": the opt-report id (the runId returned by "
                + "RunWorkflow('opt-report'), or a pre-existing report id).\n"
                + "- \"expectedAgentId\" (required): the agent you are evolving; the report "
                + "must belong to it (else a validation error). Always pass your targetAgentId.\n"
                + "Returns { reportId, agentId, status, issueCount, topIssues: [{ id, title, "
                + "severity, surface, suspectSurface, fixSurface, convertible, sessionCount, "
                + "exampleSessionIds, suggestion, actionType }] }. Pass each issue's reportId + "
                + "id (issueId) + surface to GenerateCandidate. Only issues with "
                + "convertible=true can be turned into a candidate (surface other/unclear cannot).";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reportId", Map.of(
                "type", "string",
                "description", "The opt-report id (runId from RunWorkflow('opt-report'))."
        ));
        properties.put("expectedAgentId", Map.of(
                "type", "string",
                "description", "The agent being evolved; report must belong to it (your targetAgentId)."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("reportId", "expectedAgentId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required (reportId)");
            }
            String reportId = trimToNull(input.get("reportId"));
            if (reportId == null) {
                return SkillResult.validationError("reportId is required");
            }

            FlywheelRunEntity report = runRepository.findById(reportId).orElse(null);
            if (report == null) {
                return SkillResult.validationError("opt-report not found: " + reportId);
            }
            if (!FlywheelRunEntity.LOOP_KIND_OPT_REPORT.equals(report.getLoopKind())) {
                return SkillResult.validationError(
                        "run " + reportId + " is not an opt-report (loop_kind="
                                + report.getLoopKind() + ")");
            }

            // expectedAgentId is REQUIRED + server-enforced (not just schema-advertised):
            // reading a report by id alone would leak another agent's issue titles +
            // exampleSessionIds. Enforcing here — rather than trusting the LLM to pass
            // it — mirrors the FR-C7 budget-cap hardening (don't rely on prompt/schema
            // for an access gate). Pin the read to the agent being evolved.
            Long expectedAgentId;
            try {
                expectedAgentId = parseLong(input.get("expectedAgentId"));
            } catch (IllegalArgumentException e) {
                return SkillResult.validationError(e.getMessage());
            }
            if (expectedAgentId == null) {
                return SkillResult.validationError(
                        "expectedAgentId is required (the agent you are evolving); the report "
                                + "read is pinned to it to prevent cross-agent issue disclosure");
            }
            if (!expectedAgentId.equals(report.getAgentId())) {
                return SkillResult.validationError(
                        "report " + reportId + " belongs to agent " + report.getAgentId()
                                + " but expectedAgentId=" + expectedAgentId);
            }

            if (!FlywheelRunEntity.STATUS_COMPLETED.equals(report.getStatus())) {
                // Not terminal-success yet — the orchestrator should poll/wait
                // (or the report failed). Surface as a non-validation error so the
                // agent reasons about retry/wait rather than "fix your input".
                return SkillResult.error(
                        "opt-report " + reportId + " is not completed yet (status="
                                + report.getStatus() + "); wait for it to finish before reading issues");
            }

            String summaryJson = report.getSummaryJson();
            if (summaryJson == null || summaryJson.isBlank()) {
                return SkillResult.error(
                        "opt-report " + reportId + " is completed but has no summary; "
                                + "it may have found no candidate sessions");
            }

            OptReportSummaryJson summary = summaryParser.parse(summaryJson);
            List<Map<String, Object>> issues = new ArrayList<>();
            for (OptReportIssueDto issue : summary.topIssues()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", issue.id());
                m.put("title", issue.title());
                m.put("severity", issue.severity());
                m.put("surface", issue.effectiveSurface());
                m.put("suspectSurface", issue.suspectSurface());
                m.put("fixSurface", issue.fixSurface());
                m.put("convertible",
                        OptReportIssueDto.CONVERTIBLE_SURFACES.contains(issue.effectiveSurface()));
                m.put("sessionCount", issue.sessionCount());
                m.put("exampleSessionIds", issue.exampleSessionIds());
                m.put("suggestion", issue.suggestion());
                m.put("actionType", issue.actionType());
                issues.add(m);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("reportId", reportId);
            response.put("agentId", report.getAgentId());
            response.put("status", report.getStatus());
            response.put("issueCount", issues.size());
            response.put("topIssues", issues);

            log.info("[GetOptReport] reportId={} agentId={} issueCount={}",
                    reportId, report.getAgentId(), issues.size());
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (IllegalArgumentException e) {
            // summaryParser rejected a schema-invalid summary_json.
            return SkillResult.error("opt-report summary is not parseable: " + e.getMessage());
        } catch (Exception e) {
            log.error("GetOptReport execute failed", e);
            return SkillResult.error("GetOptReport error: " + e.getMessage());
        }
    }

    private static Long parseLong(Object value) {
        String s = trimToNull(value);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a numeric id but got: " + s);
        }
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
