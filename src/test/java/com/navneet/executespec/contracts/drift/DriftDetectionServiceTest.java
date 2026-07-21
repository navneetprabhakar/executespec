package com.navneet.executespec.contracts.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.contracts.ContractDriftEvent;
import com.navneet.executespec.contracts.ContractDriftEventRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contracts.detection.DetectorUtils;
import com.navneet.executespec.folder.ExecutespecFolderWriter;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DriftDetectionServiceTest {

  @Mock ContractRegistryRepository registryRepo;
  @Mock ContractDriftEventRepository driftRepo;
  @Mock ProjectRepoRepository projectRepoRepo;
  @Mock ExecutespecFolderWriter folderWriter;
  @Mock GitHubRepoService gitHub;
  @Mock ActivityEventEmitter emitter;
  @Mock ObjectProvider<ActivityEventEmitter> emitterProvider;

  @InjectMocks DriftDetectionService service;

  UUID contractId;
  UUID repoId;
  UUID projectId;
  ContractRegistry contract;
  ProjectRepo repo;

  @BeforeEach
  void setUp() {
    contractId = UUID.randomUUID();
    repoId = UUID.randomUUID();
    projectId = UUID.randomUUID();
    contract =
        ContractRegistry.builder()
            .id(contractId)
            .repoId(repoId)
            .name("widget-api")
            .type(ContractRegistry.ContractType.OPENAPI.name())
            .currentRegistryVersion(1)
            .currentContentHash("abc")
            .detectedPath("api/openapi.yaml")
            .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
            .build();
    repo = new ProjectRepo();
    repo.setId(repoId);
    repo.setGithubRepoFullName("acme/widget");
    lenient().when(emitterProvider.getIfAvailable()).thenReturn(emitter);
  }

  @Test
  void runCheck_noSnapshot_isUnavailable_notVerifiedClean() {
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(folderWriter.readFile(eq(repo), anyString(), any())).thenReturn(Optional.empty());

    DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

    // RR-1631 — with no snapshot there is nothing to compare against, so the check is
    // UNAVAILABLE, not a verified "no drift".
    assertThat(r.status()).isEqualTo(DriftDetectionService.Status.UNAVAILABLE);
    assertThat(r.hasDrift()).isFalse();
    assertThat(r.summary()).contains("no .executespec/ snapshot");
    verify(driftRepo, never()).save(any());
  }

  @Test
  void runCheck_matchingHashes_returnsNoDrift_quietSuccess() {
    String content = "openapi: 3.0\ninfo:\n  version: 1.0.0\n";
    String hash = DetectorUtils.sha256(content);
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(folderWriter.readFile(eq(repo), anyString(), any()))
        .thenReturn(
            Optional.of(new ExecutespecFolderWriter.FileSnapshot(content, "blob-sha", hash)));
    when(gitHub.readRepoFile(anyString(), eq("api/openapi.yaml"), any())).thenReturn(content);

    DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

    assertThat(r.status()).isEqualTo(DriftDetectionService.Status.NO_DRIFT);
    assertThat(r.hasDrift()).isFalse();
    assertThat(r.summary()).isEqualTo("no drift");
    verifyNoInteractions(driftRepo, emitter);
  }

  @Test
  void runCheck_differentHashes_persistsEventAndEmitsActivity() {
    String snapshotContent = "openapi: 3.0\ninfo:\n  version: 1.0.0\n";
    String snapshotHash = DetectorUtils.sha256(snapshotContent);
    String sourceContent = "openapi: 3.0\ninfo:\n  version: 2.0.0\n"; // changed
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(folderWriter.readFile(eq(repo), anyString(), any()))
        .thenReturn(
            Optional.of(
                new ExecutespecFolderWriter.FileSnapshot(
                    snapshotContent, "blob-sha", snapshotHash)));
    when(gitHub.readRepoFile(anyString(), eq("api/openapi.yaml"), any())).thenReturn(sourceContent);
    when(driftRepo.save(any(ContractDriftEvent.class)))
        .thenAnswer(
            inv -> {
              ContractDriftEvent e = inv.getArgument(0);
              if (e.getId() == null) e.setId(UUID.randomUUID());
              return e;
            });

    DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

    assertThat(r.status()).isEqualTo(DriftDetectionService.Status.DRIFT);
    assertThat(r.hasDrift()).isTrue();
    assertThat(r.eventId()).isNotNull();
    verify(driftRepo).save(any(ContractDriftEvent.class));
    verify(emitter)
        .emit(
            eq(projectId),
            eq(ActivityEventType.CONTRACT_DRIFT_DETECTED),
            isNull(),
            eq("CONTRACT"),
            eq(contractId.toString()),
            anyString(),
            anyMap());
  }

  @Test
  void runCheck_sameUnresolvedDiff_reusesEventWithoutDuplicateWriteOrActivity() {
    String snapshotContent = "openapi: 3.0\ninfo:\n  version: 1.0.0\n";
    String snapshotHash = DetectorUtils.sha256(snapshotContent);
    String sourceContent = "openapi: 3.0\ninfo:\n  version: 2.0.0\n";
    String sourceHash = DetectorUtils.sha256(sourceContent);
    UUID existingId = UUID.randomUUID();
    ContractDriftEvent existing =
        ContractDriftEvent.builder()
            .id(existingId)
            .contractId(contractId)
            .sourceDiff(
                "content hash differs: snapshot="
                    + snapshotHash.substring(0, 12)
                    + " vs source="
                    + sourceHash.substring(0, 12))
            .build();
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(folderWriter.readFile(eq(repo), anyString(), any()))
        .thenReturn(
            Optional.of(
                new ExecutespecFolderWriter.FileSnapshot(
                    snapshotContent, "blob-sha", snapshotHash)));
    when(gitHub.readRepoFile(anyString(), eq("api/openapi.yaml"), any())).thenReturn(sourceContent);
    when(driftRepo.findUnresolvedByContractId(contractId)).thenReturn(List.of(existing));

    DriftDetectionService.DriftCheckResult result = service.runCheck(contract, projectId, "ghp_x");

    assertThat(result.hasDrift()).isTrue();
    assertThat(result.eventId()).isEqualTo(existingId);
    verify(driftRepo, never()).save(any());
    verifyNoInteractions(emitter);
  }

  @Test
  void runCheck_missingSourcePath_isUnavailable() {
    contract.setDetectedPath(null);
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(folderWriter.readFile(any(), anyString(), any()))
        .thenReturn(Optional.of(new ExecutespecFolderWriter.FileSnapshot("x", "y", "z")));

    DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

    assertThat(r.status()).isEqualTo(DriftDetectionService.Status.UNAVAILABLE);
    assertThat(r.hasDrift()).isFalse();
    assertThat(r.summary()).contains("no detected source path");
  }

  @Test
  void runCheck_sourceReadFails_isUnavailable_notNoDrift_doesNotPersist() {
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(folderWriter.readFile(any(), anyString(), any()))
        .thenReturn(Optional.of(new ExecutespecFolderWriter.FileSnapshot("x", "y", "z")));
    when(gitHub.readRepoFile(anyString(), anyString(), any()))
        .thenReturn("// File not found on GitHub (status 404)");

    DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

    // RR-1631 — a GitHub failure used to collapse into the same hasDrift=false as a genuine
    // match, so an outage / expired PAT silently read as "no drift".
    assertThat(r.status()).isEqualTo(DriftDetectionService.Status.UNAVAILABLE);
    assertThat(r.hasDrift()).isFalse();
    assertThat(r.summary()).contains("could not read source");
    verifyNoInteractions(driftRepo);
  }

  @Test
  void runCheck_repoNotFound_isUnavailable() {
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.empty());

    DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

    assertThat(r.status()).isEqualTo(DriftDetectionService.Status.UNAVAILABLE);
    assertThat(r.hasDrift()).isFalse();
    assertThat(r.summary()).contains("repo not found");
  }

  /**
   * RR-1631 — the old guard was {@code sourceContent.startsWith("// ")}, which misclassified a
   * perfectly valid proto / GraphQL / C-style file whose first line is a copyright comment as an
   * unreadable one. That made drift on such contracts permanently undetectable AND made refresh
   * throw BAD_GATEWAY forever, so they could never be repaired either.
   */
  @Test
  void runCheck_sourceStartingWithLineComment_isReadNormally_andDriftIsDetected() {
    String snapshotContent = "// Copyright 2026 Acme\nsyntax = \"proto3\";\nmessage A {}\n";
    String snapshotHash = DetectorUtils.sha256(snapshotContent);
    String sourceContent =
        "// Copyright 2026 Acme\nsyntax = \"proto3\";\nmessage A { int32 b = 1; }\n";
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(folderWriter.readFile(eq(repo), anyString(), any()))
        .thenReturn(
            Optional.of(
                new ExecutespecFolderWriter.FileSnapshot(
                    snapshotContent, "blob-sha", snapshotHash)));
    when(gitHub.readRepoFile(anyString(), eq("api/openapi.yaml"), any())).thenReturn(sourceContent);
    when(driftRepo.findUnresolvedByContractId(any())).thenReturn(java.util.List.of());
    when(driftRepo.save(any(ContractDriftEvent.class)))
        .thenAnswer(
            inv -> {
              ContractDriftEvent e = inv.getArgument(0);
              if (e.getId() == null) e.setId(UUID.randomUUID());
              return e;
            });

    DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

    assertThat(r.status()).isEqualTo(DriftDetectionService.Status.DRIFT);
    assertThat(r.hasDrift()).isTrue();
  }

  @Test
  void runCheck_sourceStartingWithLineComment_matchingHashes_isVerifiedClean() {
    String content = "// Copyright 2026 Acme\ntype Query { hello: String }\n";
    String hash = DetectorUtils.sha256(content);
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(folderWriter.readFile(eq(repo), anyString(), any()))
        .thenReturn(
            Optional.of(new ExecutespecFolderWriter.FileSnapshot(content, "blob-sha", hash)));
    when(gitHub.readRepoFile(anyString(), eq("api/openapi.yaml"), any())).thenReturn(content);

    DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

    assertThat(r.status()).isEqualTo(DriftDetectionService.Status.NO_DRIFT);
  }

  @Test
  void isSourceReadFailure_matchesEveryReaderSentinel_butNotOrdinaryComments() {
    // Verbatim from GitHubRepoService.readRepoFile / decodeGitHubContent.
    assertThat(DriftDetectionService.isSourceReadFailure(null)).isTrue();
    assertThat(DriftDetectionService.isSourceReadFailure("// No GitHub PAT configured")).isTrue();
    assertThat(DriftDetectionService.isSourceReadFailure("// Invalid repo URL")).isTrue();
    assertThat(
            DriftDetectionService.isSourceReadFailure("// File not found on GitHub (status 404)"))
        .isTrue();
    assertThat(DriftDetectionService.isSourceReadFailure("// Error reading file from GitHub: boom"))
        .isTrue();
    assertThat(DriftDetectionService.isSourceReadFailure("// No content field in response"))
        .isTrue();
    assertThat(DriftDetectionService.isSourceReadFailure("// Failed to decode file content: x"))
        .isTrue();

    assertThat(
            DriftDetectionService.isSourceReadFailure("// Copyright 2026 Acme\nsyntax=\"proto3\";"))
        .isFalse();
    assertThat(
            DriftDetectionService.isSourceReadFailure("// Code generated by protoc. DO NOT EDIT."))
        .isFalse();
    assertThat(DriftDetectionService.isSourceReadFailure("openapi: 3.0.0")).isFalse();
  }

  @Test
  void extensionFor_handlesAllContractTypes() {
    assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.OPENAPI))
        .isEqualTo(".yaml");
    assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.PROTO))
        .isEqualTo(".proto");
    assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.GRAPHQL))
        .isEqualTo(".graphql");
    assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.JSON_SCHEMA))
        .isEqualTo(".schema.json");
    assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.ASYNCAPI))
        .isEqualTo(".yaml");
    assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.EVENT_SCHEMA))
        .isEqualTo(".json");
  }
}
