package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Paths;
import java.util.Optional;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Skip-rule pins for {@link ToolOutputCompactor}. The compactor is the worker's prompt-cache
 * discipline (RL-2026-05-052 Phase 5+6), so its safety properties — never call the LLM when the
 * flag is off / the output is small / the tool is in the skip list / it's the immediately-prior
 * call — are load-bearing. A regression here either burns cost on every short read OR (worse)
 * compacts away the most-recent tool output the worker is about to act on.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolOutputCompactor — skip rules")
class ToolOutputCompactorTest {

  @Mock private LlmRouter router;
  @Mock private ModelResolver modelResolver;
  @Mock private PromptAssembler promptAssembler;
  @Mock private RunCostTracker costTracker;
  @Mock private FeatureFlagService featureFlagService;

  private ToolOutputCompactor compactor;

  private static final ExecutionContext CTX =
      new ExecutionContext(
          "run-xyz", "task-1", Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath());

  /** Sized just above the 3 KB threshold so the size guard alone never skips. */
  private static final String BIG_RAW =
      "x".repeat(ToolOutputCompactor.COMPACT_THRESHOLD_BYTES + 100);

  @BeforeEach
  void setUp() {
    compactor =
        new ToolOutputCompactor(
            router, modelResolver, promptAssembler, costTracker, featureFlagService);
  }

  @Test
  @DisplayName("flag OFF → returns empty without touching the LLM")
  void skip_whenFlagOff() {
    when(featureFlagService.isEnabled(ToolOutputCompactor.FLAG_ENABLED)).thenReturn(false);

    Optional<ToolOutputCompactor.Compacted> result =
        compactor.compactIfNeeded("readFile", BIG_RAW, "the goal", false, CTX);

    assertThat(result).isEmpty();
    verify(router, never()).call(any());
    verify(modelResolver, never()).resolve(anyString(), any(LlmProvider.class));
  }

  @Test
  @DisplayName("output smaller than 3 KB → returns empty (no round-trip)")
  void skip_whenBelowThreshold() {
    when(featureFlagService.isEnabled(ToolOutputCompactor.FLAG_ENABLED)).thenReturn(true);

    String tiny = "x".repeat(ToolOutputCompactor.COMPACT_THRESHOLD_BYTES - 1);

    Optional<ToolOutputCompactor.Compacted> result =
        compactor.compactIfNeeded("readFile", tiny, "the goal", false, CTX);

    assertThat(result).isEmpty();
    verify(router, never()).call(any());
  }

  @Test
  @DisplayName(
      "tool in SKIP_TOOLS (writeFile/editFile/gitCommit/completeTask/gitDiff/gitStatus) → empty")
  void skip_whenToolInSkipList() {
    when(featureFlagService.isEnabled(ToolOutputCompactor.FLAG_ENABLED)).thenReturn(true);

    for (String tool : ToolOutputCompactor.SKIP_TOOLS) {
      Optional<ToolOutputCompactor.Compacted> result =
          compactor.compactIfNeeded(tool, BIG_RAW, "the goal", false, CTX);
      assertThat(result).as("skip for tool=%s", tool).isEmpty();
    }
    verify(router, never()).call(any());
  }

  @Test
  @DisplayName("isImmediatelyPriorToolCall=true → returns empty (preserve verbatim)")
  void skip_whenImmediatelyPrior() {
    when(featureFlagService.isEnabled(ToolOutputCompactor.FLAG_ENABLED)).thenReturn(true);

    Optional<ToolOutputCompactor.Compacted> result =
        compactor.compactIfNeeded("readFile", BIG_RAW, "the goal", true, CTX);

    assertThat(result).isEmpty();
    verify(router, never()).call(any());
  }

  @Test
  @DisplayName("null raw output → returns empty (defensive)")
  void skip_whenNullRaw() {
    when(featureFlagService.isEnabled(ToolOutputCompactor.FLAG_ENABLED)).thenReturn(true);

    Optional<ToolOutputCompactor.Compacted> result =
        compactor.compactIfNeeded("readFile", null, "the goal", false, CTX);

    assertThat(result).isEmpty();
    verify(router, never()).call(any());
  }

  @Test
  @DisplayName("null context → returns empty (no run/task to attribute the call to)")
  void skip_whenNullCtx() {
    when(featureFlagService.isEnabled(ToolOutputCompactor.FLAG_ENABLED)).thenReturn(true);

    Optional<ToolOutputCompactor.Compacted> result =
        compactor.compactIfNeeded("readFile", BIG_RAW, "the goal", false, null);

    assertThat(result).isEmpty();
    verify(router, never()).call(any());
  }

  @Test
  @DisplayName("LLM throws → returns empty (fail-safe: worker NEVER loses tool output)")
  void failSafe_whenLlmThrows() {
    when(featureFlagService.isEnabled(ToolOutputCompactor.FLAG_ENABLED)).thenReturn(true);
    when(modelResolver.resolve(anyString(), any(LlmProvider.class)))
        .thenReturn(
            ResolvedModel.builder()
                .actualProvider(LlmProvider.ANTHROPIC)
                .model("claude-haiku-4-5-20251001")
                .build());
    when(promptAssembler.assemble(anyString(), any())).thenReturn("compactor prompt");
    when(router.call(any(LlmRequest.class))).thenThrow(new RuntimeException("boom"));

    Optional<ToolOutputCompactor.Compacted> result =
        compactor.compactIfNeeded("readFile", BIG_RAW, "the goal", false, CTX);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("happy path → returns Compacted with shrunken summary + token attribution")
  void compacts_whenAllRulesPass() {
    when(featureFlagService.isEnabled(ToolOutputCompactor.FLAG_ENABLED)).thenReturn(true);
    when(modelResolver.resolve(anyString(), any(LlmProvider.class)))
        .thenReturn(
            ResolvedModel.builder()
                .actualProvider(LlmProvider.ANTHROPIC)
                .model("claude-haiku-4-5-20251001")
                .build());
    when(promptAssembler.assemble(anyString(), any())).thenReturn("compactor prompt");

    String summaryText = "[compacted from " + BIG_RAW.length() + " bytes] short.";
    ChatResponse resp = Mockito.mock(ChatResponse.class);
    Generation gen = Mockito.mock(Generation.class);
    AssistantMessage msg = Mockito.mock(AssistantMessage.class);
    Mockito.when(resp.getResult()).thenReturn(gen);
    Mockito.when(gen.getOutput()).thenReturn(msg);
    Mockito.when(msg.getText()).thenReturn(summaryText);
    ChatResponseMetadata meta = Mockito.mock(ChatResponseMetadata.class);
    Usage usage = Mockito.mock(Usage.class);
    Mockito.when(resp.getMetadata()).thenReturn(meta);
    Mockito.when(meta.getUsage()).thenReturn(usage);
    Mockito.when(usage.getPromptTokens()).thenReturn(900);
    Mockito.when(usage.getCompletionTokens()).thenReturn(40);
    when(router.call(any(LlmRequest.class))).thenReturn(resp);
    when(costTracker.computeCost(anyString(), anyString(), Mockito.eq(900), Mockito.eq(40)))
        .thenReturn(0.000123);

    Optional<ToolOutputCompactor.Compacted> result =
        compactor.compactIfNeeded("readFile", BIG_RAW, "the goal", false, CTX);

    assertThat(result).isPresent();
    assertThat(result.get().summary()).isEqualTo(summaryText);
    assertThat(result.get().rawBytes()).isEqualTo(BIG_RAW.length());
    assertThat(result.get().compactedBytes()).isEqualTo(summaryText.length());
    assertThat(result.get().tokensIn()).isEqualTo(900L);
    assertThat(result.get().tokensOut()).isEqualTo(40L);
    assertThat(result.get().costUsd()).isEqualTo(0.000123);
  }
}
