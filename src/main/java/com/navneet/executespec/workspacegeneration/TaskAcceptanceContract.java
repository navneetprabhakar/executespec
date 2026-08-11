package com.navneet.executespec.workspacegeneration;

import java.util.List;
import java.util.Map;

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
    AcceptanceProofStrength requiredProofStrength,
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
    Map<String, String> immutableBootstrapArtifactDigests,
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
    immutableBootstrapArtifactDigests =
        immutableBootstrapArtifactDigests == null
            ? Map.of()
            : Map.copyOf(immutableBootstrapArtifactDigests);
    requiredProofStrength =
        requiredProofStrength == null
            ? AcceptanceProofStrength.requiredFor(runtimeSurface, testRequirement)
            : requiredProofStrength;
  }

  /** Compatibility constructor for callers created before proof strength became explicit. */
  public TaskAcceptanceContract(
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
      Map<String, String> immutableBootstrapArtifactDigests,
      String digest) {
    this(
        schemaVersion,
        taskId,
        taskIndex,
        verificationPolicy,
        buildRequirement,
        testRequirement,
        declaredTouchPaths,
        runtimeSurface,
        AcceptanceProofStrength.requiredFor(runtimeSurface, testRequirement),
        baselineKind,
        buildCommand,
        testCommand,
        requiredPaths,
        requiredTestSelectors,
        endpointExpectations,
        meaningfulDiffRequired,
        allowedRepositoryScopes,
        prefixChecks,
        finalChecks,
        immutableBootstrapArtifactDigests,
        digest);
  }

  /** Compatibility constructor for schema-v2 fixtures created before bootstrap digest binding. */
  public TaskAcceptanceContract(
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
    this(
        schemaVersion,
        taskId,
        taskIndex,
        verificationPolicy,
        buildRequirement,
        testRequirement,
        declaredTouchPaths,
        runtimeSurface,
        AcceptanceProofStrength.requiredFor(runtimeSurface, testRequirement),
        baselineKind,
        buildCommand,
        testCommand,
        requiredPaths,
        requiredTestSelectors,
        endpointExpectations,
        meaningfulDiffRequired,
        allowedRepositoryScopes,
        prefixChecks,
        finalChecks,
        Map.of(),
        digest);
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
        AcceptanceProofStrength.requiredFor("UNSPECIFIED", testRequirement),
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
        Map.of(),
        digest);
  }

  public record ResolvedCommand(
      String command, int timeoutSeconds, String source, String framework) {}

  public record AcceptanceCheck(String kind, String target, boolean hidden) {}
}
