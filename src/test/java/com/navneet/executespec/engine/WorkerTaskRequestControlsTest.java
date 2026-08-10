package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class WorkerTaskRequestControlsTest {

  @Test
  void disabledDefaultsHaveNoBehavioralEffectAndWorkerSuppliesStart() {
    WorkerTaskRequest normalized =
        WorkerTaskRequestControls.withRuntimeDefaults(request(null, null, false), 123L, -1L, -1.0);

    assertThat(normalized.taskTotalTokenCap()).isNull();
    assertThat(normalized.dollarCap()).isNull();
    assertThat(normalized.workerStartedAtEpochMs()).isEqualTo(123L);
  }

  @Test
  void configuredAndExplicitCapsResolveTightenOnly() {
    WorkerTaskRequest normalized =
        WorkerTaskRequestControls.withRuntimeDefaults(
            request(10_000L, 10.0, false), 123L, 1_000L, 2.0);

    assertThat(normalized.taskTotalTokenCap()).isEqualTo(1_000L);
    assertThat(normalized.dollarCap()).isEqualTo(2.0);
  }

  @Test
  void terminalExemptsDefaultsButStillHonorsExplicitCaps() {
    WorkerTaskRequest noExplicit =
        WorkerTaskRequestControls.withRuntimeDefaults(request(null, null, true), 123L, 1_000L, 2.0);
    WorkerTaskRequest explicit =
        WorkerTaskRequestControls.withRuntimeDefaults(request(500L, 1.0, true), 123L, 1_000L, 2.0);

    assertThat(noExplicit.taskTotalTokenCap()).isNull();
    assertThat(noExplicit.dollarCap()).isNull();
    assertThat(explicit.taskTotalTokenCap()).isEqualTo(500L);
    assertThat(explicit.dollarCap()).isEqualTo(1.0);
  }

  @Test
  void retryPreservesCumulativeLimitsStrictRouteAndSharedLoopPolicy() {
    WorkerTaskRequest primary =
        request(1_000L, 2.0, false).toBuilder()
            .changeMode(CodeChangeMode.BUILD)
            .workerStartedAtEpochMs(456L)
            .strictProvider(true)
            .zeroWriteSalvageAllowed(false)
            .verifyBeforeComplete(true)
            .progressV2(true)
            .autoVerify(true)
            .agentRole("ReviewerAgent")
            .stepPrefix("reviewer.general")
            .build();

    WorkerTaskRequest retry =
        WorkerTaskRequestControls.preserveAcrossRetry(primary, request(null, null, false));

    assertThat(retry.taskTotalTokenCap()).isEqualTo(1_000L);
    assertThat(retry.dollarCap()).isEqualTo(2.0);
    assertThat(retry.workerStartedAtEpochMs()).isEqualTo(456L);
    assertThat(retry.strictProvider()).isTrue();
    assertThat(retry.zeroWriteSalvageAllowed()).isFalse();
    assertThat(retry.changeMode()).isEqualTo(CodeChangeMode.BUILD);
    assertThat(retry.verifyBeforeComplete()).isTrue();
    assertThat(retry.progressV2()).isTrue();
    assertThat(retry.autoVerify()).isTrue();
    assertThat(retry.agentRole()).isEqualTo("ReviewerAgent");
    assertThat(retry.stepPrefix()).isEqualTo("reviewer.general");
  }

  @Test
  void recoveryCanDisableZeroWriteBestGuessSalvage() {
    WorkerTaskRequest ordinary = request(null, null, false);
    WorkerTaskRequest recovery = ordinary.toBuilder().zeroWriteSalvageAllowed(false).build();

    assertThat(ordinary.zeroWriteSalvageEnabled()).isTrue();
    assertThat(recovery.zeroWriteSalvageEnabled()).isFalse();
  }

  private static WorkerTaskRequest request(Long tokens, Double cost, boolean terminal) {
    return WorkerTaskRequest.builder()
        .provider("anthropic")
        .model("test")
        .userPrompt("fix")
        .context(new ExecutionContext("run", "task", Path.of("/tmp/worker-controls")))
        .taskTotalTokenCap(tokens)
        .dollarCap(cost)
        .terminalTaskMode(terminal)
        .build();
  }
}
