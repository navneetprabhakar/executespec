package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.reviewer.ReviewerResult;

import org.junit.jupiter.api.Test;

class RunOrchestratorTestGenAdmissionTest {

  @Test
  void skipsExpensiveGeneratedSuiteWhenSemanticReviewFailed() {
    ReviewerResult failed =
        ReviewerResult.builder().overall(ReviewerResult.Overall.REVIEW_FAILED).build();

    assertThat(
            IndependentTestGenAdmissionPolicy.shouldKickoff(
                "PARTIAL", Plan.RuntimeSurface.HTTP, failed))
        .isFalse();
  }

  @Test
  void admitsCleanHttpOutputEvenWhenIndependentProofIsStillPending() {
    ReviewerResult clean = ReviewerResult.builder().overall(ReviewerResult.Overall.CLEAN).build();

    assertThat(
            IndependentTestGenAdmissionPolicy.shouldKickoff(
                "PARTIAL", Plan.RuntimeSurface.HTTP, clean))
        .isTrue();
    assertThat(
            IndependentTestGenAdmissionPolicy.shouldKickoff(
                "COMPLETE", Plan.RuntimeSurface.CLI, clean))
        .isFalse();
  }
}
