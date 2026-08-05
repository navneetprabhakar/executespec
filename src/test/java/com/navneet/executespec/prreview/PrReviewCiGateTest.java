package com.navneet.executespec.prreview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the deterministic post-model CI gate (persona register P1 #4): red CI can never survive as
 * an approval, unverified CI is labeled instead of assumed green, and green CI changes nothing.
 */
class PrReviewCiGateTest {

  private static final String SHA = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0";

  private static PrReviewAgent.AgentReview review(String verdict, String summary) {
    return new PrReviewAgent.AgentReview(
        verdict,
        summary,
        List.of(
            new PrReviewAgent.AgentReviewComment(
                "src/A.java", 12, "RIGHT", "BUG", "MAJOR", "Null deref.")),
        42L,
        "anthropic",
        "claude-sonnet-5");
  }

  @Test
  void redCiCapsApprovalToRequestChangesWithStructuredFinding() {
    PrReviewAgent.AgentReview gated =
        PrReviewCiGate.apply(
            review("APPROVE", "Looks good."), new PrReviewCiGate.CiEvidence("FAILURE", 2, SHA));

    assertThat(gated.verdict()).isEqualTo("REQUEST_CHANGES");
    assertThat(gated.summary())
        .contains("CI failing on head " + SHA.substring(0, 12))
        .contains("2 failing check-runs")
        .contains("Looks good.");
    // Tighten-only: comments and metadata are untouched.
    assertThat(gated.comments()).isEqualTo(review("APPROVE", "x").comments());
    assertThat(gated.durationMs()).isEqualTo(42L);
    assertThat(gated.provider()).isEqualTo("anthropic");
    assertThat(gated.model()).isEqualTo("claude-sonnet-5");
  }

  @Test
  void redCiKeepsNonApprovalVerdictsButStillStampsTheFinding() {
    PrReviewAgent.AgentReview comment =
        PrReviewCiGate.apply(
            review("COMMENT", "Some notes."), new PrReviewCiGate.CiEvidence("FAILURE", 1, SHA));
    assertThat(comment.verdict()).isEqualTo("COMMENT");
    assertThat(comment.summary()).contains("CI failing on head").contains("(1 failing check-run)");

    PrReviewAgent.AgentReview requestChanges =
        PrReviewCiGate.apply(
            review("REQUEST_CHANGES", "Blocking bug."),
            new PrReviewCiGate.CiEvidence("FAILURE", 3, SHA));
    assertThat(requestChanges.verdict()).isEqualTo("REQUEST_CHANGES");
    assertThat(requestChanges.summary()).contains("CI failing on head");
  }

  @Test
  void unknownCiKeepsVerdictButStatesUnverified() {
    PrReviewAgent.AgentReview gated =
        PrReviewCiGate.apply(
            review("APPROVE", "Looks good."), new PrReviewCiGate.CiEvidence("UNKNOWN", 0, SHA));

    assertThat(gated.verdict()).isEqualTo("APPROVE");
    assertThat(gated.summary())
        .contains("unverified")
        .contains("does not assume checks passed")
        .contains("Looks good.");
  }

  @Test
  void missingEvidenceIsUnknownNeverGreen() {
    PrReviewAgent.AgentReview nullEvidence = PrReviewCiGate.apply(review("APPROVE", "Fine."), null);
    assertThat(nullEvidence.verdict()).isEqualTo("APPROVE");
    assertThat(nullEvidence.summary()).contains("unverified");

    PrReviewAgent.AgentReview blankStatus =
        PrReviewCiGate.apply(
            review("APPROVE", "Fine."), new PrReviewCiGate.CiEvidence("  ", 0, SHA));
    assertThat(blankStatus.summary()).contains("unverified (UNKNOWN)");
  }

  @Test
  void pendingCiKeepsVerdictButStatesUnverified() {
    PrReviewAgent.AgentReview gated =
        PrReviewCiGate.apply(
            review("APPROVE", "Fine."), new PrReviewCiGate.CiEvidence("PENDING", 0, SHA));
    assertThat(gated.verdict()).isEqualTo("APPROVE");
    assertThat(gated.summary()).contains("unverified (PENDING)");
  }

  @Test
  void greenCiChangesNothing() {
    PrReviewAgent.AgentReview original = review("APPROVE", "Looks good.");
    PrReviewAgent.AgentReview gated =
        PrReviewCiGate.apply(original, new PrReviewCiGate.CiEvidence("SUCCESS", 0, SHA));
    assertThat(gated).isSameAs(original);
  }
}
