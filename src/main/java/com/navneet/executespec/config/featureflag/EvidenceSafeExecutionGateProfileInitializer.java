package com.navneet.executespec.config.featureflag;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps local and dev on the same release-validation policy without changing production.
 *
 * <p>These gates are server-side and do not add tools to provider schemas. The older {@code
 * worker.build-tdd.enabled} and {@code build.test-exec-gate.enabled} flags remain untouched because
 * their model-visible/tool-schema regression is documented in V239. Ask and static screen rendering
 * are enabled in both validation environments; remote sandbox execution remains fail-closed until
 * its independent release gate is explicitly graduated.
 */
@Component
@Profile({"local", "dev"})
@RequiredArgsConstructor
@Slf4j
class EvidenceSafeExecutionGateProfileInitializer implements ApplicationRunner {

  static final Set<String> REQUIRED_ENABLED_FLAGS =
      Set.of(
          "execution.goal-decision.enforcement.enabled",
          "preview.render-agent.enabled",
          "spec.chat.enabled",
          "worker.verify-before-complete.enabled",
          "worker.auto-verify.enabled");

  static final Set<String> REQUIRED_DISABLED_FLAGS =
      Set.of(
          "sandbox.lifecycle.enabled",
          "sandbox.runtime.container-strict-v1.enabled",
          "sandbox.runtime.dispatch.enabled",
          "sandbox.runtime.toolchain-readiness.enabled",
          "testgen.live-preview-network.enabled");

  private final FeatureFlagService featureFlagService;

  @Override
  public void run(ApplicationArguments args) {
    Map<String, String> current =
        featureFlagService.listAll().stream()
            .collect(Collectors.toMap(FeatureFlag::getFlagKey, FeatureFlag::getFlagValue));
    Set<String> enabled = new LinkedHashSet<>();
    for (String key : REQUIRED_ENABLED_FLAGS) {
      if (!current.containsKey(key)) {
        log.error("Required local/dev release-validation flag is missing: {}", key);
        continue;
      }
      if (!"true".equalsIgnoreCase(current.get(key))) {
        featureFlagService.update(key, "true", "system:local-dev-release-gates");
        enabled.add(key);
      }
    }
    Set<String> disabled = new LinkedHashSet<>();
    for (String key : REQUIRED_DISABLED_FLAGS) {
      if (!current.containsKey(key)) {
        log.error("Required local/dev unreleased execution flag is missing: {}", key);
        continue;
      }
      if (!"false".equalsIgnoreCase(current.get(key))) {
        featureFlagService.update(key, "false", "system:local-dev-release-gates");
        disabled.add(key);
      }
    }
    if (!enabled.isEmpty()) {
      log.warn("Enabled local/dev release-validation gates: {}", enabled);
    }
    if (!disabled.isEmpty()) {
      log.warn("Disabled local/dev unreleased execution gates: {}", disabled);
    }
  }
}
