package com.navneet.executespec.smoketest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the build command for a given workspace.
 *
 * <p>Resolution order (per V2 plan P1.4):
 *
 * <ol>
 *   <li><b>Planner-declared</b> — explicit {@code buildCommand} field on the plan JSON (passed in
 *       via {@link #resolve(Path, String)} second argument)
 *   <li><b>Framework-inferred</b> — sniffs known marker files in the workspace ({@code
 *       package.json}, {@code pom.xml}, {@code build.gradle}, {@code Cargo.toml}, {@code go.mod},
 *       {@code requirements.txt}) and maps to the conventional command
 *   <li><b>Fallback</b> — {@code "true"} (no-op success). Means the smoke test always passes for
 *       unknown project types; the run still completes but the smoke layer provides no signal.
 *       Logged at WARN.
 * </ol>
 *
 * <p>Idempotent and side-effect-free; safe to call repeatedly.
 */
@Component
@Slf4j
public class BuildCommandResolver {

  private static final Pattern DEPENDENCY_FREE_JAVA_SMOKE =
      Pattern.compile(
          "^javac(?:\\s+(?:[A-Za-z0-9_$*]+/)*[A-Za-z0-9_$*]+\\.java)+"
              + "(?:\\s*&&\\s*java\\s+[A-Za-z_$][A-Za-z0-9_$.]*)?$");

  public BuildCommand resolve(Path workspaceRoot, String plannerDeclaredCommand) {
    Path normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize();
    BuildRootDiscovery.Selection selection = BuildRootDiscovery.select(normalizedWorkspace);
    if (!selection.roots().isEmpty()
        && !(selection.roots().size() == 1
            && selection.roots().getFirst().equals(normalizedWorkspace))) {
      return commandForRoots(normalizedWorkspace, selection.roots());
    }

    if (plannerDeclaredCommand != null && !plannerDeclaredCommand.isBlank()) {
      String cmd = normalizePython(plannerDeclaredCommand.trim());
      // A build smoke must be a repeatable verification command. Model-authored plans sometimes
      // put environment setup (`pip install`, `npm install`) or the long-running server command in
      // this field. Those commands mutate the host/toolchain, fail under externally-managed Python
      // (PEP 668), or hang until timeout; none proves the source compiles. Ignore them and use the
      // deterministic framework resolver below. The shell sandbox remains the authority for
      // command safety; this check is the narrower semantic contract for build evidence.
      if (!isRepeatableBuildSmoke(cmd)) {
        log.warn(
            "BuildCommandResolver: ignoring non-build planner command; using framework inference"
                + " command={}",
            cmd);
      } else {
        return BuildCommand.builder()
            .command(cmd)
            .timeoutSeconds(180)
            .sourceDescription("planner-declared")
            .validationRoots(selection.roots())
            .build();
      }
    }
    // 1) Root markers (single-repo + V1-style runs)
    Optional<BuildCommand> root = inferFromFramework(normalizedWorkspace);
    if (root.isPresent()) {
      BuildCommand command = root.get();
      return BuildCommand.builder()
          .command(command.command())
          .timeoutSeconds(command.timeoutSeconds())
          .sourceDescription(command.sourceDescription())
          .validationRoots(selection.roots())
          .build();
    }

    log.warn(
        "BuildCommandResolver: {} in {}; falling back to unavailable no-op",
        selection.ambiguous()
            ? "multiple build roots exist but none contains a Git working-tree change"
            : "no framework markers detected",
        workspaceRoot);
    return BuildCommand.builder()
        .command("true")
        .timeoutSeconds(5)
        .sourceDescription("fallback:no-op")
        .build();
  }

  private BuildCommand commandForRoots(Path workspaceRoot, java.util.List<Path> roots) {
    java.util.List<String> commands = new java.util.ArrayList<>();
    int timeout = 0;
    String firstSource = null;
    for (Path root : roots) {
      Optional<BuildCommand> command = inferFromFramework(root);
      if (command.isEmpty()) continue;
      String relative = workspaceRoot.relativize(root).toString().replace('\\', '/');
      commands.add("(cd " + relative + " && " + command.get().command() + ")");
      timeout += command.get().timeoutSeconds();
      if (firstSource == null) firstSource = command.get().sourceDescription() + "@" + relative;
    }
    if (commands.isEmpty()) {
      return build("true", 5, "fallback:no-op");
    }
    return BuildCommand.builder()
        .command(String.join(" && ", commands))
        .timeoutSeconds(Math.min(timeout, 1200))
        .sourceDescription(commands.size() == 1 ? firstSource : "inferred:changed-multi-root")
        .validationRoots(roots)
        .build();
  }

  private Optional<BuildCommand> inferFromFramework(Path workspaceRoot) {
    // Order matters: more-specific markers checked first.
    if (exists(workspaceRoot, ".executespec/bootstrap/node-js.marker")) {
      return Optional.of(
          build(
              "node --check " + nodeJavascriptEntry(workspaceRoot),
              120,
              "executespec-bootstrap-node-check"));
    }
    if (exists(workspaceRoot, "pom.xml")) {
      String wrapper = executable(workspaceRoot, "mvnw") ? "./mvnw" : "mvn";
      return Optional.of(build(wrapper + " -q -DskipTests compile", 300, "inferred:maven"));
    }
    if (exists(workspaceRoot, "build.gradle") || exists(workspaceRoot, "build.gradle.kts")) {
      String wrapper = exists(workspaceRoot, "gradlew") ? "./gradlew" : "gradle";
      return Optional.of(build(wrapper + " -q assemble -x test", 300, "inferred:gradle"));
    }
    if (exists(workspaceRoot, "Cargo.toml")) {
      return Optional.of(build("cargo build --quiet", 240, "inferred:cargo"));
    }
    if (exists(workspaceRoot, "go.mod")) {
      return Optional.of(build("go build ./...", 180, "inferred:go"));
    }
    // React Native (Expo, managed workflow) — app.json + package.json at root
    // is the high-signal marker. Check BEFORE the generic Node branch so RN
    // projects don't silently fall through to `npm run build` (which either
    // does nothing or invokes the wrong tooling). Smoke = Metro bundle +
    // typecheck; platform=android is enough (the bundler catches iOS-side
    // errors too — the JS is shared and the native module surface is the
    // same for v1 managed Expo).
    if (exists(workspaceRoot, "app.json") && exists(workspaceRoot, "package.json")) {
      return Optional.of(
          build(
              "npx expo export --platform android --output-dir dist && npx tsc --noEmit",
              300,
              "inferred:expo-rn"));
    }
    if (exists(workspaceRoot, "package.json")) {
      // An optional missing script exits zero without checking source. Absence is unavailable
      // verification, not a successful build. Qualified dependency-free Node uses its bootstrap.
      try {
        String script =
            new ObjectMapper()
                .readTree(workspaceRoot.resolve("package.json").toFile())
                .path("scripts")
                .path("build")
                .asText("");
        if (!script.isBlank()) {
          return Optional.of(build("npm run build", 240, "inferred:npm"));
        }
      } catch (IOException invalidManifest) {
        log.warn(
            "Cannot resolve npm build from package.json in {}", workspaceRoot, invalidManifest);
      }
      return Optional.empty();
    }
    if (exists(workspaceRoot, "pyproject.toml")
        || exists(workspaceRoot, "setup.py")
        || exists(workspaceRoot, "requirements.txt")
        || exists(workspaceRoot, ".executespec/bootstrap/python.marker")) {
      // Python has no "compile" — use a syntax check across the project as a smoke proxy.
      // Always use python3: python2 is deprecated, many systems (incl. modern macOS)
      // ship only python3, bare `python` often exits 127 with "command not found".
      return Optional.of(build("python3 -m compileall -q .", 120, "inferred:python"));
    }
    // Last-resort: loose source files with no project manifest.
    if (hasAnyTopLevelJavaFile(workspaceRoot)) {
      return Optional.of(build("javac *.java", 120, "inferred:java-loose"));
    }
    if (hasAnyPythonFile(workspaceRoot)) {
      return Optional.of(build("python3 -m compileall -q .", 60, "inferred:python-loose"));
    }
    return Optional.empty();
  }

  private static String nodeJavascriptEntry(Path workspaceRoot) {
    try {
      String entry =
          Files.readString(workspaceRoot.resolve(".executespec/bootstrap/node-js.marker")).trim();
      if (entry.matches("[A-Za-z0-9._@+-]+(?:/[A-Za-z0-9._@+-]+)*\\.(?:js|mjs)")) return entry;
    } catch (IOException ignored) {
      // The immutable bootstrap digest reports the exact corruption. Keep this resolver fail-red.
    }
    return ".executespec/bootstrap/invalid-node-entry.js";
  }

  private static boolean executable(Path dir, String name) {
    try {
      Path candidate = dir.resolve(name);
      return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  /**
   * Rewrites bare {@code python} invocations to {@code python3} in planner-declared build commands.
   * Safe: only replaces {@code python} when followed by a space or end-of-string (never touches
   * {@code python3}, {@code python3.11}, or paths like {@code /usr/bin/python}) and only at a word
   * boundary.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "python fib.py"} → {@code "python3 fib.py"}
   *   <li>{@code "python -m pytest && echo ok"} → {@code "python3 -m pytest && echo ok"}
   *   <li>{@code "python3 fib.py"} → {@code "python3 fib.py"} (unchanged)
   *   <li>{@code "/usr/bin/python foo"} → {@code "/usr/bin/python foo"} (unchanged — explicit path)
   * </ul>
   */
  static String normalizePython(String command) {
    if (command == null || command.isBlank()) return command;
    // (?<![./\w]) = not preceded by a path char or word char (prevents matching inside
    // /usr/bin/python)
    // python = literal
    // (?!\d|_) = not followed by a digit (prevents touching python3, python2.7, python_utils)
    // (?=\s|$) = followed by whitespace or end (only matches whole-word python before args)
    String normalized = command.replaceAll("(?<![./\\w])python(?!\\d|_)(?=\\s|$)", "python3");
    // Planner-authored Python build commands also frequently use the bare `pip` executable.
    // Modern macOS and several minimal Linux images expose pip only through the interpreter,
    // even when `python3 -m pip` is available. Treat this exactly like bare `python`: normalize
    // only the standalone executable, never pip3, a path, or a word containing "pip".
    return normalized.replaceAll("(?<!-m )(?<![./\\w])pip(?!\\d|_)(?=\\s|$)", "python3 -m pip");
  }

  static boolean isRepeatableBuildSmoke(String command) {
    if (command == null || command.isBlank()) return false;
    String normalized = command.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    // Dependency-free Java submissions often have no Maven/Gradle marker and verify themselves
    // with one compile followed by one no-argument test main. Accept only that closed grammar:
    // arbitrary shell chains, compiler flags, runtime arguments and traversal paths remain
    // rejected here, while BuildProjectTool still applies ShellSandboxPolicy before execution.
    if (isDependencyFreeJavaSmoke(command)) return true;
    if (normalized.contains("&&")
        || normalized.contains(";")
        || normalized.contains("|")
        || normalized.startsWith("cd ")
        || normalized.contains(" pytest")
        || normalized.contains("vitest")
        || normalized.contains("playwright")) return false;
    // Python setup (including normalizePython's rewrite of bare pip).
    if (normalized.matches("^(?:python(?:3(?:\\.\\d+)?)? -m )?pip(?:3)? install(?: |$).*")
        || normalized.matches("^uv (?:pip install|sync|venv)(?: |$).*$")) return false;
    // JavaScript dependency installation/package mutation.
    if (normalized.matches("^(?:npm|pnpm|yarn|bun) (?:install|i|ci|add)(?: |$).*$")) return false;
    // Long-running application/dev-server commands are runtime checks, not bounded build smokes.
    return !normalized.matches(
        "^(?:uvicorn|gunicorn|flask run|npm start|npm run dev|pnpm dev|yarn dev|bun run dev|java"
            + " -jar)(?: |$).*$");
  }

  static boolean isDependencyFreeJavaSmoke(String command) {
    if (command == null || command.isBlank()) return false;
    return DEPENDENCY_FREE_JAVA_SMOKE.matcher(command.trim().replaceAll("\\s+", " ")).matches();
  }

  private static boolean exists(Path root, String relative) {
    return Files.exists(root.resolve(relative));
  }

  private static boolean hasAnyPythonFile(Path root) {
    if (!Files.isDirectory(root)) return false;
    try (var stream = Files.walk(root, 3)) {
      return stream.anyMatch(p -> p.toString().endsWith(".py") && Files.isRegularFile(p));
    } catch (java.io.IOException e) {
      return false;
    }
  }

  private static boolean hasAnyTopLevelJavaFile(Path root) {
    if (!Files.isDirectory(root)) return false;
    try (var stream = Files.list(root)) {
      return stream.anyMatch(
          path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java"));
    } catch (java.io.IOException e) {
      return false;
    }
  }

  private static BuildCommand build(String command, int timeout, String source) {
    return BuildCommand.builder()
        .command(command)
        .timeoutSeconds(timeout)
        .sourceDescription(source)
        .build();
  }
}
