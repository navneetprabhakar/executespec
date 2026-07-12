package com.navneet.executespec.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import com.navneet.executespec.agent.ProviderDispatchAuthorization;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class LowCodingFixtureControllerTest {

  @Test
  void endpointRequiresExactScopeAndReturnsDeterministicEvidence() throws Exception {
    WorkerAgent worker = mock(WorkerAgent.class);
    ProviderDispatchAuthorization authorization = mock(ProviderDispatchAuthorization.class);
    when(worker.run(any(WorkerTaskRequest.class)))
        .thenAnswer(
            invocation -> {
              WorkerTaskRequest request = invocation.getArgument(0);
              java.nio.file.Files.writeString(
                  request.context().workspaceRoot().resolve("calculator.py"),
                  "def add(a, b):\n    return a + b\n");
              return WorkerResult.builder()
                  .outcome(WorkerResult.Outcome.COMPLETED)
                  .iterations(2)
                  .toolCallsDispatched(3)
                  .completionSummary("done")
                  .toolCallLog(java.util.List.of())
                  .totalLatencyMs(1)
                  .build();
            });

    LowCodingFixtureController controller = new LowCodingFixtureController(worker, authorization);
    var evidence = controller.run("deepseek", "deepseek-v4-flash-native", "low-1", "res-1");

    assertThat(evidence.tier()).isEqualTo("LOW");
    assertThat(evidence.evaluatorPassed()).isTrue();
    assertThat(evidence.provider()).isEqualTo("deepseek");
    org.mockito.Mockito.verify(authorization)
        .authorizeReservedTest("res-1", "deepseek", "deepseek-v4-flash-native", "low-1");
    Method method =
        LowCodingFixtureController.class.getDeclaredMethod(
            "run", String.class, String.class, String.class, String.class);
    assertThat(LowCodingFixtureController.class.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("hasRole('SUPER_ADMIN')");
    assertThat(method).isNotNull();
  }

  @Test
  void invalidReservationStopsBeforeWorkerDispatch() throws Exception {
    WorkerAgent worker = mock(WorkerAgent.class);
    ProviderDispatchAuthorization authorization = mock(ProviderDispatchAuthorization.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("reservation denied"))
        .when(authorization)
        .authorizeReservedTest("bad", "deepseek", "deepseek-v4-flash-native", "low-2");

    LowCodingFixtureController controller = new LowCodingFixtureController(worker, authorization);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> controller.run("deepseek", "deepseek-v4-flash-native", "low-2", "bad"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reservation denied");
    org.mockito.Mockito.verifyNoInteractions(worker);
  }
}
