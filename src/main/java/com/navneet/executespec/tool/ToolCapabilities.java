package com.navneet.executespec.tool;

import java.util.Map;
import java.util.Optional;

/**
 * The single alias → {@link ToolCapability} table (RR-1599).
 *
 * <p>Every name any registered {@link Tool} can advertise appears here exactly once, including the
 * snake_case client-parity spellings and the {@code CommandAliasTool} / {@code BuildAliasTool}
 * names. A name absent from this table is UNCLASSIFIED, and callers must fail closed rather than
 * assume it is harmless — that is precisely the failure mode this table exists to end.
 *
 * <p>{@code ToolCapabilitiesTest} asserts that every name reachable through {@code
 * ToolRegistry.toolNames()} is classified, so adding a tool without classifying it breaks the
 * build.
 */
public final class ToolCapabilities {

  private ToolCapabilities() {}

  private static final Map<String, ToolCapability> BY_NAME =
      Map.ofEntries(
          // ---- reads -------------------------------------------------------------------
          Map.entry("readFile", ToolCapability.READ_FILE),
          Map.entry("read_file", ToolCapability.READ_FILE),
          Map.entry("listFiles", ToolCapability.LIST_FILES),
          Map.entry("glob", ToolCapability.LIST_FILES),
          Map.entry("searchFiles", ToolCapability.SEARCH_FILES),
          Map.entry("grep", ToolCapability.SEARCH_FILES),
          Map.entry("ast_search", ToolCapability.SEARCH_FILES),
          Map.entry("astSearch", ToolCapability.SEARCH_FILES),
          Map.entry("readCodeOutline", ToolCapability.READ_CODE_OUTLINE),

          // ---- file mutation -----------------------------------------------------------
          Map.entry("writeFile", ToolCapability.WRITE_FILE),
          Map.entry("write_file", ToolCapability.WRITE_FILE),
          Map.entry("editFile", ToolCapability.EDIT_FILE),
          Map.entry("edit_file", ToolCapability.EDIT_FILE),
          Map.entry("multiEdit", ToolCapability.EDIT_FILE),
          Map.entry("multi_edit", ToolCapability.EDIT_FILE),
          Map.entry("applyPatch", ToolCapability.EDIT_FILE),
          Map.entry("apply_patch", ToolCapability.EDIT_FILE),
          Map.entry("ast_edit", ToolCapability.EDIT_FILE),
          Map.entry("astEdit", ToolCapability.EDIT_FILE),
          Map.entry("move", ToolCapability.MOVE_PATH),
          Map.entry("rename", ToolCapability.MOVE_PATH),
          Map.entry("delete", ToolCapability.DELETE_PATH),
          Map.entry("deletePath", ToolCapability.DELETE_PATH),

          // ---- command execution -------------------------------------------------------
          Map.entry("shell", ToolCapability.SHELL),
          Map.entry("bash", ToolCapability.SHELL),
          Map.entry("executeCommand", ToolCapability.SHELL),
          Map.entry("runCommand", ToolCapability.SHELL),
          Map.entry("terminalCommand", ToolCapability.SHELL),
          Map.entry("testProject", ToolCapability.SHELL),
          Map.entry("installDependencies", ToolCapability.SHELL),
          Map.entry("lintProject", ToolCapability.SHELL),
          Map.entry("formatProject", ToolCapability.SHELL),
          Map.entry("backgroundJob", ToolCapability.BACKGROUND_PROCESS),
          Map.entry("background_process", ToolCapability.BACKGROUND_PROCESS),
          Map.entry("backgroundProcess", ToolCapability.BACKGROUND_PROCESS),
          Map.entry("buildProject", ToolCapability.BUILD_PROJECT),
          Map.entry("build", ToolCapability.BUILD_PROJECT),
          Map.entry("runBuild", ToolCapability.BUILD_PROJECT),
          Map.entry("runTests", ToolCapability.RUN_TESTS),
          Map.entry("runWorkspaceTests", ToolCapability.RUN_TESTS),
          Map.entry("verifyBoot", ToolCapability.VERIFY_BOOT),
          Map.entry("diagnostics", ToolCapability.DIAGNOSTICS),

          // ---- git ---------------------------------------------------------------------
          Map.entry("gitStatus", ToolCapability.GIT_READ),
          Map.entry("gitDiff", ToolCapability.GIT_READ),
          Map.entry("gitCommit", ToolCapability.GIT_WRITE),

          // ---- control / network / stubs -----------------------------------------------
          Map.entry("completeTask", ToolCapability.COMPLETE_TASK),
          Map.entry("update_plan", ToolCapability.PLAN_STATE),
          Map.entry("updatePlan", ToolCapability.PLAN_STATE),
          Map.entry("todo", ToolCapability.PLAN_STATE),
          Map.entry("web_fetch", ToolCapability.NETWORK),
          Map.entry("webFetch", ToolCapability.NETWORK),
          Map.entry("web_search", ToolCapability.NETWORK),
          Map.entry("webSearch", ToolCapability.NETWORK),
          Map.entry("lsp", ToolCapability.UNWIRED_FALLBACK),
          Map.entry("mcp_client", ToolCapability.UNWIRED_FALLBACK),
          Map.entry("mcpClient", ToolCapability.UNWIRED_FALLBACK));

  /** Capability for a tool name, or empty when the name is not classified. */
  public static Optional<ToolCapability> of(String toolName) {
    return toolName == null ? Optional.empty() : Optional.ofNullable(BY_NAME.get(toolName));
  }

  /** True when {@code toolName} has an explicit classification. */
  public static boolean isClassified(String toolName) {
    return of(toolName).isPresent();
  }

  /**
   * Fail-closed write predicate: true for every name that mutates files, AND for an unclassified
   * name (an unknown tool must never be treated as read-only by a safety gate).
   */
  public static boolean mutatesFiles(String toolName) {
    return of(toolName).map(ToolCapability::mutatesFiles).orElse(true);
  }

  /** Fail-closed command-execution predicate; unclassified names count as command execution. */
  public static boolean executesCommands(String toolName) {
    return of(toolName).map(ToolCapability::executesCommands).orElse(true);
  }

  /** Fail-closed mutation predicate; unclassified names count as mutating. */
  public static boolean mutating(String toolName) {
    return of(toolName).map(ToolCapability::mutating).orElse(true);
  }

  /** True only for names explicitly classified as side-effect-free. */
  public static boolean readOnly(String toolName) {
    return of(toolName).map(ToolCapability::readOnly).orElse(false);
  }
}
