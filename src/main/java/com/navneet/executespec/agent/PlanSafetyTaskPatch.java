package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class PlanSafetyTaskPatch {

  final PlannedTask task;
  List<String> constraints;
  final List<String> touchesFiles;
  final List<String> summaries;
  int applied;
  String description;
  String globallyNormalizedDescription;
  String spacingNormalized;
  String endpointNormalized;
  String taskEvidence;
  boolean backendInventoryReadTask;
  boolean backendOrderApiTask;
  boolean stockMutationServiceTask;
  boolean ownsStockMutationApi;
  boolean ownsIdempotencyModel;
  boolean ownsMigration;
  boolean orderSchemaProducer;
  boolean reservationSchemaProducer;
  String orderEvidence;
  String taskIdentityEvidence;
  boolean frontendOnlyTask;
  boolean orderReservationTask;
  int failingAcceptanceInstructions;
  boolean documentationTask;
  boolean apiOrServiceTask;
  boolean testTask;
  boolean credentialTask;
  boolean dedicatedAcceptanceTestTask;
  boolean explicitRealHttpTestTask;
  boolean acceptanceContractTask;
  boolean realHttpTestTask;
  boolean authenticationProofTask;
  boolean ownedResourceBehaviorTask;
  boolean idempotencyBehaviorTask;
  boolean idempotencyContractTask;
  boolean createReservationTask;
  boolean stockMutationTask;
  boolean cancellationBehaviorTask;
  boolean invalidCancellationContractTask;
  boolean errorContractTask;
  boolean reservationScopedIdempotencyHeader;
  boolean orderCreationIdempotencyHeader;
  boolean idempotencyTransportTask;
  boolean reservationLifecycleTask;
  boolean runtimeOrMigrationTask;
  boolean taskInstallsDuringBuildOrTest;
  boolean rootVerificationTask;

  PlanSafetyTaskPatch(PlannedTask task, List<String> summaries) {
    this.task = task;
    this.description = task.description();
    this.constraints = new ArrayList<>(new LinkedHashSet<>(task.hardConstraints()));
    this.touchesFiles = new ArrayList<>(task.touchesFiles());
    this.summaries = summaries;
  }

  PlannedTask toTask() {
    return PlanSafetyStructureSupport.withDescriptionConstraintsAndFiles(
        task,
        PlanSafetyTextSupport.normalizeTechnicalSpacing(description),
        constraints,
        touchesFiles);
  }
}
