package com.navneet.executespec.integration;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.requirement.RunRequirementContractShadowService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Persists the legacy compiled plan first, then records additive RequirementIR shadow evidence. */
@Component
@RequiredArgsConstructor
@Slf4j
class RunPlanRequirementShadowCoordinator {
  static final String SHADOW_FLAG = "requirement.ir.shadow.enabled";

  private final RunPersistenceWriter persistenceWriter;
  private final RunRequirementContractShadowService shadowService;
  private final FeatureFlagService featureFlagService;

  RunPersistenceWriter.PersistedCompiledPlan persist(
      String runId, String exactSource, Plan compiledPlan) {
    RunPersistenceWriter.PersistedCompiledPlan persisted =
        persistenceWriter.persistCompiledPlan(runId, compiledPlan);
    if (persisted.exactPlanJson() == null || !shadowEnabled(runId)) {
      return persisted;
    }
    try {
      shadowService.persist(runId, exactSource, persisted.exactPlanJson());
    } catch (RuntimeException unexpectedShadowFailure) {
      log.warn(
          "run={} RequirementIR shadow boundary failed errorClass={}",
          runId,
          unexpectedShadowFailure.getClass().getSimpleName());
    }
    return persisted;
  }

  private boolean shadowEnabled(String runId) {
    try {
      return featureFlagService.isEnabled(SHADOW_FLAG);
    } catch (RuntimeException flagReadFailure) {
      log.warn(
          "run={} RequirementIR shadow flag read failed errorClass={}",
          runId,
          flagReadFailure.getClass().getSimpleName());
      return false;
    }
  }
}
