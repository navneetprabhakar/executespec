package com.navneet.executespec.eval.v2;

import java.nio.file.Path;

import com.navneet.executespec.agent.WorkerResult;

/**
 * A WORKHORSE-tier agentic eval case. Unlike {@link TierEvalCase} (single-shot tool-call grading),
 * a workhorse case drives the real multi-turn {@link com.navneet.executespec.agent.WorkerAgent}
 * loop against a seeded scratch workspace and grades the resulting file-system state.
 *
 * <p>This is the closest the eval gets to production: same worker prompt, same tool set (readFile /
 * writeFile / editFile / listFiles / completeTask bound through the real ToolRegistry), same
 * completion semantics. The grade looks at what actually landed on disk plus the worker's terminal
 * {@link WorkerResult.Outcome}.
 */
public interface WorkhorseEvalCase {

  String name();

  /** The task instruction handed to the worker (becomes the user prompt). */
  String taskPrompt();

  /**
   * Seed the scratch workspace with the starting files for this case. Called once before the worker
   * runs, with an empty freshly-created directory.
   */
  void seedWorkspace(Path workspaceDir) throws Exception;

  /**
   * Grade the final workspace + worker outcome 0–100. Implementations inspect files on disk under
   * {@code workspaceDir} and may consider {@code result.outcome()} / iterations.
   */
  TierEvalCase.ValidationVerdict validate(Path workspaceDir, WorkerResult result);
}
