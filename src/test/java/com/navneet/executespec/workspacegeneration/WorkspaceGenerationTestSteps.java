package com.navneet.executespec.workspacegeneration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

/** In-memory interaction stub for unit tests that do not boot the V397 PostgreSQL journal. */
final class WorkspaceGenerationTestSteps {

  private WorkspaceGenerationTestSteps() {}

  static WorkspaceGenerationStepStore store() {
    WorkspaceGenerationStepStore store = mock(WorkspaceGenerationStepStore.class);
    when(store.recordIntent(any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            call ->
                new WorkspaceGenerationStepStore.Step(
                    UUID.randomUUID(),
                    call.getArgument(0),
                    call.getArgument(1),
                    call.getArgument(2),
                    "1".repeat(64),
                    WorkspaceGenerationStepStore.State.INTENT,
                    call.getArgument(3),
                    call.getArgument(4),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "{}"));
    return store;
  }
}
