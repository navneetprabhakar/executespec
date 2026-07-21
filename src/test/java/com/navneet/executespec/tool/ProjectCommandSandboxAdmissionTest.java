package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.security.guardrail.GuardrailLevel;

import org.junit.jupiter.api.Test;

/**
 * RR-1545 — ExecuteSpec's own command detection emitted commands its own sandbox always rejected,
 * so {@code testProject} / {@code installDependencies} / {@code lintProject} / {@code
 * formatProject} were permanently FORBIDDEN for Gradle-wrapper, Ruff and Go projects, and the
 * failure text blamed the sandbox rather than an unsupported toolchain.
 */
class ProjectCommandSandboxAdmissionTest {

  private final ShellSandboxPolicy policy = new ShellSandboxPolicy();

  /** Every command {@code ProjectCommandTool.detect(...)} can produce. */
  private static final List<String> DETECTED_COMMANDS =
      List.of(
          // install
          "pnpm install --frozen-lockfile",
          "yarn install --frozen-lockfile",
          "npm ci",
          "npm install",
          "mvn -q -DskipTests dependency:go-offline",
          "./gradlew dependencies --quiet",
          "cargo fetch",
          "go mod download",
          "python -m pip install -r requirements.txt",
          "python -m pip install -e .",
          // test
          "mvn -q test",
          "./gradlew test",
          "cargo test",
          "go test ./...",
          "pytest -q",
          // lint
          "cargo clippy --all-targets --all-features",
          "go vet ./...",
          "ruff check .",
          // format
          "cargo fmt",
          "gofmt -w .",
          "ruff format .");

  @Test
  void everyDetectedCommandIsAdmittedAtLowAndMedium() {
    for (String command : DETECTED_COMMANDS) {
      for (GuardrailLevel level : List.of(GuardrailLevel.LOW, GuardrailLevel.MEDIUM)) {
        var result = policy.validate(command, "/ws", level);
        assertThat(result.permitted())
            .as("%s at %s → %s", command, level, result.reason())
            .isTrue();
      }
    }
  }

  @Test
  void previouslyUnallowlistedToolchainsAreNowAdmittedAtHigh() {
    for (String command :
        List.of(
            "./gradlew test",
            "./gradlew dependencies --quiet",
            "ruff check .",
            "ruff format .",
            "gofmt -w .")) {
      var result = policy.validate(command, "/ws", GuardrailLevel.HIGH);
      assertThat(result.permitted()).as("%s → %s", command, result.reason()).isTrue();
    }
  }

  @Test
  void highStillBlocksArbitraryPackageScripts() {
    // Deliberately NOT relaxed: a package.json script is arbitrary user code, and blocking it is
    // the purpose of the HIGH verb allowlist. `npm run <script>` therefore stays refused at HIGH.
    assertThat(policy.validate("npm run lint", "/ws", GuardrailLevel.HIGH).permitted()).isFalse();
  }

  @Test
  void ruffAndGradlewStillRejectNonCanonicalVerbsAtHigh() {
    assertThat(policy.validate("ruff server", "/ws", GuardrailLevel.HIGH).permitted()).isFalse();
    assertThat(
            policy
                .validate("./gradlew publishToMavenLocal", "/ws", GuardrailLevel.HIGH)
                .permitted())
        .isFalse();
  }
}
