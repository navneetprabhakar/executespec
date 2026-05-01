package com.navneet.executespec.contracts.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.contracts.ContractConsumer;
import com.navneet.executespec.contracts.ContractConsumerRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contracts.breaking.BreakingChangeService;
import com.navneet.executespec.contracts.breaking.ContractDiff;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanBreakingChangeReportTest {

  @Mock BreakingChangeService breakingChangeService;
  @Mock ContractRegistryRepository registryRepo;
  @Mock ContractConsumerRepository consumerRepo;
  @Mock ProjectRepoRepository projectRepoRepo;

  @InjectMocks PlanBreakingChangeReport report;

  String projectId;
  UUID contractId;
  UUID consumerRepoId;
  ContractRegistry contract;

  @BeforeEach
  void setUp() {
    projectId = UUID.randomUUID().toString();
    contractId = UUID.randomUUID();
    consumerRepoId = UUID.randomUUID();
    contract =
        ContractRegistry.builder()
            .id(contractId)
            .name("payments-api")
            .type(ContractRegistry.ContractType.OPENAPI.name())
            .currentRegistryVersion(1)
            .currentContentHash("h")
            .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
            .build();
  }

  @Test
  void emptyInput_returnsEmptyReport() {
    PlanBreakingChangeReport.Report r = report.compose(projectId, List.of());
    assertThat(r.breakingChanges()).isEmpty();
    assertThat(r.requiresTypedAck()).isFalse();
  }

  @Test
  void breaking_with_consumer_triggersGateFriction() {
    when(registryRepo.findById(contractId)).thenReturn(Optional.of(contract));
    when(breakingChangeService.classify(any(), any(), any()))
        .thenReturn(breakingDiff("currency removed"));
    when(consumerRepo.findByContractIdAndDeclaredInProjectId(contractId, projectId))
        .thenReturn(
            List.of(
                ContractConsumer.builder()
                    .id(UUID.randomUUID())
                    .contractId(contractId)
                    .consumerRepoId(consumerRepoId)
                    .declaredInProjectId(projectId)
                    .build()));
    ProjectRepo cr = new ProjectRepo();
    cr.setId(consumerRepoId);
    cr.setGithubRepoFullName("acme/web");
    when(projectRepoRepo.findById(consumerRepoId)).thenReturn(Optional.of(cr));

    PlanBreakingChangeReport.Report r =
        report.compose(
            projectId,
            List.of(new PlanBreakingChangeReport.IntendedChange(contractId, "old", "new")));

    assertThat(r.requiresTypedAck()).isTrue();
    assertThat(r.breakingChanges()).hasSize(1);
    assertThat(r.breakingChanges().get(0).affectedConsumers()).contains("acme/web");
    assertThat(r.breakingChanges().get(0).requiresFriction()).isTrue();
  }

  @Test
  void breaking_without_consumer_recordedButNoFriction() {
    when(registryRepo.findById(contractId)).thenReturn(Optional.of(contract));
    when(breakingChangeService.classify(any(), any(), any()))
        .thenReturn(breakingDiff("currency removed"));
    when(consumerRepo.findByContractIdAndDeclaredInProjectId(contractId, projectId))
        .thenReturn(List.of());

    PlanBreakingChangeReport.Report r =
        report.compose(
            projectId,
            List.of(new PlanBreakingChangeReport.IntendedChange(contractId, "old", "new")));

    assertThat(r.requiresTypedAck()).isFalse();
    assertThat(r.breakingChanges()).hasSize(1);
    assertThat(r.breakingChanges().get(0).requiresFriction()).isFalse();
    assertThat(r.breakingChanges().get(0).affectedConsumers()).isEmpty();
  }

  @Test
  void nonBreakingChange_excludedFromReport() {
    when(registryRepo.findById(contractId)).thenReturn(Optional.of(contract));
    when(breakingChangeService.classify(any(), any(), any()))
        .thenReturn(nonBreakingDiff("optional field added"));

    PlanBreakingChangeReport.Report r =
        report.compose(
            projectId,
            List.of(new PlanBreakingChangeReport.IntendedChange(contractId, "old", "new")));

    assertThat(r.breakingChanges()).isEmpty();
    assertThat(r.requiresTypedAck()).isFalse();
  }

  @Test
  void unknownContract_skipped_doesNotCrash() {
    when(registryRepo.findById(contractId)).thenReturn(Optional.empty());

    PlanBreakingChangeReport.Report r =
        report.compose(
            projectId,
            List.of(new PlanBreakingChangeReport.IntendedChange(contractId, "old", "new")));

    assertThat(r.breakingChanges()).isEmpty();
    verifyNoInteractions(breakingChangeService);
  }

  @Test
  void mixedBatch_onlyBreakingWithConsumersTriggerAck() {
    UUID c1 = UUID.randomUUID();
    UUID c2 = UUID.randomUUID();
    ContractRegistry r1 = contract;
    ContractRegistry r2 =
        ContractRegistry.builder()
            .id(c1)
            .name("inventory")
            .type(ContractRegistry.ContractType.OPENAPI.name())
            .currentRegistryVersion(1)
            .currentContentHash("h")
            .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
            .build();
    ContractRegistry r3 =
        ContractRegistry.builder()
            .id(c2)
            .name("audit")
            .type(ContractRegistry.ContractType.OPENAPI.name())
            .currentRegistryVersion(1)
            .currentContentHash("h")
            .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
            .build();
    when(registryRepo.findById(contractId)).thenReturn(Optional.of(r1));
    when(registryRepo.findById(c1)).thenReturn(Optional.of(r2));
    when(registryRepo.findById(c2)).thenReturn(Optional.of(r3));
    when(breakingChangeService.classify(any(), any(), any()))
        .thenReturn(breakingDiff("breaking 1"))
        .thenReturn(nonBreakingDiff("safe"))
        .thenReturn(breakingDiff("breaking 3"));
    // contract 1: breaking + has consumers → friction
    when(consumerRepo.findByContractIdAndDeclaredInProjectId(contractId, projectId))
        .thenReturn(
            List.of(
                ContractConsumer.builder()
                    .contractId(contractId)
                    .consumerRepoId(consumerRepoId)
                    .declaredInProjectId(projectId)
                    .build()));
    ProjectRepo cr = new ProjectRepo();
    cr.setId(consumerRepoId);
    cr.setGithubRepoFullName("acme/web");
    when(projectRepoRepo.findById(consumerRepoId)).thenReturn(Optional.of(cr));
    // contract 3: breaking + NO consumers → recorded, no friction
    when(consumerRepo.findByContractIdAndDeclaredInProjectId(c2, projectId)).thenReturn(List.of());

    PlanBreakingChangeReport.Report r =
        report.compose(
            projectId,
            List.of(
                new PlanBreakingChangeReport.IntendedChange(contractId, "old", "new"),
                new PlanBreakingChangeReport.IntendedChange(c1, "old", "new"),
                new PlanBreakingChangeReport.IntendedChange(c2, "old", "new")));

    // Two rows present (contract 1 + contract 3); contract 2 (non-breaking) excluded
    assertThat(r.breakingChanges()).hasSize(2);
    // Only contract 1 triggers requiresTypedAck (breaking + consumers)
    assertThat(r.requiresTypedAck()).isTrue();
    assertThat(
            r.breakingChanges().stream()
                .filter(PlanBreakingChangeReport.BreakingChangeRow::requiresFriction)
                .count())
        .isEqualTo(1);
  }

  private ContractDiff breakingDiff(String summary) {
    return new ContractDiff(
        summary,
        List.of(new ContractDiff.Change(ContractDiff.Change.Severity.BREAKING, summary, "(loc)")),
        ContractDiff.Confidence.DETERMINISTIC,
        "Test");
  }

  private ContractDiff nonBreakingDiff(String summary) {
    return new ContractDiff(
        summary,
        List.of(
            new ContractDiff.Change(ContractDiff.Change.Severity.NON_BREAKING, summary, "(loc)")),
        ContractDiff.Confidence.DETERMINISTIC,
        "Test");
  }
}
