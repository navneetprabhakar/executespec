package com.navneet.executespec.testgen;

import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.model.TestTaskStatus;
import com.navneet.executespec.testgen.repository.TestTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Compiles the generated test project to verify tests are valid Java.
 * Uses mvn compile on the workspace-test/{runId}/ project.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TestCompilationGate {

    private static final long COMPILE_TIMEOUT_SECONDS = 120;
    private static final Pattern ERROR_FILE_PATTERN =
            Pattern.compile("\\[ERROR\\].*?/([A-Z][A-Za-z]+Test)\\.java");

    private final TestTaskRepository testTaskRepository;

    /**
     * Compile all test files in workspace-test/{runId}/.
     *
     * @return true if compilation succeeds, false otherwise
     */
    public CompilationResult compileTests(String runId) {
        Path testProjectDir = Path.of("workspace-test", runId);
        Path pomFile = testProjectDir.resolve("pom.xml");

        if (!pomFile.toFile().exists()) {
            log.error("[TestCompile] pom.xml not found at {}", pomFile);
            return new CompilationResult(false, "pom.xml not found", List.of());
        }

        try {
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome == null || javaHome.isBlank()) {
                javaHome = "/opt/homebrew/opt/openjdk@21";
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "test-compile", "-f", pomFile.toString(), "-q", "-T", "1C"
            );
            pb.environment().put("JAVA_HOME", javaHome);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CompilationResult(false, "Compilation timed out after " + COMPILE_TIMEOUT_SECONDS + "s", List.of());
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("[TestCompile] Tests compiled successfully for run {}", runId);
                return new CompilationResult(true, null, List.of());
            }

            // Parse compilation errors and map to test classes
            List<String> failedClasses = ERROR_FILE_PATTERN.matcher(output).results()
                    .map(m -> m.group(1))
                    .distinct()
                    .toList();

            log.warn("[TestCompile] Compilation failed for run {} — {} classes with errors: {}",
                    runId, failedClasses.size(), failedClasses);

            return new CompilationResult(false, output, failedClasses);

        } catch (Exception e) {
            log.error("[TestCompile] Failed to run compilation for run {}: {}", runId, e.getMessage(), e);
            return new CompilationResult(false, e.getMessage(), List.of());
        }
    }

    /**
     * Mark tasks that had compilation errors as FAILED with error details.
     */
    public void markFailedTasks(Long testPlanId, List<String> failedClassNames, String fullOutput) {
        List<TestTask> tasks = testTaskRepository.findByTestPlanId(testPlanId);
        for (TestTask task : tasks) {
            if (failedClassNames.contains(task.getTaskName())) {
                task.setStatus(TestTaskStatus.FAILED);
                task.setCompilationError(truncate(fullOutput, 2000));
                testTaskRepository.save(task);
            } else if (task.getStatus() == TestTaskStatus.GENERATING) {
                // Mark successfully compiled tasks
                task.setStatus(TestTaskStatus.COMPILED);
                testTaskRepository.save(task);
            }
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "... (truncated)";
    }

    public record CompilationResult(boolean success, String output, List<String> failedClasses) {}
}
