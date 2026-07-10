package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

class ContextPlannerTest {

  private final ModelRegistry models = mock(ModelRegistry.class);
  private ContextPlanner planner;

  @BeforeEach
  void setUp() {
    planner = new ContextPlanner(models);
  }

  @Test
  void reservesOutputVerificationAndHandoffBeforeSelectingEvidence() {
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

  @Test
  void equalPriorityEvidenceUsesStableFreshKindAndIdTieBreakers() {
    var stale =
        new ContextPlanner.Item(
            "z-stale", ContextPlanner.Kind.GOAL, "search", "sha", null, 100, 50, false);
    var freshZ =
        new ContextPlanner.Item(
            "z-fresh", ContextPlanner.Kind.GOAL, "search", "sha", null, 100, 50, true);
    var freshA =
        new ContextPlanner.Item(
            "a-fresh", ContextPlanner.Kind.GOAL, "search", "sha", null, 100, 50, true);

    var plan =
        planner.plan(
            new ContextPlanner.Window(500, 0, 0, 0, 0, 0), List.of(stale, freshZ, freshA));

    assertThat(plan.selected())
        .extracting(ContextPlanner.Item::id)
        .containsExactly("a-fresh", "z-fresh", "z-stale");
  }

  @Test
  void unknownModelPreservesCallerFallback() {
    when(models.isKnownModel("future-model")).thenReturn(false);

    var decision =
        planner.planHeadroom(
            "provider", "future-model", 90_000, 8_000, List.of(new UserMessage("goal")), List.of());

    assertThat(decision.action()).isEqualTo(ContextPlanner.HeadroomAction.UNAVAILABLE);
    assertThat(decision.reason()).contains("not registered");
  }

  @Test
  void continuesWhenOutputAndSafetyReservesFitModelWindow() {
    knownModel("model-128k", 128_000, 16_000);

    var decision =
        planner.planHeadroom(
            "deepseek", "model-128k", 80_000, 8_000, List.of(new UserMessage("goal")), List.of());

    assertThat(decision.action()).isEqualTo(ContextPlanner.HeadroomAction.CONTINUE);
    assertThat(decision.contextWindowTokens()).isEqualTo(128_000);
    assertThat(decision.outputReserveTokens()).isEqualTo(8_000);
    assertThat(decision.safetyReserveTokens()).isEqualTo(10_240);
    assertThat(decision.availableHeadroomTokens()).isEqualTo(40_000);
  }

  @Test
  void compactsBeforeReservedOutputCanOverflowSmallerWindow() {
    knownModel("model-64k", 64_000, 16_000);

    var decision = planner.planHeadroom("openai", "model-64k", 52_000, 8_000, List.of(), List.of());

    assertThat(decision.action()).isEqualTo(ContextPlanner.HeadroomAction.COMPACT);
    assertThat(decision.availableHeadroomTokens()).isEqualTo(4_000);
    assertThat(decision.safetyReserveTokens()).isEqualTo(5_120);
  }

  @Test
  void currentMessagesAndToolSchemasProtectHeadroomWhenProviderUsageIsMissing() {
    knownModel("tiny-model", 10_000, 4_000);
    ToolCallback tool = mock(ToolCallback.class);
    when(tool.getToolDefinition())
        .thenReturn(
            DefaultToolDefinition.builder()
                .name("largeTool")
                .description("d".repeat(2_000))
                .inputSchema("s".repeat(12_000))
                .build());

    var decision =
        planner.planHeadroom(
            "provider",
            "tiny-model",
            0,
            4_000,
            List.of(new UserMessage("m".repeat(8_000))),
            List.of(tool));

    assertThat(decision.estimatedInputTokens()).isGreaterThan(5_000);
    assertThat(decision.action()).isEqualTo(ContextPlanner.HeadroomAction.COMPACT);
  }

  @Test
  void outputReserveNeverExceedsModelCapability() {
    knownModel("model", 32_000, 4_000);

    var decision = planner.planHeadroom("provider", "model", 1_000, 50_000, List.of(), List.of());

    assertThat(decision.outputReserveTokens()).isEqualTo(4_000);
  }

  private void knownModel(String model, int window, int maxOutput) {
    when(models.isKnownModel(model)).thenReturn(true);
    when(models.contextLimitFor(model)).thenReturn(window);
    when(models.maxOutputForModel(model)).thenReturn(maxOutput);
  }
}
