package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Enforces the Phase 5 rule that there is exactly one way to execute a sandbox.
 *
 * <p>The plan states: "Add one {@code RunSandboxRuntime} interface and one dispatcher. Architecture
 * tests must prohibit bypassing it from controllers/agents." A bypass is not a style problem — a
 * controller that shells out directly inherits none of the isolation profile, resource limits,
 * fenced ownership or teardown guarantees the dispatcher applies, and no lifecycle row records that
 * it ran.
 *
 * <p>Measured from source text rather than bytecode, matching the existing convention in this
 * package ({@code ProviderDispatchGuardCoverageTest}, {@code AccessGuardCoverageTest}).
 */
class SandboxRuntimeDispatchBoundaryTest {

  private static final Path JAVA_SOURCE_ROOT = Path.of("src/main/java");
  private static final Path SANDBOX_PACKAGE =
      Path.of("src/main/java/com/navneet/executespec/sandbox");

  /** Ways to start a process or a container without going through the dispatcher. */
  private static final List<String> DIRECT_EXECUTION_TOKENS =
      List.of("new ProcessBuilder", "Runtime.getRuntime().exec", "ProcessHandle.current().destroy");

  /**
   * Controllers and agents that already executed processes directly when this boundary was
   * installed on 2026-07-20. None of them is a sandbox: this allowlist is the honest starting
   * point, is expected to shrink, and must never grow to admit sandbox execution.
   */
  private static final Set<String> PRE_EXISTING_DIRECT_EXECUTORS =
      Set.of(
          // Reads local git metadata for the run ledger digest; no container, no sandbox.
          "com/navneet/executespec/memory/RunLedgerAgent.java",
          // Operator-only SUPER_ADMIN smoke controllers, local/dev profiles.
          "com/navneet/executespec/test/ReviewerTestController.java",
          "com/navneet/executespec/test/ToolRegistryTestController.java");

  @Test
  void noControllerOrAgentExecutesProcessesOrContainersDirectly() throws IOException {
    List<String> bypasses = new ArrayList<>();

    for (Path file : javaSources()) {
      String relative = relative(file);
      boolean controllerOrAgent =
          relative.endsWith("Controller.java") || relative.endsWith("Agent.java");
      if (!controllerOrAgent || PRE_EXISTING_DIRECT_EXECUTORS.contains(relative)) {
        continue;
      }
      String source = Files.readString(file);
      for (String token : DIRECT_EXECUTION_TOKENS) {
        if (source.contains(token)) {
          bypasses.add(relative + " uses " + token);
        }
      }
    }

    assertThat(bypasses)
        .as(
            """
            A controller or agent executes a process or container directly.

            Sandbox execution has exactly one entry point: RunSandboxRuntimeDispatcher, which
            applies the isolation profile, resource limits, fenced ownership and teardown, and
            records a run_sandbox lifecycle row. Code that bypasses it inherits none of that and
            leaves no evidence that it ran.

            Route the work through the dispatcher. Do not add an allowlist entry for a sandbox.
            """)
        .isEmpty();
  }

  @Test
  void allowlistedDirectExecutorsStillExistAndStillExecuteDirectly() throws IOException {
    List<String> stale = new ArrayList<>();
    for (String relative : PRE_EXISTING_DIRECT_EXECUTORS) {
      Path file = JAVA_SOURCE_ROOT.resolve(relative);
      if (!Files.exists(file)) {
        stale.add(relative + " no longer exists");
        continue;
      }
      String source = Files.readString(file);
      if (DIRECT_EXECUTION_TOKENS.stream().noneMatch(source::contains)) {
        stale.add(relative + " no longer executes directly");
      }
    }
    assertThat(stale)
        .as("A stale allowlist entry is standing permission to bypass the dispatcher. Remove it.")
        .isEmpty();
  }

  @Test
  void containerExecutionForSandboxesLivesOnlyInTheSandboxRuntime() throws IOException {
    List<String> offenders = new ArrayList<>();

    for (Path file : javaSources()) {
      if (file.startsWith(SANDBOX_PACKAGE)) {
        continue;
      }
      String source = Files.readString(file);
      if (source.contains("RunSandboxRuntime") && !source.contains("RunSandboxRuntimeDispatcher")) {
        offenders.add(relative(file) + " references a sandbox runtime without the dispatcher");
      }
    }

    assertThat(offenders)
        .as(
            "Outside the sandbox package, the dispatcher is the only permitted sandbox entry"
                + " point.")
        .isEmpty();
  }

  @Test
  void exactlyOneRuntimeInterfaceAndOneDispatcherExist() throws IOException {
    assertThat(SANDBOX_PACKAGE.resolve("RunSandboxRuntime.java")).exists();
    assertThat(SANDBOX_PACKAGE.resolve("RunSandboxRuntimeDispatcher.java")).exists();

    List<String> dispatchers =
        javaSources().stream()
            .map(SandboxRuntimeDispatchBoundaryTest::relative)
            .filter(name -> name.endsWith("SandboxRuntimeDispatcher.java"))
            .toList();
    assertThat(dispatchers).as("there must be exactly one sandbox dispatcher").hasSize(1);
  }

  @Test
  void onlyTheDispatcherWritesSandboxLifecycleTransitions() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (Path file : javaSources()) {
      if (file.startsWith(SANDBOX_PACKAGE)) {
        continue;
      }
      String source = Files.readString(file);
      if (source.contains("RunSandboxStore") && !relative(file).endsWith("Service.java")) {
        offenders.add(relative(file) + " writes sandbox lifecycle state outside the domain");
      }
    }
    assertThat(offenders)
        .as("Lifecycle transitions belong to the sandbox domain, not to arbitrary callers.")
        .isEmpty();
  }

  @Test
  void theStrictRuntimeNeverMountsTheDockerSocketOrHostCredentials() throws IOException {
    // Comments are stripped first: the property under test is what reaches the argument vector,
    // not what the documentation happens to name. Asserting over prose would fail the moment the
    // class explains which flags it refuses to pass.
    String code =
        stripComments(Files.readString(SANDBOX_PACKAGE.resolve("SandboxContainerCommand.java")));

    // A sandbox that can reach the container-engine socket is not a sandbox: it can start an
    // unconstrained sibling container. Asserted on the command builder, which is the only place an
    // argument list is constructed.
    assertThat(code).doesNotContain("/var/run/docker.sock");
    assertThat(code).doesNotContain("--privileged");
    assertThat(code).doesNotContain("--cap-add");
    assertThat(code).doesNotContain("--pid=host");
    assertThat(code).doesNotContain("--network=host");
    assertThat(code).doesNotContain("--network host");

    // The flags that must be present are asserted positively, so removing one fails the build
    // rather than silently weakening the profile.
    assertThat(code).contains("--network", "none");
    assertThat(code).contains("--cap-drop", "ALL");
    assertThat(code).contains("--read-only");
    assertThat(code).contains("no-new-privileges");
    assertThat(code).contains("--pids-limit");
    assertThat(code).contains("--memory-swap");
  }

  /** Removes block and line comments so assertions read code, not documentation. */
  private static String stripComments(String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
  }

  private static List<Path> javaSources() throws IOException {
    try (var paths = Files.walk(JAVA_SOURCE_ROOT)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .toList();
    }
  }

  private static String relative(Path file) {
    return JAVA_SOURCE_ROOT.relativize(file).toString().replace('\\', '/');
  }
}
