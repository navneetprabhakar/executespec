package com.navneet.executespec.smoketest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Infers the project's TEST command from on-disk markers — the test-execution sibling of {@link
 * BuildCommandResolver}. Used by the BUILD-mode post-completion test stage so the verifiedScore
 * TESTS slot can use a real passed/total instead of an LLM coverage guess. Rationale:
 * RL-2026-05-001.
 *
 * <p>Deliberately conservative: returns {@link Optional#empty()} when there's no recognized test
 * runner, so the caller falls back to the LLM-judged coverage path rather than running a bogus
 * command. The {@link TestCommand#framework} tells the parser which output grammar to expect.
 */
@Component
public class TestCommandResolver {

  /** A resolved test command + the framework whose output grammar the parser should use. */
  public record TestCommand(
      String command, int timeoutSeconds, Framework framework, String source) {}

  public enum Framework {
    PYTEST,
    JEST,
    SUREFIRE, // mvn / gradle JUnit
    GO,
    CARGO,
    UNKNOWN
  }

  /**
   * Resolve the test command for a workspace. Order mirrors {@link BuildCommandResolver} — most-
   * specific markers first. Returns empty when no recognized runner is present.
   */
  public Optional<TestCommand> resolve(Path workspaceRoot) {
    if (workspaceRoot == null) return Optional.empty();

    if (exists(workspaceRoot, "pom.xml")) {
      // -q keeps output small; surefire still prints the "Tests run:" summary we parse.
      return Optional.of(new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven"));
    }
    if (exists(workspaceRoot, "build.gradle") || exists(workspaceRoot, "build.gradle.kts")) {
      String wrapper = exists(workspaceRoot, "gradlew") ? "./gradlew" : "gradle";
      // --info surfaces the per-class "Tests run" lines; gradle is quiet by default.
      return Optional.of(
          new TestCommand(wrapper + " test --console=plain", 600, Framework.SUREFIRE, "gradle"));
    }
    if (exists(workspaceRoot, "Cargo.toml")) {
      return Optional.of(new TestCommand("cargo test", 600, Framework.CARGO, "cargo"));
    }
    if (exists(workspaceRoot, "go.mod")) {
      return Optional.of(new TestCommand("go test ./...", 360, Framework.GO, "go"));
    }
    if (exists(workspaceRoot, "package.json")) {
      // Only run if a "test" script exists AND it isn't the npm-init placeholder that exits 1.
      String pkg = read(workspaceRoot.resolve("package.json"));
      if (pkg.contains("\"test\"") && !pkg.contains("Error: no test specified")) {
        return Optional.of(new TestCommand("npm test --silent", 600, Framework.JEST, "npm"));
      }
      return Optional.empty();
    }
    if (exists(workspaceRoot, "pyproject.toml")
        || exists(workspaceRoot, "setup.py")
        || exists(workspaceRoot, "requirements.txt")
        || hasPytestFiles(workspaceRoot)) {
      // -q gives the "N passed, M failed" summary line the parser reads.
      return Optional.of(new TestCommand("python3 -m pytest -q", 600, Framework.PYTEST, "pytest"));
    }
    return Optional.empty();
  }

  private static boolean exists(Path dir, String name) {
    try {
      return Files.exists(dir.resolve(name));
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean hasPytestFiles(Path dir) {
    try (var s = Files.walk(dir, 3)) {
      return s.anyMatch(
          p -> {
            String n = p.getFileName() == null ? "" : p.getFileName().toString();
            return n.startsWith("test_") && n.endsWith(".py")
                || n.endsWith("_test.py")
                || n.equals("conftest.py");
          });
    } catch (Exception e) {
      return false;
    }
  }

  private static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (Exception e) {
      return "";
    }
  }
}
