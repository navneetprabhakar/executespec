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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

  @PostMapping
  public FixtureResponse seed(@org.springframework.security.core.annotation.AuthenticationPrincipal AppUserDetails principal)
      throws IOException {
    String userId = principal.getUser().getId();
    String orgId = principal.getUser().getOrgId();
    String sessionId = PREFIX + UUID.randomUUID();
    AgentSession session = sessionRepository.save(AgentSession.builder()
        .id(sessionId).orgId(orgId).userId(userId).clientSurface("WEB")
        .title("Release QA agent evidence").status("ACTIVE")
        .defaultExecutionMode("REMOTE").approvalPolicy("MANUAL").createdAt(Instant.now()).updatedAt(Instant.now()).build());
    AgentTurn turn = turnRepository.save(AgentTurn.builder()
        .id(PREFIX + "turn-" + UUID.randomUUID()).sessionId(sessionId).orgId(orgId).userId(userId)
        .sequence(1).userMessage("Implement the release QA fixture").assistantSummary("Fixture action is ready for approval.")
        .intentType("CODE_RUN_REQUIRED").intentConfidence(1.0).actionType("create_cloud_run")
        .proposedActionJson("{\"action\":\"create_cloud_run\",\"requiresConfirmation\":true}").status("OK").build());
    eventRepository.save(AgentActionEvent.builder().id(PREFIX + "event-" + UUID.randomUUID()).sessionId(sessionId)
        .turnId(turn.getId()).actionId(PREFIX + "action").type("GOAL_DECISION")
        .payloadJson("{\"status\":\"READY\",\"summary\":\"Fixture evidence is ready\"}").createdAt(Instant.now()).build());

    PipelineRun success = createRun(userId, orgId, "COMPLETE", "CLEAN_COMPLETION", "Release QA success", true);
    PipelineRun failed = createRun(userId, orgId, "FAILED", "FINAL_BUILD_FAILED", "Release QA recovery", false);
    PipelineRun gate = createRun(userId, orgId, "AWAITING_APPROVAL", "NO_REVIEW_RESULT", "Release QA approval gate", true);
    gate.setCompletedAt(null);
    gate.setCurrentGate("PLAN_APPROVAL");
    runRepository.save(gate);
    return new FixtureResponse(success.getRunId(), failed.getRunId(), gate.getRunId(), sessionId);
  }

  @DeleteMapping
  public Map<String, Object> cleanup(@org.springframework.security.core.annotation.AuthenticationPrincipal AppUserDetails principal)
      throws IOException {
    String userId = principal.getUser().getId();
    List<PipelineRun> runs = runRepository.findByCreatedByUserIdOrderByStartedAtDesc(userId, org.springframework.data.domain.PageRequest.of(0, 50)).getContent()
        .stream().filter(r -> r.getRunId().startsWith(PREFIX)).toList();
    for (PipelineRun run : runs) {
      taskRepository.deleteAll(taskRepository.findByRunId(run.getRunId()));
      reportRepository.findByRunId(run.getRunId()).ifPresent(reportRepository::delete);
      testPlanRepository.findByRunId(run.getRunId()).ifPresent(plan -> {
        testResultRepository.deleteAll(testResultRepository.findByTestPlanId(plan.getId()));
        testTaskRepository.deleteAll(testTaskRepository.findByTestPlanId(plan.getId()));
        testPlanRepository.delete(plan);
      });
      deleteWorkspace(run.getWorkspacePath());
      runRepository.delete(run);
    }
    return Map.of("deletedRuns", runs.size());
  }

  private PipelineRun createRun(String userId, String orgId, String status, String reason, String title, boolean success)
      throws IOException {
    String runId = PREFIX + UUID.randomUUID();
    Path workspace = Path.of("workspace", "web-qa", runId).toAbsolutePath();
    Files.createDirectories(workspace.resolve("src"));
    Files.writeString(workspace.resolve("README.md"), "# Release QA fixture\n", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("src/app.ts"), success ? "export const status = 'complete';\n" : "export const status = 'failed';\n", StandardCharsets.UTF_8);
    PipelineRun run = PipelineRun.builder().runId(runId).userRequest(title).status(status).statusReason(reason)
        .harnessVersion("v2").runMode("BUILD").createdByUserId(userId).orgId(orgId)
        .startedAt(Instant.now().minusSeconds(30)).completedAt(Instant.now()).workspacePath(workspace.toString())
        .runTitle(title).runSummary("Deterministic release evidence fixture").planJson("{\"tasks\":[{\"id\":\"fixture-task\",\"title\":\"Validate fixture\"}]}")
        .reviewJson(success ? "{\"verdict\":\"CLEAN\"}" : "{\"verdict\":\"UNRESOLVED_ISSUES\"}")
        .totalInputTokens(120).totalOutputTokens(60).actualCostUsd(0.0).plannerModel("fixture").workerModel("fixture").reviewerModel("fixture")
        .planStrategy("SINGLE_SHOT").build();
    runRepository.save(run);
    taskRepository.save(AgentTask.builder().taskId(runId + "-task").runId(runId).title("Validate fixture").complexity("LOW")
        .status(success ? "COMPLETE" : "FAILED").taskJson("{\"id\":\"fixture-task\"}").completionNotes(success ? "Verified" : "Build failed")
        .provider("fixture").model("fixture").startedAt(run.getStartedAt()).completedAt(run.getCompletedAt()).build());
    reportRepository.save(ValidationReport.builder().id(runId + "-report").runId(runId).filesGenerated(1)
        .languages("TypeScript").pipelineResult(success ? PipelineResult.PASSED : PipelineResult.FAILED)
        .issuesJson(success ? "[]" : "[{\"severity\":\"CRITICAL\",\"message\":\"Build failed\"}]")
        .checksPassed(success ? "build,tests,review" : "none").reportMarkdown(success ? "# Verified fixture\n" : "# Failed fixture\n")
        .verifiedScore(success ? 92 : 38).finalBuildPassed(success).testsPassed(success ? 2 : 0).testsTotal(2)
        .reviewerVerdict(success ? "CLEAN" : "UNRESOLVED_ISSUES").status("COMPLETE").build());
    TestPlan plan = testPlanRepository.save(TestPlan.builder().runId(runId).status(success ? TestPlanStatus.COMPLETED : TestPlanStatus.FAILED)
        .planJson("{\"tests\":[\"fixture happy path\",\"fixture failure path\"]}").createdAt(Instant.now()).updatedAt(Instant.now()).build());
    TestTask test = testTaskRepository.save(TestTask.builder().testPlanId(plan.getId()).taskName("fixture smoke")
        .taskDescription("Deterministic release fixture test").testCategory(TestCategory.VALIDATION)
        .status(success ? TestTaskStatus.COMPILED : TestTaskStatus.FAILED).generatedCode("fixture").build());
    testResultRepository.save(TestResult.builder().testPlanId(plan.getId()).testTaskId(test.getId()).testMethod("fixture smoke")
        .status(success ? TestResultStatus.PASSED : TestResultStatus.FAILED).expectedValue("pass").actualValue(success ? "pass" : "build error")
        .failureMessage(success ? null : "fixture build failed").durationMs(12).build());
    return run;
  }

  private void deleteWorkspace(String value) throws IOException {
    if (value == null) return;
    Path path = Path.of(value).toAbsolutePath();
    if (!path.toString().contains("/workspace/web-qa/")) return;
    if (Files.exists(path)) try (var stream = Files.walk(path)) { stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
  }

  public record FixtureResponse(String successRunId, String failedRunId, String gateRunId, String agentSessionId) {}
}
