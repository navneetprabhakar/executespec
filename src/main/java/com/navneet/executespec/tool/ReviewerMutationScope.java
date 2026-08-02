package com.navneet.executespec.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Thread-bound, fail-closed write authority for reviewer passes.
 *
 * <p>Two distinct concerns live here:
 *
 * <ul>
 *   <li><b>Brownfield containment</b> — a brownfield pass may only write inside admitted repository
 *       roots, may not fabricate build manifests, and may not create unplanned files or run a
 *       shell. Greenfield passes are unrestricted on THIS axis.
 *   <li><b>Revocation (RR-272 / RR-1687)</b> — a timed-out/abandoned reviewer pass keeps running
 *       until {@link com.navneet.executespec.engine.WorkerAgent} next checks its interrupt flag at
 *       an iteration boundary, and its thread-local policy is still bound because the pass's {@code
 *       finally} has not run. The reviewer control thread cannot reach into another thread's {@link
 *       ThreadLocal}, so it holds a {@link Lease} — a shared revocation flag bound alongside the
 *       policy — and flips it when it abandons the pass. Every write/mutation/command check
 *       consults the lease FIRST, before the brownfield gate, so revocation refuses a post-abandon
 *       {@code writeFile}/{@code editFile}/delete/move/shell on BOTH the brownfield and greenfield
 *       paths — not merely declines to vouch for it.
 * </ul>
 */
public final class ReviewerMutationScope {

  private static final ThreadLocal<Bound> CURRENT = new ThreadLocal<>();
  private static final ThreadLocal<AtomicBoolean> SINGLE_BUILD_PROJECT = new ThreadLocal<>();
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

  /**
   * A revocable authority handle for one reviewer pass. The pass binds it (indirectly, via {@link
   * #withPolicy(Policy, Lease, Supplier)}); the reviewer control thread that supervises the pass
   * retains a reference and calls {@link #revoke()} the moment it abandons the pass after a
   * timeout. Idempotent and thread-safe: once revoked, every mutation check on the bound thread
   * fails closed forever, so an in-flight or later tool call from a runaway worker thread is
   * refused rather than merely un-vouched-for.
   */
  public static final class Lease {
    private final AtomicBoolean revoked = new AtomicBoolean(false);

    public void revoke() {
      revoked.set(true);
    }

    public boolean isRevoked() {
      return revoked.get();
    }
  }

  /** The policy plus its revocation lease, bound together on the pass thread. */
  private record Bound(Policy policy, Lease lease) {}

  public static <T> T withPolicy(Policy policy, Supplier<T> operation) {
    return withPolicy(policy, new Lease(), operation);
  }

  /**
   * Bind {@code policy} together with a caller-supplied {@code lease} so the supervising thread can
   * revoke write authority for this pass even while the pass thread is still running. A {@code
   * null} lease is treated as a fresh (never-externally-revoked) lease.
   */
  public static <T> T withPolicy(Policy policy, Lease lease, Supplier<T> operation) {
    Bound previous = CURRENT.get();
    CURRENT.set(new Bound(policy, lease == null ? new Lease() : lease));
    try {
      return operation.get();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }

  /**
   * Bounds a read-only attestation to one resolver-selected build. A reviewer cannot turn {@code
   * buildProject} into a general test shell by retrying it with different arguments.
   */
  public static <T> T withSingleBuildProject(Supplier<T> operation) {
    AtomicBoolean previous = SINGLE_BUILD_PROJECT.get();
    SINGLE_BUILD_PROJECT.set(new AtomicBoolean(false));
    try {
      return operation.get();
    } finally {
      if (previous == null) SINGLE_BUILD_PROJECT.remove();
      else SINGLE_BUILD_PROJECT.set(previous);
    }
  }

  public static String validateBuildProject() {
    AtomicBoolean consumed = SINGLE_BUILD_PROJECT.get();
    if (consumed == null || consumed.compareAndSet(false, true)) return null;
    return "buildProject already executed for this read-only reviewer pass; use the prior result"
        + " and call completeTask instead of retrying with another command";
  }

  /**
   * Error returned when the bound pass's authority has been revoked. Checked before the
   * brownfield/greenfield gate so revocation refuses writes on both paths.
   */
  private static String revokedError(Bound bound) {
    return bound.lease().isRevoked()
        ? "reviewer write authority revoked: this review pass was abandoned after a timeout and"
            + " can no longer mutate the workspace"
        : null;
  }

  public static String validateWriteFile(String path, boolean exists, boolean overwrite) {
    Bound bound = CURRENT.get();
    if (bound == null) return null;
    String revoked = revokedError(bound);
    if (revoked != null) return revoked;
    Policy policy = bound.policy();
    if (!policy.brownfield()) return null;
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
    Bound bound = CURRENT.get();
    if (bound == null) return null;
    String revoked = revokedError(bound);
    if (revoked != null) return revoked;
    Policy policy = bound.policy();
    if (!policy.brownfield()) return null;
    return validateRoot(normalize(path), policy.allowedRepoRoots());
  }

  /**
   * Deletion is a destructive mutation and is confined to the same admitted repository roots as
   * writes (RR-1542 — {@code delete} / {@code deletePath} previously bypassed this scope entirely).
   */
  public static String validateDelete(String path) {
    Bound bound = CURRENT.get();
    if (bound == null) return null;
    String revoked = revokedError(bound);
    if (revoked != null) return revoked;
    Policy policy = bound.policy();
    if (!policy.brownfield()) return null;
    return validateRoot(normalize(path), policy.allowedRepoRoots());
  }

  /**
   * A move is a delete of {@code src} plus a create of {@code dst}; BOTH endpoints must sit inside
   * an admitted repository root (RR-1542 — {@code move} / {@code rename} previously bypassed this
   * scope entirely).
   */
  public static String validateMove(String srcPath, String dstPath) {
    Bound bound = CURRENT.get();
    if (bound == null) return null;
    String revoked = revokedError(bound);
    if (revoked != null) return revoked;
    Policy policy = bound.policy();
    if (!policy.brownfield()) return null;
    String srcError = validateRoot(normalize(srcPath), policy.allowedRepoRoots());
    if (srcError != null) return srcError;
    return validateRoot(normalize(dstPath), policy.allowedRepoRoots());
  }

  public static String validateShell() {
    Bound bound = CURRENT.get();
    if (bound == null) return null;
    String revoked = revokedError(bound);
    if (revoked != null) return revoked;
    return bound.policy().brownfield()
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
