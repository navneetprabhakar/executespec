package com.navneet.executespec.terminalbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.Test;

class TerminalBenchProviderPromptTest {

  @Test
  void deepseekTerminalPromptIncludesTerminalSpecificToolDisciplineWhenFlagEnabled() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.provider-prompt-tuning.enabled")).thenReturn(true);

    String prompt = service(flags).systemPromptForProvider("deepseek");

    assertThat(prompt).contains("PROVIDER-SPECIFIC TERMINAL NOTES (DeepSeek)");
    assertThat(prompt).contains("EMIT A TOOL CALL ON EVERY TURN");
    assertThat(prompt).contains("verifier-shaped command");
    assertThat(prompt).doesNotContain("PROVIDER-SPECIFIC PATCH NOTES").doesNotContain("SWE-bench");
  }

  @Test
  void unknownProviderFallsBackToBaseTerminalPrompt() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.provider-prompt-tuning.enabled")).thenReturn(true);

    String prompt = service(flags).systemPromptForProvider("made-up");

    assertThat(prompt).contains("You are an autonomous terminal agent");
    assertThat(prompt).doesNotContain("PROVIDER-SPECIFIC TERMINAL NOTES");
  }

  private static TerminalBenchRunService service(FeatureFlagService flags) {
    return new TerminalBenchRunService(
        mock(com.navneet.executespec.state.StateStore.class),
        mock(com.navneet.executespec.tool.remote.RemoteToolDispatcher.class),
        mock(com.navneet.executespec.engine.WorkerAgent.class),
        mock(com.navneet.executespec.cost.RunCostTracker.class),
        mock(com.navneet.executespec.sse.SseBroadcaster.class),
        mock(com.navneet.executespec.auth.security.JwtUtil.class),
        flags);
  }
}
