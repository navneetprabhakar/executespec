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
}
