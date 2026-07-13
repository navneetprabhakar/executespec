package com.navneet.executespec.eval.v2.cases;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.eval.v2.TierEvalCase;

import org.junit.jupiter.api.Test;

class PlanningCasesTest {

  @Test
  void requirementTraceabilityPointsRequireEveryTaskToCiteRequirements() {
    TierEvalCase caseUnderTest = PlanningCases.all().get(0);

    String shared =
        "{\"planTitle\":\"Plan\",\"planSummary\":\"Summary\","
            + "\"projectType\":\"REST\",\"requirements\":["
            + "{\"id\":\"R1\",\"text\":\"first\"},"
            + "{\"id\":\"R2\",\"text\":\"second\"},"
            + "{\"id\":\"R3\",\"text\":\"third\"}],\"tasks\":[";
    String complete =
        shared
            + "{\"id\":\"T1\",\"title\":\"One\",\"meetsRequirements\":[\"R1\"]},"
            + "{\"id\":\"T2\",\"title\":\"Two\",\"meetsRequirements\":[\"R2\"]},"
            + "{\"id\":\"T3\",\"title\":\"Three\",\"meetsRequirements\":[\"R3\"]}]}";
    String partial =
        shared
            + "{\"id\":\"T1\",\"title\":\"One\",\"meetsRequirements\":[\"R1\"]},"
            + "{\"id\":\"T2\",\"title\":\"Two\"},"
            + "{\"id\":\"T3\",\"title\":\"Three\"}]}";
    String missing =
        shared
            + "{\"id\":\"T1\",\"title\":\"One\"},"
            + "{\"id\":\"T2\",\"title\":\"Two\"},"
            + "{\"id\":\"T3\",\"title\":\"Three\"}]}";

    TierEvalCase.ValidationVerdict completeVerdict =
        caseUnderTest.validate("", "submitOutline", complete);
    TierEvalCase.ValidationVerdict partialVerdict =
        caseUnderTest.validate("", "submitOutline", partial);
    TierEvalCase.ValidationVerdict missingVerdict =
        caseUnderTest.validate("", "submitOutline", missing);

    assertThat(completeVerdict.correctness()).isEqualTo(100);
    assertThat(partialVerdict.correctness()).isEqualTo(95);
    assertThat(missingVerdict.correctness()).isEqualTo(90);
    assertThat(partialVerdict.detail()).contains("requirement coverage=1/3");
    assertThat(missingVerdict.detail()).contains("no task cites meetsRequirements");
  }

  @Test
  void blankTaskIdentityAndUnknownRequirementLinksDoNotEarnStructuralPoints() {
    TierEvalCase caseUnderTest = PlanningCases.all().get(0);
    String args =
        "{\"planTitle\":\"Plan\",\"planSummary\":\"Summary\","
            + "\"projectType\":\"REST\",\"requirements\":["
            + "{\"id\":\"R1\",\"text\":\"first\"}],\"tasks\":["
            + "{\"id\":\" \" ,\"title\":null,\"meetsRequirements\":[\"R1\"]},"
            + "{\"id\":\"T2\",\"title\":\"Two\",\"meetsRequirements\":[\"UNKNOWN\"]},"
            + "{\"id\":\"T3\",\"title\":\"Three\",\"meetsRequirements\":[\"R1\"]}]}";

    TierEvalCase.ValidationVerdict verdict = caseUnderTest.validate("", "submitOutline", args);

    assertThat(verdict.correctness()).isEqualTo(85);
    assertThat(verdict.detail()).contains("some tasks missing id/title");
    assertThat(verdict.detail()).contains("requirement coverage=1/3");
  }

  @Test
  void requirementLinksMustResolveToDeclaredNonBlankRequirementIds() {
    TierEvalCase caseUnderTest = PlanningCases.all().get(0);
    String args =
        "{\"planTitle\":\"Plan\",\"planSummary\":\"Summary\","
            + "\"projectType\":\"REST\",\"requirements\":["
            + "{\"id\":\"R1\",\"text\":\"first\"}],\"tasks\":["
            + "{\"id\":\"T1\",\"title\":\"One\",\"meetsRequirements\":[\" \" ]},"
            + "{\"id\":\"T2\",\"title\":\"Two\",\"meetsRequirements\":[\"R1\",\"R1\"]},"
            + "{\"id\":\"T3\",\"title\":\"Three\",\"meetsRequirements\":[\"R1\"]}]}";

    TierEvalCase.ValidationVerdict verdict = caseUnderTest.validate("", "submitOutline", args);

    assertThat(verdict.correctness()).isEqualTo(95);
    assertThat(verdict.detail()).contains("requirement coverage=1/3");
  }
}
