package com.navneet.executespec.deployment.preview.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootSignals;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.Verdict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/**
 * RL-2026-05-066 (verifyBoot) — pins the {@link PreviewVerifier} fail-open contract (the same shape
 * as {@code WorkspaceTestRunnerTest}): every "can't trustworthily verify" path collapses to {@link
 * Optional#empty()} so the scorer keeps its compile-only BUILD signal, and only a real boot-engine
 * result yields a verdict. Because no {@link PreviewBootEngine} bean exists until preview Phase 0,
 * production verifyBoot is inert today — these tests inject fakes to exercise the wiring.
 */
class PreviewVerifierTest {

  private final ObjectMapper json = new ObjectMapper();

  @SuppressWarnings("unchecked")
  private PreviewVerifier verifier(boolean buildpackOn, PreviewBootEngine engine) {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(eq(PreviewVerifier.FLAG_BUILDPACK))).thenReturn(buildpackOn);
    ObjectProvider<PreviewBootEngine> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(engine);
    return new PreviewVerifier(json, flags, provider);
  }

  private PreviewBootEngine healthyEngine() {
    return (workspace, recipe, runId) ->
        new BootSignals(
            true, true, 200, recipe.healthPath(), 300, recipe.startTimeoutSec() * 1000L);
  }

  private void writeRecipe(Path ws, String content) throws Exception {
    Path dir = ws.resolve(".agent-context");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("run-recipe.json"), content);
  }

  @Test
  void no_recipe_fails_open(@TempDir Path ws) {
    assertThat(verifier(true, healthyEngine()).verify(ws, "r1")).isEmpty();
  }

  @Test
  void null_workspace_fails_open() {
    assertThat(verifier(true, healthyEngine()).verify(null, "r1")).isEmpty();
  }

  @Test
  void capability_disabled_fails_open(@TempDir Path ws) throws Exception {
    writeRecipe(ws, "{\"port\":3000,\"healthPath\":\"/health\",\"startTimeoutSec\":30}");
    assertThat(verifier(false, healthyEngine()).verify(ws, "r1")).isEmpty();
  }

  @Test
  void no_engine_fails_open(@TempDir Path ws) throws Exception {
    writeRecipe(ws, "{\"port\":3000}");
    assertThat(verifier(true, null).verify(ws, "r1")).isEmpty();
  }

  @Test
  void malformed_recipe_fails_open(@TempDir Path ws) throws Exception {
    writeRecipe(ws, "not json at all {{{");
    assertThat(verifier(true, healthyEngine()).verify(ws, "r1")).isEmpty();
  }

  @Test
  void engine_exception_fails_open(@TempDir Path ws) throws Exception {
    writeRecipe(ws, "{\"port\":3000}");
    PreviewBootEngine boom =
        (workspace, recipe, runId) -> {
          throw new RuntimeException("docker daemon unreachable");
        };
    assertThat(verifier(true, boom).verify(ws, "r1")).isEmpty();
  }

  @Test
  void healthy_boot_returns_verdict(@TempDir Path ws) throws Exception {
    writeRecipe(ws, "{\"port\":3000,\"healthPath\":\"/health\",\"startTimeoutSec\":30}");
    Optional<BootVerification> v = verifier(true, healthyEngine()).verify(ws, "r1");
    assertThat(v).isPresent();
    assertThat(v.get().verdict()).isEqualTo(Verdict.HEALTHY);
    assertThat(v.get().healthy()).isTrue();
  }

  @Test
  void failed_boot_is_classified_not_swallowed(@TempDir Path ws) throws Exception {
    writeRecipe(ws, "{\"port\":3000,\"healthPath\":\"/health\",\"startTimeoutSec\":5}");
    // build ok, started, but health never answered before the 5s timeout → TIMED_OUT (a real,
    // non-empty verdict — fail-open is for "couldn't verify", NOT for "verified unhealthy").
    PreviewBootEngine timesOut =
        (workspace, recipe, runId) ->
            new BootSignals(true, true, null, recipe.healthPath(), 5000, 5000);
    Optional<BootVerification> v = verifier(true, timesOut).verify(ws, "r1");
    assertThat(v).isPresent();
    assertThat(v.get().verdict()).isEqualTo(Verdict.TIMED_OUT);
    assertThat(v.get().healthy()).isFalse();
  }
}
