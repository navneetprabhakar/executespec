package com.navneet.executespec.tool;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Bridges {@link WorkspaceContext} into Micrometer's context propagation system.
 *
 * <p>WorkspaceContext stores a composite value (runId::worktreePath + taskId) in ThreadLocals. This
 * accessor captures and restores the full state when context snapshots are taken for virtual thread
 * spawning.
 *
 * <p>The stored value is the raw worktree ThreadLocal string plus taskId. Restored atomically on
 * the child thread.
 */
public class WorkspaceContextThreadLocalAccessor
    implements ThreadLocalAccessor<WorkspaceContext.Snapshot> {

  public static final String KEY = "executespec.workspaceContext";

  @Override
  public Object key() {
    return KEY;
  }

  /**
   * Captures the current workspace context. The worktree value must travel with taskId; propagating
   * only taskId makes child-thread file/git tools fall back to the shared run workspace.
   */
  @Override
  public WorkspaceContext.Snapshot getValue() {
    return WorkspaceContext.capture();
  }

  @Override
  public void setValue(WorkspaceContext.Snapshot snapshot) {
    WorkspaceContext.restore(snapshot);
  }

  @Override
  public void setValue() {
    WorkspaceContext.clear();
  }
}
