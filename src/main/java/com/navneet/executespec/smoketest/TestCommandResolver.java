package com.navneet.executespec.smoketest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

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

  private static final ObjectMapper JSON = new ObjectMapper();

  private record LocatedCommand(String relativePath, TestCommand command) {}

  /** A resolved test command + the framework whose output grammar the parser should use. */
  public record TestCommand(
      String command, int timeoutSeconds, Framework framework, String source) {}

  public enum Framework {
    PYTEST,
    NODE_TEST,
    JEST,
    VITEST,
    SUREFIRE, // mvn / gradle JUnit
    GO,
    CARGO,
    MULTI,
    UNKNOWN
  }

  /**
   * Resolve the test command for a workspace. Order mirrors {@link BuildCommandResolver} — most-
   * specific markers first. Returns empty when no recognized runner is present.
   */
  public Optional<TestCommand> resolve(Path workspaceRoot) {
    if (workspaceRoot == null) return Optional.empty();
    Path normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize();

    BuildRootDiscovery.Selection selection = BuildRootDiscovery.select(normalizedWorkspace);
    if (selection.ambiguous()) return Optional.empty();
    if (selection.roots().size() == 1 && selection.roots().getFirst().equals(normalizedWorkspace)) {
      return resolveSingle(normalizedWorkspace);
    }

    List<LocatedCommand> resolvedCommands = new ArrayList<>();
    int timeout = 0;
    try {
      for (Path child : selection.roots()) {
        String relative = normalizedWorkspace.relativize(child).toString();
        if (!relative.matches("[A-Za-z0-9._/-]+")) continue;
        Optional<TestCommand> resolved = resolveSingle(child);
        if (resolved.isEmpty()) continue;
        resolvedCommands.add(new LocatedCommand(relative, resolved.get()));
        timeout += resolved.get().timeoutSeconds();
      }
    } catch (Exception ignored) {
      return Optional.empty();
    }
    if (resolvedCommands.isEmpty()) return resolveSingle(normalizedWorkspace);
    if (resolvedCommands.size() == 1) {
      LocatedCommand located = resolvedCommands.getFirst();
      TestCommand command = located.command();
      return Optional.of(
          new TestCommand(
              "(cd " + located.relativePath() + " && " + command.command() + ")",
              command.timeoutSeconds(),
              command.framework(),
              command.source() + "@" + located.relativePath()));
    }
    List<String> commands =
        resolvedCommands.stream()
            .map(c -> "(cd " + c.relativePath() + " && " + c.command().command() + ")")
            .toList();
    return Optional.of(
        new TestCommand(
            String.join(" && ", commands), Math.min(timeout, 1200), Framework.MULTI, "multi-root"));
  }

  private Optional<TestCommand> resolveSingle(Path workspaceRoot) {

    if (exists(workspaceRoot, "pom.xml")) {
      // -q keeps output small; surefire still prints the "Tests run:" summary we parse.
      String wrapper = executable(workspaceRoot, "mvnw") ? "./mvnw" : "mvn";
      return Optional.of(new TestCommand(wrapper + " -q test", 600, Framework.SUREFIRE, "maven"));
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
        String normalizedScript = testScript(pkg).toLowerCase();
        Framework framework =
            normalizedScript.contains("node --test")
                ? Framework.NODE_TEST
                : normalizedScript.contains("vitest") ? Framework.VITEST : Framework.JEST;
        return Optional.of(new TestCommand("npm test --silent", 600, framework, "npm"));
      }
      return Optional.empty();
    }
    if (exists(workspaceRoot, "pyproject.toml")
        || exists(workspaceRoot, "setup.py")
        || exists(workspaceRoot, "requirements.txt")
        || hasPytestFiles(workspaceRoot)) {
      // -q gives the "N passed, M failed" summary line the parser reads.
      String python =
          exists(workspaceRoot, ".venv/bin/python")
              ? ".venv/bin/python"
              : exists(workspaceRoot, "venv/bin/python") ? "venv/bin/python" : "python3";
      return Optional.of(
          new TestCommand(python + " -m pytest -q", 600, Framework.PYTEST, "pytest"));
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

  private static boolean executable(Path dir, String name) {
    try {
      Path candidate = dir.resolve(name);
      return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    } catch (RuntimeException ignored) {
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

  private static String testScript(String packageJson) {
    try {
      return JSON.readTree(packageJson).path("scripts").path("test").asText("");
    } catch (Exception ignored) {
      return "";
    }
  }
}
