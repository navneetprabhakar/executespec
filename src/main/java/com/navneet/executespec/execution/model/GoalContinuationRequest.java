package com.navneet.executespec.execution.model;

import com.navneet.executespec.router.ModelProviderRankingDecision;
import com.navneet.executespec.router.ModelProviderScore;

import lombok.Builder;

/** Input to the policy that turns goal doneness into continue/escalate/stop decisions. */
@Builder
public record GoalContinuationRequest(
    GoalDonenessDecision goalDecision,
    ModelProviderScore currentCandidate,
    ModelProviderRankingDecision rankingDecision,
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

  public GoalContinuationRequest {
    providerSwitchesUsed = Math.max(0, providerSwitchesUsed);
    modelStepUpsUsed = Math.max(0, modelStepUpsUsed);
    modelStepDownsUsed = Math.max(0, modelStepDownsUsed);
    maxProviderSwitches = Math.max(0, maxProviderSwitches);
    maxModelStepUps = Math.max(0, maxModelStepUps);
    maxModelStepDowns = Math.max(0, maxModelStepDowns);
  }

  public boolean canSwitchProvider() {
    return providerSwitchesUsed < maxProviderSwitches;
  }

  public boolean canStepUp() {
    return modelStepUpsUsed < maxModelStepUps;
  }

  public boolean canStepDown() {
    return modelStepDownsUsed < maxModelStepDowns;
  }
}
