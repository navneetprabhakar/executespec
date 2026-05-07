package com.navneet.executespec.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

/**
 * Regression guard for the audit Sprint 4 BYOK fix.
 *
 * <p>Background: before the V2 BYOK rewiring (audit-output/BYOK_DEEPDIVE.md), every paying
 * TEAM/ENTERPRISE customer's run silently dispatched against the platform's {@code
 * spring.ai.anthropic.api-key} singleton instead of their own configured Anthropic key. The fix
 * lives entirely in {@link com.navneet.executespec.agent.LlmRouter} — it builds per-key {@link
 * org.springframework.ai.anthropic.AnthropicChatModel} instances from customer keys via a Caffeine
 * cache, leaving the platform singleton bean (built in {@link
 * com.navneet.executespec.config.AgentModelConfig}) for the no-BYOK fallback.
 *
 * <p>If a future agent grabs {@code AnthropicChatModel} directly via {@code @Autowired} or {@code
 * applicationContext.getBean(AnthropicChatModel.class)} it will silently use the platform key again
 * — re-introducing the bug. This test fails the build when an unapproved class references the type
 * so the regression is caught at CI time.
 *
 * <p>To add a new approved consumer: justify why the new caller should either (a) use a per-key
 * model from {@code LlmRouter}'s cache or (b) is genuinely platform-key-only (e.g. an admin
 * endpoint that doesn't route customer prompts), then add the simple class name to {@link
 * #APPROVED_CONSUMERS}.
 */
class AnthropicModelAccessTest {

  /**
   * Production classes allowed to reference {@code AnthropicChatModel} directly. Each entry is a
   * simple class name. Justifications:
   *
   * <ul>
   *   <li>{@code AgentModelConfig} — defines the platform singleton bean (the @Bean factory
   *       method).
   *   <li>{@code LlmRouter} — owns dispatch + the per-key cache. This is THE place that turns a
   *       customer BYOK key into a model on the V2 path; every other consumer should call this
   *       instead of grabbing the singleton.
   *   <li>{@code FallbackLlmRouter} — V1 router. Already implements its own per-key Anthropic model
   *       construction via {@code buildAnthropicModel} (BYOK-aware by design). Kept until V1
   *       callers (chat copilot, project-doc generators, accuracy assessment) migrate to V2.
   *   <li>{@code FeatureAssessmentService} — pre-V2 assessment path. <b>Likely BYOK gap</b> — uses
   *       singleton, doesn't thread customer keys. Tracked for follow-up; not blocking the audit
   *       fix.
   *   <li>{@code RunCodeMapService} — pre-V2 codemap generator. <b>Likely BYOK gap</b> — same shape
   *       as FeatureAssessmentService. Tracked for follow-up.
   *   <li>{@code NativeToolsTestController} — test/smoke endpoint. Uses the platform singleton
   *       intentionally — it tests the bean, not customer dispatch.
   * </ul>
   */
  private static final Set<String> APPROVED_CONSUMERS =
      new TreeSet<>(
          Set.of(
              "AgentModelConfig",
              "FeatureAssessmentService", // BYOK follow-up: thread customer keys
              "FallbackLlmRouter", // V1, already BYOK-aware via buildAnthropicModel
              "LlmRouter", // V2 router — main BYOK entry point
              "NativeToolsTestController", // test-only smoke
              "RunCodeMapService" // BYOK follow-up: thread customer keys
              ));

  @Test
  void anthropicChatModelOnlyUsedByApprovedClasses() {
    JavaClasses prodClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.navneet.executespec");

    Set<String> actualConsumers =
        prodClasses.stream()
            .filter(
                c ->
                    c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(
                            dep ->
                                dep.getTargetClass()
                                    .getFullName()
                                    .equals("org.springframework.ai.anthropic.AnthropicChatModel")))
            .map(JavaClass::getSimpleName)
            // Skip generated `$` inner / synthetic classes (lambdas, builders).
            .filter(name -> !name.contains("$"))
            .collect(Collectors.toCollection(TreeSet::new));

    assertEquals(
        APPROVED_CONSUMERS,
        actualConsumers,
        "AnthropicChatModel access drift detected. Either: \n"
            + "  (a) you intended to reference the platform singleton — add the class to"
            + " APPROVED_CONSUMERS with a justification, OR\n"
            + "  (b) you should be calling LlmRouter.call() so per-customer BYOK keys land. \n"
            + "See audit-output/BYOK_DEEPDIVE.md for context.\n"
            + "Expected: "
            + APPROVED_CONSUMERS
            + "\n"
            + "Actual:   "
            + actualConsumers);
  }
}
