package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.NonExecutingGitTreeWriter;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.RunMode;
import com.navneet.executespec.state.entity.PipelineRun;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicationPreparationServiceTest {

  @Test
  void replayReinsertsOnlyTheExactReceiptedObjectAndNeverResolvesANewTarget(@TempDir Path workspace)
      throws Exception {
    Path tracked = workspace.resolve("tracked.txt");
    Files.writeString(tracked, "before\n");
    try (Git git = Git.init().setDirectory(workspace.toFile()).call()) {
      git.add().addFilepattern("tracked.txt").call();
      git.commit()
          .setAuthor("Test", "test@example.com")
          .setCommitter("Test", "test@example.com")
          .setMessage("base")
          .call();
    }
    Files.writeString(tracked, "after\n");
    PublicationPreparationStore store = mock(PublicationPreparationStore.class);
    PublicationTargetResolver targets = mock(PublicationTargetResolver.class);
    NonExecutingGitTreeWriter gitObjects = new NonExecutingGitTreeWriter();
    RunWorkspaceAuthorityService authorities = mock(RunWorkspaceAuthorityService.class);
    CorrectionWorkspaceTransaction generations = mock(CorrectionWorkspaceTransaction.class);
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    PublicationPreparationService service =
        new PublicationPreparationService(
            store, targets, gitObjects, authorities, generations, reports, new ObjectMapper());

    String generation = "a".repeat(64);
    PipelineRun run =
        PipelineRun.builder()
            .runId("publication-replay")
            .runMode(RunMode.BUILD)
            .buildProtocolVersion("SHARED_KERNEL_V2")
            .acceptedWorkspaceGeneration(generation)
            .acceptedGenerationAttemptId(UUID.randomUUID())
            .acceptedCheckpointKind("GCS")
            .acceptedCheckpointKey("gs://bucket/checkpoint")
            .acceptedCheckpointDigest("b".repeat(64))
            .acceptedCheckpointCompletedAt(Instant.parse("2026-08-12T11:00:00Z"))
            .build();
    ValidationReport report =
        ValidationReport.builder()
            .id("report-1")
            .runId(run.getRunId())
            .workspaceGeneration(generation)
            .status("COMPLETE")
            .pipelineResult(PipelineResult.PASSED)
            .updatedAt(Instant.parse("2026-08-12T11:30:00Z"))
            .build();
    UUID preparationId = UUID.randomUUID();
    Instant committedAt = Instant.parse("2026-08-12T12:00:00Z");
    NonExecutingGitTreeWriter.PreparedCommit exact =
        gitObjects.prepareCommit(workspace, "receipted message", committedAt, () -> {});
    Path looseCommit =
        workspace
            .resolve(".git/objects")
            .resolve(exact.commitId().substring(0, 2))
            .resolve(exact.commitId().substring(2));
    Files.delete(looseCommit);
    PublicationPreparationStore.PreparedRepository receipt =
        new PublicationPreparationStore.PreparedRepository(
            UUID.randomUUID(),
            "GITHUB:owner/repository",
            PublicationPreparationService.sha256("GITHUB:owner/repository"),
            ".",
            "main",
            exact.parentCommitId(),
            exact.treeId(),
            exact.commitId(),
            exact.hasChanges(),
            true,
            "receipted message",
            committedAt);
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(reports.findByRunId(run.getRunId())).thenReturn(Optional.of(report));
    when(authorities.acquire(
            run.getRunId(), RunWorkspaceAuthorityService.Authority.PUBLICATION_PREPARE))
        .thenReturn(lease);
    when(generations.captureGeneration(workspace)).thenReturn(generation);
    String evidenceDigest = service.validationEvidenceDigest(report);
    when(store.findPrepared(run.getRunId(), generation, evidenceDigest))
        .thenReturn(
            Optional.of(
                new PublicationPreparationStore.PreparedPublication(
                    preparationId,
                    run.getRunId(),
                    generation,
                    report.getId(),
                    evidenceDigest,
                    Instant.parse("2026-08-12T12:01:00Z"))));
    when(store.preparedRepositories(preparationId)).thenReturn(List.of(receipt));
    PublicationPreparationService.PreparationResult replay = service.prepare(run, workspace);

    assertThat(replay.replay()).isTrue();
    assertThat(replay.repositories()).containsExactly(receipt);
    assertThat(looseCommit).exists();
    verify(targets, never()).resolve(any());
    verify(store, never()).admit(any(), any());
  }
}
