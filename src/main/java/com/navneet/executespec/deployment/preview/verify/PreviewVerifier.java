package com.navneet.executespec.deployment.preview.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootSignals;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The verifyBoot runner (RL-2026-05-066, preview Phase 1) — the boot-verification sibling of {@link
 * com.navneet.executespec.smoketest.WorkspaceTestRunner}. It reads the agent-authored {@link
 * RunRecipe}, hands the build-and-boot off to the {@link PreviewBootEngine} (preview Phase 0), and
 * classifies the result with the pure {@link BootHealthEvaluator}. The deterministic verdict feeds
 * the BUILD scoring slot, replacing the LLM's guess about whether the app actually works.
 *
 * <p><b>Fail-open</b> — exactly the {@code WorkspaceTestRunner} contract. No recipe, no boot engine
 * wired, the buildpack capability disabled, or any exception during the boot all collapse to {@link
 * Optional#empty()}: the scorer then keeps today's compile-only BUILD signal. The headline can only
 * ever become MORE honest, never break report generation. Because the boot engine bean does not
 * exist until Phase 0, this is completely inert today.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PreviewVerifier {

  /** Workspace-relative path to the agent-authored run recipe. */
  static final String RECIPE_PATH = ".agent-context/run-recipe.json";

  /** Capability flag — the build-and-boot path is enabled (preview Phase 0). Default OFF (V227). */
  static final String FLAG_BUILDPACK = "preview.buildpack.enabled";

  private final ObjectMapper objectMapper;
  private final FeatureFlagService featureFlagService;

  /**
   * Optional — no implementation exists until preview Phase 0. {@link ObjectProvider} keeps
   * constructor injection working when there is no candidate bean (no startup failure), unlike a
   * plain {@code final PreviewBootEngine}.
   */
  private final ObjectProvider<PreviewBootEngine> bootEngineProvider;

  /**
   * Resolve the recipe, build + boot + health-check the app, and classify the result. Never throws
   * — every failure mode collapses to {@link Optional#empty()} (fail-open).
   */
  public Optional<BootVerification> verify(Path workspace, String runId) {
    if (workspace == null) return Optional.empty();

    Optional<RunRecipe> recipe = readRecipe(workspace);
    if (recipe.isEmpty()) {
      log.debug("PreviewVerifier run={} no {} — boot not verified", runId, RECIPE_PATH);
      return Optional.empty();
    }

    // Capability gate: the Phase-0 build-and-boot engine must be both present AND enabled. Until
    // then verifyBoot is inert and fails open, so the BUILD slot keeps its compile-only signal.
    PreviewBootEngine engine =
        bootEngineProvider != null ? bootEngineProvider.getIfAvailable() : null;
    if (engine == null
        || featureFlagService == null
        || !featureFlagService.isEnabled(FLAG_BUILDPACK)) {
      log.debug("PreviewVerifier run={} boot engine unavailable or disabled — fail-open", runId);
      return Optional.empty();
    }

    try {
      BootSignals signals = engine.boot(workspace, recipe.get(), runId);
      BootVerification verdict = BootHealthEvaluator.evaluate(signals);
      log.info("PreviewVerifier run={} → {} ({})", runId, verdict.verdict(), verdict.reason());
      return Optional.of(verdict);
    } catch (Exception e) {
      log.warn("PreviewVerifier run={} boot attempt failed (fail-open): {}", runId, e.getMessage());
      return Optional.empty();
    }
  }

  /** Read + parse {@code .agent-context/run-recipe.json}. Empty when absent or unparseable. */
  Optional<RunRecipe> readRecipe(Path workspace) {
    try {
      Path p = workspace.resolve(RECIPE_PATH);
      if (!Files.isRegularFile(p)) return Optional.empty();
      JsonNode node = objectMapper.readTree(Files.readString(p));
      return Optional.ofNullable(RunRecipe.fromJson(node));
    } catch (Exception e) {
      log.debug("PreviewVerifier could not read {}: {}", RECIPE_PATH, e.getMessage());
      return Optional.empty();
    }
  }
}
