package com.navneet.executespec.agent;

import java.nio.charset.StandardCharsets;

import jakarta.annotation.PostConstruct;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
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
 * (everything except gitCommit). The git-diff capture happens at run-end inside the bench services'
 * {@code captureOnTerminalAsync} path; the agent never commits.
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
   * Default iteration cap. Per-provider overrides apply (see {@link #iterCapFor}). Real ceiling
   * stays the cost cap; iter cap is defense-in-depth.
   */
  private static final int DEFAULT_MAX_ITERATIONS = 120;

  /**
   * 2026-05-08 — per-provider iteration cap tuning, derived from hard-matrix output-token
   * distribution. Empirical observation across 15 hard SWE-bench runs:
   *
   * <ul>
   *   <li><b>Anthropic claude-sonnet-4-6</b>: rich 32-67K output tokens per run, edits efficiently,
   *       benefits from longest runway. Cap: 150.
   *   <li><b>OpenAI gpt-5.4</b>: thin 1-2K output tokens regardless of cap, terminates early with
   *       wrong-fix patches. Bigger cap doesn't help; smaller cap saves cost on stuck runs without
   *       quality loss. Cap: 100.
   *   <li><b>Gemini gemini-3-pro-preview</b>: medium 13-67K output, $1-4 typical spend. Default.
   *       Cap: 120.
   * </ul>
   *
   * Anthropic 150 unlocks long-tail "needs more time" runs; OpenAI 100 trims cost on the
   * thin-output failure mode (a stuck OpenAI run hits cap 33% sooner = same quality + $0.50-1.00
   * less burn). Gemini stays at 120 default — its peak was 24K output on sqlfluff-1733 fits well
   * within 120-iter envelope.
   */
  private static int iterCapFor(String provider) {
    if (provider == null) return DEFAULT_MAX_ITERATIONS;
    return switch (provider.toLowerCase()) {
      case "anthropic" -> 150;
      case "openai" -> 100;
      case "gemini", "google" -> 120;
      default -> DEFAULT_MAX_ITERATIONS;
    };
  }

  /**
   * Stagnation thresholds set to MAX_ITERATIONS so the guard never fires in patch mode. The
   * downstream cost cap and the iteration cap itself are the authoritative stop conditions.
   * Investigation-heavy tasks need this — premature force-terminate at iter 12 was the failure mode
   * that motivated the patch-mode rewrite.
   */
  private final WorkerAgent workerAgent;

  /** Reads the run's {@code source} to scope the test-verification gate (see {@link #run}). */
  private final PipelineRunRepository runRepo;

  /** Run a single thin-agent patch loop. Blocks until the worker terminates. */
  public WorkerResult run(String provider, String model, String userRequest, ExecutionContext ctx) {

    int maxIterations = iterCapFor(provider);
    int stagnationDisabled = maxIterations;

    log.info(
        "PatchAgent START run={} provider={} model={} maxIter={}",
        ctx.runId(),
        provider,
        model,
        maxIterations);

    // Scope the test-verification gate by run source. Blind benchmark runs (SWE-bench harness)
    // deliberately hold out the target test, so the "runTests must pass before completeTask" gate
    // can never be satisfied and the worker loops to the iter cap. Disable it for those
    // ("swebench*"/"benchmark*"); keep it ON for the customer autofix surfaces ("finding:*").
    String src = runRepo.findById(ctx.runId()).map(PipelineRun::getSourceType).orElse("");
    boolean requireTV =
        !(src != null && (src.startsWith("swebench") || src.startsWith("benchmark")));

    WorkerTaskRequest req =
        WorkerTaskRequest.builder()
            .provider(provider)
            .model(model)
            .systemPrompt(buildSystemPrompt())
            .userPrompt(userRequest)
            .context(ctx)
            .maxIterations(maxIterations)
            .temperature(0.0)
            .maxTokensPerCall(MAX_TOKENS_PER_CALL)
            .stagnationWarnAfter(stagnationDisabled)
            .stagnationForceAfter(stagnationDisabled)
            // V182 layer-3 — refuse completeTask in patch mode unless runTests was called
            // recently. Without this, prompt-only "verify before finishing" guidance can be
            // ignored or over-applied; the gate enforces test execution programmatically.
            // 2026-05-08 strengthened: gate now requires tests to PASS (failed:[] +
            // errored:[] + exit_code:0) not just be run — see WorkerAgent line ~470.
            .requireTestVerification(requireTV)
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
   * Patch-mode system prompt loaded from classpath at boot.
   *
   * <p>Audit 2026-05-12 review item M #69 — extracted from a 130-line inline string literal to
   * {@code prompts/patch-agent.md} per the CLAUDE.md MUST-pattern (prompts in external resource
   * files).
   */
  @Value("classpath:prompts/patch-agent.md")
  private Resource patchAgentPromptResource;

  private String patchAgentPrompt;

  @PostConstruct
  void loadPatchAgentPrompt() {
    try {
      patchAgentPrompt = patchAgentPromptResource.getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.error("[PatchAgent] Failed to load classpath:prompts/patch-agent.md — using a stub", e);
      patchAgentPrompt =
          "You are a patch-mode coding agent. Make the minimum change that fixes the issue.";
    }
  }

  /**
   * Returns the loaded patch-mode system prompt. See {@code prompts/patch-agent.md} for the full
   * template.
   */
  private String buildSystemPrompt() {
    return patchAgentPrompt;
  }

  /**
   * @deprecated Inlined prompt removed; see {@link #buildSystemPrompt} + {@code
   *     prompts/patch-agent.md}. Kept for compile compatibility during the inline-prompt audit — DO
   *     NOT call.
   */
  @Deprecated(forRemoval = true)
  @SuppressWarnings("unused")
  private String buildSystemPromptInlineDeprecated() {
    return """
You are a patch-mode coding agent. The user has given you a specific bug-fix or
targeted-edit task. Your job is to make the minimum change that fixes it.

WORKING DIRECTORY
- The repository is already at the workspace root. Files you create / edit there
  will be captured at run-end as a unified diff (`git diff` against the base
  commit). You don't need to commit.

INVESTIGATE BEFORE EDITING — READ THE GOLD TESTS FIRST

The user message above contains TWO pieces of ground truth:
  1. The pytest output (what error the test produced on the unmodified code)
  2. The GOLD TEST PATCH (a unified diff showing the assertions your fix must
     satisfy — the FAIL_TO_PASS tests do not exist in /testbed yet, so this
     diff is your ONLY source of truth for what behaviour to produce)

YOU MUST READ THE GOLD TESTS BEFORE EDITING. Match the assertions exactly —
literal strings in the assertions are not suggestions, they are the spec.
Returning `"Unknown"` when the test asserts `== "Unknown.Unknown()"` does
NOT pass.

Error messages lie about root cause more than half the time:

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

After reading the gold tests:

- Identify the EXACT literal/value each `assert` expects. If the test asserts
  `x == "Unknown.Unknown()"`, your code must return that exact string — not
  `"Unknown"`, not `repr(node)`, not `node.__class__.__name__`. Match the
  literal.
- Trace from each `assert` back to the production code it exercises.
- Use `shell` for navigation (grep / find / ls). Use `readFile` when you know
  the path.
- Spend as many iterations as you need on investigation. There is no penalty
  for reading; there is a heavy penalty for editing the wrong file or
  producing the wrong return value.
- Watch out for the "shallow fix" failure mode: do not stop at the first
  obvious-looking deprecation / typo / import error you see. That's
  frequently a smokescreen for a deeper bug. ALWAYS run the failing test
  after your edit — if it still fails (or fails in a new way), keep digging.
- Remember: the gold tests do NOT exist in /testbed yet. You CANNOT verify
  your fix by `pytest <test_name>` — pytest will report `not found`. To check
  your work, manually trace your edited code against each gold assertion
  shown in the diff. If they all hold, the fix is correct.

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
  2. Run pytest against tests in the SAME file as the FAIL_TO_PASS tests
     (e.g. if FAIL_TO_PASS is `tests/foo.py::test_x`, run
     `pytest tests/foo.py -q --no-header`). This proves the PASS_TO_PASS
     siblings still pass — i.e. you didn't break adjacent tests.

Note: the FAIL_TO_PASS tests themselves do NOT exist in /testbed yet, so
running `pytest <FAIL_TO_PASS_id>` directly will report "no tests ran" —
that's expected, not a fix problem. The gold tests are applied by the
SWE-bench harness AFTER your patch lands.

To verify your fix is right, manually trace each assertion in the gold
test diff against your edited code:

  - Look up each `assert <expr> == <literal>` in the gold tests
  - Plug your code's output into <expr> mentally (or copy the relevant
    function bodies into the python REPL via `shell` and call them)
  - Confirm the literal matches

If you call completeTask without having run pytest at all, the call will
be REFUSED. The "verify PASS_TO_PASS sibling didn't regress" step is
non-negotiable.

WHEN YOU'RE DONE
- Run pytest on the test file ONE LAST TIME to confirm sibling
  PASS_TO_PASS tests still pass (the FAIL_TO_PASS itself will not
  appear in the run — that's normal).
- Re-read the gold-test diff and confirm each `assert` will hold under
  your edited code.
- THEN call `completeTask` with: (a) a one-line summary of the change,
  (b) a one-line note of what tests you ran in pytest. The git diff
  between the base commit and the workspace is your final patch.

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
