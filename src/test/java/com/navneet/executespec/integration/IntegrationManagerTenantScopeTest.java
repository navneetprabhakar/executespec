package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.IntegrationRun;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.IntegrationEventLogRepository;
import com.navneet.executespec.state.repository.IntegrationRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RR-339 (cross-tenant listing) and RR-340 (confused-deputy manual triggers) — the manager must
 * scope reads by owning org and must submit interactive triggers under the caller's identity.
 */
class IntegrationManagerTenantScopeTest {

  private IntegrationRunRepository runRepo;
  private IntegrationEventLogRepository eventLogRepo;
  private RunOrchestrator orchestrator;
  private AppUserRepository userRepo;
  private IntegrationManager manager;

  @BeforeEach
  void setUp() {
    runRepo = mock(IntegrationRunRepository.class);
    eventLogRepo = mock(IntegrationEventLogRepository.class);
    orchestrator = mock(RunOrchestrator.class);
    userRepo = mock(AppUserRepository.class);
    IntegrationProperties props = new IntegrationProperties();
    props.setRunOnBehalfOfUserId("service-runner");
    manager =
        new IntegrationManager(
            List.of(),
            runRepo,
            eventLogRepo,
            mock(StateStore.class),
            orchestrator,
            props,
            userRepo);
  }

  private void stubSubmit(String runId) {
    PipelineRun run = new PipelineRun();
    run.setRunId(runId);
    when(orchestrator.submitRun(
            anyString(),
            anyString(),
            any(),
            anyString(),
            any(),
            any(),
            anyBoolean(),
            anyBoolean(),
            any(),
            any(),
            anyString(),
            anyString(),
            any(),
            anyString(),
            any()))
        .thenReturn(run);
  }

  private static IntegrationEvent event() {
    return new IntegrationEvent(
        "JIRA", "KAN-1", "https://example.invalid/KAN-1", "goal", "context", "", true);
  }

  @Test
  void listingIsScopedToTheOwningOrg() {
    manager.listIntegrationRuns(null, "org-a");
    verify(runRepo).findByOrgIdOrderByCreatedAtDesc("org-a");
    verify(runRepo, never()).findAllByOrderByCreatedAtDesc();

    manager.listIntegrationRuns("jira", "org-a");
    verify(runRepo).findByOrgIdAndSourceTypeOrderByCreatedAtDesc("org-a", "JIRA");
  }

  @Test
  void listingWithoutAnOwningOrgReturnsNothingRatherThanEverything() {
    assertThat(manager.listIntegrationRuns(null, null)).isEmpty();
    assertThat(manager.listIntegrationRuns("jira", "  ")).isEmpty();
    verify(runRepo, never()).findAllByOrderByCreatedAtDesc();
    verify(runRepo, never()).findBySourceTypeOrderByCreatedAtDesc(anyString());
  }

  @Test
  void manualTriggerRequiresAnAuthenticatedActorWithAnOrg() {
    assertThatThrownBy(() -> manager.submitEvent(event(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> manager.submitEvent(event(), new IntegrationManager.IntegrationActor("u1", null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> manager.submitEvent(event(), new IntegrationManager.IntegrationActor(null, "o1")))
        .isInstanceOf(IllegalArgumentException.class);
    verify(orchestrator, never())
        .submitRun(
            anyString(),
            anyString(),
            any(),
            anyString(),
            any(),
            any(),
            anyBoolean(),
            anyBoolean(),
            any(),
            any(),
            anyString(),
            anyString(),
            any(),
            anyString(),
            any());
  }

  @Test
  void manualTriggerRunsAsTheCallerNotTheServiceRunnerAndKeepsThePlanGate() {
    stubSubmit("run-1");
    AppUser runner = new AppUser();
    runner.setOrgId("runner-org");
    when(userRepo.findById("service-runner")).thenReturn(java.util.Optional.of(runner));

    String runId =
        manager.submitEvent(event(), new IntegrationManager.IntegrationActor("caller", "org-a"));

    assertThat(runId).isEqualTo("run-1");
    // Charged to the caller's org, not the globally configured runner's org...
    verify(orchestrator)
        .submitRun(
            anyString(),
            eq("JIRA"),
            any(),
            anyString(),
            any(),
            any(),
            /* skipApproval */ eq(false), // ...and the adapter's skip-approval is NOT honoured.
            anyBoolean(),
            any(),
            any(),
            eq("caller"),
            eq("org-a"),
            any(),
            anyString(),
            any());

    ArgumentCaptor<IntegrationRun> saved = ArgumentCaptor.forClass(IntegrationRun.class);
    verify(runRepo).save(saved.capture());
    assertThat(saved.getValue().getOrgId()).isEqualTo("org-a");
    assertThat(saved.getValue().getCreatedByUserId()).isEqualTo("caller");
  }
}
