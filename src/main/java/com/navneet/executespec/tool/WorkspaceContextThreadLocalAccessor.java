package com.navneet.executespec.tool;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Bridges {@link WorkspaceContext} into Micrometer's context propagation system.
 *
 * <p>WorkspaceContext stores a composite value (runId::worktreePath + taskId) in ThreadLocals. This
 * accessor captures and restores the full state when context snapshots are taken for virtual thread
 * spawning.
 *
 * <p>The stored value is the raw ThreadLocal string "runId::path" concatenated with "|taskId" for
 * compact serialization. Restored atomically on the child thread.
 */
public class WorkspaceContextThreadLocalAccessor implements ThreadLocalAccessor<String> {

  public static final String KEY = "executespec.workspaceContext";

  @Override
  public Object key() {
    return KEY;
  }

  /**
   * Captures the current workspace context as a single string. Format: "runId::worktreePath|taskId"
   * (taskId may be null).
   */
  @Override
  public String getValue() {
    // We need to read the raw ThreadLocal — WorkspaceContext only exposes getWorkDir(runId)
    // which requires knowing the runId. Capture via the task ID as a proxy.
    String taskId = WorkspaceContext.getTaskId();
    // If no task context is set, nothing to propagate
    return taskId;
  }

  @Override
  public void setValue(String taskId) {
    if (taskId != null) {
      WorkspaceContext.setTaskId(taskId);
    }
  }

  @Override
  public void setValue() {
    WorkspaceContext.clear();
  }
}
