package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.execution.model.GoalContinuationAdvisorRequest;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.router.ModelProviderCandidateRequest;
import com.navneet.executespec.router.ModelProviderCandidateService;
import com.navneet.executespec.router.ModelProviderScore;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GoalContinuationAdvisorTest {

  private final ModelProviderCandidateService candidateService =
      mock(ModelProviderCandidateService.class);
  private final GoalContinuationAdvisor advisor = new GoalContinuationAdvisor(candidateService);

  @Test
  void recommendsSameProviderStepUpWhenQualityIsInsufficientAndStrongerModelClearsThreshold() {
    when(candidateService.buildCandidates(any()))
        .thenReturn(
            List.of(
                score("anthropic", "haiku", 74, 70, 1.0),
                score("anthropic", "sonnet", 91, 70, 8.0)));

    var decision =
        advisor.advise(
            GoalContinuationAdvisorRequest.builder()
                .goalDecision(progressing())
                .currentProvider("anthropic")
                .currentModel("haiku")
                .taskCategory("BUG_FIX")
                .requiredQualityThreshold(70.0)
                .qualityInsufficient(true)
                .maxModelStepUps(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.STEP_UP_MODEL);
    assertThat(decision.selectedCandidate()).contains(score("anthropic", "sonnet", 91, 70, 8.0));
  }

  @Test
  void recommendsProviderChangeWhenProviderBehaviorIsPoorAndAlternateClearsThreshold() {
    when(candidateService.buildCandidates(any()))
        .thenReturn(
            List.of(
                score("anthropic", "sonnet", 80, 70, 8.0),
                score("openai", "gpt-5.4", 88, 70, 7.0)));

    var decision =
        advisor.advise(
            GoalContinuationAdvisorRequest.builder()
                .goalDecision(progressing())
                .currentProvider("anthropic")
                .currentModel("sonnet")
                .taskCategory("BUG_FIX")
                .providerBehaviorPoor(true)
                .maxProviderSwitches(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.CHANGE_PROVIDER);
    assertThat(decision.selectedCandidate()).contains(score("openai", "gpt-5.4", 88, 70, 7.0));
  }

  @Test
  void recommendsStepDownOnlyToCheaperEligibleCandidate() {
    when(candidateService.buildCandidates(any()))
        .thenReturn(
            List.of(
                score("anthropic", "sonnet", 90, 70, 8.0),
                score("anthropic", "haiku", 78, 70, 1.0),
                score("anthropic", "tiny", 60, 70, 0.1)));

    var decision =
        advisor.advise(
            GoalContinuationAdvisorRequest.builder()
                .goalDecision(progressing())
                .currentProvider("anthropic")
                .currentModel("sonnet")
                .taskCategory("READ_ONLY_HELP")
                .taskSimplerThanExpected(true)
                .maxModelStepDowns(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.STEP_DOWN_MODEL);
    assertThat(decision.selectedCandidate()).contains(score("anthropic", "haiku", 78, 70, 1.0));
  }

  @Test
  void passesRuntimeFiltersAndExternalSignalsToCandidateService() {
    when(candidateService.buildCandidates(any())).thenReturn(List.of());

    advisor.advise(
        GoalContinuationAdvisorRequest.builder()
            .goalDecision(progressing())
            .currentProvider("anthropic")
            .currentModel("sonnet")
            .taskCategory("TERMINAL_TASK")
            .requiredQualityThreshold(82.0)
            .providerAllowList(List.of("anthropic", "openai"))
            .modelAllowList(List.of("sonnet", "gpt-5.4"))
            .providerAvailability(Map.of("openai", false))
            .providerPreferenceOrder(List.of("anthropic", "openai"))
            .build());

    ArgumentCaptor<ModelProviderCandidateRequest> captor =
        ArgumentCaptor.forClass(ModelProviderCandidateRequest.class);
    verify(candidateService).buildCandidates(captor.capture());
    assertThat(captor.getValue().taskCategory()).isEqualTo("TERMINAL_TASK");
    assertThat(captor.getValue().requiredQualityThreshold()).isEqualTo(82.0);
    assertThat(captor.getValue().providerAllowList()).containsExactly("anthropic", "openai");
    assertThat(captor.getValue().modelAllowList()).containsExactly("sonnet", "gpt-5.4");
    assertThat(captor.getValue().providerAvailability()).containsEntry("openai", false);
    assertThat(captor.getValue().providerPreferenceOrder()).containsExactly("anthropic", "openai");
  }

  private static GoalDonenessDecision progressing() {
    return new GoalDonenessDecision(
        GoalDonenessState.NOT_DONE_BUT_PROGRESSING,
        RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
        "progressing",
        "test goal",
        List.of("some progress"),
        List.of("verification missing"),
        List.of("files changed"),
        List.of(),
        false,
        true);
  }

  private static ModelProviderScore score(
      String provider, String model, double quality, double threshold, double cost) {
    return new ModelProviderScore(
        provider, model, "BUG_FIX", quality, cost, 1_000, 2_000, threshold, List.of());
  }
}
