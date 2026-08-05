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
 * spring.ai.anthropic.api-key} singleton instead of their own configured Anthropic key. The fix is
 * enforced by {@link com.navneet.executespec.agent.LlmRouter} and {@link
 * com.navneet.executespec.agent.AnthropicAttemptModelFactory}. The router resolves the admitted key
 * and deadline; the factory builds a fresh single-Spring-attempt {@link
 * org.springframework.ai.anthropic.AnthropicChatModel}. {@link
 * com.navneet.executespec.config.AgentModelConfig} retains only the compatibility bean.
 *
 * <p>If a future agent grabs {@code AnthropicChatModel} directly via {@code @Autowired} or {@code
 * applicationContext.getBean(AnthropicChatModel.class)} it will silently use the platform key again
 * — re-introducing the bug. This test fails the build when an unapproved class references the type
 * so the regression is caught at CI time.
 *
 * <p>To add a new approved consumer: justify why the new caller should either (a) use a per-key
 * model from the admitted-attempt factory or (b) is genuinely platform-key-only (e.g. an admin
 * endpoint that doesn't route customer prompts), then add the simple class name to {@link
 * #APPROVED_CONSUMERS}.
 */
class AnthropicModelAccessTest {

  /**
   * Production classes allowed to reference {@code AnthropicChatModel} directly. Each entry is a
   * simple class name. Justifications:
   *
   * <ul>
   *   <li>{@code AgentModelConfig} — defines the compatibility @Bean through the bounded factory.
   *   <li>{@code AnthropicAttemptModelFactory} — builds the fresh deadline-bounded,
   *       single-Spring-attempt model for each admitted physical request.
   *   <li>{@code LlmRouter} — owns dispatch and passes the admitted key/deadline to that factory;
   *       every other consumer should call the router.
   * </ul>
   */
  private static final Set<String> APPROVED_CONSUMERS =
      new TreeSet<>(
          Set.of(
              "AgentModelConfig",
              "AnthropicAttemptModelFactory",
              "LlmRouter" // V2 router — main BYOK entry point
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
