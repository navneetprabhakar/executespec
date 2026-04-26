package com.navneet.executespec.contracts.drift;

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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        contract = ContractRegistry.builder()
                .id(contractId).repoId(repoId).name("widget-api")
                .type(ContractRegistry.ContractType.OPENAPI.name())
                .currentRegistryVersion(1).currentContentHash("abc")
                .detectedPath("api/openapi.yaml")
                .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name()).build();
        repo = new ProjectRepo();
        repo.setId(repoId);
        repo.setGithubRepoFullName("acme/widget");
        lenient().when(emitterProvider.getIfAvailable()).thenReturn(emitter);
    }

    @Test
    void runCheck_noSnapshot_returnsNoDrift_doesNotPersistEvent() {
        when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
        when(folderWriter.readFile(eq(repo), anyString(), any())).thenReturn(Optional.empty());

        DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

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
                .thenReturn(Optional.of(new ExecutespecFolderWriter.FileSnapshot(content, "blob-sha", hash)));
        when(gitHub.readRepoFile(anyString(), eq("api/openapi.yaml"), any())).thenReturn(content);

        DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

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
        when(folderWriter.readFile(eq(repo), anyString(), any())).thenReturn(Optional.of(
                new ExecutespecFolderWriter.FileSnapshot(snapshotContent, "blob-sha", snapshotHash)));
        when(gitHub.readRepoFile(anyString(), eq("api/openapi.yaml"), any())).thenReturn(sourceContent);
        when(driftRepo.save(any(ContractDriftEvent.class))).thenAnswer(inv -> {
            ContractDriftEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });

        DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

        assertThat(r.hasDrift()).isTrue();
        assertThat(r.eventId()).isNotNull();
        verify(driftRepo).save(any(ContractDriftEvent.class));
        verify(emitter).emit(eq(projectId), eq(ActivityEventType.CONTRACT_DRIFT_DETECTED),
                isNull(), eq("CONTRACT"), eq(contractId.toString()), anyString(), anyMap());
    }

    @Test
    void runCheck_missingSourcePath_returnsNoDrift() {
        contract.setDetectedPath(null);
        when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
        when(folderWriter.readFile(any(), anyString(), any())).thenReturn(Optional.of(
                new ExecutespecFolderWriter.FileSnapshot("x", "y", "z")));

        DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

        assertThat(r.hasDrift()).isFalse();
        assertThat(r.summary()).contains("no detected source path");
    }

    @Test
    void runCheck_sourceReadFails_returnsNoDrift_doesNotPersist() {
        when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
        when(folderWriter.readFile(any(), anyString(), any())).thenReturn(Optional.of(
                new ExecutespecFolderWriter.FileSnapshot("x", "y", "z")));
        when(gitHub.readRepoFile(anyString(), anyString(), any()))
                .thenReturn("// File not found on GitHub (status 404)");

        DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

        assertThat(r.hasDrift()).isFalse();
        assertThat(r.summary()).contains("could not read source");
        verifyNoInteractions(driftRepo);
    }

    @Test
    void runCheck_repoNotFound_returnsNoDrift() {
        when(projectRepoRepo.findById(repoId)).thenReturn(Optional.empty());

        DriftDetectionService.DriftCheckResult r = service.runCheck(contract, projectId, "ghp_x");

        assertThat(r.hasDrift()).isFalse();
        assertThat(r.summary()).contains("repo not found");
    }

    @Test
    void extensionFor_handlesAllContractTypes() {
        assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.OPENAPI)).isEqualTo(".yaml");
        assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.PROTO)).isEqualTo(".proto");
        assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.GRAPHQL)).isEqualTo(".graphql");
        assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.JSON_SCHEMA)).isEqualTo(".schema.json");
        assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.ASYNCAPI)).isEqualTo(".yaml");
        assertThat(DriftDetectionService.extensionFor(ContractRegistry.ContractType.EVENT_SCHEMA)).isEqualTo(".json");
    }
}
