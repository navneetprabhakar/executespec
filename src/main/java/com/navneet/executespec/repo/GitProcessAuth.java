package com.navneet.executespec.repo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2026-07-17 credential gate — git process authentication WITHOUT credential material in argv or
 * remotes.
 *
 * <p>The historical pattern embedded the token in the URL ({@code https://oauth2:PAT@github.com/…})
 * which leaked it three ways: process argv (visible to {@code ps}/procfs), the persisted {@code
 * .git/config} remote URL inside the run workspace (readable by the worker's own {@code readFile}
 * tool), and any log/error echo of the command line.
 *
 * <p>This helper instead passes credentials through a per-invocation {@code credential.helper}
 * whose shell body references environment variables. {@link ProcessBuilder} passes arguments
 * without a shell, so argv carries only the literal unexpanded {@code $GIT_AUTH_TOKEN} string; git
 * runs the helper via {@code sh -c}, which expands the variables from the child process
 * environment. Nothing credential-bearing is ever written to disk or argv.
 *
 * <p><b>RR-874 (2026-07-20) — repository-controlled code must not observe the credential.</b> Git
 * runs hooks with the full parent environment, so a {@code pre-push} hook committed into (or
 * written into) the workspace by generated code used to be handed {@code GIT_AUTH_TOKEN} during
 * exactly the privileged operation this class exists to protect. Every invocation now:
 *
 * <ul>
 *   <li>pins {@code core.hooksPath} to a path that cannot contain an executable hook, which
 *       overrides both {@code .git/hooks} and any repository-set {@code core.hooksPath};
 *   <li>disables the {@code fsmonitor} hook and the {@code ext::} transport, the two other
 *       repository-config paths that execute an arbitrary command during a fetch/push;
 *   <li>ignores system/global git config so only the explicit {@code -c} settings and the
 *       repository's own (untrusted, but now inert) config apply;
 *   <li>strips inherited {@code GIT_*} and {@code *ASKPASS} variables so a poisoned parent
 *       environment cannot re-enable a helper or prompt.
 * </ul>
 *
 * <p>This is a bounded containment of the credential-bearing invocation, not a sandbox: it does not
 * stop repository config from rewriting a push URL ({@code url.<base>.insteadOf}), which remains
 * the separate host-binding finding.
 */
public final class GitProcessAuth {

  /** Environment variable read by the ephemeral credential helper. */
  public static final String TOKEN_ENV = "GIT_AUTH_TOKEN";

  /** Optional username env (defaults to {@code oauth2}, GitHub's PAT convention). */
  public static final String USER_ENV = "GIT_AUTH_USER";

  /**
   * Hooks directory for every invocation. {@code /dev/null} is a character device, so git's attempt
   * to stat/execute {@code /dev/null/<hook>} fails with {@code ENOTDIR} and the hook is treated as
   * absent. Being absolute, it cannot be reinterpreted relative to the untrusted worktree.
   */
  static final String NO_HOOKS_PATH = "/dev/null";

  /**
   * Inherited environment variables that survive the {@code GIT_*} strip because they carry TLS
   * trust material rather than behaviour.
   */
  private static final Set<String> GIT_ENV_ALLOWLIST =
      Set.of("GIT_SSL_CAINFO", "GIT_SSL_CAPATH", "GIT_EXEC_PATH");

  private static final String HELPER =
      "!f() { echo \"username=${"
          + USER_ENV
          + ":-oauth2}\"; echo \"password=${"
          + TOKEN_ENV
          + "}\"; }; f";

  private GitProcessAuth() {}

  /**
   * Build a git ProcessBuilder that authenticates via environment-carried credentials.
   *
   * @param token credential (PAT / app password); null or blank yields an unauthenticated command
   * @param username helper username; null defaults to {@code oauth2}
   * @param gitArgs the git arguments AFTER the {@code git} executable (e.g. {@code clone}, url, …)
   */
  public static ProcessBuilder git(String token, String username, List<String> gitArgs) {
    boolean authenticated = token != null && !token.isBlank();
    List<String> command = new ArrayList<>();
    command.add("git");
    // Untrusted-repository containment — applied whether or not a credential is attached so the
    // behaviour of an authenticated and an unauthenticated invocation cannot diverge.
    command.add("-c");
    command.add("core.hooksPath=" + NO_HOOKS_PATH);
    command.add("-c");
    command.add("core.fsmonitor=false");
    command.add("-c");
    command.add("protocol.ext.allow=never");
    if (authenticated) {
      // Reset any inherited helper first so ONLY the ephemeral env-backed helper answers.
      command.add("-c");
      command.add("credential.helper=");
      command.add("-c");
      command.add("credential.helper=" + HELPER);
    }
    command.addAll(gitArgs);
    ProcessBuilder pb = new ProcessBuilder(command);
    hardenEnvironment(pb.environment());
    if (authenticated) {
      pb.environment().put(TOKEN_ENV, token);
      if (username != null && !username.isBlank()) {
        pb.environment().put(USER_ENV, username);
      }
    }
    return pb;
  }

  /** GitHub-shaped convenience: username {@code oauth2}. */
  public static ProcessBuilder git(String token, List<String> gitArgs) {
    return git(token, null, gitArgs);
  }

  /**
   * Remove inherited git/askpass configuration from the child environment and pin the
   * non-interactive, config-isolated defaults. Package-private for direct test assertion.
   */
  static void hardenEnvironment(Map<String, String> env) {
    env.keySet()
        .removeIf(
            key ->
                key != null
                    && ((key.startsWith("GIT_") && !GIT_ENV_ALLOWLIST.contains(key))
                        || key.endsWith("ASKPASS")));
    env.put("GIT_TERMINAL_PROMPT", "0");
    env.put("GIT_CONFIG_NOSYSTEM", "1");
    env.put("GIT_CONFIG_GLOBAL", "/dev/null");
  }
}
