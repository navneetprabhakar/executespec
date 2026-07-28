package com.navneet.executespec.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.repository.GateRepository;
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
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** Deterministic, provider-free fixtures for authenticated Web release captures. */
@Profile("web-qa")
@RestController
@RequestMapping("/api/v2/test/web-fixture")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class WebQaFixtureController {

  private static final String PREFIX = "web-qa-";
  private static final String PROVIDER_READINESS_MARKER = "web-qa-disabled-provider-key";

  private final PipelineRunRepository runRepository;
  private final GateRepository gateRepository;
  private final AgentTaskRepository taskRepository;
  private final ValidationReportRepository reportRepository;
  private final TestPlanRepository testPlanRepository;
  private final TestTaskRepository testTaskRepository;
  private final TestResultRepository testResultRepository;
  private final AgentSessionRepository sessionRepository;
  private final AgentTurnRepository turnRepository;
  private final AgentActionEventRepository eventRepository;
  private final AppUserRepository userRepository;
  private final SseBroadcaster sseBroadcaster;
  private final FeatureFlagService featureFlagService;
  private final Environment environment;

  @org.springframework.beans.factory.annotation.Value("${executespec.live-llm.enabled:true}")
  private boolean liveLlmEnabled;

  /**
   * Matches StartupRunResumer's configured root so restart fixtures work with isolated workspaces.
   */
  @org.springframework.beans.factory.annotation.Value("${agent.workspace:workspace}")
  private String workspaceRoot;

  @PostMapping
  @Transactional(rollbackFor = Exception.class)
  public FixtureResponse seed(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          AppUserDetails principal)
      throws IOException {
    SafetyEvidence safety = safetyEvidence();
    if (!safety.safe()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Web QA fixture seeding requires the isolated web-qa profile with live LLM calls"
              + " disabled");
    }
    String userId = principal.getUser().getId();
    String orgId = principal.getUser().getOrgId();
    userRepository
        .findById(userId)
        .filter(user -> user.getDeepSeekApiKey() == null || user.getDeepSeekApiKey().isBlank())
        .ifPresent(
            user -> {
              user.setDeepSeekApiKey(PROVIDER_READINESS_MARKER);
              userRepository.save(user);
            });
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
    createLegacyPlanGate(gate.getRunId());
    PipelineRun recovery =
        createRun(
            userId,
            orgId,
            "FAILED",
            "REVIEWER_FAILED",
            "Release QA restart recovery unavailable",
            true);
    turn.setLinkedRunId(gate.getRunId());
    turnRepository.save(turn);
    return new FixtureResponse(
        success.getRunId(),
        failed.getRunId(),
        gate.getRunId(),
        recovery.getRunId(),
        sessionId,
        safety);
  }

  /**
   * Advance only a fixture-owned plan-gate row for provider-free screen captures. EXECUTING maps to
   * the V2 pipeline's persisted WORKING state. Neither transition starts a worker, provider, quota,
   * PR, or background job. Restart recovery is deliberately not simulated here because real
   * recovery must run the fenced reviewer and validation lifecycle rather than manufacture a
   * terminal status from fixture state. The endpoint is intentionally local/dev + SUPER_ADMIN only
   * with a strict prefix and transition order so it cannot mutate customer runs.
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

  // The fixture must obey the same durable plan-gate shape as a real historical run. A synthetic
  // AWAITING_APPROVAL status without a gate row correctly fails the RequirementIR evidence API
  // closed, which would make this protected-Web harness test corrupt data instead of product UI.
  void createLegacyPlanGate(String runId) {
    GateRecord gate = GateRecord.pending("gate:plan:" + runId, runId, "PLAN");
    gate.setBindingMode("LEGACY");
    gate.setBindingStatus("UNBOUND");
    gateRepository.save(gate);
  }

  @DeleteMapping
  // Keep run/workspace and agent-session graph deletion atomic. Without a transaction, a stale
  // fixture row can fail midway through cleanup and leave ACTIVE web-qa sessions behind.
  @Transactional(rollbackFor = Exception.class)
  public Map<String, Object> cleanup(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          AppUserDetails principal,
      @RequestParam(required = false) String sessionId,
      @RequestParam(defaultValue = "false") boolean preserveProviderReadiness)
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
      gateRepository
          .findFirstByRunIdAndGateType(run.getRunId(), "PLAN")
          .ifPresent(gateRepository::delete);
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
    List<AgentSession> sessions =
        new java.util.ArrayList<>(sessionRepository.findByUserIdAndIdStartingWith(userId, PREFIX));
    if (sessionId != null && !sessionId.isBlank()) {
      sessionRepository
          .findById(sessionId)
          .filter(session -> userId.equals(session.getUserId()))
          .filter(
              session ->
                  sessions.stream().noneMatch(existing -> existing.getId().equals(session.getId())))
          .ifPresent(sessions::add);
    }
    for (AgentSession session : sessions) {
      eventRepository.deleteBySessionId(session.getId());
      turnRepository.deleteBySessionId(session.getId());
      sessionRepository.delete(session);
    }
    if (!preserveProviderReadiness) {
      userRepository
          .findById(userId)
          .filter(user -> PROVIDER_READINESS_MARKER.equals(user.getDeepSeekApiKey()))
          .ifPresent(
              user -> {
                user.setDeepSeekApiKey(null);
                userRepository.save(user);
              });
    }
    return Map.of(
        "deletedRuns", runs.size(),
        "deletedAgentSessions", sessions.size(),
        "safety", safetyEvidence());
  }

  Map<String, Object> cleanup(AppUserDetails principal) throws IOException {
    return cleanup(principal, null, false);
  }

  /**
   * Provider-free browser evidence must be self-describing. The digest includes values and audit
   * metadata, so the browser harness can prove that it did not rewrite feature flags while it ran
   * instead of merely assuming that no mutation occurred.
   */
  SafetyEvidence safetyEvidence() {
    List<String> profiles = Arrays.stream(environment.getActiveProfiles()).sorted().toList();
    boolean isolatedProfile = profiles.contains("web-qa");
    boolean sharedProfileActive =
        profiles.stream()
            .anyMatch(
                profile -> List.of("local", "dev", "prod", "benchmark", "gcp").contains(profile));
    String canonicalFlags =
        featureFlagService.listAll().stream()
            .sorted(java.util.Comparator.comparing(flag -> flag.getFlagKey()))
            .map(
                flag ->
                    String.join(
                        "\u001f",
                        flag.getFlagKey(),
                        flag.getFlagValue(),
                        flag.getUpdatedBy() == null ? "" : flag.getUpdatedBy(),
                        flag.getUpdatedAt() == null ? "" : flag.getUpdatedAt().toString()))
            .collect(java.util.stream.Collectors.joining("\n"));
    return new SafetyEvidence(
        isolatedProfile && !sharedProfileActive && !liveLlmEnabled,
        profiles,
        liveLlmEnabled,
        sha256(canonicalFlags));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  // Package-visible for the regression test that ensures visual fixtures remain score-neutral.
  PipelineRun createRun(
      String userId, String orgId, String status, String reason, String title, boolean success)
      throws IOException {
    String runId = PREFIX + UUID.randomUUID();
    // Keep the fixture workspace on the same canonical <agent.workspace>/<runId> layout used by
    // WorkspaceFileService; otherwise the authenticated Code tab cannot show the fixture diff.
    String root = workspaceRoot == null || workspaceRoot.isBlank() ? "workspace" : workspaceRoot;
    Path workspace = Path.of(root, runId).toAbsolutePath();
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
    TestTask edgeTest =
        testTaskRepository.save(
            TestTask.builder()
                .testPlanId(plan.getId())
                .taskName("fixture edge case")
                .taskDescription("Deterministic release fixture edge-case test")
                .testCategory(TestCategory.VALIDATION)
                .status(success ? TestTaskStatus.COMPILED : TestTaskStatus.FAILED)
                .generatedCode("fixture")
                .build());
    testResultRepository.save(
        TestResult.builder()
            .testPlanId(plan.getId())
            .testTaskId(edgeTest.getId())
            .testMethod("fixture edge case")
            .status(success ? TestResultStatus.PASSED : TestResultStatus.FAILED)
            .expectedValue("reject invalid input")
            .actualValue(success ? "reject invalid input" : "build error")
            .failureMessage(success ? null : "fixture build failed")
            .durationMs(8)
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
    String configuredRoot =
        workspaceRoot == null || workspaceRoot.isBlank() ? "workspace" : workspaceRoot;
    Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
    Path normalized = path.toAbsolutePath().normalize();
    Path name = normalized.getFileName();
    return name != null
        && name.toString().startsWith(PREFIX)
        && normalized.getParent() != null
        && normalized.getParent().equals(root);
  }

  public record FixtureResponse(
      String successRunId,
      String failedRunId,
      String gateRunId,
      String recoveryRunId,
      String agentSessionId,
      SafetyEvidence safety) {}

  public record SafetyEvidence(
      boolean safe,
      List<String> activeProfiles,
      boolean liveLlmEnabled,
      String featureFlagSnapshotSha256) {}

  public record AdvanceRequest(String status) {}

  public record AdvanceResponse(
      String runId, String requestedStatus, String persistedStatus, String statusReason) {}
}
