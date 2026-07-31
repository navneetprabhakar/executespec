package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableCorrectionLifecycleTest {

  @TempDir Path tempDir;

  private final Map<UUID, CorrectionAttempt> rows = new HashMap<>();
  private final Map<String, ValidationReport> evidence = new HashMap<>();
  private final Map<String, AgentTask> taskRows = new HashMap<>();
  private CorrectionAttemptRepository attempts;
  private ValidationReportRepository reports;
  private AgentTaskRepository tasks;
  private CorrectionWorkspaceTransaction workspaces;
  private RunWorkspaceAuthorityService workspaceAuthorities;
  private DurableCorrectionLifecycle lifecycle;

  @BeforeEach
  void setUp() {
    attempts = mock(CorrectionAttemptRepository.class);
    reports = mock(ValidationReportRepository.class);
    tasks = mock(AgentTaskRepository.class);
    workspaceAuthorities = mock(RunWorkspaceAuthorityService.class);
    workspaces = new CorrectionWorkspaceTransaction();
    when(workspaceAuthorities.acquire(anyString(), any())).thenAnswer(i -> lease());
    when(workspaceAuthorities.tryAcquire(anyString(), any())).thenAnswer(i -> Optional.of(lease()));
    when(attempts.saveAndFlush(any()))
        .thenAnswer(
            invocation -> {
              CorrectionAttempt row = invocation.getArgument(0);
              rows.put(row.getId(), row);
              return row;
            });
    when(attempts.findById(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
    when(attempts.findFirstByRunIdAndPhaseInOrderByCreatedAtDesc(any(), any()))
        .thenAnswer(
            i ->
                rows.values().stream()
                    .filter(r -> r.getRunId().equals(i.getArgument(0)))
                    .filter(r -> ((List<?>) i.getArgument(1)).contains(r.getPhase()))
                    .findFirst());
    when(attempts.findByPhaseInOrderByCreatedAtAsc(any()))
        .thenAnswer(
            i -> {
              List<?> phases = i.getArgument(0);
              return rows.values().stream().filter(r -> phases.contains(r.getPhase())).toList();
            });
    when(attempts.transition(any(), any(), anyString(), anyString()))
        .thenAnswer(
            i -> {
              CorrectionAttempt row = rows.get(i.getArgument(0));
              if (row == null
                  || !java.util.Objects.equals(row.getLeaseToken(), i.getArgument(1))
                  || !java.util.Objects.equals(row.getPhase(), i.getArgument(2))) return 0;
              row.setPhase(i.getArgument(3));
              return 1;
            });
    when(attempts.claimRecovery(any(), any()))
        .thenAnswer(
            i -> {
              CorrectionAttempt row = rows.get(i.getArgument(0));
              if (row == null || !DurableCorrectionLifecycle.UNRESOLVED.contains(row.getPhase())) {
                return 0;
              }
              row.setLeaseToken(i.getArgument(1));
              return 1;
            });
    when(attempts.accept(any(), any(), anyString()))
        .thenAnswer(
            i -> {
              CorrectionAttempt row = rows.get(i.getArgument(0));
              if (row == null
                  || !java.util.Objects.equals(row.getLeaseToken(), i.getArgument(1))
                  || !"MUTATING".equals(row.getPhase())) return 0;
              row.setPhase("ACCEPTED");
              row.setAfterGeneration(i.getArgument(2));
              row.setResolvedAt(Instant.now());
              return 1;
            });
    when(attempts.acceptRecovered(any(), any(), anyString()))
        .thenAnswer(
            i -> {
              CorrectionAttempt row = rows.get(i.getArgument(0));
              if (row == null
                  || !java.util.Objects.equals(row.getLeaseToken(), i.getArgument(1))
                  || !("MUTATING".equals(row.getPhase())
                      || "RECOVERY_FAILED".equals(row.getPhase()))) return 0;
              row.setPhase("ACCEPTED");
              row.setAfterGeneration(i.getArgument(2));
              row.setResolvedAt(Instant.now());
              return 1;
            });
    when(attempts.rolledBack(any(), any()))
        .thenAnswer(
            i -> {
              CorrectionAttempt row = rows.get(i.getArgument(0));
              if (row == null || !java.util.Objects.equals(row.getLeaseToken(), i.getArgument(1)))
                return 0;
              row.setPhase("ROLLED_BACK");
              row.setAfterGeneration(row.getBeforeGeneration());
              row.setResolvedAt(Instant.now());
              return 1;
            });
    when(attempts.recoveryFailed(any(), any(), anyString()))
        .thenAnswer(
            i -> {
              CorrectionAttempt row = rows.get(i.getArgument(0));
              if (row == null || !java.util.Objects.equals(row.getLeaseToken(), i.getArgument(1)))
                return 0;
              row.setPhase("RECOVERY_FAILED");
              row.setFailureReason(i.getArgument(2));
              return 1;
            });
    when(reports.findByRunId(any()))
        .thenAnswer(i -> Optional.ofNullable(evidence.get(i.getArgument(0))));
    when(reports.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              ValidationReport report = i.getArgument(0);
              evidence.put(report.getRunId(), report);
              return report;
            });
    when(tasks.findById(any()))
        .thenAnswer(i -> Optional.ofNullable(taskRows.get(i.getArgument(0))));
    when(tasks.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              AgentTask task = i.getArgument(0);
              taskRows.put(task.getTaskId(), task);
              return task;
            });
    when(tasks.updateContinuationApprovalStatus(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            i -> {
              AgentTask task = taskRows.get(i.getArgument(0));
              if (task == null
                  || !java.util.Objects.equals(task.getContinuationApprovalId(), i.getArgument(1))
                  || !java.util.Objects.equals(
                      task.getContinuationApprovalProposalDigest(), i.getArgument(2))) return 0;
              task.setContinuationApprovalStatus(i.getArgument(3));
              return 1;
            });
    lifecycle =
        new DurableCorrectionLifecycle(
            attempts,
            workspaces,
            reports,
            tasks,
            new ObjectMapper().findAndRegisterModules(),
            workspaceAuthorities);
  }

  @Test
  void restartRecoveryRestoresWorkspaceAndEvidenceBeforeResolvingAttempt() throws Exception {
    Path repository = repository("recovery");
    ValidationReport original = report("run-1", "report-1", "COMPLETE");
    evidence.put("run-1", original);
    taskRows.put("run-1:task-1", task("run-1", "run-1:task-1"));

    DurableCorrectionLifecycle.Prepared prepared =
        lifecycle.prepare(
            "run-1",
            "run-1:task-1",
            repository,
            AutomaticCorrectionLifecycle.snapshotTask(taskRows.get("run-1:task-1")),
            "approval-1",
            "d".repeat(64));
    lifecycle.markMutating(prepared);
    Files.writeString(repository.resolve("tracked.txt"), "unsafe correction\n");
    evidence.put("run-1", report("run-1", "report-1", "STALE"));
    AgentTask mutated = taskRows.get("run-1:task-1");
    mutated.setStatus("FAILED");
    mutated.setWorkerReportJson("rejected-report");
    mutated.setProvider("anthropic");
    mutated.setModel("sonnet");
    mutated.setInputTokens(900);
    mutated.setContinuationActionsJson("[\"attempt-spend\"]");
    mutated.setContinuationApprovalId("approval-1");
    mutated.setContinuationApprovalProposalDigest("d".repeat(64));
    mutated.setContinuationApprovalStatus("CLAIMED");

    lifecycle.recoverAll();

    CorrectionAttempt row = rows.get(prepared.attemptId());
    assertThat(row.getPhase()).isEqualTo("ROLLED_BACK");
    assertThat(row.getAfterGeneration()).isEqualTo(row.getBeforeGeneration());
    assertThat(Files.readString(repository.resolve("tracked.txt"))).isEqualTo("baseline\n");
    assertThat(evidence.get("run-1").getStatus()).isEqualTo("COMPLETE");
    assertThat(evidence.get("run-1").getWorkspaceGeneration()).isEqualTo("a".repeat(64));
    assertThat(row.getRepositoriesJson()).contains("recovery", "head", "tree");
    assertThat(row.getEvidenceReportId()).isEqualTo("report-1");
    AgentTask restored = taskRows.get("run-1:task-1");
    assertThat(restored.getStatus()).isEqualTo("COMPLETE");
    assertThat(restored.getWorkerReportJson()).isEqualTo("{\"primary\":true}");
    assertThat(restored.getProvider()).isEqualTo("openrouter");
    assertThat(restored.getModel()).isEqualTo("deepseek");
    assertThat(restored.getInputTokens()).isEqualTo(900);
    assertThat(restored.getContinuationActionsJson()).isEqualTo("[\"attempt-spend\"]");
    assertThat(restored.getContinuationApprovalId()).isEqualTo("approval-1");
    assertThat(restored.getContinuationApprovalProposalDigest()).isEqualTo("d".repeat(64));
    assertThat(restored.getContinuationApprovalStatus()).isEqualTo("REJECTED");
  }

  @Test
  void acceptedResolutionPersistsExactBeforeAndAfterGenerations() throws Exception {
    Path repository = repository("accepted");
    evidence.put("run-2", report("run-2", "report-2", "COMPLETE"));
    taskRows.put("run-2:task-1", task("run-2", "run-2:task-1"));
    DurableCorrectionLifecycle.Prepared prepared =
        lifecycle.prepare("run-2", "run-2:task-1", repository);
    lifecycle.markMutating(prepared);
    Files.writeString(repository.resolve("tracked.txt"), "accepted correction\n");
    String after = workspaces.currentGeneration(prepared.checkpoint());

    lifecycle.accept(prepared, after);

    CorrectionAttempt row = rows.get(prepared.attemptId());
    assertThat(row.getPhase()).isEqualTo("ACCEPTED");
    assertThat(row.getBeforeGeneration()).isEqualTo(prepared.checkpoint().generation());
    assertThat(row.getAfterGeneration()).isEqualTo(after).isNotEqualTo(row.getBeforeGeneration());
    assertThat(row.getResolvedAt()).isNotNull();
  }

  @Test
  void restartAfterCompletedApprovalCommitsFreshValidatedGeneration() throws Exception {
    Path repository = repository("completed-recovery");
    AgentTask task = task("run-4", "run-4:task-1");
    task.setContinuationApprovalId("approval-4");
    task.setContinuationApprovalProposalDigest("e".repeat(64));
    task.setContinuationApprovalStatus("QUEUED");
    taskRows.put(task.getTaskId(), task);
    evidence.put("run-4", report("run-4", "report-4", "COMPLETE"));

    DurableCorrectionLifecycle.Prepared prepared =
        lifecycle.prepare(
            "run-4",
            task.getTaskId(),
            repository,
            AutomaticCorrectionLifecycle.snapshotTask(task),
            "approval-4",
            "e".repeat(64));
    lifecycle.markMutating(prepared);
    Files.writeString(repository.resolve("tracked.txt"), "accepted before crash\n");
    String acceptedGeneration = workspaces.currentGeneration(prepared.checkpoint());
    ValidationReport fresh = report("run-4", "report-4", "COMPLETE");
    fresh.setWorkspaceGeneration(acceptedGeneration);
    evidence.put("run-4", fresh);
    task.setContinuationApprovalStatus("COMPLETED");

    lifecycle.recoverAll();

    CorrectionAttempt row = rows.get(prepared.attemptId());
    assertThat(row.getPhase()).isEqualTo("ACCEPTED");
    assertThat(row.getAfterGeneration()).isEqualTo(acceptedGeneration);
    assertThat(Files.readString(repository.resolve("tracked.txt")))
        .isEqualTo("accepted before crash\n");
    assertThat(task.getContinuationApprovalStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void failedRollbackProofRemainsAnAdmissionFence() throws Exception {
    Path repository = repository("failed");
    taskRows.put("run-3:task-1", task("run-3", "run-3:task-1"));
    DurableCorrectionLifecycle.Prepared prepared =
        lifecycle.prepare("run-3", "run-3:task-1", repository);
    lifecycle.markMutating(prepared);
    git(repository, "checkout", "--orphan", "different-head");
    Files.writeString(repository.resolve("tracked.txt"), "different head\n");
    git(repository, "add", "-A");
    git(repository, "commit", "-q", "-m", "different");

    lifecycle.recoverAll();

    assertThat(rows.get(prepared.attemptId()).getPhase()).isEqualTo("RECOVERY_FAILED");
    assertThatThrownBy(() -> lifecycle.recoverRunOrThrow("run-3"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RECOVERY_UNPROVEN");
  }

  @Test
  void recoveryScanContinuesAfterOneAuthorityAcquisitionFails() throws Exception {
    Path firstRepository = repository("scan-first");
    Path secondRepository = repository("scan-second");
    taskRows.put("run-scan-1:task", task("run-scan-1", "run-scan-1:task"));
    taskRows.put("run-scan-2:task", task("run-scan-2", "run-scan-2:task"));
    DurableCorrectionLifecycle.Prepared first =
        lifecycle.prepare("run-scan-1", "run-scan-1:task", firstRepository);
    DurableCorrectionLifecycle.Prepared second =
        lifecycle.prepare("run-scan-2", "run-scan-2:task", secondRepository);
    lifecycle.markMutating(first);
    lifecycle.markMutating(second);
    Files.writeString(firstRepository.resolve("tracked.txt"), "unsafe first\n");
    Files.writeString(secondRepository.resolve("tracked.txt"), "unsafe second\n");
    when(workspaceAuthorities.tryAcquire(
            "run-scan-1", RunWorkspaceAuthorityService.Authority.RECOVERY))
        .thenThrow(new IllegalStateException("database unavailable"));

    lifecycle.recoverAll();

    assertThat(rows.get(first.attemptId()).getPhase()).isEqualTo("MUTATING");
    assertThat(rows.get(second.attemptId()).getPhase()).isEqualTo("ROLLED_BACK");
    assertThat(Files.readString(secondRepository.resolve("tracked.txt"))).isEqualTo("baseline\n");
  }

  private ValidationReport report(String runId, String id, String status) {
    return ValidationReport.builder()
        .id(id)
        .runId(runId)
        .workspaceGeneration("a".repeat(64))
        .status(status)
        .filesGenerated(1)
        .pipelineResult(PipelineResult.PASSED)
        .finalBuildPassed(true)
        .testExecutionVerified(true)
        .testsPassed(2)
        .testsTotal(2)
        .issuesJson("[]")
        .checksPassed("build")
        .createdAt(Instant.parse("2026-07-31T00:00:00Z"))
        .updatedAt(Instant.parse("2026-07-31T00:00:00Z"))
        .build();
  }

  private AgentTask task(String runId, String taskId) {
    return AgentTask.builder()
        .taskId(taskId)
        .runId(runId)
        .status("COMPLETE")
        .workerReportJson("{\"primary\":true}")
        .provider("openrouter")
        .model("deepseek")
        .effectiveProvider("openrouter")
        .effectiveModel("deepseek")
        .effectiveRouteAttribution("LEDGER")
        .continuationActionsJson("[]")
        .build();
  }

  private Path repository(String name) throws Exception {
    Path repository = Files.createDirectories(tempDir.resolve(name));
    git(repository, "init", "-q");
    git(repository, "config", "user.email", "test@executespec.invalid");
    git(repository, "config", "user.name", "ExecuteSpec Test");
    Files.writeString(repository.resolve("tracked.txt"), "baseline\n");
    git(repository, "add", "tracked.txt");
    git(repository, "commit", "-q", "-m", "baseline");
    return repository;
  }

  private static void git(Path repository, String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).directory(repository.toFile()).start();
    assertThat(process.waitFor()).isZero();
  }

  private static RunWorkspaceAuthorityService.Lease lease() {
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.ownerToken()).thenReturn(UUID.randomUUID());
    return lease;
  }
}
