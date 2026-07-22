package com.navneet.executespec.contracts.consumers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
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
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
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
  @Mock ProjectRepoLinkRepository projectRepoLinkRepo;
  @Mock ExecutespecFolderWriter folderWriter;
  @Mock ActivityEventEmitter emitter;
  @Mock ObjectProvider<ActivityEventEmitter> emitterProvider;
  @Mock com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;

  @InjectMocks ContractConsumerController controller;

  String projectId;
  UUID contractId;
  UUID consumerRepoId;
  UUID producerRepoId;
  ContractRegistry contract;

  @BeforeEach
  void setUp() {
    projectId = UUID.randomUUID().toString();
    contractId = UUID.randomUUID();
    consumerRepoId = UUID.randomUUID();
    producerRepoId = UUID.randomUUID();
    contract =
        ContractRegistry.builder()
            .id(contractId)
            .repoId(producerRepoId)
            .name("payments-api")
            .type(ContractRegistry.ContractType.OPENAPI.name())
            .currentRegistryVersion(1)
            .currentContentHash("h")
            .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
            .build();
    lenient().when(registryRepo.findById(contractId)).thenReturn(Optional.of(contract));
    lenient()
        .when(projectRepoLinkRepo.findByProjectIdAndRepoId(eq(projectId), any(UUID.class)))
        .thenReturn(Optional.of(mock(ProjectRepoLink.class)));
    lenient().when(emitterProvider.getIfAvailable()).thenReturn(emitter);
  }

  @Test
  void declare_rejectsForeignConsumerRepoBeforeWritingOrPersisting() {
    when(registryRepo.findById(contractId)).thenReturn(Optional.of(contract));
    when(projectRepoLinkRepo.findByProjectIdAndRepoId(projectId, producerRepoId))
        .thenReturn(Optional.of(mock(ProjectRepoLink.class)));
    when(projectRepoLinkRepo.findByProjectIdAndRepoId(projectId, consumerRepoId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                controller.declare(
                    projectId,
                    contractId,
                    new ContractConsumerController.DeclareRequest(consumerRepoId, "v1"),
                    fakeAuth()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    verify(consumerRepo, never()).save(any());
    verifyNoInteractions(folderWriter);
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
  @SuppressWarnings("unchecked")
  void composeConsumerYaml_emitsExpectedShape() {
    // RR-598 — assert on the PARSED document, not on raw substrings: `>=v23` starts with the YAML
    // block-scalar indicator `>`, so a safe serializer quotes it. The value that round-trips must
    // still be exactly what was requested.
    String yaml = ContractConsumerController.composeConsumerYaml(contract, ">=v23");
    Map<String, Object> parsed =
        (Map<String, Object>) com.navneet.executespec.util.SafeYamlLoader.load(yaml);
    Map<String, Object> contractNode = (Map<String, Object>) parsed.get("contract");
    assertThat(contractNode.get("name")).isEqualTo("payments-api");
    assertThat(contractNode.get("type")).isEqualTo("OPENAPI");
    assertThat(contractNode.get("id")).isEqualTo(contractId.toString());
    assertThat(parsed.get("versionConstraint")).isEqualTo(">=v23");
  }

  @Test
  @SuppressWarnings("unchecked")
  void composeConsumerYaml_neutralisesYamlInjectionInNameAndConstraint() {
    // RR-598 — a hostile contract name / version constraint must NOT be able to add keys or change
    // the document structure. Pre-fix, a name like "x\ninjected: true" or a constraint containing a
    // newline/colon rewrote the generated .executespec control file.
    ContractRegistry hostile =
        ContractRegistry.builder()
            .id(contractId)
            .repoId(producerRepoId)
            .name("evil\ninjectedTop: pwned\ncontract:\n  admin: true")
            .type(ContractRegistry.ContractType.OPENAPI.name())
            .currentRegistryVersion(1)
            .currentContentHash("h")
            .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
            .build();

    String yaml =
        ContractConsumerController.composeConsumerYaml(hostile, "1.0\nrogue: yes # comment");
    Map<String, Object> parsed =
        (Map<String, Object>) com.navneet.executespec.util.SafeYamlLoader.load(yaml);

    // Only the two intended top-level keys survive; no injected sibling.
    assertThat(parsed.keySet()).containsExactlyInAnyOrder("contract", "versionConstraint");
    assertThat(parsed).doesNotContainKey("injectedTop");
    assertThat(parsed).doesNotContainKey("rogue");
    // The malicious name is trapped inside a single scalar value, not parsed as structure.
    Map<String, Object> contractNode = (Map<String, Object>) parsed.get("contract");
    assertThat(contractNode.get("name"))
        .isInstanceOf(String.class)
        .asString()
        .contains("injectedTop: pwned")
        .contains("admin: true");
    assertThat(contractNode).doesNotContainKey("admin");
  }

  @Test
  void sanitizeVersionConstraint_defaultsAndStripsControlChars() {
    // RR-598 — null/blank → "any"; control characters are removed; the value is length-bounded.
    assertThat(ContractConsumerController.sanitizeVersionConstraint(null)).isEqualTo("any");
    assertThat(ContractConsumerController.sanitizeVersionConstraint("   ")).isEqualTo("any");
    assertThat(ContractConsumerController.sanitizeVersionConstraint("v1\u0000\u0007"))
        .isEqualTo("v1");
    assertThat(ContractConsumerController.sanitizeVersionConstraint("a".repeat(500)).length())
        .isEqualTo(100);
  }

  private UsernamePasswordAuthenticationToken fakeAuth() {
    // RL-2026-05-040 follow-up: the controller now resolves principal(auth) as AppUserDetails (the
    // RL-036 ProjectAccessGuard wiring); a bare String principal → 401 before validation. Mocked
    // projectAccessGuard no-ops the assertCanRead/Write so tests reach the 400/404 path.
    com.navneet.executespec.auth.entity.AppUser u =
        new com.navneet.executespec.auth.entity.AppUser();
    u.setId(UUID.randomUUID().toString());
    u.setOrgId(UUID.randomUUID().toString());
    u.setEmail("ci-test@executespec.local"); // getName() = getUsername() = email; non-null for auth
    // helpers
    return new UsernamePasswordAuthenticationToken(
        new com.navneet.executespec.auth.security.AppUserDetails(u, null), "n/a");
  }
}
