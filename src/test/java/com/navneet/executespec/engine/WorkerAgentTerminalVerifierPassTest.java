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
  void noOpBuildEvidenceCannotSatisfyBuildVerificationGate() {
    String noOp =
        "{\"ok\":true,\"content\":\"command=true\\nsource=fallback:no-op"
            + "\\nsmokeSignal=UNAVAILABLE\\nnoOp=true\\nexit=0\\noutput:\\n\"}";
    String executed =
        "{\"ok\":true,\"content\":\"command=npm run build\\nsource=inferred:npm"
            + "\\nsmokeSignal=EXECUTED\\nnoOp=false\\nexit=0\\noutput:\\n\"}";
    String legacy =
        "{\"ok\":true,\"content\":\"command=npm run build\\nsource=inferred:npm"
            + "\\nexit=0\\noutput:\\n\"}";

    assertThat(WorkerAgent.isSuccessfulBuildEnvelope(noOp)).isFalse();
    assertThat(WorkerAgent.isSuccessfulBuildEnvelope(executed)).isTrue();
    assertThat(WorkerAgent.isSuccessfulBuildEnvelope(legacy)).isTrue();
  }

  @Test
  void shellExitZeroAfterLongOutputRemainsVerificationEvidence() {
    String envelope =
        "{\"ok\":true,\"content\":\""
            + "x".repeat(1200)
            + " reported exit=1 earlier\\nexit=0\\n\"}";

    assertThat(WorkerAgent.envelopeProvesExitZero("shell", envelope)).isTrue();
    assertThat(WorkerAgent.isFailureEnvelope(envelope)).isFalse();
  }

  @Test
  void terminalVerifierCommandRejectsExplorationAndTrivialSmokeCommands() {
    assertThat(WorkerAgent.terminalVerifierCommand("shell", "ls -la /app")).isFalse();
    assertThat(WorkerAgent.terminalVerifierCommand("shell", "ls -la /tests/")).isFalse();
    assertThat(WorkerAgent.terminalVerifierCommand("shell", "find /tests -maxdepth 2 -type f"))
        .isFalse();
    assertThat(WorkerAgent.terminalVerifierCommand("shell", "cat /tests/test.sh")).isFalse();
    assertThat(WorkerAgent.terminalVerifierCommand("shell", "python -c 'import solution'"))
        .isFalse();
    assertThat(WorkerAgent.terminalVerifierCommand("readFile", "/tests/test.sh")).isFalse();
  }

  @Test
  void terminalVisibleTestCommandRequiresTheVisibleTestsPath() {
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "bash /tests/test.sh")).isTrue();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "cd /tests && pytest -q")).isTrue();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "cd /tests && ./test.sh")).isTrue();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "python -m pytest /tests -q"))
        .isTrue();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "ls -la /tests/")).isFalse();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "find /tests -maxdepth 2 -type f"))
        .isFalse();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "cat /tests/test.sh")).isFalse();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "python -c 'custom smoke'"))
        .isFalse();
    assertThat(WorkerAgent.terminalVisibleTestCommand("shell", "python -m pytest /app -q"))
        .isFalse();
  }
}
