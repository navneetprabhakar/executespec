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
            return BuildCommand.builder()
                    .command(plannerDeclaredCommand.trim())
                    .timeoutSeconds(180)
                    .sourceDescription("planner-declared")
                    .build();
        }
        return inferFromFramework(workspaceRoot)
                .orElseGet(() -> {
                    log.warn("BuildCommandResolver: no framework markers detected in {}, " +
                            "falling back to no-op 'true' (smoke test will always pass)", workspaceRoot);
                    return BuildCommand.builder()
                            .command("true")
                            .timeoutSeconds(5)
                            .sourceDescription("fallback:no-op")
                            .build();
                });
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
        if (exists(workspaceRoot, "package.json")) {
            // Prefer "build" script if defined; otherwise tsc as a TypeScript project sanity check.
            return Optional.of(build("npm run build --if-present", 240, "inferred:npm"));
        }
        if (exists(workspaceRoot, "pyproject.toml") || exists(workspaceRoot, "setup.py")
                || exists(workspaceRoot, "requirements.txt")) {
            // Python has no "compile" — use a syntax check across the project as a smoke proxy.
            return Optional.of(build("python -m compileall -q .", 120, "inferred:python"));
        }
        return Optional.empty();
    }

    private static boolean exists(Path root, String relative) {
        return Files.exists(root.resolve(relative));
    }

    private static BuildCommand build(String command, int timeout, String source) {
        return BuildCommand.builder()
                .command(command)
                .timeoutSeconds(timeout)
                .sourceDescription(source)
                .build();
    }
}
