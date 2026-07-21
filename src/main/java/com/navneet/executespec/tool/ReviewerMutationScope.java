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

  /**
   * Deletion is a destructive mutation and is confined to the same admitted repository roots as
   * writes (RR-1542 — {@code delete} / {@code deletePath} previously bypassed this scope entirely).
   */
  public static String validateDelete(String path) {
    Policy policy = CURRENT.get();
    if (policy == null || !policy.brownfield()) return null;
    return validateRoot(normalize(path), policy.allowedRepoRoots());
  }

  /**
   * A move is a delete of {@code src} plus a create of {@code dst}; BOTH endpoints must sit inside
   * an admitted repository root (RR-1542 — {@code move} / {@code rename} previously bypassed this
   * scope entirely).
   */
  public static String validateMove(String srcPath, String dstPath) {
    Policy policy = CURRENT.get();
    if (policy == null || !policy.brownfield()) return null;
    String srcError = validateRoot(normalize(srcPath), policy.allowedRepoRoots());
    if (srcError != null) return srcError;
    return validateRoot(normalize(dstPath), policy.allowedRepoRoots());
  }

  public static String validateShell() {
    Policy policy = CURRENT.get();
    return policy != null && policy.brownfield()
        ? "shell is disabled during brownfield review; use read/edit/buildProject tools"
        : null;
  }

  /**
   * Background jobs run an arbitrary sandbox-permitted command, so they are shell authority under
   * another name and are refused whenever {@link #validateShell()} refuses (RR-1542).
   */
  public static String validateBackgroundJob() {
    return validateShell();
  }

  private static String validateRoot(String path, List<String> roots) {
    if (path.isEmpty()) {
      return "reviewer write is outside admitted repository roots: (empty path)";
    }
    boolean inside =
        roots != null
            && roots.stream()
                .map(ReviewerMutationScope::normalize)
                .filter(root -> !root.isEmpty() && !ESCAPED.equals(root))
                .anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
    return inside ? null : "reviewer write is outside admitted repository roots: " + path;
  }

  private static boolean approvedNewPath(String path, List<String> approved) {
    if (approved == null || approved.isEmpty()) return false;
    return approved.stream()
        .map(ReviewerMutationScope::normalize)
        .filter(candidate -> !candidate.isEmpty())
        // Full normalized-relative-path match only. The pre-RR-1543 `endsWith("/" + candidate)`
        // clause let ONE approved basename authorize that filename anywhere in the tree.
        .anyMatch(candidate -> path.equals(candidate) || path.startsWith(candidate + "/"));
  }

  /**
   * Canonicalizes a workspace-relative path for prefix comparison: separator-normalized, dot
   * segments resolved, trailing slashes dropped. Any path that still escapes upward after
   * normalization (leading {@code ..}) or is absolute is rejected by returning the sentinel {@code
   * ".."}, which can never be inside an admitted root.
   *
   * <p>RR-1543: the previous implementation only swapped {@code \} for {@code /} and stripped
   * leading {@code ./}, so {@code repos/allowed/../other/x.java} passed the {@code
   * startsWith("repos/allowed/")} check while {@link PathSafety#safeResolve} normalized it and
   * wrote into the sibling repository. Containment must be evaluated on the same canonical form the
   * filesystem will use.
   */
  private static String normalize(String path) {
    if (path == null || path.isBlank()) return "";
    String slashed = path.replace('\\', '/').trim();
    if (slashed.startsWith("/")) return ESCAPED;
    java.util.Deque<String> stack = new java.util.ArrayDeque<>();
    for (String segment : slashed.split("/")) {
      if (segment.isEmpty() || ".".equals(segment)) continue;
      if ("..".equals(segment)) {
        if (stack.isEmpty()) return ESCAPED;
        stack.removeLast();
        continue;
      }
      stack.addLast(segment);
    }
    return String.join("/", stack);
  }

  /** Sentinel for a path that escapes the workspace-relative space; matches no admitted root. */
  private static final String ESCAPED = "..";

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
