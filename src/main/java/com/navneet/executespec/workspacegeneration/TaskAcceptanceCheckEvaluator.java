package com.navneet.executespec.workspacegeneration;

import java.util.ArrayList;
import java.util.List;

/** Evaluates every frozen schema-v2 acceptance check; unknown checks fail as unavailable. */
final class TaskAcceptanceCheckEvaluator {

  private TaskAcceptanceCheckEvaluator() {}

  static Evaluation evaluate(
      TaskAcceptanceContract contract,
      List<String> changedFiles,
      boolean buildCommandAvailable,
      boolean testCommandAvailable,
      boolean requiredPathsPresent,
      boolean buildPassed,
      boolean testsExecuted,
      boolean testsPassed,
      boolean runtimeEndpointContractPassed,
      boolean bootstrapArtifactsMatch) {
    List<Result> results = new ArrayList<>();
    boolean mutationPresent = changedFiles != null && !changedFiles.isEmpty();
    boolean repositoryScopePassed = withinScopes(changedFiles, contract.allowedRepositoryScopes());
    for (TaskAcceptanceContract.AcceptanceCheck check :
        java.util.stream.Stream.concat(
                contract.prefixChecks().stream(), contract.finalChecks().stream())
            .toList()) {
      results.add(
          switch (check.kind()) {
            case "BUILD_COMMAND_AVAILABLE" ->
                result(check, buildCommandAvailable, "server-resolved build command");
            case "TEST_COMMAND_AVAILABLE" ->
                result(check, testCommandAvailable, "server-resolved test command");
            case "REPOSITORY_SCOPE" ->
                result(check, repositoryScopePassed, "changed files stay inside frozen scopes");
            case "REQUIRED_PATHS_PRESENT" ->
                result(check, requiredPathsPresent, "all frozen required paths exist");
            case "BOOTSTRAP_ARTIFACT_INTEGRITY" ->
                result(
                    check,
                    bootstrapArtifactsMatch,
                    "server-owned bootstrap artifacts retain their frozen bytes");
            case "MEANINGFUL_DIFF" ->
                result(check, mutationPresent, "candidate has a non-empty product diff");
            case "BUILD_PASSED" -> result(check, buildPassed, "exact frozen build passed");
            case "TESTS_PASSED" ->
                testResult(check, contract.testRequirement(), testsExecuted, testsPassed);
            case "RUNTIME_ENDPOINT_CONTRACT" ->
                result(
                    check,
                    runtimeEndpointContractPassed,
                    "every frozen HTTP method/path passed against the running application");
            default ->
                new Result(
                    check.kind(),
                    check.target(),
                    check.hidden(),
                    Status.UNAVAILABLE,
                    "unsupported frozen acceptance check");
          });
    }
    if (!contract.requiredTestSelectors().isEmpty()) {
      boolean selectorsFrozen =
          contract.testCommand() != null
              && contract.requiredTestSelectors().stream()
                  .allMatch(selector -> contract.testCommand().command().contains(selector));
      results.add(
          new Result(
              "REQUIRED_TEST_SELECTORS",
              "test-command",
              false,
              selectorsFrozen ? Status.PASSED : Status.UNAVAILABLE,
              selectorsFrozen
                  ? "all selectors are bound into the frozen command"
                  : "a required selector is not bound into the frozen command"));
    }
    Status overall =
        results.stream().anyMatch(result -> result.status() == Status.FAILED)
            ? Status.FAILED
            : results.stream().anyMatch(result -> result.status() == Status.UNAVAILABLE)
                ? Status.UNAVAILABLE
                : Status.PASSED;
    return new Evaluation(overall, List.copyOf(results));
  }

  private static Result testResult(
      TaskAcceptanceContract.AcceptanceCheck check,
      String requirement,
      boolean executed,
      boolean passed) {
    if (executed) return result(check, passed, "non-empty exact frozen test suite passed");
    if ("REQUIRED".equals(requirement)) {
      return new Result(
          check.kind(),
          check.target(),
          check.hidden(),
          Status.UNAVAILABLE,
          "required test suite did not produce deterministic evidence");
    }
    return new Result(
        check.kind(),
        check.target(),
        check.hidden(),
        Status.PASSED,
        "optional test not applicable");
  }

  private static Result result(
      TaskAcceptanceContract.AcceptanceCheck check, boolean passed, String detail) {
    return new Result(
        check.kind(),
        check.target(),
        check.hidden(),
        passed ? Status.PASSED : Status.FAILED,
        detail);
  }

  private static boolean withinScopes(List<String> changedFiles, List<String> scopes) {
    if (changedFiles == null || changedFiles.isEmpty()) return true;
    if (scopes == null || scopes.isEmpty()) return false;
    if (scopes.contains(".")) return true;
    return changedFiles.stream()
        .allMatch(
            file ->
                scopes.stream()
                    .anyMatch(scope -> file.equals(scope) || file.startsWith(scope + "/")));
  }

  enum Status {
    PASSED,
    FAILED,
    UNAVAILABLE
  }

  record Result(String kind, String target, boolean hidden, Status status, String detail) {}

  record Evaluation(Status status, List<Result> results) {}
}
