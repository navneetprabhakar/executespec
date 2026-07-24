package com.navneet.executespec.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.report.entity.ValidationReport;

import org.junit.jupiter.api.Test;

class ChatEvidenceTruthPolicyTest {

  @Test
  void replacesBuildToPytestInferenceWhenConcreteTestCountsAreAbsent() {
    ValidationReport report = new ValidationReport();
    report.setFinalBuildPassed(true);
    report.setTestsPassed(0);
    report.setTestsTotal(0);

    String result =
        ChatEvidenceTruthPolicy.enforce(
            "`finalBuildPassed: true` means `pytest` exited with a zero return code.", report);

    assertThat(result)
        .contains("configured build command passed")
        .contains("does not prove that pytest")
        .contains("behavioral correctness is not proven")
        .doesNotContain("pytest exited without");
  }

  @Test
  void preservesAnswerWhenConcreteTestCountsExist() {
    ValidationReport report = new ValidationReport();
    report.setTestsPassed(9);
    report.setTestsTotal(10);

    String reply = "The build passed, but tests passed only 9/10.";

    assertThat(ChatEvidenceTruthPolicy.enforce(reply, report)).isEqualTo(reply);
  }

  @Test
  void preservesHonestUnknownTestStatement() {
    ValidationReport report = new ValidationReport();
    report.setTestsTotal(0);
    String reply = "The build passed, but that does not prove tests passed.";

    assertThat(ChatEvidenceTruthPolicy.enforce(reply, report)).isEqualTo(reply);
  }
}
