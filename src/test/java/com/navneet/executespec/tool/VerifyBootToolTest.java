package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.Verdict;
import com.navneet.executespec.deployment.preview.verify.PreviewVerifier;

import org.junit.jupiter.api.Test;

/**
 * RL-2026-05-066 (verifyBoot) — pins the {@link VerifyBootTool} flag-gate + Ok-envelope shape
 * (mirrors {@code WorkspaceRunTestsTool}): OFF → {@code available:false}; ON but unverifiable →
 * fail-open {@code available:true, verified:false}; a real verdict surfaces verbatim. Never an Err
 * on a normal run, and never a false "healthy".
 */
class VerifyBootToolTest {

  private final ObjectMapper json = new ObjectMapper();
  private final ExecutionContext ctx =
      new ExecutionContext("r1", "t1", Path.of("/tmp/verifyboot-test-ws").toAbsolutePath());

  private JsonNode run(boolean flagOn, Optional<BootVerification> verdict) throws Exception {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(eq(VerifyBootTool.FLAG_VERIFY_BOOT))).thenReturn(flagOn);
    PreviewVerifier verifier = mock(PreviewVerifier.class);
    when(verifier.verify(any(), any())).thenReturn(verdict);
    ToolResult r = new VerifyBootTool(verifier, flags, json).execute(json.createObjectNode(), ctx);
    assertThat(r).isInstanceOf(ToolResult.Ok.class);
    return json.readTree(((ToolResult.Ok) r).content());
  }

  @Test
  void disabled_returns_available_false() throws Exception {
    JsonNode n = run(false, Optional.empty());
    assertThat(n.path("available").asBoolean()).isFalse();
  }

  @Test
  void enabled_but_unverifiable_fails_open() throws Exception {
    JsonNode n = run(true, Optional.empty());
    assertThat(n.path("available").asBoolean()).isTrue();
    assertThat(n.path("verified").asBoolean()).isFalse();
  }

  @Test
  void healthy_verdict_surfaces_verified_true() throws Exception {
    JsonNode n =
        run(true, Optional.of(new BootVerification(Verdict.HEALTHY, true, "boots; /health 200")));
    assertThat(n.path("available").asBoolean()).isTrue();
    assertThat(n.path("verified").asBoolean()).isTrue();
    assertThat(n.path("verdict").asText()).isEqualTo("HEALTHY");
  }

  @Test
  void failed_verdict_is_reported_not_verified() throws Exception {
    JsonNode n =
        run(
            true,
            Optional.of(new BootVerification(Verdict.BUILD_FAILED, false, "image build failed")));
    assertThat(n.path("available").asBoolean()).isTrue();
    assertThat(n.path("verified").asBoolean()).isFalse();
    assertThat(n.path("verdict").asText()).isEqualTo("BUILD_FAILED");
    assertThat(n.path("reason").asText()).contains("build failed");
  }
}
