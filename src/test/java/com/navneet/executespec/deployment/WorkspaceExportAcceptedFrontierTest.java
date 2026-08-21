package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.preview.DevDockerfileGenerator;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.DurableCorrectionLifecycle;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationRecoveryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class WorkspaceExportAcceptedFrontierTest {

  @TempDir Path workspace;

  private final StateStore stateStore = mock(StateStore.class);
  private final WorkspaceGenerationRecoveryService generations =
      mock(WorkspaceGenerationRecoveryService.class);
  private final DurableCorrectionLifecycle corrections = mock(DurableCorrectionLifecycle.class);
  private final CorrectionWorkspaceTransaction workspaceGenerations =
      mock(CorrectionWorkspaceTransaction.class);
  private final RunWorkspaceAuthorityService workspaceAuthorities =
      mock(RunWorkspaceAuthorityService.class);
  private final RunWorkspaceAuthorityService.Lease publicationLease =
      mock(RunWorkspaceAuthorityService.Lease.class);
  private WorkspaceExportService exports;

  @BeforeEach
  void setUp() {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    when(workspaceAuthorities.acquire(
            anyString(), eq(RunWorkspaceAuthorityService.Authority.PUBLICATION)))
        .thenReturn(publicationLease);
    exports =
        new WorkspaceExportService(
            properties,
            stateStore,
            mock(GcsWorkspaceStore.class),
            mock(DevDockerfileGenerator.class),
            mock(WorkspaceRestoreService.class),
            mock(ArtifactPublicationGuard.class),
            generations,
            corrections,
            workspaceGenerations,
            workspaceAuthorities);
  }

  @Test
  void buildExportFailsClosedBeforeReadingAnUnresolvedCandidate() throws Exception {
    String runId = "build-unresolved";
    Path root = Files.createDirectories(workspace.resolve(runId));
    Files.writeString(root.resolve("candidate.txt"), "unaccepted\n");
    when(stateStore.findRun(runId)).thenReturn(Optional.of(run(runId, "BUILD")));
    when(corrections.latestAcceptedGeneration(runId)).thenReturn(Optional.empty());
    doThrow(new IllegalStateException("WORKSPACE_GENERATION_UNRESOLVED"))
        .when(generations)
        .recoverRunOrThrow(runId);

    assertThatThrownBy(() -> exports.exportWorkspace(runId))
        .isInstanceOf(WorkspaceExportService.UnacceptedWorkspaceException.class)
        .hasMessageContaining("WORKSPACE_EXPORT_ACCEPTED_FRONTIER_UNPROVEN")
        .hasMessageContaining("WORKSPACE_GENERATION_UNRESOLVED");

    verify(generations, never())
        .assertAcceptedFrontier(runId, root.toAbsolutePath().normalize(), "WORKSPACE_EXPORT");
  }

  @Test
  void buildExportReadsOnlyAfterRecoveryAndExactFrontierProof() throws Exception {
    String runId = "build-accepted";
    Path root = Files.createDirectories(workspace.resolve(runId));
    Files.writeString(root.resolve("accepted.txt"), "accepted\n");
    when(stateStore.findRun(runId)).thenReturn(Optional.of(run(runId, "BUILD")));
    when(corrections.latestAcceptedGeneration(runId)).thenReturn(Optional.empty());

    Path archive = exports.exportWorkspace(runId);
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      assertThat(zip.getEntry("accepted.txt")).isNotNull();
      assertThat(zip.getEntry("candidate.txt")).isNull();
    } finally {
      Files.deleteIfExists(archive);
    }

    verify(generations).recoverRunOrThrow(runId);
    verify(generations)
        .assertAcceptedFrontier(runId, root.toAbsolutePath().normalize(), "WORKSPACE_EXPORT");
    InOrder authorityOrder = inOrder(workspaceAuthorities, publicationLease, generations);
    authorityOrder
        .verify(workspaceAuthorities)
        .acquire(runId, RunWorkspaceAuthorityService.Authority.PUBLICATION);
    authorityOrder.verify(publicationLease).assertOwned();
    authorityOrder
        .verify(generations)
        .assertAcceptedFrontier(runId, root.toAbsolutePath().normalize(), "WORKSPACE_EXPORT");
    verify(publicationLease).close();
  }

  @Test
  void buildExportAbortsWhenPublicationAuthorityIsLostDuringArchiveRead() throws Exception {
    String runId = "build-lease-lost";
    Path root = Files.createDirectories(workspace.resolve(runId));
    Files.writeString(root.resolve("accepted.txt"), "accepted\n");
    when(stateStore.findRun(runId)).thenReturn(Optional.of(run(runId, "BUILD")));
    when(corrections.latestAcceptedGeneration(runId)).thenReturn(Optional.empty());
    AtomicInteger ownershipChecks = new AtomicInteger();
    org.mockito.Mockito.doAnswer(
            ignored -> {
              if (ownershipChecks.incrementAndGet() >= 3) {
                throw new IllegalStateException("RUN_WORKSPACE_AUTHORITY_LOST");
              }
              return null;
            })
        .when(publicationLease)
        .assertOwned();

    assertThatThrownBy(() -> exports.exportWorkspace(runId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RUN_WORKSPACE_AUTHORITY_LOST");

    verify(generations)
        .assertAcceptedFrontier(runId, root.toAbsolutePath().normalize(), "WORKSPACE_EXPORT");
    verify(publicationLease).close();
  }

  @Test
  void acceptedCorrectionSupersedesTheInitialBuildFrontier() throws Exception {
    String runId = "build-corrected";
    String generation = "c".repeat(64);
    Path root = Files.createDirectories(workspace.resolve(runId));
    Files.writeString(root.resolve("corrected.txt"), "corrected\n");
    when(stateStore.findRun(runId)).thenReturn(Optional.of(run(runId, "BUILD")));
    when(corrections.latestAcceptedGeneration(runId)).thenReturn(Optional.of(generation));
    when(workspaceGenerations.captureGeneration(root.toAbsolutePath().normalize()))
        .thenReturn(generation);

    Path archive = exports.exportWorkspace(runId);
    Files.deleteIfExists(archive);

    verify(corrections).recoverRunOrThrow(runId);
    verify(generations, never()).recoverRunOrThrow(runId);
    verify(generations, never())
        .assertAcceptedFrontier(runId, root.toAbsolutePath().normalize(), "WORKSPACE_EXPORT");
  }

  @Test
  void acceptedCorrectionGenerationMismatchFailsClosedWithoutFallingBackToBuildFrontier()
      throws Exception {
    String runId = "build-correction-mismatch";
    String acceptedGeneration = "c".repeat(64);
    String currentGeneration = "d".repeat(64);
    Path root = Files.createDirectories(workspace.resolve(runId));
    Files.writeString(root.resolve("corrupted.txt"), "not the accepted correction\n");
    when(stateStore.findRun(runId)).thenReturn(Optional.of(run(runId, "BUILD")));
    when(corrections.latestAcceptedGeneration(runId)).thenReturn(Optional.of(acceptedGeneration));
    when(workspaceGenerations.captureGeneration(root.toAbsolutePath().normalize()))
        .thenReturn(currentGeneration);

    assertThatThrownBy(() -> exports.exportWorkspace(runId))
        .isInstanceOf(WorkspaceExportService.UnacceptedWorkspaceException.class)
        .hasMessageContaining("WORKSPACE_EXPORT_ACCEPTED_FRONTIER_UNPROVEN")
        .hasMessageContaining("CORRECTION_GENERATION_FRONTIER_MISMATCH");

    verify(corrections).recoverRunOrThrow(runId);
    verify(generations, never()).recoverRunOrThrow(runId);
    verify(generations, never())
        .assertAcceptedFrontier(runId, root.toAbsolutePath().normalize(), "WORKSPACE_EXPORT");
  }

  @Test
  void patchExportDoesNotAcquireTheBuildGenerationProtocol() throws Exception {
    String runId = "patch-run";
    Path root = Files.createDirectories(workspace.resolve(runId));
    Files.writeString(root.resolve("patch.txt"), "patch\n");
    when(stateStore.findRun(runId)).thenReturn(Optional.of(run(runId, "PATCH")));

    Path archive = exports.exportWorkspace(runId);
    Files.deleteIfExists(archive);

    verify(generations, never()).recoverRunOrThrow(runId);
    verify(generations, never())
        .assertAcceptedFrontier(runId, root.toAbsolutePath().normalize(), "WORKSPACE_EXPORT");
  }

  private static PipelineRun run(String runId, String mode) {
    return PipelineRun.builder().runId(runId).runMode(mode).build();
  }
}
