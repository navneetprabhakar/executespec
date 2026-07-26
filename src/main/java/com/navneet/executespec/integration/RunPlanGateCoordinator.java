package com.navneet.executespec.integration;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.agent.requirement.RequirementGateBindingService.RequestedMode;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Keeps RequirementIR preparation and atomic plan-gate opening out of the run orchestrator. */
@Component
@RequiredArgsConstructor
class RunPlanGateCoordinator {
  private final RunPlanRequirementShadowCoordinator shadowCoordinator;
  private final RequirementGateModeResolver modeResolver;
  private final PlanGateOpenService opener;

  Preparation prepare(boolean needsApproval, String runId, String exactSource, Plan plan) {
    RequestedMode mode = needsApproval ? modeResolver.resolve() : RequestedMode.LEGACY;
    var persisted =
        shadowCoordinator.persist(
            runId, exactSource, plan, needsApproval && mode != RequestedMode.LEGACY);
    return new Preparation(mode, persisted);
  }

  void open(
      String runId,
      Preparation preparation,
      String continuationJson,
      int continuationSchemaVersion,
      String contractGateReportDigest) {
    String exactPlanJson = preparation.persistedPlan().exactPlanJson();
    opener.open(
        new PlanGateOpenService.OpenCommand(
            runId,
            exactPlanJson,
            ExactPlanFingerprint.sha256(exactPlanJson),
            continuationJson,
            continuationSchemaVersion,
            contractGateReportDigest,
            preparation.mode()));
  }

  record Preparation(
      RequestedMode mode, RunPersistenceWriter.PersistedCompiledPlan persistedPlan) {}
}
