package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class ReviewerReadOnlyAttestationTest {

  @Test
  void finalAttestationExposesNoMutationTools() {
    var tools = ReviewerAgent.reviewerToolNames("reviewer-general", true);

    assertThat(tools)
        .contains("readFile", "searchFiles", "gitDiff", "buildProject", "completeTask")
        .doesNotContain("writeFile", "editFile", "shell", "runTests");
  }

  @Test
  void finalAttestationPromptExplicitlyRejectsRepairAuthority() {
    ReviewerRequest request =
        ReviewerRequest.builder()
            .provider("openrouter")
            .model("grok-4.20")
            .context(new ExecutionContext("run-1", "review", Path.of(".").toAbsolutePath()))
            .readOnly(true)
            .build();

    String prompt = ReviewerAgent.composeReviewerUserPrompt(request, "compile failed");

    assertThat(prompt)
        .contains("READ-ONLY FINAL ATTESTATION")
        .contains("Do not edit")
        .contains("not authority to repair");
  }
}
