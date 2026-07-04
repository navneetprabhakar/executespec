# ExecuteSpec Code-Gen Harness — Adversarial Assessment (2026-07-04)

**Question:** the overall code-gen system is under par vs. frontier coding-agent harnesses (Claude Code, Codex, Cursor Agent, OpenHands, Devin-class). What is the harness missing, and what can be fixed?

**Scope:** the production V2 loop — `WorkerAgent`, `SequentialExecutor`, `CodeGenEngineImpl`, `ReviewerAgent`, tools — grounded in four code audits (worker mechanics, generation path, verification tooling, BUILD-mode loop) plus the TB 2.1 review (`infra/benchmark/BENCHMARK-REVIEW-2026-07-03.md`). The SWE-bench-specific findings live in the companion doc `swe-bench-pro-harness-adversarial-assessment-2026-07-04.md`.

---

## Thesis

The harness is built on a philosophy that is the **inverse of what wins in 2026**:

| Frontier harnesses | ExecuteSpec today |
|---|---|
| **Trust the model's agency**; bound it with time/cost budgets | **Distrust the model**: write-only progress counters, 6/8/12-iteration guillotines, 2-strike text-stop kills, forced completions |
| **Verify mechanically**: run tests/compilers after changes, feed failures back automatically | **Verify aspirationally**: "TESTS ARE MANDATORY" lives in the prompt; the mechanical gates (V209/V217) were built, then reverted/left OFF |
| **Engineer the context**: compaction, structured summaries, stable cached prefixes, overflow recovery | **Ship the raw transcript**: full history, compactors default OFF, fixed 60k trigger, context overflow = hard `ERROR`, no recovery |
| **Parallelize**: subagent fan-out for search/verify, concurrent tasks | **Strictly sequential**: one flat agent, one task at a time; wave scheduler computes parallel plans and then ignores them ("SEQUENTIAL_ADVISORY") |
| **Plans are living documents** | **Plan frozen at gate**; failure = skip/abort or same-task rerun; no re-planning |
| **Tuned per model**; capability features stay on for models that can use them | **Tuned to the cheapest model**: features that stall DeepSeek get reverted globally, degrading every stronger model |

The result: the core agent loop is roughly a 2024-era SWE-agent with guillotines bolted on, while the surrounding control plane (continuation advisor, goal decisions, event envelopes, managed AI) is 2026-era enterprise machinery. **The complexity budget went to governance and telemetry around the loop, not to the loop itself.**

---

## Gap 1 — The control loop replaces model agency with guillotines (highest impact)

**What the code does** (`WorkerAgent.java:154-172, 245-255, 1275-1321`):

- "Progress" = write-class tool calls only. `readFile`, `searchFiles`, `shell` — i.e., *investigation and verification* — never count (except the terminal-bench carve-out).
- Warn nudge at 8 non-write iterations (6 in PATCH mode pre-first-edit), **force-terminate at 12** (`COMPLETED_BY_STAGNATION`).
- The nudge text literally instructs: *"STOP reading … do NOT open more files to 'understand' first. Commit to the single most likely edit … even an imperfect edit beats zero."* (`WorkerAgent.java:2229-2248`)
- Two text-only (no-tool) turns → run killed with `STOPPED_WITHOUT_COMPLETE` (`:705-768`). Reasoning-style models that plan out loud get executed for it.
- Lever B "salvage" then papers over the forced kill with one blind best-guess edit.

**Why it's the core defect:** the system prompt's own bug-fixing directive says *"If you cannot reproduce that exact symptom you have not localized it — keep looking, do not guess-edit"* — and 12 iterations later the harness force-terminates the worker for following that instruction. The harness is at war with its own prompt. The TB review reached the same verdict for terminal mode (R1/R4: "DeepSeek-calibrated control logic misfires for other models"); the production loop has the identical disease. On any large/legacy repo, careful localization + in-loop verification is *exactly* the behavior that separates frontier harnesses — and this loop punishes it. It also directly caps quality on hard tasks: "even an imperfect edit beats zero" is true for benchmark diff-scoring and false for customer PRs.

**Fixes (ordered):**
1. Count evidence-gathering as progress before the first edit (reads/search/shell reset the counter, with a *separate, much higher* cap on total pre-edit iterations — e.g. 40).
2. Detect real stagnation by *repetition*, not tool class: same file re-read, same failing command, same explanation text (the `execution.continuation.*` evidence signals already compute repeated-failure/edit-churn/stale-explanation — reuse them in-loop instead of the write-only counter).
3. Text-only turns: never a kill signal. Re-prompt with `tool_choice` pressure instead; kill only at wall-clock/cost budget.
4. Delete "even an imperfect edit beats zero" from the nudge for production runs; it manufactures the wrong-half-fix class the reviewer then has to catch.
5. Make thresholds per-provider config, not global constants (see Gap 6).

## Gap 2 — Verification is prose, not mechanism

**What the code does:**

- `SYSTEM_PROMPT_BASE` demands tests ("Untested code is an INCOMPLETE task") — but the in-loop test tool (`runWorkspaceTests`, V217) and the build-time test gate (V209) are **OFF**, reverted 2026-06-01 because their schemas stalled native DeepSeek (RL-2026-05-061).
- `runTests` is **pytest-only** (`RunTestsTool.java:314-340`) — useless for Go/JS/TS/Rust/Java repos, while its schema tells the model to prefer it over shell.
- VBC (verify-before-complete, ON since V239) server-runs the suite at `completeTask` — but as a *refusal gate* (3 bounded refusals, fail-open), not a repair loop: the model gets "tests do not pass," not the failing output to fix against.
- The reviewer (`ReviewerAgent.java`) never runs tests at all — general pass, wiring pass, formatter, one build smoke. Its verdict never loops back to a worker except the fix-build third pass.
- Nothing enforces even a compile check per edit except the Python-only `worker.edit-diagnostics` (`py_compile`).

**Why it matters:** the single biggest behavioral difference between frontier harnesses and everything else is *tight edit→run→observe loops*. Claude Code/Codex-class agents run something after nearly every change and see the real output. Here, a worker can plausibly complete an entire MEDIUM task having never executed the code it wrote, and the first real signal arrives post-hoc at scoring time — where it becomes a score cap instead of a fix.

**Fixes:**
1. Make `runTests` language-aware by routing it through the `WorkspaceTestRunner` resolver (pytest/mvn/gradle/npm/cargo/go) — the resolver already exists and is used for scoring; the tool just doesn't use it.
2. Solve the DeepSeek-schema-stall problem *without* removing the capability: don't add a new tool to the schema — have the **server** auto-run the resolved test command after every Nth successful edit (or on `completeTask`) and inject the result as a tool-style observation. No schema change, no stall, every provider benefits. (The V239 VBC gate is 80% of this; it just fires too late and returns too little.)
3. VBC failure → repair loop: inject the actual failing test output + a bounded "fix and re-verify" continuation, not a bare refusal.
4. Give the reviewer the test runner and make `UNRESOLVED_ISSUES` optionally dispatch a bounded worker fix pass (the fix-build third pass already proves this pattern rescues runs — 4 of 30 sampled).

## Gap 3 — Context is unmanaged and unrecoverable

**What the code does:**

- Full conversation history per task; no sliding window. Both compactors default OFF in production (`worker.tool-compactor.enabled` V216, `worker.history-compaction.enabled` V222).
- When history-compaction *is* on (benchmarks), it triggers at a **fixed 60k input tokens** — 30% of a 200k window — and replaces evidence with a ≤1,200-token summary (TB review R2: "each firing discards 40–55k tokens of concrete evidence").
- Provider context-length error → `WorkerResult.ERROR`, no retry, no truncation fallback (`WorkerAgent.java:628-637`). A recoverable state becomes a dead run.
- Failed TB tasks burned 4.2× the input tokens of passing ones — thrash, not convergence — and cost scales with that raw volume even at 59% cache-hit.

**Fixes:**
1. Window-aware compaction ON by default: trigger at ~0.75× the model window (per YAML `context-window`), keep a token-budgeted tail, and produce a **structured** summary (files touched, current hypothesis, failing commands + exact errors, open TODOs) instead of free prose — that's the difference between Claude Code's compaction and "forgetting."
2. Context-overflow handler: catch the 400, compact/truncate, retry once — land it together with any compaction change (the TB review flagged exactly this pairing).
3. Never compact away the task message, the last N tool results, or exact expected-output literals (partially handled via skip-lists; make it a contract).

## Gap 4 — No parallelism and no subagents anywhere in the loop

- Tasks run strictly sequentially; `TaskWaveScheduler` computes conflict-free waves and emits them as an SSE event, then execution ignores them (`SequentialExecutor.java:663-702`).
- Within a turn, only pure read-only batches parallelize (virtual threads, flag-gated).
- There are **zero subagents**: no cheap-tier search/localization helper, no summarizer persona, no independent verifier. One flat conversation does everything, which is precisely why context bloats (Gap 3) and why exploration eats the guillotine budget (Gap 1).

**Fixes (in value order):**
1. A **localization subagent**: on task start against an existing repo, a LIGHTWEIGHT-tier agent (or even deterministic code) produces "relevant files + symbols + call sites" for the task; worker starts warm. This converts the guillotine problem into a non-problem for most tasks and is the cheapest big win here.
2. Enforce the wave plan for genuinely disjoint tasks (bounded by `max-parallel-tasks`, worktree-style isolation per wave) — wall-clock and plan-quality both improve since coherence issues surface earlier.
3. An **independent verifier** subagent at task end (fresh context: task + diff + test output → pass/fail + reasons) — the critic pass (ON, V231/V239) is this in embryo; give it tools and the test runner.

## Gap 5 — Static plan, amnesiac tasks

- The plan is frozen at the gate. No mid-run re-planning: divergence = task failure = skip/abort or same-task rerun via the continuation advisor. Nothing can add, split, reorder, or drop tasks based on what execution learned.
- Task N+1 does not see task N's summary — only fresh RAG (k=2, 800 tokens). The worker doesn't see the overall plan either. Cross-task coherence is delegated entirely to the post-hoc reviewer wiring pass.

**Fixes:** thread a rolling "run journal" (per-task completion summaries already exist for the ledger — inject the last few into the next task's prompt); allow one bounded re-plan pass when a task fails structurally (the CoherencePlannerAgent is the natural owner). Both are cheap; neither touches the model schema.

## Gap 6 — The harness is shaped by its weakest model

The pattern, three times documented: a capability feature helps strong models → native DeepSeek stalls on it → the feature is reverted **globally** (runWorkspaceTests prompt directive + V217 + V209, RL-2026-05-061; code-outline tool V234 held back for the same fear; TB guards tuned on DeepSeek misfired on Sonnet, TB review R1/R4). Meanwhile the single `SYSTEM_PROMPT_BASE`, the nudge texts, and the 6/8/12 thresholds are one-size-fits-all. Per-model prompt variants were approved as a data-gated follow-up (memory: `project_per_model_prompt_tuning`) and never built.

**Fix:** a per-provider **capability profile** (YAML, next to the model matrix): which optional tools bind, stagnation thresholds, nudge style, tool_choice forcing behavior, compaction trigger. DeepSeek keeps its lean schema; Sonnet/GPT get the full toolbox. This is the structural cure for both the reverts and the TB R1/R4 class.

## Gap 7 — Recovery and durability are coarse

- One smoke-retry per task, then PARTIAL — no error triage (transient vs structural vs cascading).
- Redis snapshots every 5 iterations; a crash loses in-flight work; resume is same-JVM only.
- Error handling is provider-generic; a rate-limit, an overload, and a schema rejection all look the same to the loop (the router's bounded retry helps transport-level only).

**Fixes:** checkpoint per tool-call (append-only journal, trivially resumable); classify provider errors into retry/step-up/fail lanes. Medium effort, mostly plumbing.

## Gap 8 — The feedback instrument can't validate any of this

The TB review's own admission: k=1 noise was ±3–5 tasks and "six months of tuning got feedback that was substantially random." Consequence visible in the flag table: **at least seven quality levers built and left OFF or reverted** (V209, V216, V217, V222, V233, V234, plus the code-outline class) because no measurement could prove them safe. The harness doesn't lack ideas — it lacks the instrument to graduate them.

**Fix (process, not code):** standing rule — no lever ships or reverts without a k≥3 eval on a fixed slice with error bars (DeepSeek pricing makes this ~$10–30); every flag gets a graduation criterion at creation or gets deleted. An ablation matrix (guards on/off × compaction on/off × verification on/off, one model, k=3) would settle in a week what six months of k=1 could not.

---

## What is genuinely good (keep, don't rebuild)

- **Tool layer**: 3-tier fuzzy `editFile` with read-before-edit and staleness gates; head-first byte-exact `readFile` truncation; spill-to-disk for big outputs. This is frontier-grade.
- **Bounded fail-open completion gates** (deliverable check, VBC, critic, verify nudge) — the *architecture* is right; they need to become repair loops instead of refusals.
- Lever B salvage, formatter discipline, reviewer fix-build third pass — each empirically rescued a real failure class.
- Cost tracking, `run_step_usage` attribution, event envelopes — better observability than most open harnesses.
- The continuation advisor's *evidence signals* (repeated failures, edit churn, stale explanations) are exactly the right stagnation features — they're just wired to post-hoc governance instead of the live loop.

## Priority order (impact × effort)

| # | Fix | Gap | Effort | Why first |
|---|---|---|---|---|
| 1 | Progress = evidence, not writes; repetition-based stagnation; no text-stop kills | 1 | S | Removes the ceiling on every hard task, all modes, all models |
| 2 | Language-aware `runTests` via `WorkspaceTestRunner` resolver | 2 | S | One file; unblocks verification everywhere incl. benchmarks |
| 3 | Server-side auto test-run + failure-output injection (schema-free); VBC → repair loop | 2 | M | The edit→run→observe loop, without the DeepSeek stall |
| 4 | Window-aware structured compaction ON + overflow retry | 3 | M | Kills the thrash-cost spiral and the dead-run overflow class |
| 5 | Localization subagent (warm-start file/symbol map per task) | 4 | M | Biggest single quality lever for existing-repo work |
| 6 | Per-provider capability profiles (tools, thresholds, nudges) | 6 | M | Ends the tune-to-DeepSeek death spiral |
| 7 | Run journal across tasks + bounded mid-run re-plan | 5 | M | Cheap coherence win |
| 8 | k≥3 eval gate + flag graduation policy | 8 | S (process) | Nothing above can be trusted without it |
| 9 | Enforced task waves; independent verifier subagent | 4 | L | After 1–6 land |
| 10 | Tool-call-granular checkpointing; provider-aware error lanes | 7 | M | Durability, lower urgency |

**One-line summary:** the harness under-trusts the model, under-verifies the code, over-trusts the transcript to fit in context, and runs everything single-file — and the machinery to fix all four already half-exists in the codebase behind flags that nobody could safely flip because the measurement was noise. Fix the instrument, relax the guillotines, mechanize verification, and let strong models use the tools that were reverted for DeepSeek's sake.

---

# Appendix — Implementation detail per priority item

## Item 1 — Progress = evidence, not writes; repetition-based stagnation; no text-stop kills

**Today** (`WorkerAgent.java`): `itersSinceProgress` resets only on write-class tools (`WRITE_CLASS_TOOLS` :245-255; `buildProject` counts only post-first-edit). PATCH pre-edit nudge at 6 (`PATCH_NO_EDIT_WARN_AFTER_N` :172), warn at 8 (:155), force-terminate at 12 (:164 → `COMPLETED_BY_STAGNATION` :1275-1321, Lever B salvage if zero writes). Text-only turn: one nudge, then `STOPPED_WITHOUT_COMPLETE` (:705-768). Nudge text tells the model to stop reading and commit to "the single most likely edit… even an imperfect edit beats zero" (:2229-2248).

**Change:**
1. **Novelty-based progress in the exploration phase** (pre-first-edit): any successful tool call whose `(tool, normalized-args)` hash is *new this task* resets the counter — reads, searches, shell included. Repeats don't. Keep a seen-set; the signature-hashing needed already exists for the repeated-failure evidence in the continuation signals.
2. **Repetition-based stagnation instead of tool-class-based**: fire the warn nudge when any of the already-computed evidence counters crosses threshold — repeated identical failing tool signature (`max-repeated-failures` logic), same-file edit churn (`max-edit-churn`), assistant explanations without new evidence (`max-stale-explanations`). These are currently computed for post-hoc `GOAL_DECISION` governance; move the computation in-loop (shared helper) and wire the live counter to it.
3. **Separate, generous exploration ceiling**: e.g. 40 novel-exploration iterations before a "commit to a hypothesis" nudge; per-provider value via the capability profile (Item 6). Hard termination only at wall-clock / cost / iteration budget.
4. **Text-only turns are never a kill signal**: first → corrective user message (as today); second → retry the same turn with `tool_choice=required` on the full tool set (the salvage path :1406-1415 already implements forced tool choice with an auto fallback for providers that reject it); only budget exhaustion terminates.
5. **Reserve `COMPLETED_BY_STAGNATION`** for true loops: ≥3 identical failing signatures *after* the nudge.
6. **Fix the nudge copy**: drop "even an imperfect edit beats zero" for production sources (customer PRs); optionally keep for benchmark PATCH where an empty diff auto-scores zero — gate the sentence on run source.

**Risks / mitigations:** runaway novel-read loops burn tokens → the novelty requirement plus the dollar/wall-clock budget bound it; weak models may wander longer → per-provider ceilings (Item 6). **Effort:** S–M, localized to `WorkerAgent` + a shared evidence helper. **Validate:** k=3 on the SWE Pro sample-20 + tier-eval-v2; expect `COMPLETED_BY_STAGNATION`/`STOPPED_WITHOUT_COMPLETE` share to collapse and Flash's no-usable-patch rate to drop; watch mean tokens/task for the wander regression.

## Item 2 — Language-aware `runTests`

**Today:** `RunTestsTool.java:314-340` hardcodes `python -m pytest <ids>`; the schema tells the model to prefer it over `shell` "for running tests" — actively misleading on Go/JS/TS/Rust/Java repos. Meanwhile `smoketest/WorkspaceTestRunner` + `BuildCommandResolver` already resolve pytest / mvn / gradle / npm / cargo / go for post-run scoring.

**Change:**
1. Route command construction through the same resolver family: `go.mod` → `go test <package>` or `go test -run <regex> ./...`; `package.json` → `npm test -- <pattern>` (or the detected runner: jest/vitest flags); `Cargo.toml` → `cargo test <filter>`; `pom.xml` → `mvn -q test -Dtest=<cls>`; gradle equivalent; pytest as today.
2. Generalize the `test_ids` argument to `tests` (ids, file paths, package names, or empty = suite/module) and map per-runner to filter flags. Keep the JSON envelope shape (`passed/failed/errored`) by reusing the scoring path's per-runner output parsers.
3. Rewrite the schema description language-neutrally; the "prefer this over shell" claim becomes true.
4. Container dispatch unchanged (the tool already consults the container store and wraps via `docker exec`); raise the tool's timeout ceiling for test context (suite runs on big repos exceed 300s — spill output as usual).

**Risks:** wrong runner detection on polyglot repos → fall back to the detected primary language of the touched files, else return an informative "no runner resolved" envelope so the model uses `shell` deliberately. **Effort:** S. **Validate:** unit tests per resolver; rerun the SWE Pro sample — 13/20 instances are Go/JS/TS and directly affected.

## Item 3 — Server-side auto test-run + failure injection; VBC → repair loop

**Today:** VBC (`worker.verify-before-complete.enabled`, ON via V239) server-runs the suite at first `completeTask` and refuses up to 3× (`WorkerAgent:939-956`), but the refusal message is only counts ("failed=N, errors=M") — the runner's failure detail is discarded (`:2358-2405`). The in-loop levers (V209 gate, V217 tool) are OFF since RL-2026-05-061 because *adding the tool to the schema* stalled native DeepSeek. The reviewer never runs tests.

**Change:**
1. **Schema-free auto-verify hook** in the worker loop: after every K successful write/edit iterations (K≈5, profile-configurable) — and always on completion intent — the server runs the resolved test command itself (module-targeted when derivable from touched files' nearest test dir/package; else a bounded quick suite; skip + cache when the workspace hash is unchanged since the last run).
2. **Inject the observation** as a structured user-message block: pass/fail counts, first N failing tests with truncated assertion output, spill path for the full log. No new tool in the schema → sidesteps the RL-2026-05-061 stall class entirely; every provider benefits.
3. **Upgrade VBC from gate to repair loop:** the refusal carries the failing test names + tracebacks + "fix these, re-verify, then completeTask." Keep the 3-refusal bound and fail-open semantics.
4. Flag `worker.auto-verify.enabled`, default per provider profile; K and module-targeting depth configurable.
5. Give the **reviewer** the same server-run capability so `UNRESOLVED_ISSUES` verdicts are grounded in executed tests, not reading.

**Risks:** slow suites (mitigate: module targeting, timeout, hash-skip); flaky tests poisoning the loop (mitigate: only block on failures in files related to the diff; report others as advisory after two consecutive identical failures). **Effort:** M. **Validate:** this is the lever the 2026-05-28 roadmap sized at +8–12pp on SWE-like sets — measure at k=3; also watch completion-with-red-tests rate go to ~0.

## Item 4 — Window-aware structured compaction + overflow retry

**Today:** `WorkerHistoryCompactor` triggers at a fixed 60k input tokens (:72) — 30% of a 200k window, calibrated in the DeepSeek-128k era — keeps first-2/last-8 messages and a ≤1,200-token free-prose summary (:85). OFF in prod, ON for TB where it destroyed evidence (TB review R2). Context-length errors from the provider are terminal: `ERROR`, no retry (`WorkerAgent:628-637`, `isTransientError` doesn't match the 400).

**Change:**
1. **Trigger at 0.75 × the model's context window** read from YAML `model-matrix` (the registry already carries per-model `context-window`).
2. **Token-budgeted tail**: retain as many recent messages as fit ~30% of the window, cut at assistant-message boundaries (the pair-safe cutting already exists :141-158).
3. **Structured summary contract** (4–8k tokens, not 1,200), with mandatory sections: task restatement · files read/edited with one-line roles · current hypothesis · **verbatim** key evidence (exact expected outputs, exact error text — copied, never paraphrased) · approaches tried and failed · next steps. Never compact: system prompt, original task message, last N tool results, any message tagged as carrying an exact contract.
4. **Overflow handler:** classify the per-provider context-length 400 → compact once → retry; second overflow → hard-trim middle → retry; only then `ERROR`. Land in the same change as the compaction flip (the TB review explicitly couples them).
5. Flip `worker.history-compaction.enabled` (and re-evaluate `worker.tool-compactor.enabled`) ON after the k=3 ablation of Item 8.

**Cache note:** each compaction rewrites the cached prefix (one-time miss) — triggering rarely at 0.75× instead of constantly at 60k is also the cache-friendly design. **Effort:** M. **Validate:** long-task token cost (TB showed failed tasks at 4.2× input volume — expect the spiral to flatten), zero overflow-ERROR runs, no regression on byte-exact/evidence-heavy tasks.

## Item 5 — Localization subagent (warm-start per task)

**Today:** the worker starts cold on existing repos: generic `SYSTEM_PROMPT_BASE`, the task text, and k=2 / 800-token RAG snippets. No file tree, no codemap, no symbol index reaches the worker (the architecture doc goes to the *planner* only, `codeintel.inject-arch.enabled`). Exploration must be done inside the loop — where Item 1's counters (today: guillotines) live.

**Change:**
1. **Deterministic layer** (no LLM, runs first): depth-capped file tree, `git grep` of task-derived identifiers, top-N candidate files by lexical/path match, the resolver's build+test commands.
2. **LIGHTWEIGHT-tier read-only subagent** (bounded ~10 iterations, tools: readFile/searchFiles/listFiles): "for this task, return relevant files, key symbols, call sites, and where this module's tests live" → structured JSON (schema-forced).
3. Inject as a bounded (~2–4k token) `REPO ORIENTATION` block in the worker's first user message, explicitly marked advisory ("verify before relying on it").
4. Project runs merge with the existing codemap instead of recomputing; benchmark PATCH runs get the identical treatment (localization is the dominant cost on SWE Pro's huge repos).

**Risks:** wrong orientation anchors the model → advisory framing + the worker's own tools correct it; +1 cheap LLM call per task (LIGHTWEIGHT tier ≈ cents). **Effort:** M (a new pre-task step in `CodeGenEngineImpl`/`SequentialExecutor` + one prompt + one schema). **Validate:** iterations-to-first-correct-edit (derivable from `run_step_usage` + task events), SWE sample resolved%, worker token volume.

## Item 6 — Per-provider capability profiles

**Today:** one `SYSTEM_PROMPT_BASE` for all providers; global 6/8/12 thresholds; one global tool bind (`ToolRegistry.bindForWorker`); per-provider differences exist *only* as PATCH iteration caps (220/150/180, `CodeGenEngineImpl:164-172`). Three documented incidents of capability features reverted globally because native DeepSeek stalled on schema additions (V217+V209 revert RL-2026-05-061; V234 held back; TB R1/R4 guard misfires on Sonnet).

**Change:** a `capability-profile` block per provider (or model class) in `application.yml model-matrix`:

```yaml
capability-profiles:
  anthropic:
    optional-tools: [runWorkspaceTests, codeOutline]
    stagnation: { exploration-cap: 48, warn-after: 12, force-after: 999 }  # budget-bound
    tool-choice-forcing: supported
    compaction: { trigger-ratio: 0.75, summary-tokens: 6000 }
    prompt-append: prompts/fragments/worker-anthropic.md
  deepseek:
    optional-tools: []           # lean schema — the stall class stays fixed
    stagnation: { exploration-cap: 32, warn-after: 8, force-after: 24 }
    tool-choice-forcing: fallback
```

- `ToolRegistry.bindForWorker(ctx)` consults the profile for optional tools; `WorkerAgent` reads thresholds from `WorkerTaskRequest` (per-task overrides already exist :348-355 — plumb profile → request in the engine).
- Per-provider prompt fragments ride the existing `PromptAssembler` include mechanism (the per-model prompt-variant follow-up was already approved, data-gated).
- Governance effect: a DeepSeek stall becomes a one-line profile edit, never a global revert; V217/V234-class tools re-enable immediately for Anthropic/OpenAI profiles.

**Effort:** M (YAML + plumbing; no new behavior). **Validate:** tier-eval-v2 per provider before/after; re-run the V217 A/B on Sonnet only.

## Item 7 — Run journal across tasks + bounded mid-run re-plan

**Today:** task N+1 sees nothing of task N (`SequentialExecutor` injects only the task description + constraints + fresh RAG); the worker never sees the plan; cross-task coherence is deferred entirely to the reviewer's wiring pass. The plan is immutable after the gate — failure paths are skip/abort (`shouldEnforceGoalStop` :597-624) or same-task rerun via the continuation advisor; nothing can amend the plan.

**Change:**
1. **Run journal:** after each task, append `{taskId, completionSummary, filesTouched, key decisions}` (all already produced — the ledger/`RunLedgerAgent` consumes the same data) to an in-run list; inject the last M entries (~1–2k tokens) plus a one-line plan overview into each subsequent task's user prompt.
2. **Bounded re-plan:** when a task fails with a structural signature (goal evidence: environment-block, plan-mismatch, repeated failures across tasks), invoke `CoherencePlannerAgent` once per run with {plan, journal, failure evidence} → it may split/reorder/drop *remaining* tasks. PRODUCTION mode: if the amendment changes scope/cost, surface it through the existing plan-gate approval machinery rather than auto-applying.

**Effort:** S (journal) + M (re-plan). **Validate:** reviewer wiring-pass issue counts and `UNRESOLVED_ISSUES` rate should fall; fewer ALL_TASKS_FAILED cascades from one bad early task.

## Item 8 — k≥3 eval gate + flag graduation policy

**Today:** k=1 iteration ("six months of tuning got feedback that was substantially random" — TB review A1); ≥7 built-but-dormant levers (V209, V216, V217, V222, V233, V234 + code-outline class) with no graduation criteria; reverts also decided on k=1 evidence.

**Change (process + thin infra):**
1. Standing rule: any worker/control-loop change (and any revert) ships with a k=3 eval on a fixed slice — SWE Lite dev-23 or Pro sample-20 on DeepSeek (~$3–10), tier-eval-v2 for schema/tool-call validity — reported as mean ± spread. The drivers already exist (`v2-eval.sh`, `tier-eval-v2.sh`, `run-iter2.py`); the missing piece is a thin wrapper that runs k passes and aggregates.
2. Every feature flag carries, at creation: graduation criterion, owner, expiry ("flip ON if k=3 shows quality ≥ baseline and tokens −20%; else delete by <date>"). Apply retroactively to the seven dormant flags.
3. Run the **ablation matrix once** — {guards on/off} × {compaction on/off} × {auto-verify on/off}, one model, k=3 — to decide Items 1/3/4 empirically instead of by argument. ~$30–60 on DeepSeek.
4. Feed results into `benchmark_run` rows so the continuation advisor's benchmark priors improve as a side effect.

**Effort:** S. This item is the precondition for trusting every other item.

## Item 9 — Enforced task waves + independent verifier subagent

**Today:** `TaskWaveScheduler.scheduleBounded` already computes dependency waves with file-touch conflict detection (`SequentialExecutor.java:663-702`) — then emits them as an advisory SSE event and executes sequentially. The critic pass is a single no-tools LLM call (:2460-2491).

**Change:**
1. **Waves:** execute wave members concurrently on `PipelineExecutorPool` (cap `max-parallel-tasks`), each task in an isolated per-task worktree of the repo; merge back in wave order; run the `buildProject` smoke at the wave boundary; on merge conflict, serialize the conflicting task (fall back to today's behavior). Multi-repo runs already isolate per-repo directories — the new work is per-task worktrees within one repo and the merge step.
2. **Verifier subagent:** at task end, a fresh-context agent (input: task, `gitDiff`, build/test output; tools: read-only set + the Item-2 test runner; WORKHORSE tier) returns `{verdict, missingRequirements[], evidence}` via forced schema. FAIL → one bounded worker repair pass seeded with the verifier's findings — generalizing both the critic pass and the reviewer third-pass pattern that already demonstrably rescues runs.

**Effort:** L — real concurrency and merge semantics; sequence after Items 1–6. **Validate:** wall-clock per run (waves), verified-score and REVIEWER_UNRESOLVED rate (verifier).

## Item 10 — Tool-call-granular checkpointing + provider error lanes

**Today:** Redis snapshot every 5 iterations at iteration boundaries (`v2:agent-ctx:{runId}:{taskId}`, 24h TTL); a crash loses in-flight work; resume is same-JVM only. Provider errors collapse into transient-vs-not (`isTransientError` bounded retry); rate-limit, overload, schema-rejection and context-overflow all look alike to the loop.

**Change:**
1. **Append-only per-task journal** (JSONL on disk: iteration, tool calls, result envelopes, message deltas) written synchronously after each dispatch → exact history reconstruction on resume; keep the Redis snapshot as a fast pointer. This is also the substrate for the cross-JVM resume via GCS already scoped in the reason-log, and the trajectory exporter the SWE-bench submission needs (two birds).
2. **Error lanes** on the router exception path: 429/529 overload → existing backoff retry; context-length 400 → Item-4 handler; schema/tool-rejection 400 → strip optional tools per profile + retry once + record against the profile; transport 5xx → existing bounded retry; auth/permanent 4xx → fail fast. Stamp the error kind into `run_step_usage` rows for observability.

**Effort:** M, mostly plumbing. **Validate:** chaos test (`kill -9` mid-task → resume equivalence); error-lane counts in Grafana.

## Suggested sequencing

| Bundle | Items | Rationale |
|---|---|---|
| A (week 1) | 8, 1, 2 | Instrument first; then the two small changes with the largest ceilings |
| B | 3, 4 | The verification loop + context discipline, decided by the Item-8 ablation |
| C | 5, 6, 7 | Warm-start, per-provider profiles, coherence |
| D | 9, 10 | Concurrency + durability, after the loop is healthy |

---

# Deep dives — one item at a time

These go beyond the implementation appendix: why each item is (or isn't) the real problem, the design alternatives I considered, second-order effects, and what evidence would change my mind.

## Deep dive 1 — The progress/stagnation engine

**The uncomfortable truth this item encodes:** the stagnation engine isn't a safety net that occasionally misfires — it is an *opinion about how coding works*, hard-coded as constants. The opinion is: "reading is procrastination, writing is work." That opinion is approximately true for the workload the harness grew up on (small Lite bugs, greenfield BUILD tasks where the first correct action *is* to write) and categorically false for the workload the product is aimed at (existing enterprise repos, multi-repo runs, long-horizon patches). The constants 6/8/12 are fossilized DeepSeek-on-Lite behavioral data.

**Why I'd treat this as the #1 item even though Item 3 (verification) has the bigger headline pp-estimate:** every other improvement is throttled by this one. A localization subagent (Item 5) reduces exploration need but doesn't eliminate it; auto-verification (Item 3) *adds* non-write iterations (running tests is "no progress" under the current counter!) — meaning the current engine actively punishes the exact behavior Item 3 wants to encourage. Note the interaction bug that already exists today: a worker that dutifully follows the VERIFY directive — edit, then buildProject, then runTests, then read the failure, then think — accumulates stagnation iterations for everything after the edit unless buildProject bails it out (it counts post-first-edit only). The engine and the prompt disagree about what good behavior looks like, and the engine wins because it holds the kill switch.

**Design alternatives I considered and rejected:**
- *Just raise the thresholds* (12 → 40). Rejected as the primary fix: it keeps the wrong signal, only later. A model stuck re-reading the same file 35 times sails through; a model doing 41 productive novel reads on a monorepo still dies. Wrong metric, any threshold.
- *Remove stagnation control entirely, rely on wall-clock/cost.* Tempting and closest to what Claude Code does (no write-quota at all), but the TB data shows genuine pathological loops exist (schemelike: 3.2M tokens over 124 iterations). You want *loop detection*, just not *exploration detection*.
- *LLM-judged progress* (a cheap model grades "is this agent making progress?"). Rejected: adds latency and cost to every iteration to answer a question that repetition-hashing answers deterministically.

**The design I'd actually build:** deterministic novelty tracking. Hash `(tool, normalized-args)`; novel hash = progress. Three repetition counters (same failing signature, same-file churn, explanation-without-new-evidence) — all three already exist as continuation-advisor evidence, computed *after the run for governance dashboards*. That's the deepest irony in this codebase: the correct stagnation detector was built, then pointed at Grafana instead of the loop. Moving it in-loop is mostly a refactor, not new invention.

**Second-order effects to watch:** (a) Token cost rises on models that explore enthusiastically — this is *correct* spend but will look bad on the cost dashboard; pre-agree the budget ceiling with the founder so the first expensive-but-passing run doesn't trigger a panic revert. (b) Lever B salvage fires much less often — good (it exists to compensate for forced kills), but empty-patch rates on weak models must be re-measured since salvage was quietly propping them up. (c) The nudge copy change ("imperfect edit beats zero") interacts with scoring: on customer runs, a forced bad edit becomes reviewer load and trust-cap damage; the sentence was only ever rational for benchmark diff-scoring.

**What would change my mind:** if the k=3 ablation (Item 8) shows guards-off/novelty-on *loses* to the current engine on DeepSeek while winning on Sonnet — then the right answer is not "keep the guillotine" but "per-provider thresholds" (Item 6), because the product roadmap depends on strong models performing at their ceiling.

## Deep dive 2 — Language-aware `runTests`

**Why such a small item ranks so high:** it's not really about the tool — it's about *the schema being a lie*. The description says "use this instead of shell for running tests — it extracts assertion errors and counts cleanly." A model that trusts its tools (which is exactly what we want models to do) calls `runTests` on a Go repo, gets a pytest-not-found failure, and has now learned mid-task that the harness lies to it. The behavioral damage exceeds the wasted turn: models generalize distrust, and start shelling around *all* the structured tools — which is precisely the pathology TB measured with Sonnet (3,009 shell calls vs 24 editFile; the deliverable-extraction machinery got bypassed because the model had learned the structured path was unreliable). Schema honesty is a compounding asset.

**Design notes beyond the appendix:** the resolver must return *structured capability*, not just a command string: `{runner, command, filterSyntax, cwd}`. The interesting cases: monorepos with multiple `package.json`s (resolve from the *touched files'* nearest manifest, not repo root); Go's per-package test model (map "test ids" to `-run` regex + package path — and surface Go's `[no test files]` output as a first-class "no tests in package" result, since the SWE-bench RCA already showed a classifier misreading that exact string); JS runner ambiguity (read `scripts.test` rather than guessing jest vs vitest). I would *not* build per-runner failure parsing beyond pass/fail counts + raw tail — over-parsing is where these tools go to die; the model reads raw assertion output perfectly well.

**The trap to avoid:** don't silently run something different from what the model asked for. If the model passes pytest ids on a Go repo, return an explicit error naming the detected runner and the expected filter syntax. A tool that "helpfully" reinterprets requests is a subtler version of the schema lie.

**Interaction:** this tool is a hard dependency of Item 3 (auto-verify runs the same resolver server-side) and of Item 9's verifier subagent. Building it first is what makes Bundle B cheap.

## Deep dive 3 — Auto-verification: the edit→run→observe loop

**My core thought:** the RL-2026-05-061 revert was the single most consequential wrong turn in this harness's history, and it was wrong in an instructive way. The observed fact was "adding `runWorkspaceTests` to the schema makes native DeepSeek emit reasoning-only turns." The inference drawn was "in-loop test execution is dangerous." The correct inference was "*schema growth* is dangerous for this model" — an inference the codebase itself later re-confirmed (V234 code-outline held back for the same reason, TB R6 measured 40 unknown-tool calls from schema bloat). The capability and the delivery mechanism got conflated, and the capability — the one thing that most separates frontier harnesses from the rest — was sacrificed to save the delivery mechanism.

**Why server-side injection is the right shape** (and not, say, re-adding the tool for capable models only, which Item 6 also enables): because the decision "should we check that the code still works?" should never have been delegated to the model in the first place. Claude Code delegates it to the model because it can't run anything without permission; a server-side harness has no such constraint. Determinism here buys three things: it fires on *every* model including the ones too weak to self-verify (which is exactly when it's most needed); it can't be gamed by completion-eagerness; and it produces uniform telemetry (tests-green-at-completion becomes a real, comparable metric across providers).

**The hard design problems, honestly:** (a) *When to fire.* Every-K-edits is a blunt heuristic; the better trigger is "edit burst ended" — fire when an iteration contains no write after iterations that did. (b) *What to run.* Full suites are unaffordable in-loop on big repos; nearest-test-dir targeting will be wrong sometimes. I'd accept wrongness: a targeted-but-imperfect signal every few edits beats a perfect signal never. (c) *Flakes.* The mitigation in the appendix (only block on failures touching the diff's blast radius) is the load-bearing part — without it, one flaky suite turns the repair loop into a tarpit. (d) *Cost*: each auto-run injects output into history; with Item 4's compaction absent, this measurably accelerates context growth. Bundle B pairs 3+4 for exactly this reason — shipping 3 without 4 trades one failure mode for another.

**What would change my mind:** if the ablation shows models ignore injected results they didn't ask for (plausible — unsolicited observations can be attended to weakly), the fallback design is *conditional injection*: inject only a one-line result, with the full failure spilled behind a readFile path; the model's own retrieval act restores salience.

## Deep dive 4 — Context engineering

**The reframe:** this item is usually pitched as cost control, but the TB data says it's a *correctness* problem: failed tasks burned 4.2× the input tokens of passing ones. That's not "failure is expensive" — it's bidirectional causation. Context bloat degrades attention over the middle of the window, degraded attention causes re-reading and re-derivation, which bloats context further. The thrash spiral is a quality failure that happens to show up on the invoice. Meanwhile the one deployed compaction config (TB benchmark) made things *worse* by destroying evidence at 60k tokens — so the org's lived experience is "compaction hurts," which is true only of *that* compaction.

**The design conviction I'd defend:** the summary must be a **structured dossier, not prose**. The 1,200-token free-prose summary fails because an LLM summarizer, unguided, preserves narrative ("the agent then explored the parser module") and discards exactly what matters (the byte-exact expected output read from a test file). The section contract in the appendix — with *verbatim-copy* rules for error text and expected outputs — is the whole game. I'd go further: the compactor prompt should be told it is writing a handoff for an engineer with amnesia, and that paraphrasing an error message is a firing offense. Cheap to A/B: compact a thrashed transcript both ways, resume, measure recovery.

**On the overflow handler:** the current behavior (provider 400 → `ERROR`, run dead) is the only place in the harness where a *fully recoverable* state is treated as fatal. Even a dumb handler (drop middle 50%, retry once) strictly dominates. The reason it must ship *with* the compaction flip is already documented in the TB review: compaction currently masks overflow; turn compaction off (or trigger it later at 0.75×) and the overflow path starts firing for real.

**Second-order:** compaction rewrites the cache prefix — each firing is a one-time full-context cache miss. At 0.75× trigger this happens ~once per very long task, which is fine; at 60k it happens constantly, which is partly where TB's cost went. Trigger ratio is therefore *both* a quality and a cache-economics knob — worth exposing in the per-provider profile (Item 6) since window sizes differ radically (DeepSeek 1M vs Sonnet 200k).

## Deep dive 5 — Localization subagent

**Honest assessment of impact:** this is the item I'd bet on for *customer-visible* quality on existing-repo work, more than benchmarks. On SWE-bench the model at least gets an issue that names symptoms; a customer spec ("add rate limiting to the export endpoints") names nothing, and today's worker gets 800 tokens of RAG and a cold prompt. The gap between "agent that knows where things are" and "agent that must find out" is most of the felt difference between Cursor/Claude Code in a warm repo and a cold API call.

**Why a subagent and not more RAG:** the existing pgvector RAG answers "what text is similar to this spec" — the wrong question. Localization needs "what would I have to touch, and what calls it," which is a *navigation* task (grep → read → follow imports), not a retrieval task. A LIGHTWEIGHT-tier agent doing 10 read-only iterations is embarrassingly cheap (~1-3 cents) and answers the right question. The deterministic layer (tree + git grep) exists so the subagent starts warm too, and so the feature degrades gracefully to still-useful output if the LLM layer fails.

**The anchoring risk, taken seriously:** a wrong orientation block is worse than no block for weak models, which treat it as ground truth. Three mitigations, in order of importance: (1) the block lists *candidates with evidence* ("`RateLimiter.java` — matched 'rate limit', exports `checkQuota()` called from 3 controllers"), never conclusions; (2) confidence-tiered phrasing; (3) the subagent must output "unclear — the spec could map to X or Y" when true, and the worker prompt must treat that as a real answer. If eval shows anchoring damage anyway, the fallback is to demote the block to a tool (`getOrientation`) the model can pull — worse ergonomics, zero anchoring.

**Interaction with Item 1:** these two items are partial substitutes — warm-start reduces exploration need; relaxed guards make exploration survivable. Shipping only Item 5 while keeping the guillotines would be the classic mistake: it makes the *average* task fine and leaves the *hard* tasks (where orientation is wrong or insufficient) exactly as capped as today. Hard tasks are where the reputation lives.

## Deep dive 6 — Per-provider capability profiles

**The strategic point beneath the plumbing:** ExecuteSpec's business model is multi-provider (BYOK, model matrix, managed tiers) but its *harness* is single-provider in disguise — one prompt, one control loop, all tuned on whichever model iteration was cheapest that month. That produces a systematic bias with a nasty commercial consequence: the harness delivers the *least* differentiation exactly on the *strongest* models — the ones enterprise buyers bring — because every affordance strong models exploit (bigger toolsets, longer autonomy, self-verification) was sanded off for DeepSeek. The TB matrix already shows the symptom: Sonnet 4.6 scored *below* DeepSeek Flash (42 vs 46) on a harness whose guards were DeepSeek-shaped. Sonnet isn't worse than Flash at terminal tasks; the harness made it worse.

**Scope discipline — where profiles should stop:** profiles should cover *mechanical affordances* (which optional tools bind, thresholds, tool-choice forcing, compaction trigger, maybe a short prompt fragment). They should *not* fork the core prompt into per-provider variants wholesale — that path leads to N divergent prompts, each overfit to k=1 observations, unmaintainable and untestable (the per-model-prompt-tuning memory is rightly gated on evidence of *prompt-fixable* failures). Rule of thumb: a profile entry must be justified by a *reproducible mechanical failure* (schema stall, unsupported tool_choice), not by vibes about a model's "style."

**The governance win is bigger than the perf win:** today, every capability experiment is a global bet — if it hurts any one model, it reverts for all. Profiles convert that into per-lane bets, which changes what the team can safely *try*. The V217 story replays as: enable for Anthropic profile, measure, expand — no revert pressure. This is the item that stops the harness from being shaped by its weakest model *permanently*, not just fixing today's instance of it.

## Deep dive 7 — Run journal + bounded re-plan

**Why this is smaller than it looks (and that's fine):** the wiring-pass reviewer already exists as a *post-hoc* patch for cross-task incoherence, and empirically catches the endpoint-not-wired class. The journal moves a slice of that from cure to prevention. I rank it below 1–6 because its failure mode is visible and bounded (reviewer catches it, score reflects it) whereas Items 1–4's failure modes are silent caps. But it's near-free — the summaries already exist for the ledger — and the cheapest fixes should ship even when mid-impact.

**The one deep thing here:** *plan visibility.* Today the worker doesn't know it's task 3 of 7, doesn't know task 5 will build the UI for the API it's writing, and so makes locally-reasonable, globally-wrong choices (naming, file placement, interface shapes). Injecting the plan outline + journal is how each task becomes a *member of a run* rather than an isolated job. Frontier equivalents: Claude Code's todo list isn't primarily a UX feature — it's the mechanism that keeps a long session globally coherent.

**On re-plan, a warning from the other direction:** unbounded re-planning is how agent systems dissolve into churn (plan → fail → replan → new failure mode → replan…). The "once per run, structural signatures only, scope changes re-gate" bounds in the appendix aren't conservatism theater — they're the difference between a repair mechanism and an instability. If one re-plan doesn't save the run, the run's problem isn't the plan.

## Deep dive 8 — The instrument

**This is the meta-item, and my strongest-held opinion in the whole assessment:** the harness doesn't primarily have a quality problem — it has an *epistemology* problem, and the quality problem is downstream. Consider the record as a decision audit: V217 built then reverted on k=1 evidence; the TB "Pro 55→49 overfitting regression" — a conclusion later withdrawn as pure noise; the Flash "iteration-2 prompt verdict" currently being read off a 20-instance k=1 delta whose noise band exceeds any plausible prompt effect; seven levers dormant because nothing could prove them safe. Every one of these is the same failure: *decisions sized smaller than the noise floor of the measurement backing them.* An org that fixes Items 1–7 without fixing this will un-fix them within a quarter, one plausible-looking k=1 regression at a time.

**Why this is cheap now in a way it wasn't in March:** the TB era's excuse was that flagship reruns cost $136 a pass. DeepSeek at ~$0.4–1.5/pass removed the excuse; the marginal cost of *never being fooled by noise again* is about the price of lunch. The only real cost is cultural — mean±spread is slower and less satisfying than a number that moved.

**What I'd add beyond the appendix:** (a) a **regression sentinel** — the fixed 25 always-pass TB tasks (and the SWE equivalents once known) as a cheap canary suite run at k=1 on every harness change; always-pass tasks flipping is *signal* even at k=1, which is what makes them the right canary. (b) **Decision-sized sampling**: flag flips need k=3/N≥50; directional exploration can stay cheap. Codifying *which decisions need which instrument* prevents the rule from becoming either theater or bureaucracy.

## Deep dive 9 — Waves + independent verifier

**On waves — a candid demotion:** I put this in Bundle D deliberately. Parallel task execution is the most conspicuous frontier feature the harness lacks, and also the one with the worst risk/reward *right now*: it multiplies whatever the loop does, including its pathologies, and adds merge semantics — a real source of new failure modes — for a payoff (wall-clock) that customers feel less than they feel quality. Parallelize a healthy loop, not this one. The one prerequisite worth doing early: per-task worktree isolation is the same machinery Item 10's checkpointing wants, so the infra can be shared.

**On the verifier — the more interesting half:** the deep principle is *separation of author and assessor*. The worker cannot be trusted to grade its own work — not because models lie, but because the transcript that produced a bug is the transcript least able to see it (same blind spots, same assumptions, now reinforced by sunk cost). A fresh-context verifier with the diff, the task, and a test runner has none of that. TB's 32-of-47 "self-certified done, verifier disagreed" failures are this principle measured. The existing critic pass is the right instinct at 10% strength: no tools, no test run, one shot, fresh *eyes* but no *hands*. Give it hands.

**Design line I'd hold:** the verifier *reports*; the worker *repairs*. A verifier that edits becomes a second author with the same self-grading problem, plus attribution soup. One bounded repair pass seeded with the verifier's findings, then re-verify, then stop — mirroring the reviewer third-pass shape that already empirically rescues runs.

## Deep dive 10 — Durability + error lanes

**Why last, and why still real:** these don't lift scores; they stop entropy from taxing everything else. Two observations elevate it above housekeeping. First, the journal is *triple-purpose*: crash-resume, the cross-JVM resume that pause/resume and GCS shipping already want, and — immediately — the trajectory exporter the SWE-bench Pro submission requires (event logs die in 24h Redis TTL; Gate B explicitly needs per-instance transcripts written to disk *during* the run). One design, three open needs. Second, error-lane classification is the missing *sensor* for Item 6's governance: today "DeepSeek stalls on big schemas" is folklore reconstructed from postmortems; with schema-rejection as a first-class error kind stamped into `run_step_usage`, it becomes a queryable rate per provider — and profile decisions (which tools to bind where) get made on data instead of memory.

**Implementation conviction:** append-only JSONL, written synchronously after each dispatch, replayable without the original process. Resist the temptation to make it a database — the value is that it's boring. The Redis snapshot stays as a fast pointer; the journal is truth.

---

# Tool-layer analysis

Full audit of `tool/` — every tool bound via `ToolRegistry.bindForWorker()` (:109-118; only `gitCommit` is denied, `WORKER_DENY_TOOLS` :65), schemas, envelopes, caps, and gates. Verdict up front: **the individual tools are the best-engineered layer in the harness — and the toolset as a whole undermines them.**

## What the worker actually sees

**Core tools (16 always + 4 flag-gated):** `readFile`, `writeFile`, `editFile`, `listFiles`, `searchFiles`, `gitStatus`, `gitDiff`, `buildProject`, `runTests`, `shell`, `install`/`test`/`lint`/`format` (ProjectCommandTool), `completeTask`; flag-gated: `backgroundJob` (V233 OFF), `readCodeOutline` (V234 OFF), `runWorkspaceTests` (V217 OFF), `verifyBoot` (V227 OFF).

**Plus ~15 "compatibility" tools bound alongside them** (`CompatibilityTools.java:29-1358`): `read_file`, `write_file`, `edit_file`, `grep`, `multiEdit`/`applyEdits`, `glob`, `move`, `delete`, `bash`, `background_process`, `web_fetch`, `web_search`, `diagnostics`, `ast_edit`, `lsp`, `plan`, `spawn_subagent`, `mcp_client`, plus `unavailable_protocol_*` stubs.

Net: **~35 registered, ~20–25 bound per call, ~6,750 tokens of schema on every worker LLM call.** Claude Code ships ~15 tools with zero aliases.

## Finding T1 — The duplicated toolset is the tool layer's biggest defect

The worker sees `readFile` *and* `read_file`, `shell` *and* `bash`, `searchFiles` *and* `grep`, `editFile` *and* `edit_file` *and* `multiEdit` *and* `ast_edit` (which is just an alias of edit_file — the name promises AST awareness that doesn't exist). Consequences:

1. **Split behavior, split telemetry.** Two models (or the same model on different days) route the same intent through different names; per-tool metrics, gates, and skip-lists silently cover only one path. Worse: the duplicates have **different error semantics** — `shell` returns `Ok` with `exit=N` embedded for the model to parse, while `bash` returns a structured `COMMAND_FAILED` error on non-zero exit (:639). Whether the model "sees" a failure depends on which alias it happened to call.
2. **Gate bypass.** The read-before-edit ledger, WRITE_CLASS progress counting, and compactor skip-lists are keyed by tool name. Every alias is a chance for a write to flow through a name the guards don't recognize (the WorkerAgent WRITE_CLASS list does include `multiEdit`/`applyPatch` variants — but the fact that this must be maintained by hand across aliases is the smell).
3. **Schema-token bloat on the model most sensitive to it.** The org's own documented failure class (RL-2026-05-061: DeepSeek stalls when the schema grows; TB R6: 40 unknown-tool hallucinated calls) is aggravated by shipping ~2× the necessary schema on every call. Flag-gated tools are correctly absent-when-off — and then the compat layer gives back all the bloat the flags saved.
4. **Trust erosion.** `lsp` is a stub, `spawn_subagent` is "bounded reconnaissance" not real dispatch, `ast_edit` is a lie in the name, `unavailable_protocol_*` returns UNAVAILABLE. Every stub a model calls teaches it the tool surface is unreliable — the same pathology as the pytest-only `runTests` (Deep dive 2), multiplied.

**Fix:** one canonical toolset (~12 tools) for server-side workers; bind compat aliases only for the session types that need client parity (local-hands CLI), selected by session/run type at `bindForWorker`. This is a binding change, not a rewrite. Expected side effects: lower unknown-tool rate, honest per-tool telemetry, and a measurable schema-token cut per call.

## Finding T2 — Inconsistent conventions invite the hallucinated-tool class

camelCase core (`readFile`, `oldStr`, `startLine/endLine`) next to snake_case compat (`read_file`, `old_str`, `offset/limit`). TB measured the result: models invent `executeCommand`, `terminalCommand`, `glob`-with-wrong-args, `diagnostics`. Models arrive pre-trained on the conventions of the big harnesses; every deviation and every inconsistency is a tax paid in wasted turns. Pick one convention (snake_case matches what current models see most), alias nothing, and keep parameter names aligned with the dominant harnesses where possible (`old_string/new_string`, `offset/limit`).

## Finding T3 — `gitDiff` is capped at 3,000 chars, making self-review impossible

`GitDiffTool.java:21` — `MAX_DIFF_CHARS = 3_000`, no path argument, no paging. A worker that wants to review its own multi-file change before `completeTask` (exactly what the self-verify checklist and the critic want) physically cannot see it: a 5-file patch blows the cap. And `gitDiff` sits on the compactor skip-list as "already short" — short by amputation. Meanwhile every other bulky tool got the `ToolOutput.budget()` spill-to-disk treatment. **Fix:** route gitDiff through the same budget (6KB inline + spill + readFile path) and add optional `path`/`staged` args. Trivial; directly serves Items 3 and 9.

## Finding T4 — The write/edit split has a hole: nothing can rewrite a file

`writeFile` refuses existing files (`ALREADY_EXISTS` :109); `editFile` requires existing files and exact-match oldStr. So wholesale regeneration of a mangled or >60KB file has no sanctioned path — the model's options are a giant fragile `editFile` whose oldStr must byte-match a file it can only read in 60KB windows, the compat `delete`+`write_file` pair (alias-dependent), or shelling around the guards (which the prompt forbids: no `sed -i`, no `>` redirects). Frontier harnesses allow overwrite *guarded by read-recency* — the same ledger EditFileTool already consults. **Fix:** `writeFile` accepts `overwrite=true` gated on a fresh read of the file (reuse `ReadFileStateStore`); keeps the hallucination protection, closes the hole.

## Finding T5 — Verification tools are the weakest members of an otherwise strong family

Already covered as Items 2/3 but confirmed at the schema level: `runTests` hard-schemas `test_ids` as pytest ids (`minItems 1` — you cannot even ask for "the suite") and its description over-promises ("use this instead of shell"). `runWorkspaceTests` — the tool that actually resolves pytest/mvn/gradle/npm/cargo/go and returns a clean verified envelope — is exactly the right design **and is flag-OFF (V217)**. `verifyBoot` (deterministic HEALTHY/BUILD_FAILED boot verdict — genuinely ahead of frontier practice for app-shaped work) is **flag-OFF (V227)**. `backgroundJob` — required to test any server without blocking — is **flag-OFF (V233)**, so a worker building a web service cannot actually run it while probing it. The best verification tools in the codebase are all dark. Also note the remote-contract bug (TB R6): `runTests` schema requires `test_ids` while the Harbor adapter expects `command` — every schema-compliant remote call fails.

## Finding T6 — Small ergonomic gaps that cost turns

- **`searchFiles` has no context lines** (`-A/-B/-C` equivalent) and clamps lines at 240 chars — a match is often unusable without its surroundings, forcing a follow-up readFile per hit. Add `contextLines` (cap ~5).
- **`listFiles` 500-entry cap with no depth control** — on a monorepo, `recursive=true` burns the cap on the first vendored corner it doesn't hide; add `maxDepth`.
- **`shell` is stateless per call** (fresh process, no cwd/env persistence, no stdin) — every call from a subdirectory pays a `cd &&` prefix; fine for now, but it's the same substrate gap TB Part-B item 3 (persistent tmux) identified for terminal work.
- **No image input anywhere** — the product accepts `figmaUrl` at run level, but no tool lets the worker *see* a design or screenshot. For frontend work this is a product-level gap vs. every frontier harness.
- **`readCodeOutline` is Java-only** and OFF — the outline concept is right (symbol map → range read); tree-sitter would make it universal for ~the same cost.

## What is genuinely excellent (protect these)

- **Read-before-edit + staleness ledger** (`ReadFileStateStore`, RL-2026-05-051) — stricter than Claude Code, and correct: it kills the hallucinated-edit class at the tool boundary.
- **Head-first byte-exact `readFile` truncation** with the range-read escape hatch (RL-2026-05-062) — the truncation *notice text itself* teaches the recovery path. Textbook tool design.
- **`ToolOutput.budget()`** — 6KB inline + full spill to `.agent-context/tool-output/` + a readFile path. Never destroys evidence (the TB adapter's failure to replicate this remotely is what R3 measured).
- **Batch-atomic `editFile`** (all-or-nothing multi-edit) + 3-tier matching with actionable error messages naming the fix (`replaceAll`, `occurrence`, add context).
- **Maven dependency preflight** in buildProject (hallucinated deps fail fast with a precise message) — ahead of frontier practice.
- **ripgrep fast-path with Java fallback** in searchFiles; env-whitelisted shell; vendored-path commit guard; gitignore-aware glob.

## Tool-layer priority list

| # | Fix | Size |
|---|---|---|
| T1 | Single canonical toolset per session type; unbind compat aliases from server workers | S |
| T2 | One naming convention; align param names with dominant-harness conventions | S (with T1) |
| T3 | `gitDiff` through `ToolOutput.budget()` + path/staged args | XS |
| T4 | `writeFile overwrite=true` gated on read-recency | XS |
| T5 | Ship Item 2 (language-aware runTests); flip `verifyBoot`+`backgroundJob` for app-shaped BUILD runs via per-provider profiles; fix the remote `runTests` contract | S–M |
| T6 | searchFiles contextLines; listFiles maxDepth; tree-sitter outline (later) | S each |

The through-line matches the main assessment: the primitives are frontier-grade; the *composition* (duplication, dark flags, one lying schema, one starved diff tool) is what a strong model actually experiences — and it experiences it as an unreliable toolbox.

---

# Evaluation against the primary idea

The product thesis is an **economic orchestration layer over interchangeable models**, resting on four pillars:

1. **Model rating per job** — tier models to match job difficulty
2. **Mid-run model switch** — escalate when the model can't solve the task
3. **Prompt caching across** — keep the economics viable
4. **Task breakdown on submission** — decompose to manage execution

Verdict up front: **the thesis is sound and genuinely differentiated — no frontier harness does policy-governed multi-model economics — but every pillar is currently starved by the same two deficits: a harness that isn't model-neutral, and a measurement layer that can't produce trustworthy ratings.** The 10 items in this assessment are not a competing agenda; they are the pillars' missing prerequisites.

## Pillar 1 — Model rating per job. Idea: A. Implementation: C+ (routing A−, *rating* D)

**What's real:** the routing plumbing is genuinely excellent — YAML single-source-of-truth, the `ModelResolver` chokepoint every agent funnels through, per-org matrix overrides, complexity-routed worker tiers, per-run pins with `routePinned` semantics. This is better model-routing infrastructure than any open harness has.

**What's hollow:** the *rating* half. "Model rating per job" requires measured per-category model performance, and the data supply is broken three ways:

- **The ratings are noise.** Benchmark priors gate on 20+ samples and category compatibility (good design), but the underlying `benchmark_run` rows are k=1 measurements with a documented ±3–5-task flip band. The eval-methodology memory itself says prior SWE numbers are invalid. Below the priors sits hand-authored `quality-class` YAML — folklore, not measurement.
- **The harness corrupts its own ratings.** Because the control loop is DeepSeek-shaped (Gap 6), strong models under-score *through this harness* — TB measured Sonnet 4.6 below DeepSeek Flash. Any rating collected via the current loop mis-ranks exactly the models the rating system exists to differentiate. Pillar 1 structurally requires a model-neutral harness; there isn't one.
- **"Per job" is really "per plan-time guess."** Complexity is assigned once by a LIGHTWEIGHT classifier before execution. There is no post-hoc reconciliation (was this LOW actually LOW?) feeding back into classification — the rating loop is open.

**What closes it:** Item 8 (the instrument — pillar 1's data supply, literally), Items 1+6 (model-neutral loop so ratings mean something), plus one new cheap piece: log predicted-vs-actual difficulty per task (iterations, escalations, outcome) and feed it back to the classifier. **The pillar is buildable; today it's tiering by price class + folklore.**

## Pillar 2 — Mid-run model switch. Idea: A−. Implementation: C

**What's real:** the continuation advisor is the most differentiated machinery in the codebase — typed decisions, bounded escalation budgets, benchmark-confidence and spend-confirmation gates, approval flows, full audit evidence. Nobody else has this.

**Three structural problems:**

- **It switches too late, at the wrong granularity.** The idea says "switch *in between* if the model can't solve the task." The implementation switches at task boundaries and post-review — after the failing model has burned its entire iteration/wall-clock budget. The struggle signals that would justify switching at iteration 15 (repeated failing signatures, edit churn, stale explanations) are computed — but consumed by post-hoc governance, not the live loop (the same finding as Deep dive 1, from the other side). The differentiated version of this pillar is **mid-task escalation**; today's is expensive-retry-after-full-failure.
- **The switch can't detect most failures, because verification is off.** TB: 32 of 47 failures were "worker self-certified done, verifier disagreed." A model that *believes* it succeeded never trips the "not able to solve" trigger — no switch fires, the run completes wrong. With in-loop verification dark (Gap 2), pillar 2's trigger condition is blind to the dominant failure mode. **Item 3 isn't adjacent to this pillar — it is this pillar's sensor.**
- **The rerun restarts cold.** An escalation reruns the task from the prompt; the failed attempt's diagnosis (what was tried, what broke, which files matter) doesn't transfer. The strong model pays full exploration cost again — inside the same guillotines that may have killed the cheap model. The right pattern is "fresh eyes with a briefing": hand the escalated model a structured summary of the failed attempt (the Item 4 dossier format, reused).

**Also worth saying plainly:** an enormous share of engineering went into the *governance* of switching (approvals, evidence ledgers, confirmation gates) before the *value* of switching was ever demonstrated at k≥3. That's control plane before data plane. And the strategically best version of this pillar — **start cheap by default, escalate fast and early** — is currently unsafe to offer, because early truthful failure detection (Items 1+3) doesn't exist. Fix those two and cheap-first-fast-escalate becomes a genuine cost moat no competitor has.

## Pillar 3 — Prompt caching across. Idea: B (table stakes, not a moat). Implementation: B−

**What's real:** transport-level caching works — per-provider implicit cache config, Anthropic `cache_control`, cache-aware cost tracking, and a measured 59.4% cache-read rate on the Sonnet TB run. The Gemini Pro→Flash migrations show the org watches cache economics seriously.

**Where it falls short of "caching *across*":**

- **The architecture fights its own cache.** History compaction rewrites the prefix (full cache nuke per firing — at the 60k trigger, frequently); the per-turn system prompt + tool-schema drift that zeroed Gemini Pro's cache was fixed by *changing models* rather than by making the bytes stable; and the ~6.7k tokens of tool schema per call are only cacheable if byte-identical every call — the duplicated compat toolset (T1) doubles the surface that must stay stable.
- **Caching is a cost patch on a volume problem.** TB's $136 Sonnet run had a healthy cache; the cost was raw context volume from thrash (Gap 3). No cache rate rescues a loop that processes 905k input tokens per task. Items 1+4 are worth more to unit economics than any cache tuning.
- **"Across" is unrealized.** Cross-task and cross-run prefix reuse (same system prompt, same schemas, back-to-back tasks inside provider TTL) is left to luck rather than orchestrated — no deliberate prefix layout (static bytes first, volatile RAG/task content last), no schedule-aware warm-prefix batching.

**Honest strategic note:** caching is necessary hygiene, but it cannot be a differentiator — every serious operator does it. The differentiated economics live in pillars 1+2 (right model, fast escalation), with caching as the multiplier. Grade the pillar as *enabler*, not *moat*.

## Pillar 4 — Task breakdown on submission. Idea: B+. Implementation: C+

**What's real:** classifier → outline planner → hierarchical expansion for LARGE → **plan gate**. The plan gate is the most underrated asset in the product: human-approved scope + cost estimate + credit charge at approval is genuine enterprise value no CLI harness offers. Decomposition as a *contract and billing artifact* works.

**Where the pillar under-delivers on "to manage execution":**

- **The breakdown manages nothing after the gate.** The plan is frozen; tasks are amnesiac (no journal); the worker never sees the plan it's part of; failure handling is skip/abort; and the wave scheduler computes safe parallelism then executes sequentially anyway. Decomposition currently buys approval-time clarity and zero execution-time advantage.
- **Decomposition granularity is tuned for weak models, against the industry current.** The planner over-decomposition already had to be relaxed once (RL-2026-05-011). Frontier practice moved to *fewer, larger tasks* for strong models, with intra-task todo tracking — because every task boundary in this architecture is a context wipe, and context is where competence lives. Fixed fine-grained decomposition is a 2024 bet; the durable version of this pillar is decomposition that *earns* its boundaries: split where parallelism (Item 9) or tier-routing (pillar 1) actually pays, keep coherent work in one context otherwise.
- **The tier-routing payoff of decomposition is muted:** LOW and MEDIUM both route to WORKHORSE by default (post cheap-tier regression), so per-task complexity currently changes caps more than models — the pillar-1 connection decomposition exists to serve is mostly dormant.

**What closes it:** Item 7 (journal + plan visibility + bounded re-plan) makes the plan a live execution object; Item 9 (enforced waves) makes decomposition pay wall-clock rent; per-task difficulty reconciliation (pillar 1 fix) makes it pay routing rent.

## Synthesis — the pillars vs. the 10 items

| Pillar | Binding constraint today | Items that unblock it |
|---|---|---|
| 1. Rating per job | Ratings are k=1 noise measured through a non-neutral harness | **8** (instrument), 1+6 (neutral loop), + predicted-vs-actual feedback |
| 2. Mid-run switch | No truthful failure signal (verification dark), switches late and cold | **3** (sensor), 1 (in-loop signals), 4-dossier (warm handoff) |
| 3. Caching across | Prefix instability + volume thrash dominate cache math | 4 (compaction/overflow), T1 (stable schema bytes), 1 (volume) |
| 4. Breakdown manages execution | Plan is inert after the gate; boundaries wipe context | 7 (journal/re-plan), 9 (waves), 5 (cheap boundaries via warm starts) |

**The one-paragraph verdict:** the primary idea is right, and it is *more* right in 2026 than when it was conceived — model heterogeneity is increasing and buyers want cost governance. But all four pillars assume something the current system doesn't have: that a task's failure or success is *detected truthfully* (verification), *attributable to the model* rather than the harness (neutral loop), and *measured above the noise floor* (instrument). Without those three, ratings are folklore, switches fire blind and late, cache math is dominated by thrash, and decomposition is a billing artifact. With them, the four pillars compose into the strategy competitors actually can't copy: **start every job on the cheapest plausible model, detect struggle early and truthfully, escalate hot with a briefing, and bill the customer for exactly the intelligence the job needed.** That's the product the plumbing was always building toward — the missing pieces are the ten items, not a new idea.

---

# Resolution path

Everything above, converted into an executable sequence. Four principles govern it, then five phases plus a parallel benchmark track. Each phase has an explicit **exit gate** — measured, not vibes — and names what it unlocks in pillar terms.

## Operating principles (apply to every phase)

1. **No change without error bars.** Every loop/prompt/flag change ships with a k=3 eval on a fixed slice; every revert needs the same evidence as a ship. The canary suite (always-pass tasks) runs at k=1 on every change — always-pass flipping is signal even at k=1.
2. **Every flag gets a graduation criterion, owner, and expiry at creation** — retroactively applied to the seven dormant flags in the first week. A flag past expiry without evidence gets deleted, not kept "just in case."
3. **Per-provider lanes, never global reverts.** From Phase 1 onward, a regression on one model is a profile edit for that model, not a rollback for all.
4. **Don't parallelize or governance-wrap a sick loop.** Waves, richer escalation policy, and new governance surfaces wait until the core loop passes Phase 2's gate.

## Phase 0 — Install the instrument (2–4 days, blocks nothing else starting)

| Step | What | Notes |
|---|---|---|
| 0.1 | k-wrapper over existing drivers (`run-iter2.py`, `v2-eval.sh`, `tier-eval-v2.sh`): run k passes, emit mean ± spread per slice | Thin script; the drivers already exist |
| 0.2 | Canary suite: pin the TB 25 always-pass set + a 10-instance SWE always/never set once known; wire to run on any harness change | Cheap regression tripwire |
| 0.3 | **Baseline measurement** at k=3: SWE Pro sample-20 (DeepSeek Pro + Flash), TB first-20, tier-eval-v2 — on the current HEAD, before touching anything | ~$5–15 total; this is the "before" photo every later claim compares against |
| 0.4 | Flag triage: assign graduation criterion + expiry to V209/V216/V217/V222/V227/V233/V234; delete anything nobody will defend | Half a day of decisions |

**Exit gate:** baseline numbers with error bars exist for SWE-Pro-20, TB-20, tier-eval; canary wired. **Unlocks:** Pillar 1's data supply; the ability to trust every subsequent phase.

## Phase 1 — Make the loop honest and neutral (week 1–2)

The two highest-ceiling small changes plus the tool-layer quick wins. All behind flags, measured against the Phase-0 baseline.

| Step | What | Item |
|---|---|---|
| 1.1 | Novelty-based progress pre-first-edit (seen-set of `(tool, normalized-args)` hashes); repetition-based stagnation from the existing continuation-evidence counters moved in-loop; text-only turns → re-prompt then `tool_choice=required`, never a kill; `COMPLETED_BY_STAGNATION` reserved for ≥3 identical failing signatures post-nudge | 1 |
| 1.2 | Nudge copy: remove "even an imperfect edit beats zero" for customer sources (keep for benchmark PATCH) | 1 |
| 1.3 | `runTests` → language-aware via `WorkspaceTestRunner` resolver; schema description rewritten honestly; fix the remote `test_ids`-vs-`command` contract | 2, T5 |
| 1.4 | Single canonical toolset for server workers (unbind compat aliases; keep them for local-hands sessions); one naming convention | T1, T2 |
| 1.5 | `gitDiff` through `ToolOutput.budget()` + `path`/`staged` args; `writeFile overwrite=true` gated on read-recency | T3, T4 |

**Exit gate (k=3 vs baseline):** no quality regression on any provider; `COMPLETED_BY_STAGNATION` + `STOPPED_WITHOUT_COMPLETE` share materially down; empty-patch rate down (Flash's 9/20 class); unknown-tool call rate ~0; tokens/task within agreed budget (pre-agree the ceiling — exploration spend *will* rise and that is correct). **Unlocks:** harness neutrality — the precondition for Pillar 1 ratings and Pillar 2 attribution.

## Phase 2 — Mechanize verification + context discipline (week 2–4)

Run the **ablation matrix first** ({guards new/old} × {compaction on/off} × {auto-verify on/off}, one model, k=3, ~$30–60): it decides the defaults for everything in this phase.

| Step | What | Item |
|---|---|---|
| 2.1 | Server-side auto-verify: run the resolved test command after edit bursts / on completion intent; inject counts + first-N failures + spill path as an observation; no schema change | 3 |
| 2.2 | VBC → repair loop: refusals carry failing test names + tracebacks + "fix and re-verify"; keep 3-refusal bound, fail-open | 3 |
| 2.3 | Window-aware compaction: 0.75× trigger from YAML window, token-budgeted tail, **structured dossier** summary (verbatim-copy rules for errors/expected outputs); flip `worker.history-compaction.enabled` per ablation result | 4 |
| 2.4 | Context-overflow handler: classify per-provider 400 → compact → retry → hard-trim → retry → only then ERROR (ships in the same change as 2.3) | 4 |
| 2.5 | Reviewer gets the test runner; `UNRESOLVED_ISSUES` may dispatch one bounded worker fix pass | 3 |

**Exit gate (k=3):** tests-green-at-completion becomes a tracked metric and rises materially; zero context-overflow ERRORs; failed-task token multiple (baseline 4.2×) shrinks; verified-score up on the eval matrix. **Unlocks:** Pillar 2's sensor (truthful failure detection); Pillar 3's volume problem addressed at the source.

## Phase 3 — Warm starts, profiles, coherence (week 4–6)

| Step | What | Item |
|---|---|---|
| 3.1 | Per-provider capability profiles in YAML (optional tools, thresholds, tool-choice forcing, compaction trigger, prompt fragment); plumb profile → `WorkerTaskRequest` | 6 |
| 3.2 | Re-enable the dark tools per profile: `runWorkspaceTests` + `readCodeOutline` for Anthropic/OpenAI lanes; `verifyBoot` + `backgroundJob` for app-shaped BUILD runs | 6, T5 |
| 3.3 | Localization pre-task step: deterministic layer (tree + git grep + resolver commands) + LIGHTWEIGHT read-only subagent → advisory REPO ORIENTATION block | 5 |
| 3.4 | Run journal: inject prior-task summaries + plan outline into each task prompt; bounded re-plan (once per run, structural failures only, scope changes re-gate) | 7 |
| 3.5 | searchFiles `contextLines`; listFiles `maxDepth` | T6 |

**Exit gate (k=3):** iterations-to-first-correct-edit down; Sonnet-vs-Flash delta flips to positive (the **neutrality metric** — a stronger model should now measurably beat a cheaper one through this harness); reviewer wiring-pass issue count down. **Unlocks:** Pillar 4 starts paying execution rent; Pillar 1 ratings become meaningful per lane.

## Phase 4 — Activate the pillars (week 6–10)

Only now — on a healthy, neutral, measured loop — build the differentiating economics.

| Step | What | Pillar |
|---|---|---|
| 4.1 | Predicted-vs-actual difficulty feedback: log classifier prediction vs observed (iterations, escalations, outcome) per task; feed the reconciliation into classification and the quality priors | 1 |
| 4.2 | **Mid-task escalation**: when in-loop repetition signals cross threshold at iteration N (not task end), propose the switch immediately; escalate **hot** — hand the new model a structured dossier of the failed attempt (reuse the Phase-2 compactor format) | 2 |
| 4.3 | Cheap-first-fast-escalate as a product mode: LOW-start on the cheapest plausible lane + aggressive early escalation; bill for the intelligence actually used | 1+2 |
| 4.4 | Cache-aware prefix layout: static bytes first (system prompt, schemas — now stable post-T1), volatile RAG/task content last; verify with per-provider cache-read dashboards | 3 |
| 4.5 | Enforced task waves (per-task worktrees, wave-boundary smoke, conflict → serialize); independent verifier subagent with tools + test runner, one bounded repair pass | 4 / 9 |
| 4.6 | Tool-call-granular journal (also the SWE-bench trajectory exporter) + provider error lanes stamped into `run_step_usage` | 10 |

**Exit gate:** escalation fires mid-task with measured save-rate; cost-per-verified-point beats the all-flagship baseline on the eval matrix; cache-read rate stable ≥60% on Anthropic lanes. **Unlocks:** the composed moat — cheap-first, escalate-hot, bill-for-intelligence-used.

## Parallel track B — SWE-bench Pro (starts immediately, independent of Phase 1–4 code)

Runs on the benchmark VM against the deployed SHA; doubles as the live measurement bed. **Quiesce running iterations before any deploy** (handoff §8: restarts kill detached lanes).

| Step | What | When |
|---|---|---|
| B.1 | Inject `requirements` + `interface` per the official protocol (`SwebenchInstanceLoader` + `synthesizeBlindSpec`) — the leaderboard-legal double-digit lever | Immediately |
| B.2 | Zero-cost RCA: count `COMPLETED_BY_STAGNATION`/`STOPPED_WITHOUT_COMPLETE`/salvage outcomes in the existing sample-20 rows; grep the 20 gold patches against the diff-exclusion globs | Immediately |
| B.3 | Relax pre-edit force threshold for benchmark sources (interim, until Phase 1 lands properly); fix exclusion globs per B.2 findings | After B.2 |
| B.4 | Re-measure: k=3 on sample-20, then sample-50 for a tight CI before any full-731 decision | After B.1–B.3 |
| B.5 | Full-731 + submission (per `SWE-BENCH-PRO-PLAN-2026-07-03.md` gates) only after B.4 clears the founder's bar; trajectory exporter comes from 4.6 or a stopgap | Gated |

## Dashboard — the numbers that tell you it's working

| Metric | Baseline (Phase 0) | Direction |
|---|---|---|
| SWE Pro sample resolved% (k=3, mean ± spread) | ~30% k=1 | ↑ |
| Stagnation/text-stop termination share | measure | ↓ toward ~0 on healthy runs |
| Empty/no-usable-patch rate | 9/20 Flash class | ↓ toward Gate-A's ≥90% non-empty |
| Tests-green-at-completion | not tracked today | new; ↑ |
| Failed-task token multiple | 4.2× (TB) | ↓ |
| Context-overflow ERRORs | >0 | 0 |
| Unknown-tool call rate | 40/8 tasks (TB) | ~0 |
| **Sonnet-vs-Flash delta (neutrality)** | negative (42 vs 46, TB) | positive |
| Mid-task escalation save-rate | n/a | new (Phase 4) |
| Cost per verified point | measure | ↓ |

## Risk register

| Risk | Mitigation |
|---|---|
| Exploration spend rises post-Phase-1 and triggers a panic revert | Pre-agree the token/task ceiling at Phase-0; judge on cost-per-verified-point, not tokens |
| A DeepSeek regression pressures a global rollback | Principle 3: profile edit, never global revert (available from Phase 3; interim: source/provider-scoped flags) |
| Auto-verify tarpits on flaky suites | Blast-radius filter (block only diff-related failures); advisory otherwise |
| Deploys kill in-flight benchmark lanes | Quiesce protocol before every benchmark-VM deploy (handoff §8) |
| Scope creep into Phase 4 before gates pass | Phases 4.2–4.5 are explicitly gated on Phase 2+3 exits |
| Solo-founder bandwidth | Phases are sized S/M and independent within a phase; B-track is mostly config + scripts and runs unattended |

**Total shape:** ~2 weeks to an honest, neutral loop; ~4 weeks to mechanized verification with the measurement to prove it; ~6 weeks to warm, per-provider execution; ~10 weeks to the differentiated economics the primary idea was always about — with the SWE-bench Pro number improving in parallel from week 1 because its biggest lever (B.1) is a data-plumbing fix, not a harness rebuild.
