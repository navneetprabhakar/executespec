package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ContextPlannerTest {
  @Test
  void reservesOutputVerificationAndHandoffBeforeSelectingEvidence() {
    var planner = new ContextPlanner();
    var plan =
        planner.plan(
            new ContextPlanner.Window(1000, 200, 100, 100, 100, 100),
            List.of(
                new ContextPlanner.Item(
                    "goal", ContextPlanner.Kind.GOAL, "user", "sha", null, 100, 100, true),
                new ContextPlanner.Item(
                    "evidence",
                    ContextPlanner.Kind.TASK_EVIDENCE,
                    "search",
                    "sha",
                    "src/A.java",
                    250,
                    80,
                    true),
                new ContextPlanner.Item(
                    "old",
                    ContextPlanner.Kind.EXTERNAL_ARTIFACT,
                    "log",
                    "sha",
                    "build.log",
                    300,
                    10,
                    false)));

    assertThat(plan.usableInputTokens()).isEqualTo(400);
    assertThat(plan.selected())
        .extracting(ContextPlanner.Item::id)
        .containsExactly("goal", "evidence");
    assertThat(plan.omitted()).extracting(ContextPlanner.Item::id).containsExactly("old");
    assertThat(plan.remainingTokens()).isEqualTo(50);
    assertThat(plan.omittedTokens()).isEqualTo(300);
  }
}
