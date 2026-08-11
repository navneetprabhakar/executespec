package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TaskAcceptanceCheckEvaluatorTest {

  @Test
  void enforcesRepositoryScopeAndEveryNamedCheck() {
    TaskAcceptanceContract contract = contract(List.of("repos/api"), List.of(), List.of());

    var accepted =
        TaskAcceptanceCheckEvaluator.evaluate(
            contract,
            List.of("repos/api/src/App.java", "repos/api/src/AppTest.java"),
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true);
    var escaped =
        TaskAcceptanceCheckEvaluator.evaluate(
            contract,
            List.of("repos/api/src/App.java", "repos/web/src/App.ts"),
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true);

    assertThat(accepted.status()).isEqualTo(TaskAcceptanceCheckEvaluator.Status.PASSED);
    assertThat(accepted.results()).allMatch(result -> result.status().name().equals("PASSED"));
    assertThat(escaped.status()).isEqualTo(TaskAcceptanceCheckEvaluator.Status.FAILED);
    assertThat(escaped.results())
        .anySatisfy(
            result -> {
              assertThat(result.kind()).isEqualTo("REPOSITORY_SCOPE");
              assertThat(result.status()).isEqualTo(TaskAcceptanceCheckEvaluator.Status.FAILED);
            });
  }

  @Test
  void unsupportedOrUnboundFrozenCriteriaAreUnavailableNotIgnored() {
    TaskAcceptanceContract contract =
        contract(List.of("."), List.of("specific_case"), List.of("GET /health -> 200"));

    var result =
        TaskAcceptanceCheckEvaluator.evaluate(
            contract, List.of("src/app.py"), true, true, true, true, true, true, false, true);

    assertThat(result.status()).isEqualTo(TaskAcceptanceCheckEvaluator.Status.UNAVAILABLE);
    assertThat(result.results())
        .anySatisfy(
            check -> {
              assertThat(check.kind()).isEqualTo("REQUIRED_TEST_SELECTORS");
              assertThat(check.status()).isEqualTo(TaskAcceptanceCheckEvaluator.Status.UNAVAILABLE);
            });
  }

  @Test
  void unknownFrozenCheckCannotAccidentallyPass() {
    TaskAcceptanceContract base = contract(List.of("."), List.of(), List.of());
    TaskAcceptanceContract contract =
        new TaskAcceptanceContract(
            base.schemaVersion(),
            base.taskId(),
            base.taskIndex(),
            base.verificationPolicy(),
            base.buildRequirement(),
            base.testRequirement(),
            base.declaredTouchPaths(),
            base.runtimeSurface(),
            base.baselineKind(),
            base.buildCommand(),
            base.testCommand(),
            base.requiredPaths(),
            base.requiredTestSelectors(),
            base.endpointExpectations(),
            base.meaningfulDiffRequired(),
            base.allowedRepositoryScopes(),
            List.of(new TaskAcceptanceContract.AcceptanceCheck("FUTURE_CHECK", "x", true)),
            base.finalChecks(),
            base.digest());

    var result =
        TaskAcceptanceCheckEvaluator.evaluate(
            contract, List.of("src/app.py"), true, true, true, true, true, true, true, true);

    assertThat(result.status()).isEqualTo(TaskAcceptanceCheckEvaluator.Status.UNAVAILABLE);
  }

  private static TaskAcceptanceContract contract(
      List<String> scopes, List<String> selectors, List<String> endpoints) {
    return new TaskAcceptanceContract(
        2,
        "T1",
        0,
        "SERVER_RESOLVED_FAILURE_FIRST_V2",
        "REQUIRED_NON_NOOP",
        "REQUIRED",
        List.of("src/app.py"),
        "PYTHON_LIBRARY",
        "PYTHON",
        new TaskAcceptanceContract.ResolvedCommand(
            "python3 -m compileall -q .", 120, "inferred:python", "PYTHON"),
        new TaskAcceptanceContract.ResolvedCommand("python3 -m pytest -q", 600, "pytest", "PYTEST"),
        List.of("src/app.py"),
        selectors,
        endpoints,
        true,
        scopes,
        List.of(
            new TaskAcceptanceContract.AcceptanceCheck(
                "BUILD_COMMAND_AVAILABLE", "workspace", false),
            new TaskAcceptanceContract.AcceptanceCheck(
                "TEST_COMMAND_AVAILABLE", "workspace", false)),
        List.of(
            new TaskAcceptanceContract.AcceptanceCheck("REPOSITORY_SCOPE", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck(
                "REQUIRED_PATHS_PRESENT", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck("MEANINGFUL_DIFF", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck("BUILD_PASSED", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck("TESTS_PASSED", "candidate", false)),
        "a".repeat(64));
  }
}
