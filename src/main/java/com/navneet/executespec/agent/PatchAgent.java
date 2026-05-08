package com.navneet.executespec.agent;

import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Patch-mode entry point — single thin-agent loop for bug-fix shapes.
 *
 * <p>The architecture motivation lives in the discussion that produced V182 / {@link
 * com.navneet.executespec.state.RunMode}; this class is the runtime end of it. It does not
 * re-implement the loop — it delegates straight to {@link WorkerAgent} with carefully chosen
 * configuration so the existing context-snapshot / cost-tracking / tool-dispatch / metric plumbing
 * is reused unchanged. The differences from a build-mode worker call are:
 *
 * <ul>
 *   <li>One {@code WorkerTaskRequest} for the entire user request — no classifier, no planner, no
 *       per-task fragmentation. The model's working memory IS the plan.
 *   <li>Iteration cap raised to 75 and stagnation thresholds raised to (effectively) off so the
 *       agent can spend many iterations exploring before any write. On SWE-bench / bug-fix shapes,
 *       investigation is the work — premature stagnation force-terminate killed empty-patch runs.
 *   <li>Forced PLANNING-tier model regardless of upstream classification. Haiku-class models can't
 *       navigate 1000-file codebases reliably; the reasoning-gap is bigger than any harness tweak.
 *   <li>Higher per-call output cap so a long edit / multi-file patch fits in one tool-call.
 * </ul>
 *
 * <p>Tools available are exactly {@link com.navneet.executespec.tool.ToolRegistry#bindForWorker}
 * (everything except gitCommit). The git-diff capture happens at run-end inside the bench
 * services' {@code captureOnTerminalAsync} path; the agent never commits.
 *
 * <p>This class deliberately stays small: ~80 LOC of orchestration + one prompt template.
 * Improvements (better navigation primitives, retrieval, self-consistency vote) layer on top
 * without touching the orchestrator's branch logic.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PatchAgent {

  /**
   * Per-call output cap. Higher than the build-mode worker's per-tier caps because a SWE-bench fix
   * may produce one big edit rather than several small ones, and we don't want truncation to leave
   * a half-formed tool-call.
   */
  private static final int MAX_TOKENS_PER_CALL = 16_000;

  /**
   * Iteration cap. Calibrated against mini-swe-agent's published runs — most SWE-bench Lite tasks
   * resolve in 25-50 iterations; the long tail benefits from headroom to 75. Combined with the
   * per-run hard-cap (default $4 in the bench services), this caps total cost without strangling
   * the long-tail problems.
   */
  private static final int MAX_ITERATIONS = 75;

  /**
   * Stagnation thresholds set to MAX_ITERATIONS so the guard never fires in patch mode. The
   * downstream cost cap and the iteration cap itself are the authoritative stop conditions.
   * Investigation-heavy tasks need this — premature force-terminate at iter 12 was the failure
   * mode that motivated the patch-mode rewrite.
   */
  private static final int STAGNATION_DISABLED = MAX_ITERATIONS;

  private final WorkerAgent workerAgent;

  /** Run a single thin-agent patch loop. Blocks until the worker terminates. */
  public WorkerResult run(
      String provider, String model, String userRequest, ExecutionContext ctx) {

    log.info(
        "PatchAgent START run={} provider={} model={} maxIter={}",
        ctx.runId(),
        provider,
        model,
        MAX_ITERATIONS);

    WorkerTaskRequest req =
        WorkerTaskRequest.builder()
            .provider(provider)
            .model(model)
            .systemPrompt(buildSystemPrompt())
            .userPrompt(userRequest)
            .context(ctx)
            .maxIterations(MAX_ITERATIONS)
            .temperature(0.0)
            .maxTokensPerCall(MAX_TOKENS_PER_CALL)
            .stagnationWarnAfter(STAGNATION_DISABLED)
            .stagnationForceAfter(STAGNATION_DISABLED)
            // V182 layer-3 — refuse completeTask in patch mode unless runTests was called
            // recently. Without this, prompt-only "verify before finishing" guidance can be
            // ignored or over-applied; the gate enforces test execution programmatically.
            .requireTestVerification(true)
            .build();

    WorkerResult result = workerAgent.run(req);

    log.info(
        "PatchAgent END run={} outcome={} iterations={} toolCalls={} latencyMs={}",
        ctx.runId(),
        result.outcome(),
        result.iterations(),
        result.toolCallsDispatched(),
        result.totalLatencyMs());

    return result;
  }

  /**
   * Patch-mode system prompt. Test-driven: the first investigation step is always the failing
   * test (when the request mentions one). Minimal-change discipline: PASS_TO_PASS regression is the
   * dominant SWE-bench failure mode, so the prompt explicitly directs surgical edits over
   * rewrites.
   */
  private String buildSystemPrompt() {
    return """
You are a patch-mode coding agent. The user has given you a specific bug-fix or
targeted-edit task. Your job is to make the minimum change that fixes it.

WORKING DIRECTORY
- The repository is already at the workspace root. Files you create / edit there
  will be captured at run-end as a unified diff (`git diff` against the base
  commit). You don't need to commit.

INVESTIGATE BEFORE EDITING — READ THE FAILING TEST FIRST

The user message above contains TWO pieces of ground truth:
  1. The pytest output (what error the test produced on the unmodified code)
  2. The failing test SOURCE CODE (the assertions you must satisfy)

YOU MUST READ THE TEST SOURCE BEFORE EDITING. Error messages lie about root
cause more than half the time:

  - The error trace says "ImportError: np.Inf was removed" but the actual
    failing test is checking divide-by-zero in golden-section search. Fixing
    np.Inf only unblocks test collection; the test then fails for a different
    reason. → Read the test, see the assertion, find the production code that
    handles the assertion's input.

  - The error says "AttributeError: visit_unknown not found" and the fix IS
    just adding that method. (astroid-1268 case.) Easy.

  - The error says nothing useful (e.g. tests time out) → the test source is
    the only signal you have. Read it.

The pattern: **the test source tells you what behavior is being verified,
which is the only reliable anchor when error messages mislead.** The error
trace is a hint at where the symptom appears; the test source is the
specification of what the system should do.

After reading the test:

- Trace from each `assert` back to the production code it exercises.
- Use `shell` for navigation (grep / find / ls). Use `readFile` when you know
  the path.
- Spend as many iterations as you need on investigation. There is no penalty
  for reading; there is a heavy penalty for editing the wrong file (the
  patch will be evaluated by re-running the test, and a wrong-file fix won't
  make the test pass).
- Watch out for the "shallow fix" failure mode: do not stop at the first
  obvious-looking deprecation / typo / import error you see. That's
  frequently a smokescreen for a deeper bug. ALWAYS run the failing test
  after your edit — if it still fails (or fails in a new way), keep digging.

EDIT MINIMALLY
- Prefer `editFile` (surgical replace) over `writeFile` (whole-file overwrite).
  A small targeted patch is much less likely to break the existing test suite.
- Don't touch tests unless the task explicitly says to. Most bug-fix tasks
  expect production code edits only.
- Don't reformat / reorganise / refactor code beyond what the fix requires.
  Style changes balloon the diff and increase regression risk.

THE LOOP — FOLLOW THIS RHYTHM

Patch tasks have a specific shape: investigate → edit → verify → iterate.
Don't get stuck in any one phase. Most successful runs alternate:

  Read 2-4 files → make ONE focused edit → run the failing test →
  read the test output → either the test passes (move on) or you see
  the next thing wrong (read 1-2 more files → make another focused edit →
  run again → repeat).

Don't read for 30 iterations before editing. Don't edit then call complete
without running the test. Don't run the test 10 times without changing the
code. Each iteration should produce a small, observable step.

VERIFICATION

Before calling completeTask, you MUST have:

  1. Made at least one edit (writeFile / editFile) to a non-test source
     file.
  2. Run the failing test (via the `runTests` tool, or `shell` with
     pytest) AND seen it pass — output should show `1 passed` or
     equivalent.

If the test still fails after your edit, your patch isn't right yet —
read the assertion error, diagnose, edit, run again. There's no penalty
for spending 5-10 iterations iterating; there is a big penalty for
calling completeTask without test verification.

If you call completeTask without having run a test recently, the call
will be REFUSED and you'll be asked to run the test first. Don't waste
the round-trip.

WHEN YOU'RE DONE
- Run the failing test ONE LAST TIME to confirm.
- Run 2-3 PASS_TO_PASS tests from the same file to confirm no regression.
- THEN call `completeTask` with: (a) a one-line summary of the change,
  (b) a one-line note of what test passed and what command you ran.
  The git diff between the base commit and the workspace is your final
  patch.

THINGS TO AVOID
- Don't try to commit, push, branch, or otherwise mutate git state — those tools
  are denied for you. Workspace state at task-end is the artifact.
- Don't write debug print statements you forget to remove. Don't leave commented-
  out code. Don't add "TODO" / "FIXME" markers — solve the problem in this run.
- Don't write "I notice the failing test expects X, so I'll just modify the test
  to expect Y" — that's not a fix.
""";
  }
}
