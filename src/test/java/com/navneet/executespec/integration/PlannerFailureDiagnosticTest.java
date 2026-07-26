package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.TaskExecutionBriefCompiler;
import com.navneet.executespec.agent.TaskExpanderAgent;
import com.navneet.executespec.security.ContextSanitizer;

import org.junit.jupiter.api.Test;

class PlannerFailureDiagnosticTest {

  private final ContextSanitizer sanitizer = new ContextSanitizer();

  @Test
  void exposesBoundedSanitizedDeterministicContractViolationsFromNestedCause() {
    String secret = "sk-proj-123456789012345678901234567890";
    Throwable failure =
        new IllegalStateException(
            "hierarchical plan coherence validation failed",
            new IllegalStateException(
                "assembled plan still violates executable contracts after one targeted repair: "
                    + "T8 misses GET /api/products; local=/Users/forge/private/plan.json; key="
                    + secret));

    String diagnostic = PlannerFailureDiagnostic.describe(failure, "planning failed", sanitizer);

    assertThat(diagnostic)
        .startsWith("Plan validation rejected:")
        .contains("T8 misses GET /api/products")
        .contains("[local path redacted]")
        .contains("[REDACTED]")
        .doesNotContain("/Users/forge")
        .doesNotContain(secret);
    assertThat(diagnostic.length()).isLessThanOrEqualTo(PlannerFailureDiagnostic.MAX_CHARS);
  }

  @Test
  void providerAndInfrastructureFailuresRemainGeneric() {
    Throwable providerFailure =
        new IllegalStateException("401 from provider: api_key=secret-value-that-must-not-surface");

    assertThat(
            PlannerFailureDiagnostic.describe(
                providerFailure, "planning could not produce a safe executable plan", sanitizer))
        .isEqualTo("planning could not produce a safe executable plan");
  }

  @Test
  void hierarchicalCoherenceWrapperDoesNotTurnProviderFailureIntoValidationEvidence() {
    Throwable failure =
        new IllegalStateException(
            "hierarchical plan coherence validation failed",
            new IllegalStateException("LLM call failed: provider returned 503"));

    String diagnostic =
        PlannerFailureDiagnostic.describe(
            failure, "planning could not produce a safe executable plan", sanitizer);

    assertThat(diagnostic).isEqualTo("planning could not produce a safe executable plan");
    assertThat(PlannerFailureDiagnostic.isDeterministicEvidence(diagnostic)).isFalse();
  }

  @Test
  void hierarchicalWrapperPreservesNestedDeterministicValidationEvidence() {
    Throwable failure =
        new IllegalStateException(
            "hierarchical plan coherence validation failed",
            new IllegalStateException(
                "assembled plan still violates executable contracts after one targeted repair: "
                    + "T9: GET /api/jobs has no backend producer"));

    String diagnostic = PlannerFailureDiagnostic.describe(failure, "planning failed", sanitizer);

    assertThat(diagnostic)
        .contains("T9: GET /api/jobs has no backend producer")
        .startsWith("Plan validation rejected: ");
    assertThat(PlannerFailureDiagnostic.isDeterministicEvidence(diagnostic)).isTrue();
  }

  @Test
  void preservesTypedFatalContractConflictThroughMediumCoherenceWrapper() {
    Throwable failure =
        new IllegalStateException(
            "MEDIUM plan coherence validation failed: fatal contract conflicts: required audit"
                + " route has no implementation owner");

    String diagnostic = PlannerFailureDiagnostic.describe(failure, "planning failed", sanitizer);

    assertThat(diagnostic)
        .startsWith("Plan validation rejected: ")
        .contains("required audit route has no implementation owner");
  }

  @Test
  void capsVeryLargeValidationEvidence() {
    Throwable failure =
        new IllegalStateException(
            "plan contract validation failed: " + "route mismatch; ".repeat(400));

    String diagnostic = PlannerFailureDiagnostic.describe(failure, "planning failed", sanitizer);

    assertThat(diagnostic.length()).isEqualTo(PlannerFailureDiagnostic.MAX_CHARS);
    assertThat(diagnostic).endsWith("… [truncated]");
  }

  @Test
  void exposesDirectFinalCompilationViolationsWithoutTreatingThemAsProviderFailures() {
    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                TaskExecutionBriefCompiler.compileExecutable(
                    Plan.builder().tasks(java.util.List.of()).build()));

    String diagnostic = PlannerFailureDiagnostic.describe(failure, "planning failed", sanitizer);

    assertThat(PlannerFailureDiagnostic.isExecutableContractRejection(failure)).isTrue();
    assertThat(diagnostic)
        .isEqualTo(
            "Plan validation rejected: final plan contract validation failed: planner returned an "
                + "empty executable plan");
  }

  @Test
  void arbitraryCompilerRuntimeExceptionNeverBecomesDeterministicEvidence() {
    Throwable internalFailure =
        new NullPointerException(
            "final plan contract validation failed: internal compiler invariant broke");

    String diagnostic =
        PlannerFailureDiagnostic.describe(internalFailure, "planning failed", sanitizer);

    assertThat(PlannerFailureDiagnostic.isExecutableContractRejection(internalFailure)).isFalse();
    assertThat(diagnostic).isEqualTo("planning failed");
    assertThat(PlannerFailureDiagnostic.isDeterministicEvidence(diagnostic)).isFalse();
  }

  @Test
  void exposesTypedTaskExpansionContractViolation() {
    Throwable failure =
        new IllegalStateException(
            "LARGE expansion phase failed",
            new TaskExpanderAgent.ExpansionContractException(
                "T9",
                PlannedTask.builder().id("T9").title("API client").build(),
                java.util.List.of(
                    "Task description makes the required Idempotency-Key header optional")));

    String diagnostic = PlannerFailureDiagnostic.describe(failure, "planning failed", sanitizer);

    assertThat(diagnostic)
        .startsWith("Plan validation rejected: task expansion contract validation failed:")
        .contains("T9")
        .contains("Idempotency-Key header optional");
  }

  @Test
  void redactsCanonicalMacOsPrivateTemporaryPaths() {
    Throwable failure =
        new IllegalStateException(
            "plan contract validation failed: first=/private/tmp/run/plan.json "
                + "second=/private/var/folders/05/cache/result.json");

    String diagnostic = PlannerFailureDiagnostic.describe(failure, "planning failed", sanitizer);

    assertThat(diagnostic)
        .contains("[local path redacted]")
        .doesNotContain("/private/tmp")
        .doesNotContain("/private/var/folders");
  }
}
