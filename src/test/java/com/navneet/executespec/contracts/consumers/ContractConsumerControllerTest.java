package com.navneet.executespec.contracts.consumers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.contracts.ContractConsumer;
import com.navneet.executespec.contracts.ContractConsumerRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.folder.ExecutespecFolderWriter;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractConsumerControllerTest {

  @Mock ContractRegistryRepository registryRepo;
  @Mock ContractConsumerRepository consumerRepo;
  @Mock ProjectRepoRepository projectRepoRepo;
  @Mock ExecutespecFolderWriter folderWriter;
  @Mock com.navneet.executespec.auth.repository.AppUserRepository userRepo;
  @Mock ActivityEventEmitter emitter;
  @Mock ObjectProvider<ActivityEventEmitter> emitterProvider;

  @InjectMocks ContractConsumerController controller;

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
    lenient().when(emitterProvider.getIfAvailable()).thenReturn(emitter);
  }

  @Test
  void declare_persistsRowAndEmitsEvent() {
    when(registryRepo.findById(contractId)).thenReturn(Optional.of(contract));
    ProjectRepo cr = new ProjectRepo();
    cr.setId(consumerRepoId);
    cr.setGithubRepoFullName("acme/web");
    when(projectRepoRepo.findById(consumerRepoId)).thenReturn(Optional.of(cr));
    when(consumerRepo.findByContractIdAndConsumerRepoIdAndDeclaredInProjectId(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(consumerRepo.save(any(ContractConsumer.class)))
        .thenAnswer(
            inv -> {
              ContractConsumer c = inv.getArgument(0);
              if (c.getId() == null) c.setId(UUID.randomUUID());
              return c;
            });

    ResponseEntity<ContractConsumerController.ConsumerDto> res =
        controller.declare(
            projectId,
            contractId,
            new ContractConsumerController.DeclareRequest(consumerRepoId, ">=v23"),
            fakeAuth());

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    verify(consumerRepo).save(any());
    verify(emitter)
        .emit(
            any(),
            eq(ActivityEventType.CONTRACT_CONSUMER_DECLARED),
            any(),
            any(),
            any(),
            anyString(),
            anyMap());
  }

  @Test
  void declare_idempotent_returnsExistingRow_andDoesNotReSave() {
    ContractConsumer existing =
        ContractConsumer.builder()
            .id(UUID.randomUUID())
            .contractId(contractId)
            .consumerRepoId(consumerRepoId)
            .declaredInProjectId(projectId)
            .declaredVersionConstraint("v1")
            .build();
    when(registryRepo.findById(contractId)).thenReturn(Optional.of(contract));
    ProjectRepo cr = new ProjectRepo();
    cr.setId(consumerRepoId);
    cr.setGithubRepoFullName("acme/web");
    when(projectRepoRepo.findById(consumerRepoId)).thenReturn(Optional.of(cr));
    when(consumerRepo.findByContractIdAndConsumerRepoIdAndDeclaredInProjectId(any(), any(), any()))
        .thenReturn(Optional.of(existing));

    ResponseEntity<ContractConsumerController.ConsumerDto> res =
        controller.declare(
            projectId,
            contractId,
            new ContractConsumerController.DeclareRequest(consumerRepoId, "v1"),
            fakeAuth());

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(consumerRepo, never()).save(any());
    verifyNoInteractions(emitter);
  }

  @Test
  void declare_rejectsMissingConsumerRepoId() {
    assertThatThrownBy(
            () ->
                controller.declare(
                    projectId,
                    contractId,
                    new ContractConsumerController.DeclareRequest(null, "v1"),
                    fakeAuth()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void remove_deletesRowAndEmitsEvent() {
    ContractConsumer existing =
        ContractConsumer.builder()
            .id(UUID.randomUUID())
            .contractId(contractId)
            .consumerRepoId(consumerRepoId)
            .declaredInProjectId(projectId)
            .build();
    when(consumerRepo.findByContractIdAndConsumerRepoIdAndDeclaredInProjectId(any(), any(), any()))
        .thenReturn(Optional.of(existing));

    ResponseEntity<Void> res = controller.remove(projectId, contractId, consumerRepoId, fakeAuth());

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(consumerRepo).delete(existing);
    verify(emitter)
        .emit(
            any(),
            eq(ActivityEventType.CONTRACT_CONSUMER_REMOVED),
            any(),
            any(),
            any(),
            anyString(),
            anyMap());
  }

  @Test
  void remove_404WhenNotDeclared() {
    when(consumerRepo.findByContractIdAndConsumerRepoIdAndDeclaredInProjectId(any(), any(), any()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.remove(projectId, contractId, consumerRepoId, fakeAuth()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void list_returnsConsumersForContractInProject() {
    when(consumerRepo.findByContractIdAndDeclaredInProjectId(contractId, projectId))
        .thenReturn(
            List.of(
                ContractConsumer.builder()
                    .id(UUID.randomUUID())
                    .contractId(contractId)
                    .consumerRepoId(UUID.randomUUID())
                    .declaredInProjectId(projectId)
                    .declaredVersionConstraint("v23")
                    .build()));

    ResponseEntity<List<ContractConsumerController.ConsumerDto>> res =
        controller.list(projectId, contractId, fakeAuth());

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).hasSize(1);
    assertThat(res.getBody().get(0).versionConstraint()).isEqualTo("v23");
  }

  @Test
  void composeConsumerYaml_emitsExpectedShape() {
    String yaml = ContractConsumerController.composeConsumerYaml(contract, ">=v23");
    assertThat(yaml).contains("contract:");
    assertThat(yaml).contains("  name: payments-api");
    assertThat(yaml).contains("  type: OPENAPI");
    assertThat(yaml).contains("versionConstraint: >=v23");
  }

  private UsernamePasswordAuthenticationToken fakeAuth() {
    return new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), "n/a");
  }
}
