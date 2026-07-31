package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class PlannerPromptAssemblyTest {

  @Test
  void taskExpanderUsesCanonicalPromptAssembler() {
    PromptAssembler assembler = mock(PromptAssembler.class);
    when(assembler.assemble("task-expander.md")).thenReturn("assembled task prompt");
    TaskExpanderAgent agent = new TaskExpanderAgent(null, null, null, assembler);

    assertThat(agent.loadPrompt()).isEqualTo("assembled task prompt");
    verify(assembler).assemble("task-expander.md");
  }

  @Test
  void coherencePlannerUsesCanonicalPromptAssembler() {
    PromptAssembler assembler = mock(PromptAssembler.class);
    when(assembler.assemble("coherence-planner.md")).thenReturn("assembled coherence prompt");
    CoherencePlannerAgent agent = new CoherencePlannerAgent(null, null, null, assembler, null);

    assertThat(agent.loadPrompt()).isEqualTo("assembled coherence prompt");
    verify(assembler).assemble("coherence-planner.md");
  }
}
