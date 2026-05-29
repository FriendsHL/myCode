package com.skillforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.SessionService;
import com.skillforge.workflow.exception.WorkflowNotFoundException;
import com.skillforge.workflow.exception.WorkflowAlreadyRunningException;
import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import com.skillforge.workflow.ws.WorkflowWsBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Task G: the workflow run orchestrator. Public {@link #startRun} acquires the
 * per-name lock, opens a {@code t_flywheel_run} row (loop_kind=workflow) + a
 * depth-0 anchor session, then dispatches the JS body to the dedicated workflow
 * executor and returns the run id immediately (async).
 *
 * <p>The async body: build a {@link WorkflowContext} (budget + args) → create the
 * per-run {@link WorkflowAgentInvoker} → {@link WorkflowEvaluator#evaluate} (await
 * stripping + IIFE wrap + L1 sandbox) → {@code markCompleted} / {@code markError}
 * → always {@code release} the lock.
 *
 * <p>Pool separation (plan §6): the JS body runs on {@code workflowExecutor}; the
 * blocking {@code agent()} {@code engine.run} calls offloaded by
 * {@code HostParallel} run on {@code workflowSubAgentExecutor}. Separate pools
 * avoid nested-pool deadlock (the workflow thread barrier-joins on sub-agent
 * futures).
 */
@Service
public class WorkflowRunnerService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunnerService.class);

    /** loop_kind for workflow runs (V126 allow-listed it on t_flywheel_run). */
    static final String LOOP_KIND_WORKFLOW = "workflow";

    private final WorkflowDefinitionRegistry registry;
    private final FlywheelRunService flywheelRunService;
    private final SessionService sessionService;
    private final AgentRepository agentRepository;
    private final WorkflowAgentInvokerFactory invokerFactory;
    private final ConsolidationLock consolidationLock;
    private final WorkflowWsBroadcaster wsBroadcaster;
    private final ObjectMapper objectMapper;
    private final ExecutorService workflowExecutor;
    private final ExecutorService workflowSubAgentExecutor;

    private final L1SandboxFactory sandboxFactory = new L1SandboxFactory();
    private final WorkflowEvaluator evaluator = new WorkflowEvaluator(sandboxFactory);

    /**
     * Name of the agent the workflow run + anchor session are attributed to.
     * Sprint-1: a seeded system agent (FK to t_agent is required by
     * t_flywheel_run.agent_id). Configurable so deployments / tests can override.
     */
    private final String anchorAgentName;

    public WorkflowRunnerService(WorkflowDefinitionRegistry registry,
                                 FlywheelRunService flywheelRunService,
                                 SessionService sessionService,
                                 AgentRepository agentRepository,
                                 WorkflowAgentInvokerFactory invokerFactory,
                                 ConsolidationLock consolidationLock,
                                 WorkflowWsBroadcaster wsBroadcaster,
                                 ObjectMapper objectMapper,
                                 @Qualifier("workflowExecutor") ExecutorService workflowExecutor,
                                 @Qualifier("workflowSubAgentExecutor") ExecutorService workflowSubAgentExecutor,
                                 @Value("${skillforge.workflow.anchor-agent-name:session-annotator}")
                                 String anchorAgentName) {
        this.registry = registry;
        this.flywheelRunService = flywheelRunService;
        this.sessionService = sessionService;
        this.agentRepository = agentRepository;
        this.invokerFactory = invokerFactory;
        this.consolidationLock = consolidationLock;
        this.wsBroadcaster = wsBroadcaster;
        this.objectMapper = objectMapper;
        this.workflowExecutor = workflowExecutor;
        this.workflowSubAgentExecutor = workflowSubAgentExecutor;
        this.anchorAgentName = anchorAgentName;
    }

    /**
     * Starts a workflow run.
     *
     * @return the {@code t_flywheel_run} id
     * @throws WorkflowNotFoundException       no definition registered under the name
     * @throws WorkflowAlreadyRunningException a run for this name is already in flight
     */
    public String startRun(String workflowName, Map<String, Object> args, Long userId) {
        WorkflowDefinition def = registry.findByName(workflowName)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowName));

        if (!consolidationLock.tryAcquire(workflowName)) {
            throw new WorkflowAlreadyRunningException(workflowName);
        }

        // runId tracked outside the try so the catch can mark a created-but-never-
        // dispatched run as errored (e.g. the executor rejects the body).
        String runId = null;
        try {
            AgentEntity anchorAgent = agentRepository.findFirstByName(anchorAgentName)
                    .orElseThrow(() -> new IllegalStateException(
                            "Workflow anchor agent not found: " + anchorAgentName));

            Map<String, Object> inputJson = new LinkedHashMap<>();
            inputJson.put("workflow_name", workflowName);
            inputJson.put("workflow_args", args == null ? Map.of() : args);
            inputJson.put("sourceHash", def.sourceHash());

            FlywheelRunEntity run = flywheelRunService.startRun(
                    LOOP_KIND_WORKFLOW,
                    FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL,
                    inputJson,
                    anchorAgent.getId(),
                    1);
            runId = run.getId();

            // Depth-0 anchor session: parent for every agent() worker sub-session.
            SessionEntity anchor = sessionService.createSession(userId, anchorAgent.getId());
            flywheelRunService.attachGeneratorSession(runId, anchor.getId());

            // execute() is inside the try: a RejectedExecutionException (queue full /
            // pool shut down) must release the lock and mark the run errored,
            // otherwise the lock leaks and the run is stuck 'running' forever.
            final String fRunId = runId;
            final SessionEntity fAnchor = anchor;
            final Map<String, Object> fArgs = args;
            workflowExecutor.execute(() -> runWorkflowBody(fRunId, def, fArgs, userId, fAnchor, workflowName));
            return runId;
        } catch (RuntimeException e) {
            consolidationLock.release(workflowName);
            if (runId != null) {
                try {
                    flywheelRunService.markError(runId, "workflow dispatch failed: " + e.getMessage());
                } catch (RuntimeException markEx) {
                    log.error("WorkflowRunnerService: failed to mark run {} errored after dispatch failure: {}",
                            runId, markEx.getMessage());
                }
            }
            throw e;
        }
    }

    /**
     * The async workflow body. Package-private so an IT can drive it
     * synchronously without the executor when asserting DB state.
     */
    void runWorkflowBody(String runId, WorkflowDefinition def, Map<String, Object> args,
                         Long userId, SessionEntity anchor, String workflowName) {
        try {
            BudgetTracker budget = new BudgetTracker(
                    BudgetTracker.DEFAULT_INSTRUCTION_CAP,
                    BudgetTracker.DEFAULT_AGENT_CALL_CAP,
                    System.nanoTime(),
                    BudgetTracker.DEFAULT_TIMEOUT_NANOS);
            WorkflowContext ctx = new WorkflowContext(runId, args, budget);
            ctx.setBroadcaster(wsBroadcaster);
            WorkflowAgentInvoker invoker = invokerFactory.create(runId, anchor, userId);

            Object result = evaluator.evaluate(def.jsSource(), ctx, invoker, workflowSubAgentExecutor);

            String summaryJson = serializeResult(result);
            flywheelRunService.markCompleted(runId, null, summaryJson);
            log.info("WorkflowRunnerService: run {} ('{}') completed", runId, workflowName);
        } catch (Exception e) {
            log.warn("WorkflowRunnerService: run {} ('{}') errored: {}", runId, workflowName, e.getMessage(), e);
            try {
                flywheelRunService.markError(runId, e.getMessage());
            } catch (RuntimeException markEx) {
                log.error("WorkflowRunnerService: failed to mark run {} errored: {}", runId, markEx.getMessage());
            }
        } finally {
            consolidationLock.release(workflowName);
        }
    }

    private String serializeResult(Object result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("result", String.valueOf(result));
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            return "{}";
        }
    }
}
