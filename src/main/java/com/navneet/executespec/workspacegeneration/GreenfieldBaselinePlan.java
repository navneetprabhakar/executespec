package com.navneet.executespec.workspacegeneration;

import java.util.List;
import java.util.Map;

/** Side-effect-free plan for the minimum deterministic BUILD baseline. */
public record GreenfieldBaselinePlan(
    Kind kind,
    String runtimeSurface,
    boolean greenfield,
    boolean supported,
    Map<String, String> filesToCreate,
    List<String> requiredTaskPaths,
    List<String> initialFailureEvidence,
    List<RepositoryTarget> repositoryTargets) {

  public GreenfieldBaselinePlan {
    filesToCreate = filesToCreate == null ? Map.of() : Map.copyOf(filesToCreate);
    requiredTaskPaths = requiredTaskPaths == null ? List.of() : List.copyOf(requiredTaskPaths);
    initialFailureEvidence =
        initialFailureEvidence == null ? List.of() : List.copyOf(initialFailureEvidence);
    repositoryTargets = repositoryTargets == null ? List.of() : List.copyOf(repositoryTargets);
  }

  public GreenfieldBaselinePlan(
      Kind kind,
      String runtimeSurface,
      boolean greenfield,
      boolean supported,
      Map<String, String> filesToCreate,
      List<String> requiredTaskPaths,
      List<String> initialFailureEvidence) {
    this(
        kind,
        runtimeSurface,
        greenfield,
        supported,
        filesToCreate,
        requiredTaskPaths,
        initialFailureEvidence,
        List.of());
  }

  public record RepositoryTarget(
      String relativeRoot, Kind kind, String runtimeSurface, List<String> requiredPaths) {
    public RepositoryTarget {
      requiredPaths = requiredPaths == null ? List.of() : List.copyOf(requiredPaths);
    }
  }

  public enum Kind {
    EXISTING,
    PYTHON,
    NODE_TYPESCRIPT,
    JAVA_MAVEN,
    FRONTEND_REACT,
    MULTI_REPOSITORY,
    UNSUPPORTED
  }
}
