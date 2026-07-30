package com.navneet.executespec.contracts.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.contracts.ContractConsumer;
import com.navneet.executespec.contracts.ContractConsumerRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.service.ProjectRepoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * RR-819 — the plan-approval contract gate. Before this gate, {@code requiresTypedAck} was a
 * sentence in a javadoc with no consumer anywhere in the API, frontend or CLI, so a
 * consumer-breaking contract change could be approved and auto-published with no friction and no
 * record that friction was owed.
 */
class PlanContractGateServiceTest {

  private static final UUID REPO_ID = UUID.randomUUID();
  private static final UUID CONSUMER_REPO_ID = UUID.randomUUID();
  private static final UUID CONTRACT_ID = UUID.randomUUID();
  private static final String PROJECT_ID = "proj-1";

  private ProjectRepoService projectRepoService;
  private ContractRegistryRepository registryRepo;
  private ContractConsumerRepository consumerRepo;
  private ProjectRepoRepository projectRepoRepo;
  private PlanContractGateReportRepository gateRepo;
  private BrownfieldHttpContractGuard brownfieldHttpContractGuard;
  private PlanContractGateService service;

  @BeforeEach
  void setUp() {
    projectRepoService = mock(ProjectRepoService.class);
    registryRepo = mock(ContractRegistryRepository.class);
    consumerRepo = mock(ContractConsumerRepository.class);
    projectRepoRepo = mock(ProjectRepoRepository.class);
    gateRepo = mock(PlanContractGateReportRepository.class);
    brownfieldHttpContractGuard = mock(BrownfieldHttpContractGuard.class);
    service =
        new PlanContractGateService(
            projectRepoService,
            registryRepo,
            consumerRepo,
            projectRepoRepo,
            gateRepo,
            new ObjectMapper(),
            brownfieldHttpContractGuard);
  }

  private static Plan planTouching(String... files) {
    return Plan.builder()
        .planTitle("t")
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("change the api")
                    .touchesFiles(List.of(files))
                    .build()))
        .build();
  }

  private static Plan planWithRequirement(String requirement) {
    return Plan.builder()
        .planTitle("brownfield change")
        .requirements(
            List.of(
                new RequirementSpec("R1", requirement, RequirementSpec.RequirementKind.FUNCTIONAL)))
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("implement admitted change")
                    .description(requirement)
                    .touchesFiles(List.of("src/main/java/example/CasinoController.java"))
                    .build()))
        .build();
  }

  private void withContract(String detectedPath, boolean hasConsumer) {
    ProjectRepo repo = new ProjectRepo();
    repo.setId(REPO_ID);
    repo.setGithubRepoFullName("acme/orders");
    when(projectRepoService.resolveForRun(eq(PROJECT_ID), any())).thenReturn(List.of(repo));

    ContractRegistry contract = new ContractRegistry();
    contract.setId(CONTRACT_ID);
    contract.setName("orders-api");
    contract.setDetectedPath(detectedPath);
    contract.setType(ContractRegistry.ContractType.OPENAPI.name());
    when(registryRepo.findByRepoId(REPO_ID)).thenReturn(List.of(contract));

    if (hasConsumer) {
      ContractConsumer cc = new ContractConsumer();
      cc.setConsumerRepoId(CONSUMER_REPO_ID);
      when(consumerRepo.findByContractIdAndDeclaredInProjectId(CONTRACT_ID, PROJECT_ID))
          .thenReturn(List.of(cc));
      ProjectRepo consumer = new ProjectRepo();
      consumer.setId(CONSUMER_REPO_ID);
      consumer.setGithubRepoFullName("acme/web");
      when(projectRepoRepo.findById(CONSUMER_REPO_ID)).thenReturn(Optional.of(consumer));
    } else {
      when(consumerRepo.findByContractIdAndDeclaredInProjectId(CONTRACT_ID, PROJECT_ID))
          .thenReturn(List.of());
    }
  }

  @Test
  void plan_touching_a_consumed_contract_requires_typed_ack() {
    withContract("api/openapi.yaml", true);

    var report =
        service.composeAndPersist(
            "run-1", PROJECT_ID, planTouching("api/openapi.yaml"), List.of(REPO_ID));

    assertTrue(report.isPresent());
    assertTrue(report.get().requiresTypedAck());
    assertEquals(1, report.get().rows().size());
    assertEquals(List.of("acme/web"), report.get().rows().get(0).declaredConsumers());
    assertEquals(List.of("T1"), report.get().rows().get(0).matchedPlanTasks());
    verify(gateRepo).save(any());
  }

  @Test
  void multi_repo_prefixed_paths_still_match_the_contract() {
    withContract("api/openapi.yaml", true);

    var report =
        service.composeAndPersist(
            "run-1", PROJECT_ID, planTouching("repos/orders/api/openapi.yaml"), List.of(REPO_ID));

    assertTrue(report.isPresent(), "a multi-repo plan path must not escape the gate");
    assertTrue(report.get().requiresTypedAck());
  }

  @Test
  void contract_with_no_declared_consumer_records_evidence_without_friction() {
    withContract("api/openapi.yaml", false);

    var report =
        service.composeAndPersist(
            "run-1", PROJECT_ID, planTouching("api/openapi.yaml"), List.of(REPO_ID));

    assertTrue(report.isPresent());
    assertFalse(report.get().requiresTypedAck());
  }

  @Test
  void plan_touching_nothing_registered_composes_no_gate() {
    withContract("api/openapi.yaml", true);

    var report =
        service.composeAndPersist(
            "run-1", PROJECT_ID, planTouching("src/main/java/Foo.java"), List.of(REPO_ID));

    assertTrue(report.isEmpty());
    verify(gateRepo, never()).save(any());
  }

  @Test
  void exactExplicitBaselineRemovalIsPersistedAndCanBeAcknowledged() throws Exception {
    withContract("api/openapi.yaml", false);
    when(brownfieldHttpContractGuard.hasBaseline("run-1")).thenReturn(true);
    when(brownfieldHttpContractGuard.protectedRoutes("run-1"))
        .thenReturn(Set.of(new RouteKey("POST", "/simulation/casino-game")));

    var composed =
        service.composeAndPersist(
            "run-1",
            PROJECT_ID,
            planWithRequirement("Remove the existing HTTP endpoint POST /simulation/casino-game."),
            List.of(REPO_ID));

    assertTrue(composed.isPresent());
    assertTrue(composed.get().requiresTypedAck());
    assertEquals(
        List.of(new PlanContractGateService.HttpRouteRemoval("POST", "/simulation/casino-game")),
        composed.get().brownfieldHttpRouteRemovals());

    ArgumentCaptor<PlanContractGateReport> persisted =
        ArgumentCaptor.forClass(PlanContractGateReport.class);
    verify(gateRepo).save(persisted.capture());
    var json = new ObjectMapper().readTree(persisted.getValue().getReportJson());
    assertEquals(1, json.path("schemaVersion").asInt());
    assertEquals(
        "/simulation/casino-game",
        json.path("brownfieldHttpRouteRemovals").get(0).path("path").asText());

    when(gateRepo.findById("run-1")).thenReturn(Optional.of(persisted.getValue()));
    when(gateRepo.claimAcknowledgement(
            eq("run-1"),
            eq(persisted.getValue().getReportDigest()),
            eq("user-1"),
            eq("BREAKING"),
            any(Instant.class)))
        .thenReturn(1);
    assertEquals(
        PlanContractGateService.AckOutcome.ACKNOWLEDGED,
        service.acknowledge("run-1", "BREAKING", persisted.getValue().getReportDigest(), "user-1"));
  }

  @Test
  void standaloneBrownfieldPlanCanEmitExactRouteRemovalWithoutProjectRegistry() {
    when(brownfieldHttpContractGuard.hasBaseline("run-standalone")).thenReturn(true);
    when(brownfieldHttpContractGuard.protectedRoutes("run-standalone"))
        .thenReturn(Set.of(new RouteKey("DELETE", "/legacy/items/{id}")));

    var report =
        service.composeAndPersist(
            "run-standalone",
            null,
            planWithRequirement("Retire HTTP route DELETE /legacy/items/{id}."),
            List.of());

    assertTrue(report.isPresent());
    assertTrue(report.get().requiresTypedAck());
    assertEquals(
        List.of(new PlanContractGateService.HttpRouteRemoval("DELETE", "/legacy/items/{id}")),
        report.get().brownfieldHttpRouteRemovals());
  }

  @Test
  void preserveExistingEndpointNeverBecomesRemovalAuthority() {
    withContract("api/openapi.yaml", false);
    when(brownfieldHttpContractGuard.hasBaseline("run-1")).thenReturn(true);
    when(brownfieldHttpContractGuard.protectedRoutes("run-1"))
        .thenReturn(Set.of(new RouteKey("POST", "/simulation/casino-game")));

    var report =
        service.composeAndPersist(
            "run-1",
            PROJECT_ID,
            planWithRequirement(
                "Preserve the existing endpoint POST /simulation/casino-game; do not remove POST"
                    + " /simulation/casino-game."),
            List.of(REPO_ID));

    assertTrue(report.isEmpty());
    verify(gateRepo, never()).save(any());
  }

  @Test
  void unrelatedExactOrGenericBreakingIntentEmitsNoRouteAuthority() {
    withContract("api/openapi.yaml", false);
    when(brownfieldHttpContractGuard.hasBaseline("run-1")).thenReturn(true);
    when(brownfieldHttpContractGuard.protectedRoutes("run-1"))
        .thenReturn(Set.of(new RouteKey("POST", "/simulation/casino-game")));

    assertTrue(
        service
            .composeAndPersist(
                "run-1",
                PROJECT_ID,
                planWithRequirement("Remove DELETE /simulation/unrelated."),
                List.of(REPO_ID))
            .isEmpty());
    assertTrue(
        service
            .composeAndPersist(
                "run-1",
                PROJECT_ID,
                planWithRequirement("Make a breaking API change after review."),
                List.of(REPO_ID))
            .isEmpty());
    verify(gateRepo, never()).save(any());
  }

  // ---- enforcement ----

  private PlanContractGateReport persisted(boolean requiresAck, Instant acknowledgedAt) {
    return PlanContractGateReport.builder()
        .runId("run-1")
        .projectId(PROJECT_ID)
        .reportJson("[]")
        .reportDigest("abc123")
        .requiresTypedAck(requiresAck)
        .contractCount(1)
        .createdAt(Instant.now())
        .acknowledgedAt(acknowledgedAt)
        .build();
  }

  @Test
  void approval_without_the_typed_ack_is_refused() {
    when(gateRepo.findById("run-1")).thenReturn(Optional.of(persisted(true, null)));

    assertEquals(
        PlanContractGateService.AckOutcome.MISSING,
        service.acknowledge("run-1", null, "abc123", "u1"));
    assertEquals(
        PlanContractGateService.AckOutcome.MISSING,
        service.acknowledge("run-1", "ok", "abc123", "u1"),
        "any text other than BREAKING must not satisfy the gate");
  }

  @Test
  void acknowledgement_against_a_different_report_is_refused() {
    when(gateRepo.findById("run-1")).thenReturn(Optional.of(persisted(true, null)));

    assertEquals(
        PlanContractGateService.AckOutcome.STALE_REPORT,
        service.acknowledge("run-1", "BREAKING", "someotherdigest", "u1"));
  }

  @Test
  void correct_typed_ack_bound_to_the_shown_report_is_recorded() {
    when(gateRepo.findById("run-1")).thenReturn(Optional.of(persisted(true, null)));
    when(gateRepo.claimAcknowledgement(
            eq("run-1"), eq("abc123"), eq("u1"), anyString(), any(Instant.class)))
        .thenReturn(1);

    assertEquals(
        PlanContractGateService.AckOutcome.ACKNOWLEDGED,
        service.acknowledge("run-1", " breaking ", "ABC123", "u1"));
    verify(gateRepo)
        .claimAcknowledgement(eq("run-1"), eq("abc123"), eq("u1"), eq("breaking"), any());
  }

  @Test
  void gate_that_does_not_require_friction_never_blocks_approval() {
    when(gateRepo.findById("run-1")).thenReturn(Optional.of(persisted(false, null)));

    assertEquals(
        PlanContractGateService.AckOutcome.NOT_REQUIRED,
        service.acknowledge("run-1", null, null, "u1"));
  }

  @Test
  void run_with_no_gate_report_never_blocks_approval() {
    when(gateRepo.findById("run-1")).thenReturn(Optional.empty());

    assertEquals(
        PlanContractGateService.AckOutcome.NOT_REQUIRED,
        service.acknowledge("run-1", null, null, "u1"));
  }

  @Test
  void replayed_approval_on_an_already_acknowledged_gate_is_idempotent() {
    when(gateRepo.findById("run-1")).thenReturn(Optional.of(persisted(true, Instant.now())));

    assertEquals(
        PlanContractGateService.AckOutcome.ACKNOWLEDGED,
        service.acknowledge("run-1", "BREAKING", "abc123", "u1"));
    verify(gateRepo, never()).claimAcknowledgement(any(), any(), any(), any(), any());
  }

  @Test
  void acknowledgement_claim_has_its_own_transaction_boundary() throws Exception {
    var method =
        PlanContractGateReportRepository.class.getMethod(
            "claimAcknowledgement",
            String.class,
            String.class,
            String.class,
            String.class,
            Instant.class);

    assertTrue(AnnotatedElementUtils.hasAnnotation(method, Transactional.class));
  }
}
