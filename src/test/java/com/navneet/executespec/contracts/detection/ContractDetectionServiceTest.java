package com.navneet.executespec.contracts.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contracts.ContractVersion;
import com.navneet.executespec.contracts.ContractVersionRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tests for {@link ContractDetectionService} — focused on the upsert decision tree (first detection
 * vs no-change vs version bump) + activity event emission contract.
 */
@ExtendWith(MockitoExtension.class)
class ContractDetectionServiceTest {

  @Mock ContractRegistryRepository registryRepo;
  @Mock ContractVersionRepository versionRepo;
  @Mock ProjectRepoRepository projectRepoRepo;
  @Mock ActivityEventEmitter emitter;
  @Mock ObjectProvider<ActivityEventEmitter> emitterProvider;

  ContractDetectionService service;
  ProjectRepo repo;
  UUID repoId;
  UUID projectId;

  @BeforeEach
  void setUp() {
    // Use the real OpenApiFileDetector; the rest are stubbed implicitly
    // by passing only one detector to the service constructor.
    service =
        new ContractDetectionService(
            List.of(new OpenApiFileDetector()),
            registryRepo,
            versionRepo,
            projectRepoRepo,
            emitterProvider);
    repoId = UUID.randomUUID();
    projectId = UUID.randomUUID();
    repo = new ProjectRepo();
    repo.setId(repoId);
    repo.setGithubRepoFullName("acme/widget");
    // lenient — some tests short-circuit before the stubs get used
    // (entity-not-found path, file-list failure, no-change skip).
    // Strict stubbing would flag those as UnnecessaryStubbing.
    org.mockito.Mockito.lenient()
        .when(projectRepoRepo.findById(repoId))
        .thenReturn(Optional.of(repo));
    org.mockito.Mockito.lenient().when(emitterProvider.getIfAvailable()).thenReturn(emitter);
  }

  @Test
  void firstDetection_insertsRegistryAndVersionAndEmitsDetectedEvent() {
    when(registryRepo.findByRepoIdAndName(eq(repoId), eq("openapi"))).thenReturn(Optional.empty());
    when(registryRepo.save(any(ContractRegistry.class)))
        .thenAnswer(
            inv -> {
              ContractRegistry c = inv.getArgument(0);
              if (c.getId() == null) c.setId(UUID.randomUUID());
              return c;
            });

    Map<String, String> files = Map.of("openapi.yaml", "openapi: 3.0.0\ninfo:\n  version: 1.0.0\n");
    service.runForRepo(repoId, projectId, () -> List.copyOf(files.keySet()), files::get);

    ArgumentCaptor<ContractRegistry> savedRegistry =
        ArgumentCaptor.forClass(ContractRegistry.class);
    verify(registryRepo).save(savedRegistry.capture());
    assertThat(savedRegistry.getValue().getCurrentRegistryVersion()).isEqualTo(1);
    assertThat(savedRegistry.getValue().detectionMethodEnum())
        .isEqualTo(ContractRegistry.DetectionMethod.AUTO_FILE);
    verify(versionRepo).save(any(ContractVersion.class));
    verify(emitter)
        .emit(
            eq(projectId),
            eq(ActivityEventType.CONTRACT_DETECTED),
            isNull(),
            eq("CONTRACT"),
            anyString(),
            anyString(),
            anyMap());
  }

  @Test
  void unchangedHash_skipsSaveAndDoesNotEmit() {
    ContractRegistry existing =
        ContractRegistry.builder()
            .id(UUID.randomUUID())
            .repoId(repoId)
            .name("openapi")
            .type(ContractRegistry.ContractType.OPENAPI.name())
            .currentRegistryVersion(2)
            .currentContentHash(DetectorUtils.sha256("openapi: 3.0.0\ninfo:\n  version: 1.0.0\n"))
            .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
            .build();
    when(registryRepo.findByRepoIdAndName(eq(repoId), eq("openapi")))
        .thenReturn(Optional.of(existing));

    Map<String, String> files = Map.of("openapi.yaml", "openapi: 3.0.0\ninfo:\n  version: 1.0.0\n");
    service.runForRepo(repoId, projectId, () -> List.copyOf(files.keySet()), files::get);

    verify(registryRepo, never()).save(any());
    verify(versionRepo, never()).save(any());
    verifyNoInteractions(emitter);
  }

  @Test
  void changedHash_bumpsVersionAndEmitsVersionChangedEvent() {
    ContractRegistry existing =
        ContractRegistry.builder()
            .id(UUID.randomUUID())
            .repoId(repoId)
            .name("openapi")
            .type(ContractRegistry.ContractType.OPENAPI.name())
            .currentRegistryVersion(2)
            .currentContentHash("OLDHASH")
            .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
            .build();
    when(registryRepo.findByRepoIdAndName(eq(repoId), eq("openapi")))
        .thenReturn(Optional.of(existing));
    when(registryRepo.save(any(ContractRegistry.class))).thenAnswer(inv -> inv.getArgument(0));

    Map<String, String> files = Map.of("openapi.yaml", "openapi: 3.0.0\ninfo:\n  version: 2.0.0\n");
    service.runForRepo(repoId, projectId, () -> List.copyOf(files.keySet()), files::get);

    ArgumentCaptor<ContractRegistry> savedRegistry =
        ArgumentCaptor.forClass(ContractRegistry.class);
    verify(registryRepo).save(savedRegistry.capture());
    assertThat(savedRegistry.getValue().getCurrentRegistryVersion()).isEqualTo(3);
    verify(versionRepo).save(any(ContractVersion.class));
    verify(emitter)
        .emit(
            eq(projectId),
            eq(ActivityEventType.CONTRACT_VERSION_CHANGED),
            any(),
            any(),
            any(),
            anyString(),
            anyMap());
  }

  @Test
  void detectorFailure_isCapturedInResultButDoesNotAbortRun() {
    FileBasedContractDetector failingDetector =
        new FileBasedContractDetector() {
          @Override
          public String detectorName() {
            return "FailingDetector";
          }

          @Override
          public ContractRegistry.ContractType type() {
            return ContractRegistry.ContractType.PROTO;
          }

          @Override
          public java.util.List<String> filePatterns() {
            return java.util.List.of("**/*.proto");
          }

          @Override
          public java.util.List<DetectedContract> detect(
              java.util.List<String> p, ContentLoader l) {
            throw new RuntimeException("internal explosion");
          }
        };
    ContractDetectionService svc =
        new ContractDetectionService(
            java.util.List.of(failingDetector),
            registryRepo,
            versionRepo,
            projectRepoRepo,
            emitterProvider);

    Map<String, String> files = Map.of("a.proto", "syntax = \"proto3\";");
    java.util.List<ContractDetectionService.DetectionResult> summary =
        svc.runForRepo(repoId, projectId, () -> List.copyOf(files.keySet()), files::get);

    assertThat(summary)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.status())
                  .isEqualTo(ContractDetectionService.DetectionResult.Status.FAILED);
              assertThat(r.failureReason()).contains("internal explosion");
            });
  }

  @Test
  void repoNotFound_returnsEmptyAndDoesNotCallDetectors() {
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.empty());
    java.util.List<ContractDetectionService.DetectionResult> summary =
        service.runForRepo(
            repoId, projectId, () -> java.util.List.of("openapi.yaml"), p -> "openapi: 3.0.0");
    assertThat(summary).isEmpty();
  }

  @Test
  void fileListerFailure_returnsEmptyButDoesNotThrow() {
    java.util.List<ContractDetectionService.DetectionResult> summary =
        service.runForRepo(
            repoId,
            projectId,
            () -> {
              throw new RuntimeException("GH 503");
            },
            p -> null);
    assertThat(summary).isEmpty();
  }
}
