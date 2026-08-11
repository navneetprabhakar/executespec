package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.GcsWorkspaceStore;
import com.navneet.executespec.harness.TaskStatusPersistenceService;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class WorkspaceGenerationRecoveryServiceTest {

  @TempDir Path temp;

  @Test
  void acceptedFrontierRequiresTheExactDedicatedCheckpointReceipt() {
    String runId = "run-frontier-receipt";
    UUID attemptId = UUID.randomUUID();
    String generation = "a".repeat(64);
    Instant completedAt = Instant.parse("2026-08-10T10:00:00Z");
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    WorkspaceGenerationAttempt accepted =
        WorkspaceGenerationAttempt.builder()
            .id(attemptId)
            .runId(runId)
            .taskIndex(0)
            .state(WorkspaceGenerationState.ACCEPTED)
            .candidateGeneration(generation)
            .checkpointKind("GCS")
            .checkpointKey("gs://internal/accepted.tar.gz")
            .checkpointDigest("b".repeat(64))
            .checkpointCompletedAt(completedAt)
            .build();
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .runMode("BUILD")
            .acceptedWorkspaceGeneration(generation)
            .acceptedTaskIndex(0)
            .acceptedGenerationAttemptId(attemptId)
            .acceptedCheckpointKind("GCS")
            .acceptedCheckpointKey("gs://internal/accepted.tar.gz")
            .acceptedCheckpointDigest("b".repeat(64))
            .acceptedCheckpointCompletedAt(completedAt)
            .build();
    when(runs.findById(runId)).thenReturn(Optional.of(run));
    when(attempts.findById(attemptId)).thenReturn(Optional.of(accepted));
    WorkspaceGenerationRecoveryService recovery = recovery(attempts, runs);

    recovery.assertAcceptedFrontier(runId, generation);

    run.setAcceptedCheckpointKey("gs://internal/different.tar.gz");
    assertThatThrownBy(() -> recovery.assertAcceptedFrontier(runId, generation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("WORKSPACE_GENERATION_ACCEPTANCE_INVALID");
  }

  @Test
  void restoresAcceptedArchiveIntoCurrentNodeRootNotPersistedHostPath() throws Exception {
    String runId = "run-cross-node";
    Path source = temp.resolve("old-node/source");
    Files.createDirectories(source);
    init(source);
    Files.writeString(source.resolve("app.txt"), "before\n");
    commit(source, "baseline");
    Files.writeString(source.resolve("app.txt"), "accepted candidate\n");

    CorrectionWorkspaceTransaction transactions = new CorrectionWorkspaceTransaction();
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = transactions.begin(source);
    WorkspaceGenerationArchiveService archives =
        new WorkspaceGenerationArchiveService(new ObjectMapper(), transactions);
    Path archive = archives.create(checkpoint);
    String digest;
    try (var input = Files.newInputStream(archive)) {
      digest = DigestUtils.sha256Hex(input);
    }

    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationAttempt accepted =
        WorkspaceGenerationAttempt.builder()
            .id(UUID.randomUUID())
            .runId(runId)
            .state(WorkspaceGenerationState.ACCEPTED)
            .workspaceRoot("/old-host/non-portable/" + runId)
            .candidateGeneration(checkpoint.generation())
            .checkpointKind("GCS")
            .checkpointKey("gs://bucket/candidate.tar.gz")
            .checkpointDigest(digest)
            .build();
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    stubAcceptedFrontier(attempts, runs, accepted);
    GcsWorkspaceStore gcs = mock(GcsWorkspaceStore.class);
    when(gcs.downloadToTemp("gs://bucket/candidate.tar.gz", runId)).thenReturn(archive);
    AgentProperties properties = new AgentProperties();
    Path currentBase = temp.resolve("new-node/workspace");
    properties.setWorkspace(currentBase.toString());
    RunWorkspaceAuthorityService authorities = mock(RunWorkspaceAuthorityService.class);
    RunWorkspaceAuthorityService.Lease recoveryLease = recoveryLease(runId);
    when(authorities.tryAcquire(runId, RunWorkspaceAuthorityService.Authority.RECOVERY))
        .thenReturn(Optional.of(recoveryLease));

    WorkspaceGenerationRecoveryService recovery =
        new WorkspaceGenerationRecoveryService(
            attempts,
            transactions,
            authorities,
            new ObjectMapper(),
            mock(PlatformTransactionManager.class),
            mock(TaskStatusPersistenceService.class),
            mock(WorkspaceGenerationTaskTransitionService.class),
            gcs,
            archives,
            runs,
            mock(WorkspaceIgnoredArtifactGuard.class),
            properties,
            mock(WorkspaceGenerationTelemetry.class));

    recovery.ensureAcceptedWorkspaceAvailable(runId);

    Path restored = currentBase.resolve(runId);
    assertThat(Files.readString(restored.resolve("app.txt"))).isEqualTo("accepted candidate\n");
    assertThat(transactions.begin(restored).generation()).isEqualTo(checkpoint.generation());
    verify(recoveryLease).close();
  }

  @Test
  void replacesExistingWrongWorkspaceWithExactAcceptedArchive() throws Exception {
    String runId = "run-stale-node";
    Path source = Files.createDirectories(temp.resolve("accepted-source")).toRealPath();
    init(source);
    Files.writeString(source.resolve("app.txt"), "baseline\n");
    commit(source, "baseline");
    Files.writeString(source.resolve("app.txt"), "accepted\n");
    CorrectionWorkspaceTransaction transactions = new CorrectionWorkspaceTransaction();
    CorrectionWorkspaceTransaction.Checkpoint acceptedCheckpoint = transactions.begin(source);
    WorkspaceGenerationArchiveService archives =
        new WorkspaceGenerationArchiveService(new ObjectMapper(), transactions);
    Path archive = archives.create(acceptedCheckpoint);
    String digest = DigestUtils.sha256Hex(Files.readAllBytes(archive));

    Path currentBase = temp.resolve("replacement-node");
    Path wrong = Files.createDirectories(currentBase.resolve(runId)).toRealPath();
    init(wrong);
    Files.writeString(wrong.resolve("wrong.txt"), "partial or stale\n");
    commit(wrong, "wrong generation");

    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationAttempt accepted =
        WorkspaceGenerationAttempt.builder()
            .id(UUID.randomUUID())
            .runId(runId)
            .state(WorkspaceGenerationState.ACCEPTED)
            .candidateGeneration(acceptedCheckpoint.generation())
            .checkpointKind("GCS")
            .checkpointKey("gs://bucket/accepted.tar.gz")
            .checkpointDigest(digest)
            .build();
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    stubAcceptedFrontier(attempts, runs, accepted);
    GcsWorkspaceStore gcs = mock(GcsWorkspaceStore.class);
    when(gcs.downloadToTemp("gs://bucket/accepted.tar.gz", runId)).thenReturn(archive);
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(currentBase.toString());
    RunWorkspaceAuthorityService authorities = mock(RunWorkspaceAuthorityService.class);
    RunWorkspaceAuthorityService.Lease recoveryLease = recoveryLease(runId);
    when(authorities.tryAcquire(runId, RunWorkspaceAuthorityService.Authority.RECOVERY))
        .thenReturn(Optional.of(recoveryLease));
    WorkspaceGenerationRecoveryService recovery =
        new WorkspaceGenerationRecoveryService(
            attempts,
            transactions,
            authorities,
            new ObjectMapper(),
            transactionManager(),
            mock(TaskStatusPersistenceService.class),
            mock(WorkspaceGenerationTaskTransitionService.class),
            gcs,
            archives,
            runs,
            mock(WorkspaceIgnoredArtifactGuard.class),
            properties,
            mock(WorkspaceGenerationTelemetry.class));

    recovery.ensureAcceptedWorkspaceAvailable(runId);

    Path restored = currentBase.resolve(runId);
    assertThat(Files.readString(restored.resolve("app.txt"))).isEqualTo("accepted\n");
    assertThat(restored.resolve("wrong.txt")).doesNotExist();
    assertThat(transactions.begin(restored).generation())
        .isEqualTo(acceptedCheckpoint.generation());
    try (var siblings = Files.list(currentBase)) {
      assertThat(siblings.map(path -> path.getFileName().toString()).toList())
          .containsExactly(runId);
    }
    verify(recoveryLease).close();
  }

  @Test
  void staleWorkspaceIsNeverReplacedWithoutRecoveryAuthority() throws Exception {
    String runId = "run-stale-busy";
    Path currentBase = temp.resolve("busy-node");
    Path wrong = Files.createDirectories(currentBase.resolve(runId)).toRealPath();
    init(wrong);
    Files.writeString(wrong.resolve("wrong.txt"), "must remain until authority is acquired\n");
    commit(wrong, "wrong generation");

    CorrectionWorkspaceTransaction transactions = new CorrectionWorkspaceTransaction();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationAttempt accepted =
        WorkspaceGenerationAttempt.builder()
            .id(UUID.randomUUID())
            .runId(runId)
            .state(WorkspaceGenerationState.ACCEPTED)
            .candidateGeneration("a".repeat(64))
            .checkpointKind("GCS")
            .checkpointKey("gs://bucket/accepted.tar.gz")
            .checkpointDigest("b".repeat(64))
            .build();
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    stubAcceptedFrontier(attempts, runs, accepted);
    RunWorkspaceAuthorityService authorities = mock(RunWorkspaceAuthorityService.class);
    when(authorities.tryAcquire(runId, RunWorkspaceAuthorityService.Authority.RECOVERY))
        .thenReturn(Optional.empty());
    GcsWorkspaceStore gcs = mock(GcsWorkspaceStore.class);
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(currentBase.toString());
    WorkspaceGenerationRecoveryService recovery =
        new WorkspaceGenerationRecoveryService(
            attempts,
            transactions,
            authorities,
            new ObjectMapper(),
            transactionManager(),
            mock(TaskStatusPersistenceService.class),
            mock(WorkspaceGenerationTaskTransitionService.class),
            gcs,
            mock(WorkspaceGenerationArchiveService.class),
            runs,
            mock(WorkspaceIgnoredArtifactGuard.class),
            properties,
            mock(WorkspaceGenerationTelemetry.class));

    assertThatThrownBy(() -> recovery.ensureAcceptedWorkspaceAvailable(runId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("WORKSPACE_GENERATION_RECOVERY_DEFERRED");

    assertThat(Files.readString(wrong.resolve("wrong.txt")))
        .isEqualTo("must remain until authority is acquired\n");
    verifyNoInteractions(gcs);
  }

  @Test
  void everyCrashWindowRestoresTrackedUntrackedStagedAndIgnoredMultiRepoState() throws Exception {
    for (WorkspaceGenerationState crashState : WorkspaceGenerationRecoveryService.UNRESOLVED) {
      String runId = "kill-" + crashState.name().toLowerCase();
      Path root = Files.createDirectories(temp.resolve("kill-windows").resolve(runId)).toRealPath();
      Path nested = Files.createDirectories(root.resolve("repos/api")).toRealPath();
      init(root);
      init(nested);
      Files.writeString(root.resolve(".gitignore"), "baseline-cache/\nscratch/\n");
      Files.writeString(root.resolve("tracked.txt"), "tracked-before\n");
      Files.writeString(nested.resolve("api.txt"), "api-before\n");
      commit(nested, "api baseline");
      commit(root, "root baseline");

      Files.writeString(root.resolve("staged.txt"), "staged-before\n");
      git(root, "add", "staged.txt");
      Files.writeString(root.resolve("untracked.txt"), "untracked-before\n");
      Files.createDirectories(root.resolve("baseline-cache"));
      Files.writeString(root.resolve("baseline-cache/dependency.bin"), "keep\n");

      CorrectionWorkspaceTransaction transactions = new CorrectionWorkspaceTransaction();
      CorrectionWorkspaceTransaction.Checkpoint checkpoint = transactions.begin(root);
      WorkspaceIgnoredArtifactGuard ignored =
          spy(new WorkspaceIgnoredArtifactGuard(new ObjectMapper()));
      WorkspaceIgnoredArtifactGuard.Baseline ignoredBaseline =
          ignored.capture(checkpoint, () -> {});
      AtomicBoolean baselinePresentBeforeArtifactCleanup = new AtomicBoolean();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                baselinePresentBeforeArtifactCleanup.set(
                    Files.exists(root.resolve("baseline-cache/dependency.bin")));
                return invocation.callRealMethod();
              })
          .when(ignored)
          .cleanAttemptArtifacts(any(), eq(ignoredBaseline.json()), any());

      Files.writeString(root.resolve("tracked.txt"), "rejected\n");
      Files.writeString(root.resolve("staged.txt"), "rejected\n");
      Files.delete(root.resolve("untracked.txt"));
      Files.writeString(nested.resolve("api.txt"), "rejected\n");
      Files.createDirectories(root.resolve("scratch"));
      Files.writeString(root.resolve("scratch/rejected.bin"), "remove\n");

      UUID attemptId = UUID.randomUUID();
      UUID recoveryToken = UUID.randomUUID();
      WorkspaceGenerationAttempt stale =
          attempt(attemptId, runId, crashState, checkpoint, ignoredBaseline.json(), null);
      WorkspaceGenerationAttempt claimed =
          attempt(
              attemptId,
              runId,
              WorkspaceGenerationState.ROLLING_BACK,
              checkpoint,
              ignoredBaseline.json(),
              recoveryToken);
      WorkspaceGenerationAttemptRepository attempts =
          mock(WorkspaceGenerationAttemptRepository.class);
      when(attempts.claimRecovery(
              eq(attemptId),
              eq(crashState),
              eq(WorkspaceGenerationState.ROLLING_BACK),
              eq(recoveryToken),
              any()))
          .thenReturn(1);
      when(attempts.findById(attemptId)).thenReturn(Optional.of(claimed));
      when(attempts.recordRecoveryEvidence(
              eq(attemptId),
              eq(recoveryToken),
              eq(WorkspaceGenerationState.ROLLING_BACK),
              anyString(),
              anyString(),
              eq("INTERRUPTED_GENERATION_RECOVERED"),
              anyString(),
              any()))
          .thenReturn(1);
      when(attempts.markRecoveredRolledBack(
              eq(attemptId),
              eq(recoveryToken),
              eq(WorkspaceGenerationState.ROLLING_BACK),
              eq(WorkspaceGenerationState.ROLLED_BACK),
              any()))
          .thenReturn(1);
      RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
      when(lease.ownerToken()).thenReturn(recoveryToken);
      doNothing().when(lease).assertOwned();
      TaskStatusPersistenceService taskStatuses = mock(TaskStatusPersistenceService.class);
      AgentProperties properties = new AgentProperties();
      properties.setWorkspace(temp.resolve("kill-windows").toString());
      WorkspaceGenerationRecoveryService recovery =
          recoveryService(
              attempts, transactions, taskStatuses, ignored, properties, transactionManager());

      recovery.recover(stale, lease);

      assertThat(baselinePresentBeforeArtifactCleanup)
          .as("baseline ignored root survived Git rollback after %s crash", crashState)
          .isTrue();
      assertThat(transactions.begin(root).generation())
          .as("restored generation after %s crash", crashState)
          .isEqualTo(checkpoint.generation());
      assertThat(Files.readString(root.resolve("tracked.txt"))).isEqualTo("tracked-before\n");
      assertThat(Files.readString(root.resolve("staged.txt"))).isEqualTo("staged-before\n");
      assertThat(Files.readString(root.resolve("untracked.txt"))).isEqualTo("untracked-before\n");
      assertThat(Files.readString(nested.resolve("api.txt"))).isEqualTo("api-before\n");
      assertThat(root.resolve("baseline-cache")).doesNotExist();
      assertThat(root.resolve("scratch")).doesNotExist();
      verify(attempts)
          .recordRecoveryEvidence(
              eq(attemptId),
              eq(recoveryToken),
              eq(WorkspaceGenerationState.ROLLING_BACK),
              anyString(),
              contains("changedFiles"),
              eq("INTERRUPTED_GENERATION_RECOVERED"),
              contains("before dependent execution"),
              any());
      verify(lease).close();
    }
  }

  @Test
  void recoveryClaimLoserCannotWriteFalseRecoveryFailureEvidence() {
    UUID attemptId = UUID.randomUUID();
    UUID recoveryToken = UUID.randomUUID();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationAttempt stale =
        WorkspaceGenerationAttempt.builder()
            .id(attemptId)
            .runId("claim-race")
            .taskId("T1")
            .state(WorkspaceGenerationState.MUTATING)
            .build();
    when(attempts.claimRecovery(
            eq(attemptId),
            eq(WorkspaceGenerationState.MUTATING),
            eq(WorkspaceGenerationState.ROLLING_BACK),
            eq(recoveryToken),
            any()))
        .thenReturn(0);
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.ownerToken()).thenReturn(recoveryToken);
    PlatformTransactionManager transactionManager = transactionManager();
    WorkspaceGenerationRecoveryService recovery =
        recoveryService(
            attempts,
            new CorrectionWorkspaceTransaction(),
            mock(TaskStatusPersistenceService.class),
            mock(WorkspaceIgnoredArtifactGuard.class),
            new AgentProperties(),
            transactionManager);

    assertThatThrownBy(() -> recovery.recover(stale, lease))
        .hasMessageContaining("RECOVERY_CLAIM_FAILED");

    verify(attempts, never()).markRecoveredFailure(any(), any(), any(), any(), any(), any(), any());
    verify(lease).close();
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  private WorkspaceGenerationRecoveryService recoveryService(
      WorkspaceGenerationAttemptRepository attempts,
      CorrectionWorkspaceTransaction transactions,
      TaskStatusPersistenceService taskStatuses,
      WorkspaceIgnoredArtifactGuard ignored,
      AgentProperties properties,
      PlatformTransactionManager transactionManager) {
    return new WorkspaceGenerationRecoveryService(
        attempts,
        transactions,
        mock(RunWorkspaceAuthorityService.class),
        new ObjectMapper(),
        transactionManager,
        taskStatuses,
        mock(WorkspaceGenerationTaskTransitionService.class),
        mock(GcsWorkspaceStore.class),
        new WorkspaceGenerationArchiveService(new ObjectMapper(), transactions),
        mock(PipelineRunRepository.class),
        ignored,
        properties,
        mock(WorkspaceGenerationTelemetry.class));
  }

  private WorkspaceGenerationRecoveryService recovery(
      WorkspaceGenerationAttemptRepository attempts, PipelineRunRepository runs) {
    CorrectionWorkspaceTransaction transactions = new CorrectionWorkspaceTransaction();
    return new WorkspaceGenerationRecoveryService(
        attempts,
        transactions,
        mock(RunWorkspaceAuthorityService.class),
        new ObjectMapper(),
        transactionManager(),
        mock(TaskStatusPersistenceService.class),
        mock(WorkspaceGenerationTaskTransitionService.class),
        mock(GcsWorkspaceStore.class),
        new WorkspaceGenerationArchiveService(new ObjectMapper(), transactions),
        runs,
        mock(WorkspaceIgnoredArtifactGuard.class),
        new AgentProperties(),
        mock(WorkspaceGenerationTelemetry.class));
  }

  private static void stubAcceptedFrontier(
      WorkspaceGenerationAttemptRepository attempts,
      PipelineRunRepository runs,
      WorkspaceGenerationAttempt accepted) {
    Instant completedAt = Instant.parse("2026-08-10T10:00:00Z");
    accepted.setTaskIndex(0);
    accepted.setCheckpointCompletedAt(completedAt);
    PipelineRun run =
        PipelineRun.builder()
            .runId(accepted.getRunId())
            .runMode("BUILD")
            .acceptedWorkspaceGeneration(accepted.getCandidateGeneration())
            .acceptedTaskIndex(accepted.getTaskIndex())
            .acceptedGenerationAttemptId(accepted.getId())
            .acceptedCheckpointKind(accepted.getCheckpointKind())
            .acceptedCheckpointKey(accepted.getCheckpointKey())
            .acceptedCheckpointDigest(accepted.getCheckpointDigest())
            .acceptedCheckpointCompletedAt(completedAt)
            .build();
    when(runs.findById(accepted.getRunId())).thenReturn(Optional.of(run));
    when(attempts.findById(accepted.getId())).thenReturn(Optional.of(accepted));
  }

  private static PlatformTransactionManager transactionManager() {
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    TransactionStatus status = mock(TransactionStatus.class);
    when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    return manager;
  }

  private static RunWorkspaceAuthorityService.Lease recoveryLease(String runId) {
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn(runId);
    doNothing().when(lease).assertOwned();
    return lease;
  }

  private static WorkspaceGenerationAttempt attempt(
      UUID id,
      String runId,
      WorkspaceGenerationState state,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      String ignoredBaseline,
      UUID recoveryToken)
      throws Exception {
    var repositories =
        checkpoint.repositories().stream()
            .map(
                repository ->
                    java.util.Map.of(
                        "repository",
                        checkpoint
                            .workspaceRoot()
                            .relativize(repository.repository())
                            .toString()
                            .replace('\\', '/')
                            .replaceFirst("^$", "."),
                        "head",
                        repository.head(),
                        "tree",
                        repository.tree()))
            .toList();
    return WorkspaceGenerationAttempt.builder()
        .id(id)
        .runId(runId)
        .taskId("T1")
        .state(state)
        .workspaceRoot(checkpoint.workspaceRoot().toString())
        .beforeGeneration(checkpoint.generation())
        .repositoriesJson(new ObjectMapper().writeValueAsString(repositories))
        .ignoredArtifactBaselineJson(ignoredBaseline)
        .recoveryLeaseToken(recoveryToken)
        .build();
  }

  private static void init(Path repository) throws Exception {
    git(repository, "init", "-b", "main");
    git(repository, "config", "user.email", "test@executespec.local");
    git(repository, "config", "user.name", "ExecuteSpec Test");
  }

  private static void commit(Path repository, String message) throws Exception {
    git(repository, "add", "-A");
    git(repository, "commit", "-m", message);
  }

  private static String git(Path repository, String... args) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(java.util.List.of(args));
    Process process =
        new ProcessBuilder(command)
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = process.waitFor();
    if (exit != 0) throw new AssertionError(String.join(" ", command) + ": " + output);
    return output.trim();
  }
}
