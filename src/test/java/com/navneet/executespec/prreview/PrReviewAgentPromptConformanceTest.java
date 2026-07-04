package com.navneet.executespec.prreview;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PrReviewAgentPromptConformanceTest {

  @Test
  void promptAdvertisesOnlyTheBoundSubmitReviewTool() throws IOException {
    String prompt =
        new ClassPathResource("prompts/codereview/pr-review-agent.md")
            .getContentAsString(StandardCharsets.UTF_8);
    var tool = new PrReviewAgent.SubmitReviewToolCallback();

    assertThat(tool.getToolDefinition().name()).isEqualTo("submitReview");
    assertThat(prompt).contains("Call the `submitReview` tool exactly once");
    assertThat(prompt).doesNotContain("readFile").doesNotContain("read_file");
  }
}
