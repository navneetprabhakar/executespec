package com.navneet.executespec.tool;

/**
 * Canonical capability a {@link Tool} exposes, independent of the NAME the model sees.
 *
 * <p><b>Why this exists (RR-1599).</b> The registry deliberately mints multiple names over the same
 * implementation for client/local-hands vocabulary parity — {@code executeCommand} / {@code
 * runCommand} / {@code terminalCommand} are all {@link ShellTool}; {@code build} / {@code runBuild}
 * are {@link BuildProjectTool}; {@code rename} is {@code move}; {@code deletePath} is {@code
 * delete}. Every downstream worker safety and evidence gate, however, was a literal-name match
 * ({@code isWriteTool} / {@code isShellTool} / {@code WRITE_CLASS_TOOLS} in {@code
 * engine.WorkerAgent}; {@code repeatedCommandFailureSignature} / {@code mutationPathSignature} in
 * {@code harness.SequentialExecutor}). An alias therefore executed the same privileged action while
 * being invisible to the guard that was supposed to police it: the verified-pass guard failed OPEN
 * and the repeated-failure waste guard never fired.
 *
 * <p>Policy must key on capability, not spelling. {@link ToolCapabilities} is the single table
 * mapping every registered name to its capability, and {@link Tool#capability()} exposes it per
 * instance.
 */
public enum ToolCapability {

  /** Reads file content. */
  READ_FILE,
  /** Enumerates directory entries. */
  LIST_FILES,
  /** Searches file content or paths. */
  SEARCH_FILES,
  /** Reads a structural outline of code without executing anything. */
  READ_CODE_OUTLINE,

  /** Creates or wholesale-replaces a file. */
  WRITE_FILE,
  /** Applies a targeted in-place edit to an existing file. */
  EDIT_FILE,
  /** Moves or renames a path. */
  MOVE_PATH,
  /** Deletes a path. */
  DELETE_PATH,

  /** Runs an arbitrary sandbox-permitted command in the foreground. */
  SHELL,
  /** Runs an arbitrary sandbox-permitted command as a managed background process. */
  BACKGROUND_PROCESS,
  /** Runs the project build. */
  BUILD_PROJECT,
  /** Runs project tests. */
  RUN_TESTS,
  /** Runs a boot/health verification pass. */
  VERIFY_BOOT,

  /** Reads git state without mutating it. */
  GIT_READ,
  /** Mutates git state (commit). */
  GIT_WRITE,

  /** Signals task termination. */
  COMPLETE_TASK,
  /** Records plan/todo state; no side effects outside the agent's own bookkeeping. */
  PLAN_STATE,
  /** Fetches or searches the network. */
  NETWORK,
  /** Reports diagnostics derived from a sandboxed command. */
  DIAGNOSTICS,
  /** Registered for client parity but not actually wired server-side (LSP / MCP stubs). */
  UNWIRED_FALLBACK;

  /** True when invoking this capability can change files on disk. */
  public boolean mutatesFiles() {
    return this == WRITE_FILE || this == EDIT_FILE || this == MOVE_PATH || this == DELETE_PATH;
  }

  /** True when invoking this capability runs an arbitrary command. */
  public boolean executesCommands() {
    return this == SHELL || this == BACKGROUND_PROCESS;
  }

  /**
   * True when invoking this capability can change workspace or repository state — the conservative
   * predicate a fail-closed guard should use. Build/test runs are included because they execute
   * project-controlled scripts.
   */
  public boolean mutating() {
    return mutatesFiles()
        || executesCommands()
        || this == GIT_WRITE
        || this == BUILD_PROJECT
        || this == RUN_TESTS
        || this == VERIFY_BOOT;
  }

  /** True when this capability performs no state change and no command execution. */
  public boolean readOnly() {
    return !mutating() && this != UNWIRED_FALLBACK;
  }
}
