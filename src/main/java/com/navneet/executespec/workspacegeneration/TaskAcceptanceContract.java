package com.navneet.executespec.workspacegeneration;

import java.util.List;

/** Frozen server-owned policy describing what one planner task must prove before acceptance. */
public record TaskAcceptanceContract(
    int schemaVersion,
    String taskId,
    int taskIndex,
    String verificationPolicy,
    String buildRequirement,
    String testRequirement,
    List<String> declaredTouchPaths,
    String runtimeSurface,
    String baselineKind,
    ResolvedCommand buildCommand,
    ResolvedCommand testCommand,
    List<String> requiredPaths,
    List<String> requiredTestSelectors,
    List<String> endpointExpectations,
    boolean meaningfulDiffRequired,
    List<String> allowedRepositoryScopes,
    List<AcceptanceCheck> prefixChecks,
    List<AcceptanceCheck> finalChecks,
    String digest) {

  public TaskAcceptanceContract {
    declaredTouchPaths = declaredTouchPaths == null ? List.of() : List.copyOf(declaredTouchPaths);
    requiredPaths = requiredPaths == null ? List.of() : List.copyOf(requiredPaths);
    requiredTestSelectors =
        requiredTestSelectors == null ? List.of() : List.copyOf(requiredTestSelectors);
    endpointExpectations =
        endpointExpectations == null ? List.of() : List.copyOf(endpointExpectations);
    allowedRepositoryScopes =
        allowedRepositoryScopes == null ? List.of() : List.copyOf(allowedRepositoryScopes);
    prefixChecks = prefixChecks == null ? List.of() : List.copyOf(prefixChecks);
    finalChecks = finalChecks == null ? List.of() : List.copyOf(finalChecks);
  }

  /** Compatibility constructor for schema-v1 persisted/test fixtures. */
  public TaskAcceptanceContract(
      int schemaVersion,
      String taskId,
      int taskIndex,
      String verificationPolicy,
      String buildRequirement,
      String testRequirement,
      List<String> declaredTouchPaths,
      String digest) {
    this(
        schemaVersion,
        taskId,
        taskIndex,
        verificationPolicy,
        buildRequirement,
        testRequirement,
        declaredTouchPaths,
        "UNSPECIFIED",
        "LEGACY",
        null,
        null,
        declaredTouchPaths,
        List.of(),
        List.of(),
        true,
        List.of("."),
        List.of(),
        List.of(),
        digest);
  }

  public record ResolvedCommand(
      String command, int timeoutSeconds, String source, String framework) {}

  public record AcceptanceCheck(String kind, String target, boolean hidden) {}
}
