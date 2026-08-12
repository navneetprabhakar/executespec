package com.navneet.executespec.harness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.function.Function;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationDispatchAuthority;

import org.springframework.test.util.ReflectionTestUtils;

/** Pass-through BUILD supervisor for executor policy tests outside the generation protocol. */
final class SequentialExecutorTestSupervisor {

  private SequentialExecutorTestSupervisor() {}

  static SequentialExecutor attach(SequentialExecutor executor) {
    BuildTaskGenerationCoordinator supervisor = mock(BuildTaskGenerationCoordinator.class);
    lenient()
        .when(supervisor.execute(anyInt(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              ExecutionRequest request = invocation.getArgument(3);
              PlannedTask task = invocation.getArgument(1);
              return invocation
                  .getArgument(4, Function.class)
                  .apply(authorizedContext(request, task));
            });
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
        .thenAnswer(
            invocation -> {
              ExecutionRequest request = invocation.getArgument(3);
              PlannedTask task = invocation.getArgument(1);
              return invocation
                  .getArgument(8, Function.class)
                  .apply(authorizedContext(request, task));
            });
    ReflectionTestUtils.setField(executor, "buildTaskGenerations", supervisor);
    return executor;
  }

  private static ExecutionContext authorizedContext(ExecutionRequest request, PlannedTask task) {
    return request
        .runContext()
        .child(task.id())
        .withWorkspaceGenerationAuthority(mock(WorkspaceGenerationDispatchAuthority.class));
  }
}
