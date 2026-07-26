package com.navneet.executespec.config.featureflag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class EvidenceSafeExecutionGateProfileInitializerTest {

  @Test
  void enablesOnlyMissingEvidenceSafeFlags() throws Exception {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.listAll())
        .thenReturn(
            List.of(
                flag("execution.goal-decision.enforcement.enabled", "false"),
                flag("worker.verify-before-complete.enabled", "true"),
                flag("worker.auto-verify.enabled", "false"),
                flag("spec.chat.enabled", "false"),
                flag("preview.render-agent.enabled", "true"),
                flag("sandbox.lifecycle.enabled", "true"),
                flag("sandbox.runtime.dispatch.enabled", "true"),
                flag("sandbox.runtime.container-strict-v1.enabled", "true"),
                flag("sandbox.runtime.toolchain-readiness.enabled", "true"),
                flag("testgen.live-preview-network.enabled", "true"),
                flag("worker.build-tdd.enabled", "false"),
                flag("build.test-exec-gate.enabled", "false")));

    new EvidenceSafeExecutionGateProfileInitializer(flags).run(null);

    verify(flags)
        .update(
            "execution.goal-decision.enforcement.enabled",
            "true",
            "system:local-dev-release-gates");
    verify(flags).update("worker.auto-verify.enabled", "true", "system:local-dev-release-gates");
    verify(flags).update("spec.chat.enabled", "true", "system:local-dev-release-gates");
    verify(flags).update("sandbox.lifecycle.enabled", "false", "system:local-dev-release-gates");
    verify(flags)
        .update(
            "sandbox.runtime.container-strict-v1.enabled",
            "false",
            "system:local-dev-release-gates");
    verify(flags)
        .update("sandbox.runtime.dispatch.enabled", "false", "system:local-dev-release-gates");
    verify(flags)
        .update(
            "sandbox.runtime.toolchain-readiness.enabled",
            "false",
            "system:local-dev-release-gates");
    verify(flags)
        .update("testgen.live-preview-network.enabled", "false", "system:local-dev-release-gates");
    verify(flags, never())
        .update("worker.verify-before-complete.enabled", "true", "system:local-dev-release-gates");
    verify(flags, never())
        .update("preview.render-agent.enabled", "true", "system:local-dev-release-gates");
    verify(flags, never())
        .update("worker.build-tdd.enabled", "true", "system:local-dev-release-gates");
    verify(flags, never())
        .update("build.test-exec-gate.enabled", "true", "system:local-dev-release-gates");
  }

  @Test
  void componentIsRestrictedToLocalAndDevProfiles() {
    Profile profile =
        EvidenceSafeExecutionGateProfileInitializer.class.getAnnotation(Profile.class);
    assertThat(profile).isNotNull();
    assertThat(profile.value()).containsExactly("local", "dev");
  }

  @Test
  void doesNotWriteWhenEveryReleaseGateAlreadyMatches() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    List<FeatureFlag> current =
        java.util.stream.Stream.concat(
                EvidenceSafeExecutionGateProfileInitializer.REQUIRED_ENABLED_FLAGS.stream()
                    .map(key -> flag(key, "true")),
                EvidenceSafeExecutionGateProfileInitializer.REQUIRED_DISABLED_FLAGS.stream()
                    .map(key -> flag(key, "false")))
            .toList();
    when(flags.listAll()).thenReturn(current);

    new EvidenceSafeExecutionGateProfileInitializer(flags).run(null);

    verify(flags).listAll();
    verify(flags, never())
        .update(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void missingMigrationFlagsDoNotFailStartupOrCreateUnknownRows() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.listAll()).thenReturn(List.of());

    new EvidenceSafeExecutionGateProfileInitializer(flags).run(null);

    verify(flags).listAll();
    verify(flags, never())
        .update(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  private static FeatureFlag flag(String key, String value) {
    return FeatureFlag.builder().flagKey(key).flagValue(value).build();
  }
}
