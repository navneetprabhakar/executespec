package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.navneet.executespec.security.guardrail.GuardrailLevel;

import org.junit.jupiter.api.Test;

/** Guardrail-level tests for {@link FileSandboxPolicy} (Plan 1 P5). */
class FileSandboxPolicyLevelTest {

  private final FileSandboxPolicy policy = new FileSandboxPolicy();
  private final Path root = Paths.get("/ws/run-abc").toAbsolutePath();

  @Test
  void lowAllowsEverything() {
    assertThat(
            policy
                .validateWrite(root, root.resolve("randomfile.txt"), GuardrailLevel.LOW)
                .allowed())
        .isTrue();
    assertThat(
            policy.validateWrite(root, root.resolve(".secretfile"), GuardrailLevel.LOW).allowed())
        .isTrue();
    assertThat(
            policy
                .validateWrite(root, root.resolve("config/app.yml"), GuardrailLevel.LOW)
                .allowed())
        .isTrue();
  }

  @Test
  void mediumAllowsSrcTestPublicResources() {
    assertThat(
            policy
                .validateWrite(root, root.resolve("src/main.js"), GuardrailLevel.MEDIUM)
                .allowed())
        .isTrue();
    assertThat(
            policy
                .validateWrite(root, root.resolve("test/sanity.spec.ts"), GuardrailLevel.MEDIUM)
                .allowed())
        .isTrue();
    assertThat(
            policy
                .validateWrite(root, root.resolve("public/index.html"), GuardrailLevel.MEDIUM)
                .allowed())
        .isTrue();
    assertThat(
            policy
                .validateWrite(root, root.resolve("resources/logo.png"), GuardrailLevel.MEDIUM)
                .allowed())
        .isTrue();
  }

  @Test
  void mediumAllowsKnownRootConfigs() {
    assertThat(
            policy
                .validateWrite(root, root.resolve("package.json"), GuardrailLevel.MEDIUM)
                .allowed())
        .isTrue();
    assertThat(policy.validateWrite(root, root.resolve("pom.xml"), GuardrailLevel.MEDIUM).allowed())
        .isTrue();
    assertThat(
            policy.validateWrite(root, root.resolve("Dockerfile"), GuardrailLevel.MEDIUM).allowed())
        .isTrue();
    assertThat(
            policy.validateWrite(root, root.resolve("README.md"), GuardrailLevel.MEDIUM).allowed())
        .isTrue();
  }

  @Test
  void mediumBlocksRandomRootFile() {
    var decision =
        policy.validateWrite(root, root.resolve("random_output.txt"), GuardrailLevel.MEDIUM);
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).contains("writes restricted to");
  }

  @Test
  void mediumBlocksWriteOutsideAllowedDirs() {
    var decision =
        policy.validateWrite(root, root.resolve("build/junk.bin"), GuardrailLevel.MEDIUM);
    assertThat(decision.allowed()).isFalse();
  }

  @Test
  void highDotfileLockdown() {
    // canonical dotfiles still allowed under src/ (dir allowlist) or root (explicit)
    assertThat(
            policy.validateWrite(root, root.resolve(".gitignore"), GuardrailLevel.HIGH).allowed())
        .isTrue();
    assertThat(
            policy.validateWrite(root, root.resolve(".env.example"), GuardrailLevel.HIGH).allowed())
        .isTrue();
    // arbitrary dotfile blocked at HIGH (src/.env)
    var decision = policy.validateWrite(root, root.resolve("src/.env"), GuardrailLevel.HIGH);
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).contains("HIGH");
  }

  @Test
  void escapeAttemptBlocked() {
    var decision =
        policy.validateWrite(
            root, root.resolve("../secret.txt").normalize(), GuardrailLevel.MEDIUM);
    assertThat(decision.allowed()).isFalse();
  }

  @Test
  void nullLevelTreatedAsLow() {
    assertThat(policy.validateWrite(root, root.resolve("random_output.txt"), null).allowed())
        .isTrue();
  }
}
