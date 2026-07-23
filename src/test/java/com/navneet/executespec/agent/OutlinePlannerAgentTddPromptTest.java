package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutlinePlannerAgentTddPromptTest {

  @Test
  void tdd_mode_keeps_tests_and_implementation_in_one_cohesive_task() {
    String block = OutlinePlannerAgent.renderTddBlock(true);

    assertThat(block)
        .contains("Do NOT split one feature into a test-only task")
        .contains("must first add failing")
        .contains("tests, then implement")
        .contains("run those tests before completion");
  }

  @Test
  void non_tdd_mode_adds_no_tdd_instructions() {
    assertThat(OutlinePlannerAgent.renderTddBlock(false)).isEmpty();
  }
}
