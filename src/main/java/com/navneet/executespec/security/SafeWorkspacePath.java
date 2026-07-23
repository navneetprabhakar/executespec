package com.navneet.executespec.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Shared path-containment boundary for every framework-owned filesystem access that touches a
 * customer-controlled tree (cloned repositories, run workspaces, generated test scaffolds).
 *
 * <p>The defect class this closes is uniform: a path derived from a repository is used with
 * ordinary {@code Files} operations, all of which follow symbolic links. A repository can therefore
 * ship {@code .agent-context} (or {@code README.md}, or any other framework-reserved name) as a
 * symlink to a host directory and make ExecuteSpec read, write or — worst case — delete files
 * outside the workspace.
 *
 * <p>The rules enforced here are the same ones {@code deployment/WorkspaceFileService} and {@code
 * tool/PathSafety} already apply to agent tool calls, extracted so framework code can reuse one
 * tested implementation instead of ten independent ad-hoc checks:
 *
 * <ol>
 *   <li>the requested path must be relative, non-empty and free of {@code ..} / empty / {@code .}
 *       segments;
 *   <li>lexical containment beneath the canonical root;
 *   <li>every <em>existing</em> component between the root and the target must not be a symbolic
 *       link, and its no-follow real path must still be beneath the canonical root. A not-yet
 *       existing leaf is allowed only under an already-validated existing parent.
 * </ol>
 *
 * <p>Failures throw {@link SecurityException}, never a silent fallback: a repository that ships a
 * reserved-name symlink loses the metadata write, it does not redirect it.
 */
public final class SafeWorkspacePath {

  private SafeWorkspacePath() {}

  /**
   * Resolve {@code relativePath} beneath {@code root} and prove containment.
   *
   * @return the resolved, contained path (which may not exist yet)
   * @throws SecurityException if the path escapes the root or crosses a symbolic link
   * @throws IOException if {@code root} cannot be canonicalized
   */
  public static Path resolveWithin(Path root, String relativePath) throws IOException {
    if (root == null) {
      throw new SecurityException("workspace root is null");
    }
    if (relativePath == null || relativePath.isBlank()) {
      throw new SecurityException("path is empty");
    }
    Path requested;
    try {
      requested = Path.of(relativePath);
    } catch (InvalidPathException e) {
      throw new SecurityException("path is not a valid filesystem path");
    }
    if (requested.isAbsolute()) {
      throw new SecurityException("absolute paths are not allowed");
    }
    for (Path segment : requested) {
      String name = segment.toString();
      if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
        throw new SecurityException("path contains a dot or empty segment");
      }
    }
    Path rootReal = root.toRealPath();
    Path resolved = rootReal.resolve(requested).normalize();
    requireNoLinkComponents(rootReal, resolved);
    return resolved;
  }

  /**
   * Prove that an already-constructed {@code candidate} (for example a walk result) is beneath
   * {@code root} without crossing a symbolic link.
   *
   * @throws SecurityException if the candidate escapes the root or crosses a symbolic link
   * @throws IOException if {@code root} cannot be canonicalized
   */
  public static void requireWithin(Path root, Path candidate) throws IOException {
    if (root == null || candidate == null) {
      throw new SecurityException("workspace root or candidate is null");
    }
    Path rootReal = root.toRealPath();
    Path resolved;
    if (candidate.isAbsolute()) {
      Path abs = candidate.normalize();
      if (abs.startsWith(rootReal)) {
        resolved = abs;
      } else {
        // The caller may have built the candidate from the non-canonical root (a very common case:
        // on macOS a temp/workspace root under /var resolves to /private/var). Re-express it
        // relative to the canonical root before judging containment, so an alias is not mistaken
        // for an escape — and a genuine escape still fails below.
        Path rawRoot = root.toAbsolutePath().normalize();
        if (!abs.startsWith(rawRoot)) {
          throw new SecurityException("path escapes the workspace root");
        }
        resolved = rootReal.resolve(rawRoot.relativize(abs)).normalize();
      }
    } else {
      resolved = rootReal.resolve(candidate).normalize();
    }
    requireNoLinkComponents(rootReal, resolved);
  }

  /**
   * Non-throwing form of {@link #requireWithin(Path, Path)} for stream filters. Any failure —
   * including an unreadable root — is treated as "not contained", so the caller skips the entry.
   */
  public static boolean isWithin(Path root, Path candidate) {
    try {
      requireWithin(root, candidate);
      return true;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /**
   * Validate a repository-relative POSIX path that will be written through an SCM API rather than
   * the local filesystem (so there is no root to canonicalize against). Rejects absolute paths,
   * backslashes, dot segments, empty segments, control characters and percent-encoded separators.
   *
   * @return the same path when it is safe
   * @throws SecurityException when it is not
   */
  public static String requireSafeRelativePosixPath(String path) {
    if (path == null || path.isBlank()) {
      throw new SecurityException("path is empty");
    }
    if (path.startsWith("/") || path.contains("\\")) {
      throw new SecurityException("path is absolute or contains a backslash separator");
    }
    String lower = path.toLowerCase();
    if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e")) {
      throw new SecurityException("path contains an encoded separator or dot segment");
    }
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        throw new SecurityException("path contains a control character");
      }
    }
    for (String segment : path.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw new SecurityException("path contains a dot or empty segment");
      }
    }
    return path;
  }

  private static void requireNoLinkComponents(Path rootReal, Path resolved) throws IOException {
    if (!resolved.startsWith(rootReal)) {
      throw new SecurityException("path escapes the workspace root");
    }
    Path current = rootReal;
    for (Path component : rootReal.relativize(resolved)) {
      current = current.resolve(component);
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        // Nothing exists from here down; the nearest existing parent was already proven contained.
        return;
      }
      if (Files.isSymbolicLink(current)) {
        throw new SecurityException("symbolic links are not allowed in workspace paths");
      }
      if (!current.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(rootReal)) {
        throw new SecurityException("path escapes the workspace root");
      }
    }
  }
}
