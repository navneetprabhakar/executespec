package com.navneet.executespec.harness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.function.Supplier;

import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.test.util.ReflectionTestUtils;

/** Pass-through BUILD supervisor for executor policy tests outside the generation protocol. */
final class SequentialExecutorTestSupervisor {

  private SequentialExecutorTestSupervisor() {}

  static SequentialExecutor attach(SequentialExecutor executor) {
    BuildTaskGenerationCoordinator supervisor = mock(BuildTaskGenerationCoordinator.class);
    lenient()
        .when(supervisor.pendingStatus(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(supervisor.childContext(any(ExecutionContext.class), anyString()))
        .thenAnswer(
            invocation ->
                invocation
                    .getArgument(0, ExecutionContext.class)
                    .child(invocation.getArgument(1, String.class)));
    lenient()
        .when(supervisor.execute(anyInt(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(4, Supplier.class).get());
    lenient()
        .when(
            supervisor.executeContinuation(
                anyInt(),
                any(),
                any(),
                any(),
                anyString(),
                anyString(),
                any(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                any(),
                any()))
        .thenAnswer(invocation -> invocation.getArgument(8, Supplier.class).get());
    ReflectionTestUtils.setField(executor, "buildTaskGenerations", supervisor);
    return executor;
  }
}
