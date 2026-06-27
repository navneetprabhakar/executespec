package com.navneet.executespec.harness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.execution.model.GoalContinuationAdvisorRequest;
import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.GoalContinuationRequest;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.GoalEvidence;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.execution.service.GoalContinuationAdvisor;
import com.navneet.executespec.execution.service.GoalContinuationPolicy;
import com.navneet.executespec.execution.service.GoalDonenessEvaluator;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelProviderScore;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ProviderAvailabilityMap;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolResult;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 sequential executor. Runs a {@link Plan}'s tasks in order, one worker per task.
 *
 * <p>Per-task flow:
 *
 * <ol>
 *   <li>Compose system + user prompts from the task's description + hardConstraints + touchesFiles
 *       hint
 *   <li>Invoke {@link WorkerAgent} with the composed prompts and the run's ExecutionContext
 *       (substituting the task ID)
 *   <li>If worker didn't reach {@code COMPLETED}: record {@code WORKER_FAILED}, continue to next
 *       task (current policy — Phase 5 baseline. A future supervisor could upgrade to ABORT on
 *       catastrophic failures.)
 *   <li>Worker completed → run smoke test via {@link BuildProjectTool} using the plan's {@code
 *       buildCommand}
 *   <li>Smoke test passed → {@code SUCCESS}, next task
 *   <li>Smoke test failed → ONE retry: re-invoke worker with the build error injected into the user
 *       message
 *   <li>Retry passed → {@code SUCCESS_AFTER_RETRY}; retry failed → {@code COMPLETED_WITH_WARNING},
 *       continue anyway (reviewer phase — Phase 7 — will catch residual issues)
 * </ol>
 *
 * <p>Phase 5 baseline scope:
 *
 * <ul>
 *   <li>Rule-based failure handling (retry once, then warn). A future TaskSupervisorV2 can add
 *       LLM-tier triage (CASCADING / FIXABLE / STRUCTURAL) for smarter decisions.
 *   <li>No SSE events, no Redis context persistence, no cross-task context sharing.
 *   <li>Smoke tests default to the plan's {@code smokeTestStrategy} (currently only BUILD is fully
 *       wired — PREVIEW_HEALTH / TEST_SUITE planned for later phases).
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SequentialExecutor {

  /**
   * Accessor for the worker base system prompt so the tier-eval-v2 WORKHORSE cases can drive the
   * worker loop with the EXACT production prompt (native tool calling) instead of the legacy
   * XML-style {@code prompts/worker.txt}. Read-only; the eval never mutates it.
   */
  public static String workerSystemPromptBase() {
    return SYSTEM_PROMPT_BASE;
  }

  /** System prompt template for worker invocations. */
  private static final String SYSTEM_PROMPT_BASE =
      """
You are a code-execution agent operating in a sandboxed workspace. You have
access to filesystem (readFile, writeFile, editFile, listFiles, searchFiles),
shell, read-only git (status, diff), and buildProject tools. Use the tools
to complete the task — do not explain or hedge.
CLI-compatible aliases are also valid when present in the tool schema:
read_file, write_file, edit_file, glob, grep, bash, multi_edit/apply_patch,
move/rename, delete, background_process, web_fetch/web_search, diagnostics,
ast_search. Use only tools actually listed in the current tool schema.

Important rules:
- The shell runs in the workspace root already. Do NOT `cd /workspace` or use
  absolute paths — use relative paths (e.g. `python3 fib.py`, not `cd /workspace && ...`).
- Use `python3` (never bare `python`); many systems have no `python` on PATH.
- writeFile creates NEW files only. To modify an existing file, use editFile —
  never mutate a tracked file in place through the shell (`sed -i`, `awk -i
  inplace`, or `>`/`>>` over an existing file); that skips the read-before-edit
  check and leaves no reviewable diff (`sed -i`/`awk -i inplace` are blocked).
- write_file follows the same rule: it never silently clobbers an existing file.
  If a write returns ALREADY_EXISTS, read the file and use edit_file/editFile.
- editFile takes an array of {oldStr, newStr} pairs; every oldStr must occur
  exactly once in the current file.
- edit_file is the single-replacement alias. If it returns STALE_READ, call
  read_file/readFile again and re-issue the edit against the current content.
- bash returns JSON {stdout, stderr, exit_code}. If it returns COMMAND_FAILED,
  read stdout/stderr and fix the cause; do not retry the same command blindly.
- After making changes, you may call buildProject to verify the project still
  compiles.
- When the task is fully complete, call completeTask with a brief summary.
  Do not output any text that is not needed to complete the task.

DO NOT commit, stage, or push. Workspace changes are committed and pushed
automatically at run-end by a downstream service. You have NO gitCommit
tool — attempts to call it return FORBIDDEN. Do not run shell commands that
mutate git state (`git add`, `git commit`, `git reset`, `git stash`,
`git push`). Edit files; call completeTask. That's it.

BUILD ONLY WHAT THE TASK ASKS — match complexity to the requirement (applies to
ALL tasks, new features as well as fixes):
  - Write the minimum implementation that satisfies the task. Do not add options,
    config, abstraction, or "future-proofing" the task did not request.
  - No defensive code for cases that cannot occur given how the code is called.
  - Validate inputs only at real trust boundaries (external/untrusted input); trust
    your own internal callers rather than re-checking between private functions.
  - Do NOT add comments or docstrings to code you did not change. Any new comment
    states the non-obvious WHY, never a restatement of what the code does.
  - If code becomes unused, delete it outright — do not comment it out or rename it
    to `_unused`. Dead code left behind is not cleanup.
  - Prefer a few similar concrete lines over a premature abstraction; add a helper
    only when duplication is real and repeated, not merely anticipated.
This does NOT waive the test requirement below — tests that exercise what you build
are part of the task, not gold-plating.

FIXING A BUG IN EXISTING CODE — localize, reproduce, fix small, verify.
When the task is to FIX a defect in code that ALREADY EXISTS (not scaffold a new
feature), follow this discipline — it is what separates a fix that lands from one
that only looks plausible:
  1. LOCALIZE precisely first. Use searchFiles (or `grep -rn <symbol>`) to find
     the exact file + function the bug lives in, then read only those one or two
     files — not the whole repo. Reading without editing is NOT progress; the
     moment you have found the spot, make the edit.
  2. REPRODUCE before fixing — use the issue's OWN reproduction, verbatim. Copy the
     exact snippet/MWE the task shows into /tmp/repro.py (NEVER inside the repo, so it
     stays out of your diff). Do NOT paraphrase it or write a simpler version of your
     own — a repro you invent can pass while the real symptom remains. Run it FIRST and
     observe the EXACT wrong behaviour the issue names (the precise error, the missing
     comma, the bad key, the wrong output). If you cannot reproduce that exact symptom
     you have not localized it — keep looking, do not guess-edit.
  3. FIX minimally. Change only the lines required. Do NOT reformat, reorder
     imports, refactor, or edit files unrelated to the fix. A 3-line fix that
     resolves the issue beats a 40-line fix that also "cleans up."
  4. VERIFY before completeTask — against the issue's symptom, not your own. Re-run the
     issue's reproduction and confirm the EXACT symptom it described is gone (and run the
     existing test nearest the change if one exists). Decisive check: if your edit did NOT
     change the observed output, the code you edited does not run on this path — you are in
     the WRONG place. Do not double down: trace where the bad output is actually produced
     (add a print, or grep the literal wrong string/symbol from the issue) and fix THERE.
     A patch that leaves the issue's symptom unchanged is not a fix — never call completeTask
     on it. Also match the issue's stated contract exactly: if it shows a call like
     `f(x, text=False)` or names an option, your fix must expose THAT name/signature, not an
     equivalent you renamed.

Report honestly + recover deliberately:
- Report outcomes faithfully. If a build or test fails, say so with the actual output — never claim
  it passed, and never weaken, skip, or simplify a check (test, build, type-check) to manufacture a
  green result. If you did not run a verification step, say that plainly rather than implying it
  succeeded. Equally, when a check did pass, state it directly — don't hedge a confirmed result.
- When something fails, DIAGNOSE before retrying: read the actual error, check the assumption it
  breaks, then make a focused fix. Do NOT re-run the identical action hoping for a different result;
  do NOT abandon a working approach after a single failure.
- Weigh irreversible actions. Local file edits, builds, and tests are freely reversible — just do
  them. But do not reach for a destructive or hard-to-undo shortcut to make an obstacle disappear
  (deleting files, discarding uncommitted work, `rm -rf`, or bypassing a safety check such as
  `--no-verify`); find and fix the root cause instead.

TESTS ARE PART OF THE TASK — NOT OPTIONAL.
If your task adds or changes a function, endpoint, route, or module, you MUST
also create or extend a test that exercises it before calling completeTask:
  - At least one happy-path test per public function / endpoint you touched.
  - At least one error / edge-case test (invalid input, not-found, failure path)
    where the spec or the code has an obvious failure mode.
  - Put tests in the project's conventional location + framework (pytest
    `test_*.py`, Jest `*.test.ts` / `__tests__/`, Go `*_test.go`, JUnit
    `src/test/java/...`). If no test scaffold exists, create the minimal one.
Untested code is an INCOMPLETE task. Do not call completeTask on a code change
with zero tests unless the task is genuinely untestable (pure static asset, a
one-line config) — and if so, say why in your completeTask summary.

Efficiency — BATCH AGGRESSIVELY. Every extra turn costs tokens and latency:
- Emit ALL independent tool calls in ONE turn (reads, writes, edits, builds).
- If you just wrote a file, DO NOT re-read it to verify — trust writeFile's
  success envelope and move on.
- Do NOT call gitStatus/gitDiff unless you specifically need commit staging info.
- Do NOT re-read the same file twice in a task; content does not change unless
  YOU wrote to it.
- Prefer large editFile batches (many {oldStr,newStr} pairs in one call) over
  many small editFile calls.
- When the work is done, call completeTask IMMEDIATELY; do not add
  "final verification" round-trips.

Format-after-edit discipline (2026-05-13 audit run 6024f510 RC-1):
The customer's CI runs the project's formatter in --check mode and rejects the
PR if the diff isn't formatter-clean. Run the formatter on EVERY file you write
or edit BEFORE moving on:
  - Python (.py)         → `black <file>` (or `ruff format <file>` if pyproject.toml uses ruff)
  - JS/TS (.js .ts .tsx) → `npx prettier --write <file>`
  - Go (.go)             → `gofmt -w <file>`
  - Java (.java)         → skip; mvn spotless handles it on smoke
If the formatter binary isn't on PATH (`black: command not found`), continue —
do NOT pip install / npm install just to format. The build's lint gate will
catch it as fallback.
When the spec explicitly mentions a formatter (e.g. "apply black formatting"),
treat that as a HARD requirement: run `<formatter> --check <dir>` once at the
end and only call completeTask if it exits 0.
""";

  private static final String LOCAL_HANDS_DIRECTIVE =
      """


LOCAL-HANDS MODE — you are editing the user's current working tree directly.
Treat the request like an interactive coding-agent task, not a product scaffold:
  - Obey explicit file scope exactly. If the user says "only", "exactly", "do not create", or names
    specific files, touch only those files unless the task is impossible without another file.
  - Default CLI/local-hands behavior is PROTOTYPE speed. Do not create test files, validation
    modules, package manifests, config files, or framework scaffolds unless the user explicitly
    asks for tests/validation/scaffolding.
  - You may run existing tests/builds when they already exist and are cheap/relevant, but running
    checks is not permission to add new test files.
  - Do not start or simulate review, scoring, validation-report, preview, deploy, PR, or merge
    workflow unless the user explicitly asks for that workflow. Build/check is allowed; review is
    not part of the default CLI prototype path.
  - For data/content/config/documentation-only edits, and for direct file-generation requests, the
    test requirement is satisfied by producing the requested files correctly; do not invent tests
    just because the workspace is empty.
  - When the requested local edits are complete, call completeTask immediately.
""";

  private static final String LOCAL_HANDS_FINAL_OVERRIDE =
      """


LOCAL-HANDS FINAL OVERRIDE:
The local-hands file-scope rule overrides the generic "tests are mandatory" rule above.
If the user explicitly requested an exact file set, creating any additional test, validator,
package, config, or scaffold file is a task failure. For exact-scope data/content/config/docs
edits, write only the requested files and complete. In CLI/local-hands prototype runs, create new
tests only when the user asks for tests, TDD, coverage, or validation files. If the task modifies
existing executable source code and the project already has a clear nearby test convention, prefer
extending an existing test only when it is necessary to verify the requested code change; otherwise
finish the requested edit and report what existing check, if any, you ran. Do not run reviewer or
validation-report flows unless the user explicitly asks for review/validation.
""";

  /**
   * RL-2026-05-066 (verifyBoot) — appended to the worker prompt ONLY when {@code
   * worker.verify-boot.enabled} is on (default OFF). Kept out of {@link #SYSTEM_PROMPT_BASE} per
   * the RL-2026-05-061 lesson that an always-present new tool/directive can cause reasoning-only
   * stalls on some providers; the verifyBoot tool itself returns {@code available:false} when the
   * flag is off, so the worker only ever sees this when it can actually act on it.
   */
  private static final String VERIFY_BOOT_DIRECTIVE =
      """


VERIFY THE APP BOOTS — runnable apps only.
If this task produces a runnable app (web service, API, frontend, full-stack), then before
completeTask:
  1. Ensure .agent-context/run-recipe.json exists at the workspace root declaring the port and
     health path the app serves, e.g. {"port": 3000, "healthPath": "/health", "startTimeoutSec": 60}.
     Create it if missing. Use a Dockerfile if the app needs a non-default build; otherwise the
     buildpack default auto-detects how to build + start it.
  2. Call verifyBoot. It builds + boots the app in an isolated sandbox and probes healthPath.
  3. If the verdict is not HEALTHY, read the reason and FIX the app (wrong start command, port not
     bound to 0.0.0.0, missing dependency, crash on boot), then call verifyBoot again. A booting app
     is the BUILD signal the score reads — "it compiles" is not enough.
This applies ONLY to apps meant to run; pure libraries, scripts, and config-only tasks have nothing
to boot — skip it for those.""";

  private final WorkerAgent worker;
  private final BuildProjectTool buildProjectTool;
  private final ObjectMapper objectMapper;
  private final Metrics metrics;
  private final SseBroadcaster broadcaster;
  private final AgentTaskRepository taskRepo;
  private final ModelResolver modelResolver;
  private final com.navneet.executespec.router.ModelMatrixProperties matrixProps;
  private final com.navneet.executespec.project.rag.RetrievalService retrievalService;
  // Transfer #1 (PATCH→BUILD, RL-2026-05-034): in-loop test-execution gate. After a clean compile,
  // RUN the worker's tests so "tests are mandatory" means "tests pass," not just "tests compile."
  private final com.navneet.executespec.smoketest.WorkspaceTestRunner inLoopTestRunner;
  private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;

  /**
   * 2026-05-04 — used by the resume path to load saved worker conversation snapshots. The
   * non-resume execute path doesn't touch this field; ContextStore writes happen inside {@link
   * WorkerAgent} itself.
   */
  private final com.navneet.executespec.context.ContextStore contextStore;

  /**
   * Live per-task token attribution — read the accumulator on every terminal transition so the UI's
   * "Token Usage by Model" shows mid-run numbers instead of waiting for the end-of-run batch flush.
   */
  private final com.navneet.executespec.cost.RunCostTracker costTracker;

  // RL-2026-05-041 (audit #2) — mid-run durability: GCS checkpoint at each task boundary.
  private final com.navneet.executespec.deployment.GcsTaskCheckpointService
      gcsTaskCheckpointService;

  private final com.navneet.executespec.router.AvailableProviderResolver availableProviderResolver;
  private final GoalContinuationAdvisor goalContinuationAdvisor;
  private final GoalDonenessEvaluator goalDonenessEvaluator = new GoalDonenessEvaluator();
  private final GoalContinuationPolicy goalContinuationPolicy = new GoalContinuationPolicy();
  private final ConcurrentMap<String, AtomicInteger> continuationProviderSwitchesPerRun =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AtomicInteger> continuationModelStepUpsPerTask =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AtomicInteger> continuationModelStepDownsPerTask =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Boolean> continuationConfirmationRequiredByRun =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, ContinuationConfirmationScope>
      continuationConfirmationScopeByRun =
      new ConcurrentHashMap<>();

  private record ContinuationConfirmationScope(
      boolean confirmed, RunContinuationDecision action, String provider, String model) {
    static ContinuationConfirmationScope from(ExecutionRequest req) {
      if (req == null || !Boolean.TRUE.equals(req.userConfirmedExpensiveContinuation())) {
        return new ContinuationConfirmationScope(false, null, null, null);
      }
      return new ContinuationConfirmationScope(
          true,
          req.userConfirmedContinuationAction(),
          req.userConfirmedContinuationProvider(),
          req.userConfirmedContinuationModel());
    }
  }

  @Value("${execution.continuation.required-quality-threshold:70.0}")
  private double goalRequiredQualityThreshold = 70.0;

  @Value("${execution.continuation.required-quality-thresholds.task-low:-1}")
  private double goalRequiredQualityThresholdTaskLow = -1.0;

  @Value("${execution.continuation.required-quality-thresholds.task-medium:-1}")
  private double goalRequiredQualityThresholdTaskMedium = -1.0;

  @Value("${execution.continuation.required-quality-thresholds.task-high:-1}")
  private double goalRequiredQualityThresholdTaskHigh = -1.0;

  @Value("${execution.continuation.required-quality-thresholds.greenfield-build:-1}")
  private double goalRequiredQualityThresholdGreenfieldBuild = -1.0;

  @Value("${execution.continuation.required-quality-thresholds.project-patch:-1}")
  private double goalRequiredQualityThresholdProjectPatch = -1.0;

  @Value("${execution.continuation.required-quality-thresholds.bug-fix:-1}")
  private double goalRequiredQualityThresholdBugFix = -1.0;

  @Value("${execution.continuation.required-quality-thresholds.test-fix:-1}")
  private double goalRequiredQualityThresholdTestFix = -1.0;

  @Value("${execution.continuation.required-quality-thresholds.refactor:-1}")
  private double goalRequiredQualityThresholdRefactor = -1.0;

  @Value("${execution.continuation.required-quality-thresholds.benchmark-patch:-1}")
  private double goalRequiredQualityThresholdBenchmarkPatch = -1.0;

  @Value("${execution.continuation.required-quality-thresholds.terminal-task:-1}")
  private double goalRequiredQualityThresholdTerminalTask = -1.0;

  @Value("${execution.continuation.required-quality-thresholds.read-only-help:-1}")
  private double goalRequiredQualityThresholdReadOnlyHelp = -1.0;

  @Value("${execution.continuation.max-provider-switches-per-run:1}")
  private int advisoryProviderSwitchLimit = 1;

  @Value("${execution.continuation.max-model-step-ups-per-task:1}")
  private int advisoryModelStepUpLimit = 1;

  @Value("${execution.continuation.max-model-step-downs-per-task:1}")
  private int advisoryModelStepDownLimit = 1;

  @Value("${execution.continuation.max-no-progress-iterations:28}")
  private int maxGoalNoProgressIterations = 28;

  @Value("${execution.continuation.max-false-done-claims:2}")
  private int maxGoalFalseDoneClaims = 2;

  @Value("${execution.continuation.max-repeated-failures:3}")
  private int maxGoalRepeatedFailures = 3;

  @Value("${execution.continuation.max-edit-churn:6}")
  private int maxGoalEditChurn = 6;

  @Value("${execution.continuation.max-stale-explanations:2}")
  private int maxGoalStaleExplanations = 2;

  @Value("${execution.continuation.max-parallel-tasks:3}")
  private int maxGoalParallelTasks = 3;

  @Value("${execution.continuation.require-confirmation-before-expensive-continuation:false}")
  private boolean requireConfirmationBeforeExpensiveContinuation = false;

  @Value("${execution.continuation.require-benchmark-prior-for-automatic-continuation:true}")
  private boolean requireBenchmarkPriorForAutomaticContinuation = false;

  @Value("${execution.continuation.max-total-cost-usd:-1}")
  private double maxGoalTotalCostUsd = -1.0;

  @Value("${execution.continuation.max-total-tokens:-1}")
  private int maxGoalTotalTokens = -1;

  @Value("${execution.continuation.max-task-wall-clock-ms:-1}")
  private long maxGoalTaskWallClockMs = -1L;

  @Value("${execution.continuation.acting.enabled:true}")
  private boolean continuationActingEnabled = true;

  @Value("${execution.continuation.max-reruns-per-task:1}")
  private int continuationRerunLimit = 1;

  /**
   * Retrieval knobs for worker-time RAG injection (v2.5.2+). Smaller than the planner's budget
   * (k=3, 1500) so per-task cost stays bounded across many tasks.
   */
  private static final int WORKER_RAG_K = 2;

  private static final int WORKER_RAG_TOKEN_CAP = 800;

  public ExecutionResult execute(ExecutionRequest req) {
    return execute(req, null);
  }

  /**
   * Resume-aware execute. When {@code resume} is non-null, every PlannedTask is checked against its
   * persisted {@link com.navneet.executespec.state.entity.AgentTask} status:
   *
   * <ul>
   *   <li>{@code COMPLETE} / {@code COMPLETED_WITH_WARNING} / {@code PARTIAL} / {@code FAILED} —
   *       already terminal. Skip; build a synthetic {@link TaskOutcome} from the persisted row so
   *       the run-level summary is consistent with what landed before the crash.
   *   <li>{@code WORKING} — the in-flight task at the time of the crash. If a Redis snapshot
   *       exists, hand it to the worker via the resume-aware {@code worker.run(req, snapshot)}
   *       overload so the conversation continues from the saved iteration. If the snapshot is gone
   *       (TTL expired, no save fired yet), the task runs fresh from iter=1.
   *   <li>{@code PENDING} / null / unknown — never started. Run fresh.
   * </ul>
   *
   * <p>The resume target is identified by either the explicit {@code resume.targetTaskId()} or —
   * when null — the first task whose persisted status is non-terminal (typically WORKING). All
   * tasks before the resume target are treated as terminal-skip; all tasks after are run fresh.
   */
  public ExecutionResult execute(ExecutionRequest req, ResumeRequest resume) {
    long runStart = System.currentTimeMillis();
    List<TaskOutcome> outcomes = new ArrayList<>();
    Plan plan = req.plan();
    String runId = req.runContext().runId();
    boolean abortedByGoalDecision = false;
    continuationConfirmationRequiredByRun.put(
        runId,
        requireConfirmationBeforeExpensiveContinuation
            || Boolean.TRUE.equals(req.requireConfirmationBeforeExpensiveContinuation()));
    continuationConfirmationScopeByRun.put(runId, ContinuationConfirmationScope.from(req));
    seedInitialContinuationActionState(runId, req, resume);

    try {
      log.info(
          "SequentialExecutor {} run={} tasks={} provider={} model={} smokeTests={}"
              + " resumeTarget={}",
          resume == null ? "START" : "RESUME",
          runId,
          plan.tasks().size(),
          req.provider(),
          req.model(),
          req.enableSmokeTests(),
          resume == null ? null : resume.targetTaskId());
      emitTaskWavePlan(runId, plan);

      int taskCount = plan.tasks().size();
      for (int i = 0; i < taskCount; i++) {
        // 2026-05-10 — cancel honour at task boundary. {@code RunOrchestrator.cancel}
        // calls {@code future.cancel(true)} which interrupts THIS thread, but
        // the only place the interrupt actually surfaces is the next blocking
        // call (Spring AI / OkHttp inside {@link com.navneet.executespec.agent.LlmRouter}).
        // Without a top-of-loop check, every remaining task gets dispatched in
        // turn — each one starts the worker, the very first LLM call throws
        // {@code BackOffInterruptedException}, persistTaskStatus stamps it
        // FAILED, the loop body returns, and we walk straight into the next
        // task. Net effect on dev run 7483896a (PR #99 analysis): a cancel
        // mid-T11 cascaded T11→T20 into FAILED in <1 second, overwriting
        // their original PARTIAL / FAILED-stagnation reasons with a generic
        // "Thread interrupted while sleeping" message and clobbering the
        // COMPLETE state of T13–T16, T20.
        //
        // Re-asserting the interrupt flag with {@code Thread.currentThread()
        // .interrupt()} so any caller waiting on this thread (RunOrchestrator's
        // {@code activeRuns} future) still sees it as cancelled.
        if (Thread.currentThread().isInterrupted()) {
          log.info(
              "SequentialExecutor cancel detected at task index {}/{}, breaking loop",
              i,
              taskCount);
          Thread.currentThread().interrupt();
          break;
        }

        PlannedTask task = plan.tasks().get(i);
        boolean isFinalTask = (i == taskCount - 1);

        // Resume bookkeeping: figure out what to do with this task BEFORE running anything.
        //   - skip it (already terminal in DB) → emit synthetic outcome
        //   - resume it (snapshot present) → executeOne with snapshot
        //   - run fresh
        TaskOutcome outcome;
        if (resume != null) {
          ResumeAction action = resolveResumeAction(runId, task.id(), resume);
          switch (action.kind()) {
            case SKIP_TERMINAL -> {
              log.info(
                  "Executor RESUME skip task={} status={} (already terminal pre-resume)",
                  task.id(),
                  action.persistedStatus());
              outcome = synthesizeSkippedOutcome(task, action.persistedStatus());
            }
            case RESUME_FROM_SNAPSHOT -> {
              log.info(
                  "Executor RESUME task={} from snapshot iter={}",
                  task.id(),
                  action.snapshot() == null ? "n/a" : action.snapshot().iteration());
              outcome = executeOne(task, plan, req, isFinalTask, action.snapshot());
            }
            case RUN_FRESH -> outcome = executeOne(task, plan, req, isFinalTask, null);
            default -> outcome = executeOne(task, plan, req, isFinalTask, null);
          }
        } else {
          outcome = executeOne(task, plan, req, isFinalTask, null);
        }

        if (resume == null) {
          outcome = maybeActOnContinuation(task, plan, req, isFinalTask, outcome);
        }

        outcomes.add(outcome);
        metrics.recordTaskStatus(outcome.status().name());
        metrics.recordTaskLatency(
            outcome.status().name(), java.time.Duration.ofMillis(outcome.totalLatencyMs()));

        // RL-2026-05-041 (audit #2) — checkpoint the workspace to GCS at this task boundary so
        // mid-run work survives a node loss. Async + no-op when GCS is unconfigured (local dev).
        gcsTaskCheckpointService.checkpointAfterTask(runId, task.id());

        GoalDonenessDecision goalDecision = outcome.goalDonenessDecision();
        if (shouldEnforceGoalStop(goalDecision)) {
          int remaining = taskCount - i - 1;
          log.warn(
              "SequentialExecutor goal-stop enforced run={} task={} decision={} state={}"
                  + " remaining={}",
              runId,
              task.id(),
              goalDecision.recommendedDecision(),
              goalDecision.state(),
              remaining);
          broadcaster.emit(
              runId,
              RunEvent.log(
                  runId,
                  task.id(),
                  "Goal stop enforced: "
                      + goalDecision.recommendedDecision()
                      + " ("
                      + goalDecision.reason()
                      + "); skipping "
                      + remaining
                      + " remaining task"
                      + (remaining == 1 ? "" : "s")));
          appendSkippedGoalStopOutcomes(outcomes, plan, i + 1);
          abortedByGoalDecision = true;
          break;
        }
      }

      ExecutionResult result =
          summarize(outcomes, System.currentTimeMillis() - runStart, abortedByGoalDecision);
      log.info(
          "SequentialExecutor DONE run={} overall={} success={}/{} warnings={} failed={}"
              + " latency={}ms",
          runId,
          result.overall(),
          result.tasksSuccessful(),
          result.tasksExecuted(),
          result.tasksWithWarning(),
          result.tasksFailed(),
          result.totalLatencyMs());

      // 2026-05-28 (RL-2026-05-052, T3.14) — Redis cleanup hygiene. Per-task agent-ctx snapshots
      // are saved every 5 iters with a 24h TTL; that TTL is fine for a stuck JVM but is way too
      // long for a normal run-end. Delete every v2:agent-ctx:{runId}:* key now that the executor
      // has produced its terminal result. ALWAYS runs (not behind the retry-retention flag) —
      // pure hygiene, safe regardless of which other flags are flipped. Cross-provider in-memory
      // state gets the same cleanup. Both calls are best-effort; failures are logged only.
      if (contextStore != null) {
        contextStore.deleteAll(runId);
      }
      clearCrossProviderState(runId);
      clearContinuationActionState(runId);

      return result;
    } finally {
      continuationConfirmationRequiredByRun.remove(runId);
      continuationConfirmationScopeByRun.remove(runId);
    }
  }

  private void emitTaskWavePlan(String runId, Plan plan) {
    if (plan == null || plan.tasks().isEmpty()) {
      return;
    }
    TaskWaveScheduler.BoundedResult schedule =
        TaskWaveScheduler.scheduleBounded(
            taskIds(plan), dependsOnByTask(plan), touchesFilesByTask(plan), maxParallelTasks());
    int maxWaveWidth = schedule.waves().stream().mapToInt(List::size).max().orElse(0);
    boolean parallelEligible = schedule.waves().stream().anyMatch(wave -> wave.size() > 1);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("executionMode", "SEQUENTIAL_ADVISORY");
    data.put("maxParallelTasks", maxParallelTasks());
    data.put("taskCount", plan.tasks().size());
    data.put("waveCount", schedule.waves().size());
    data.put("maxWaveWidth", maxWaveWidth);
    data.put("parallelEligible", parallelEligible);
    data.put("waves", schedule.waves());
    data.put("serializedTasks", schedule.serializedTasks());
    data.put("unscheduledTasks", schedule.unscheduled());
    data.put("missingDependencies", schedule.missingDependencies());
    String summary =
        "Task wave plan: "
            + schedule.waves().size()
            + " bounded wave"
            + (schedule.waves().size() == 1 ? "" : "s")
            + " · maxParallelTasks="
            + maxParallelTasks()
            + " · execution=sequential-advisory";
    log.info(
        "SequentialExecutor taskWavePlan run={} waves={} maxParallelTasks={} serialized={} "
            + "unscheduled={} missingDeps={}",
        runId,
        schedule.waves(),
        maxParallelTasks(),
        schedule.serializedTasks(),
        schedule.unscheduled(),
        schedule.missingDependencies());
    broadcaster.emit(runId, RunEvent.taskWavePlan(runId, summary, data));
    broadcaster.emit(runId, RunEvent.log(runId, null, summary));
  }

  private static List<String> taskIds(Plan plan) {
    return plan.tasks().stream().map(PlannedTask::id).toList();
  }

  private static Map<String, List<String>> dependsOnByTask(Plan plan) {
    Map<String, List<String>> dependsOn = new LinkedHashMap<>();
    for (PlannedTask task : plan.tasks()) {
      dependsOn.put(task.id(), task.dependsOn());
    }
    return dependsOn;
  }

  private static Map<String, List<String>> touchesFilesByTask(Plan plan) {
    Map<String, List<String>> touchesFiles = new LinkedHashMap<>();
    for (PlannedTask task : plan.tasks()) {
      touchesFiles.put(task.id(), task.touchesFiles());
    }
    return touchesFiles;
  }

  /**
   * Encapsulates the resume target — passed to {@link #execute(ExecutionRequest, ResumeRequest)}.
   */
  public record ResumeRequest(
      /**
       * Task id to resume from. Tasks before this id are treated as terminal (assumed already
       * finished pre-crash); tasks at this id are resumed (with snapshot if available); tasks after
       * are run fresh. May be null — in which case the executor walks the plan and resumes the
       * first task whose persisted DB status is non-terminal.
       */
      String targetTaskId) {}

  /** Result of the per-task resume decision in the loop above. */
  private record ResumeAction(
      ResumeKind kind,
      String persistedStatus,
      com.navneet.executespec.context.SavedContext snapshot) {}

  private enum ResumeKind {
    SKIP_TERMINAL,
    RESUME_FROM_SNAPSHOT,
    RUN_FRESH
  }

  /**
   * Decide what the executor should do with a given task on a resume pass. Reads the persisted
   * {@link com.navneet.executespec.state.entity.AgentTask} status; loads a Redis snapshot iff the
   * task is the resume target.
   */
  private ResumeAction resolveResumeAction(String runId, String taskId, ResumeRequest resume) {
    // 2026-05-10 — task-id format normalisation.
    //
    // The `taskId` argument is the LOGICAL plan id ("T12") — comes
    // straight from {@link PlannedTask#id()} when the executor walks
    // {@code plan.tasks()}. The DB primary key on {@code agent_task},
    // however, is the FULL prefixed key (runShort + "_" + plannedId,
    // e.g. "7483896a_T12"). Pre-fix, this method called
    // {@link #readPersistedTaskStatus(String, String)} with the bare
    // logical id, which {@code findByRunIdAndTaskId} never matched
    // against the prefixed primary key — every query returned empty,
    // every task was treated as non-terminal, every resume re-ran
    // every task from T1.
    //
    // Symptom in production: dev run 7483896a, retry of T12 caused
    // T8 → T9 → T10 → ... to all flip WORKING + re-execute, burning
    // ~$5.65 of LLM budget before the operator cancelled. Latent on
    // {@link com.navneet.executespec.recovery.StartupRunResumer} too —
    // every JVM-crash recovery has been silently re-running tasks
    // since the resume path landed.
    //
    // Fix: build the full DB key explicitly from {@code runId} +
    // {@code taskId} before lookup, and accept either format
    // (prefixed OR bare) on {@code resume.targetTaskId()} so callers
    // don't have to remember which one this method wants.
    String runShort = runId != null && runId.length() >= 8 ? runId.substring(0, 8) : runId;
    String fullKey = runShort + "_" + taskId;
    String persisted = readPersistedTaskStatus(runId, fullKey);
    boolean isTerminal =
        persisted != null
            && (persisted.equals("COMPLETE")
                || persisted.equals("COMPLETED_WITH_WARNING")
                || persisted.equals("PARTIAL")
                || persisted.equals("FAILED"));

    String target = resume.targetTaskId();
    boolean isResumeTarget = target != null && (target.equals(taskId) || target.equals(fullKey));

    // If a target wasn't given explicitly, treat the FIRST non-terminal task we encounter as
    // the resume target (auto-detect path). Once we've passed the target, everything else runs
    // fresh.
    if (target == null) {
      if (isTerminal) return new ResumeAction(ResumeKind.SKIP_TERMINAL, persisted, null);
      // First non-terminal: this is our resume target. Below logic loads the snapshot.
      isResumeTarget = true;
    }

    if (isTerminal) {
      return new ResumeAction(ResumeKind.SKIP_TERMINAL, persisted, null);
    }
    if (!isResumeTarget) {
      return new ResumeAction(ResumeKind.RUN_FRESH, persisted, null);
    }

    // Resume target — try to load the snapshot from Redis.
    com.navneet.executespec.tool.ExecutionContext ctx =
        new com.navneet.executespec.tool.ExecutionContext(
            runId,
            taskId,
            // workspaceRoot isn't part of the Redis key — any value works for keyFor.
            java.nio.file.Paths.get("/"));
    com.navneet.executespec.context.SavedContext snap = contextStore.load(ctx).orElse(null);
    if (snap == null) {
      log.info(
          "Executor RESUME run={} task={} no snapshot — running fresh (likely <5 iters at crash)",
          runId,
          taskId);
      return new ResumeAction(ResumeKind.RUN_FRESH, persisted, null);
    }
    return new ResumeAction(ResumeKind.RESUME_FROM_SNAPSHOT, persisted, snap);
  }

  private String readPersistedTaskStatus(String runId, String taskId) {
    return taskRepo
        .findByRunIdAndTaskId(runId, taskId)
        .map(com.navneet.executespec.state.entity.AgentTask::getStatus)
        .orElse(null);
  }

  /**
   * Build a synthetic {@link TaskOutcome} for a task that was already terminal at resume time. The
   * summary aggregator uses this to compute total latency / success counts; we zero out
   * worker-specific fields since we don't have a worker result for it (the original work happened
   * in a previous JVM).
   */
  private TaskOutcome synthesizeSkippedOutcome(PlannedTask task, String persistedStatus) {
    // Map the persisted DB task status onto the in-memory TaskOutcome.Status enum. PARTIAL on
    // disk maps to COMPLETED_WITH_WARNING here — both indicate "worker wrote files but smoke
    // didn't pass cleanly", and the in-memory enum doesn't carry a separate PARTIAL value.
    TaskOutcome.Status mapped =
        switch (persistedStatus == null ? "" : persistedStatus) {
          case "COMPLETE" -> TaskOutcome.Status.SUCCESS;
          case "COMPLETED_WITH_WARNING", "PARTIAL" -> TaskOutcome.Status.COMPLETED_WITH_WARNING;
          case "FAILED" -> TaskOutcome.Status.WORKER_FAILED;
          default -> TaskOutcome.Status.SUCCESS;
        };
    return TaskOutcome.builder()
        .taskId(task.id())
        .taskTitle(task.title())
        .status(mapped)
        .totalLatencyMs(0L)
        .toolCallsDispatched(0)
        .build();
  }

  private void appendSkippedGoalStopOutcomes(
      List<TaskOutcome> outcomes, Plan plan, int firstSkippedIndex) {
    for (int j = firstSkippedIndex; j < plan.tasks().size(); j++) {
      PlannedTask skipped = plan.tasks().get(j);
      outcomes.add(
          TaskOutcome.builder()
              .taskId(skipped.id())
              .taskTitle(skipped.title())
              .status(TaskOutcome.Status.SKIPPED)
              .totalLatencyMs(0L)
              .toolCallsDispatched(0)
              .build());
    }
  }

  private boolean shouldEnforceGoalStop(GoalDonenessDecision decision) {
    if (decision == null) {
      return false;
    }
    if (featureFlagService == null
        || !featureFlagService.isEnabled(FLAG_GOAL_DECISION_ENFORCEMENT)) {
      return false;
    }
    return switch (decision.recommendedDecision()) {
      case STOP_BLOCKED, STOP_FAILED, STOP_WASTE_GUARD -> true;
      case STOP_DONE,
              CONTINUE_SAME_MODEL_PROVIDER,
              STEP_UP_MODEL,
              STEP_DOWN_MODEL,
              CHANGE_PROVIDER ->
          false;
    };
  }

  private TaskOutcome maybeActOnContinuation(
      PlannedTask task, Plan plan, ExecutionRequest req, boolean isFinalTask, TaskOutcome outcome) {
    if (!continuationActingEnabled || maxContinuationReruns() <= 0 || outcome == null) {
      return outcome;
    }
    GoalContinuationPolicyDecision decision = outcome.continuationDecision();
    if (!actionableContinuationDecision(decision)) {
      return outcome;
    }
    ModelProviderScore candidate = decision.selectedCandidate().orElse(null);
    if (candidate == null) {
      return outcome;
    }
    if (sameProviderModel(candidate, outcome.dispatchedProvider(), outcome.dispatchedModel())) {
      return outcome;
    }

    return executeContinuationAction(
        task, plan, req, isFinalTask, outcome, decision, candidate, true);
  }

  private TaskOutcome executeContinuationAction(
      PlannedTask task,
      Plan plan,
      ExecutionRequest req,
      boolean isFinalTask,
      TaskOutcome outcome,
      GoalContinuationPolicyDecision decision,
      ModelProviderScore candidate,
      boolean keepOriginalWhenSkipped) {
    String runId = req.runContext().runId();
    ContinuationActionLimit actionLimit = reserveContinuationAction(runId, task.id(), decision);
    if (!actionLimit.allowed()) {
      emitContinuationActionSkipped(runId, task.id(), decision, candidate, actionLimit);
      return keepOriginalWhenSkipped ? outcome : null;
    }

    String reason = firstReason(decision);
    String summary =
        "Continuation action: "
            + decision.decision()
            + " -> rerun task with "
            + candidate.label()
            + " ("
            + reason
            + ")";
    log.warn(
        "SequentialExecutor continuation action run={} task={} decision={} selected={} reason={}",
        runId,
        task.id(),
        decision.decision(),
        candidate.label(),
        reason);
    broadcaster.emit(
        runId,
        RunEvent.continuationAction(
            runId,
            task.id(),
            summary,
            decision.decision().name(),
            candidate.provider(),
            candidate.model(),
            reason,
            1,
            maxContinuationReruns(),
            actionLimit.limitName(),
            actionLimit.usedAfterReserve(),
            actionLimit.maxAllowed()));
    broadcaster.emit(runId, RunEvent.log(runId, task.id(), summary));
    appendContinuationActionHistory(
        runId, task.id(), decision, candidate, actionLimit, "EXECUTED", reason);

    TaskOutcome rerun =
        executeOne(
            task,
            plan,
            req,
            isFinalTask,
            null,
            new ProviderModelOverride(
                candidate.provider(), candidate.model(), decision.decision()));
    return mergeContinuationAttempt(outcome, rerun, decision, candidate);
  }

  private boolean actionableContinuationDecision(GoalContinuationPolicyDecision decision) {
    if (decision == null) {
      return false;
    }
    return switch (decision.decision()) {
      case STEP_UP_MODEL, STEP_DOWN_MODEL, CHANGE_PROVIDER ->
          decision.selectedCandidate().isPresent();
      case CONTINUE_SAME_MODEL_PROVIDER, STOP_DONE, STOP_BLOCKED, STOP_FAILED, STOP_WASTE_GUARD ->
          false;
    };
  }

  private static boolean sameProviderModel(
      ModelProviderScore candidate, String dispatchedProvider, String dispatchedModel) {
    return candidate
            .provider()
            .equalsIgnoreCase(dispatchedProvider == null ? "" : dispatchedProvider)
        && candidate.model().equals(dispatchedModel);
  }

  private int maxContinuationReruns() {
    return Math.min(1, Math.max(0, continuationRerunLimit));
  }

  private ContinuationActionLimit reserveContinuationAction(
      String runId, String taskId, GoalContinuationPolicyDecision decision) {
    return switch (decision.decision()) {
      case CHANGE_PROVIDER ->
          reserveLimit(
              continuationProviderSwitchesPerRun,
              runId,
              "maxProviderSwitches",
              maxProviderSwitches());
      case STEP_UP_MODEL ->
          reserveLimit(
              continuationModelStepUpsPerTask,
              continuationTaskKey(runId, taskId),
              "maxModelStepUps",
              maxModelStepUps());
      case STEP_DOWN_MODEL ->
          reserveLimit(
              continuationModelStepDownsPerTask,
              continuationTaskKey(runId, taskId),
              "maxModelStepDowns",
              maxModelStepDowns());
      case CONTINUE_SAME_MODEL_PROVIDER, STOP_DONE, STOP_BLOCKED, STOP_FAILED, STOP_WASTE_GUARD ->
          new ContinuationActionLimit("notActionable", 0, 0, false);
    };
  }

  private static ContinuationActionLimit reserveLimit(
      ConcurrentMap<String, AtomicInteger> counters, String key, String limitName, int maxAllowed) {
    if (maxAllowed <= 0) {
      int used = counters.getOrDefault(key, new AtomicInteger(0)).get();
      return new ContinuationActionLimit(limitName, used, maxAllowed, false);
    }
    AtomicInteger counter = counters.computeIfAbsent(key, ignored -> new AtomicInteger(0));
    while (true) {
      int used = counter.get();
      if (used >= maxAllowed) {
        return new ContinuationActionLimit(limitName, used, maxAllowed, false);
      }
      if (counter.compareAndSet(used, used + 1)) {
        return new ContinuationActionLimit(limitName, used + 1, maxAllowed, true);
      }
    }
  }

  private void emitContinuationActionSkipped(
      String runId,
      String taskId,
      GoalContinuationPolicyDecision decision,
      ModelProviderScore candidate,
      ContinuationActionLimit actionLimit) {
    String reason =
        actionLimit.limitName()
            + " limit reached (used="
            + actionLimit.usedAfterReserve()
            + ", max="
            + actionLimit.maxAllowed()
            + ")";
    String summary =
        "Continuation action skipped: "
            + decision.decision()
            + " -> "
            + candidate.label()
            + " ("
            + reason
            + ")";
    log.warn(
        "SequentialExecutor continuation action skipped run={} task={} decision={} selected={}"
            + " limit={} used={} max={}",
        runId,
        taskId,
        decision.decision(),
        candidate.label(),
        actionLimit.limitName(),
        actionLimit.usedAfterReserve(),
        actionLimit.maxAllowed());
    broadcaster.emit(
        runId,
        RunEvent.continuationActionSkipped(
            runId,
            taskId,
            summary,
            decision.decision().name(),
            candidate.provider(),
            candidate.model(),
            firstReason(decision),
            actionLimit.limitName(),
            actionLimit.usedAfterReserve(),
            actionLimit.maxAllowed()));
    broadcaster.emit(runId, RunEvent.log(runId, taskId, summary));
    appendContinuationActionHistory(
        runId, taskId, decision, candidate, actionLimit, "SKIPPED", reason);
  }

  private void appendContinuationActionHistory(
      String runId,
      String taskIdShort,
      GoalContinuationPolicyDecision decision,
      ModelProviderScore candidate,
      ContinuationActionLimit actionLimit,
      String status,
      String reason) {
    if (taskRepo == null
        || objectMapper == null
        || runId == null
        || runId.length() < 8
        || taskIdShort == null
        || taskIdShort.isBlank()
        || decision == null
        || candidate == null
        || actionLimit == null) {
      return;
    }
    String key = runId.substring(0, 8) + "_" + taskIdShort;
    try {
      var existing = taskRepo.findById(key);
      if (existing.isEmpty()) {
        log.debug("appendContinuationActionHistory: row not found id={}", key);
        return;
      }
      var task = existing.get();
      ArrayNode actions = readContinuationActions(task.getContinuationActionsJson());
      ObjectNode action = actions.addObject();
      action.put("status", status == null || status.isBlank() ? "UNKNOWN" : status);
      action.put("decision", decision.decision().name());
      action.put("provider", candidate.provider());
      action.put("model", candidate.model());
      action.put("reason", reason == null ? "" : reason);
      action.put("limitName", actionLimit.limitName());
      action.put("usedLimit", actionLimit.usedAfterReserve());
      action.put("maxLimit", actionLimit.maxAllowed());
      action.put("attempt", actions.size());
      task.setContinuationActionsJson(objectMapper.writeValueAsString(actions));
      taskRepo.saveAndFlush(task);
    } catch (Exception ex) {
      log.warn("appendContinuationActionHistory: {} failed: {}", key, ex.getMessage(), ex);
    }
  }

  private ArrayNode readContinuationActions(String json)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    if (json == null || json.isBlank()) {
      return objectMapper.createArrayNode();
    }
    JsonNode parsed = objectMapper.readTree(json);
    if (parsed != null && parsed.isArray()) {
      ArrayNode copy = objectMapper.createArrayNode();
      parsed.forEach(copy::add);
      return copy;
    }
    return objectMapper.createArrayNode();
  }

  private void clearContinuationActionState(String runId) {
    continuationProviderSwitchesPerRun.remove(runId);
    String prefix = continuationTaskKey(runId, "");
    continuationModelStepUpsPerTask.keySet().removeIf(key -> key.startsWith(prefix));
    continuationModelStepDownsPerTask.keySet().removeIf(key -> key.startsWith(prefix));
  }

  private static String continuationTaskKey(String runId, String taskId) {
    return (runId == null ? "" : runId) + ":" + (taskId == null ? "" : taskId);
  }

  private void seedInitialContinuationActionState(
      String runId, ExecutionRequest req, ResumeRequest resume) {
    if (runId == null || runId.isBlank() || req == null) {
      return;
    }
    if (req.initialProviderSwitchesUsed() > 0) {
      continuationProviderSwitchesPerRun.put(
          runId, new AtomicInteger(req.initialProviderSwitchesUsed()));
    }
    String targetTaskId = resumeTargetShortTaskId(runId, resume);
    if (targetTaskId == null || targetTaskId.isBlank()) {
      return;
    }
    if (req.initialModelStepUpsUsed() > 0) {
      continuationModelStepUpsPerTask.put(
          continuationTaskKey(runId, targetTaskId), new AtomicInteger(req.initialModelStepUpsUsed()));
    }
    if (req.initialModelStepDownsUsed() > 0) {
      continuationModelStepDownsPerTask.put(
          continuationTaskKey(runId, targetTaskId),
          new AtomicInteger(req.initialModelStepDownsUsed()));
    }
  }

  private static String resumeTargetShortTaskId(String runId, ResumeRequest resume) {
    if (resume == null || resume.targetTaskId() == null || resume.targetTaskId().isBlank()) {
      return null;
    }
    String target = resume.targetTaskId();
    String prefix =
        runId != null && runId.length() >= 8 ? runId.substring(0, 8) + "_" : "";
    return !prefix.isBlank() && target.startsWith(prefix) ? target.substring(prefix.length()) : target;
  }

  private int providerSwitchesUsed(String runId) {
    if (runId == null || runId.isBlank()) {
      return 0;
    }
    AtomicInteger used = continuationProviderSwitchesPerRun.get(runId);
    return used == null ? 0 : Math.max(0, used.get());
  }

  private int modelStepUpsUsed(String runId, String taskId) {
    return continuationTaskCounterValue(continuationModelStepUpsPerTask, runId, taskId);
  }

  private int modelStepDownsUsed(String runId, String taskId) {
    return continuationTaskCounterValue(continuationModelStepDownsPerTask, runId, taskId);
  }

  private int continuationTaskCounterValue(
      ConcurrentMap<String, AtomicInteger> counters, String runId, String taskId) {
    if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank()) {
      return 0;
    }
    AtomicInteger used = counters.get(continuationTaskKey(runId, taskId));
    return used == null ? 0 : Math.max(0, used.get());
  }

  private record ContinuationActionLimit(
      String limitName, int usedAfterReserve, int maxAllowed, boolean allowed) {}

  private static TaskOutcome mergeContinuationAttempt(
      TaskOutcome initial,
      TaskOutcome rerun,
      GoalContinuationPolicyDecision decision,
      ModelProviderScore candidate) {
    if (rerun == null) {
      return initial;
    }
    return TaskOutcome.builder()
        .taskId(rerun.taskId())
        .taskTitle(rerun.taskTitle())
        .status(rerun.status())
        .workerOutcome(rerun.workerOutcome())
        .retryAttempted(rerun.retryAttempted())
        .retryWorkerOutcome(rerun.retryWorkerOutcome())
        .smokeTestPassed(rerun.smokeTestPassed())
        .smokeTestExitCode(rerun.smokeTestExitCode())
        .smokeTestOutput(rerun.smokeTestOutput())
        .totalLatencyMs(initial.totalLatencyMs() + rerun.totalLatencyMs())
        .toolCallsDispatched(initial.toolCallsDispatched() + rerun.toolCallsDispatched())
        .dispatchedProvider(rerun.dispatchedProvider())
        .dispatchedModel(rerun.dispatchedModel())
        .goalDonenessDecision(rerun.goalDonenessDecision())
        .continuationDecision(rerun.continuationDecision())
        .continuationAction(decision == null ? null : decision.decision())
        .continuationActionProvider(candidate == null ? null : candidate.provider())
        .continuationActionModel(candidate == null ? null : candidate.model())
        .build();
  }

  private TaskOutcome executeOne(
      PlannedTask task, Plan plan, ExecutionRequest req, boolean isFinalTask) {
    return executeOne(task, plan, req, isFinalTask, null);
  }

  private TaskOutcome executeOne(
      PlannedTask task,
      Plan plan,
      ExecutionRequest req,
      boolean isFinalTask,
      com.navneet.executespec.context.SavedContext resumeFrom) {
    return executeOne(task, plan, req, isFinalTask, resumeFrom, null);
  }

  private TaskOutcome executeOne(
      PlannedTask task,
      Plan plan,
      ExecutionRequest req,
      boolean isFinalTask,
      com.navneet.executespec.context.SavedContext resumeFrom,
      ProviderModelOverride providerModelOverride) {
    long taskStart = System.currentTimeMillis();
    ExecutionContext taskCtx =
        new ExecutionContext(req.runContext().runId(), task.id(), req.runContext().workspaceRoot());
    String requestedProvider =
        providerModelOverride == null ? req.provider() : providerModelOverride.provider();
    String requestedModel =
        providerModelOverride == null ? req.model() : providerModelOverride.model();

    String refDocs = fetchReferenceDocs(req.projectId(), task);
    String systemPrompt =
        composeSystemPrompt(
            task,
            req.designBlock(),
            refDocs,
            req.plan() == null ? null : req.plan().projectType(),
            req.workspaceSummary(),
            requestedProvider,
            req.localHandsMode());
    String userPrompt = composeUserPrompt(task, null);

    log.info("Executor task={} ('{}') START", task.id(), task.title());
    String runId = req.runContext().runId();
    persistTaskStatus(runId, task.id(), "WORKING");
    broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "WORKING"));
    broadcaster.emit(runId, RunEvent.log(runId, task.id(), "Starting task: " + task.title()));
    TierConfig cfg =
        tierConfigFor(
            task.complexity(), requestedProvider, requestedModel, req.projectId() != null);
    if (providerModelOverride != null) {
      cfg = cfg.withProviderModel(providerModelOverride.provider(), providerModelOverride.model());
    }
    // 2026-05-04 — quirky pre-dispatch log so the Live Log tab has more than just
    // token counters during long worker loops. Format mirrors the "Starting task:" line so
    // the rendering stays uniform; copy varies by complexity to surface what the user is
    // actually waiting for (HIGH = LLM-heavy reasoning, LOW = quick code edits).
    String complexityHint = task.complexity() == null ? "MEDIUM" : task.complexity().name();
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            task.id(),
            switch (complexityHint) {
              case "LOW" ->
                  "🛠️  Worker spinning up (LOW · "
                      + cfg.model
                      + ", up to "
                      + cfg.maxIter
                      + " iters)";
              case "HIGH" ->
                  "🧠 Worker spinning up (HIGH · "
                      + cfg.model
                      + ", up to "
                      + cfg.maxIter
                      + " iters — heavy reasoning ahead)";
              default ->
                  "⚙️  Worker spinning up (MEDIUM · "
                      + cfg.model
                      + ", up to "
                      + cfg.maxIter
                      + " iters)";
            }));
    WorkerTaskRequest workerReq =
        WorkerTaskRequest.builder()
            .provider(cfg.provider)
            .model(cfg.model)
            .systemPrompt(systemPrompt)
            .userPrompt(userPrompt)
            .context(taskCtx)
            .maxIterations(
                req.maxIterationsPerTask() != null ? req.maxIterationsPerTask() : cfg.maxIter)
            .temperature(0.0)
            .maxTokensPerCall(cfg.maxTokens)
            .stagnationWarnAfter(cfg.stagnationWarnAfter)
            .stagnationForceAfter(cfg.stagnationForceAfter)
            .wallClockSeconds(cfg.wallClockSeconds)
            .forceToolChoiceOnSalvage(cfg.forceToolChoiceOnSalvage)
            // VBC verify-before-complete (RL-2026-06-…, V235, default OFF) — server-RUN the
            // project's tests at completeTask and refuse if red. Gated to the FINAL task (mirrors
            // the smoke gate at executeOne's multiTask check) so an intermediate task isn't blocked
            // by a later task's not-yet-implemented tests. Decoupled from V217 (runner called
            // directly), no model-visible schema change. Byte-identical until the flag flips.
            .verifyBeforeComplete(
                isFinalTask
                    && featureFlagService != null
                    && featureFlagService.isEnabled("worker.verify-before-complete.enabled"))
            .build();
    // Resume-aware dispatch — when resumeFrom is non-null, the worker rebuilds history from the
    // saved snapshot and resumes the iteration loop from snapshot.iteration() + 1. Snapshots
    // ride a 24h Redis TTL and are written every 5 worker iters.
    WorkerResult primary = worker.run(workerReq, resumeFrom);

    // 2026-05-04 — emit a worker-summary log line right after the worker returns so the user
    // sees outcome + iters without waiting for the smoke test to finish or scrolling back to
    // the task badge. Format mirrors the "Starting task:" line.
    String workerEmoji =
        switch (primary.outcome()) {
          case COMPLETED -> "✅";
          case COMPLETED_BY_STAGNATION -> "⏱️ ";
          case ITERATION_LIMIT_EXCEEDED -> "⚠️ ";
          case WALL_CLOCK_EXCEEDED -> "⏰";
          case STOPPED_WITHOUT_COMPLETE -> "⚠️ ";
          case BUDGET_EXCEEDED -> "💸";
          case ERROR -> "❌";
        };
    int writes = countFileWrites(primary.toolCallLog());
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            task.id(),
            workerEmoji
                + " Worker "
                + primary.outcome().name()
                + " in "
                + primary.iterations()
                + " iters · "
                + primary.toolCallsDispatched()
                + " tool calls"
                + (writes > 0 ? " · " + writes + " file write" + (writes == 1 ? "" : "s") : "")
                + (primary.totalLatencyMs() > 0
                    ? " · " + (primary.totalLatencyMs() / 1000) + "s"
                    : "")));

    log.info(
        "Executor task={} toolCallStats provider={} model={} complexity={} outcome={} iters={}"
            + " toolCalls={} fileWrites={}",
        task.id(),
        cfg.provider,
        cfg.model,
        task.complexity() == null ? "MEDIUM" : task.complexity().name(),
        primary.outcome(),
        primary.iterations(),
        primary.toolCallsDispatched(),
        countFileWrites(primary.toolCallLog()));

    // Worker didn't reach COMPLETED. Check whether it still wrote files — if so
    // the task produced real output and shouldn't be marked FAILED outright.
    // Common case: Gemini hits its iteration cap on HIGH tasks but has already
    // written the schema / entities / controller and just failed to call
    // `completeTask`. Marking these FAILED made the run look broken even though
    // the code was fine, and tanked the accuracy score.
    //
    // Fix C (2026-05-03, run a076c876 T2 diagnosis): when the worker wrote
    // files but didn't call completeTask, do NOT pre-mark PARTIAL. Fall
    // through to the SAME gate the COMPLETED path uses (multi-task skip /
    // smoke test) and let that decide between COMPLETE / COMPLETED_WITH_WARNING
    // / PARTIAL. The worker's premature-exit reason is carried in
    // softWarnReason and persisted on lastLlmFailure regardless of final
    // status, so the UI still surfaces "worker hit iter cap" even when the
    // task lands COMPLETE because the smoke passed. Without this an
    // intermediate task in a multi-task plan that the worker exited early
    // on would be marked PARTIAL even though the reviewer's final build is
    // the authoritative gate — exactly what happened to T2.
    final boolean softCompletion;
    final String softWarnReason;
    if (primary.outcome() != WorkerResult.Outcome.COMPLETED) {
      int fileWrites = countFileWrites(primary.toolCallLog());
      if (fileWrites == 0) {
        log.warn(
            "Executor task={} WORKER_FAILED outcome={} iters={} (no file writes)",
            task.id(),
            primary.outcome(),
            primary.iterations());
        String failureReason = describeWorkerFailure(primary);
        // v2.5.12 — populate a concrete failureReason for every terminal
        // worker outcome so RunMapper doesn't have to fall back to
        // "unknown — see worker logs". This closes Bug D from the eval.
        // V190 (2026-05-13, PR-A) — also persist the structured diagnostic JSON so the UI can
        // surface "outcome=STOPPED_WITHOUT_COMPLETE, iters=47/75, last tool call: writeFile
        // app/x.py failed" without operators SSHing the VM log.
        persistTaskStatus(
            runId,
            task.id(),
            "FAILED",
            failureReason,
            buildFailureDiagnosticJson(primary, cfg.maxIter));
        broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "FAILED"));
        GoalTaskDecision goalDecision =
            emitGoalDecision(
                runId,
                task,
                primary,
                TaskOutcome.Status.WORKER_FAILED,
                null,
                failureReason,
                cfg.provider,
                cfg.model);
        return TaskOutcome.builder()
            .taskId(task.id())
            .taskTitle(task.title())
            .status(TaskOutcome.Status.WORKER_FAILED)
            .workerOutcome(primary)
            .totalLatencyMs(System.currentTimeMillis() - taskStart)
            .toolCallsDispatched(primary.toolCallsDispatched())
            .dispatchedProvider(cfg.provider)
            .dispatchedModel(cfg.model)
            .goalDonenessDecision(goalDecision.doneness())
            .continuationDecision(goalDecision.continuation())
            .build();
      }
      // Soft completion: worker wrote files; fall through to smoke gate.
      log.warn(
          "Executor task={} worker outcome={} but wrote {} files — falling through to"
              + " smoke gate (soft completion)",
          task.id(),
          primary.outcome(),
          fileWrites);
      softCompletion = true;
      softWarnReason =
          "Worker stopped without completion but wrote "
              + fileWrites
              + " file"
              + (fileWrites == 1 ? "" : "s")
              + " — "
              + describeWorkerFailure(primary);
    } else {
      softCompletion = false;
      softWarnReason = null;
    }

    if (!req.enableSmokeTests()) {
      TaskOutcome.Status status =
          softCompletion ? TaskOutcome.Status.COMPLETED_WITH_WARNING : TaskOutcome.Status.SUCCESS;
      log.info(
          "Executor task={} {}{}",
          task.id(),
          softCompletion ? "COMPLETED_WITH_WARNING" : "SUCCESS",
          " (smoke tests disabled)");
      persistTaskStatus(runId, task.id(), "COMPLETE", softWarnReason);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      GoalTaskDecision goalDecision =
          emitGoalDecision(
              runId, task, primary, status, null, softWarnReason, cfg.provider, cfg.model);
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(status)
          .workerOutcome(primary)
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
          .goalDonenessDecision(goalDecision.doneness())
          .continuationDecision(goalDecision.continuation())
          .build();
    }

    // Skip per-task smoke for non-final tasks in multi-task plans:
    // intermediate tasks (T1 scaffold, T2 partial wiring, ...) cannot
    // possibly satisfy a full project build because later tasks haven't
    // landed yet. The reviewer's final build at the end of the run is
    // the authoritative gate. This eliminates false-negative PARTIAL
    // statuses on tasks whose code is later wired up correctly.
    boolean multiTask = plan.tasks().size() > 1;
    if (multiTask && !isFinalTask) {
      TaskOutcome.Status status =
          softCompletion ? TaskOutcome.Status.COMPLETED_WITH_WARNING : TaskOutcome.Status.SUCCESS;
      log.info(
          "Executor task={} {} (intermediate smoke skipped — multi-task plan, final task +"
              + " reviewer are authoritative)",
          task.id(),
          softCompletion ? "COMPLETED_WITH_WARNING" : "SUCCESS");
      persistTaskStatus(runId, task.id(), "COMPLETE", softWarnReason);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      GoalTaskDecision goalDecision =
          emitGoalDecision(
              runId, task, primary, status, null, softWarnReason, cfg.provider, cfg.model);
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(status)
          .workerOutcome(primary)
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
          .goalDonenessDecision(goalDecision.doneness())
          .continuationDecision(goalDecision.continuation())
          .build();
    }

    SmokeTestResult smoke = runSmokeTest(taskCtx, plan.buildCommand());
    if (smoke.passed()) {
      TaskOutcome.Status status =
          softCompletion ? TaskOutcome.Status.COMPLETED_WITH_WARNING : TaskOutcome.Status.SUCCESS;
      log.info(
          "Executor task={} {} (smoke exit={}, took {}ms)",
          task.id(),
          softCompletion ? "COMPLETED_WITH_WARNING" : "SUCCESS",
          smoke.exitCode(),
          smoke.latencyMs());
      persistTaskStatus(runId, task.id(), "COMPLETE", softWarnReason);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      GoalTaskDecision goalDecision =
          emitGoalDecision(
              runId, task, primary, status, true, softWarnReason, cfg.provider, cfg.model);
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(status)
          .workerOutcome(primary)
          .smokeTestPassed(true)
          .smokeTestExitCode(smoke.exitCode())
          .smokeTestOutput(smoke.output())
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
          .goalDonenessDecision(goalDecision.doneness())
          .continuationDecision(goalDecision.continuation())
          .build();
    }

    // Smoke FAILED on the primary attempt. The retry logic below was designed
    // for the COMPLETED path. When we got here via a soft completion (worker
    // didn't call completeTask), we don't retry — the worker already burned
    // its budget. Mark the task PARTIAL with both the soft-completion warning
    // and the smoke failure so the user sees the full picture.
    if (softCompletion) {
      String smokeTail = smoke.output() == null ? "" : smoke.output();
      if (smokeTail.length() > 240) {
        smokeTail = smokeTail.substring(smokeTail.length() - 240);
      }
      String reason =
          softWarnReason
              + ". Build smoke also failed (exit "
              + smoke.exitCode()
              + ")"
              + (smokeTail.isBlank() ? "" : " — " + smokeTail.trim().replaceAll("\\s+", " "));
      log.warn("Executor task={} soft-completion + smoke failed; marking PARTIAL", task.id());
      persistTaskStatus(runId, task.id(), "PARTIAL", reason);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "PARTIAL"));
      GoalTaskDecision goalDecision =
          emitGoalDecision(
              runId,
              task,
              primary,
              TaskOutcome.Status.COMPLETED_WITH_WARNING,
              false,
              reason,
              cfg.provider,
              cfg.model);
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
          .workerOutcome(primary)
          .smokeTestPassed(false)
          .smokeTestExitCode(smoke.exitCode())
          .smokeTestOutput(smoke.output())
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
          .goalDonenessDecision(goalDecision.doneness())
          .continuationDecision(goalDecision.continuation())
          .build();
    }

    TaskOutcome continuationBeforeRetry =
        maybeActOnContinuationBeforeSmokeRetry(
            task, plan, req, isFinalTask, taskStart, primary, smoke, cfg);
    if (continuationBeforeRetry != null) {
      return continuationBeforeRetry;
    }

    log.warn(
        "Executor task={} smoke test FAILED (exit={}), retrying once with error context",
        task.id(),
        smoke.exitCode());

    // ONE retry with the build error injected.
    // 2026-05-08 — set requireBuildVerification on the RETRY only. The first attempt keeps
    // today's behaviour (no gate, since some legit early scaffold tasks naturally don't have
    // a building project yet). On retry though, the build error is the explicit spec — the
    // worker MUST re-run buildProject and see exit=0 before claiming done. Without this gate
    // the retry can call completeTask after a single editFile and we get the same regression
    // path that produced the 16/22 FINAL_BUILD_FAILED rate over the last 30 days.
    String retryPrompt = composeUserPrompt(task, smoke.output());
    // PR-D (2026-05-13) — adaptive iter budget on retry. If the primary attempt exhausted its
    // iter or wall-clock budget, give the retry +50% headroom — the smoke-test feedback path
    // depends on the worker actually completing, and burning the same budget twice on a
    // genuinely-larger-than-MEDIUM task is the exact wasteful-retry pattern the user reported.
    // Keep the boost capped at 1.5× so a runaway worker can't escalate unbounded; the
    // user-cost-cap remains the hard ceiling regardless.
    int retryMaxIter =
        req.maxIterationsPerTask() != null ? req.maxIterationsPerTask() : cfg.maxIter;
    int retryWallClock = cfg.wallClockSeconds;
    boolean primaryHitIterCap = primary.outcome() == WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED;
    boolean primaryHitWallClock = primary.outcome() == WorkerResult.Outcome.WALL_CLOCK_EXCEEDED;
    if (primaryHitIterCap) {
      retryMaxIter = Math.max(retryMaxIter, (int) Math.ceil(cfg.maxIter * 1.5));
      log.info(
          "Executor task={} primary exhausted iter cap ({} iter); retry boosted to {} iter",
          task.id(),
          cfg.maxIter,
          retryMaxIter);
    }
    if (primaryHitWallClock) {
      retryWallClock = (int) Math.ceil(cfg.wallClockSeconds * 1.5);
      log.info(
          "Executor task={} primary exhausted wall-clock ({}s); retry boosted to {}s",
          task.id(),
          cfg.wallClockSeconds,
          retryWallClock);
    }
    // 2026-05-28 (RL-2026-05-052, T3.14) — context retention across retries. When the flag is
    // ON, load the worker's prior context-snapshot so the retry continues from where it broke
    // (re-issuing the failed build's edit on top of the prior context) instead of starting fresh
    // and re-reading every file. Existing snapshot save happens every 5 iters inside
    // WorkerAgent; this just wires the LOAD on the retry path. Flag-gated default OFF (V221).
    WorkerTaskRequest retryReq =
        WorkerTaskRequest.builder()
            .provider(cfg.provider)
            .model(cfg.model)
            .systemPrompt(systemPrompt)
            .userPrompt(retryPrompt)
            .context(taskCtx)
            .maxIterations(retryMaxIter)
            .temperature(0.0)
            .maxTokensPerCall(cfg.maxTokens)
            .stagnationWarnAfter(cfg.stagnationWarnAfter)
            .stagnationForceAfter(cfg.stagnationForceAfter)
            .wallClockSeconds(retryWallClock)
            .requireBuildVerification(true)
            .forceToolChoiceOnSalvage(cfg.forceToolChoiceOnSalvage)
            .build();
    WorkerResult retry;
    if (featureFlagService != null && featureFlagService.isEnabled(FLAG_RETRY_CONTEXT_RETENTION)) {
      java.util.Optional<com.navneet.executespec.context.SavedContext> snapshot =
          contextStore == null ? java.util.Optional.empty() : contextStore.load(taskCtx);
      if (snapshot.isPresent()) {
        log.info(
            "Executor task={} retry CONTEXT-RETAINED from snapshot (iter={})",
            task.id(),
            snapshot.get().iteration());
        retry = worker.run(retryReq, snapshot.get());
      } else {
        log.debug("Executor task={} retry context-retention flag ON but no snapshot", task.id());
        retry = worker.run(retryReq);
      }
    } else {
      retry = worker.run(retryReq);
    }

    int totalToolCalls = primary.toolCallsDispatched() + retry.toolCallsDispatched();
    if (retry.outcome() != WorkerResult.Outcome.COMPLETED) {
      // 2026-05-28 (RL-2026-05-052, T3.9) — cross-provider fallback. After the existing
      // same-provider retry fails, try ONCE on the next provider in the failover chain. This
      // captures cases where one provider can't solve the task but another can (mixed10 showed
      // this — different arms solved different cases). Hard ceiling: max one cross-provider
      // retry per task; max MAX_CROSS_PROVIDER_PER_RUN per run; flag-gated default OFF (V220).
      CrossProviderRetryResult crossProviderRetry =
          maybeCrossProviderRetry(task, plan, req, cfg, taskCtx, systemPrompt, smoke, retry);
      if (crossProviderRetry != null
          && crossProviderRetry.result().outcome() == WorkerResult.Outcome.COMPLETED) {
        SmokeTestResult crossSmoke = runSmokeTest(taskCtx, plan.buildCommand());
        totalToolCalls += crossProviderRetry.result().toolCallsDispatched();
        if (crossSmoke.passed()) {
          log.info(
              "Executor task={} SUCCESS_AFTER_CROSS_PROVIDER_RETRY (fallback provider={})",
              task.id(),
              crossProviderRetry.provider());
          persistTaskStatus(runId, task.id(), "COMPLETE");
          broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
          GoalTaskDecision goalDecision =
              emitGoalDecision(
                  runId,
                  task,
                  crossProviderRetry.result(),
                  TaskOutcome.Status.SUCCESS_AFTER_RETRY,
                  true,
                  null,
                  crossProviderRetry.provider(),
                  crossProviderRetry.model());
          return TaskOutcome.builder()
              .taskId(task.id())
              .taskTitle(task.title())
              .status(TaskOutcome.Status.SUCCESS_AFTER_RETRY)
              .workerOutcome(primary)
              .retryAttempted(true)
              .retryWorkerOutcome(crossProviderRetry.result())
              .smokeTestPassed(true)
              .smokeTestExitCode(crossSmoke.exitCode())
              .smokeTestOutput(crossSmoke.output())
              .totalLatencyMs(System.currentTimeMillis() - taskStart)
              .toolCallsDispatched(totalToolCalls)
              .dispatchedProvider(crossProviderRetry.provider())
              .dispatchedModel(crossProviderRetry.model())
              .goalDonenessDecision(goalDecision.doneness())
              .continuationDecision(goalDecision.continuation())
              .build();
        }
      } else if (crossProviderRetry != null) {
        totalToolCalls += crossProviderRetry.result().toolCallsDispatched();
      }
      log.warn(
          "Executor task={} retry WORKER_FAILED outcome={}; marking COMPLETED_WITH_WARNING",
          task.id(),
          retry.outcome());
      String retryFailureReason = describeWorkerFailure(retry);
      GoalTaskDecision goalDecision =
          emitGoalDecision(
              runId,
              task,
              retry,
              TaskOutcome.Status.COMPLETED_WITH_WARNING,
              false,
              retryFailureReason,
              cfg.provider,
              cfg.model);
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
          .workerOutcome(primary)
          .retryAttempted(true)
          .retryWorkerOutcome(retry)
          .smokeTestPassed(false)
          .smokeTestExitCode(smoke.exitCode())
          .smokeTestOutput(smoke.output())
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(totalToolCalls)
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
          .goalDonenessDecision(goalDecision.doneness())
          .continuationDecision(goalDecision.continuation())
          .build();
    }

    // Retry completed — re-smoke.
    SmokeTestResult retrySmoke = runSmokeTest(taskCtx, plan.buildCommand());
    if (retrySmoke.passed()) {
      log.info("Executor task={} SUCCESS_AFTER_RETRY", task.id());
      persistTaskStatus(runId, task.id(), "COMPLETE");
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      GoalTaskDecision goalDecision =
          emitGoalDecision(
              runId,
              task,
              retry,
              TaskOutcome.Status.SUCCESS_AFTER_RETRY,
              true,
              null,
              cfg.provider,
              cfg.model);
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.SUCCESS_AFTER_RETRY)
          .workerOutcome(primary)
          .retryAttempted(true)
          .retryWorkerOutcome(retry)
          .smokeTestPassed(true)
          .smokeTestExitCode(retrySmoke.exitCode())
          .smokeTestOutput(retrySmoke.output())
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(totalToolCalls)
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
          .goalDonenessDecision(goalDecision.doneness())
          .continuationDecision(goalDecision.continuation())
          .build();
    }

    // If the primary + retry worker wrote zero files, the task did not change
    // the codebase — a smoke failure here is almost always environmental
    // (wrong directory, missing toolchain, flaky network). Downgrading to
    // COMPLETE avoids poisoning the run's overall status on a tautological
    // verify-only task. The smoke output is still preserved on the outcome
    // so the reviewer / report can surface it if relevant.
    int totalFileWrites =
        countFileWrites(primary.toolCallLog()) + countFileWrites(retry.toolCallLog());
    if (totalFileWrites == 0) {
      log.warn(
          "Executor task={} smoke failed after retry but worker wrote 0 files — "
              + "demoting to COMPLETE (verify-only task, smoke failure is environmental)",
          task.id());
      persistTaskStatus(runId, task.id(), "COMPLETE");
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      GoalTaskDecision goalDecision =
          emitGoalDecision(
              runId,
              task,
              retry,
              TaskOutcome.Status.SUCCESS,
              false,
              "Smoke failed after retry but no files were written; treated as"
                  + " verify-only/environmental",
              cfg.provider,
              cfg.model);
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.SUCCESS)
          .workerOutcome(primary)
          .retryAttempted(true)
          .retryWorkerOutcome(retry)
          .smokeTestPassed(false)
          .smokeTestExitCode(retrySmoke.exitCode())
          .smokeTestOutput(retrySmoke.output())
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(totalToolCalls)
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
          .goalDonenessDecision(goalDecision.doneness())
          .continuationDecision(goalDecision.continuation())
          .build();
    }

    // 2026-05-08 — HIGH-complexity tasks get a SECOND retry with the latest build error.
    // Spring Boot multi-file scaffolds and similar heavy work occasionally need 2 build-error
    // feedback loops to converge (LOW/MEDIUM tasks rarely benefit; their state space is small
    // enough that retry-1 is decisive). retry-2 reuses the same requireBuildVerification gate
    // so the worker MUST run buildProject and see exit=0 before claiming done. Cost ceiling
    // is the per-run cost cap which absorbs the extra retry budget.
    if (task.complexity() == PlannedTask.Complexity.HIGH) {
      log.warn(
          "Executor task={} HIGH-complexity, smoke still fails after retry-1; attempting retry-2",
          task.id());
      String retry2Prompt = composeUserPrompt(task, retrySmoke.output());
      WorkerResult retry2 =
          worker.run(
              WorkerTaskRequest.builder()
                  .provider(cfg.provider)
                  .model(cfg.model)
                  .systemPrompt(systemPrompt)
                  .userPrompt(retry2Prompt)
                  .context(taskCtx)
                  .maxIterations(
                      req.maxIterationsPerTask() != null ? req.maxIterationsPerTask() : cfg.maxIter)
                  .temperature(0.0)
                  .maxTokensPerCall(cfg.maxTokens)
                  .stagnationWarnAfter(cfg.stagnationWarnAfter)
                  .stagnationForceAfter(cfg.stagnationForceAfter)
                  .wallClockSeconds(cfg.wallClockSeconds)
                  .requireBuildVerification(true)
                  .forceToolChoiceOnSalvage(cfg.forceToolChoiceOnSalvage)
                  .build());
      int totalToolCalls2 = totalToolCalls + retry2.toolCallsDispatched();
      if (retry2.outcome() == WorkerResult.Outcome.COMPLETED) {
        SmokeTestResult retry2Smoke = runSmokeTest(taskCtx, plan.buildCommand());
        if (retry2Smoke.passed()) {
          log.info("Executor task={} SUCCESS_AFTER_RETRY2 (HIGH complexity)", task.id());
          persistTaskStatus(runId, task.id(), "COMPLETE");
          broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
          GoalTaskDecision goalDecision =
              emitGoalDecision(
                  runId,
                  task,
                  retry2,
                  TaskOutcome.Status.SUCCESS_AFTER_RETRY,
                  true,
                  null,
                  cfg.provider,
                  cfg.model);
          return TaskOutcome.builder()
              .taskId(task.id())
              .taskTitle(task.title())
              .status(TaskOutcome.Status.SUCCESS_AFTER_RETRY)
              .workerOutcome(primary)
              .retryAttempted(true)
              .retryWorkerOutcome(retry2)
              .smokeTestPassed(true)
              .smokeTestExitCode(retry2Smoke.exitCode())
              .smokeTestOutput(retry2Smoke.output())
              .totalLatencyMs(System.currentTimeMillis() - taskStart)
              .toolCallsDispatched(totalToolCalls2)
              .dispatchedProvider(cfg.provider)
              .dispatchedModel(cfg.model)
              .goalDonenessDecision(goalDecision.doneness())
              .continuationDecision(goalDecision.continuation())
              .build();
        }
        // retry-2 completed but smoke STILL fails → fall through to COMPLETED_WITH_WARNING
        // below with the retry-2 smoke output as the "after retry" signal.
        retrySmoke = retry2Smoke;
        retry = retry2;
        totalToolCalls = totalToolCalls2;
      } else {
        log.warn(
            "Executor task={} retry-2 WORKER_FAILED outcome={}; falling through to"
                + " COMPLETED_WITH_WARNING with retry-1 smoke as final signal",
            task.id(),
            retry2.outcome());
        totalToolCalls = totalToolCalls2;
      }
    }

    log.warn("Executor task={} smoke still fails after retry; COMPLETED_WITH_WARNING", task.id());
    // 2026-05-02 — surface the smoke failure inline on the task card. Use exit code +
    // a tail of the smoke output so the user sees something like "Build smoke failed
    // (exit 1): SHELL BLOCKED 'bun' in: cd repos/frontend && bun install" instead of a
    // bare PARTIAL with no explanation.
    String smokeTail = retrySmoke.output() == null ? "" : retrySmoke.output();
    if (smokeTail.length() > 240) {
      smokeTail = smokeTail.substring(smokeTail.length() - 240);
    }
    String reason =
        "Build smoke failed (exit "
            + retrySmoke.exitCode()
            + ") even after one retry"
            + (smokeTail.isBlank() ? "" : " — " + smokeTail.trim().replaceAll("\\s+", " "));
    persistTaskStatus(runId, task.id(), "PARTIAL", reason);
    broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "PARTIAL"));
    GoalTaskDecision goalDecision =
        emitGoalDecision(
            runId,
            task,
            retry,
            TaskOutcome.Status.COMPLETED_WITH_WARNING,
            false,
            reason,
            cfg.provider,
            cfg.model);
    return TaskOutcome.builder()
        .taskId(task.id())
        .taskTitle(task.title())
        .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
        .workerOutcome(primary)
        .retryAttempted(true)
        .retryWorkerOutcome(retry)
        .smokeTestPassed(false)
        .smokeTestExitCode(retrySmoke.exitCode())
        .smokeTestOutput(retrySmoke.output())
        .totalLatencyMs(System.currentTimeMillis() - taskStart)
        .toolCallsDispatched(totalToolCalls)
        .dispatchedProvider(cfg.provider)
        .dispatchedModel(cfg.model)
        .goalDonenessDecision(goalDecision.doneness())
        .continuationDecision(goalDecision.continuation())
        .build();
  }

  private GoalTaskDecision emitGoalDecision(
      String runId,
      PlannedTask task,
      WorkerResult workerResult,
      TaskOutcome.Status finalStatus,
      Boolean smokePassed,
      String failureReason,
      String dispatchedProvider,
      String dispatchedModel) {
    GoalDonenessDecision decision =
        goalDonenessEvaluator.evaluate(
            buildGoalEvidence(runId, task, workerResult, finalStatus, smokePassed, failureReason));
    GoalContinuationPolicyDecision continuationDecision =
        decideContinuation(
            runId,
            task,
            decision,
            dispatchedProvider,
            dispatchedModel,
            providerBehaviorPoor(workerResult, failureReason));
    emitGoalAndContinuationDecision(runId, task, decision, continuationDecision);
    return new GoalTaskDecision(decision, continuationDecision);
  }

  private void emitGoalAndContinuationDecision(
      String runId,
      PlannedTask task,
      GoalDonenessDecision decision,
      GoalContinuationPolicyDecision continuationDecision) {
    log.info(
        "Executor task={} goalDecision state={} decision={} reason={}",
        task.id(),
        decision.state(),
        decision.recommendedDecision(),
        decision.reason());
    String goalSummary =
        "Goal decision: "
            + decision.state()
            + " -> "
            + decision.recommendedDecision()
            + " ("
            + decision.reason()
            + ")";
    broadcaster.emit(
        runId, RunEvent.goalDecision(runId, task.id(), goalSummary, goalDecisionData(decision)));
    broadcaster.emit(runId, RunEvent.log(runId, task.id(), goalSummary));
    log.info(
        "Executor task={} continuationDecision decision={} reason={}",
        task.id(),
        continuationDecision.decision(),
        firstReason(continuationDecision));
    String continuationSummary =
        "Continuation decision: "
            + continuationDecision.decision()
            + " ("
            + firstReason(continuationDecision)
            + ")";
    broadcaster.emit(
        runId,
        RunEvent.continuationDecision(
            runId, task.id(), continuationSummary, continuationDecisionData(continuationDecision)));
    broadcaster.emit(runId, RunEvent.log(runId, task.id(), continuationSummary));
    persistGoalAndContinuationDecision(runId, task.id(), decision, continuationDecision);
  }

  private TaskOutcome maybeActOnContinuationBeforeSmokeRetry(
      PlannedTask task,
      Plan plan,
      ExecutionRequest req,
      boolean isFinalTask,
      long taskStart,
      WorkerResult primary,
      SmokeTestResult smoke,
      TierConfig cfg) {
    if (!continuationActingEnabled || maxContinuationReruns() <= 0 || primary == null) {
      return null;
    }
    String runId = req.runContext().runId();
    String reason = smokeFailureReason(smoke, "before same-model retry");
    GoalDonenessDecision goalDecision =
        goalDonenessEvaluator.evaluate(
            buildGoalEvidence(
                runId,
                task,
                primary,
                TaskOutcome.Status.COMPLETED_WITH_WARNING,
                false,
                reason));
    GoalContinuationPolicyDecision continuationDecision =
        decideContinuation(
            runId,
            task,
            goalDecision,
            cfg.provider,
            cfg.model,
            providerBehaviorPoor(primary, reason));
    if (approvalBlockedContinuationProposal(continuationDecision)) {
      persistTaskStatus(runId, task.id(), "PARTIAL", reason);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "PARTIAL"));
      emitGoalAndContinuationDecision(runId, task, goalDecision, continuationDecision);
      return preRetryContinuationOutcome(
          task, taskStart, primary, smoke, cfg, goalDecision, continuationDecision);
    }
    if (!actionableContinuationDecision(continuationDecision)) {
      return null;
    }
    ModelProviderScore candidate = continuationDecision.selectedCandidate().orElse(null);
    if (candidate == null || sameProviderModel(candidate, cfg.provider, cfg.model)) {
      return null;
    }
    if (!continuationActionCapacityAvailable(runId, task.id(), continuationDecision.decision())) {
      return null;
    }
    emitGoalAndContinuationDecision(runId, task, goalDecision, continuationDecision);
    TaskOutcome preRetryOutcome =
        preRetryContinuationOutcome(
            task, taskStart, primary, smoke, cfg, goalDecision, continuationDecision);
    return executeContinuationAction(
        task, plan, req, isFinalTask, preRetryOutcome, continuationDecision, candidate, false);
  }

  private static boolean approvalBlockedContinuationProposal(
      GoalContinuationPolicyDecision decision) {
    return decision != null
        && decision.decision() == RunContinuationDecision.STOP_BLOCKED
        && decision.selectedCandidate().isPresent();
  }

  private static TaskOutcome preRetryContinuationOutcome(
      PlannedTask task,
      long taskStart,
      WorkerResult primary,
      SmokeTestResult smoke,
      TierConfig cfg,
      GoalDonenessDecision goalDecision,
      GoalContinuationPolicyDecision continuationDecision) {
    return TaskOutcome.builder()
        .taskId(task.id())
        .taskTitle(task.title())
        .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
        .workerOutcome(primary)
        .smokeTestPassed(false)
        .smokeTestExitCode(smoke.exitCode())
        .smokeTestOutput(smoke.output())
        .totalLatencyMs(System.currentTimeMillis() - taskStart)
        .toolCallsDispatched(primary.toolCallsDispatched())
        .dispatchedProvider(cfg.provider)
        .dispatchedModel(cfg.model)
        .goalDonenessDecision(goalDecision)
        .continuationDecision(continuationDecision)
        .build();
  }

  private boolean continuationActionCapacityAvailable(
      String runId, String taskId, RunContinuationDecision decision) {
    return switch (decision) {
      case CHANGE_PROVIDER -> providerSwitchesUsed(runId) < maxProviderSwitches();
      case STEP_UP_MODEL -> modelStepUpsUsed(runId, taskId) < maxModelStepUps();
      case STEP_DOWN_MODEL -> modelStepDownsUsed(runId, taskId) < maxModelStepDowns();
      case CONTINUE_SAME_MODEL_PROVIDER, STOP_DONE, STOP_BLOCKED, STOP_FAILED, STOP_WASTE_GUARD ->
          false;
    };
  }

  private static String smokeFailureReason(SmokeTestResult smoke, String phase) {
    String smokeTail = smoke == null || smoke.output() == null ? "" : smoke.output();
    if (smokeTail.length() > 240) {
      smokeTail = smokeTail.substring(smokeTail.length() - 240);
    }
    String suffix = smokeTail.isBlank() ? "" : " — " + smokeTail.trim().replaceAll("\\s+", " ");
    return "Build smoke failed (exit "
        + (smoke == null ? "unknown" : smoke.exitCode())
        + ") "
        + phase
        + suffix;
  }

  private GoalContinuationPolicyDecision decideContinuation(
      String runId,
      PlannedTask task,
      GoalDonenessDecision decision,
      String provider,
      String model,
      boolean providerBehaviorPoor) {
    String taskCategory = taskCategory(task);
    double requiredQualityThreshold = requiredGoalQualityThreshold(taskCategory);
    boolean confirmationRequired = requireConfirmationBeforeExpensiveContinuation(runId);
    ContinuationConfirmationScope confirmationScope = continuationConfirmationScope(runId);
    int providerSwitchesUsed = providerSwitchesUsed(runId);
    int modelStepUpsUsed = modelStepUpsUsed(runId, task.id());
    int modelStepDownsUsed = modelStepDownsUsed(runId, task.id());
    if (goalContinuationAdvisor == null) {
      return decideContinuationFallback(
          decision,
          taskCategory,
          requiredQualityThreshold,
          confirmationRequired,
          providerBehaviorPoor,
          confirmationScope,
          providerSwitchesUsed,
          modelStepUpsUsed,
          modelStepDownsUsed);
    }
    try {
      GoalContinuationPolicyDecision advised =
          goalContinuationAdvisor.advise(
              GoalContinuationAdvisorRequest.builder()
                  .goalDecision(decision)
                  .currentProvider(provider)
                  .currentModel(model)
                  .taskCategory(taskCategory)
                  .requiredQualityThreshold(requiredQualityThreshold)
                  .providerAvailability(providerAvailability(runId))
                  .providerPreferenceOrder(providerPreferenceOrder(provider))
                  .qualityInsufficient(decision != null && decision.anotherModelProviderMightHelp())
                  .providerBehaviorPoor(providerBehaviorPoor)
                  .expensiveContinuationRequiresConfirmation(confirmationRequired)
                  .benchmarkQualityPriorRequiredForAutomaticContinuation(
                      requireBenchmarkPriorForAutomaticContinuation)
                  .userConfirmedExpensiveContinuation(confirmationScope.confirmed())
                  .userConfirmedContinuationAction(confirmationScope.action())
                  .userConfirmedContinuationProvider(confirmationScope.provider())
                  .userConfirmedContinuationModel(confirmationScope.model())
                  .providerSwitchesUsed(providerSwitchesUsed)
                  .maxProviderSwitches(maxProviderSwitches())
                  .modelStepUpsUsed(modelStepUpsUsed)
                  .maxModelStepUps(maxModelStepUps())
                  .modelStepDownsUsed(modelStepDownsUsed)
                  .maxModelStepDowns(maxModelStepDowns())
                  .build());
      return withAppliedPolicy(
          advised,
          taskCategory,
          requiredQualityThreshold,
          confirmationRequired,
          providerBehaviorPoor,
          confirmationScope,
          providerSwitchesUsed,
          modelStepUpsUsed,
          modelStepDownsUsed);
    } catch (RuntimeException ex) {
      log.warn(
          "Goal continuation advisor failed for task={} provider={} model={}; falling back to pure"
              + " policy",
          task.id(),
          provider,
          model,
          ex);
      return decideContinuationFallback(
          decision,
          taskCategory,
          requiredQualityThreshold,
          confirmationRequired,
          providerBehaviorPoor,
          confirmationScope,
          providerSwitchesUsed,
          modelStepUpsUsed,
          modelStepDownsUsed);
    }
  }

  private GoalContinuationPolicyDecision decideContinuationFallback(
      GoalDonenessDecision decision,
      String taskCategory,
      double requiredQualityThreshold,
      boolean confirmationRequired,
      boolean providerBehaviorPoor,
      ContinuationConfirmationScope confirmationScope,
      int providerSwitchesUsed,
      int modelStepUpsUsed,
      int modelStepDownsUsed) {
    return withAppliedPolicy(
        goalContinuationPolicy.decide(
            GoalContinuationRequest.builder()
                .goalDecision(decision)
                .expensiveContinuationRequiresConfirmation(confirmationRequired)
                .benchmarkQualityPriorRequiredForAutomaticContinuation(
                    requireBenchmarkPriorForAutomaticContinuation)
                .userConfirmedExpensiveContinuation(confirmationScope.confirmed())
                .userConfirmedContinuationAction(confirmationScope.action())
                .userConfirmedContinuationProvider(confirmationScope.provider())
                .userConfirmedContinuationModel(confirmationScope.model())
                .providerBehaviorPoor(providerBehaviorPoor)
                .providerSwitchesUsed(providerSwitchesUsed)
                .maxProviderSwitches(maxProviderSwitches())
                .modelStepUpsUsed(modelStepUpsUsed)
                .maxModelStepUps(maxModelStepUps())
                .modelStepDownsUsed(modelStepDownsUsed)
                .maxModelStepDowns(maxModelStepDowns())
                .build()),
          taskCategory,
          requiredQualityThreshold,
          confirmationRequired,
          providerBehaviorPoor,
          confirmationScope,
          providerSwitchesUsed,
          modelStepUpsUsed,
          modelStepDownsUsed);
  }

  private GoalContinuationPolicyDecision withAppliedPolicy(
      GoalContinuationPolicyDecision decision,
      String taskCategory,
      double requiredQualityThreshold,
      boolean confirmationRequired,
      boolean providerBehaviorPoor,
      ContinuationConfirmationScope confirmationScope,
      int providerSwitchesUsed,
      int modelStepUpsUsed,
      int modelStepDownsUsed) {
    if (decision == null) {
      return null;
    }
    return new GoalContinuationPolicyDecision(
        decision.decision(),
        decision.selectedCandidate(),
        decision.reasons(),
        continuationPolicyData(
            decision,
            taskCategory,
            requiredQualityThreshold,
            confirmationRequired,
            providerBehaviorPoor,
            confirmationScope,
            providerSwitchesUsed,
            modelStepUpsUsed,
            modelStepDownsUsed));
  }

  private List<String> providerPreferenceOrder(String currentProvider) {
    List<String> order = new ArrayList<>();
    if (currentProvider == null || currentProvider.isBlank()) {
      return order;
    }
    addProvider(order, currentProvider);
    if (matrixProps == null || matrixProps.getFailoverOrder() == null) {
      return order;
    }
    List<String> failovers =
        matrixProps.getFailoverOrder().get(currentProvider.toLowerCase(Locale.ROOT));
    if (failovers != null) {
      failovers.forEach(provider -> addProvider(order, provider));
    }
    return order;
  }

  private Map<String, Boolean> providerAvailability(String runId) {
    if (availableProviderResolver == null) {
      return Map.of();
    }
    try {
      Set<LlmProvider> available = availableProviderResolver.resolve(runId);
      return ProviderAvailabilityMap.fromAvailableProviders(available, knownProviderNames());
    } catch (RuntimeException ex) {
      log.warn(
          "Goal continuation provider availability lookup failed for run={}: {}",
          runId,
          ex.getMessage());
      return Map.of();
    }
  }

  private Set<String> knownProviderNames() {
    Set<String> providers = new LinkedHashSet<>();
    if (matrixProps != null && matrixProps.getProviders() != null) {
      providers.addAll(matrixProps.getProviders().keySet());
    }
    if (matrixProps != null && matrixProps.getModels() != null) {
      matrixProps.getModels().values().stream()
          .filter(config -> config != null && config.getProvider() != null)
          .map(com.navneet.executespec.router.ModelMatrixProperties.ModelConfig::getProvider)
          .forEach(providers::add);
    }
    return providers;
  }

  private static void addProvider(List<String> order, String provider) {
    if (provider == null || provider.isBlank()) {
      return;
    }
    String normalized = provider.trim().toLowerCase(Locale.ROOT);
    if (!order.contains(normalized)) {
      order.add(normalized);
    }
  }

  private static String taskCategory(PlannedTask task) {
    if (task == null || task.complexity() == null) {
      return "TASK_MEDIUM";
    }
    String text = normalizedTaskText(task);
    if (containsAny(text, "swe-bench", "terminal-bench", "programbench", "benchmark")) {
      return "BENCHMARK_PATCH";
    }
    if (touchesTestFiles(task)
        || containsAny(
            text,
            "failing test",
            "test failure",
            "fix test",
            "unit test",
            "integration test",
            "pytest",
            "junit",
            "jest")) {
      return "TEST_FIX";
    }
    if (containsAny(
        text,
        "bug",
        "fix",
        "error",
        "exception",
        "failure",
        "regression",
        "broken",
        "failing",
        "crash")) {
      return "BUG_FIX";
    }
    if (containsAny(
        text, "refactor", "cleanup", "clean up", "rename", "extract", "restructure", "decompose")) {
      return "REFACTOR";
    }
    if (containsAny(text, "terminal", "cli", "command-line", "command line", "shell", "script")) {
      return "TERMINAL_TASK";
    }
    if (containsAny(
        text, "read-only", "read only", "investigate", "analyze", "analyse", "explain")) {
      return "READ_ONLY_HELP";
    }
    if (containsAny(text, "create", "implement", "build", "add", "new", "scaffold", "generate")) {
      return "GREENFIELD_BUILD";
    }
    if (!taskValues(task.touchesFiles()).isEmpty()) {
      return "PROJECT_PATCH";
    }
    return "TASK_" + task.complexity().name();
  }

  private static String normalizedTaskText(PlannedTask task) {
    StringBuilder text = new StringBuilder();
    appendTaskText(text, task.title());
    appendTaskText(text, task.description());
    taskValues(task.hardConstraints()).forEach(value -> appendTaskText(text, value));
    taskValues(task.touchesFiles()).forEach(value -> appendTaskText(text, value));
    return text.toString().toLowerCase(Locale.ROOT);
  }

  private static List<String> taskValues(List<String> values) {
    return values == null ? List.of() : values;
  }

  private static void appendTaskText(StringBuilder text, String value) {
    if (value != null && !value.isBlank()) {
      text.append(' ').append(value);
    }
  }

  private static boolean containsAny(String text, String... needles) {
    if (text == null || text.isBlank()) {
      return false;
    }
    for (String needle : needles) {
      if (needle != null && !needle.isBlank() && text.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static boolean touchesTestFiles(PlannedTask task) {
    return taskValues(task.touchesFiles()).stream()
        .map(value -> value == null ? "" : value.toLowerCase(Locale.ROOT))
        .anyMatch(
            path ->
                path.contains("/test/")
                    || path.contains("/tests/")
                    || path.contains("__tests__")
                    || path.endsWith("test.java")
                    || path.endsWith("tests.java")
                    || path.endsWith(".test.ts")
                    || path.endsWith(".test.tsx")
                    || path.endsWith(".spec.ts")
                    || path.endsWith(".spec.tsx")
                    || path.endsWith("_test.go")
                    || path.startsWith("test_")
                    || path.endsWith("_test.py"));
  }

  private static Map<String, Object> goalDecisionData(GoalDonenessDecision decision) {
    if (decision == null) {
      return Map.of("state", "UNKNOWN", "reason", "goal decision missing");
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("state", decision.state().name());
    data.put("recommendedDecision", decision.recommendedDecision().name());
    data.put("reason", decision.reason());
    data.put("recommendedNextStep", decision.recommendedNextStep());
    data.put("goalSummary", decision.goalSummary());
    data.put("completedItems", decision.completedItems());
    data.put("incompleteItems", decision.incompleteItems());
    data.put("evidence", decision.evidence());
    data.put("risks", decision.risks());
    data.put("budgetCeilingReason", decision.budgetCeilingReason());
    data.put("inputTokens", decision.inputTokens());
    data.put("outputTokens", decision.outputTokens());
    data.put("cacheReadTokens", decision.cacheReadTokens());
    data.put("cacheWriteTokens", decision.cacheWriteTokens());
    data.put("reasoningTokens", decision.reasoningTokens());
    data.put("totalTokens", decision.totalTokens());
    data.put("costUsd", decision.costUsd());
    data.put("wallClockMs", decision.wallClockMs());
    data.put("userInputRequired", decision.userInputRequired());
    data.put("anotherModelProviderMightHelp", decision.anotherModelProviderMightHelp());
    return data;
  }

  private Map<String, Object> continuationDecisionData(GoalContinuationPolicyDecision decision) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (decision == null) {
      data.put("decision", "UNKNOWN");
      data.put("reasons", List.of("continuation decision missing"));
      data.put("policy", continuationPolicyData());
      return data;
    }
    data.put("decision", decision.decision().name());
    decision
        .selectedCandidate()
        .ifPresent(candidate -> data.put("selectedCandidate", selectedCandidateData(candidate)));
    data.put("reasons", decision.reasons());
    data.put("policy", continuationPolicyData(decision));
    return data;
  }

  private Map<String, Object> continuationPolicyData(GoalContinuationPolicyDecision decision) {
    if (decision != null && decision.policy() != null && !decision.policy().isEmpty()) {
      return decision.policy();
    }
    return continuationPolicyData();
  }

  private Map<String, Object> continuationPolicyData() {
    return continuationPolicyData(null, "UNKNOWN", requiredGoalQualityThreshold());
  }

  private Map<String, Object> continuationPolicyData(
      GoalContinuationPolicyDecision decision,
      String taskCategory,
      double requiredQualityThreshold) {
    return continuationPolicyData(
        decision,
        taskCategory,
        requiredQualityThreshold,
        requireConfirmationBeforeExpensiveContinuation);
  }

  private Map<String, Object> continuationPolicyData(
      GoalContinuationPolicyDecision decision,
      String taskCategory,
      double requiredQualityThreshold,
      boolean confirmationRequired) {
    return continuationPolicyData(
        decision,
        taskCategory,
        requiredQualityThreshold,
        confirmationRequired,
        false,
        new ContinuationConfirmationScope(false, null, null, null),
        0,
        0,
        0);
  }

  private Map<String, Object> continuationPolicyData(
      GoalContinuationPolicyDecision decision,
      String taskCategory,
      double requiredQualityThreshold,
      boolean confirmationRequired,
      boolean providerBehaviorPoor,
      ContinuationConfirmationScope confirmationScope,
      int providerSwitchesUsed,
      int modelStepUpsUsed,
      int modelStepDownsUsed) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (decision != null && decision.policy() != null && !decision.policy().isEmpty()) {
      data.putAll(decision.policy());
    }
    data.put(
        "taskCategory", taskCategory == null || taskCategory.isBlank() ? "UNKNOWN" : taskCategory);
    data.put(
        "requiredQualityThreshold", sanitizeRequiredQualityThreshold(requiredQualityThreshold));
    data.put("defaultRequiredQualityThreshold", requiredGoalQualityThreshold());
    data.put("requiredQualityThresholds", requiredQualityThresholdsByCategory());
    data.put("providerSwitchesUsed", Math.max(0, providerSwitchesUsed));
    data.put("maxProviderSwitches", maxProviderSwitches());
    data.put("modelStepUpsUsed", Math.max(0, modelStepUpsUsed));
    data.put("maxModelStepUps", maxModelStepUps());
    data.put("modelStepDownsUsed", Math.max(0, modelStepDownsUsed));
    data.put("maxModelStepDowns", maxModelStepDowns());
    data.put("maxNoProgressIterations", maxNoProgressIterations());
    data.put("maxFalseDoneClaims", maxFalseDoneClaims());
    data.put("maxRepeatedFailures", maxRepeatedFailures());
    data.put("maxEditChurn", maxEditChurn());
    data.put("maxStaleExplanations", maxStaleExplanations());
    data.put("maxParallelTasks", maxParallelTasks());
    data.put("providerBehaviorPoor", providerBehaviorPoor);
    data.put("requireConfirmationBeforeExpensiveContinuation", confirmationRequired);
    data.put("userConfirmedExpensiveContinuation", confirmationScope.confirmed());
    if (confirmationScope.action() != null) {
      data.put("userConfirmedContinuationAction", confirmationScope.action().name());
    }
    if (hasText(confirmationScope.provider())) {
      data.put("userConfirmedContinuationProvider", confirmationScope.provider());
    }
    if (hasText(confirmationScope.model())) {
      data.put("userConfirmedContinuationModel", confirmationScope.model());
    }
    data.put(
        "requireBenchmarkPriorForAutomaticContinuation",
        requireBenchmarkPriorForAutomaticContinuation);
    data.put("maxTotalCostUsd", maxGoalTotalCostUsd());
    data.put("maxTotalTokens", maxGoalTotalTokens());
    data.put("maxTaskWallClockMs", maxGoalTaskWallClockMs());
    data.put("actingEnabled", continuationActingEnabled);
    data.put("maxRerunsPerTask", maxContinuationReruns());
    return data;
  }

  private boolean requireConfirmationBeforeExpensiveContinuation(String runId) {
    return continuationConfirmationRequiredByRun.getOrDefault(
        runId, requireConfirmationBeforeExpensiveContinuation);
  }

  private ContinuationConfirmationScope continuationConfirmationScope(String runId) {
    return continuationConfirmationScopeByRun.getOrDefault(
        runId, new ContinuationConfirmationScope(false, null, null, null));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private double requiredGoalQualityThreshold() {
    return sanitizeRequiredQualityThreshold(goalRequiredQualityThreshold);
  }

  private double requiredGoalQualityThreshold(String taskCategory) {
    double override =
        switch (taskCategory == null ? "" : taskCategory.trim().toUpperCase(Locale.ROOT)) {
          case "TASK_LOW" -> goalRequiredQualityThresholdTaskLow;
          case "TASK_MEDIUM" -> goalRequiredQualityThresholdTaskMedium;
          case "TASK_HIGH" -> goalRequiredQualityThresholdTaskHigh;
          case "GREENFIELD_BUILD" -> goalRequiredQualityThresholdGreenfieldBuild;
          case "PROJECT_PATCH" -> goalRequiredQualityThresholdProjectPatch;
          case "BUG_FIX" -> goalRequiredQualityThresholdBugFix;
          case "TEST_FIX" -> goalRequiredQualityThresholdTestFix;
          case "REFACTOR" -> goalRequiredQualityThresholdRefactor;
          case "BENCHMARK_PATCH" -> goalRequiredQualityThresholdBenchmarkPatch;
          case "TERMINAL_TASK" -> goalRequiredQualityThresholdTerminalTask;
          case "READ_ONLY_HELP" -> goalRequiredQualityThresholdReadOnlyHelp;
          default -> -1.0;
        };
    if (Double.isFinite(override) && override > 0.0) {
      return sanitizeRequiredQualityThreshold(override);
    }
    return requiredGoalQualityThreshold();
  }

  private Map<String, Object> requiredQualityThresholdsByCategory() {
    Map<String, Object> thresholds = new LinkedHashMap<>();
    thresholds.put("TASK_LOW", requiredGoalQualityThreshold("TASK_LOW"));
    thresholds.put("TASK_MEDIUM", requiredGoalQualityThreshold("TASK_MEDIUM"));
    thresholds.put("TASK_HIGH", requiredGoalQualityThreshold("TASK_HIGH"));
    thresholds.put("GREENFIELD_BUILD", requiredGoalQualityThreshold("GREENFIELD_BUILD"));
    thresholds.put("PROJECT_PATCH", requiredGoalQualityThreshold("PROJECT_PATCH"));
    thresholds.put("BUG_FIX", requiredGoalQualityThreshold("BUG_FIX"));
    thresholds.put("TEST_FIX", requiredGoalQualityThreshold("TEST_FIX"));
    thresholds.put("REFACTOR", requiredGoalQualityThreshold("REFACTOR"));
    thresholds.put("BENCHMARK_PATCH", requiredGoalQualityThreshold("BENCHMARK_PATCH"));
    thresholds.put("TERMINAL_TASK", requiredGoalQualityThreshold("TERMINAL_TASK"));
    thresholds.put("READ_ONLY_HELP", requiredGoalQualityThreshold("READ_ONLY_HELP"));
    return thresholds;
  }

  private static double sanitizeRequiredQualityThreshold(double threshold) {
    if (!Double.isFinite(threshold) || threshold <= 0.0) {
      return 70.0;
    }
    return Math.min(100.0, threshold);
  }

  private int maxProviderSwitches() {
    return Math.max(0, advisoryProviderSwitchLimit);
  }

  private int maxModelStepUps() {
    return Math.max(0, advisoryModelStepUpLimit);
  }

  private int maxModelStepDowns() {
    return Math.max(0, advisoryModelStepDownLimit);
  }

  private int maxNoProgressIterations() {
    return maxGoalNoProgressIterations;
  }

  private int maxFalseDoneClaims() {
    return maxGoalFalseDoneClaims;
  }

  private int maxRepeatedFailures() {
    return maxGoalRepeatedFailures;
  }

  private int maxEditChurn() {
    return maxGoalEditChurn;
  }

  private int maxStaleExplanations() {
    return maxGoalStaleExplanations;
  }

  private int maxParallelTasks() {
    return Math.max(1, maxGoalParallelTasks);
  }

  private static Map<String, Object> selectedCandidateData(
      com.navneet.executespec.router.ModelProviderScore candidate) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("provider", candidate.provider());
    data.put("model", candidate.model());
    data.put("taskCategory", candidate.taskCategory());
    data.put("qualityScore", candidate.qualityScore());
    data.put("requiredQualityThreshold", candidate.requiredQualityThreshold());
    data.put("costPerMillionTokens", candidate.costPerMillionTokens());
    data.put("latencyP50Ms", candidate.latencyP50Ms());
    data.put("latencyP95Ms", candidate.latencyP95Ms());
    data.put("benchmarkQualityPriorAvailable", candidate.benchmarkQualityPriorAvailable());
    data.put("reasons", candidate.reasons());
    return data;
  }

  private void persistGoalAndContinuationDecision(
      String runId,
      String taskIdShort,
      GoalDonenessDecision goalDecision,
      GoalContinuationPolicyDecision continuationDecision) {
    if (taskRepo == null
        || objectMapper == null
        || runId == null
        || runId.length() < 8
        || taskIdShort == null) {
      return;
    }
    String key = runId.substring(0, 8) + "_" + taskIdShort;
    try {
      var existing = taskRepo.findById(key);
      if (existing.isEmpty()) {
        log.debug("persistGoalDecision: row not found id={}", key);
        return;
      }
      var task = existing.get();
      task.setGoalDonenessJson(objectMapper.writeValueAsString(goalDecision));
      task.setContinuationDecisionJson(serializeContinuationDecision(continuationDecision));
      taskRepo.saveAndFlush(task);
    } catch (Exception ex) {
      log.warn("persistGoalDecision: {} failed: {}", key, ex.getMessage(), ex);
    }
  }

  private String serializeContinuationDecision(GoalContinuationPolicyDecision decision)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    ObjectNode node = objectMapper.createObjectNode();
    if (decision == null) {
      node.put("decision", "UNKNOWN");
      node.putArray("reasons").add("continuation decision missing");
      writeContinuationPolicy(node.putObject("policy"), null);
      return objectMapper.writeValueAsString(node);
    }
    node.put("decision", decision.decision().name());
    var selected = decision.selectedCandidate();
    if (selected.isPresent()) {
      var candidate = selected.get();
      ObjectNode candidateNode = node.putObject("selectedCandidate");
      candidateNode.put("provider", candidate.provider());
      candidateNode.put("model", candidate.model());
      candidateNode.put("taskCategory", candidate.taskCategory());
      candidateNode.put("qualityScore", candidate.qualityScore());
      candidateNode.put("requiredQualityThreshold", candidate.requiredQualityThreshold());
      candidateNode.put("costPerMillionTokens", candidate.costPerMillionTokens());
      candidateNode.put("latencyP50Ms", candidate.latencyP50Ms());
      candidateNode.put("latencyP95Ms", candidate.latencyP95Ms());
      candidateNode.put("benchmarkQualityPriorAvailable", candidate.benchmarkQualityPriorAvailable());
      var candidateReasons = candidateNode.putArray("reasons");
      candidate.reasons().forEach(candidateReasons::add);
    }
    var reasons = node.putArray("reasons");
    decision.reasons().forEach(reasons::add);
    writeContinuationPolicy(node.putObject("policy"), decision);
    return objectMapper.writeValueAsString(node);
  }

  private void writeContinuationPolicy(
      ObjectNode policyNode, GoalContinuationPolicyDecision decision) {
    if (decision != null && decision.policy() != null && !decision.policy().isEmpty()) {
      objectMapper
          .valueToTree(decision.policy())
          .fields()
          .forEachRemaining(entry -> policyNode.set(entry.getKey(), entry.getValue()));
      return;
    }
    policyNode.put("taskCategory", "UNKNOWN");
    policyNode.put("requiredQualityThreshold", requiredGoalQualityThreshold());
    policyNode.put("defaultRequiredQualityThreshold", requiredGoalQualityThreshold());
    ObjectNode thresholds = policyNode.putObject("requiredQualityThresholds");
    requiredQualityThresholdsByCategory()
        .forEach((category, threshold) -> thresholds.put(category, (Double) threshold));
    policyNode.put("providerSwitchesUsed", providerSwitchesUsed(null));
    policyNode.put("maxProviderSwitches", maxProviderSwitches());
    policyNode.put("modelStepUpsUsed", modelStepUpsUsed(null, null));
    policyNode.put("maxModelStepUps", maxModelStepUps());
    policyNode.put("modelStepDownsUsed", modelStepDownsUsed(null, null));
    policyNode.put("maxModelStepDowns", maxModelStepDowns());
    policyNode.put("maxNoProgressIterations", maxNoProgressIterations());
    policyNode.put("maxFalseDoneClaims", maxFalseDoneClaims());
    policyNode.put("maxRepeatedFailures", maxRepeatedFailures());
    policyNode.put("maxEditChurn", maxEditChurn());
    policyNode.put("maxParallelTasks", maxParallelTasks());
    policyNode.put("providerBehaviorPoor", false);
    policyNode.put(
        "requireConfirmationBeforeExpensiveContinuation",
        requireConfirmationBeforeExpensiveContinuation);
    policyNode.put(
        "requireBenchmarkPriorForAutomaticContinuation",
        requireBenchmarkPriorForAutomaticContinuation);
    policyNode.put("maxTotalCostUsd", maxGoalTotalCostUsd());
    policyNode.put("maxTotalTokens", maxGoalTotalTokens());
    policyNode.put("maxTaskWallClockMs", maxGoalTaskWallClockMs());
    policyNode.put("actingEnabled", continuationActingEnabled);
    policyNode.put("maxRerunsPerTask", maxContinuationReruns());
  }

  private static String firstReason(GoalContinuationPolicyDecision decision) {
    if (decision == null || decision.reasons().isEmpty()) {
      return "no continuation reason supplied";
    }
    return decision.reasons().getFirst();
  }

  private GoalEvidence buildGoalEvidence(
      String runId,
      PlannedTask task,
      WorkerResult workerResult,
      TaskOutcome.Status finalStatus,
      Boolean smokePassed,
      String failureReason) {
    int fileWrites = workerResult == null ? 0 : countFileWrites(workerResult.toolCallLog());
    RunCostTracker.Snapshot cost = costSnapshot(runId);
    int totalTokens = totalTrackedTokens(cost);
    long wallClockMs = workerResult == null ? 0L : workerResult.totalLatencyMs();
    boolean completeTaskClaimed =
        workerResult != null && workerResult.outcome() == WorkerResult.Outcome.COMPLETED;
    boolean validationAttempted = smokePassed != null;
    boolean validationPassed = Boolean.TRUE.equals(smokePassed);
    boolean statusClean =
        finalStatus == TaskOutcome.Status.SUCCESS
            || finalStatus == TaskOutcome.Status.SUCCESS_AFTER_RETRY;
    List<String> completed = new ArrayList<>();
    if (completeTaskClaimed) {
      completed.add("worker called completeTask");
    }
    if (fileWrites > 0) {
      completed.add(fileWrites + " file write" + (fileWrites == 1 ? "" : "s"));
    }
    if (validationPassed) {
      completed.add("smoke validation passed");
    }

    List<String> incomplete = new ArrayList<>();
    if (!statusClean) {
      incomplete.add("task status is " + finalStatus);
    }
    if (validationAttempted && !validationPassed) {
      incomplete.add("smoke validation failed");
    }
    if (failureReason != null && !failureReason.isBlank()) {
      incomplete.add(failureReason);
    }

    List<String> evidence = new ArrayList<>();
    evidence.add("taskStatus=" + finalStatus);
    if (workerResult != null) {
      evidence.add("workerOutcome=" + workerResult.outcome());
      evidence.add("iterations=" + workerResult.iterations());
      evidence.add("toolCalls=" + workerResult.toolCallsDispatched());
      evidence.add("fileWrites=" + fileWrites);
      evidence.add("wallClockMs=" + wallClockMs);
    }
    evidence.add("inputTokens=" + cost.inputTokens());
    evidence.add("outputTokens=" + cost.outputTokens());
    evidence.add("cacheReadTokens=" + cost.cacheReadTokens());
    evidence.add("cacheWriteTokens=" + cost.cacheWriteTokens());
    evidence.add("reasoningTokens=" + cost.reasoningTokens());
    evidence.add("totalTrackedTokens=" + totalTokens);
    evidence.add("costUsd=" + String.format(Locale.ROOT, "%.6f", cost.costUsd()));
    if (smokePassed != null) {
      evidence.add("smokePassed=" + smokePassed);
    }

    String budgetCeilingReason = budgetCeilingReason(workerResult, cost, totalTokens, wallClockMs);
    boolean budgetCeilingReached = budgetCeilingReason != null && !validationPassed;
    boolean blockedByEnvironment = blockedByEnvironment(workerResult, failureReason);
    boolean unsafeContinuation = unsafeContinuation(workerResult, failureReason);
    boolean completionConfidenceLow = fileWrites > 0 && (!completeTaskClaimed || !statusClean);
    int noProgressIterations =
        fileWrites == 0 && !completeTaskClaimed && workerResult != null
            ? Math.max(0, workerResult.iterations())
            : 0;
    int falseDoneClaims = completeTaskClaimed && validationAttempted && !validationPassed ? 1 : 0;
    int repeatedFailureCount =
        repeatedFailureCount(workerResult, validationAttempted, validationPassed, failureReason);
    int editChurnCount = editChurnCount(workerResult);
    int staleExplanationCount = staleExplanationCount(workerResult, fileWrites, validationPassed);
    evidence.add("noProgressIterations=" + noProgressIterations + "/" + maxNoProgressIterations());
    evidence.add("falseDoneClaims=" + falseDoneClaims + "/" + maxFalseDoneClaims());
    evidence.add("repeatedFailureCount=" + repeatedFailureCount + "/" + maxRepeatedFailures());
    evidence.add("editChurnCount=" + editChurnCount + "/" + maxEditChurn());
    evidence.add("staleExplanationCount=" + staleExplanationCount + "/" + maxStaleExplanations());
    if (blockedByEnvironment) {
      evidence.add("blockedByEnvironment=true");
    }
    if (unsafeContinuation) {
      evidence.add("unsafeContinuation=true");
    }
    if (completionConfidenceLow) {
      evidence.add("completionConfidenceLow=true");
      incomplete.add("completion confidence is low despite meaningful changes");
    }

    return GoalEvidence.builder()
        .goalSummary(goalSummary(task))
        .goalCriteriaSatisfied(completeTaskClaimed && validationPassed && statusClean)
        .validationAttempted(validationAttempted)
        .validationPassed(smokePassed)
        .requiredOutputsPresent(validationPassed && statusClean)
        .constraintsRespected(statusClean && (failureReason == null || failureReason.isBlank()))
        .meaningfulDiffCount(fileWrites)
        .noProgressIterations(noProgressIterations)
        .maxNoProgressIterations(maxNoProgressIterations())
        .falseDoneClaims(falseDoneClaims)
        .maxFalseDoneClaims(maxFalseDoneClaims())
        .repeatedFailureCount(repeatedFailureCount)
        .maxRepeatedFailures(maxRepeatedFailures())
        .editChurnCount(editChurnCount)
        .maxEditChurn(maxEditChurn())
        .staleExplanationCount(staleExplanationCount)
        .maxStaleExplanations(maxStaleExplanations())
        .budgetCeilingReached(budgetCeilingReached)
        .budgetCeilingReason(budgetCeilingReason)
        .inputTokens(cost.inputTokens())
        .outputTokens(cost.outputTokens())
        .cacheReadTokens(cost.cacheReadTokens())
        .cacheWriteTokens(cost.cacheWriteTokens())
        .reasoningTokens(cost.reasoningTokens())
        .totalTokens(totalTokens)
        .costUsd(cost.costUsd())
        .wallClockMs(wallClockMs)
        .blockedByEnvironment(blockedByEnvironment)
        .unsafeContinuation(unsafeContinuation)
        .completionConfidenceLow(completionConfidenceLow)
        .completeTaskClaimed(completeTaskClaimed)
        .completedItems(completed)
        .incompleteItems(incomplete)
        .evidence(evidence)
        .risks(risksFor(workerResult, failureReason, budgetCeilingReason, completionConfidenceLow))
        .build();
  }

  private static int repeatedFailureCount(
      WorkerResult workerResult,
      boolean validationAttempted,
      boolean validationPassed,
      String failureReason) {
    int max = 0;
    Map<String, Integer> counts = new LinkedHashMap<>();
    if (workerResult != null && workerResult.toolCallLog() != null) {
      for (String entry : workerResult.toolCallLog()) {
        String signature = repeatedFailureSignature(entry);
        if (signature == null) {
          continue;
        }
        int count = counts.getOrDefault(signature, 0) + 1;
        counts.put(signature, count);
        max = Math.max(max, count);
      }
    }
    if (validationAttempted
        && !validationPassed
        && failureReason != null
        && failureReason.toLowerCase(Locale.ROOT).contains("after one retry")) {
      max = Math.max(max, 2);
    }
    return max;
  }

  private static String repeatedFailureSignature(String entry) {
    if (entry == null || entry.isBlank()) {
      return null;
    }
    String normalized = entry.replace("→", "->").replaceAll("\\s+", " ").trim();
    String lower = normalized.toLowerCase(Locale.ROOT);
    boolean failure =
        lower.contains("refused")
            || lower.contains("-> err")
            || lower.contains("critic")
            || lower.contains("verify-before-complete");
    if (!failure) {
      return null;
    }
    String commandSignature = repeatedCommandFailureSignature(normalized);
    return commandSignature == null ? normalized : commandSignature;
  }

  private static String repeatedCommandFailureSignature(String normalized) {
    String lower = normalized.toLowerCase(Locale.ROOT);
    if (!(lower.startsWith("shell(")
        || lower.startsWith("bash(")
        || lower.startsWith("buildproject(")
        || lower.startsWith("runtests("))) {
      return null;
    }
    int status = lower.indexOf("->");
    String call = status < 0 ? normalized : normalized.substring(0, status).trim();
    return call.isBlank() ? null : call;
  }

  private static int editChurnCount(WorkerResult workerResult) {
    int max = 0;
    Map<String, Integer> counts = new LinkedHashMap<>();
    if (workerResult == null || workerResult.toolCallLog() == null) {
      return 0;
    }
    for (String entry : workerResult.toolCallLog()) {
      String path = mutationPathSignature(entry);
      if (path == null) {
        continue;
      }
      int count = counts.getOrDefault(path, 0) + 1;
      counts.put(path, count);
      max = Math.max(max, count);
    }
    return max;
  }

  private static int staleExplanationCount(
      WorkerResult workerResult, int fileWrites, boolean validationPassed) {
    if (workerResult == null || fileWrites > 0 || validationPassed) {
      return 0;
    }
    if (workerResult.finalAssistantText() == null || workerResult.finalAssistantText().isBlank()) {
      return 0;
    }
    return Math.max(0, workerResult.repeatedAssistantTextCount());
  }

  private static boolean providerBehaviorPoor(WorkerResult workerResult, String failureReason) {
    if (workerResult != null && workerResult.outcome() == WorkerResult.Outcome.ERROR) {
      return true;
    }
    String text =
        ((failureReason == null ? "" : failureReason)
                + " "
                + (workerResult == null || workerResult.finalAssistantText() == null
                    ? ""
                    : workerResult.finalAssistantText()))
            .toLowerCase(Locale.ROOT);
    return containsAny(
        text,
        "llm",
        "model provider",
        "provider error",
        "router",
        "rate limit",
        "429",
        "5xx",
        "timeout",
        "connection reset",
        "api error");
  }

  private static String mutationPathSignature(String entry) {
    if (entry == null || entry.isBlank()) {
      return null;
    }
    String normalized = entry.replace("→", "->").replaceAll("\\s+", " ").trim();
    if (!(normalized.startsWith("writeFile")
        || normalized.startsWith("write_file")
        || normalized.startsWith("editFile")
        || normalized.startsWith("edit_file")
        || normalized.startsWith("multiEdit")
        || normalized.startsWith("multi_edit"))) {
      return null;
    }
    String path = quotedJsonPath(normalized);
    if (path == null) {
      path = keyValuePath(normalized);
    }
    return path == null || path.isBlank() ? null : path;
  }

  private static String quotedJsonPath(String text) {
    int key = text.indexOf("\"path\"");
    if (key < 0) {
      return null;
    }
    int colon = text.indexOf(':', key);
    int start = colon < 0 ? -1 : text.indexOf('"', colon + 1);
    int end = start < 0 ? -1 : text.indexOf('"', start + 1);
    return end > start ? text.substring(start + 1, end) : null;
  }

  private static String keyValuePath(String text) {
    int key = text.indexOf("path=");
    if (key < 0) {
      return null;
    }
    int start = key + "path=".length();
    int comma = text.indexOf(',', start);
    int paren = text.indexOf(')', start);
    int end = comma < 0 ? paren : paren < 0 ? comma : Math.min(comma, paren);
    return end > start ? text.substring(start, end).trim() : text.substring(start).trim();
  }

  private static boolean blockedByEnvironment(WorkerResult workerResult, String failureReason) {
    StringBuilder combined = new StringBuilder();
    if (failureReason != null) {
      combined.append(failureReason).append('\n');
    }
    if (workerResult != null && workerResult.toolCallLog() != null) {
      for (String entry : workerResult.toolCallLog()) {
        if (entry != null) {
          combined.append(entry).append('\n');
        }
      }
    }
    String lower = combined.toString().toLowerCase(Locale.ROOT);
    return lower.contains("missing credential")
        || lower.contains("missing api key")
        || lower.contains("api key missing")
        || lower.contains("api_key")
        || lower.contains("not configured")
        || lower.contains("authentication failed")
        || lower.contains("unauthorized")
        || lower.contains("permission denied")
        || lower.contains("access denied")
        || lower.contains("network unreachable")
        || lower.contains("could not resolve host")
        || lower.contains("temporary failure in name resolution")
        || lower.contains("connection timed out")
        || lower.contains("missing required file");
  }

  private static boolean unsafeContinuation(WorkerResult workerResult, String failureReason) {
    StringBuilder combined = new StringBuilder();
    if (failureReason != null) {
      combined.append(failureReason).append('\n');
    }
    if (workerResult != null && workerResult.toolCallLog() != null) {
      for (String entry : workerResult.toolCallLog()) {
        if (entry != null) {
          combined.append(entry).append('\n');
        }
      }
    }
    String lower = combined.toString().toLowerCase(Locale.ROOT);
    return lower.contains("path traversal not allowed")
        || lower.contains("outside workspace")
        || lower.contains("blocked by sandbox")
        || lower.contains("command not allowed")
        || lower.contains("destructive command")
        || lower.contains("refused to overwrite")
        || lower.contains("code abuse")
        || lower.contains("exfiltration")
        || lower.contains("cloud metadata")
        || lower.contains("sandbox policy");
  }

  private static String goalSummary(PlannedTask task) {
    if (task == null) {
      return "unspecified task goal";
    }
    if (task.description() == null || task.description().isBlank()) {
      return task.title();
    }
    return task.title() + " — " + task.description();
  }

  private static boolean workerStoppedOnCeiling(WorkerResult workerResult) {
    if (workerResult == null) {
      return false;
    }
    return workerResult.outcome() == WorkerResult.Outcome.BUDGET_EXCEEDED
        || workerResult.outcome() == WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED
        || workerResult.outcome() == WorkerResult.Outcome.WALL_CLOCK_EXCEEDED;
  }

  private RunCostTracker.Snapshot costSnapshot(String runId) {
    if (costTracker == null) {
      return new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0);
    }
    try {
      RunCostTracker.Snapshot snapshot = costTracker.snapshot(runId);
      return snapshot == null ? new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0) : snapshot;
    } catch (Exception ex) {
      log.debug("goalEvidence: cost snapshot failed for run={}: {}", runId, ex.getMessage());
      return new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0);
    }
  }

  private String budgetCeilingReason(
      WorkerResult workerResult, RunCostTracker.Snapshot cost, int totalTokens, long wallClockMs) {
    if (workerStoppedOnCeiling(workerResult)) {
      return "worker outcome " + workerResult.outcome() + " reached an execution ceiling";
    }
    if (maxGoalTotalCostUsd() > 0.0 && cost != null && cost.costUsd() >= maxGoalTotalCostUsd()) {
      return "configured total cost ceiling reached ($"
          + String.format(Locale.ROOT, "%.6f", cost.costUsd())
          + " >= $"
          + String.format(Locale.ROOT, "%.6f", maxGoalTotalCostUsd())
          + ")";
    }
    if (maxGoalTotalTokens() > 0 && totalTokens >= maxGoalTotalTokens()) {
      return "configured total token ceiling reached ("
          + totalTokens
          + " >= "
          + maxGoalTotalTokens()
          + ")";
    }
    if (maxGoalTaskWallClockMs() > 0 && wallClockMs >= maxGoalTaskWallClockMs()) {
      return "configured task wall-clock ceiling reached ("
          + wallClockMs
          + "ms >= "
          + maxGoalTaskWallClockMs()
          + "ms)";
    }
    return null;
  }

  private static int totalTrackedTokens(RunCostTracker.Snapshot cost) {
    if (cost == null) {
      return 0;
    }
    long total =
        (long) cost.inputTokens()
            + cost.outputTokens()
            + cost.cacheReadTokens()
            + cost.cacheWriteTokens()
            + cost.reasoningTokens();
    return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
  }

  private double maxGoalTotalCostUsd() {
    return Double.isFinite(maxGoalTotalCostUsd) ? maxGoalTotalCostUsd : -1.0;
  }

  private int maxGoalTotalTokens() {
    return maxGoalTotalTokens;
  }

  private long maxGoalTaskWallClockMs() {
    return maxGoalTaskWallClockMs;
  }

  private static List<String> risksFor(
      WorkerResult workerResult,
      String failureReason,
      String budgetCeilingReason,
      boolean completionConfidenceLow) {
    List<String> risks = new ArrayList<>();
    if (workerResult != null && workerResult.outcome() != WorkerResult.Outcome.COMPLETED) {
      risks.add("workerOutcome=" + workerResult.outcome());
    }
    if (completionConfidenceLow) {
      risks.add("completion confidence is low despite meaningful changes");
    }
    if (budgetCeilingReason != null && !budgetCeilingReason.isBlank()) {
      risks.add(budgetCeilingReason);
    }
    if (failureReason != null && !failureReason.isBlank()) {
      risks.add(failureReason);
    }
    return List.copyOf(risks);
  }

  /**
   * Updates {@code AgentTask.status} in the DB mid-run so the UI's polled task list reflects live
   * progress (not just the terminal snapshot written by {@code
   * RunOrchestrator.applyExecutionResultToTasks}). Uses the same prefixed-key convention as the
   * orchestrator — first 8 chars of runId plus the planner-assigned task id.
   */
  /**
   * Per-task tuning: tier-resolved model + iteration / max-token caps driven by the planner's
   * complexity tag.
   *
   * <ul>
   *   <li>LOW → WORKHORSE model (flagship; RL-2026-06-052 decouple), LOW caps: 60 iters, 4096 max
   *       tokens
   *   <li>MEDIUM → WORKHORSE tier, 120 iters, 8192 max tokens
   *   <li>HIGH → PLANNING tier, 180 iters, 16384 max tokens
   * </ul>
   *
   * <p>When {@link ModelResolver} / provider conversion fails (e.g. user-supplied provider doesn't
   * match any enum value), we fall back to the run-level provider+model so the executor never
   * crashes on a planner edge case.
   */
  /**
   * Per-task tuning carrier. {@code stagnationWarnAfter} / {@code stagnationForceAfter} are
   * complexity-aware overrides forwarded into {@link WorkerTaskRequest} so the stagnation guard
   * fires at thresholds proportional to the task's expected exploration depth. Calibrated
   * 2026-05-08 against ProgramBench cmatrix anthropic run 9b37b099 — HIGH and MEDIUM tasks both
   * stagnated at the static 12-iter cap before the worker could write substantive source. The new
   * HIGH cap (24 warn / 40 force) is enough for legitimate reverse-engineering patterns: read
   * README + man page + binary --help/--version + plan + write.
   */
  private record TierConfig(
      String provider,
      String model,
      int maxIter,
      int maxTokens,
      int stagnationWarnAfter,
      int stagnationForceAfter,
      int wallClockSeconds,
      boolean forceToolChoiceOnSalvage) {
    TierConfig withProviderModel(String provider, String model) {
      return new TierConfig(
          provider,
          model,
          maxIter,
          maxTokens,
          stagnationWarnAfter,
          stagnationForceAfter,
          wallClockSeconds,
          forceToolChoiceOnSalvage);
    }
  }

  private record ProviderModelOverride(
      String provider, String model, RunContinuationDecision decision) {}

  private record GoalTaskDecision(
      GoalDonenessDecision doneness, GoalContinuationPolicyDecision continuation) {}

  private TierConfig tierConfigFor(
      PlannedTask.Complexity complexity, String runProvider, String runModel, boolean projectRun) {
    AgentTier tier;
    int maxIter;
    int maxTokens;
    int stagnationWarnAfter;
    int stagnationForceAfter;
    int wallClockSeconds;
    switch (complexity == null ? PlannedTask.Complexity.MEDIUM : complexity) {
        // Caps bumped again 2026-04-19: 20/30/45 → 30/50/75. Observed on
        // the RN Calorie Tracker run (0a0201c7) — MEDIUM scaffold task
        // (7 files + npm install + typecheck) hit 30 iters and finished
        // PARTIAL even though all files were written. Bigger scaffold-heavy
        // specs consistently need >30 iters because every readFile /
        // writeFile is one tool call. 50 covers a clean 7-file scaffold
        // with 2-3 self-review re-reads; 75 covers HIGH tasks (e.g. T3
        // "implement 4 screens + service" on the same run) with headroom.
        // (Previous bump 12/22/35 → 20/30/45 was for multi-repo Launches;
        //  this second bump is for codegen-dense greenfield scaffolds.)
        //
        // 2026-05-08 — stagnation thresholds are now complexity-aware too. Static
        // STAGNATION_WARN_AFTER_N=8 / STAGNATION_FORCE_AFTER_N=12 was calibrated
        // for typical write-then-verify-then-stop patterns; for HIGH-complexity
        // ProgramBench-style reverse-engineering the worker legitimately needs
        // ~15-20 read iters before its first write, and was force-terminating
        // at iter 12 before any source was written. Reviewer then "fixed" the
        // empty workspace with no-op compile.sh — passing build smoke without
        // actually compiling source. Verified on cmatrix anthropic run 9b37b099
        // (2026-05-08) with empty workspace + 100/100 programbench score (which
        // counted the cleanroom's original binary).
      case LOW -> {
        // RL-2026-06-052 — the worker stays on the flagship even for LOW tasks. The LIGHTWEIGHT
        // tier model is now a cheap model (Haiku / -mini / flash) reserved for the genuinely-light
        // NON-worker agents; routing the worker there reintroduces the prior cheap-tier eval
        // regression. So LOW keeps its smaller iteration/token caps but resolves the WORKHORSE
        // (flagship) model — decoupling worker quality from the lightweight-agent cost tier.
        tier = AgentTier.WORKHORSE;
        // RL-2026-06: caps loosened again so iteration limits are a backstop, not a
        // quality ceiling. The dollar cap and stagnation guard remain the real runaway
        // controls; LOW gets enough room for inspect -> edit -> verify loops on real repos.
        maxIter = 60;
        maxTokens = 4096;
        stagnationWarnAfter = 10;
        stagnationForceAfter = 16;
        wallClockSeconds = 30 * 60;
      }
      case HIGH -> {
        tier = AgentTier.PLANNING;
        // 2026-05-08 — HIGH iter cap 75 → 120 (matches PATCH mode's calibration). Pairs
        // with WorkerAgent's Lever A force-edit watchdog (fires at 70% with zero writes)
        // + Lever B best-guess salvage (fires on iter-limit with zero writes). Real cost
        // ceiling is the per-run cost cap, not the iter cap. Extends headroom for genuine
        // long-tail HIGH tasks (multi-file Spring Boot apps with deep wiring) that
        // currently hit iter-limit at iter 75 mid-edit.
        //
        // RL-2026-06: 120 -> 180. Non-frontier models need more iterations for the same
        // tool-using loop. Stagnation remains <25% of the cap, so read-only loops still stop.
        maxIter = 180;
        maxTokens = 16384;
        stagnationWarnAfter = 24;
        stagnationForceAfter = 40;
        // RL-2026-05-004: 45→75 min. Gemini is genuinely slow per turn; a real HIGH
        // task was being wall-clock-killed mid-work → PARTIAL. $ cap still backstops.
        wallClockSeconds = 75 * 60;
      }
      default -> {
        tier = AgentTier.WORKHORSE;
        // 2026-05-10 — MEDIUM iter cap 50 → 75. Dev run a59f9798/T12 ("write unit tests for
        // 3 mobile media.service methods") wrote 92 files at 0.92 writes/iter and hit the
        // project-run 100-iter cap (50 × 2) mid-test-suite. The worker was making genuine
        // progress; iter cap killed it.
        //
        // RL-2026-06: 75 -> 120. This is the common "real feature slice" band and the most
        // important budget to uncap for DeepSeek-class models.
        maxIter = 120;
        maxTokens = 8192;
        stagnationWarnAfter = 16;
        stagnationForceAfter = 28;
        wallClockSeconds = 45 * 60; // RL-2026-05-004: 25→45 min (PARTIAL reduction)
      }
    }
    // Project runs (Launches on an existing cloned codebase) need more
    // iterations: the worker has to readFile / searchFiles the real repo
    // before it can write, so 2× the fresh-scratch cap is the default.
    //
    // 2026-05-10 — wall-clock cap also doubles for project runs (same rationale: more files
    // to read before the first write). Stagnation thresholds DO NOT double — they're
    // measuring "consecutive non-write iterations" which is a behavioural signal, not a
    // budget; the underlying LLM doesn't need 2× more reads to recognise "I'm stuck."
    //
    // 2026-05-10 — LOW + MEDIUM project stagnation force bumps: 12 → 18 (LOW) and 20 → 24
    // (MEDIUM) ONLY for project runs. Dev run a59f9798/T9 force-terminated at 12 reads on
    // a LOW project task that needed to readFile a real codebase before writing the audit
    // fix; the static 12 is calibrated for fresh-scratch where the workspace is empty.
    if (projectRun) {
      maxIter *= 2;
      wallClockSeconds *= 2;
      // Bump only the project-run force threshold; warn stays the same so the model still
      // gets the "PROGRESS CHECK" nudge at the same point.
      if (complexity == PlannedTask.Complexity.LOW) stagnationForceAfter = 24;
      else if (complexity == null || complexity == PlannedTask.Complexity.MEDIUM)
        stagnationForceAfter = 36;
    }
    // 2026-05-08 — OpenAI 0.7× cap LIFTED. Original premise (2026-04-26): "OpenAI is markedly
    // chattier per iter than Anthropic (Spring AI re-ships the FULL conversation history on
    // every tool call, no prompt cache); observed 1.45M input tokens on HIGH library spec vs
    // Anthropic's 132K for the same spec." That premise is now stale on three counts:
    //   1. V2 OpenAI does NOT go through Spring AI — uses OpenAiNativeToolClient (the native
    //      bypass shipped in v2.6.0 to fix Spring AI 1.1.4's extra_body bug).
    //   2. The native client preserves prefix stability AND OpenAI auto-caches prompts ≥1024
    //      tokens silently. Empirically validated 2026-05-08 on a LOW spec: cache utilization
    //      43.2%, 12 of 15 calls hit cache, total in=50K (vs Anthropic's 14K + 76K cacheRead).
    //   3. Cap was a cost-control hack, but actual cost is now within 4× of Anthropic on the
    //      same workload — well within healthy budget bounds for a fair iteration budget.
    // Lifting the cap means HIGH non-project OpenAI tasks now get 75 iters (was 53 = ceil(75×0.7)),
    // matching Anthropic + Gemini. This unlocks the long-tail tasks where OpenAI was stagnating
    // out of iteration budget, not capability. Telemetry in RunCostTracker per-call logs
    // (cacheRead=N) makes a regression visible in production.
    // RL-2026-05-059 — resolve the forced-tool-choice-on-salvage flag once per tier-config so both
    // construction paths (resolved + fallback) carry it identically.
    boolean forceSalvageTool =
        featureFlagService != null && featureFlagService.isEnabled(FLAG_SALVAGE_FORCE_TOOL);
    try {
      // v2.5.13 — enum + V2 strings now agree ("gemini"/"anthropic"/"openai")
      LlmProvider p =
          runProvider == null ? LlmProvider.OPENAI : LlmProvider.valueOf(runProvider.toUpperCase());
      ResolvedModel resolved = modelResolver.resolve("WorkerAgent", p, tier);
      return new TierConfig(
          resolved.providerName(),
          resolved.getModel(),
          maxIter,
          maxTokens,
          stagnationWarnAfter,
          stagnationForceAfter,
          wallClockSeconds,
          forceSalvageTool);
    } catch (Exception e) {
      log.warn(
          "tierConfigFor: resolve failed for provider={} tier={} — using run-level {} / {}",
          runProvider,
          tier,
          runProvider,
          runModel);
      return new TierConfig(
          runProvider,
          runModel,
          maxIter,
          maxTokens,
          stagnationWarnAfter,
          stagnationForceAfter,
          wallClockSeconds,
          forceSalvageTool);
    }
  }

  @org.springframework.transaction.annotation.Transactional
  @org.springframework.retry.annotation.Retryable(
      retryFor = {org.springframework.dao.DataAccessException.class},
      maxAttempts = 3,
      backoff = @org.springframework.retry.annotation.Backoff(delay = 100, multiplier = 2.0))
  public void persistTaskStatus(String runId, String taskIdShort, String status) {
    persistTaskStatus(runId, taskIdShort, status, null);
  }

  /**
   * v2.5.12 — overload that stores an optional failure reason on the task row. The reason lands in
   * {@code lastLlmFailure} (already read as a fallback by {@code RunMapper.extractFailureReason})
   * so the UI shows a concrete message instead of "unknown — see worker logs".
   *
   * <p>2026-05-04 — {@code @Retryable} added after dev run {@code 3b739e94...} task T1 got stuck in
   * WORKING for 90+ minutes because a single transient {@code DataAccessException} on the WORKING→
   * COMPLETE update was silently swallowed and the executor moved on to T2. With 3 attempts + 100ms
   * backoff (×2 multiplier = 100ms / 200ms / 400ms), transient blips no longer leave stale WORKING
   * rows. {@code DataAccessException} is the right level — it covers Hikari connection timeouts,
   * optimistic lock failures, and Postgres deadlocks while still letting genuine programmer errors
   * (NullPointerException etc) propagate immediately.
   */
  @org.springframework.transaction.annotation.Transactional
  @org.springframework.retry.annotation.Retryable(
      retryFor = {org.springframework.dao.DataAccessException.class},
      maxAttempts = 3,
      backoff = @org.springframework.retry.annotation.Backoff(delay = 100, multiplier = 2.0))
  public void persistTaskStatus(
      String runId, String taskIdShort, String status, String failureReason) {
    persistTaskStatus(runId, taskIdShort, status, failureReason, null);
  }

  /**
   * V190 (2026-05-13, PR-A) — overload that also persists the {@code failure_diagnostic_json}
   * column on non-COMPLETE outcomes. The diagnostic JSON carries the {@link WorkerResult.Outcome}
   * enum, iteration count, last tool call, last LLM error, and a 240-char tail of the last
   * assistant text — enough for an operator to debug a stuck task without SSH access to the app
   * log.
   *
   * <p>Building the JSON is the caller's responsibility: see {@link
   * #buildFailureDiagnosticJson(WorkerResult, int)} for the canonical shape.
   *
   * <p>For COMPLETE tasks, callers should pass null for the diagnostic — the column is
   * intentionally NULL on success rows so it doubles as a "did this task have problems" filter.
   */
  @org.springframework.transaction.annotation.Transactional
  @org.springframework.retry.annotation.Retryable(
      retryFor = {org.springframework.dao.DataAccessException.class},
      maxAttempts = 3,
      backoff = @org.springframework.retry.annotation.Backoff(delay = 100, multiplier = 2.0))
  public void persistTaskStatus(
      String runId,
      String taskIdShort,
      String status,
      String failureReason,
      String failureDiagnosticJson) {
    String key = runId.substring(0, 8) + "_" + taskIdShort;
    try {
      var existing = taskRepo.findById(key);
      if (existing.isEmpty()) {
        log.warn("persistTaskStatus: row not found id={} (status={} skipped)", key, status);
        return;
      }
      var t = existing.get();
      t.setStatus(status);
      // Timestamp live — the Timeline tab needs real per-task start/end to render
      // staggered bars, and the end-of-run applyExecutionResultToTasks only writes
      // completedAt + durationMs. Stamp startedAt when a task first moves to WORKING,
      // completedAt when it reaches a terminal status. Don't overwrite existing values.
      if ("WORKING".equals(status) && t.getStartedAt() == null) {
        t.setStartedAt(java.time.Instant.now());
      }
      boolean terminal =
          "COMPLETE".equals(status)
              || "PARTIAL".equals(status)
              || "FAILED".equals(status)
              || "BLOCKED".equals(status);
      if (terminal && t.getCompletedAt() == null) {
        java.time.Instant now = java.time.Instant.now();
        t.setCompletedAt(now);
        if (t.getStartedAt() != null && t.getDurationMs() == null) {
          t.setDurationMs(java.time.Duration.between(t.getStartedAt(), now).toMillis());
        }
      }
      // Live token flush — pull the cumulative per-task snapshot out of
      // RunCostTracker so the UI sees real numbers for this task the
      // moment it terminates, not only after the whole run ends.
      if (terminal && costTracker != null) {
        try {
          var snap = costTracker.snapshotTask(runId, taskIdShort);
          if (snap != null && (snap.inputTokens() > 0 || snap.outputTokens() > 0)) {
            t.setInputTokens(snap.inputTokens());
            t.setOutputTokens(snap.outputTokens());
          }
        } catch (Exception snapEx) {
          log.debug("persistTaskStatus: snapshotTask failed for {}: {}", key, snapEx.getMessage());
        }
      }
      if (failureReason != null && !failureReason.isBlank()) {
        // 2026-05-10 (V184 companion) — defensive cap on the persistence
        // boundary even though the column is TEXT. Reasons routed here
        // can include a 240-char finalAssistantText tail PLUS a stack
        // trace if a tool blew up in an exception path; capping at 4000
        // is enough to keep the failure cause readable in the DB while
        // keeping row sizes predictable. Truncate marker is a literal
        // suffix so a future reader knows the value was clipped, not
        // that the worker terminated mid-thought.
        String reason = failureReason;
        if (reason.length() > 4000) {
          reason = reason.substring(0, 4000) + "… [truncated]";
        }
        t.setLastLlmFailure(reason);
      }
      if (failureDiagnosticJson != null && !failureDiagnosticJson.isBlank()) {
        // V190 (2026-05-13, PR-A) — defensive cap matching last_llm_failure. The diagnostic JSON
        // is bounded to ~600 chars at the build site (240-char text tail + numeric fields), but
        // belt-and-braces here too in case a future caller passes a larger payload.
        String diag = failureDiagnosticJson;
        if (diag.length() > 8000) {
          diag = diag.substring(0, 8000) + "\"… [truncated]\"}";
        }
        t.setFailureDiagnosticJson(diag);
      }
      taskRepo.saveAndFlush(t);
      log.info(
          "persistTaskStatus: {} -> {}{}",
          key,
          status,
          failureReason != null ? " (reason: " + failureReason + ")" : "");
    } catch (Exception e) {
      log.warn("persistTaskStatus: {} -> {} failed: {}", key, status, e.getMessage(), e);
    }
  }

  /**
   * V190 (2026-05-13, PR-A) — builds the structured failure-diagnostic JSON stored on {@code
   * agent_task.failure_diagnostic_json}. Compact + bounded (~600 chars max).
   *
   * <p>Schema:
   *
   * <pre>{
   *   "outcome":          "STOPPED_WITHOUT_COMPLETE",
   *   "iterations":       47,
   *   "iterBudget":       75,
   *   "toolCallsTotal":   132,
   *   "totalLatencyMs":   1837451,
   *   "lastToolCall":     "writeFile(app/x.py) → ERR: permission denied",
   *   "lastAssistantText":"Let me also check..."
   * }</pre>
   *
   * <p>JSON is hand-built (vs Jackson) to keep the persistence path allocation-light + guarantee
   * the bounded length the column comment promises.
   */
  static String buildFailureDiagnosticJson(WorkerResult r, int iterBudget) {
    if (r == null || r.outcome() == null) {
      return "{\"outcome\":\"UNKNOWN\"}";
    }
    StringBuilder b = new StringBuilder(600);
    b.append('{');
    b.append("\"outcome\":\"").append(r.outcome().name()).append("\",");
    b.append("\"iterations\":").append(r.iterations()).append(',');
    b.append("\"iterBudget\":").append(iterBudget).append(',');
    b.append("\"toolCallsTotal\":").append(r.toolCallsDispatched()).append(',');
    b.append("\"totalLatencyMs\":").append(r.totalLatencyMs());
    java.util.List<String> log = r.toolCallLog();
    if (log != null && !log.isEmpty()) {
      String last = log.get(log.size() - 1);
      if (last != null) {
        if (last.length() > 200) last = last.substring(0, 200) + "…";
        b.append(",\"lastToolCall\":\"").append(jsonEscape(last)).append('"');
      }
    }
    String tail = r.finalAssistantText();
    if (tail != null && !tail.isBlank()) {
      if (tail.length() > 240) tail = tail.substring(0, 240) + "…";
      b.append(",\"lastAssistantText\":\"").append(jsonEscape(tail)).append('"');
    }
    b.append('}');
    return b.toString();
  }

  /** Minimal JSON string escape — handles the chars that break a JSON string literal. */
  private static String jsonEscape(String s) {
    if (s == null) return "";
    StringBuilder out = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  /**
   * v2.5.12 — maps a non-COMPLETED worker outcome to a human-readable failure reason. Keeps the
   * message short enough to render inline on the run detail page while preserving the most
   * diagnostic snippet (finalAssistantText for STOPPED_WITHOUT_COMPLETE, the iteration count for
   * ITERATION_LIMIT_EXCEEDED).
   */
  private static String describeWorkerFailure(WorkerResult r) {
    if (r == null || r.outcome() == null) return "Worker outcome unknown";
    String tail = r.finalAssistantText();
    if (tail != null && tail.length() > 240) tail = tail.substring(0, 240) + "…";
    return switch (r.outcome()) {
      case STOPPED_WITHOUT_COMPLETE ->
          "Worker stopped without calling completeTask after "
              + r.iterations()
              + " iterations"
              + (tail != null && !tail.isBlank() ? ". Final: " + tail : "");
      case ITERATION_LIMIT_EXCEEDED ->
          "Iteration cap exceeded ("
              + r.iterations()
              + " iters, "
              + r.toolCallsDispatched()
              + " tool calls). Worker did not call completeTask.";
      case WALL_CLOCK_EXCEEDED ->
          "Wall-clock cap exceeded ("
              + r.iterations()
              + " iters, "
              + r.toolCallsDispatched()
              + " tool calls, "
              + (r.totalLatencyMs() / 1000)
              + "s elapsed). Worker did not call completeTask.";
      case ERROR ->
          "Worker threw unrecoverable exception"
              + (tail != null && !tail.isBlank() ? ": " + tail : "");
      case BUDGET_EXCEEDED -> "Run hit its hard cost cap; worker stopped cleanly mid-task.";
      default -> "Worker outcome=" + r.outcome();
    };
  }

  /** Backwards-compatible 5-arg overload — used by tests. Delegates with null provider. */
  String composeSystemPrompt(
      PlannedTask task,
      String designBlock,
      String refDocs,
      String projectType,
      String workspaceSummary) {
    return composeSystemPrompt(
        task, designBlock, refDocs, projectType, workspaceSummary, null, false);
  }

  /**
   * Provider-fragment flag — see prompts/fragments/provider/{name}/build.md and
   * prompts/fragments/provider/{name}/patch.md (RL-2026-05-052, T3.10).
   */
  public static final String FLAG_PROVIDER_TUNING = "worker.provider-prompt-tuning.enabled";

  /** Cross-provider fallback flag (RL-2026-05-052, T3.9). Default OFF, V220. */
  public static final String FLAG_CROSS_PROVIDER_FALLBACK =
      "worker.cross-provider-fallback.enabled";

  /**
   * Context retention across task retries (RL-2026-05-052, T3.14). When ON, the retry path loads
   * the prior worker context-snapshot from {@link com.navneet.executespec.context.ContextStore} and
   * continues from the failure point instead of starting fresh — so the worker doesn't re-read
   * files + re-issue the same searches before getting back to where it broke. Default OFF, V221.
   */
  public static final String FLAG_RETRY_CONTEXT_RETENTION =
      "worker.retry-context-retention.enabled";

  /**
   * Goal-decision enforcement flag (RL-2026-06-198). When ON, negative goal stop decisions at task
   * boundaries abort the remaining sequential tasks instead of continuing to spend tokens on work
   * the evaluator already classified as blocked, failed, or waste-risk. Default OFF, V249.
   */
  public static final String FLAG_GOAL_DECISION_ENFORCEMENT =
      "execution.goal-decision.enforcement.enabled";

  /**
   * Forced-tool-call salvage flag (RL-2026-05-059). When ON, the worker's Lever-B salvage call
   * restricts its tool set to write/edit/completeTask AND sets {@code tool_choice="required"} so a
   * model that would otherwise return reasoning-only text (the verified 4/10 native-DeepSeek
   * empty-patch failure on the 2026-05-30 mixed10 deep-dive) is forced to emit an actual edit.
   * Default OFF, V224. Honoured by the OpenAI-wire clients (OpenAI / DeepSeek / OpenRouter); falls
   * back to today's auto behaviour for providers that reject the forced choice.
   */
  public static final String FLAG_SALVAGE_FORCE_TOOL = "worker.salvage-force-tool-choice.enabled";

  /** Max per-run cross-provider retries — hard ceiling so a bad run can't cascade-blow up cost. */
  public static final int MAX_CROSS_PROVIDER_PER_RUN = 3;

  /**
   * Per-run cross-provider retry counter. Each successful cross-provider retry trigger (regardless
   * of whether the alternate provider succeeds) increments this. Cleaned by {@link
   * #clearCrossProviderState(String)} on terminal run state.
   */
  private final java.util.concurrent.ConcurrentMap<
          String, java.util.concurrent.atomic.AtomicInteger>
      crossProviderTriggersPerRun = new java.util.concurrent.ConcurrentHashMap<>();

  // VisibleForTesting — package-private so MultiRepoWorkerPromptTest can pin the
  // workspace-summary forwarding without spinning up the full ExecutionRequest pipeline.
  String composeSystemPrompt(
      PlannedTask task,
      String designBlock,
      String refDocs,
      String projectType,
      String workspaceSummary,
      String provider) {
    return composeSystemPrompt(
        task, designBlock, refDocs, projectType, workspaceSummary, provider, false);
  }

  String composeSystemPrompt(
      PlannedTask task,
      String designBlock,
      String refDocs,
      String projectType,
      String workspaceSummary,
      String provider,
      Boolean localHandsMode) {
    StringBuilder sb = new StringBuilder(SYSTEM_PROMPT_BASE);

    if (Boolean.TRUE.equals(localHandsMode)) {
      sb.append(LOCAL_HANDS_DIRECTIVE);
    }

    // 2026-05-28 (RL-2026-05-052, T3.10) — provider-specific BUILD prompt tuning. Each provider
    // has characteristic failure modes (Anthropic narrates, OpenAI re-reads, Gemini cache thrashes,
    // DeepSeek invents tool names). Per-provider build fragments are intentionally wire/discipline
    // only: PATCH/SWE-bench reproduce-first guidance lives under provider/<name>/patch.md.
    if (provider != null
        && featureFlagService != null
        && featureFlagService.isEnabled(FLAG_PROVIDER_TUNING)) {
      String providerFragment = loadProviderFragment(provider);
      if (!providerFragment.isBlank()) {
        sb.append("\n\n").append(providerFragment);
      }
    }

    // RL-2026-05-066 (verifyBoot) — flag-gated boot-verification directive (default OFF). Appended
    // only when worker.verify-boot.enabled is on; absent from the base prompt so production is
    // byte-identical until the eval justifies flipping it (RL-2026-05-061 stall lesson).
    if (featureFlagService != null
        && featureFlagService.isEnabled(
            com.navneet.executespec.tool.VerifyBootTool.FLAG_VERIFY_BOOT)) {
      sb.append(VERIFY_BOOT_DIRECTIVE);
    }

    // Multi-repo correctness (2026-05-02 fix). When the orchestrator targets a multi-repo project
    // it ships a workspaceSummary with "MULTI-REPO PROJECT WORKSPACE" + the per-repo subdir list.
    // Without forwarding it to the worker the planner-side `repos/<name>/` directives are
    // invisible — the worker's writeFile lands files at the workspace root, the per-repo push
    // step finds no diff to ship, runs finish COMPLETE with empty PRs. See
    // RunOrchestrator.buildMultiRepoSummary + MultiRepoWorkerPromptTest.
    if (workspaceSummary != null && workspaceSummary.contains("MULTI-REPO")) {
      sb.append("\n\n").append(workspaceSummary);
    }
    // v2.5.13 — RN/Expo scaffold adherence directive. When the planner declared
    // react-native-expo, inject the scaffold template verbatim so the worker
    // stops drifting to Expo SDK 51 (stable-ish but doesn't match our smoke
    // container expectations) or to `@testing-library/jest-native` (deprecated,
    // matchers are built into RNTL v12.4+). Run 0a0201c7 shipped SDK 51 +
    // jest-native + no hermes-compiler dep; all three we baked into the
    // template as explicit warnings after the rn-smoke spike, but the worker
    // only sees the planner's paraphrase unless we surface the template here.
    String scaffoldBlock = loadScaffoldForProjectType(projectType);
    if (!scaffoldBlock.isBlank()) {
      sb.append(
              "\n\n--- SCAFFOLD TEMPLATE (MATCH EXACTLY; deviate only with a written reason) ---\n")
          .append(scaffoldBlock)
          .append("\n--- END SCAFFOLD TEMPLATE ---\n");
    }
    if (!task.hardConstraints().isEmpty()) {
      sb.append("\n\nHARD CONSTRAINTS for this task (MUST follow):\n");
      for (String c : task.hardConstraints()) {
        sb.append("- ").append(c).append('\n');
      }
    }
    if (!task.touchesFiles().isEmpty()) {
      sb.append("\nExpected files to touch (hint, not a hard wall): ")
          .append(String.join(", ", task.touchesFiles()))
          .append('\n');
    }
    if (designBlock != null && !designBlock.isBlank()) {
      sb.append("\n\n").append(designBlock);
    }
    if (refDocs != null && !refDocs.isBlank()) {
      // Worker-time RAG (v2.5.2): relevant snippets from uploaded project docs.
      // Planner already injects its own {{REFERENCE_DOCS}} block during plan
      // synthesis; this is a narrower, task-scoped retrieval that keeps
      // detail constraints (field names, API contracts, limits) in front of
      // the worker while it writes code.
      sb.append("\n\n--- REFERENCE DOCS (task-relevant excerpts) ---\n")
          .append(refDocs)
          .append("\n--- END REFERENCE DOCS ---\n");
    }
    if (Boolean.TRUE.equals(localHandsMode)) {
      sb.append(LOCAL_HANDS_FINAL_OVERRIDE);
    }
    return sb.toString();
  }

  /**
   * Cross-provider retry result — wraps the {@link WorkerResult} with the provider + model that
   * actually ran so we can record it on the {@link TaskOutcome}. Internal to {@link
   * #maybeCrossProviderRetry}.
   */
  private record CrossProviderRetryResult(WorkerResult result, String provider, String model) {}

  /**
   * Attempt one cross-provider retry on the next provider in the failover chain, when the existing
   * same-provider retry has failed. Hard ceilings:
   *
   * <ul>
   *   <li>Flag {@link #FLAG_CROSS_PROVIDER_FALLBACK} must be ON (default OFF, V220).
   *   <li>Max one cross-provider retry per task (this method is only called once per task).
   *   <li>Max {@link #MAX_CROSS_PROVIDER_PER_RUN} per run.
   *   <li>The next provider must be different from the primary AND must resolve to a real model.
   * </ul>
   *
   * Returns {@code null} when no retry was attempted (any ceiling missed). Returns a wrapped {@link
   * WorkerResult} otherwise, regardless of whether the alternate provider succeeded.
   *
   * <p>RL-2026-05-052, T3.9.
   */
  CrossProviderRetryResult maybeCrossProviderRetry(
      PlannedTask task,
      Plan plan,
      ExecutionRequest req,
      TierConfig primaryCfg,
      ExecutionContext taskCtx,
      String systemPrompt,
      SmokeTestResult smoke,
      WorkerResult sameProviderRetry) {
    if (featureFlagService == null || !featureFlagService.isEnabled(FLAG_CROSS_PROVIDER_FALLBACK)) {
      return null;
    }
    String runId = req.runContext().runId();
    var counter =
        crossProviderTriggersPerRun.computeIfAbsent(
            runId, k -> new java.util.concurrent.atomic.AtomicInteger(0));
    if (counter.get() >= MAX_CROSS_PROVIDER_PER_RUN) {
      log.info(
          "Executor task={} cross-provider retry SKIPPED — per-run ceiling {} reached for run={}",
          task.id(),
          MAX_CROSS_PROVIDER_PER_RUN,
          runId);
      return null;
    }
    String altProvider = nextProviderInChain(primaryCfg.provider());
    if (altProvider == null) {
      log.info(
          "Executor task={} cross-provider retry SKIPPED — no failover candidate for primary={}",
          task.id(),
          primaryCfg.provider());
      return null;
    }
    TierConfig altCfg =
        tierConfigFor(task.complexity(), altProvider, null, req.projectId() != null);
    if (altCfg.provider().equalsIgnoreCase(primaryCfg.provider())) {
      // adaptive routing or strict-provider re-resolved back to the primary — abandon.
      log.info(
          "Executor task={} cross-provider retry SKIPPED — alt resolved back to primary={}",
          task.id(),
          primaryCfg.provider());
      return null;
    }
    counter.incrementAndGet();
    log.info(
        "Executor task={} cross-provider retry START — primary={} → alt={} (run-count={}/{})",
        task.id(),
        primaryCfg.provider(),
        altCfg.provider(),
        counter.get(),
        MAX_CROSS_PROVIDER_PER_RUN);
    String userPrompt = composeUserPrompt(task, smoke.output());
    WorkerResult altResult =
        worker.run(
            WorkerTaskRequest.builder()
                .provider(altCfg.provider())
                .model(altCfg.model())
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .context(taskCtx)
                .maxIterations(altCfg.maxIter())
                .temperature(0.0)
                .maxTokensPerCall(altCfg.maxTokens())
                .stagnationWarnAfter(altCfg.stagnationWarnAfter())
                .stagnationForceAfter(altCfg.stagnationForceAfter())
                .wallClockSeconds(altCfg.wallClockSeconds())
                .requireBuildVerification(true)
                .forceToolChoiceOnSalvage(altCfg.forceToolChoiceOnSalvage())
                .build());
    log.info(
        "Executor task={} cross-provider retry DONE — alt={} outcome={}",
        task.id(),
        altCfg.provider(),
        altResult.outcome());
    return new CrossProviderRetryResult(altResult, altCfg.provider(), altCfg.model());
  }

  /**
   * Walk the YAML failover chain (`application.yml model-matrix.failover-order.<primary>`) and
   * return the first provider that's different from the primary. Returns {@code null} when no chain
   * exists or the chain has no different candidate.
   */
  String nextProviderInChain(String primary) {
    if (primary == null || matrixProps == null || matrixProps.getFailoverOrder() == null) {
      return null;
    }
    var chain = matrixProps.getFailoverOrder().get(primary.toLowerCase());
    if (chain == null || chain.isEmpty()) return null;
    for (String candidate : chain) {
      if (!primary.equalsIgnoreCase(candidate)) return candidate;
    }
    return null;
  }

  /** Clear per-run cross-provider retry state — call from RunOrchestrator's terminal handlers. */
  public void clearCrossProviderState(String runId) {
    crossProviderTriggersPerRun.remove(runId);
  }

  /**
   * Load the provider-specific BUILD tuning fragment ({@code
   * prompts/fragments/provider/<name>/build.md}). The provider string is lower-cased; missing
   * fragments resolve to "" and are skipped. Mirrors {@link #loadScaffoldForProjectType} dispatch
   * style.
   */
  String loadProviderFragment(String provider) {
    if (provider == null || provider.isBlank()) return "";
    String key = provider.trim().toLowerCase();
    try {
      org.springframework.core.io.Resource r =
          new org.springframework.core.io.ClassPathResource(
              "prompts/fragments/provider/" + key + "/build.md");
      if (!r.exists()) return "";
      return new String(r.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.debug("loadProviderFragment({}) failed: {}", provider, e.getMessage());
      return "";
    }
  }

  /**
   * Load the scaffold template file matching the given projectType, or "" if none applies. Mirrors
   * the loose kebab-case convention used by {@link
   * com.navneet.executespec.execution.controller.ScaffoldController} — the file at {@code
   * prompts/scaffolds/<key>.md} wins if present. Planner-facing aliases are normalized first; for
   * example {@code java-spring-boot} resolves to {@code prompts/scaffolds/spring-boot.md}.
   */
  private String loadScaffoldForProjectType(String projectType) {
    if (projectType == null || projectType.isBlank()) return "";
    String key = scaffoldKeyForProjectType(projectType);
    if (key.isBlank()) return "";
    try {
      org.springframework.core.io.Resource r =
          new org.springframework.core.io.ClassPathResource("prompts/scaffolds/" + key + ".md");
      if (!r.exists()) return "";
      return new String(r.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.debug("loadScaffoldForProjectType({}) failed: {}", projectType, e.getMessage());
      return "";
    }
  }

  static String scaffoldKeyForProjectType(String projectType) {
    if (projectType == null || projectType.isBlank()) return "";
    String key = projectType.trim().toLowerCase().replace('_', '-');
    return switch (key) {
      case "java-spring-boot", "springboot" -> "spring-boot";
      default -> key;
    };
  }

  /**
   * Task-scoped RAG retrieval. Uses the task description as the query; empty string on any failure
   * (retrieval errors never fail the run). Returns the raw markdown from {@code RetrievalService}
   * ready for prompt inclusion.
   */
  private String fetchReferenceDocs(String projectId, PlannedTask task) {
    if (projectId == null || projectId.isBlank()) return "";
    String query =
        task.description() == null || task.description().isBlank()
            ? task.title()
            : task.description();
    if (query == null || query.isBlank()) return "";
    try {
      String docs = retrievalService.retrieve(projectId, query, WORKER_RAG_K, WORKER_RAG_TOKEN_CAP);
      return docs == null ? "" : docs;
    } catch (Exception e) {
      log.debug(
          "fetchReferenceDocs failed for project={} task={}: {}",
          projectId,
          task.id(),
          e.getMessage());
      return "";
    }
  }

  private String composeUserPrompt(PlannedTask task, String previousBuildError) {
    StringBuilder sb = new StringBuilder();
    sb.append("Task ").append(task.id()).append(": ").append(task.title()).append("\n\n");
    sb.append(task.description());
    if (previousBuildError != null && !previousBuildError.isBlank()) {
      // 2026-05-08 — sharpened from the generic "NOTE: previous attempt completed but build
      // failed" framing to the SWE-bench test-as-spec pattern: the build error IS the
      // specification of what to fix. The agent's earlier edits are still in the workspace
      // (per-task retry doesn't reset the workspace), so this is a continuation, not a redo.
      sb.append("\n\n---\nBUILD FAILED ON THE PREVIOUS ATTEMPT.\n\n");
      sb.append(
          "The error below is your spec for this retry — read it carefully, identify the "
              + "specific files / line numbers / error messages, and fix the root cause. Don't "
              + "refactor, don't reorganise, don't second-guess your earlier design choices. "
              + "Your previous edits are still in place; this is a continuation. Use readFile "
              + "to confirm the current file state where the error points, editFile to fix it, "
              + "buildProject to verify, then completeTask.\n\n");
      sb.append("--- BUILD ERROR (verbatim) ---\n");
      sb.append(truncateBuildErrorForRetry(previousBuildError));
      sb.append("\n--- END BUILD ERROR ---\n");
    }
    return sb.toString();
  }

  /**
   * Cap the build error at 12K chars (head 8K + tail 4K) — long enough for compiler stack traces
   * with stem+leaf, short enough that the worker's prefix stays cache-friendly on the retry. Most
   * actionable info is in the first stack trace and the last few "BUILD FAILED" lines; the middle
   * is repetition.
   */
  private static String truncateBuildErrorForRetry(String s) {
    if (s == null) return "";
    int cap = 12_000;
    if (s.length() <= cap) return s;
    int head = 8_000;
    int omitted = s.length() - cap;
    return s.substring(0, head)
        + "\n\n... ["
        + omitted
        + " chars omitted from middle of build output] ...\n\n"
        + s.substring(s.length() - (cap - head));
  }

  private SmokeTestResult runSmokeTest(ExecutionContext ctx, String plannerCommand) {
    long start = System.currentTimeMillis();
    ObjectNode args = objectMapper.createObjectNode();
    if (plannerCommand != null && !plannerCommand.isBlank()) {
      args.put("plannerCommand", plannerCommand);
    }
    ToolResult result = buildProjectTool.execute(args, ctx);
    long elapsed = System.currentTimeMillis() - start;
    if (result instanceof ToolResult.Ok ok) {
      String content = ok.content();
      int exit = extractExitCode(content);
      boolean passed = exit == 0;
      // Transfer #1 / verify-before-done (RL-2026-05-034): after a clean COMPILE, RUN the worker's
      // tests in-loop. BUILD's tests-are-mandatory directive WRITES tests; this verifies they PASS.
      // Language-neutral (TestCommandResolver → pytest / jest|vitest / go test / mvn|gradle /
      // cargo).
      // Flag-gated + fail-safe: no runner / empty summary → skip (today's compile-only behavior). A
      // real test failure flips this gate to FAILED, transparently reusing the existing build-fail
      // retry path (which re-invokes the worker with this failure text injected).
      if (passed && featureFlagService.isEnabled("build.test-exec-gate.enabled")) {
        try {
          java.util.Optional<com.navneet.executespec.testgen.model.TestExecutionSummary> ts =
              inLoopTestRunner.runTests(ctx.runId(), ctx.workspaceRoot());
          if (ts.isPresent() && (ts.get().getFailed() > 0 || ts.get().getErrors() > 0)) {
            int bad = ts.get().getFailed() + ts.get().getErrors();
            passed = false;
            exit = 1;
            content =
                content
                    + "\n\nBUILD COMPILED, but "
                    + bad
                    + " of "
                    + ts.get().getTotalTests()
                    + " tests FAILED ("
                    + ts.get().getPassed()
                    + " passed). The task is NOT complete until the existing + newly-written tests"
                    + " pass. Re-run the test suite, find the failing cases, and fix them.";
            log.info(
                "Executor test-exec gate run={}: {}/{} tests failed → smoke FAILED (retry)",
                ctx.runId(),
                bad,
                ts.get().getTotalTests());
          }
        } catch (Exception e) {
          log.warn("Executor test-exec gate errored (fail-safe: ignored): {}", e.getMessage());
        }
      }
      metrics.recordSmokeTest(passed ? "pass" : "fail");
      return new SmokeTestResult(passed, exit, content, elapsed);
    }
    // Tool Err — treat as smoke failure with a synthetic exit code
    metrics.recordSmokeTest("fail");
    String msg = result instanceof ToolResult.Err err ? err.message() : "(unknown tool err)";
    return new SmokeTestResult(false, -1, "tool error: " + msg, elapsed);
  }

  /** Parses "exit=N" out of BuildProjectTool's output envelope. */
  private static int extractExitCode(String output) {
    if (output == null) return -1;
    int i = output.indexOf("exit=");
    if (i < 0) return -1;
    int j = i + "exit=".length();
    int k = j;
    while (k < output.length() && Character.isDigit(output.charAt(k))) k++;
    try {
      return Integer.parseInt(output.substring(j, k));
    } catch (NumberFormatException nfe) {
      return -1;
    }
  }

  private record SmokeTestResult(boolean passed, int exitCode, String output, long latencyMs) {}

  private ExecutionResult summarize(
      List<TaskOutcome> outcomes, long totalLatency, boolean abortedByGoalDecision) {
    int succ = 0, warn = 0, fail = 0, skip = 0;
    for (var o : outcomes) {
      switch (o.status()) {
        case SUCCESS, SUCCESS_AFTER_RETRY -> succ++;
        case COMPLETED_WITH_WARNING -> warn++;
        case WORKER_FAILED -> fail++;
        case SKIPPED -> skip++;
      }
    }
    ExecutionResult.Overall overall;
    if (abortedByGoalDecision) overall = ExecutionResult.Overall.ABORTED;
    else if (fail > 0) overall = ExecutionResult.Overall.FAILED;
    else if (warn > 0) overall = ExecutionResult.Overall.PARTIAL_WITH_WARNINGS;
    else overall = ExecutionResult.Overall.ALL_SUCCESS;

    // v2.5.10 — separate "final task status" from "any intermediate warning"
    // so RunOrchestrator can distinguish CLEAN_COMPLETION from
    // CLEAN_WITH_INTERMEDIATE_WARNINGS when the final task is clean but
    // an earlier task flickered.
    TaskOutcome lastNonSkipped = null;
    for (int i = outcomes.size() - 1; i >= 0; i--) {
      if (outcomes.get(i).status() != TaskOutcome.Status.SKIPPED) {
        lastNonSkipped = outcomes.get(i);
        break;
      }
    }
    boolean anyIntermediateWarning = false;
    if (lastNonSkipped != null) {
      for (TaskOutcome o : outcomes) {
        if (o == lastNonSkipped) continue;
        if (o.status() == TaskOutcome.Status.COMPLETED_WITH_WARNING) {
          anyIntermediateWarning = true;
          break;
        }
      }
    }

    return ExecutionResult.builder()
        .overall(overall)
        .tasksExecuted(outcomes.size() - skip)
        .tasksSuccessful(succ)
        .tasksWithWarning(warn)
        .tasksFailed(fail)
        .tasksSkipped(skip)
        .perTask(outcomes)
        .totalLatencyMs(totalLatency)
        .finalTaskStatus(lastNonSkipped != null ? lastNonSkipped.status() : null)
        .anyIntermediateWarning(anyIntermediateWarning)
        .build();
  }

  /**
   * Counts successful file-writing tool calls in a worker's tool log. The log entries look like
   * {@code "writeFile({...})"}, {@code "writeFile → COMPLETED"}, or {@code "editFile(...)"}. We
   * only need a count (not file paths) to decide whether an incomplete worker still produced real
   * output.
   */
  private static int countFileWrites(java.util.List<String> toolLog) {
    if (toolLog == null || toolLog.isEmpty()) return 0;
    int n = 0;
    for (String entry : toolLog) {
      if (entry == null) continue;
      if (entry.startsWith("writeFile")
          || entry.startsWith("write_file")
          || entry.startsWith("editFile")
          || entry.startsWith("edit_file")
          || entry.startsWith("multiEdit")
          || entry.startsWith("multi_edit")
          || entry.startsWith("applyPatch")
          || entry.startsWith("apply_patch")) n++;
    }
    return n;
  }
}
