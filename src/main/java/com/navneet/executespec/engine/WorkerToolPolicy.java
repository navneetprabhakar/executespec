package com.navneet.executespec.engine;

import java.util.Set;

import com.navneet.executespec.tool.ToolCapabilities;
import com.navneet.executespec.tool.ToolCapability;

/**
 * Capability-keyed classification of a worker tool call for {@code WorkerAgent}'s safety and
 * progress gates (RR-1599).
 *
 * <p>Every predicate here used to be a literal-name match inside {@code WorkerAgent}. The registry
 * deliberately mints several names over one implementation for client-vocabulary parity — {@code
 * executeCommand} / {@code runCommand} / {@code terminalCommand} are all the shell; {@code build} /
 * {@code runBuild} are the build; {@code rename} is {@code move}; {@code deletePath} is {@code
 * delete}; {@code applyPatch} / {@code ast_edit} are edits. An alias therefore executed the same
 * privileged action while being invisible to the guard meant to police it: the verified-pass guard
 * failed OPEN, and the build-verification gate was unsatisfiable through {@code build} / {@code
 * runBuild}.
 *
 * <p>"Fail closed" means closed in the direction that cannot vouch for the run, which differs per
 * predicate and is documented on each.
 */
final class WorkerToolPolicy {

  private WorkerToolPolicy() {}

  /**
   * Capabilities that count as "real progress" for the stagnation guard. Reads and git inspection
   * don't reset the counter — only file mutations and explicit build attempts.
   *
   * <p>{@code BUILD_PROJECT} has counted since 2026-05-08: a worker legitimately alternating
   * read/write/build cycles was stagnating because build calls didn't reset the counter. Build
   * attempts are deliberate forward motion (in programbench mode the build wrapper fails any no-op
   * compile script, so spam-building cannot game the guard).
   */
  private static final Set<ToolCapability> PROGRESS_CAPABILITIES =
      Set.of(
          ToolCapability.WRITE_FILE,
          ToolCapability.EDIT_FILE,
          ToolCapability.MOVE_PATH,
          ToolCapability.DELETE_PATH,
          ToolCapability.BUILD_PROJECT);

  /**
   * Progress classification for the stagnation guard.
   *
   * <p>Fails CLOSED by treating an UNCLASSIFIED name as NOT progress: an unknown tool must not be
   * able to hold the stagnation guard open forever.
   */
  static boolean isProgressTool(String toolName, boolean terminalTaskMode) {
    ToolCapability capability = ToolCapabilities.of(toolName).orElse(null);
    if (capability == null) return false;
    return PROGRESS_CAPABILITIES.contains(capability)
        || (terminalTaskMode && capability.executesCommands());
  }

  /**
   * True when the call can change files on disk — {@code writeFile} / {@code editFile} and every
   * alias over them, plus {@code move} and {@code delete}. Fails CLOSED on an UNCLASSIFIED name.
   */
  static boolean isWriteTool(String toolName) {
    return ToolCapabilities.mutatesFiles(toolName);
  }

  /**
   * True when the call runs an arbitrary command — {@code shell} / {@code bash} and every alias
   * ({@code executeCommand}, {@code runCommand}, {@code terminalCommand}, {@code
   * background_process}, …). Fails CLOSED on an UNCLASSIFIED name.
   */
  static boolean isShellTool(String toolName) {
    return ToolCapabilities.executesCommands(toolName);
  }

  /** True when the call runs the project build under any registered spelling. */
  static boolean isBuildTool(String toolName) {
    return ToolCapabilities.of(toolName).orElse(null) == ToolCapability.BUILD_PROJECT;
  }

  /** True when the call runs the structured test tool under any registered spelling. */
  static boolean isRunTestsTool(String toolName) {
    return ToolCapabilities.of(toolName).orElse(null) == ToolCapability.RUN_TESTS;
  }

  /** Tools whose success can establish a terminal verified pass. */
  static boolean isTerminalVerifierTool(String toolName) {
    return isShellTool(toolName) || isRunTestsTool(toolName) || isBuildTool(toolName);
  }

  /**
   * The verified-pass guard's refusal set.
   *
   * <p>Documented as mechanically preserving a passing candidate, this used to be a literal-name
   * match and so failed OPEN for every alias — {@code executeCommand} / {@code runCommand} / {@code
   * terminalCommand} / {@code build} / {@code runBuild} / {@code move} / {@code rename} / {@code
   * delete} / {@code deletePath} / {@code backgroundProcess} could all still edit or re-run a
   * passing candidate into failure. {@link ToolCapability#mutating()} covers file mutation, command
   * execution, git writes, build, test and boot verification in one fail-closed predicate.
   */
  static boolean isRiskyAfterVerifiedPass(String toolName) {
    return ToolCapabilities.mutating(toolName);
  }
}
