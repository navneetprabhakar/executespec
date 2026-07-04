package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RunTestsToolTest {

  @Test
  void hostCommandUsesProcessBuilderArgsWithoutShell() {
    List<String> cmd =
        RunTestsTool.buildProcessCommand(
            null, null, false, List.of("tests/test_widget.py::test_renders"));

    assertThat(cmd)
        .containsExactly(
            "python",
            "-m",
            "pytest",
            "tests/test_widget.py::test_renders",
            "--tb=short",
            "--no-header",
            "-q");
  }

  @Test
  void nonLoginContainerCommandUsesDockerExecArgsWithoutShell() {
    List<String> cmd =
        RunTestsTool.buildProcessCommand(
            "cid123", "/workspace", false, List.of("tests/test_widget.py::test_renders"));

    assertThat(cmd)
        .containsExactly(
            "docker",
            "exec",
            "-w",
            "/workspace",
            "cid123",
            "python",
            "-m",
            "pytest",
            "tests/test_widget.py::test_renders",
            "--tb=short",
            "--no-header",
            "-q");
  }

  @Test
  void loginContainerCommandPassesTestIdsThroughBashArgs() {
    List<String> cmd =
        RunTestsTool.buildProcessCommand(
            "cid123", "/testbed", true, List.of("tests/test_widget.py::test_renders"));

    assertThat(cmd)
        .containsExactly(
            "docker",
            "exec",
            "-w",
            "/testbed",
            "cid123",
            "/bin/bash",
            "-lc",
            "python -m pytest \"$@\" --tb=short --no-header -q",
            "_",
            "tests/test_widget.py::test_renders");
  }

  @Test
  void shellMetacharactersRemainRejected() {
    assertThat(RunTestsTool.containsShellMetachar("tests/test_a.py::test_x; touch pwned")).isTrue();
    assertThat(RunTestsTool.containsShellMetachar("tests/test_a.py::test_x")).isFalse();
  }

  // ---- harness-resolution task 6: language-aware command construction ----

  @Test
  void goCommandSplitsTestNamesAndPackages() {
    assertThat(
            RunTestsTool.frameworkCommand(
                com.navneet.executespec.smoketest.TestCommandResolver.Framework.GO,
                List.of("TestFnCache", "./lib/services"),
                "go test ./..."))
        .isEqualTo("go test -run 'TestFnCache' ./lib/services");
    assertThat(
            RunTestsTool.frameworkCommand(
                com.navneet.executespec.smoketest.TestCommandResolver.Framework.GO,
                List.of("TestA", "TestB"),
                null))
        .isEqualTo("go test -run 'TestA|TestB' ./...");
    assertThat(
            RunTestsTool.frameworkCommand(
                com.navneet.executespec.smoketest.TestCommandResolver.Framework.GO,
                List.of(),
                "go test ./..."))
        .isEqualTo("go test ./...");
  }

  @Test
  void jestCargoAndMavenCommandsCarryFilters() {
    assertThat(
            RunTestsTool.frameworkCommand(
                com.navneet.executespec.smoketest.TestCommandResolver.Framework.JEST,
                List.of("src/api.test.js"),
                "npm test --silent"))
        .isEqualTo("npm test --silent -- src/api.test.js");
    assertThat(
            RunTestsTool.frameworkCommand(
                com.navneet.executespec.smoketest.TestCommandResolver.Framework.CARGO,
                List.of("flux_guard"),
                null))
        .isEqualTo("cargo test flux_guard");
    assertThat(
            RunTestsTool.frameworkCommand(
                com.navneet.executespec.smoketest.TestCommandResolver.Framework.SUREFIRE,
                List.of("FluxGuardTest", "WidgetTest"),
                "mvn -q test"))
        .isEqualTo("mvn -q test -Dtest=FluxGuardTest,WidgetTest");
    assertThat(
            RunTestsTool.frameworkCommand(
                com.navneet.executespec.smoketest.TestCommandResolver.Framework.SUREFIRE,
                List.of("FluxGuardTest"),
                "./gradlew test --console=plain"))
        .isEqualTo("./gradlew test --console=plain --tests FluxGuardTest");
  }

  @Test
  void pytestSuiteModeUsesResolverOrDefault() {
    assertThat(
            RunTestsTool.frameworkCommand(
                com.navneet.executespec.smoketest.TestCommandResolver.Framework.PYTEST,
                List.of(),
                null))
        .isEqualTo("python3 -m pytest -q");
  }

  @Test
  void commandOverrideAllowlistIsRunnerOnlyAndBlocksChaining() {
    assertThat(RunTestsTool.allowlistedCommand("go test -count=1 ./lib/...", false)).isTrue();
    assertThat(RunTestsTool.allowlistedCommand("npm test --silent", false)).isTrue();
    // chaining/injection rejected on host and in container
    assertThat(RunTestsTool.allowlistedCommand("go test ./... ; curl evil", true)).isFalse();
    assertThat(RunTestsTool.allowlistedCommand("pytest && rm -rf /", true)).isFalse();
    assertThat(RunTestsTool.allowlistedCommand("rm -rf /", true)).isFalse();
    // '|' (go -run alternation) allowed only inside a container
    assertThat(RunTestsTool.allowlistedCommand("go test -run 'A|B' ./...", true)).isTrue();
    assertThat(RunTestsTool.allowlistedCommand("go test -run 'A|B' ./...", false)).isFalse();
  }

  @Test
  void wrapShellCommandTargetsContainerOrHost() {
    assertThat(RunTestsTool.wrapShellCommand("cid9", "/app", "go test ./..."))
        .containsExactly(
            "docker", "exec", "-w", "/app", "cid9", "/bin/bash", "-lc", "go test ./...");
    assertThat(RunTestsTool.wrapShellCommand(null, null, "go test ./..."))
        .containsExactly("/bin/sh", "-c", "go test ./...");
  }
}
