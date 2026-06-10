package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkspaceContextThreadLocalAccessorTest {

  private final WorkspaceContextThreadLocalAccessor accessor =
      new WorkspaceContextThreadLocalAccessor();

  @AfterEach
  void tearDown() {
    WorkspaceContext.clear();
  }

  @Test
  void capturesAndRestoresWorktreeOverrideAndTaskId() {
    WorkspaceContext.set("run-1", "/tmp/worktrees/run-1/task-a");
    WorkspaceContext.setTaskId("task-a");

    WorkspaceContext.Snapshot snapshot = accessor.getValue();

    WorkspaceContext.clear();
    accessor.setValue(snapshot);

    assertThat(WorkspaceContext.getWorkDir("run-1")).isEqualTo("/tmp/worktrees/run-1/task-a");
    assertThat(WorkspaceContext.getTaskId()).isEqualTo("task-a");
  }

  @Test
  void restoreNullSnapshotClearsExistingContext() {
    WorkspaceContext.set("run-2", "/tmp/worktrees/run-2/task-b");
    WorkspaceContext.setTaskId("task-b");

    accessor.setValue(null);

    assertThat(WorkspaceContext.getWorkDir("run-2")).isNull();
    assertThat(WorkspaceContext.getTaskId()).isNull();
  }

  @Test
  void setValueWithoutArgumentClearsExistingContext() {
    WorkspaceContext.set("run-3", "/tmp/worktrees/run-3/task-c");
    WorkspaceContext.setTaskId("task-c");

    accessor.setValue();

    assertThat(WorkspaceContext.getWorkDir("run-3")).isNull();
    assertThat(WorkspaceContext.getTaskId()).isNull();
  }
}
