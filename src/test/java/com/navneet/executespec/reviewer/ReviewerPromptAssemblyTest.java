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
}
