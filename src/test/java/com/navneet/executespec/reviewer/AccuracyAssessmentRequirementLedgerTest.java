package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.RequirementSpec;

import org.junit.jupiter.api.Test;

class AccuracyAssessmentRequirementLedgerTest {

  private final AccuracyAssessmentService service =
      new AccuracyAssessmentService(null, null, null, null, new ObjectMapper());

  @Test
  void rendersEveryCanonicalRequirementWithoutDroppingCompoundClauses() {
    String ledger =
        service.renderRequirementLedger(
            List.of(
                new RequirementSpec(
                    "R7",
                    "Replay returns the original immutable response and keys are scoped by"
                        + " operation",
                    RequirementSpec.RequirementKind.FUNCTIONAL),
                new RequirementSpec(
                    "R8",
                    "Expected version is required and stale requests return 409",
                    RequirementSpec.RequirementKind.CONSTRAINT)));

    assertThat(ledger)
        .contains("R7 [FUNCTIONAL]")
        .contains("original immutable response")
        .contains("scoped by operation")
        .contains("R8 [CONSTRAINT]")
        .contains("stale requests return 409");
  }

  @Test
  void emptyLedgerMakesLegacyFallbackExplicit() {
    assertThat(service.renderRequirementLedger(List.of()))
        .contains("extract requirements from the original specification");
  }
}
