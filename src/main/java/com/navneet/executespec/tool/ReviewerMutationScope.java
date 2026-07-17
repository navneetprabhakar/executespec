package com.navneet.executespec.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Thread-bound, fail-closed write authority for brownfield reviewer passes. */
public final class ReviewerMutationScope {

  private static final ThreadLocal<Policy> CURRENT = new ThreadLocal<>();
  private static final Set<String> BUILD_MANIFESTS =
      Set.of(
          "package.json",
          "pom.xml",
          "build.gradle",
          "build.gradle.kts",
          "settings.gradle",
          "settings.gradle.kts",
          "pyproject.toml",
          "requirements.txt",
          "cargo.toml",
          "go.mod",
          "dockerfile");

  private ReviewerMutationScope() {}

  public static <T> T withPolicy(Policy policy, Supplier<T> operation) {
    Policy previous = CURRENT.get();
    CURRENT.set(policy);
    try {
      return operation.get();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }

  public static String validateWriteFile(String path, boolean exists, boolean overwrite) {
    Policy policy = CURRENT.get();
    if (policy == null || !policy.brownfield()) return null;
    String normalized = normalize(path);
    String rootError = validateRoot(normalized, policy.allowedRepoRoots());
    if (rootError != null) return rootError;
    String name = Path.of(normalized).getFileName().toString().toLowerCase();
    if (BUILD_MANIFESTS.contains(name)
        && (!exists || overwrite)
        && !policy.allowBuildManifestCreation()) {
      return "reviewer cannot create or wholesale-replace build manifest: " + normalized;
    }
    if (!exists && !approvedNewPath(normalized, policy.approvedNewFiles())) {
      return "reviewer cannot create an unplanned file in a brownfield run: " + normalized;
    }
    return null;
  }

  public static String validateEditFile(String path) {
    Policy policy = CURRENT.get();
    if (policy == null || !policy.brownfield()) return null;
    return validateRoot(normalize(path), policy.allowedRepoRoots());
  }

  public static String validateShell() {
    Policy policy = CURRENT.get();
    return policy != null && policy.brownfield()
        ? "shell is disabled during brownfield review; use read/edit/buildProject tools"
        : null;
  }

  private static String validateRoot(String path, List<String> roots) {
    boolean inside =
        roots != null
            && roots.stream()
                .map(ReviewerMutationScope::normalize)
                .anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
    return inside ? null : "reviewer write is outside admitted repository roots: " + path;
  }

  private static boolean approvedNewPath(String path, List<String> approved) {
    if (approved == null || approved.isEmpty()) return false;
    return approved.stream()
        .map(ReviewerMutationScope::normalize)
        .anyMatch(
            candidate ->
                path.equals(candidate)
                    || path.startsWith(candidate + "/")
                    || path.endsWith("/" + candidate));
  }

  private static String normalize(String path) {
    if (path == null) return "";
    String normalized = path.replace('\\', '/');
    while (normalized.startsWith("./")) normalized = normalized.substring(2);
    return normalized;
  }

  public record Policy(
      boolean brownfield,
      List<String> allowedRepoRoots,
      List<String> approvedNewFiles,
      boolean allowBuildManifestCreation) {
    public Policy {
      allowedRepoRoots = allowedRepoRoots == null ? List.of() : List.copyOf(allowedRepoRoots);
      approvedNewFiles = approvedNewFiles == null ? List.of() : List.copyOf(approvedNewFiles);
    }
  }
}
