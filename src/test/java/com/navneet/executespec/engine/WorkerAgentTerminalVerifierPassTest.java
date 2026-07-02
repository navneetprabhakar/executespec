package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerAgentTerminalVerifierPassTest {

  @Test
  void terminalVerifierCommandAcceptsVisibleTestAndBenchmarkCommands() {
    assertThat(WorkerAgent.terminalVerifierCommand("shell", "bash /tests/test.sh")).isTrue();
    assertThat(WorkerAgent.terminalVerifierCommand("shell", "python -m pytest /tests -q")).isTrue();
    assertThat(WorkerAgent.terminalVerifierCommand("shell", "cargo test --release")).isTrue();
    assertThat(WorkerAgent.terminalVerifierCommand("shell", "./benchmark --golden /app/out.bin"))
        .isTrue();
  }

  @Test
  void terminalVerifierCommandAcceptsStructuredBuildAndTestTools() {
    assertThat(WorkerAgent.terminalVerifierCommand("runTests", "")).isTrue();
    assertThat(WorkerAgent.terminalVerifierCommand("buildProject", "")).isTrue();
  }

  @Test
  void terminalVerifierCommandRejectsExplorationAndTrivialSmokeCommands() {
    assertThat(WorkerAgent.terminalVerifierCommand("shell", "ls -la /app")).isFalse();
    assertThat(WorkerAgent.terminalVerifierCommand("shell", "python -c 'import solution'"))
        .isFalse();
    assertThat(WorkerAgent.terminalVerifierCommand("readFile", "/tests/test.sh")).isFalse();
  }

  @Test
  void terminalVisibleTestCommandRequiresTheVisibleTestsPath() {
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "bash /tests/test.sh")).isTrue();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "cd /tests && pytest -q")).isTrue();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "python -m pytest /tests -q"))
        .isTrue();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "python -c 'custom smoke'"))
        .isFalse();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "python -m pytest /app -q"))
        .isFalse();
  }
}
