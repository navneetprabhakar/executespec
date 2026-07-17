package com.navneet.executespec.repo;

import java.util.ArrayList;
import java.util.List;

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
 */
public final class GitProcessAuth {

  /** Environment variable read by the ephemeral credential helper. */
  public static final String TOKEN_ENV = "GIT_AUTH_TOKEN";

  /** Optional username env (defaults to {@code oauth2}, GitHub's PAT convention). */
  public static final String USER_ENV = "GIT_AUTH_USER";

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
    List<String> command = new ArrayList<>();
    command.add("git");
    if (token != null && !token.isBlank()) {
      // Reset any inherited helper first so ONLY the ephemeral env-backed helper answers.
      command.add("-c");
      command.add("credential.helper=");
      command.add("-c");
      command.add("credential.helper=" + HELPER);
    }
    command.addAll(gitArgs);
    ProcessBuilder pb = new ProcessBuilder(command);
    if (token != null && !token.isBlank()) {
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
}
