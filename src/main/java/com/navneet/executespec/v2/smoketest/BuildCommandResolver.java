package com.navneet.executespec.v2.smoketest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the build command for a given workspace.
 *
 * <p>Resolution order (per V2 plan P1.4):
 * <ol>
 *   <li><b>Planner-declared</b> — explicit {@code buildCommand} field on the plan JSON
 *       (passed in via {@link #resolve(Path, String)} second argument)</li>
 *   <li><b>Framework-inferred</b> — sniffs known marker files in the workspace
 *       ({@code package.json}, {@code pom.xml}, {@code build.gradle}, {@code Cargo.toml},
 *       {@code go.mod}, {@code requirements.txt}) and maps to the conventional command</li>
 *   <li><b>Fallback</b> — {@code "true"} (no-op success). Means the smoke test always
 *       passes for unknown project types; the run still completes but the smoke layer
 *       provides no signal. Logged at WARN.</li>
 * </ol>
 *
 * <p>Idempotent and side-effect-free; safe to call repeatedly.
 */
@Component
@Slf4j
public class BuildCommandResolver {

    public BuildCommand resolve(Path workspaceRoot, String plannerDeclaredCommand) {
        if (plannerDeclaredCommand != null && !plannerDeclaredCommand.isBlank()) {
            String cmd = normalizePython(plannerDeclaredCommand.trim());
            // Planner commands often assume the workspace IS the project (single-repo
            // runs). For multi-repo Launches the real project sits under repos/<name>/ —
            // if the workspace root has no recognisable marker but repos/ does, wrap
            // the planner command with `cd` so it executes in the right directory.
            String wrapped = wrapForReposSubdirIfNeeded(workspaceRoot, cmd);
            return BuildCommand.builder()
                    .command(wrapped)
                    .timeoutSeconds(180)
                    .sourceDescription("planner-declared")
                    .build();
        }
        // 1) Root markers (single-repo + V1-style runs)
        Optional<BuildCommand> root = inferFromFramework(workspaceRoot);
        if (root.isPresent()) return root.get();

        // 2) Multi-repo convention: workspace/<runId>/repos/<name>/. Pick the first
        //    subdir that has recognisable markers. If multiple repos build under
        //    different stacks, chain them with &&.
        Path reposDir = workspaceRoot.resolve("repos");
        if (Files.isDirectory(reposDir)) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            String source = null;
            try (var stream = Files.list(reposDir)) {
                for (Path repo : stream.filter(Files::isDirectory).toList()) {
                    Optional<BuildCommand> cmd = inferFromFramework(repo);
                    if (cmd.isEmpty()) continue;
                    // cd into the repo and run its native build.
                    parts.add("(cd " + repo.getFileName() + " && " + cmd.get().command() + ")");
                    if (source == null) source = cmd.get().sourceDescription() + "@repos/" + repo.getFileName();
                }
            } catch (java.io.IOException e) {
                log.warn("BuildCommandResolver: repos/ scan failed in {}: {}", workspaceRoot, e.getMessage());
            }
            if (!parts.isEmpty()) {
                // The combined command runs from reposDir so the `cd <name>` segments work.
                String combined = "cd repos && " + String.join(" && ", parts);
                return BuildCommand.builder()
                        .command(combined)
                        .timeoutSeconds(300)
                        .sourceDescription(source)
                        .build();
            }
        }

        log.warn("BuildCommandResolver: no framework markers detected in {} or any repos/ subdir, " +
                "falling back to no-op 'true' (smoke test will always pass)", workspaceRoot);
        return BuildCommand.builder()
                .command("true")
                .timeoutSeconds(5)
                .sourceDescription("fallback:no-op")
                .build();
    }

    /**
     * Planner build commands (e.g. {@code "mvn -q -DskipTests compile"}) assume the
     * workspace root IS the project. For multi-repo Launches that's false — the
     * project lives at {@code repos/<name>/}. When the workspace root has no
     * markers but a single {@code repos/*} subdir does, wrap the planner command
     * with a {@code cd} so it runs in the right directory.
     *
     * <p>When multiple repos exist, prepend per-repo {@code cd} segments chained
     * with {@code &&} so each runs its build. For zero repos under {@code repos/}
     * the command is returned unchanged.
     */
    private String wrapForReposSubdirIfNeeded(Path workspaceRoot, String plannerCmd) {
        // Workspace root already looks like a project — planner command will work as-is.
        if (hasAnyMarker(workspaceRoot)) return plannerCmd;
        Path reposDir = workspaceRoot.resolve("repos");
        if (!Files.isDirectory(reposDir)) return plannerCmd;
        try (var stream = Files.list(reposDir)) {
            java.util.List<Path> buildableRepos = stream
                    .filter(Files::isDirectory)
                    .filter(this::hasAnyMarker)
                    .toList();
            if (buildableRepos.isEmpty()) return plannerCmd;
            if (buildableRepos.size() == 1) {
                return "cd repos/" + buildableRepos.get(0).getFileName() + " && " + plannerCmd;
            }
            // Multiple repos — run the planner command in each; the harness reports
            // non-zero exit if any fails.
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (Path repo : buildableRepos) {
                parts.add("(cd " + repo.getFileName() + " && " + plannerCmd + ")");
            }
            return "cd repos && " + String.join(" && ", parts);
        } catch (java.io.IOException e) {
            log.warn("wrapForReposSubdirIfNeeded: scan failed in {}: {}", workspaceRoot, e.getMessage());
            return plannerCmd;
        }
    }

    private boolean hasAnyMarker(Path dir) {
        return exists(dir, "pom.xml")
                || exists(dir, "build.gradle") || exists(dir, "build.gradle.kts")
                || exists(dir, "Cargo.toml")
                || exists(dir, "go.mod")
                || exists(dir, "package.json")
                || exists(dir, "pyproject.toml") || exists(dir, "setup.py") || exists(dir, "requirements.txt");
    }

    private Optional<BuildCommand> inferFromFramework(Path workspaceRoot) {
        // Order matters: more-specific markers checked first.
        if (exists(workspaceRoot, "pom.xml")) {
            return Optional.of(build("mvn -q -DskipTests compile", 300, "inferred:maven"));
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
            return Optional.of(build(
                    "npx expo export --platform android --output-dir dist && npx tsc --noEmit",
                    300, "inferred:expo-rn"));
        }
        if (exists(workspaceRoot, "package.json")) {
            // Prefer "build" script if defined; otherwise tsc as a TypeScript project sanity check.
            return Optional.of(build("npm run build --if-present", 240, "inferred:npm"));
        }
        if (exists(workspaceRoot, "pyproject.toml") || exists(workspaceRoot, "setup.py")
                || exists(workspaceRoot, "requirements.txt")) {
            // Python has no "compile" — use a syntax check across the project as a smoke proxy.
            // Always use python3: python2 is deprecated, many systems (incl. modern macOS)
            // ship only python3, bare `python` often exits 127 with "command not found".
            return Optional.of(build("python3 -m compileall -q .", 120, "inferred:python"));
        }
        // Last-resort: loose .py files with no project manifest (e.g. single-shot "write fib.py").
        if (hasAnyPythonFile(workspaceRoot)) {
            return Optional.of(build("python3 -m compileall -q .", 60, "inferred:python-loose"));
        }
        return Optional.empty();
    }

    /**
     * Rewrites bare {@code python} invocations to {@code python3} in planner-declared
     * build commands. Safe: only replaces {@code python} when followed by a space or
     * end-of-string (never touches {@code python3}, {@code python3.11}, or paths like
     * {@code /usr/bin/python}) and only at a word boundary.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "python fib.py"} → {@code "python3 fib.py"}</li>
     *   <li>{@code "python -m pytest && echo ok"} → {@code "python3 -m pytest && echo ok"}</li>
     *   <li>{@code "python3 fib.py"} → {@code "python3 fib.py"} (unchanged)</li>
     *   <li>{@code "/usr/bin/python foo"} → {@code "/usr/bin/python foo"} (unchanged — explicit path)</li>
     * </ul>
     */
    static String normalizePython(String command) {
        if (command == null || command.isBlank()) return command;
        // (?<![./\w]) = not preceded by a path char or word char (prevents matching inside /usr/bin/python)
        // python = literal
        // (?!\d|_) = not followed by a digit (prevents touching python3, python2.7, python_utils)
        // (?=\s|$) = followed by whitespace or end (only matches whole-word python before args)
        return command.replaceAll("(?<![./\\w])python(?!\\d|_)(?=\\s|$)", "python3");
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

    private static BuildCommand build(String command, int timeout, String source) {
        return BuildCommand.builder()
                .command(command)
                .timeoutSeconds(timeout)
                .sourceDescription(source)
                .build();
    }
}
