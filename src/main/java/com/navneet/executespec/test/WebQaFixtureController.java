package com.navneet.executespec.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.testgen.model.TestCategory;
import com.navneet.executespec.testgen.model.TestPlan;
import com.navneet.executespec.testgen.model.TestPlanStatus;
import com.navneet.executespec.testgen.model.TestResult;
import com.navneet.executespec.testgen.model.TestResultStatus;
import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.model.TestTaskStatus;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** Deterministic, provider-free fixtures for authenticated Web release captures. */
@Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v2/test/web-fixture")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class WebQaFixtureController {

  private static final String PREFIX = "web-qa-";

  private final PipelineRunRepository runRepository;
  private final AgentTaskRepository taskRepository;
  private final ValidationReportRepository reportRepository;
  private final TestPlanRepository testPlanRepository;
  private final TestTaskRepository testTaskRepository;
  private final TestResultRepository testResultRepository;
  private final AgentSessionRepository sessionRepository;
  private final AgentTurnRepository turnRepository;
  private final AgentActionEventRepository eventRepository;
  private final SseBroadcaster sseBroadcaster;

  @PostMapping
  public FixtureResponse seed(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          AppUserDetails principal)
      throws IOException {
    String userId = principal.getUser().getId();
    String orgId = principal.getUser().getOrgId();
    String sessionId = PREFIX + UUID.randomUUID();
    AgentSession session =
        sessionRepository.save(
            AgentSession.builder()
                .id(sessionId)
                .orgId(orgId)
                .userId(userId)
                .clientSurface("WEB")
                .title("Release QA agent evidence")
                .status("ACTIVE")
                .defaultExecutionMode("REMOTE")
                .approvalPolicy("MANUAL")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    AgentTurn turn =
        turnRepository.save(
            AgentTurn.builder()
                .id(PREFIX + "turn-" + UUID.randomUUID())
                .sessionId(sessionId)
                .orgId(orgId)
                .userId(userId)
                .sequence(1)
                .userMessage("Implement the release QA fixture")
                .assistantSummary("Fixture action is ready for approval.")
                .intentType("CODE_RUN_REQUIRED")
                .intentConfidence(1.0)
                .actionType("create_cloud_run")
                .proposedActionJson(
                    "{\"action\":\"create_cloud_run\",\"requiresConfirmation\":true}")
                .status("OK")
                .build());
    eventRepository.save(
        AgentActionEvent.builder()
            .id(PREFIX + "event-" + UUID.randomUUID())
            .sessionId(sessionId)
            .turnId(turn.getId())
            .actionId(PREFIX + "action")
            .type("GOAL_DECISION")
            .payloadJson("{\"status\":\"READY\",\"summary\":\"Fixture evidence is ready\"}")
            .createdAt(Instant.now())
            .build());

    PipelineRun success =
        createRun(userId, orgId, "COMPLETE", "CLEAN_COMPLETION", "Release QA success", true);
    PipelineRun failed =
        createRun(userId, orgId, "FAILED", "FINAL_BUILD_FAILED", "Release QA recovery", false);
    PipelineRun gate =
        createRun(
            userId,
            orgId,
            "AWAITING_APPROVAL",
            "NO_REVIEW_RESULT",
            "Release QA approval gate",
            true);
    gate.setCompletedAt(null);
    gate.setCurrentGate("PLAN_APPROVAL");
    runRepository.save(gate);
    turn.setLinkedRunId(gate.getRunId());
    turnRepository.save(turn);
    return new FixtureResponse(success.getRunId(), failed.getRunId(), gate.getRunId(), sessionId);
  }

  /**
   * Advance only a fixture-owned plan-gate row for provider-free screen captures. EXECUTING maps to
   * the V2 pipeline's persisted WORKING state; no worker, provider, quota, PR, or background job is
   * started. The endpoint is intentionally local/dev + SUPER_ADMIN only with a strict prefix and
   * transition order so it cannot mutate customer runs.
   */
  @PostMapping("/{runId}/advance")
  public AdvanceResponse advance(
      @PathVariable String runId,
      @RequestBody AdvanceRequest request,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          AppUserDetails principal) {
    if (runId == null || !runId.startsWith(PREFIX)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "fixture run not found");
    }
    String requested = request == null || request.status() == null ? "" : request.status().trim();
    if (!List.of("EXECUTING", "COMPLETE").contains(requested)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "status must be EXECUTING or COMPLETE");
    }
    PipelineRun run =
        runRepository
            .findById(runId)
            .filter(candidate -> runId.equals(candidate.getRunId()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "fixture run not found"));
    if (!java.util.Objects.equals(run.getCreatedByUserId(), principal.getUser().getId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "fixture run not found");
    }
    String current = run.getStatus();
    if ("EXECUTING".equals(requested) && !"AWAITING_APPROVAL".equals(current)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "fixture run is not awaiting approval");
    }
    if ("COMPLETE".equals(requested) && !"WORKING".equals(current)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "fixture run is not executing");
    }
    if ("EXECUTING".equals(requested)) {
      run.setStatus("WORKING");
      run.setCurrentGate(null);
      run.setCompletedAt(null);
      run.setStatusReason("CLEAN_COMPLETION");
      advanceTasks(runId, false);
    } else {
      run.setStatus("COMPLETE");
      run.setCompletedAt(Instant.now());
      run.setStatusReason("CLEAN_COMPLETION");
      advanceTasks(runId, true);
    }
    runRepository.save(run);
    sseBroadcaster.emit(runId, RunEvent.runStatus(runId, run.getStatus()));
    sseBroadcaster.emit(runId, RunEvent.log(runId, null, "fixture advanced to " + requested));
    return new AdvanceResponse(runId, requested, run.getStatus(), run.getStatusReason());
  }

  private void advanceTasks(String runId, boolean complete) {
    List<AgentTask> tasks = taskRepository.findByRunId(runId);
    if (tasks == null) return;
    Instant now = Instant.now();
    for (AgentTask task : tasks) {
      task.setStatus(complete ? "COMPLETE" : "WORKING");
      if (!complete) task.setStartedAt(now);
      if (complete) task.setCompletedAt(now);
      taskRepository.save(task);
    }
  }

  @DeleteMapping
  // Keep run/workspace and agent-session graph deletion atomic. Without a transaction, a stale
  // fixture row can fail midway through cleanup and leave ACTIVE web-qa sessions behind.
  @Transactional
  public Map<String, Object> cleanup(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          AppUserDetails principal)
      throws IOException {
    String userId = principal.getUser().getId();
    List<PipelineRun> runs =
        runRepository
            .findByCreatedByUserIdOrderByStartedAtDesc(
                userId, org.springframework.data.domain.PageRequest.of(0, 50))
            .getContent()
            .stream()
            .filter(r -> r.getRunId().startsWith(PREFIX))
            .toList();
    for (PipelineRun run : runs) {
      taskRepository.deleteAll(taskRepository.findByRunId(run.getRunId()));
      reportRepository.findByRunId(run.getRunId()).ifPresent(reportRepository::delete);
      testPlanRepository
          .findByRunId(run.getRunId())
          .ifPresent(
              plan -> {
                testResultRepository.deleteAll(testResultRepository.findByTestPlanId(plan.getId()));
                testTaskRepository.deleteAll(testTaskRepository.findByTestPlanId(plan.getId()));
                testPlanRepository.delete(plan);
              });
      deleteWorkspace(run.getWorkspacePath());
      runRepository.delete(run);
    }
    List<AgentSession> sessions = sessionRepository.findByUserIdAndIdStartingWith(userId, PREFIX);
    for (AgentSession session : sessions) {
      eventRepository.deleteBySessionId(session.getId());
      turnRepository.deleteBySessionId(session.getId());
      sessionRepository.delete(session);
    }
    return Map.of("deletedRuns", runs.size(), "deletedAgentSessions", sessions.size());
  }

  // Package-visible for the regression test that ensures visual fixtures remain score-neutral.
  PipelineRun createRun(
      String userId, String orgId, String status, String reason, String title, boolean success)
      throws IOException {
    String runId = PREFIX + UUID.randomUUID();
    // Keep the fixture workspace on the same canonical <agent.workspace>/<runId> layout used by
    // WorkspaceFileService; otherwise the authenticated Code tab cannot show the fixture diff.
    Path workspace = Path.of("workspace", runId).toAbsolutePath();
    Files.createDirectories(workspace.resolve("src"));
    Files.writeString(
        workspace.resolve("README.md"), "# Release QA fixture\n", StandardCharsets.UTF_8);
    Files.writeString(
        workspace.resolve("src/app.ts"),
        success ? "export const status = 'complete';\n" : "export const status = 'failed';\n",
        StandardCharsets.UTF_8);
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .userRequest(title)
            .status(status)
            .statusReason(reason)
            .harnessVersion("v2")
            .runMode("BUILD")
            .createdByUserId(userId)
            .orgId(orgId)
            .startedAt(Instant.now().minusSeconds(30))
            .completedAt(Instant.now())
            .workspacePath(workspace.toString())
            .runTitle(title)
            .runSummary("Deterministic release evidence fixture")
            .planJson("{\"tasks\":[{\"id\":\"fixture-task\",\"title\":\"Validate fixture\"}]}")
            .reviewJson(success ? "{\"verdict\":\"CLEAN\"}" : "{\"verdict\":\"UNRESOLVED_ISSUES\"}")
            .totalInputTokens(120)
            .totalOutputTokens(60)
            .actualCostUsd(0.0)
            .plannerModel("fixture")
            .workerModel("fixture")
            .reviewerModel("fixture")
            .planStrategy("SINGLE_SHOT")
            .build();
    runRepository.save(run);
    taskRepository.save(
        AgentTask.builder()
            .taskId(runId + "-task")
            .runId(runId)
            .title("Validate fixture")
            .complexity("LOW")
            .status(success ? "COMPLETE" : "FAILED")
            .taskJson("{\"id\":\"fixture-task\"}")
            .completionNotes(success ? "Verified" : "Build failed")
            .provider("fixture")
            .model("fixture")
            .startedAt(run.getStartedAt())
            .completedAt(run.getCompletedAt())
            .build());
    reportRepository.save(
        ValidationReport.builder()
            .id(runId + "-report")
            .runId(runId)
            .filesGenerated(1)
            .languages("TypeScript")
            .pipelineResult(success ? PipelineResult.PASSED : PipelineResult.FAILED)
            .issuesJson(
                success ? "[]" : "[{\"severity\":\"CRITICAL\",\"message\":\"Build failed\"}]")
            .checksPassed(success ? "build,tests,review" : "none")
            .reportMarkdown(success ? "# Verified fixture\n" : "# Failed fixture\n")
            // This fixture exists only to exercise authenticated Web states. It does not run the
            // production validation/scoring pipeline, so it must not publish a synthetic headline
            // score that could be mistaken for score-validation evidence. The report deliberately
            // leaves verifiedScore null; capture tooling should validate the rendered states and
            // evidence fields, while score correctness is covered by ValidationReportService tests
            // and real runs.
            .finalBuildPassed(success)
            .testsPassed(success ? 2 : 0)
            .testsTotal(2)
            .reviewerVerdict(success ? "CLEAN" : "UNRESOLVED_ISSUES")
            .status("COMPLETE")
            .build());
    TestPlan plan =
        testPlanRepository.save(
            TestPlan.builder()
                .runId(runId)
                .status(success ? TestPlanStatus.COMPLETED : TestPlanStatus.FAILED)
                .planJson("{\"tests\":[\"fixture happy path\",\"fixture failure path\"]}")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    TestTask test =
        testTaskRepository.save(
            TestTask.builder()
                .testPlanId(plan.getId())
                .taskName("fixture smoke")
                .taskDescription("Deterministic release fixture test")
                .testCategory(TestCategory.VALIDATION)
                .status(success ? TestTaskStatus.COMPILED : TestTaskStatus.FAILED)
                .generatedCode("fixture")
                .build());
    testResultRepository.save(
        TestResult.builder()
            .testPlanId(plan.getId())
            .testTaskId(test.getId())
            .testMethod("fixture smoke")
            .status(success ? TestResultStatus.PASSED : TestResultStatus.FAILED)
            .expectedValue("pass")
            .actualValue(success ? "pass" : "build error")
            .failureMessage(success ? null : "fixture build failed")
            .durationMs(12)
            .build());
    sseBroadcaster.emit(runId, RunEvent.runStatus(runId, status));
    sseBroadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            runId + "-task",
            success ? "fixture verification passed" : "fixture build failed; recovery available"));
    sseBroadcaster.emit(
        runId,
        new RunEvent(
            "AWAITING_APPROVAL".equals(status) ? "GATE_OPENED" : "COMPLETE",
            runId,
            null,
            status,
            Instant.now()));
    return run;
  }

  private void deleteWorkspace(String value) throws IOException {
    if (value == null) return;
    Path path = Path.of(value).toAbsolutePath();
    if (!isFixtureWorkspace(path)) return;
    if (Files.exists(path))
      try (var stream = Files.walk(path)) {
        stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
  }

  // Fixture workspaces are siblings named web-qa-<uuid>, not children of a web-qa directory.
  // Keep cleanup confined to the canonical workspace root and the fixture prefix.
  boolean isFixtureWorkspace(Path path) {
    Path root = Path.of("workspace").toAbsolutePath().normalize();
    Path normalized = path.toAbsolutePath().normalize();
    Path name = normalized.getFileName();
    return name != null
        && name.toString().startsWith(PREFIX)
        && normalized.getParent() != null
        && normalized.getParent().equals(root);
  }

  public record FixtureResponse(
      String successRunId, String failedRunId, String gateRunId, String agentSessionId) {}

  public record AdvanceRequest(String status) {}

  public record AdvanceResponse(
      String runId, String requestedStatus, String persistedStatus, String statusReason) {}
}
