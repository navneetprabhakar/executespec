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
  void blankTokenYieldsPlainUnauthenticatedGit() {
    ProcessBuilder pb = GitProcessAuth.git(null, List.of("clone", "https://github.com/pub/repo"));

    assertThat(pb.command()).containsExactly("git", "clone", "https://github.com/pub/repo");
    assertThat(pb.environment()).doesNotContainKey(GitProcessAuth.TOKEN_ENV);
  }
}
