package com.navneet.executespec.execution.model;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.router.ModelProviderCapabilitySignal;

import lombok.Builder;

/** Runtime-facing request for candidate-backed goal continuation advice. */
@Builder
public record GoalContinuationAdvisorRequest(
    GoalDonenessDecision goalDecision,
    String currentProvider,
    String currentModel,
    String taskCategory,
    double requiredQualityThreshold,
    List<String> providerAllowList,
    List<String> modelAllowList,
    List<ModelProviderCapabilitySignal> externalSignals,
    Map<String, Boolean> providerAvailability,
    List<String> providerPreferenceOrder,
    boolean qualityInsufficient,
    boolean providerBehaviorPoor,
    boolean taskSimplerThanExpected,
    boolean routePinned,
    boolean expensiveContinuationRequiresConfirmation,
    boolean benchmarkQualityPriorRequiredForAutomaticContinuation,
    boolean userConfirmedExpensiveContinuation,
    RunContinuationDecision userConfirmedContinuationAction,
    String userConfirmedContinuationProvider,
    String userConfirmedContinuationModel,
    int providerSwitchesUsed,
    int maxProviderSwitches,
    int modelStepUpsUsed,
    int maxModelStepUps,
    int modelStepDownsUsed,
    int maxModelStepDowns) {

  private static final double DEFAULT_REQUIRED_QUALITY_THRESHOLD = 70.0;

  public GoalContinuationAdvisorRequest {
    currentProvider =
        currentProvider == null || currentProvider.isBlank() ? "unknown" : currentProvider;
    currentModel = currentModel == null || currentModel.isBlank() ? "unknown" : currentModel;
    taskCategory = taskCategory == null || taskCategory.isBlank() ? "UNKNOWN" : taskCategory;
    requiredQualityThreshold =
        requiredQualityThreshold <= 0.0
            ? DEFAULT_REQUIRED_QUALITY_THRESHOLD
            : Math.min(100.0, requiredQualityThreshold);
    providerAllowList = providerAllowList == null ? List.of() : List.copyOf(providerAllowList);
    modelAllowList = modelAllowList == null ? List.of() : List.copyOf(modelAllowList);
    externalSignals = externalSignals == null ? List.of() : List.copyOf(externalSignals);
    providerAvailability =
        providerAvailability == null ? Map.of() : Map.copyOf(providerAvailability);
    providerPreferenceOrder =
        providerPreferenceOrder == null ? List.of() : List.copyOf(providerPreferenceOrder);
    providerSwitchesUsed = Math.max(0, providerSwitchesUsed);
    maxProviderSwitches = Math.max(0, maxProviderSwitches);
    modelStepUpsUsed = Math.max(0, modelStepUpsUsed);
    maxModelStepUps = Math.max(0, maxModelStepUps);
    modelStepDownsUsed = Math.max(0, modelStepDownsUsed);
    maxModelStepDowns = Math.max(0, maxModelStepDowns);
  }
}
