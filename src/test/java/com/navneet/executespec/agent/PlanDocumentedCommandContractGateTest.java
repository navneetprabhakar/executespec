package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanDocumentedCommandContractGateTest {

  @Test
  void rejectsMalformedPytestTargetAndConcreteUnownedFileFromDocumentedCommands() {
    PlannedTask acceptance =
        task(
            "T1",
            "Implement black-box acceptance tests",
            "Create the independently runnable acceptance suite.",
            List.of("acceptance/test_checkout.py"));
    PlannedTask docs =
        task(
            "T2",
            "Finalize README commands",
            "Document `python -m pytest tests/-v` and `cd backend && python -m pytest"
                + " tests/test_acceptance.py -v`.",
            List.of("README.md"));
    List<String> violations = new ArrayList<>();

    PlanDocumentedCommandContractGate.addViolations(plan(acceptance, docs), violations);

    assertThat(violations)
        .contains(
            "T2: documented pytest target token tests/-v is malformed; keep pytest options"
                + " separate from the test path",
            "T2: documented pytest target backend/tests/test_acceptance.py is not owned by any"
                + " plan task");
    assertThat(PlanContractQualityGate.violations(plan(acceptance, docs))).containsAll(violations);
  }

  @Test
  void acceptsOwnedFilesAndDirectoriesResolvedFromDocumentedWorkingDirectory() {
    PlannedTask tests =
        task(
            "T1",
            "Implement tests",
            "Create maintained backend tests.",
            List.of("backend/tests/test_api.py", "frontend/e2e/inventory.spec.ts"));
    PlannedTask docs =
        task(
            "T2",
            "Document verification",
            "Run `cd backend && python3.12 -m pytest tests/test_api.py -v`, `cd backend && pytest"
                + " tests`, and `npx playwright test frontend/e2e/inventory.spec.ts`.",
            List.of("README.md"));
    List<String> violations = new ArrayList<>();

    PlanDocumentedCommandContractGate.addViolations(plan(tests, docs), violations);

    assertThat(violations).isEmpty();
  }

  @Test
  void ignoresRuntimeGeneratedTargetsAndCommandsOutsideDocumentationTasks() {
    PlannedTask worker =
        task(
            "T1",
            "Run generated verification",
            "Run python -m pytest $TEST_ROOT and python -m pytest"
                + " /tmp/generated/tests/test_api.py.",
            List.of("scripts/verify.py"));
    PlannedTask docs =
        task(
            "T2",
            "Write README",
            "Document `python -m pytest ${TEST_ROOT}/test_api.py` for an operator-supplied runtime"
                + " path.",
            List.of("README.md"));
    List<String> violations = new ArrayList<>();

    PlanDocumentedCommandContractGate.addViolations(plan(worker, docs), violations);

    assertThat(violations).isEmpty();
  }

  @Test
  void ignoresNegatedRunnerMentionInJavaDocumentationTask() {
    PlannedTask javaTests =
        task(
            "T1",
            "Implement Java tests",
            "Add the Maven-owned verification suite.",
            List.of("src/test/java/com/acme/CheckoutTest.java"));
    PlannedTask docs =
        task(
            "T2",
            "Document Java verification",
            "This is a Java service: do not use pytest, Maven wrappers from unrelated projects, or"
                + " Gradle. Document the repository's supported Java command.",
            List.of("README.md"));
    List<String> violations = new ArrayList<>();

    PlanDocumentedCommandContractGate.addViolations(plan(javaTests, docs), violations);

    assertThat(violations).isEmpty();
  }

  @Test
  void ignoresInjectedGenericTestFrameworkListForReadmeOnlyTask() {
    PlannedTask docs =
        task(
            "T1",
            "Update README documentation",
            "Add a concise verification note to README.md.\n\n"
                + "Also include unit tests for any new public function, endpoint, or module this"
                + " task adds. If a test framework already exists in the workspace (Jest/Vitest,"
                + " pytest, Go *_test.go, JUnit), extend it; otherwise scaffold a minimal test file"
                + " with at least one happy-path test + one error case.",
            List.of("README.md"));
    List<String> violations = new ArrayList<>();

    PlanDocumentedCommandContractGate.addViolations(plan(docs), violations);

    assertThat(violations).isEmpty();
    assertThat(PlanContractQualityGate.violations(plan(docs))).isEmpty();
  }

  @Test
  void imperativePytestCommandWithCommaStillValidatesItsTarget() {
    PlannedTask docs =
        task(
            "T1",
            "Update README documentation",
            "Run pytest, tests/nonexistent/test_api.py to verify the documented setup.",
            List.of("README.md"));
    List<String> violations = new ArrayList<>();

    PlanDocumentedCommandContractGate.addViolations(plan(docs), violations);

    assertThat(violations)
        .anyMatch(
            violation ->
                violation.contains("documented pytest target tests/nonexistent/test_api.py")
                    && violation.contains("is not owned by any plan task"));
  }

  @Test
  void parenthesizedImperativePytestCommandStillValidatesItsTarget() {
    PlannedTask docs =
        task(
            "T1",
            "Update README documentation",
            "Run (pytest, tests/nonexistent/test_api.py) to verify the documented setup.",
            List.of("README.md"));
    List<String> violations = new ArrayList<>();

    PlanDocumentedCommandContractGate.addViolations(plan(docs), violations);

    assertThat(violations)
        .anyMatch(
            violation ->
                violation.contains("documented pytest target tests/nonexistent/test_api.py")
                    && violation.contains("is not owned by any plan task"));
  }

  @Test
  void rejectsOriginsVariableThatDisagreesWithExplicitRuntimeProducer() {
    PlannedTask runtime =
        task(
            "T1",
            "Configure CORS runtime",
            "Read ALLOWED_ORIGINS from the environment and parse it into the CORS allow-list.",
            List.of("backend/app/config.py"));
    PlannedTask docs =
        task(
            "T2",
            "Finalize setup documentation",
            "README must document the CORS_ORIGINS environment variable and safe defaults.",
            List.of("README.md"));
    List<String> violations = new ArrayList<>();

    PlanDocumentedCommandContractGate.addViolations(plan(runtime, docs), violations);

    assertThat(violations)
        .contains(
            "T2: documentation names CORS_ORIGINS but runtime/config task T1 establishes"
                + " ALLOWED_ORIGINS");
  }

  @Test
  void acceptsMatchingOriginsVariableAndDoesNotGuessWhenRuntimeIsAmbiguous() {
    PlannedTask runtime =
        task(
            "T1",
            "Configure runtime",
            "Read ALLOWED_ORIGINS from the environment. A legacy adapter separately reads"
                + " PARTNER_ORIGINS from its environment.",
            List.of("src/config.ts"));
    PlannedTask docs =
        task(
            "T2",
            "Document configuration",
            "Document ALLOWED_ORIGINS for the primary server.",
            List.of("docs/setup.md"));
    List<String> violations = new ArrayList<>();

    PlanDocumentedCommandContractGate.addViolations(plan(runtime, docs), violations);

    assertThat(violations).isEmpty();
  }

  @Test
  void ignoresExplicitlyNegatedLegacyOriginsVariableInDocumentation() {
    PlannedTask runtime =
        task(
            "T1",
            "Configure runtime",
            "Read ALLOWED_ORIGINS from the environment.",
            List.of("src/config.ts"));
    PlannedTask docs =
        task(
            "T2",
            "Document configuration",
            "Do not document CORS_ORIGINS; document ALLOWED_ORIGINS instead.",
            List.of("README.md"));
    List<String> violations = new ArrayList<>();

    PlanDocumentedCommandContractGate.addViolations(plan(runtime, docs), violations);

    assertThat(violations).isEmpty();
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder().tasks(List.of(tasks)).requirements(List.of()).build();
  }

  private static PlannedTask task(
      String id, String title, String description, List<String> touchesFiles) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .complexity(PlannedTask.Complexity.LOW)
        .touchesFiles(touchesFiles)
        .dependsOn(List.of())
        .hardConstraints(List.of())
        .meetsRequirements(List.of())
        .build();
  }
}
