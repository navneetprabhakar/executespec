package com.navneet.executespec.testgen;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.model.TestTaskStatus;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Compiles the generated test project to verify tests are valid Java. The project lives inside the
 * run-owned {@code .executespec/generated-tests/} sandbox mount.
 *
 * <p><strong>Audit 2026-05-12 review item J #59:</strong> strict routing compiles inside the run
 * sandbox against the generated project under {@code /workspace/.executespec/generated-tests}.
 * Runtime unavailability fails closed. The legacy default-off path retains the bounded,
 * environment-minimized host invocation for rollback compatibility.
 */
@Component
@Slf4j
public class TestCompilationGate {

  private static final Duration COMPILE_TIMEOUT = Duration.ofSeconds(120);
  private static final Pattern ERROR_FILE_PATTERN =
      Pattern.compile("\\[ERROR\\].*?/([A-Z][A-Za-z]+Test)\\.java");

  private final TestTaskRepository testTaskRepository;
  private final SandboxCommandRoutingService sandboxRouting;
  private final TestWorkspaceResolver workspaceResolver;

  TestCompilationGate(TestTaskRepository testTaskRepository) {
    this(testTaskRepository, null, null);
  }

  @Autowired
  public TestCompilationGate(
      TestTaskRepository testTaskRepository,
      SandboxCommandRoutingService sandboxRouting,
      TestWorkspaceResolver workspaceResolver) {
    this.testTaskRepository = testTaskRepository;
    this.sandboxRouting = sandboxRouting;
    this.workspaceResolver = workspaceResolver;
  }

  /**
   * Compile all test files in the run-owned generated-test project.
   *
   * @return true if compilation succeeds, false otherwise
   */
  public CompilationResult compileTests(String runId) {
    Path testProjectDir =
        workspaceResolver == null
            ? Path.of("workspace-test", runId)
            : workspaceResolver.projectRoot(runId);
    Path pomFile = testProjectDir.resolve("pom.xml");

    if (sandboxRouting != null && sandboxRouting.routingEnabled()) {
      SandboxExecResult result =
          sandboxRouting.execute(
              runId,
              testProjectDir,
              List.of(
                  "mvn",
                  "test-compile",
                  "-f",
                  TestWorkspaceResolver.CONTAINER_PROJECT_ROOT + "/pom.xml",
                  "-q",
                  "-T",
                  "1C"),
              COMPILE_TIMEOUT);
      if (!result.completed()) {
        return new CompilationResult(
            false, "generated-test compilation unavailable: " + result.detail(), List.of());
      }
      return compilationResult(
          runId, result.exitCode(), SandboxCommandRoutingService.combinedOutput(result));
    }
    if (!pomFile.toFile().exists()) {
      log.error("[TestCompile] pom.xml not found at {}", pomFile);
      return new CompilationResult(false, "pom.xml not found", List.of());
    }
    try {
      String javaHome = System.getenv("JAVA_HOME");
      if (javaHome == null || javaHome.isBlank()) {
        javaHome = "/opt/homebrew/opt/openjdk@21";
      }

      ProcessBuilder pb =
          new ProcessBuilder("mvn", "test-compile", "-f", pomFile.toString(), "-q", "-T", "1C");
      // Audit 2026-05-12 review item J #59 — strip inherited env. Don't leak JWT_SECRET,
      // OPENAI_API_KEY, etc. into a Maven invocation against LLM-generated pom + sources.
      java.util.Map<String, String> env = pb.environment();
      String path = env.get("PATH");
      String home = env.get("HOME");
      String user = env.get("USER");
      String mavenHome = env.get("MAVEN_HOME");
      env.clear();
      if (path != null) env.put("PATH", path);
      if (home != null) env.put("HOME", home);
      if (user != null) env.put("USER", user);
      if (mavenHome != null) env.put("MAVEN_HOME", mavenHome);
      env.put("JAVA_HOME", javaHome);
      pb.redirectErrorStream(true);

      BoundedProcess.Outcome outcome = BoundedProcess.run(pb, COMPILE_TIMEOUT);
      if (!outcome.finished()) {
        return new CompilationResult(
            false, "Compilation timed out after " + COMPILE_TIMEOUT.toSeconds() + "s", List.of());
      }

      return compilationResult(runId, outcome.exitCode(), outcome.output());

    } catch (Exception e) {
      log.error("[TestCompile] Failed to run compilation for run {}: {}", runId, e.getMessage(), e);
      return new CompilationResult(false, e.getMessage(), List.of());
    }
  }

  private CompilationResult compilationResult(String runId, int exitCode, String output) {
    if (exitCode == 0) {
      log.info("[TestCompile] Tests compiled successfully for run {}", runId);
      return new CompilationResult(true, null, List.of());
    }
    List<String> failedClasses =
        ERROR_FILE_PATTERN.matcher(output).results().map(m -> m.group(1)).distinct().toList();
    log.warn(
        "[TestCompile] Compilation failed for run {} — {} classes with errors: {}",
        runId,
        failedClasses.size(),
        failedClasses);
    return new CompilationResult(false, output, failedClasses);
  }

  /**
   * Record the outcome of a FAILED compile against the plan's tasks (RR-1584).
   *
   * <p>This method never produces a COMPILED task. {@code failedClassNames} is derived from a
   * filename regex over compiler output and is empty for whole-project failures — dependency
   * resolution, a broken POM, an error in a class the regex does not match, a timeout, an absent
   * {@code mvn}. The previous implementation treated every unlisted task as compiled, so an
   * unattributable failure was reported to the user as "N tests compiled". A compile that did not
   * succeed can only mark tasks FAILED; when nothing can be attributed, every task still in flight
   * is failed with the raw output so the retry pass has something to work from.
   */
  public void markFailedTasks(Long testPlanId, List<String> failedClassNames, String fullOutput) {
    List<TestTask> tasks = testTaskRepository.findByTestPlanId(testPlanId);
    boolean unattributable = failedClassNames == null || failedClassNames.isEmpty();
    for (TestTask task : tasks) {
      boolean namedFailure = !unattributable && failedClassNames.contains(task.getTaskName());
      boolean inFlight = task.getStatus() == TestTaskStatus.GENERATING;
      if (namedFailure || (unattributable && inFlight)) {
        task.setStatus(TestTaskStatus.FAILED);
        task.setCompilationError(truncate(fullOutput, 2000));
        testTaskRepository.save(task);
      }
    }
  }

  /**
   * Promote every in-flight task to COMPILED. Only legal after a compile that actually exited 0 —
   * this is the single transition into COMPILED (RR-1584).
   */
  public void markCompiled(Long testPlanId) {
    for (TestTask task : testTaskRepository.findByTestPlanId(testPlanId)) {
      if (task.getStatus() == TestTaskStatus.GENERATING) {
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
