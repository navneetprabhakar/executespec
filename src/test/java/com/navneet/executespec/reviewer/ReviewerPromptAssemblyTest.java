package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.agent.PromptAssembler;

import org.junit.jupiter.api.Test;

class ReviewerPromptAssemblyTest {

  @Test
  void reviewerUsesCanonicalPromptAssembler() {
    PromptAssembler assembler = mock(PromptAssembler.class);
    when(assembler.assemble("reviewer-general.md")).thenReturn("assembled reviewer prompt");
    ReviewerAgent agent = new ReviewerAgent(null, null, null, null, null, assembler, null, null);

    assertThat(agent.loadPrompt("prompts/reviewer-general.md"))
        .isEqualTo("assembled reviewer prompt");
    verify(assembler).assemble("reviewer-general.md");
  }

  @Test
  void wiringRecoveryInstructionIsAClasspathPrompt() throws Exception {
    try (var stream =
        ReviewerPromptAssemblyTest.class
            .getClassLoader()
            .getResourceAsStream("prompts/reviewer-wiring-recovery.md")) {
      assertThat(stream).isNotNull();
      String prompt = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      String normalized = prompt.replaceAll("\\s+", " ");
      assertThat(normalized)
          .contains("Same-route protocol recovery")
          .contains("VERDICT: CLEAN")
          .contains("Do not infer success");
    }
  }

  @Test
  void generalReviewerUsesDeterministicFileMeasurementsAndOneBuild() throws Exception {
    try (var stream =
        ReviewerPromptAssemblyTest.class
            .getClassLoader()
            .getResourceAsStream("prompts/reviewer-general.md")) {
      assertThat(stream).isNotNull();
      String prompt = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

      assertThat(prompt)
          .contains("includeMetadata: true")
          .contains("never invent an approximate count")
          .contains("never repeat a successful");
    }
  }
}
