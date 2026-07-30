package com.navneet.executespec.smoketest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    Optional<TestCommand> root =
        hasProjectManifest(workspaceRoot) ? resolveSingle(workspaceRoot) : Optional.empty();
    if (root.isPresent()) return root;

    List<String> commands = new ArrayList<>();
    int timeout = 0;
    try (var children = Files.list(workspaceRoot)) {
      for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
        String name = child.getFileName().toString();
        if (!name.matches("[A-Za-z0-9._-]+")) continue;
        Optional<TestCommand> resolved = resolveSingle(child);
        if (resolved.isEmpty()) continue;
        commands.add("(cd " + name + " && " + resolved.get().command() + ")");
        timeout += resolved.get().timeoutSeconds();
      }
    } catch (Exception ignored) {
      return Optional.empty();
    }
    if (commands.isEmpty()) return resolveSingle(workspaceRoot);
    if (commands.size() == 1) {
      String command = commands.getFirst();
      Optional<TestCommand> child = directChildCommand(workspaceRoot, command);
      return child;
    }
    return Optional.of(
        new TestCommand(
            String.join(" && ", commands), Math.min(timeout, 1200), Framework.MULTI, "multi-root"));
  }

  private Optional<TestCommand> directChildCommand(Path workspaceRoot, String wrappedCommand) {
    String childName = wrappedCommand.substring(4, wrappedCommand.indexOf(" && "));
    return resolveSingle(workspaceRoot.resolve(childName))
        .map(
            command ->
                new TestCommand(
                    wrappedCommand,
                    command.timeoutSeconds(),
                    command.framework(),
                    command.source() + "@" + childName));
  }

  private Optional<TestCommand> resolveSingle(Path workspaceRoot) {

    if (exists(workspaceRoot, "pom.xml")) {
      // -q keeps output small; surefire still prints the "Tests run:" summary we parse.
      String wrapper = exists(workspaceRoot, "mvnw") ? "./mvnw" : "mvn";
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
        Framework framework =
            pkg.toLowerCase().contains("vitest") ? Framework.VITEST : Framework.JEST;
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

  private static boolean hasProjectManifest(Path root) {
    return exists(root, "pom.xml")
        || exists(root, "build.gradle")
        || exists(root, "build.gradle.kts")
        || exists(root, "Cargo.toml")
        || exists(root, "go.mod")
        || exists(root, "package.json")
        || exists(root, "pyproject.toml")
        || exists(root, "setup.py")
        || exists(root, "requirements.txt");
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
