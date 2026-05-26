# Code-gen + Prompts — Gap Review ("what's missing") — 2026-05-25

> Grounded review of the V2 code-generation path: the prompts (`patch-agent.md`, worker base
> `SequentialExecutor.SYSTEM_PROMPT_BASE`, `outline-planner.md`, `reviewer-general.md`,
> `reviewer-wiring.md`) + the worker loop + tool layer. Each gap cites the specific
> prompt/file/observed failure. Prioritized by leverage on the dominant failure (wrong-but-plausible
> fixes) and on product quality.

## What's already good (so the gaps are in context)
- **Planner** (`outline-planner.md`) is genuinely strong: explicit requirement enumeration, constraint
  propagation, blast-radius invariant, language-parity, fewest-cohesive-tasks.
- **Worker base** has tests-mandatory, format-after-edit, batch-aggressively, no-git-mutation.
- **Reviewer** has a *wiring* pass (registration/scan-path/barrel-export) — a real differentiator most
  scaffolds lack — plus a fix-the-red-build third pass.
- **PATCH mode** now has reproduce-first self-verification (RL-033) + the ShellTool fix that lets it run.

## The gaps, ranked

### 1. Localization toolkit is thin — the root of the "wrong fix" failure (HIGHEST)
The worker finds *a* site by **regex grep only** (`SearchFilesTool` = Java walk + `Pattern`; no
symbol-awareness, no go-to-def/find-refs, no call graph, no repo map). On big repos this mislocalizes →
plausible-but-wrong fixes. **This matters acutely:** django + sympy are **64% of SWE-bench Lite test**,
and they're large. Compounding it:
- **`readFile` truncates at 5,000 chars** (`ReadFileTool.MAX_RESULT_CHARS`, head + 1.5k tail). Real
  django/sympy files are far larger → the worker sees a fragment and must range-read blind.
- **No repo-map / structure overview** is given at task start. Published SOTA (Agentless, Moatless,
  SWE-agent) all build a file→class→function map and rank candidate locations *first*. We have none.
- **Fix:** add a symbol/structure index (even a cheap ctags/tree-sitter repo-map injected into context),
  raise/adapt the readFile cap for source files, and a "rank candidate locations before editing" step.
  *Biggest single lever on fix correctness.*

### 2. BUILD mode has NO in-loop "run your tests and see them pass" gate (HIGH)
The worker base prompt says *write* tests (tests-mandatory) and *may* call `buildProject` (compile only).
Nothing makes it **run** the tests it wrote and confirm they pass before `completeTask`. `runTests` is
**patch-mode only**; `WorkspaceTestRunner` runs **post-run for scoring**, not in-loop. So the BUILD worker
ships tests it never executed — they may not pass or may not exercise the change.
- **Also missing: "read the module's EXISTING tests to learn the expected contract."** Observed directly:
  `marshmallow-1359` — worker guessed null-safety when the issue wanted format-inheritance; `pydicom-1139`
  — added `__iter__` but not `__next__`. The existing tests reveal the contract; neither prompt tells the
  worker to read them.
- **Fix:** port reproduce-first's run→see-pass loop into BUILD; add a "read existing tests first" step.
  (PATCH already got this; BUILD is the gap.)

### 3. There is NO correctness reviewer — the reviewer explicitly skips the dominant failure (HIGH)
`reviewer-general.md` **out-of-scope list** literally excludes: "missing tests", "logic you disagree
with", "architectural choices", "performance". It catches wiring + obvious runtime errors + the red
build — but **not** "this patch is plausible but wrong," which is ~60% of our misses. There is no QA layer
for the failure mode that actually costs us.
- **Fix:** add a correctness/acceptance pass that (a) re-reads the spec/issue intent, (b) runs the
  existing + worker-written tests, (c) checks the change actually produces the *specified* behavior — not
  just that it compiles and wires up.

### 4. Single-shot fix — no multi-candidate / test-based selection (MED-HIGH, proven lift)
We generate ONE patch and ship it. The published Lite SOTA jumps (Devlo/Globant ~48%, CodeStory) came
substantially from **N-candidate generation + selection** ("LLM-as-judge" / regression-test ranking;
Agentless's reproduction-test selection). We have no second opinion.
- **Fix:** for HIGH-complexity tasks, generate 2 candidates and keep the one whose existing-test run is
  cleaner. Gate behind complexity so it only pays where it matters (≈2× cost on hard tasks only).

### 5. Test-tampering + diff pollution hurt product PR quality (MED)
- **Worker edits EXISTING tests to match its output** — observed `pvlib-1154` ("Also updated the existing
  test_reindl to expect 0"). Both prompts weakly say "don't touch tests"; it still happens. For the autofix
  PR surfaces this is a real defect (it can mask a wrong fix).
- **Diff pollution:** scratch/temp files captured into the patch (observed `sqlfluff-1763` stray
  `tmpl1pabduk`); `captureDiff` / commit path isn't gitignore-aware.
- **Fix:** hard "never modify existing tests" guard + a post-edit check that flags test-file edits;
  gitignore-aware diff capture in both the bench driver and `ScmPushService`/`gitCommit`.

### 6. PATCH thrash ceiling is disabled (MED, cost/latency)
`PatchAgent` sets `stagnationWarnAfter/ForceAfter = maxIterations` ("investigation is the work"). Observed
`astroid-1333`: 98 iters / 0 edits / $2.90 / 17 min → empty patch. The long runway didn't rescue it.
- **Fix:** a *middle-ground* no-edit ceiling (~40 iters → one "commit to an edit or `completeTask('cannot
  fix')`" nudge, force-salvage), not 12 (too tight) and not ∞. Protects customer autofix/existing-repo runs
  from runaway cost.

### 7. Smaller but real
- **Context window:** sliding window (first 2 + last 18 msgs) can drop the issue statement / key file mid
  long run — pin the spec + the gold-file content.
- **No retrieval of similar past fixes in PATCH** (RAG is planner/BUILD-only) — the autofix surfaces could
  retrieve similar resolved findings.
- **Reviewer is project-type-aware for wiring but not for *test execution*** — it calls `buildProject`, never
  runs the suite.

## Suggested sequencing (impact × effort)
1. **Read-existing-tests + run-tests-in-loop for BUILD** (prompt + reuse `WorkspaceTestRunner`) — cheap,
   directly attacks #2/#3, helps every product run. *Do first.*
2. **Don't-modify-tests guard + gitignore-aware diff** (#5) — cheap, product-PR-quality.
3. **PATCH thrash ceiling** (#6) — cheap, cost/latency, validate on `v2-eval` no-regression.
4. **Correctness review pass** (#3) — medium, the missing QA layer.
5. **Localization repo-map / symbol index + readFile cap** (#1) — larger, but the biggest correctness lever.
6. **Multi-candidate selection for HIGH** (#4) — larger/2× cost, gate by complexity.

## Honesty notes
- #1 and #4 are where published SOTA pulls ahead; they're real engineering, not prompt lines (the
  blast-radius and L1 prompt A/Bs moved the blind score by ~0, confirming prompt-only ≠ enough here).
- #2/#3/#5/#6 are the high-confidence, product-relevant wins — they also help the 4 autofix surfaces and
  existing-repo BUILD, not just the benchmark.
- Evidence base is small-N (Lite dev + matrix); treat the ranking as directional.
