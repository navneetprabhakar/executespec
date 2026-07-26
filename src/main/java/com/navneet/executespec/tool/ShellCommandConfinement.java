package com.navneet.executespec.tool;

import java.util.List;
import java.util.Set;

/**
 * Executable-origin and absolute-path confinement rules for {@link ShellSandboxPolicy}.
 *
 * <p>Extracted from {@code ShellSandboxPolicy} so that class stays under the 600-line god-class
 * ceiling enforced by {@code GodClassLineCeilingTest}. These are a security boundary, not a
 * convenience list.
 *
 * <p><b>What this closes.</b>
 *
 * <ul>
 *   <li>RR-833 — the policy used to strip every directory prefix from the first token and authorize
 *       the resulting basename, so a worker-written {@code ./git} or {@code /tmp/npm} was validated
 *       as the allowlisted canonical tool while {@code /bin/sh} executed the worker's own file.
 *       {@link #resolveCommandName} now refuses path-qualified commands unless they are a real
 *       system-executable directory or one of the two governed workspace build wrappers.
 *   <li>RR-118 — the policy never read {@code workspacePath}, so {@code cat /proc/self/environ} and
 *       {@code find / -maxdepth 2} passed. {@link #rejectUnconfinedAbsolutePath} confines unquoted
 *       absolute (and {@code ~/}) tokens to the run workspace plus a small set of scratch, device
 *       and toolchain roots.
 * </ul>
 *
 * <p><b>What this does NOT close.</b> This is textual command inspection, not filesystem isolation.
 * A quoted path, a shell variable, an interpreter ({@code python foo.py}), or any program that
 * computes its own path at runtime defeats it entirely. Real confinement requires executing workers
 * in an OS/container sandbox with workspace-only mounts (RR-118 / RR-119 / RR-282).
 */
final class ShellCommandConfinement {

  private ShellCommandConfinement() {}

  /**
   * Directories a path-qualified command may live in. These are OS-managed executable directories;
   * a worker cannot write to them from inside its workspace, so {@code /usr/bin/mvn} still means
   * the real Maven.
   */
  private static final Set<String> TRUSTED_EXECUTABLE_DIRS =
      Set.of(
          "/bin",
          "/usr/bin",
          "/usr/local/bin",
          "/sbin",
          "/usr/sbin",
          "/opt/homebrew/bin",
          "/opt/homebrew/sbin");

  /** Workspace-relative build wrappers whose execution is explicitly supported. */
  private static final Set<String> GOVERNED_WORKSPACE_EXECUTABLES =
      Set.of("./gradlew", "./mvnw", "./gradlew.bat", "./mvnw.cmd");

  /**
   * Python virtual-environment launchers are equivalent to the already allowed {@code python} and
   * {@code pip} commands, but keep dependency installation inside the workspace. Only the
   * conventional venv directory at the current working directory is accepted; arbitrary nested
   * paths and other worker-written executable names remain blocked.
   */
  private static final String GOVERNED_PYTHON_VENV_EXECUTABLE =
      "^(?:\\./)?\\.?venv/bin/(?:python(?:3(?:\\.\\d+)?)?|pip(?:3(?:\\.\\d+)?)?)$";

  /**
   * Absolute prefixes an argument may reference. Workspace-relative work needs none of these; they
   * exist so ordinary toolchain invocations, scratch files, {@code /dev/null} redirects and
   * cleanroom-container paths are not falsely blocked. Deliberately excluded: {@code /etc}, {@code
   * /proc}, {@code /sys}, {@code /root}, {@code /home}, {@code /Users}, {@code /var/run}, and the
   * sandbox/workspace parent directories that hold OTHER runs.
   */
  private static final List<String> ALLOWED_ABSOLUTE_PREFIXES =
      List.of(
          "/dev/null",
          "/dev/stdout",
          "/dev/stderr",
          "/dev/zero",
          "/dev/urandom",
          "/tmp/",
          "/var/folders/",
          // Cleanroom-container workdirs. Commands for container dispatch are validated by the
          // same policy (WorkspaceTestRunner validates unconditionally), so these must pass.
          "/workspace",
          "/testbed",
          "/app",
          "/tests",
          // Toolchain binaries + their shipped libraries.
          "/bin/",
          "/usr/bin/",
          "/usr/local/bin/",
          "/usr/local/lib/",
          "/usr/lib/",
          "/usr/share/",
          "/sbin/",
          "/usr/sbin/",
          "/opt/homebrew/",
          "/Library/Java/");

  /** Outcome of first-token resolution: either a canonical command name or a rejection reason. */
  record CommandOrigin(String commandName, String rejection) {
    static CommandOrigin of(String name) {
      return new CommandOrigin(name, null);
    }

    static CommandOrigin rejected(String reason) {
      return new CommandOrigin(null, reason);
    }

    boolean isRejected() {
      return rejection != null;
    }
  }

  /**
   * Resolves the canonical command name a segment's first token stands for, refusing tokens whose
   * executable origin is worker-controlled (RR-833).
   */
  static CommandOrigin resolveCommandName(String firstToken) {
    if (firstToken == null || firstToken.isBlank()) {
      return CommandOrigin.rejected("empty command token");
    }
    String token = stripQuotes(firstToken);
    if (token.isEmpty()) {
      return CommandOrigin.rejected("empty command token");
    }
    if (!token.contains("/")) {
      return CommandOrigin.of(token);
    }
    if (GOVERNED_WORKSPACE_EXECUTABLES.contains(token)) {
      return CommandOrigin.of(basename(token));
    }
    if (token.matches(GOVERNED_PYTHON_VENV_EXECUTABLE)) {
      return CommandOrigin.of(basename(token));
    }
    if (!token.startsWith("/")) {
      return CommandOrigin.rejected(
          "path-qualified command '"
              + firstToken
              + "' is not allowed — a workspace file named like an approved tool is not that tool");
    }
    if (token.contains("..")) {
      return CommandOrigin.rejected("command path contains traversal: " + firstToken);
    }
    int slash = token.lastIndexOf('/');
    String dir = slash == 0 ? "/" : token.substring(0, slash);
    if (!TRUSTED_EXECUTABLE_DIRS.contains(dir)) {
      return CommandOrigin.rejected(
          "command '"
              + firstToken
              + "' is not in a trusted system executable directory "
              + TRUSTED_EXECUTABLE_DIRS);
    }
    return CommandOrigin.of(basename(token));
  }

  /**
   * Returns a rejection reason when any token in the segment references an absolute (or {@code ~/})
   * path outside the run workspace and the allowlisted roots, or {@code null} when the segment is
   * confined (RR-118).
   *
   * @param tokens whitespace-split segment tokens; index 0 (the command itself) is skipped because
   *     {@link #resolveCommandName} already governs it
   * @param workspacePath the run workspace root, or {@code null}/blank when the caller did not
   *     supply one (in which case only the fixed allowlist applies)
   */
  static String rejectUnconfinedAbsolutePath(String[] tokens, String workspacePath) {
    String root = normalizeRoot(workspacePath);
    for (int i = 1; i < tokens.length; i++) {
      String candidate = normalizeArgument(tokens[i]);
      if (candidate == null) continue;
      if (isConfined(candidate, root)) continue;
      return "absolute path '"
          + candidate
          + "' is outside the run workspace; shell commands may only reference workspace paths";
    }
    return null;
  }

  private static boolean isConfined(String candidate, String root) {
    if (root != null && (candidate.equals(root) || candidate.startsWith(root + "/"))) {
      return true;
    }
    for (String prefix : ALLOWED_ABSOLUTE_PREFIXES) {
      if (candidate.equals(prefix) || candidate.startsWith(prefix)) return true;
    }
    return false;
  }

  /**
   * Reduces a raw token to the absolute path it references, or {@code null} when it does not
   * reference one. Strips redirection operators and surrounding quotes so {@code >/etc/x} and
   * {@code "/etc/x"} are both seen. A token that is quoted-and-then-concatenated, built from a
   * variable, or produced by an interpreter is NOT seen — see the class javadoc.
   */
  private static String normalizeArgument(String rawToken) {
    if (rawToken == null || rawToken.isEmpty()) return null;
    String token = rawToken;
    while (!token.isEmpty() && (token.charAt(0) == '>' || token.charAt(0) == '<')) {
      token = token.substring(1);
    }
    token = stripQuotes(token);
    if (token.startsWith("~/")) {
      // Home-directory references (~/.aws/credentials, ~/.ssh/id_rsa) are never workspace paths.
      return token;
    }
    if (!token.startsWith("/")) return null;
    if (token.length() == 1) return "/";
    return trimTrailingSlash(token);
  }

  private static String normalizeRoot(String workspacePath) {
    if (workspacePath == null || workspacePath.isBlank()) return null;
    String root = workspacePath.trim();
    if (!root.startsWith("/")) return null;
    return trimTrailingSlash(root);
  }

  private static String trimTrailingSlash(String value) {
    String v = value;
    while (v.length() > 1 && v.endsWith("/")) {
      v = v.substring(0, v.length() - 1);
    }
    return v;
  }

  private static String stripQuotes(String token) {
    String t = token;
    while (!t.isEmpty() && (t.charAt(0) == '\'' || t.charAt(0) == '"')) {
      t = t.substring(1);
    }
    while (!t.isEmpty()) {
      char last = t.charAt(t.length() - 1);
      if (last != '\'' && last != '"') break;
      t = t.substring(0, t.length() - 1);
    }
    return t;
  }

  private static String basename(String token) {
    return token.substring(token.lastIndexOf('/') + 1);
  }
}
