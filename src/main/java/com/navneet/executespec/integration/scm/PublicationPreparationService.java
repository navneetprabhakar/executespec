package com.navneet.executespec.integration.scm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.navneet.executespec.workspacegeneration.BuildProtocolVersion;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Creates exact Git commit objects under durable publication-preparation authority.
 *
 * <p>This service never invokes Git, modifies the worktree/index, moves a local ref, contacts an
 * SCM provider, or treats a best-effort commit as success. Product bytes are re-attested before and
 * after every content-addressed object write; only a complete set of immutable per-repository
 * receipts can settle the preparation as PREPARED.
 */
@Service
@RequiredArgsConstructor
public class PublicationPreparationService {

  private final PublicationPreparationStore store;
  private final PublicationTargetResolver targetResolver;
  private final NonExecutingGitTreeWriter gitObjects;
  private final RunWorkspaceAuthorityService workspaceAuthorities;
  private final CorrectionWorkspaceTransaction workspaceGenerations;
  private final ValidationReportRepository validationReports;
  private final ObjectMapper objectMapper;

  public PreparationResult prepare(PipelineRun run, Path workspace) {
    requireSharedBuild(run);
    requireFrontier(run);
    if (workspace == null || !Files.isDirectory(workspace)) {
      throw new IllegalStateException("PUBLICATION_PREPARATION_WORKSPACE_UNAVAILABLE");
    }
    ValidationReport report =
        validationReports
            .findByRunId(run.getRunId())
            .orElseThrow(
                () -> new IllegalStateException("PUBLICATION_PREPARATION_VALIDATION_MISSING"));
    requireValidation(run, report);
    ValidationEvidence validationEvidence = validationEvidence(report);
    String evidenceDigest = validationEvidence.digest();
    try (RunWorkspaceAuthorityService.Lease lease =
        workspaceAuthorities.acquire(
            run.getRunId(), RunWorkspaceAuthorityService.Authority.PUBLICATION_PREPARE)) {
      assertFrontier(run, workspace, lease);
      var existing =
          store.findPrepared(run.getRunId(), run.getAcceptedWorkspaceGeneration(), evidenceDigest);
      if (existing.isPresent()) {
        List<PublicationPreparationStore.PreparedRepository> repositories =
            store.preparedRepositories(existing.get().preparationId());
        ensurePreparedObjects(run, workspace, repositories, lease);
        return new PreparationResult(
            existing.get().preparationId(), evidenceDigest, repositories, true);
      }
      List<PublicationTargetResolver.Target> targets = targetResolver.resolve(run);
      Instant committedAt = Instant.now();
      List<PublicationPreparationStore.RepositoryIntent> repositoryIntents = new ArrayList<>();
      for (PublicationTargetResolver.Target target : targets) {
        repositoryIntents.add(
            new PublicationPreparationStore.RepositoryIntent(
                UUID.randomUUID(),
                target.repositoryIdentity(),
                sha256(target.repositoryIdentity()),
                target.relativeWorktreePath(),
                target.publicationBranch(),
                target.provisionRequired(),
                target.commitMessage(),
                sha256(target.commitMessage()),
                committedAt));
      }
      PublicationPreparationStore.PreparationIntent intent =
          new PublicationPreparationStore.PreparationIntent(
              run.getRunId(),
              run.getAcceptedWorkspaceGeneration(),
              run.getAcceptedGenerationAttemptId(),
              run.getAcceptedCheckpointKind(),
              run.getAcceptedCheckpointKey(),
              run.getAcceptedCheckpointDigest(),
              run.getAcceptedCheckpointCompletedAt(),
              report.getId(),
              evidenceDigest,
              validationEvidence.json(),
              report.getUpdatedAt(),
              lease.ownerToken());
      UUID preparationId = store.admit(intent, repositoryIntents);
      try {
        for (PublicationPreparationStore.RepositoryIntent repository :
            store.admittedRepositories(preparationId)) {
          assertFrontier(run, workspace, lease);
          Path repoRoot = resolveRepository(workspace, repository.relativeWorktreePath());
          NonExecutingGitTreeWriter.PreparedCommit prepared =
              gitObjects.prepareCommit(
                  repoRoot,
                  repository.commitMessage(),
                  repository.committedAt(),
                  lease::assertOwned);
          assertFrontier(run, workspace, lease);
          store.recordPreparedRepository(
              repository.id(),
              prepared.parentCommitId(),
              prepared.treeId(),
              prepared.commitId(),
              prepared.hasChanges());
        }
        assertFrontier(run, workspace, lease);
        store.markPrepared(preparationId);
      } catch (RuntimeException failure) {
        store.fail(preparationId, failureCode(failure), false);
        throw failure;
      }
      return new PreparationResult(
          preparationId, evidenceDigest, store.preparedRepositories(preparationId), false);
    }
  }

  public RequiredPreparation requirePrepared(PipelineRun run) {
    requireSharedBuild(run);
    ValidationReport report =
        validationReports
            .findByRunId(run.getRunId())
            .orElseThrow(
                () -> new IllegalStateException("PUBLICATION_PREPARATION_VALIDATION_MISSING"));
    String evidenceDigest = validationEvidence(report).digest();
    PublicationPreparationStore.PreparedPublication preparation =
        store
            .findPrepared(run.getRunId(), run.getAcceptedWorkspaceGeneration(), evidenceDigest)
            .orElseThrow(
                () -> new IllegalStateException("PUBLICATION_PREPARATION_RECEIPT_MISSING"));
    List<PublicationPreparationStore.PreparedRepository> repositories =
        store.preparedRepositories(preparation.preparationId());
    if (repositories.isEmpty()) {
      throw new IllegalStateException("PUBLICATION_PREPARATION_REPOSITORIES_MISSING");
    }
    return new RequiredPreparation(preparation, repositories);
  }

  public String validationEvidenceDigest(ValidationReport report) {
    return validationEvidence(report).digest();
  }

  /**
   * Reconstructs content-addressed commit objects after a workspace/checkpoint restore and proves
   * that the reconstructed identities are byte-for-byte equal to the durable receipt. This does not
   * move HEAD, mutate the index, or contact the provider.
   */
  private void ensurePreparedObjects(
      PipelineRun run,
      Path workspace,
      List<PublicationPreparationStore.PreparedRepository> repositories,
      RunWorkspaceAuthorityService.Lease lease) {
    if (repositories.isEmpty()) {
      throw new IllegalStateException("PUBLICATION_PREPARATION_REPOSITORIES_MISSING");
    }
    assertFrontier(run, workspace, lease);
    for (PublicationPreparationStore.PreparedRepository receipt : repositories) {
      Path repository = resolveRepository(workspace, receipt.relativeWorktreePath());
      NonExecutingGitTreeWriter.PreparedCommit reconstructed =
          gitObjects.prepareCommit(
              repository, receipt.commitMessage(), receipt.committedAt(), lease::assertOwned);
      if (!receipt.parentCommitId().equals(reconstructed.parentCommitId())
          || !receipt.treeId().equals(reconstructed.treeId())
          || !receipt.commitId().equals(reconstructed.commitId())
          || receipt.hasChanges() != reconstructed.hasChanges()) {
        throw new IllegalStateException("PUBLICATION_PREPARED_OBJECT_IDENTITY_MISMATCH");
      }
      assertFrontier(run, workspace, lease);
    }
  }

  private ValidationEvidence validationEvidence(ValidationReport report) {
    if (report == null || report.getUpdatedAt() == null) {
      throw new IllegalStateException("PUBLICATION_VALIDATION_IDENTITY_INCOMPLETE");
    }
    try {
      LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("id", report.getId());
      evidence.put("runId", report.getRunId());
      evidence.put("workspaceGeneration", report.getWorkspaceGeneration());
      evidence.put("updatedAt", report.getUpdatedAt().toString());
      evidence.put("status", report.getStatus());
      evidence.put("pipelineResult", report.getPipelineResult());
      evidence.put("runtimeSurface", report.getRuntimeSurface());
      evidence.put("finalBuildPassed", report.getFinalBuildPassed());
      evidence.put("buildCommand", report.getBuildCommand());
      evidence.put("buildExitCode", report.getBuildExitCode());
      evidence.put("testExecutionVerified", report.getTestExecutionVerified());
      evidence.put("testEvidenceScope", report.getTestEvidenceScope());
      evidence.put("testCommand", report.getTestCommand());
      evidence.put("testExitCode", report.getTestExitCode());
      evidence.put("testsPassed", report.getTestsPassed());
      evidence.put("testsSkipped", report.getTestsSkipped());
      evidence.put("testsTotal", report.getTestsTotal());
      evidence.put("workflowConfigEvidence", report.getWorkflowConfigEvidenceJson());
      String canonical = objectMapper.writeValueAsString(evidence);
      return new ValidationEvidence(canonical, sha256(canonical));
    } catch (Exception failure) {
      throw new IllegalStateException("PUBLICATION_VALIDATION_DIGEST_FAILED", failure);
    }
  }

  private void assertFrontier(
      PipelineRun run, Path workspace, RunWorkspaceAuthorityService.Lease lease) {
    lease.assertOwned();
    String current = workspaceGenerations.captureGeneration(workspace);
    if (!run.getAcceptedWorkspaceGeneration().equals(current)) {
      throw new IllegalStateException("PUBLICATION_PREPARATION_FRONTIER_MISMATCH");
    }
  }

  private static Path resolveRepository(Path workspace, String relative) {
    Path root = workspace.toAbsolutePath().normalize();
    Path resolved = root.resolve(relative).normalize();
    if (!resolved.startsWith(root) || !Files.isDirectory(resolved.resolve(".git"))) {
      throw new IllegalStateException("PUBLICATION_PREPARATION_REPOSITORY_UNAVAILABLE");
    }
    return resolved;
  }

  private static void requireSharedBuild(PipelineRun run) {
    if (run == null || !RunMode.BUILD.equals(run.getRunMode())) {
      throw new IllegalStateException("PUBLICATION_PREPARATION_BUILD_REQUIRED");
    }
    if (BuildProtocolVersion.parse(run.getBuildProtocolVersion())
        != BuildProtocolVersion.SHARED_KERNEL_V2) {
      throw new IllegalStateException("PUBLICATION_PREPARATION_PROTOCOL_V2_REQUIRED");
    }
  }

  private static void requireFrontier(PipelineRun run) {
    if (!hash(run.getAcceptedWorkspaceGeneration())
        || !hash(run.getAcceptedCheckpointDigest())
        || run.getAcceptedCheckpointKind() == null
        || run.getAcceptedCheckpointKind().isBlank()
        || run.getAcceptedCheckpointKey() == null
        || run.getAcceptedCheckpointKey().isBlank()
        || run.getAcceptedCheckpointCompletedAt() == null) {
      throw new IllegalStateException("PUBLICATION_PREPARATION_FRONTIER_INCOMPLETE");
    }
  }

  private static void requireValidation(PipelineRun run, ValidationReport report) {
    if (!run.getRunId().equals(report.getRunId())
        || !"COMPLETE".equalsIgnoreCase(report.getStatus())
        || PipelineResult.FAILED.equals(report.getPipelineResult())
        || !run.getAcceptedWorkspaceGeneration().equals(report.getWorkspaceGeneration())
        || report.getUpdatedAt() == null) {
      throw new IllegalStateException("PUBLICATION_PREPARATION_VALIDATION_UNBOUND");
    }
  }

  private static boolean hash(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private static String failureCode(RuntimeException failure) {
    String message = failure.getMessage();
    if (message != null && message.matches("[A-Z][A-Z0-9_]{2,79}")) return message;
    return "PUBLICATION_PREPARATION_FAILED";
  }

  public static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  public record PreparationResult(
      UUID preparationId,
      String validationEvidenceDigest,
      List<PublicationPreparationStore.PreparedRepository> repositories,
      boolean replay) {}

  public record RequiredPreparation(
      PublicationPreparationStore.PreparedPublication preparation,
      List<PublicationPreparationStore.PreparedRepository> repositories) {}

  private record ValidationEvidence(String json, String digest) {}
}
