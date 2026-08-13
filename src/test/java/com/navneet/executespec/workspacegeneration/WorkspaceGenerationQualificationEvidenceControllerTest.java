package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.junit.jupiter.api.Test;

class WorkspaceGenerationQualificationEvidenceControllerTest {

  @Test
  void operatorEvidenceResponseIsNeverCacheable() {
    WorkspaceGenerationQualificationEvidenceService service =
        mock(WorkspaceGenerationQualificationEvidenceService.class);
    WorkspaceGenerationSchemaAttestor schemaAttestor =
        mock(WorkspaceGenerationSchemaAttestor.class);
    WorkspaceGenerationHistoricalInventoryService historicalInventory =
        mock(WorkspaceGenerationHistoricalInventoryService.class);
    WorkspaceGenerationHistoricalAdjudicationService historicalAdjudication =
        mock(WorkspaceGenerationHistoricalAdjudicationService.class);
    UUID attemptId = UUID.randomUUID();
    var payload =
        new WorkspaceGenerationQualificationEvidenceService.QualificationEvidence(
            attemptId,
            "run-1",
            "T1",
            WorkspaceGenerationState.ROLLED_BACK,
            "a".repeat(64),
            null,
            "b".repeat(64),
            "{\"files\":[]}",
            "TASK_OUTCOME_UNACCEPTED",
            "failed deterministic task smoke",
            null,
            null,
            null,
            "ANTHROPIC",
            "claude-sonnet-5",
            "ANTHROPIC",
            "claude-sonnet-5",
            9,
            11,
            9L,
            null,
            null,
            null,
            null,
            java.util.List.of(),
            java.util.List.of());
    when(service.read(attemptId)).thenReturn(payload);

    var response =
        new WorkspaceGenerationQualificationEvidenceController(
                service,
                schemaAttestor,
                historicalInventory,
                historicalAdjudication,
                mock(WorkspaceGenerationReconciliationFailureStore.class))
            .read(attemptId);

    assertThat(response.getBody()).isSameAs(payload);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }

  @Test
  void schemaAttestationIsOperatorReadableAndNeverCacheable() {
    WorkspaceGenerationQualificationEvidenceService evidence =
        mock(WorkspaceGenerationQualificationEvidenceService.class);
    WorkspaceGenerationSchemaAttestor schemaAttestor =
        mock(WorkspaceGenerationSchemaAttestor.class);
    WorkspaceGenerationHistoricalInventoryService historicalInventory =
        mock(WorkspaceGenerationHistoricalInventoryService.class);
    WorkspaceGenerationHistoricalAdjudicationService historicalAdjudication =
        mock(WorkspaceGenerationHistoricalAdjudicationService.class);
    var attestation =
        new WorkspaceGenerationSchemaAttestor.Attestation(
            1, java.time.Instant.EPOCH, true, "387", 387, java.util.List.of(), true, null);
    when(schemaAttestor.attest()).thenReturn(attestation);

    var response =
        new WorkspaceGenerationQualificationEvidenceController(
                evidence,
                schemaAttestor,
                historicalInventory,
                historicalAdjudication,
                mock(WorkspaceGenerationReconciliationFailureStore.class))
            .schemaAttestation();

    assertThat(response.getBody()).isSameAs(attestation);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }

  @Test
  void reconciliationDeadLettersAreOperatorReadableAndNeverCacheable() {
    WorkspaceGenerationReconciliationFailureStore failures =
        mock(WorkspaceGenerationReconciliationFailureStore.class);
    var payload =
        java.util.List.of(
            new WorkspaceGenerationReconciliationFailureStore.Failure(
                UUID.randomUUID(),
                WorkspaceGenerationReconciliationFailureStore.IdentityKind.RUN,
                "run-poison",
                3,
                "RUN_TERMINAL_RECONCILIATION_FAILED",
                java.time.Instant.EPOCH,
                java.time.Instant.EPOCH.plusSeconds(2),
                java.time.Instant.EPOCH.plusSeconds(2),
                null));
    when(failures.readOpen()).thenReturn(payload);

    var response =
        new WorkspaceGenerationQualificationEvidenceController(
                mock(WorkspaceGenerationQualificationEvidenceService.class),
                mock(WorkspaceGenerationSchemaAttestor.class),
                mock(WorkspaceGenerationHistoricalInventoryService.class),
                mock(WorkspaceGenerationHistoricalAdjudicationService.class),
                failures)
            .reconciliationFailures();

    assertThat(response.getBody()).isEqualTo(payload);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }

  @Test
  void historicalInventoryIsOperatorReadableAndNeverCacheable() {
    WorkspaceGenerationQualificationEvidenceService evidence =
        mock(WorkspaceGenerationQualificationEvidenceService.class);
    WorkspaceGenerationSchemaAttestor schemaAttestor =
        mock(WorkspaceGenerationSchemaAttestor.class);
    WorkspaceGenerationHistoricalInventoryService historicalInventory =
        mock(WorkspaceGenerationHistoricalInventoryService.class);
    WorkspaceGenerationHistoricalAdjudicationService historicalAdjudication =
        mock(WorkspaceGenerationHistoricalAdjudicationService.class);
    var inventory =
        new WorkspaceGenerationHistoricalInventoryService.Inventory(
            1,
            java.time.Instant.EPOCH,
            0,
            0,
            0,
            java.util.Map.of(),
            "a".repeat(64),
            java.util.List.of());
    when(historicalInventory.capture()).thenReturn(inventory);

    var response =
        new WorkspaceGenerationQualificationEvidenceController(
                evidence,
                schemaAttestor,
                historicalInventory,
                historicalAdjudication,
                mock(WorkspaceGenerationReconciliationFailureStore.class))
            .historicalInventory();

    assertThat(response.getBody()).isSameAs(inventory);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }

  @Test
  void historicalDryRunUsesAuthenticatedOperatorAndIsNeverCacheable() {
    WorkspaceGenerationQualificationEvidenceService evidence =
        mock(WorkspaceGenerationQualificationEvidenceService.class);
    WorkspaceGenerationSchemaAttestor schemaAttestor =
        mock(WorkspaceGenerationSchemaAttestor.class);
    WorkspaceGenerationHistoricalInventoryService historicalInventory =
        mock(WorkspaceGenerationHistoricalInventoryService.class);
    WorkspaceGenerationHistoricalAdjudicationService adjudication =
        mock(WorkspaceGenerationHistoricalAdjudicationService.class);
    AppUserDetails principal = mock(AppUserDetails.class);
    AppUser user = mock(AppUser.class);
    when(principal.getUser()).thenReturn(user);
    when(user.getId()).thenReturn("operator-1");
    var request =
        new WorkspaceGenerationQualificationEvidenceController.HistoricalAdjudicationRequest(
            WorkspaceGenerationHistoricalAdjudicationService.RecordKind.RUN,
            "run-1",
            WorkspaceGenerationHistoricalAdjudicationService.Action.KEEP_QUARANTINED,
            "Operator reviewed all available evidence and kept this record quarantined.");

    var response =
        new WorkspaceGenerationQualificationEvidenceController(
                evidence,
                schemaAttestor,
                historicalInventory,
                adjudication,
                mock(WorkspaceGenerationReconciliationFailureStore.class))
            .dryRunHistoricalAdjudication(request, principal);

    verify(adjudication)
        .dryRun(
            request.recordKind(),
            request.recordId(),
            request.action(),
            request.operatorReason(),
            "operator-1");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }
}
