package com.navneet.executespec.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 2026-07-17 credential gate — git credentials must never appear in argv or the remote URL. The
 * token rides the child environment; argv carries only the literal unexpanded variable reference.
 */
class GitProcessAuthTest {

  @Test
  void tokenNeverEntersArgvAndRidesTheChildEnvironment() {
    String pat = "ghp_supersecrettoken1234567890";
    ProcessBuilder pb =
        GitProcessAuth.git(pat, List.of("clone", "https://github.com/acme/api", "/tmp/x"));

    String argv = String.join(" ", pb.command());
    assertThat(argv).doesNotContain(pat);
    assertThat(argv).contains("credential.helper=");
    assertThat(argv).contains("$" + "{" + GitProcessAuth.TOKEN_ENV + "}");
    assertThat(pb.command().get(0)).isEqualTo("git");
    // The URL stays clean — no oauth2:...@ embedding.
    assertThat(argv).contains("https://github.com/acme/api");
    assertThat(argv).doesNotContain("@github.com");
    assertThat(pb.environment()).containsEntry(GitProcessAuth.TOKEN_ENV, pat);
  }

  @Test
  void usernameVariantCarriesBothValuesInEnvOnly() {
    ProcessBuilder pb =
        GitProcessAuth.git(
            "app-password-value", "bb-user", List.of("push", "https://bitbucket.org/w/r", "main"));

    String argv = String.join(" ", pb.command());
    assertThat(argv).doesNotContain("app-password-value").doesNotContain("bb-user");
    assertThat(pb.environment())
        .containsEntry(GitProcessAuth.TOKEN_ENV, "app-password-value")
        .containsEntry(GitProcessAuth.USER_ENV, "bb-user");
  }

  @Test
  void inheritedCredentialHelpersAreResetBeforeTheEphemeralOne() {
    ProcessBuilder pb = GitProcessAuth.git("t", List.of("fetch"));
    List<String> cmd = pb.command();

    int emptyReset = cmd.indexOf("credential.helper=");
    assertThat(emptyReset).isPositive();
    assertThat(cmd.subList(emptyReset + 1, cmd.size()).toString())
        .contains("credential.helper=!f()");
  }

  @Test
  void blankTokenYieldsUnauthenticatedGitWithoutACredentialHelper() {
    ProcessBuilder pb = GitProcessAuth.git(null, List.of("clone", "https://github.com/pub/repo"));

    assertThat(String.join(" ", pb.command())).doesNotContain("credential.helper");
    assertThat(pb.command()).endsWith("clone", "https://github.com/pub/repo");
    assertThat(pb.environment()).doesNotContainKey(GitProcessAuth.TOKEN_ENV);
  }

  /**
   * RR-874 — a credential-bearing push must not hand the token to a repository-controlled hook.
   * Hooks are disabled by an absolute path that cannot hold an executable, so neither {@code
   * .git/hooks} nor a repository-set {@code core.hooksPath} can run.
   */
  @Test
  void hooksAndCommandExecutingConfigAreDisabledOnEveryInvocation() {
    ProcessBuilder authenticated =
        GitProcessAuth.git("ghp_x", List.of("-C", "/ws", "push", "https://github.com/a/b", "br"));
    ProcessBuilder anonymous = GitProcessAuth.git(null, List.of("clone", "https://github.com/a/b"));

    for (ProcessBuilder pb : List.of(authenticated, anonymous)) {
      List<String> cmd = pb.command();
      assertThat(cmd).contains("core.hooksPath=" + GitProcessAuth.NO_HOOKS_PATH);
      assertThat(cmd).contains("core.fsmonitor=false");
      assertThat(cmd).contains("protocol.ext.allow=never");
      // The containment must precede the git subcommand, otherwise git ignores it.
      assertThat(cmd.indexOf("core.hooksPath=" + GitProcessAuth.NO_HOOKS_PATH))
          .isLessThan(cmd.size() - 1);
    }
  }

  @Test
  void inheritedGitAndAskpassEnvironmentIsStrippedBeforeTheCredentialIsAttached() {
    java.util.Map<String, String> env = new java.util.HashMap<>();
    env.put("GIT_CONFIG_COUNT", "1");
    env.put("GIT_CONFIG_KEY_0", "core.hooksPath");
    env.put("GIT_ASKPASS", "/tmp/evil");
    env.put("SSH_ASKPASS", "/tmp/evil");
    env.put("GIT_SSL_CAINFO", "/etc/ssl/cert.pem");
    env.put("PATH", "/usr/bin");

    GitProcessAuth.hardenEnvironment(env);

    assertThat(env)
        .doesNotContainKeys("GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_ASKPASS", "SSH_ASKPASS")
        .containsEntry("GIT_SSL_CAINFO", "/etc/ssl/cert.pem")
        .containsEntry("PATH", "/usr/bin")
        .containsEntry("GIT_TERMINAL_PROMPT", "0")
        .containsEntry("GIT_CONFIG_NOSYSTEM", "1");
  }
}
