package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.PlannedTask.Complexity;
import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins for {@link SequentialExecutor#composeSystemPrompt}'s provider-specific tuning fragment
 * injection (T3.10, RL-2026-05-052). Each provider has characteristic failure modes the
 * per-provider fragments correct; this test pins that:
 *
 * <ul>
 *   <li>The fragment is loaded + appended ONLY when the flag is ON.
 *   <li>Each known provider resolves to its specific fragment.
 *   <li>An unknown provider gracefully resolves to "" (no append).
 *   <li>A null provider is treated as "no provider tuning" (back-compat 5-arg path).
 * </ul>
 */
class ProviderPromptTuningTest {

  private SequentialExecutor newExecutor(FeatureFlagService ffs) {
    return new SequentialExecutor(
        mock(com.navneet.executespec.engine.WorkerAgent.class),
        mock(com.navneet.executespec.tool.BuildProjectTool.class),
        new com.fasterxml.jackson.databind.ObjectMapper(),
        mock(com.navneet.executespec.observability.Metrics.class),
        mock(com.navneet.executespec.sse.SseBroadcaster.class),
        mock(com.navneet.executespec.state.repository.AgentTaskRepository.class),
        mock(com.navneet.executespec.router.ModelResolver.class),
        mock(com.navneet.executespec.router.ModelMatrixProperties.class),
        mock(com.navneet.executespec.project.rag.RetrievalService.class),
        mock(com.navneet.executespec.smoketest.WorkspaceTestRunner.class),
        ffs,
        mock(com.navneet.executespec.context.ContextStore.class),
        mock(com.navneet.executespec.cost.RunCostTracker.class),
        mock(com.navneet.executespec.deployment.GcsTaskCheckpointService.class),
        mock(com.navneet.executespec.router.AvailableProviderResolver.class),
        null, null);
  }

  private PlannedTask task() {
    return PlannedTask.builder()
        .id("T1")
        .title("test")
        .description("test")
        .complexity(Complexity.LOW)
        .touchesFiles(List.of())
        .dependsOn(List.of())
        .hardConstraints(List.of())
        .build();
  }

  @Test
  @DisplayName("flag OFF: provider fragment not appended even when provider is known")
  void flagOff_noFragment() {
    FeatureFlagService ffs = mock(FeatureFlagService.class);
    when(ffs.isEnabled(SequentialExecutor.FLAG_PROVIDER_TUNING)).thenReturn(false);
    String prompt =
        newExecutor(ffs).composeSystemPrompt(task(), null, null, null, null, "anthropic");
    assertThat(prompt).doesNotContain("PROVIDER-SPECIFIC BUILD NOTES");
  }

  @Test
  @DisplayName("flag ON + anthropic: Anthropic fragment appended")
  void flagOn_anthropic_appended() {
    FeatureFlagService ffs = mock(FeatureFlagService.class);
    when(ffs.isEnabled(SequentialExecutor.FLAG_PROVIDER_TUNING)).thenReturn(true);
    String prompt =
        newExecutor(ffs).composeSystemPrompt(task(), null, null, null, null, "anthropic");
    assertThat(prompt).contains("PROVIDER-SPECIFIC BUILD NOTES (Anthropic");
  }

  @Test
  @DisplayName("flag ON + openai: OpenAI fragment appended")
  void flagOn_openai_appended() {
    FeatureFlagService ffs = mock(FeatureFlagService.class);
    when(ffs.isEnabled(SequentialExecutor.FLAG_PROVIDER_TUNING)).thenReturn(true);
    String prompt = newExecutor(ffs).composeSystemPrompt(task(), null, null, null, null, "openai");
    assertThat(prompt).contains("PROVIDER-SPECIFIC BUILD NOTES (OpenAI");
  }

  @Test
  @DisplayName("flag ON + gemini: Gemini fragment appended")
  void flagOn_gemini_appended() {
    FeatureFlagService ffs = mock(FeatureFlagService.class);
    when(ffs.isEnabled(SequentialExecutor.FLAG_PROVIDER_TUNING)).thenReturn(true);
    String prompt = newExecutor(ffs).composeSystemPrompt(task(), null, null, null, null, "gemini");
    assertThat(prompt).contains("PROVIDER-SPECIFIC BUILD NOTES (Gemini");
  }

  @Test
  @DisplayName("flag ON + fireworks: Fireworks fragment appended")
  void flagOn_fireworks_appended() {
    FeatureFlagService ffs = mock(FeatureFlagService.class);
    when(ffs.isEnabled(SequentialExecutor.FLAG_PROVIDER_TUNING)).thenReturn(true);
    String prompt =
        newExecutor(ffs).composeSystemPrompt(task(), null, null, null, null, "fireworks");
    assertThat(prompt).contains("PROVIDER-SPECIFIC BUILD NOTES (Fireworks");
  }

  @Test
  @DisplayName("flag ON + deepseek: DeepSeek fragment appended")
  void flagOn_deepseek_appended() {
    FeatureFlagService ffs = mock(FeatureFlagService.class);
    when(ffs.isEnabled(SequentialExecutor.FLAG_PROVIDER_TUNING)).thenReturn(true);
    String prompt =
        newExecutor(ffs).composeSystemPrompt(task(), null, null, null, null, "deepseek");
    assertThat(prompt).contains("PROVIDER-SPECIFIC BUILD NOTES (DeepSeek");
  }

  @Test
  @DisplayName("flag ON + openrouter: OpenRouter fragment appended")
  void flagOn_openrouter_appended() {
    FeatureFlagService ffs = mock(FeatureFlagService.class);
    when(ffs.isEnabled(SequentialExecutor.FLAG_PROVIDER_TUNING)).thenReturn(true);
    String prompt =
        newExecutor(ffs).composeSystemPrompt(task(), null, null, null, null, "openrouter");
    assertThat(prompt).contains("PROVIDER-SPECIFIC BUILD NOTES (OpenRouter");
  }

  @Test
  @DisplayName("flag ON + unknown provider: no fragment, no error")
  void flagOn_unknown_silent() {
    FeatureFlagService ffs = mock(FeatureFlagService.class);
    when(ffs.isEnabled(SequentialExecutor.FLAG_PROVIDER_TUNING)).thenReturn(true);
    String prompt = newExecutor(ffs).composeSystemPrompt(task(), null, null, null, null, "made-up");
    assertThat(prompt).doesNotContain("PROVIDER-SPECIFIC BUILD NOTES");
  }

  @Test
  @DisplayName("flag ON + null provider: no fragment (5-arg back-compat path)")
  void flagOn_nullProvider_noFragment() {
    FeatureFlagService ffs = mock(FeatureFlagService.class);
    when(ffs.isEnabled(SequentialExecutor.FLAG_PROVIDER_TUNING)).thenReturn(true);
    String prompt = newExecutor(ffs).composeSystemPrompt(task(), null, null, null, null);
    assertThat(prompt).doesNotContain("PROVIDER-SPECIFIC BUILD NOTES");
  }

  @Test
  @DisplayName("flag ON: build prompt does not inherit patch-only benchmark directives")
  void flagOn_buildPromptExcludesPatchOnlyGuidance() {
    FeatureFlagService ffs = mock(FeatureFlagService.class);
    when(ffs.isEnabled(SequentialExecutor.FLAG_PROVIDER_TUNING)).thenReturn(true);
    String prompt =
        newExecutor(ffs).composeSystemPrompt(task(), null, null, null, null, "anthropic");

    assertThat(prompt).contains("PROVIDER-SPECIFIC BUILD NOTES (Anthropic");
    assertThat(prompt)
        .doesNotContain("PROVIDER-SPECIFIC PATCH NOTES")
        .doesNotContain("patch SOURCE ONLY")
        .doesNotContain("scratch reproduction");
  }
}
