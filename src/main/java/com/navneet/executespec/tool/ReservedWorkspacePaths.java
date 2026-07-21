package com.navneet.executespec.tool;

import java.util.Set;

/**
 * Unconditional deny-list of control-plane paths that generic worker mutation tools may never
 * write, regardless of guardrail level (RR-1018).
 *
 * <p>Before this guard, {@code writeFile} / {@code editFile} checked only workspace containment
 * plus {@link FileSandboxPolicy}, which is a no-op at LOW — the production default. A worker (or a
 * prompt injection reaching one) could therefore write {@code .git/hooks/pre-commit} or {@code
 * .git/config}, and the next authenticated Git operation the server performs in that workspace
 * would execute it with the caller's PAT in scope. The same argument applies to {@code
 * .agent-context/}, which holds the run's own tool-output and read-state evidence: a worker that
 * can rewrite its evidence can rewrite what verification later reads.
 *
 * <p>Scope is deliberately narrow and unconditional. It is not a substitute for OS isolation, and
 * it governs only paths passed to the generic mutation tools — a shell command, a build script, or
 * a test that writes these files at runtime is NOT covered here (that needs RR-118 / RR-282
 * containment). Server-owned writers that legitimately maintain these directories do not route
 * through the worker tools and are unaffected.
 */
final class ReservedWorkspacePaths {

  private ReservedWorkspacePaths() {}

  /**
   * Directory names that may not appear as ANY segment of a mutation target. Nested occurrences
   * matter: {@code repos/api/.git/config} is a nested repository's control plane, not a source
   * file.
   */
  private static final Set<String> RESERVED_SEGMENTS =
      Set.of(".git", ".hg", ".svn", ".agent-context");

  /**
   * Returns a rejection reason when {@code relativePath} targets a reserved control-plane path, or
   * {@code null} when the path is an ordinary workspace file.
   *
   * @param relativePath the workspace-relative path the tool was asked to write
   */
  static String rejectionFor(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) return null;
    String normalized = relativePath.replace('\\', '/');
    for (String segment : normalized.split("/")) {
      String candidate = segment.trim();
      if (RESERVED_SEGMENTS.contains(candidate)) {
        return "'"
            + candidate
            + "' is a reserved control-plane directory and cannot be written by a worker tool"
            + " (attempted path: "
            + relativePath
            + ")";
      }
    }
    return null;
  }
}
