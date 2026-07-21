package com.navneet.executespec.repo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
 * <p><b>RR-1669 (2026-07-21) — the credential must reach the host we named.</b> The containment
 * above stops repository-controlled code from <i>running</i>, but repository-<i>local</i> config is
 * still honoured, and {@code url.<base>.insteadOf} / {@code pushInsteadOf} rewrites the URL git
 * actually contacts. There is no git switch that ignores local config while operating inside that
 * repository, so a workspace whose {@code .git/config} was edited during the run could silently
 * redirect an authenticated push — carrying the caller's PAT — to a host it controls.
 *
 * <p>Every credential-bearing invocation that resolves a remote URL ({@code push}, {@code fetch},
 * {@code pull}, {@code ls-remote}, {@code remote}, {@code submodule}, {@code archive} — {@code
 * clone} is exempt because no repository config exists yet and the URL is ours) is therefore
 * preceded by {@link #assertRemoteUrlNotRewritten}: a {@code git config --get-regexp ^url\.} read
 * under this class's own hardened environment, so it resolves EXACTLY the rewrite set the real
 * invocation would apply — including rules delivered indirectly through {@code include.path} /
 * {@code includeIf}, which a {@code --local}-scoped read does not follow (verified against git
 * 2.50.1). Any hit refuses the invocation; findings carry a count, never the attacker-chosen URL.
 *
 * <p><b>What this does and does not promise.</b> Refusal is correct rather than restrictive because
 * {@code .git/config} is not repository content: ExecuteSpec creates it by cloning, and no
 * legitimate ExecuteSpec flow writes a {@code url.*} rule into a run workspace — so a benign
 * rewrite cannot arrive through a customer's tracked files. Two residuals remain: the check is a
 * pre-flight, so a write landing between the scan and process start is not covered, and an
 * invocation whose repository directory cannot be determined is refused rather than scanned.
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
    return git(token, username, repositoryDirFromArgs(gitArgs), gitArgs);
  }

  /**
   * As {@link #git(String, String, List)}, but with the repository directory supplied explicitly.
   * Required for callers that select the working directory via {@link ProcessBuilder#directory}
   * instead of a {@code -C} argument, because the RR-1669 rewrite scan must run in the same
   * repository the invocation will.
   *
   * @param repositoryDir repository the command operates in; may be null for {@code clone} and
   *     other invocations that do not resolve a remote URL from repository config
   */
  public static ProcessBuilder git(
      String token, String username, Path repositoryDir, List<String> gitArgs) {
    boolean authenticated = token != null && !token.isBlank();
    if (authenticated && resolvesRemoteUrl(gitArgs)) {
      assertRemoteUrlNotRewritten(repositoryDir, gitArgs);
    }
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

  /** Thrown when a credential-bearing git invocation cannot be proven to reach the named host. */
  public static final class GitRemoteRewriteException extends IllegalStateException {
    GitRemoteRewriteException(String message) {
      super(message);
    }
  }

  /**
   * Git subcommands that resolve a remote URL through repository config, and are therefore subject
   * to {@code url.<base>.insteadOf} / {@code pushInsteadOf} rewriting. {@code clone} is absent
   * deliberately: it runs before any repository config exists and against a URL we chose.
   */
  private static final Set<String> REMOTE_URL_SUBCOMMANDS =
      Set.of("push", "fetch", "pull", "ls-remote", "remote", "submodule", "archive");

  /** Git options that consume the following argument, so it is not the subcommand. */
  private static final Set<String> VALUE_OPTIONS =
      Set.of("-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path", "--config-env");

  /** Bound on the pre-flight config read; a git that will not answer must not stall a push. */
  private static final int SCAN_TIMEOUT_SECONDS = 20;

  static boolean resolvesRemoteUrl(List<String> gitArgs) {
    String subcommand = subcommand(gitArgs);
    return subcommand != null && REMOTE_URL_SUBCOMMANDS.contains(subcommand);
  }

  private static String subcommand(List<String> gitArgs) {
    for (int i = 0; i < gitArgs.size(); i++) {
      String arg = gitArgs.get(i);
      if (arg == null) continue;
      if (VALUE_OPTIONS.contains(arg)) {
        i++;
        continue;
      }
      if (arg.startsWith("-")) continue;
      return arg;
    }
    return null;
  }

  static Path repositoryDirFromArgs(List<String> gitArgs) {
    for (int i = 0; i < gitArgs.size() - 1; i++) {
      if ("-C".equals(gitArgs.get(i))) {
        String dir = gitArgs.get(i + 1);
        if (dir != null && !dir.isBlank()) return Path.of(dir);
      }
    }
    return null;
  }

  /**
   * RR-1669 — refuse the invocation unless the effective config in {@code repositoryDir} contains
   * no {@code url.*} rewrite. The scan runs under {@link #hardenEnvironment}, so system and global
   * config are excluded exactly as they will be for the real command and every remaining hit is a
   * rule that would actually apply. Fails closed on an undeterminable directory or an unexpected
   * scan outcome: this is a credential-destination boundary, not a best-effort hint.
   */
  static void assertRemoteUrlNotRewritten(Path repositoryDir, List<String> gitArgs) {
    if (repositoryDir == null) {
      throw new GitRemoteRewriteException(
          "Refusing authenticated git "
              + subcommand(gitArgs)
              + ": the repository directory could not be determined, so the remote URL cannot be"
              + " proven un-rewritten.");
    }
    ProcessBuilder scan =
        new ProcessBuilder(
            "git",
            "-c",
            "core.hooksPath=" + NO_HOOKS_PATH,
            "-c",
            "core.fsmonitor=false",
            "-c",
            "protocol.ext.allow=never",
            "-C",
            repositoryDir.toString(),
            "config",
            "--get-regexp",
            "^url\\.");
    hardenEnvironment(scan.environment());
    scan.redirectErrorStream(false);
    scan.redirectError(ProcessBuilder.Redirect.DISCARD);
    Process process = null;
    try {
      process = scan.start();
      // Read before waiting: a full pipe buffer would otherwise deadlock the child.
      long rewrites;
      try (var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        rewrites = reader.lines().filter(line -> !line.isBlank()).count();
      }
      if (!process.waitFor(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new GitRemoteRewriteException(
            "Refusing authenticated git "
                + subcommand(gitArgs)
                + ": the remote-URL rewrite check did not complete.");
      }
      int exit = process.exitValue();
      if (exit == 1 && rewrites == 0) return; // git config: no matching key
      if (exit == 0 && rewrites > 0) {
        // Deliberately count-only: the matched keys and values embed the attacker-chosen host.
        throw new GitRemoteRewriteException(
            "Refusing authenticated git "
                + subcommand(gitArgs)
                + ": the repository configures "
                + rewrites
                + " url.* rewrite rule(s), which can redirect the credential to another host.");
      }
      throw new GitRemoteRewriteException(
          "Refusing authenticated git "
              + subcommand(gitArgs)
              + ": the remote-URL rewrite check returned an unexpected result.");
    } catch (java.io.IOException e) {
      throw new GitRemoteRewriteException(
          "Refusing authenticated git "
              + subcommand(gitArgs)
              + ": the remote-URL rewrite check could not run.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitRemoteRewriteException(
          "Refusing authenticated git "
              + subcommand(gitArgs)
              + ": the remote-URL rewrite check was interrupted.");
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
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
