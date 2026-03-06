package com.navneet.agentteam.tool;

/**
 * Thread-local context that lets each worker task redirect file/git
 * operations to its own isolated git worktree directory instead of the shared
 * run workspace root.
 *
 * <p>Also stores the current task ID so that {@link FileSystemTool} can enforce
 * file locks at write time.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #set(String, String)} — called by WorkerAgent before the agentic loop</li>
 *   <li>{@link #setTaskId(String)} — called by WorkerAgent right after set()</li>
 *   <li>All FileSystemTool / GitTool / ShellTool operations read the override via
 *       {@link #getWorkDir(String)}</li>
 *   <li>{@link #clear()} — called in the finally block of WorkerAgent.execute()</li>
 * </ol>
 */
public final class WorkspaceContext {

    private WorkspaceContext() {}

    /**
     * Maps runId → absolute worktree path for the current thread.
     * Stored as "runId::absolutePath" in a single ThreadLocal to avoid
     * any per-runId map bookkeeping.
     */
    private static final ThreadLocal<String> CTX = new ThreadLocal<>();

    /** Stores the current task ID for file-lock enforcement. */
    private static final ThreadLocal<String> TASK_CTX = new ThreadLocal<>();

    /** Sets the worktree override for this thread. */
    public static void set(String runId, String absoluteWorktreePath) {
        CTX.set(runId + "::" + absoluteWorktreePath);
    }

    /**
     * Returns the absolute worktree path for {@code runId} if set on this thread,
     * or {@code null} if no override is active (fall back to default workspace root).
     */
    public static String getWorkDir(String runId) {
        String val = CTX.get();
        if (val == null) return null;
        int sep = val.indexOf("::");
        if (sep < 0) return null;
        if (!val.substring(0, sep).equals(runId)) return null;
        return val.substring(sep + 2);
    }

    /** Sets the current task ID for file-lock enforcement. */
    public static void setTaskId(String taskId) {
        TASK_CTX.set(taskId);
    }

    /** Returns the current task ID, or null if not set. */
    public static String getTaskId() {
        return TASK_CTX.get();
    }

    /** Clears the context for the current thread. */
    public static void clear() {
        CTX.remove();
        TASK_CTX.remove();
    }
}
