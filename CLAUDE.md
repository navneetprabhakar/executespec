# Project: ExecuteSpec

> This file is read by Claude Code at session start. Keep it accurate.
>
> **Authoritative reference docs live in `executespec-docs/current/`** (regenerated from code 2026-08-02, validated at `3210a85dd`; see its `DISCREPANCIES-2026-08-02.md` for claims corrected in this file). When this file and `current/` disagree, verify against code.

**Version:** `2.0.0` ([`Version`](Version)). The current worktree migration head is **V371** (**357 migration files** — 356 integer-versioned + `V60_1`; next **V372**). V360 persists brownfield HTTP-contract evidence; V361 binds validation evidence to an exact workspace generation; V362 persists deterministic workflow/build/test command evidence; V363 adds explicit confirmation before retrying generated-document jobs with uncertain provider outcomes; V364 atomically fences exact continuation approvals; V365 timestamps those receipts for bounded fail-closed crash recovery; V366 persists exact score-zero causality without attributing historical rows; V367 binds validation reports to their runtime surface so non-HTTP endpoint coverage is explicitly not applicable; V368 force-disables legacy Apply-to-Run until it uses the transactional correction lifecycle; V369 persists the crash-recoverable correction workspace, task, and validation checkpoint; V370 converts V369's `CHAR(64)` digest columns to `VARCHAR(64)` because Hibernate schema-validation rejected the bpchar/entity mismatch and crash-looped the dev deployment; V371 persists skipped-test counts separately so green execution-verified suites with explicit skips can satisfy publication without hiding failures. V354's LLM operation admission remains `LEGACY`, and RequirementIR enforcement remains inactive by default. Default branch: `main`. Dev environment: `dev.executespec.ai`.

Spec-driven AI coding platform. Customer submits a spec in natural language → V2 pipeline plans, codes across one or more repos, builds + tests, opens a pull request with a deterministic verified score. Human approves at the plan gate; everything after is automatic.

## Architecture (5-minute orientation)

- **Backend:** Java 21, Spring Boot 3.5, Spring AI 1.1.6, PostgreSQL (Cloud SQL) + Redis. Single Spring Boot JAR; frontend served from `static/`. The fat JAR excludes unused heavyweight transitives from Spring AI Anthropic (`json-path`/Groovy), Tika unsupported parser families, and Google xDS/Conscrypt clients; keep parser coverage pinned with `DocumentExtractorTest` before relaxing or expanding those exclusions.
- **Frontend:** React 18, Vite 6, Tailwind CSS 4, TypeScript 5.3. **Console** (`frontend/src/console/`) is now the only protected web-app shell (RL-2026-06-027): `App.tsx` mounts `ConsoleShell` for every protected route, `/home` renders `ConsoleHome`, `/decisions` renders `ConsoleDecisions`, and the old `localStorage es:console` gate plus `IdeWorkspace` shell are gone. The VS Code-style `frontend/src/ide/` package remains the route-level implementation surface for runs, projects, billing, org settings, benchmark tracking, etc., while the actual in-editor IDE metaphor belongs to the VS Code extension. Protected routes lazy-load the heavy admin/discovery/billing/org/legal chunks so the run loop stays in the entry path. The production **Signal** chrome was retired and its pages were ported IDE-native then **deleted in the 2026-05-23 cleanup** (App.tsx's dead `ide ? Ide : Signal` ternaries collapsed + the `const ide` flag dropped; `Shell.tsx` + 22 orphaned pages removed). `frontend/src/signal/` now holds only the standalone **public** pages (auth/marketing/legal/shared + `AdminPage` for `/admin/*`) plus shared `components/` + `signal.css` reused by protected surfaces. The legacy V2 sidebar + V3 top-bar chromes (`components/`, `pages/`, `v3/`) were **deleted** (commit `5f5e4aaa`) — only `signal/`, `ide/`, `console/`, `hooks/`, `lib/`, `stores/`, `types`, `design-system/` remain under `frontend/src/`.
- **Web run-mutation identity:** protected create/fork/iterate call sites use
  `frontend/src/lib/runMutationAttempt.ts` to mint a caller-owned tab-scoped idempotency key. The
  ledger retains the same key across an ambiguous outcome and same-tab reload, but clears it after
  success or definitive rejection; `frontend/src/lib/api/runs.ts` requires the header rather than
  silently permitting an unsafe call. Enterprise Project Start Work reuses the Compose exact
  `modelName` override for that run only. Web Agent trust copy is evidence-qualified: retention is
  policy-bound, BYOK caps are optional, and PR creation remains publication-gated.
- **Infra:** GCP us-east4. Single GCE VM serves prod + dev; Cloud Run serves on-demand previews. Cloudflare in front of `*.executespec.ai`.
- **Codebase shape:** 85 top-level package roots under `com.navneet.executespec.*` (83 with Java sources; flat — the historical `v2.*` / `v3.*` prefixes were retired in 2026-05-07's unification pass). Current source includes 118 `@RestController` classes (123 files match the annotation) serving 577 routes pinned by `arch/ApiRouteInventoryTest`, `agentsession/` for cross-surface agent sessions, the committed `repositoryintel/` read-only evidence port, and the provider-free `repositoryexplorer/` selection/bundle contract. Frontend ~65K LOC of TS/TSX.

**Correction/publication trust boundary (V360–V364):** approved continuation retries bind the exact
run/task/action/provider/model/proposal digest and apply a strict override only to the target task;
the approved action route and its exact fenced physical worker/reviewer attempts are checked before
review and attribution. Publication re-attests every durable accepted or unresolved approved action
in deterministic task/action order; an earlier mismatch, missing legacy identity, or reused
physical-attempt id fails closed even when the latest approval is valid. A rejected/rolled-back
action is excluded only when its correction-resolution row carries the exact approval/proposal
identity and proves equal before/after workspace generations; an unbound legacy resolution cannot
suppress attestation. Approved worker usage is attributed from fenced physical attempt evidence,
never by replaying requested provider/model strings.
Execution View final-PR publication admits every current backing pipeline run through the same
canonical publication gate before Git. Apply-to-Run exposes exact aggregate and per-repository SCM
diagnostics, and deterministic HTTP/contract verification failures terminate as `FAILED` with their
machine code. Run-list surfaces suppress an unexplained numeric zero until causal ledger evidence
is loaded. Exact HTTP acceptance parsing is polarity-aware: an explicit 405/Method Not Allowed
constraint is not converted into a required producer route. Planning rejects an executable task
that declares the forbidden method; final validation requires that method to be absent and another
method to exist at the path, with methodless `ANY` mappings treated as violations.
V364 claims the exact persisted proposal with one durable conditional update, so a duplicate while
CLAIMED conflicts, while QUEUED/COMPLETED duplicates replay one receipt without queueing or spending
twice. Every server-side
approved continuation is workspace-mutating and therefore starts from a reversible
content-addressed Git-tree checkpoint, immediately marks earlier validation evidence stale,
requires a non-empty diff plus real all-green tests and deterministic workflow/contract checks, and
receives a read-only final reviewer.
The stale projection clears independently readable build, test, review, workflow, executable and
score facts until rollback restores the byte-equivalent report or accepted validation regenerates
them. Accepted corrections must also preserve the prior passing test population and protected
deterministic evidence; a green but narrower suite is rejected. Policy rejection is persisted as
`REJECTED`, while execution/transaction failure that requires recovery is `ROLLED_BACK`. V369
validation checkpoints are stored in PostgreSQL `jsonb`, so their SHA-256 is verified over the typed
V1 envelope re-serialized in its declared field order rather than PostgreSQL's non-stable outer key
order; the embedded report JSON remains byte-exact and any changed evidence/share value still fails
closed. In-process prepared authority is retained until the durable ACCEPTED or ROLLED_BACK terminal
CAS completes, preventing a rejected rollback from losing its attempt identity.
Exact HTTP requirements and the admitted brownfield surface must pass before that reviewer runs;
the brownfield guard runs immediately after the normal worker and also lives inside
`RunValidationKickoff`, so normal completion, crash resume,
retry-validation, and re-review cannot persist a fresh report for a regressed public surface.
Reviewer and regenerated validation evidence must match the same workspace generation. Rejected or
failed corrections restore both workspace and validation state and persist a structured resolution
with changed paths, the authoritative content-addressed diff digest, a bounded unified patch with
truncation/redaction metadata, and typed before/after validation facts for build, tests,
workflow/config, protected contracts, reviewer and score. Policy-rejected attempts retain their
attempted validation facts before rollback instead of disappearing with the restored report.
Brownfield admission stores a canonical Spring HTTP surface; removed routes fail closed unless an
exact durable plan-gate receipt and matching contract report carry uppercase `BREAKING`.
The materialized-workspace extractor traverses without a depth cutoff, prunes vendor/.git and
conventional build-output trees, filters only explicit `src/test` test sources, and fails closed on
file/byte budget overflow or read errors. Generic `test`, `tests`, and `generated` names stay
eligible, so deeply nested multi-repository controllers cannot disappear from the admitted baseline.
Validation evidence is bound to a canonical workspace-generation SHA and persists deterministic
workflow/config plus exact build/test command evidence. SCM publication and permanent deployment
require that generation to remain current plus explicit passing build and execution-verified tests.
SCM push responses distinguish Git transport, PR creation, repository resolution, workspace safety,
and multi-repository publication through stable `code`, `blockingGate`, and `remediation` fields.
The run Evidence view renders every durable correction in server task/action order; it does not
hide earlier corrections or invent cross-task timestamps that are not persisted.
Generated-document retries with ambiguous provider outcomes require explicit confirmation rather
than risking duplicate spend; V363 widens the job status column before adding
`NEEDS_CONFIRMATION`. Anthropic text responses are assembled across every returned generation, and
new generated-document rows are flushed before the same transaction binds the durable job result,
so multi-block output is not misclassified as empty and PostgreSQL never observes a dangling result
foreign key.

Run-end validation failures remain publication-blocking and now persist a bounded
`VALIDATION_FAILED[code]: reason` diagnostic plus a typed `VALIDATION_FAILED` SSE event; terminal
`VALIDATION_REPORT_UNAVAILABLE` no longer erases the failing deterministic gate; diagnostics are
secret-redacted before persistence or SSE publication. Project-run previews resolve an exact
single linked checkout beneath `repos/<name>` with symlink containment, then copy it into an
isolated build context so generated Docker/Compose files cannot dirty validation-bound source;
legacy zero-snapshot runs stage the root workspace and multi-repository previews fail explicitly.

### V2 pipeline (the only execution path)

```
RunOrchestrator
  ├─ SmartClassifierAgent (LIGHTWEIGHT)            classify spec: SINGLE_SHOT | SMALL | MEDIUM | LARGE | LEGACY
  ├─ OutlinePlannerAgent (PLANNING)                emit Plan via the forced submitOutline tool
  │   ├─ SMALL: deterministic PlanSafetyContractEnricher
  │   ├─ MEDIUM: deterministic safety → CoherencePlannerAgent (LIGHTWEIGHT)
  │   └─ LARGE/LEGACY: SKELETON_ONLY → dependency-wave TaskExpanderAgent (WORKHORSE) → deterministic safety → coherence
  ├─ plan gate (PRODUCTION pauses at AWAITING_APPROVAL; PROTOTYPE bypasses)
  ├─ SequentialExecutor                            per-task tier-routed WorkerAgent + per-task buildProject smoke
  ├─ ReviewerAgent (PLANNING)                      read-only diagnosis; correction worker owns any checkpointed repair
  ├─ ValidationReport persistence + verifiedScore
  └─ auto-preview kickoff + test generation in background
```

LARGE outlines have one 16-task admission ceiling in both the prompt and server policy. If a
provider emits more, the planner gets one bounded coarsening retry that must preserve every
requirement and acceptance/setup task; the server fails planning closed if the retry is still
oversized. It never silently truncates the tail of a plan.

Stale project architecture is advisory planner context and cannot delay run admission: the
pre-planner boundary admits/reuses the durable refresh job without awaiting provider generation;
the durable worker owns promotion and staleness-cache invalidation. Build and test inference uses
bounded manifest discovery plus exact JGit working-tree changes to select only changed nested
modules; multiple unscoped modules remain honestly unavailable instead of executing unrelated
builds. A zero-write worker response terminated by the provider output cap receives one same-route,
restricted mutation recovery and remains unverified until the normal downstream gates. Terminal
run UI treats the validation report's reviewer verdict and product-file count as authoritative;
same-route retry preserves the persisted provider/model and route changes are an explicit paid
action.

Run modes (`pipeline_run.run_mode`): **`BUILD`** (default — the diagram above) and **`PATCH`** (single thin-agent loop against a failing-test spec; skips classifier / planner / reviewer; used by the four autofix surfaces below and the SWE-bench / ProgramBench adapters).

**Worker mutation-epoch watchdog (2026-07-30):** normal workers credit successful
build/test/shell verification at most once after each successful workspace mutation. Repeated
verification-only turns against unchanged files advance an independent 4/8 nudge/force clock.
Novel successful read/search/list/diff investigation resets that clock, while other non-verification
turns pause it; a completion-policy-refused `completeTask` therefore receives one response turn
instead of being force-stopped. Terminal-Bench retains its separate shell/artifact-finalizer
contract, including one shutdown-reserve provider-and-tool finalizer after the ordinary wall-clock
cap while explicit cost/token ceilings still apply. Before another provider turn, the worker checks
observed spend and carries both ceilings into the router. At the last common pre-transport seam,
the router reserves a conservative projected cost for the complete next call (uncached input with
headroom plus the requested maximum output) using the effective managed-AI or failover route.
Normal, salvage, terminal-finalizer, and critic calls share that admission. Missing route pricing
falls back to the observed-spend check with an explicit warning rather than becoming a zero-cost
projection. Live task boundaries also check task-scoped spend through
`RunCostTracker.snapshotTask`, wall clock, and optional
`worker.best-effort-max-task-cost-usd` / `worker.best-effort-max-task-total-tokens` (both default
`-1`, disabled; Terminal-Bench exempt). The cost/token stops are JVM-local diagnostics, not durable
authorization: restart recovery does not hydrate `RunCostTracker` from `run_step_usage`, and the
projection cannot guarantee exact billing because provider tokenization/cache outcomes arrive only
after the response. The mutation epoch is also in-memory and begins fresh after resume; neither
mechanism claims a durable cross-JVM fence. An `ABORTED` executor persists task/usage evidence and
terminates before brownfield extraction, lint, or reviewer calls, so a reached ceiling cannot be
masked by a later validator failure or followed by more LLM spend.

Tools (`tool/`): `readFile`, `writeFile`, `editFile`, `listFiles`, `searchFiles`, `shell`, `gitStatus`, `gitDiff`, `gitCommit`, `buildProject`, `runTests` (patch-mode only), plus `completeTask` for native termination. `gitStatus` and `gitDiff` resolve real Git working trees under `repos/<name>/` for project/multi-repo workspaces and label each result with its workspace-relative repo root; they do not review the outer run workspace as one greenfield `repos/` directory. `ToolRegistry.bindForWorker(ctx)` filters `gitCommit` from worker authority (auto-push happens at run-end via `ScmPushService.pushMultiRepo`). Remote workers advertise canonical capability profiles rather than the complete compatibility catalogue: released CLI local-hands gets 25 schemas and Terminal-Bench gets the 11 schemas its Harbor adapter implements; legacy aliases remain dispatchable for persisted/in-flight calls but are not model-visible.

LLM router (`agent/LlmRouter.java`): native tool calling end-to-end across direct providers (Anthropic via Spring AI; OpenAI via `OpenAiNativeToolClient`; Gemini via `GeminiNativeToolClient`; DeepSeek via the OpenAI-wire client) and aggregator transports (OpenRouter and Fireworks via the OpenAI-wire client). The shared OpenAI-wire transport emits native DeepSeek's supported `max_tokens` field while OpenAI/aggregators retain `max_completion_tokens`, so per-agent output ceilings are actually enforced on every route. Gemini is available to paid customer-facing selection when a key/policy permits it; provider quality remains separately reported rather than hidden behind a hard disable. V256 removes the retired Sarvam provider/model route and clears persisted dispatch preferences. V266 graduates bounded cross-provider failover after same-provider transient retries, preserving BYOK boundaries. DeepSeek has an explicit native-first recovery policy: healthy native requests are never adaptively replaced; only a failed native route may recover through OpenRouter and then Fireworks. Every customer `OWN_KEYS` context is stricter: once a project/run/chat action selects a provider, its immutable execution policy contains only that provider and disables cross-provider failover; other customer keys and every platform key are ineligible for dispatch and continuation. OpenRouter requests include a provider-routing hint `provider.sort=latency` and `usage.include=true` so `RunCostTracker` can prefer provider-reported billed cost when present. Every other call flows through `RunCostTracker` cache-aware pricing from `application.yml model-matrix.models.<id>.pricing` (seeded into the `model_pricing` table at startup by `ModelPricingInitializer`). V282 is now the production dispatch boundary: every customer attempt carries an exact context id + tenant scope, reloads current credential provenance, and re-enforces provider/model/pin/failover/key-source/budget policy before transport; missing, stale, cross-tenant or out-of-policy contexts fail closed. `RunKeyStore` is no longer an availability authority. All text-only agents use the canonical `LlmRouter.callText`; the retired compatibility router was removed in ExecuteSpec 2.0. Agent-session structured intent classification receives the session's effective provider/model and durable turn context before its advisory call; malformed or unavailable routing fails closed without creating a run.

Workspaces: `<agent.workspace>/<runId>/` (local disk on the VM). Multi-repo runs clone each linked repo into `<workspace>/<runId>/repos/<name>/`. `StandaloneExistingWorkspaceAdmission` handles remote standalone `repoMode=EXISTING` runs before any model call: it uses `WorkspaceInitService.cloneFromRepo`, requires Git metadata plus at least one recognized source file, and fails closed on clone/inventory failure. Project-linked multi-repo admission, source-run snapshot copying, and local-hands client working-tree semantics remain separate. Independently generated tests live under the reserved, symlink-checked `<agent.workspace>/<runId>/.executespec/generated-tests/` path so compilation shares the run-derived `/workspace` sandbox mount instead of executing a POM from a server working-directory sibling. Worker writes flow through tool dispatchers; cleanroom container mounts (SWE-bench, ProgramBench) bind the workspace dir at `/workspace` so host-side tools and container-side tools see the same files. The dark representative strict toolchain image and no-network/non-root matrix live under `docker/sandbox-toolchain/`; deployment still requires a registry-produced `repository@sha256:...` reference and all four sandbox/readiness flags remain off. Strict provisioning also walks the existing bind-mounted tree and refuses it unless the configured non-root runtime uid/gid (default `65532:65532`) can write it; it never chmods/chowns customer data. The VM service/workspace ownership must be explicitly aligned before enabling strict routing.

Workers (`engine/WorkerAgent.java` — relocated from `agent/` in the Phase-4 engine extraction, RL-2026-05-052) — sequential, multi-turn. Per-complexity caps (set in `SequentialExecutor.tierConfigFor`, which ALSO routes the worker model by complexity — LOW defaults to WORKHORSE but can opt into LIGHTWEIGHT through `execution.continuation.low-complexity-worker-start-tier=LIGHTWEIGHT`, MEDIUM→WORKHORSE, HIGH→PLANNING; default LOW/MEDIUM stay on flagship-class tiers after the cheap-tier regression): LOW=60 iters / 4k tokens / 30-min wall-clock, MEDIUM=120 / 8k / 45-min, HIGH=180 / 16k / 75-min (project runs ×2 on iters + wall-clock). Per-provider PATCH-mode caps: Anthropic 220, OpenAI 150, Gemini 180, DeepSeek/OpenRouter 220. Outcomes: `COMPLETED | COMPLETED_BY_STAGNATION | STOPPED_WITHOUT_COMPLETE | ITERATION_LIMIT_EXCEEDED | BUDGET_EXCEEDED | ERROR`. Resilience is **one progress signal** since 2026-05-19 (RL-2026-05-005, unifying the old Lever A + Lever C + stagnation warn/force): `itersSinceProgress` counts iterations with no `writeFile`/`editFile`; crossing `progressWarnAfter` (per-tier from `harness/WorkerTierBudgetPolicy`: LOW 10, MEDIUM 16, HIGH 24; the 8 in `WorkerAgent` is only a class fallback) injects ONE corrective UserMessage nudge — commit to an edit / call `completeTask` / fix the build — which also fires on a premature text-only stop; crossing `progressForceAfter` (per-tier 16/28/40; project runs force-override 24/36; the 12 fallback is never dispatched) force-terminates with `COMPLETED_BY_STAGNATION`. Progress-v2 additionally detects repeated successful mutations of the same path without a proven exit-zero test/build (repeat-failure nudge 3, force `ITERATION_LIMIT_EXCEEDED` at 5; 6 is the legacy `PATCH_NO_EDIT_WARN_AFTER_N`), preventing scratch-file rewrites from resetting the watchdog and growing a task indefinitely; terminal mode is excluded. **Terminal-Bench remote-worker runs count `shell` as progress, use a 120-iteration floor, add terminal-artifact checkpoints around iterations 16 and 32, run a restricted terminal finalizer at iteration 64 even if shell calls keep resetting progress, run one restricted finalizer before wall-clock failure, force a terminal finalizer after a second text-only stop, and cap wall-clock at 1410s (below Harbor's 1500s agent timeout, with a 90s buffer for one slow in-flight tool/LLM turn plus the terminal finalizer) so extra turns produce the requested `/app` deliverable without turning long runs into Harbor timeout errors. Terminal mode also has a shared-worker visible-test completion gate: when `/tests` exists, `completeTask` is refused until a passing verifier-shaped command actually targets `/tests`; after such a pass with extracted deliverables present, the verified-pass guard snapshots the candidate under `/tmp/es-passing-candidate-<runId>`, refuses later risky edits/reruns, and force-completes text-only follow-ups so a passing candidate is not optimized into failure.** **Lever B** (best-guess salvage — one final LLM call on iter-limit / budget-exceeded / progress-force when `totalWriteCalls=0`) and the dollar budget cap survive unchanged. Conversation snapshots persisted every 5 iters to Redis at `v2:agent-ctx:{runId}:{taskId}` (24h TTL). The worker base system prompt (`SequentialExecutor.SYSTEM_PROMPT_BASE`, exposed read-only via `workerSystemPromptBase()` for the tier-eval) carries a **tests-are-mandatory** directive since 2026-05-19: any task that touches a function / endpoint / module must ship at least a happy-path test + an error/edge test before `completeTask`, or justify in the summary why it's genuinely untestable. Focused tests are planned in the same task as production code and live in conventional test paths; one-off reproductions belong under `/tmp`, outside the deliverable.

Scoring (`scoring/`, `report/`): `verifiedScore` is **additive-weighted** since 2026-05-19 (flag `scoring.additive-v2`, default ON, seeded by V201) — five slots with documented weights: **CODE_DELIVERED (30)** + **BUILD (25)** + **TESTS (20)** + **REVIEWER (20)** + **CRITICALS (5)**. Weighted-average over slots that actually fired; unmeasured signals drop from both numerator and denominator instead of defaulting to 1.0. Hard floor: `filesGenerated == 0` ⇒ headline 0. Zero is reserved for an explicit deterministic gate: LLM spec/endpoint/coverage assessments may warn or cap a positive score but cannot reduce it below 1, missing build evidence is UNKNOWN rather than a legacy zero factor, and every zero report emits a typed `score-zero-cause` ledger row with exact artifacts or an honest historical/unattributed cause-unavailable state. Worker-authored workspace tests still run through `smoketest/WorkspaceTestRunner` (flag `scoring.real-test-exec`) as repair diagnostics, but their counts do **not** certify output quality. The TESTS slot is populated by the independently generated black-box suite executed against the real preview/runtime; that population replaces rather than blends with worker tests. Missing, failed, stale, zero-test, or provenance-unverified functional execution caps both headline scores at 75 until verified external evidence arrives. `endpointFactor` and the two 2026-05-10 / 2026-05-16 trust gates are retired; the reviewer's wiring pass already catches the bug class they were guarding against. **Anti-gaming guards (RL-2026-05-042, audit #7, flag `scoring.gaming-guards`, default ON, seeded by V214):** tighten-only adjustments so the system can't vouch for a gamed run — (A) CODE_DELIVERED is confidence-adjusted by the LLM-assisted endpoint-coverage signal without treating that signal as deterministic proof; (B+E) the TESTS slot blends the real pass-rate with the coverage-QUALITY judgment (NONE caps the slot at 30, PARTIAL at 70) so trivial always-passing tests can't score 100. **RL-2026-06-026 trust caps:** the weighted score keeps partial credit, then the headline is capped by failed review (70), unresolved reviewer issues (80), unresolved criticals (85 or 70 for 3+), failed build (75), failed concrete tests (85 or 70 for zero pass), or missing/none test coverage (90/75). A run or report with admitted unverified requirements is capped at 84, below the passing threshold, even when the assessor inconsistently emits 100/100; incomplete task proof applies the same customer-facing cap to run list/detail projections. The verification ledger's trust-cap row uses only unresolved CRITICAL implementation issues; the broader report-issues count remains an operator backlog signal and cannot cap the headline by itself. The report DTO now includes a `verificationEvidence[]` ledger that labels each claim as deterministic, LLM reviewer, LLM assessment, hybrid, or internal formula so `verifiedScore` is visibly a summary, not independent proof. Legacy multiplicative formula stays in `computeVerifiedScoreMultiplicative` and runs when the additive flag is off — pinned by `ValidationReportServiceVerifiedScoreTest`; the new shape (+ guards + caps) is pinned by `ValidationReportServiceAdditiveScoreTest` and `ValidationReportServiceVerificationLedgerTest`. `accuracyScore` (LLM-judged) survives as a pre-V106 fallback; UI surfaces only `verifiedScore` since 6f6feeff. PARTIAL status survives but each one carries a `status_reason` (CLEAN_COMPLETION / CLEAN_WITH_INTERMEDIATE_WARNINGS / FINAL_BUILD_FAILED / TESTS_FAILED:N_OF_M / REVIEWER_UNRESOLVED / REVIEWER_FAILED / ALL_TASKS_FAILED / LEGACY_UNKNOWN) so the UI can disambiguate; `StatusBadge` shows it as a tooltip.

Reviewer (`reviewer/ReviewerAgent.java`): the ordinary BUILD review is a read-only diagnosis. It may
run bounded read/build evidence tools, but it cannot write, edit, shell, format, or perform a
fix-build pass. Any repair must be executed by the checkpointed correction worker and survive the
protected-contract, build, real-test, workflow/config, endpoint, and read-only final-review gates
before promotion. The same read-only boundary applies to the final correction attestation.

**Release-validation hardening (2026-07-23):** nontrivial SMALL/MEDIUM/LARGE planning is fail-closed: malformed native-tool JSON gets one same-route schema-repair call, while a remaining provider/auth/planner failure terminates as `PLANNER_UNAVAILABLE` instead of silently collapsing the request to one LOW synthetic task. `FULL_PLAN` requirement ownership is now unconditional even while the broader `validation.v3-strict` heuristics remain disabled: missing/unknown ownership gets one strict-provider repair and a second invalid graph is rejected. Every SMALL/MEDIUM/LARGE/SINGLE_SHOT result also converges on one shared pre-gate executable-plan stabilization boundary. It compiles deterministically, permits one targeted repair on the run-bound OutlinePlanner provider/model with cross-provider failover disabled, revalidates, then permits at most one different quality-ranked effective PLANNING route when the run policy allows automatic escalation. Exact pins and confirmation-required runs block escalation; benchmark-required runs admit only benchmark-backed candidates; available providers come from the durable execution policy; and candidates must equal an effective PLANNING cell from the run-bound org/Enterprise model matrix. The current matrix constrains eligible cells but does not define an ordered Enterprise recovery ladder. Every dispatch is strict-provider and accepted only when the physical-attempt ledger proves the exact selected route. A physical mismatch fails immediately without another provider call; a missing, policy-blocked, or exhausted route stops as typed `PLANNER_RECOVERY_POLICY_BLOCKED` evidence rather than pausing for planner-specific approval. Deterministic contract/coherence rejection details are persisted in the existing bounded `failureReason` field after secret/local-path sanitization; provider and infrastructure exceptions remain generic. Overview, Steps, Evidence, and terminal-run Ask therefore reload the same durable rejection evidence even when no worker task or validation report exists. SINGLE_SHOT surface inference gives explicit CLI/library intent precedence over broad backend/server words, including negated constraints such as "do not create an HTTP server." `WorkspaceTestRunner` prefers generated `.venv`/`venv` Python environments, distinguishes Vitest from Jest, and records an unparseable nonzero runner exit as deterministic red evidence rather than an LLM-coverage fallback. `BUILD_ONLY` executable verification cannot retain a clean terminal status, and a final-task worker warning remains PARTIAL even if the reviewer later proves only that the tree builds. Final assessment is also tighten-only: a pre-report `CLEAN_*` reason cannot erase a newly measured `NEEDS_ATTENTION` result; successfully auto-fixed visibility issues are not open blockers; and admitted missing/partial spec requirements cap the headline/rubric score at the accuracy score. `AccuracyReportTruthPolicy` moves UNKNOWN entries whose own text states negative implementation evidence to PARTIAL before scoring, preventing an admitted hard gap from retaining an A grade. Final plan normalization reruns deterministic executable-contract closure so subprocess readiness, framework HTTP/request-validation envelopes, and complete stack-matched Playwright lifecycle/isolation/teardown bindings cannot be lost after provider repair or description compaction.

**Live evidence consistency (2026-08-02):** active run detail monotonically overlays persisted cost
with the JVM-owned `RunCostTracker` snapshot while terminal reads retain stored durable truth.
`EffectiveRouteProjection` keeps `MIXED` for multiple models but carries a common provider for a
same-provider tier mix; provider-only requests therefore do not falsely diverge, while exact model
pins and publication attestation still require one `LEDGER` route. Feature assessment uses bounded
schema-mutation phrases plus local negation, so “do not change schema” does not emit a false risk.
Nested `repos/<repo>` test discovery resolves the repository manifest before recursive pytest
markers, preserving Maven/Surefire command attribution. A malformed/empty wiring result gets one
strict exact-same-route typed-verdict recovery and otherwise remains `REVIEW_FAILED`;
reviewer-protocol failure
cannot trigger a post-validation mutating worker rerun. Enterprise Project Start reads allowed exact
models from `/api/models/me`, which is available to run submitters rather than only org admins.
Browser run-mutation retry ledgers persist only a canonical SHA-256 digest, idempotency key and
timestamp—never the request/spec body—and delete known scoped v1 rows during upgrade. Wiring
protocol recovery rebuilds the request with `strictProvider=true`, preserving the original exact
provider/model even when the ordinary initial read-only reviewer allowed failover.

The generic hierarchical-coherence wrapper is not deterministic evidence: prompt assembly, model
resolution, provider/transport failure, and malformed tool output can all reach it. Only a
validator-owned detailed cause receives the sanitized `Plan validation rejected:` marker. Run read
models project that marker as `PLAN_VALIDATION_REJECTED` while retaining persisted
`PLANNER_UNAVAILABLE` for compatibility; provider/infrastructure failures remain
`PLANNER_UNAVAILABLE`. Ask's zero-provider explanation path is gated by the same marker, so it
cannot infer that provider availability was irrelevant from a generic coherence wrapper.
The final worker-brief compiler uses a typed executable-contract exception for its deliberate
fail-closed findings; arbitrary compiler/runtime exceptions remain generic and the rejected
candidate is not persisted as deterministic proof. Durable diagnostics also redact canonical
macOS `/private/tmp` and `/private/var/folders` paths in addition to ordinary user/temp paths.

Flattened adjacent uppercase `(binding...)` markers are restored as atomic paragraph boundaries
before task-description and worker-brief budgeting. This keeps the complete declared browser-test
runner, dependency, script, isolated state, readiness, backend/frontend lifecycle, and teardown
contract intact after coherence and compaction.

Python build-command normalization treats standalone `python` as `python3` and standalone `pip` as `python3 -m pip`; explicit paths, `pip3`, and an existing `python3 -m pip` invocation remain byte-identical. This keeps planner-authored verification portable across minimal Linux and modern macOS hosts without rewriting arbitrary shell words.

**Plan safety enrichment (2026-07-24):** hierarchical coherence no longer relies on a lightweight model to rediscover predictable safety contracts. Before the provider-specific coherence call, `PlanSafetyContractEnricher` deterministically propagates verified-principal/owned-resource and hashed-credential checks, forbids predictable/default credentials in runtime code while permitting isolated test fixtures, rejects zero/negative reservation quantities with public-boundary tests, replaces ineffective SQLite `SELECT FOR UPDATE` and SQLAlchemy `with_for_update()` plans with an actually atomic mutation contract, preserves invalid-state behavior on double cancellation, and requires the configured migration mechanism on the documented default startup and test-fixture paths without ORM `create_all`. For HTTP deliverables it also requires acceptance tests to launch the documented production server, apply real migrations to a fresh database, and exercise public behavior over real HTTP; contradictory task descriptions/constraints (caller-selected identity, successful double-cancel replay, in-process `TestClient` acceptance, or ineffective locks) are rewritten before becoming worker prompts. The model pass remains responsible for project-specific producer/consumer mismatches; its protocol failures still fail planning closed.
Explicit order lifecycle values in the authoritative requirement win over legacy reservation
defaults; the supported state vocabulary includes `DRAFT` and `COMMITTED`, and schema
normalization renders the complete requested lifecycle dynamically. Required idempotency headers
also remain required in typed frontend client signatures rather than becoming optional parameters.

**Domain-neutral final contract reconciliation (2026-07-25, hardened 2026-07-26):** the final hierarchical gate no longer embeds the inventory/order schema used by an earlier manual test. `PlanContractQualityGate` derives producer and consumer HTTP methods, canonical `/api` paths, endpoint-local query parameters, and response fields from the current plan. Relative collection routes compose with an explicitly declared or single-router-artifact prefix, and an explicit “same endpoints” contract can reuse the preceding resource's method/path suffix inventory. When several tasks mention one endpoint, the task owning the matching router artifact is authoritative; incidental verification reads cannot steal producer ownership. When a frontend aggregate read has no producer, normalization composes the existing declared collection reads client-side instead of preserving an invented endpoint. Impossible consumer `GET` mutations (201/request-body creates and reserve/commit/cancel-style actions) are normalized without letting a collection `GET` overwrite the mutating method; missing operations are then bound to the one backend router artifact that owns that resource prefix. Unsupported prose-only query suggestions are removed when the producer does not declare them. Requirement coverage treats natural hyphenated prose semantically rather than as schema identifiers, and a bounded requirement-signal line preserves any still-missing contract vocabulary before worker execution. SQL `SELECT FOR UPDATE` is never confused with an unresolved natural-language “select one” choice. Setup commands are reconciled to the dependency manifest an owning task actually creates. The gate compares compact Markdown and TypeScript field declarations for shared named models, rejects foreign model definitions and SQL writes to undeclared canonical fields, validates explicit cross-task symbol references, and requires model-local lifecycle sections to agree with the canonical lifecycle. Route inventories must have balanced syntax, one canonical versioned namespace, and no duplicate operation declarations. Documented test commands must point at owned artifacts and use the same origin variable the plan establishes. Provider-generated compaction markers, dangling numbered steps, and contradictory database initialization strategies fail closed. Coherence can make a minimal exact-description replacement, and one same-provider targeted repair is allowed when deterministic final validation still finds a contradiction. The repaired plan is validated again and still fails closed before worker spend if inconsistent.

**Canonical API route identity (2026-07-30):** path-parameter names are stack-neutral for contract comparison, while the unique backend producer's exact spelling and namespace remain authoritative in consumer and test evidence. `/x` and `/api/x` are distinct producer identities even though a frontend client with an `/api` base may use `/x` as a relative call site. `PlanSourceHttpRouteGate` compares explicit `METHOD /path` operations from the original request with the final compiled producer brief and fails before the plan gate/worker spend if normalization adds or removes the `/api` namespace. Mutating action routes that disagree on collection versus item cardinality fail closed instead of preserving both aliases. A parameterless collection GET cannot satisfy a clause that claims a missing single-resource operation.

Bare `Props` declarations are local to their UI component and are no longer conflated across
unrelated tasks. Component-qualified props types and explicitly shared/exported/reused `Props`
contracts remain subject to the same cross-task field-drift gate.

The same scope rule applies to bare aggregate-local `id` fields: Product.id and Warehouse.id are
not one shared schema contract merely because both are called `id`; qualified relationship fields
such as `warehouse_id` remain strict. When a task already selects SQLAlchemy
`create_async_engine`, a leftover synchronous `sqlite:///` default is mechanically aligned to
`sqlite+aiosqlite:///`, and executable dependency closure adds `aiosqlite` to the manifest owner.

Model-definition ownership is also closed deterministically: a migration/documentation task that
describes a model owned by another task keeps the schema fields it consumes, but its definition
claim becomes a consume-only binding to the unique model/entity artifact owner. The final gate
retains a stack-neutral rejection when ownership remains ambiguous or contradictory. Page files
also remain exclusive unless a later task directly depends on the prior owner and explicitly names
that exact `owned by Tn` hand-off; this narrow exception permits sequential component mounting
without accepting ambiguous duplicate page work, and never applies to models or migrations.

**Hierarchical requirement allocation (2026-07-26):** LARGE/LEGACY skeletons now allocate
`meetsRequirements` before parallel task expansion. The compact graph is validated independently of
the feature-flagged full-plan body checks, receives one bounded same-route retry, and fails before
fan-out if any task is unowned, any ID is unknown, or any requirement is uncovered. This removes
the former N-way full-requirement replay/guessing boundary that both inflated expansion input and
allowed task-local codegen contracts to lose user intent. Recursive EPIC sub-outlines receive the
parent's exact owned requirement IDs/text and must preserve complete ownership; an invalid
sub-outline falls back through a real single-LEAF expansion, not a generic title-only task.

**Compacted route ambiguity (2026-07-25):** `TaskExpansionRouteContract` scopes each route occurrence to its nearest sentence, newline, semicolon, or compacted Markdown-bullet boundary. Repeated preservation of a correct item route no longer inherits a neighbouring collection route's “list” wording and triggers a false semantic repair; a literal parameterized route that is actually assigned both collection and item semantics still fails.

**Browser E2E boundary (2026-07-25):** when a declared end-to-end requirement launches both the real backend and frontend, the dedicated browser task must drive every required customer journey through Playwright page interactions. Direct API calls are limited to health/readiness checks; they cannot seed business data, perform the workflow, or replace rendered-UI assertions.
The gate also rejects unresolved worker choices, incompatible lifecycle claims for the same shared
artifact, and unmapped `state`/`status` vocabulary drift within one entity contract. The shared
outline, task-expansion, and coherence prompts require one concrete strategy and one shared
artifact vocabulary across every provider.
Oversized provider task bodies are rejected as a repairable schema violation instead of being
silently compacted. One bounded same-route schema-repair call must emit a complete description
inside the 4,000-character envelope; a repeated oversize response fails planning closed.
Provider-ignored `maxItems`/`maxLength` limits on the expansion arrays are likewise
bounded before envelope validation, avoiding a futile schema-repair call while preserving the
authoritative skeleton values during merge. Consumer reconciliation canonicalizes route-parameter
names, action-obvious CRUD drift (save/update/deactivate), and exact-path method drift in both task
descriptions and constraints. Response-field bindings are selected by the endpoint resource rather
than leaking a different schema from the same producer task, and prior generated bindings are
regenerated on each normalization pass. Reconciliation fails closed when no backend producer
declares an operation instead of guessing from task
titles.
Before dependency-wave expansion, `PlanExpansionDependencyEnricher` adds cycle-safe producer edges:
functional frontend skeletons expand after backend implementation skeletons, and test/E2E
skeletons expand after implementation tasks. This is deliberately broader than title-token
matching so a dashboard or workflow consumer receives the actual established route/DTO contracts
instead of inventing them before its producers have expanded. After those edges are established,
transitively redundant dependencies are removed so each consumer receives the nearest producer
frontier rather than replaying the same upstream task bodies repeatedly. Coherence evidence emits
the first exact repeated constraint and compact `same-as Tn` references thereafter; worker task
contracts remain complete and unchanged.

Strict post-edit formatting uses the same containment boundary: Black, Prettier, and gofmt receive
only symlink-checked `/workspace/...` paths through `SandboxCommandRoutingService`; an unavailable
sandbox is recorded as a formatter error and never falls back to a host process.

### Four customer-facing autofix surfaces (V183 `triggered_by_finding`)

All routed through one chokepoint `finding/FindingPatchSubmitter`. The committed V350-era
implementation resolves the host project's org as the PATCH data tenant and the active finding
actor's home org as payer/provider-policy authority before dedupe or dispatch. Submitter dedupes per
finding key, dispatches with `runMode=PATCH` + `source="finding:<TYPE>"`, and `runPatchPipeline`
auto-pushes via `scmPushService.push()` on terminal; current-source deterministic verification is green:

| Phase | Surface                               | UX                                                                                                                 |
| ----- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| 1     | `prreview/PrReviewAutofixService`     | PR Review verdict popover → "Autofix N comments" → pushes to PR head branch via `branchOverride`                   |
| 2     | `project/techdebt/TechDebtFixService` | TechDebtPanel category rows → "Fix" per category → fresh PR per category                                           |
| 3     | `project/pr/CiFailureAutoFixService`  | GitHub `check_run` webhook auto-fires on PR CI failure (flag `ci.autofix.enabled`, default OFF; max 3 attempts/PR). V359 commits a unique claim and attempt reservation before submission, stamps the same finding key in the run's initial insert, then binds the canonical PATCH run in a separate fenced transaction. FAILED or expired claims recover safely before retry. |
| 4     | `codereview/CodeReviewAutofixService` | CodeQualityPanel severity chips → "N CRITICAL" / "N MAJOR" → one PR per severity                                   |

Phase 3's webhook stays OFF until SWE-bench Lite resolved% justifies the unattended-bot path.

### Tier ladder (live on `dev` + `main`)

**FREE** (200 managed AI credits/month, no card, low-price native DeepSeek only) → **PRO** ($20/mo, 2,000 managed AI credits, all default models, single-developer feature set) → **PRO+** ($100/mo, 125,000 managed AI credits, hidden upsell surfaced from Pro checkout or mid-month exhaustion) → **BYOK** ($50/mo, single-person org, mandatory provider keys, model matrix, Team-like solo controls) → **TEAM** ($50/seat/month BYOK, minimum 2 seats, all collaboration/governance features) → **ENTERPRISE** (custom, governance/security/procurement contract path). V253 refreshes `tier_config`, `billing_plan`, managed-AI config, top-up packages, Team's 2-seat floor, BYOK/Team model-matrix editability, and the active website/app copy to this ladder.

Each run costs 1–N run credits proportional to estimated LLM cost: `credits = max(1, ceil(bufferedEstimate / $5.0))`. Charged at plan approval (not submission); refunded on FAILURE. Customer can voluntarily lower the ceiling via `RunRequest.userCostCap` (clamped against the platform cap). BYOK orgs (`keySourceMode=OWN_KEYS`) skip the platform cap entirely — with no `userCostCap` the run has **no hard cap at all** (NULL); HYBRID + SYSTEM stay capped.

**Managed AI credits (V245 + V253):** `$1.25 = 100 AI credits` for monthly grants and top-ups, configured as `managed-ai.credit-pack-usd` + `managed-ai.credit-pack-credits`; successful platform-key LLM calls debit `ceil(raw_cost_usd * retailMultiplier * creditsPerUsd)` from the user's monthly managed-AI period (retail multipliers: PRO 1.25, all other tiers 1.00 — PRO credits deplete 25% faster per raw dollar). Top-ups are marginal purchases at the explicit package/custom rate (`$5 → 500`, `$10 → 1,000`, custom `$X → X*100`) and add `purchased_ai_credits` to the current period instead of the old bonus-run ledger. PRO is configured as 2,000 included credits; PRO+ is configured as 125,000 included credits; FREE is configured as 200 credits and **hard model-locked to native DeepSeek Flash** via `managed-ai.tiers.FREE.locked-provider=deepseek` + `locked-model=deepseek-v4-flash-native`, enforced in `ManagedAiUsageService.authorizeCall` — every FREE managed platform-key call is forced to `deepseek-v4-flash-native` regardless of the requested provider/model (F2 fix 2026-07-18; any tier can set `locked-provider`/`locked-model` to pin its managed route). BYOK/TEAM/ENTERPRISE are ungated for managed credits; the balance API exposes `unlimitedAiCredits=true` and `includedAiCredits` / `aiCreditsRemaining = -1` for those zero-cap unlimited tiers. Customer-facing web surfaces show AI credits and hide raw USD spend/caps unless the viewer is SUPER_ADMIN, global FINANCE, or an ORG_ADMIN/BILLING_ADMIN of the owning org (`cost/CostVisibilityPolicy`). `LlmRouter` pre-authorizes each call against `ManagedAiUsageService`; disallowed model classes step down to the same provider's cheap route when available, critical planner/reviewer calls in `VALUE_MODE` can step up to a same-provider frontier model while frontier budget remains, and exhausted states fail before provider spend. Successful calls write `managed_ai_usage_event`, update `managed_ai_usage_period`, and trigger in-app/email transition notifications via `ManagedAiNotificationService`. `ManagedAiTierResolver` owns the managed-credit tier lookup directly from user/org/external-project repositories so `ManagedAiUsagePeriodService` does not depend on `TierEnforcementService`; this avoids the V253 deploy-time bean cycle through `RunTopupService`.

Tier downgrade is also a credential-authority boundary: moving an org to a managed tier resets
`keySourceMode=SYSTEM`, and key resolution/readiness/submission re-check the effective tier instead
of trusting retained user/org key columns. A stale BYOK key can therefore never preserve provider
access after a FREE/PRO downgrade.

### Auth + RBAC

Browser logout blocklists the presented refresh JTI and advances the user's per-user token invalidation epoch, so access and refresh JWTs issued before logout are both rejected. Refresh rotation uses JTI-only revocation so it does not invalidate the replacement session. New JWTs carry an additive full-precision `issued_at` claim (with legacy NumericDate `iat` fallback), allowing an immediate same-second re-login without reopening the prior session.

PAT capabilities are enforced at the authentication boundary. Legacy `full` rows remain
compatible; omitted-scope and Web device-login tokens default to
`api:read,runs:write,projects:write,agent:write,settings:write`, excluding billing, token
management, operator-test, global-admin and unknown mutation routes. Device-cap LRU displacement
produces a dedicated append-only audit event.

Every `/api` response advertises the server-owned minimum supported CLI/VS Code protocol in
`X-ES-Min-CLI-Version`. The split client sends `X-ES-CLI-Version` and converts an older semantic
version into a non-retryable `426 / cli_upgrade_required` error before normal response handling.
`executespec.client-compatibility.minimum-cli-version` (environment override
`EXECUTESPEC_MIN_CLI_VERSION`) must be canonical `major.minor.patch`; the default compatibility
floor is `1.0.0`, because the later 1.0.1/1.0.2 client releases did not break the server protocol.

JWT (access 6h — `access-token-expiry-minutes: 360`, refresh 7d) + OAuth2 (Google, GitHub). OAuth authorization requests are persisted in short-lived signed HttpOnly cookies keyed by `state` (`CookieOAuth2AuthorizationRequestRepository`, 5-min TTL, `SameSite=Lax`) instead of relying only on the Tomcat session, so provider callbacks survive stale/missing session cookies and parallel OAuth attempts (RL-2026-06-032). Scoped SSE tokens (5-min TTL, `scope=sse` + `runId` claim) for event streams. **Personal Access Tokens** (V215, `personal_access_token`) provide programmatic auth for headless clients (CLI / VS Code / CI): a revocable, SHA-256-hashed (never-plaintext) `es_pat_…` bearer the user mints via `POST /api/auth/me/tokens`; `JwtAuthenticationFilter` resolves the `es_pat_` prefix to the **same `AppUserDetails`** a JWT would (identical roles + tenant), and tags the request auth source so server-only capabilities can distinguish PAT from browser JWT. **Local-hands run submit is PAT-only** (RL-2026-06-037): even when `worker.local-hands.enabled` is ON, `POST /api/runs localExecution=true` is rejected for browser/JWT sessions because the web UI cannot enforce local command/file approvals; CLI and VS Code use PAT sessions for that path. **Provider preference** (V242, `app_user.preferred_ai_provider`) is account-scoped: `GET/PUT /api/auth/me/provider-preference` lets web, CLI, and VS Code share one default provider. Explicit per-run/per-chat provider input still wins and is remembered; absent input resolves to the account preference, then existing project/YAML defaults. Roles: SUPER_ADMIN / ORG_ADMIN / BILLING_ADMIN / DEVELOPER / PROJECT_ADMIN / PROJECT_DEVELOPER / PROJECT_VIEWER (read-only, cross-org `ProjectMember` from V175). External cross-org invites also gated by **V198** per-org flag `org.external_sharing_enabled` (default false; flip via `PUT /api/org/me/settings/external-sharing`; ORG_ADMIN-of-own-org or SUPER_ADMIN only). **RR-973 (2026-07-21):** that flag, the ORG_ADMIN role gate and the paid entitlement are all evaluated for the org that OWNS the invited project, never the inviter's home org — project write access is not authority to delegate access to another tenant's project. Accepted external project collaborators now inherit the highest active host-project org tier as an **effective entitlement** through `TierEnforcementService` / `EntitlementResolver` / auth DTOs; the user's personal billing tier is not mutated, so removing the project membership automatically falls back to their own tier. Force-logout via feature-flag epoch-based JWT invalidation. Solo users get a personal org auto-provisioned on registration (V162/V163; `org.is_personal=true`); paid users can convert that personal workspace into a real org through `POST /api/org`, and `OrgDto.isPersonal` tells the web UI whether to show the conversion surface.

`AccessGuardCoverageTest` (`arch/`) pins every controller method whose URL touches `/runs/{` / `/projects/{` / `/executions/{` / `/launches/{` / `/repos/{` to call a tenant access guard — AND that write handlers (POST/PUT/DELETE/PATCH) use `ProjectAccessGuard.assertCanWrite` / `RunAccessGuard.requireRunWriteAccess`, not `assertCanRead` / `requireRunAccess` (RL-2026-05-040 + RL-2026-06-196; genuine read-only POSTs allowlisted in `READ_ONLY_WRITE_HANDLERS`). `KNOWN_LEGACY_DRIFT` is empty (35/35 cleared); new violators fail the build.

LOCAL-hands runs terminalize as `PARTIAL / LOCAL_EXECUTION`, not `COMPLETE`: edits and tests occur in the PAT-authenticated client workspace while server-side lint, review, standards, invariants, validation and scoring are skipped. The Web result labels this “Changes ready locally — not server-verified”; these runs do not emit activation-success analytics and their empty server workspace is never archived as evidence. A future COMPLETE path requires a source/installation/fingerprint-bound diff and test-evidence bundle validated by the server.

**Execution locality is run authority (2026-07-21, RR-479/RR-495).** Where a run executes must match
what the caller was promised, on every later action and not only on first dispatch. Single-task retry
(`POST /api/runs/{id}/tasks/{taskId}/retry` and the approved-continuation path behind it) used to
build a plain server-side `ExecutionRequest` regardless of `pipeline_run.local_execution_requested`,
so retrying a local-hands run silently moved command execution and file mutation onto the platform.
`RunLifecycleService.retryTaskInternal` now reads that durable intent first: it rebinds the local
transport (`registerAfterWorkerRestart`), runs with `localHandsMode`, skips the server smoke/reviewer/
validation and terminalizes `PARTIAL / LOCAL_EXECUTION` — or, when the local-hands flag or transport
is unavailable, refuses the retry with an explicit message instead of degrading to server execution.
Crash resume gained the matching terminal correction: a resumed local run no longer runs the server
reviewer and validation over a workspace that does not hold the customer's changes. The `agentsession`
writer/IMPLEMENTER seam is the same boundary from the other side: it is a SERVER sandbox that
CLI/VS Code callers may drive, so its scope is server-derived rather than a caller-named host path.

Repo lifecycle writes are also authority-checked at the service boundary: ORG-scoped sync and
webhook installation require ORG_ADMIN (or SUPER_ADMIN), while USER-scoped writes require the
owner. Read visibility alone never authorizes SCM-side or shared-metadata mutation.

### Security

`RemoteToolDispatcher` uses an explicit Spring-selected constructor for runtime dependencies; its bounded test constructor cannot be selected during application startup. (`LocalProjectSourceService` and `StartupRunResumer` follow the same pattern — `@Autowired` production constructor, package-private test overload. `StartupRunResumer` was corrected 2026-07-31 after a public test-compatibility constructor beside Lombok `@RequiredArgsConstructor` left no Spring-selectable constructor and crash-looped the dev boot.)

**Credential gate (2026-07-17):** every persisted credential is wrapped in a self-describing versioned envelope `enc:v1:<keyVersion>:<Base64(IV||ct+tag)>` via `crypto/SimpleKeyEncryptionService` + `EncryptedValueConverter`. Envelopes fail CLOSED — wrong key (GCM auth failure), unknown key version, malformed value, or an envelope met while crypto is disabled all throw instead of returning ciphertext; legacy bare-Base64 and plaintext rows keep decrypting until `KeyMigrationRunner` re-envelopes them at startup (its column list now covers github_pat, bitbucket_app_password, deepseek/fireworks/openrouter keys, and `org_slack_integration.bot_token`, which is also `@Convert`-encrypted now). Rotation uses `executespec.crypto.key-version` + `previous-keys`: the retired keys decrypt old rows during startup and `KeyMigrationRunner` rewraps every non-current envelope under the active version, after which the retired key can be removed. `application-prod.yml` and `application-benchmark.yml` set `require-encryption: true` (boot refuses passthrough). Gmail OAuth refresh tokens are enveloped on disk through `crypto/EncryptedDataStoreFactory`. Git credentials never enter argv or persisted remotes: `repo/GitProcessAuth` passes PATs/API tokens through an ephemeral env-backed credential helper (all clone sites in `WorkspaceInitService`/`GitHubRepoService`/`BitbucketRepoService` and all push sites in `GitTool`); Bitbucket API-token Git calls use `x-bitbucket-api-token-auth`, and `inheritIO()` on git children was removed. Entity secret fields (+ `passwordHash`) carry `@ToString.Exclude`. `personal_access_token.token_hash` stays SHA-256 (one-way by design).

**Background-work release containment (2026-07-19):** `config/OperationalReleasePolicy` fails closed on the unfinished cross-JVM surfaces. In `prod` and `benchmark`, deployment creation/analyse/confirm is unavailable and `TierPermissions.canDeploy=false` unless `agent.deployment.recovery-safe-enabled=true` (prod env `DEPLOYMENT_RECOVERY_SAFE_ENABLED`); cleanup/read/rollback/destroy remain available. Outbound integration lifecycle delivery is disabled in those profiles regardless of accidental adapter enablement; non-release dispatch is explicitly `BEST_EFFORT`. Crash recovery permits a same-host process restart, or a cross-host restart only when `durability.workspace.shared-persistent=true`. The current async GCS task archive is not accepted as an acknowledged task frontier, so an untrusted cross-node takeover fails/refunds instead of combining stale files with newer terminal task rows.

**Rolling-deploy interruption safety (2026-07-23):** an interrupted worker `ERROR` cannot be
soft-completed merely because earlier writes exist; its task remains non-terminal for fenced crash
recovery. `StartupRunResumer` retries interrupted V2 candidates every 60 seconds, so a boot scan
deferred by the predecessor's still-fresh two-minute heartbeat is reclaimed once stale rather than
remaining `WORKING` forever. Execution leases use a stable host identity across JVM PIDs (explicit
override, container hostname, Linux machine-id, or hostname fallback), so a systemd restart on the
same VM is not misclassified as an unsafe cross-node takeover. A newly submitted run with no first
heartbeat is protected by its two-minute `startedAt` grace period, so the recurring recovery sweep
cannot steal an active planning call before the owner's initial heartbeat refresh. New run rows
also persist their execution owner and first heartbeat atomically with submission, preventing an
early full-entity planning save from clobbering the lease back to null. Shutdown cancellation is
non-terminal for both provider-error and iteration-boundary stop outcomes even when files were
already written; unverified writes cannot soft-complete an interrupted task.
Recovered execution, task retry, approved continuation and manual re-review also reload the
original V282 run context before the first LLM call. Recreating only the filesystem
`ExecutionContext` is invalid: enforcement rejects that unscoped call and would otherwise mark
untouched post-restart tasks failed.
Before publishing any terminal run state, usage totals are raised to at least the successful
physical-attempt sums in `run_step_usage`. This closes the crash window where provider attempts were
durable but the interrupted executor never returned to its in-memory phase-attribution boundary;
the reconciliation is monotonic and cannot lower larger existing totals.

8-layer defense-in-depth: request validation → input sanitization (`InputSanitizer` — NFKC + zero-width strip + base64 decode+scan) → LLM prompt defense → code abuse/credential scanning (`CodeAbuseScanner` plus `GeneratedSecretScanner`) → shell sandbox (`ShellSandboxPolicy`) → file sandbox → process isolation → container hardening. The BUILD pipeline keeps planner/worker policy, trusted classpath overlays and tool contracts in `SystemMessage`s, while repository/workspace context, project memory/brief, retrieved documents, contracts, ADRs, PR comments, generated plan/task content, recursive EPIC context, task constraints, design context, repo orientation, run journal and build errors are delimiter-safe `PromptTrustBoundary` evidence in `UserMessage`s; embedded repository instructions therefore do not inherit system authority in outline, expansion, coherence or worker calls. The same authority split now covers PATCH tasks/orientation, reviewer build output, critic deliverables, post-run run/project chat, apply suggestions, memory ledger/compaction, validation/test-generation/reporting, API-source documentation, context generation, feature assessment and run-codemap summarisation. `PromptTrustBoundary` NFKC-normalizes content, strips zero-width/cross-label delimiter variants, and neutralizes Base64-encoded boundary closers. `writeFile` and `editFile` fail closed before disk mutation when generated content contains a high-confidence provider/PAT/cloud/live-payment key or PEM private-key marker; findings expose only a category, never the matched value. This is a bounded generated-file guard, not a substitute for scanning shell mutations, local-hands writes, pre-existing source, final diffs/exports or encoded/unknown credential formats. Configurable headers (CSP, HSTS, X-Frame-Options) via `SecurityProperties`. Three guardrail levels (LOW / MEDIUM / HIGH) flip via `security.guardrail_level` flag.

**Shared-profile crypto correction (2026-07-19; temporary dev exception 2026-07-23):** prod and
benchmark keep `require-encryption: true`. Shared dev temporarily uses `require-encryption: false`
to recover deployment while `CRYPTO_GCP_SECRET_RESOURCE` is provisioned with an immutable numeric
Secret Manager version and the VM IAM binding is verified. This means dev credential writes are
plaintext passthrough during the exception; remove it immediately after the governed key is live.

**Dev OAuth boot fallback (2026-07-23):** declared Google/GitHub registrations use the non-empty
sentinel `none` when the VM has no OAuth environment variables, because Spring validates declared
registrations at startup. OAuth is unavailable until the VM supplies real client IDs and secrets.

**Anonymous-session bearers (2026-07-21, RR-665).** The `anonrun/` trial-session token is a bearer
credential and is now treated as one. `AnonymousRunSessionStore.bindToUser` was read-check-write, so
two concurrent claims of one unbound token could both be told they succeeded and race to overwrite
the owner; ownership is now decided by a single `SETNX` on a separate claim key
(`v3:anon-run:claim:<token>`) that is the authoritative record, the session blob is written only by
the caller that won it, and every loser is returned the real owner. Sessions bound before the claim
key existed seed it from the blob, so they keep exactly one owner. Separately the store, the service
and the stub dispatcher all logged the token verbatim (or a usable prefix) and
`POST /api/v3/auth/claim-anonymous-session` echoed it back after authentication; every diagnostic now
uses a truncated SHA-256 fingerprint and `ClaimResponse` no longer carries the bearer field at all.
**At-rest closure (2026-07-22):** the earlier "inherent to a token-keyed session store" note was
wrong — the store is only ever asked about a token the caller already holds, so the bearer never has
to be persisted. Both the session and claim keys are now the full `sha256(token)` (the same one-way
discipline as `personal_access_token.token_hash`) and `save` strips the token out of the JSON before
writing; `find` re-attaches the caller's own token so every consumer sees an unchanged
`AnonymousRunSession`. A keyspace listing, `SCAN`/`MONITOR` trace or Redis snapshot therefore yields
no usable credential, pinned by `AnonymousRunSessionStoreTest`. Sessions written under the legacy
raw-token key stay readable and are rewritten hashed on their next update.

**Framework path containment (2026-07-21):** `security/SafeWorkspacePath` is the shared
containment boundary for framework-owned filesystem access into customer-controlled trees, applying
the same rules the agent tool sandbox already used (`tool/PathSafety`,
`deployment/WorkspaceFileService`): relative-only, no dot/empty segments, canonical-root
containment, and no symbolic link in any existing component. It is called by run-context metadata
writes (`integration/RunContextComposer`), worker checkpoint write/read/delete AND the destructive
run-end cleanup (`context/ContextStore`), the context scanner/agent/evidence-bundle/run-journal
reads and writes under `.agent-context` (`context/*`), and the Code Review workspace sampler
(`codereview/CodeReviewService`, which additionally opens candidates with `NOFOLLOW_LINKS`).
`folder/ExecutespecFolderWriter` now enforces canonical POSIX relative segments — a `.executespec/`
prefix alone is not containment — and contract refresh derives a bounded filename slug rather than
interpolating a customer-derived contract name. Generated test class names must be bare Java
identifiers before they become filenames (`testgen/TestWorkerAgent`, validated at plan persistence
in `TestPlannerAgent`). Failures are refusals, not redirects. This covers the server-side framework
paths; agent tool calls keep their own boundary, and the CLI/VS Code client repository has its own
equivalent gaps tracked separately.

`SensitiveWorkspacePathPolicy` is the pre-model credential-file boundary for worker tools. Direct
reads and search hits exclude `.env`, credential stores, private keys and keystores while allowing
`.env.example`/sample/template contracts; host-shell validation blocks explicit references to the
same credential material. Every server `readFile` full/range/project/DB-fallback result also passes
`GeneratedSecretScanner`; a high-confidence credential in an otherwise ordinary or template file
returns stable FORBIDDEN evidence rather than source bytes. Shell and search previews pass through
`ContextSanitizer` secret redaction before worker history. This protects server model context;
local-hands provider-capture parity and publication scanning remain independently mandatory.

Run-file and run-chat reads apply the same sensitive-path policy. `WorkspaceFileService` omits
credential paths and symlinks from listings, denies absolute/traversal/symlink reads, proves real
path containment and applies the rule to GitHub fallback before content reaches a model. Direct
Gemini generation/cache/validation requests carry credentials only in `x-goog-api-key`, never URL
queries. Client-default PATs exclude `credentials:manage`; provider/SCM/Jira/Confluence credential
validation/mutation and org key-source changes require that explicit capability (legacy `full`
tokens remain compatible).

SUPER_ADMIN runtime configuration in GCS is a bounded non-secret surface, not an arbitrary
environment-variable store. Accepted keys are limited to documented CORS, preview, concurrency,
continuation and observability namespaces; secret-like keys and high-confidence credential values
are rejected. Updates use GCS generation-match CAS and persist before changing process memory, so a
failed or concurrent write cannot return success with a JVM-only value.
Successful runtime changes append actor audit only after persistence. Secret Manager updates append
secret-name/version-only audit and report `restartRequired=true`; raw provider errors and secret
values never enter the admin response or audit payload.

`ArtifactPublicationGuard` is the final current-byte publication boundary: immediately before
GitHub/Bitbucket push it scans tracked plus non-ignored untracked candidates, and it also validates
workspace ZIPs, new/historical project-export tarballs, governed public/report JSON, PDF/Markdown
downloads, Code Review and generated Project Doc output. Archive traversal/type/count/inflation and
Git enumeration limits fail closed; findings expose category/path only and there is no request
override. Local focused and full-regression evidence is green. This is not proof for deleted Git
objects, preview/container images, argv/logs, screenshots/recordings or outbound email; those remain
separate live release/DLP gates.

Rate-limit and public-share audit identity use the effective servlet `remoteAddr`; application code
does not parse `X-Forwarded-For` or `CF-Connecting-IP`. Dev/prod/benchmark enable Spring's forwarded
header strategy, so the edge must strip/replace forwarding headers and block direct backend access.
Externally supplied page/size values on the remediated run/execution, launch, notification,
org/platform audit and payment-audit surfaces flow through `api/PageBounds`: page must be
nonnegative, size must be within the endpoint maximum, and integer-offset overflow is a stable HTTP
400 rather than an allocation/query failure. The wider controller inventory and cursor conversion
for high-cardinality ledgers remain Phase 5 work.

Cleanroom containers run with `--rm --network none`; ProgramBench bind-mounts at `/workspace`, SWE-bench at `/testbed`. `ShellTool` + `BuildProjectTool` consult `ProgramBenchContainerStore` per invocation; when a containerId is set for the run, the tool dispatches via `docker exec` instead of host shell.

**Sandbox exec seam (2026-07-21, `sandbox/`).** Three audit lanes converged on the same missing
piece: `RunSandboxRuntime` could `provision` / `destroy` / `alive` a network-none, read-only-root,
cap-dropped container but could not run anything in it, so customer-generated build and test
commands (RR-282, RR-119) and admitted repository-controlled lint (RR-1051) still executed on the
application host as the service account — with host network reach, including the GCE metadata
endpoint that a stripped environment does not hide. `RunSandboxRuntime.exec(sandbox, request)` and
`RunSandboxRuntimeDispatcher.exec(sandbox, lease, request)` are that seam.
`SandboxExecRequest` is an argument **vector** with a hard 30-minute ceiling — a shell is reachable
only by naming it (`["sh","-c",…]`), never as an invisible default. `SandboxExecResult` carries a
status (`COMPLETED` / `TIMED_OUT` / `UNAVAILABLE` / `REFUSED`), not just an exit code, so "no
sandbox ran this" is not readable as "the command failed" and cannot be caught into a host-execution
fallback. The dispatcher gates on `sandbox.runtime.dispatch.enabled`, then on
`RunSandboxStore.authorizeExec` (one statement judging fence and state together — a stale owner gets
`SandboxLeaseFencedException`, a non-READY sandbox gets `REFUSED`), then delegates. `SandboxProcess`
drains stdout and stderr separately on virtual threads so the timeout is actually reachable, caps
each stream at 256k chars, and destroys the whole process tree on expiry.
**Container fencing and mount containment (2026-07-21, RR-327 / RR-328).** The container name is
derived from the sandbox id so a restarted JVM reattaches instead of leaking a second container —
which means the name is not an ownership proof. `SandboxContainerCommand.create` now stamps
`com.executespec.sandbox.id` and `com.executespec.sandbox.epoch` labels, and reattach, `alive` and
`destroy` all read them back and act only on an exact match; an unlabelled, foreign or
different-generation container, or an unreadable engine, is a refusal (surfaced as
`TEARDOWN_FAILED`), never a guess. The ownership half moved to the dispatcher: the
`SandboxLeaseFencedException` branch of `provision` no longer destroys anything, because a failed
fenced transition is proof that another JVM owns the sandbox and the takeover reattaches to that
same container; the ordinary-failure branch cleans up only while `holdsLiveLease` is true. Writable
bind-mount admission was lexical and said so in its own javadoc, so a symlink planted inside the
sandbox root bind-mounted its target read-write; it now proves containment on disk through
`security/SafeWorkspacePath` (no symbolic link in any existing component, canonical-root
containment) and additionally requires the mount to already exist as a real directory. All three
sandbox flags remain seeded false.
**Explicitly not closed:** a timeout destroys the host-side `docker exec` client tree; the process
inside the container is a child of container init and is bounded only by the container's own
CPU/memory/PID/wall-clock ceilings until release — a container-side kill would depend on a binary
present in an untrusted image. The lease check is a check, not a hold. Neither the label fence nor
the mount check eliminates its check/use window: Docker offers no compare-and-delete, so a
generation could in principle change between the inspect and the `rm`, and a mount path validated
here could be replaced by a symlink before the daemon opens it. Closing those needs
descriptor-relative mounts or engine-managed volumes. `exec` does not re-read the labels per
command; a stale owner is caught at `authorizeExec` in the database, but a name-colliding container
is not.

**Callers migrated onto the seam (2026-07-21, RR-119 / RR-282 / RR-1674).**
`sandboxroute/SandboxCommandRoutingService` is the caller-side adapter: it resolves the run's
sandbox from `RunSandboxStore.findActiveForRun` (admitting one through `RunSandboxLifecycleService`
when absent), keeps this JVM's lease alive across a run's many commands, and hands one argument
vector to `RunSandboxRuntimeDispatcher.exec`. `routingEnabled()` — `sandbox.lifecycle.enabled` AND
`sandbox.runtime.dispatch.enabled`, both still seeded false — is the only question a caller may ask.
Four call sites now consult it ahead of any host `ProcessBuilder`: `tool/BuildProjectTool`,
`tool/RunTestsTool`, `smoketest/WorkspaceTestRunner` and
`standards/enforcement/lint/LintProcessRunner`. **`UNAVAILABLE` is never a host fallback** — the
build reports "build unavailable", `runTests` reports "tests unavailable", the workspace test runner
returns no count (the existing fail-safe to LLM-judged coverage), and lint reports UNAVAILABLE. The
SWE-bench / ProgramBench cleanroom branches are untouched; those containers are already the boundary.
**With the flags off every path is byte-identical**, pinned by `SandboxRoutedBuildAndLintTest`.
**Not yet operable with the flags on:** `SandboxContainerCommand` admits a writable mount only under
`executespec.sandbox.workspace-root/<sandboxId>` (RR-821), and a run workspace lives under
`agent.workspace/<runId>`, so provisioning is refused and every routed command reports UNAVAILABLE.
That is fail-closed and correct, but the flags cannot be turned on for real work until `sandbox/`
admits the run workspace (or the workspace is staged into the sandbox-owned root). `LintAdapter` /
`StandardsRunner` also do not thread a run id, so lint has no run scope to be contained in.

Terminal-Bench 2.0 (`terminalbench/`, RL-2026-06-004) is an operator-only **local/dev** benchmark adapter, not a customer surface. `POST /api/v2/test/terminal-bench/start` (SUPER_ADMIN + `@Profile({"local","dev","benchmark"})` — the `benchmark` profile was added so TB runs on the dedicated benchmark env) persists a `TERMINAL_BENCH` run and executes `engine/WorkerAgent` directly in REMOTE-tool mode: ExecuteSpec is the brain, the Harbor adapter in `~/tbench/es_harbor_agent.py` is the local hands inside the TB container. The keystone fix is in the manual worker dispatch path: `ToolRegistry.isRemoteRun(ctx)` + `dispatchRemote(ctx,name,args)` are consulted by `WorkerAgent.dispatchOne`; `RemoteToolDispatcher` timeout is configurable via `executespec.remote-tool.timeout-ms` (default 600s). DeepSeek first-20 baseline before terminal-mode tuning: 7/20 resolved (35%) for about $0.09; 80-floor terminal tuning reached 12/20 (60%) for about $0.40. The 100-floor + terminal-finalizer run also reached 12/20 (60%) for $0.400126 in 47m52s: `openssl-selfsigned-cert` and `sanitize-git-repo` flipped to pass, while `largest-eigenval` and `query-optimize` regressed on verifier/performance thresholds. Current comparable runs should use DeepSeek, the first-20 slice, an effective 120-iteration floor, terminal-artifact checkpoints at about iterations 16 and 32, a restricted terminal finalizer at iteration 64 even when shell calls keep counting as progress, a restricted wall-clock finalizer before failure, a forced terminal finalizer after the second text-only stop, a 1410s server-side wall clock below Harbor's 1500s agent timeout with a 90s buffer for an in-flight turn/finalizer, prompt policy that inspects `/tests`, treats exact visible assertions as byte-for-byte artifact contracts, implements detection-first no-op handling for clean transform inputs, cleans helper artifacts from deliverable directories, sweeps named error patterns, benchmarks against golden/reference thresholds, and the shared verified-pass guard that mechanically preserves a passing candidate.

### Project workbench (`project/` + companions)

Per-project workspace at `workspace/projects/<projectId>/` (codemaps/, architecture.md, summary.md, docs/, .git) owned by `ProjectWorkspaceService` with atomic writes + Redis-backed lock. Disk is ephemeral on Cloud Run / GCE; DB blobs are authoritative.

3-step architecture pipeline (`codeintel/`): per-repo `CodeMapGenerator` (idempotent by SHA) → `RepoArchitectureGenerator` (per-repo HLD/LLD, PLANNING tier) → `ArchitectureDocGenerator` (cross-repo synthesis, PLANNING tier). The codemap carries a versioned deterministic Spring HTTP-surface block bound to the exact source revision, including canonical method/path rows, controller locations, context-path configuration provenance and digest. That exact block is appended after both repository and combined LLM synthesis; provider prose cannot replace it. Unreadable, truncated, over-limit, or unsupported source is explicitly `UNAVAILABLE`, and old same-SHA artifacts without the block are regenerated instead of reused. `StalenessService` keeps the dashboard "STALE" badge honest via Redis-backed cache. `ProjectDocGeneratorService` assembles provider-neutral prompts through `PromptAssembler`, sorts repositories by canonical full name/id, fairly water-fills its 60k evidence budget across architecture and every linked repository at complete-line boundaries, and marks each omitted section explicitly so later repositories cannot be prefix-starved or query-order-dependent. Every repository section is loaded from the codemap whose source SHA exactly matches the architecture snapshot; a missing or extra SHA fails with `STALE_SOURCE`. The provider call runs outside a database transaction; same-source same-JVM requests share one in-flight result only when provider, resolved model, actor, and confirmed authority context also match. The short persistence phase takes the shared project publication lock, re-reads the latest architecture, and requires the exact version/fingerprint before cache recheck/version allocation. Every abnormal provider finish reason fails closed, and deterministic heading/body/fence validation plus secret and tool-protocol scanning runs before persistence; GET/download re-scan retained legacy documents before exposure. `POST /api/projects/{id}/repos/{repoId}/architecture` and `GET /api/projects/{id}/architecture/diff?from=&to=` (returns both authorized map bodies for the primary client-side LCS content diff plus GitHub source-file correlation).

Governed project RAG: content-derived, bounded Tika extraction + 500/50-token chunking feeds either `SEMANTIC` OpenAI `text-embedding-3-small`/1536 vectors or an honest PostgreSQL lexical corpus. V290 makes chunk vectors nullable, persists document provider/model/retrieval provenance, and refuses startup if the configured model/dimension drifts without a corpus migration. Embedding is a dedicated OpenAI capability independent of the project's code-generation provider, so Anthropic/Gemini/DeepSeek/OpenRouter/Fireworks projects may use it when current key-source and org policy authorize OpenAI; `OWN_KEYS` never falls through to platform OpenAI, and unavailable capability remains `LEXICAL_READY`. Every physical embedding batch is re-authorized immediately before provider dispatch, attributed through V283, and platform-key successes participate in managed-AI accounting; this narrows multi-batch overspend but is not a durable reservation. Interactive Project Chat passes the authenticated actor into retrieval so query-embedding credential selection and usage attribution do not fall back to the project creator. The OpenAI embedding transport has explicit 5s connect/60s read bounds (`project.rag.embedding.openai.*-timeout-millis`). `ProjectDocumentService` + `/api/projects/{id}/documents` handle admitted upload + leased/restart-safe indexing (`UPLOADED → EXTRACTING → EMBEDDING|INDEXING → READY|LEXICAL_READY|RETRY_PENDING|FAILED`); document deletion keeps the audit/recovery tombstone but transactionally purges the retained raw blob before derived chunks/workspace copies are removed. Generated-doc LLM contexts use compact `project-doc:<uuid>` correlation ids so the bounded database field cannot overflow. `CodeIndexingService` reports `FULL_ELIGIBLE_SOURCE_COVERAGE` when every eligible source file is indexed; discovered/eligible/skipped counts remain separate so excluded binary, oversized, sensitive or secret-bearing files are not mislabeled as covered. `RetrievalService` is wired into planner, worker and chat with lexical fallback; semantic chunks above the configurable cosine-distance ceiling (`project.rag.embedding.max-cosine-distance`, default 0.55) are rejected before prompt assembly and retrieval falls back honestly to lexical search. Tika parser dependencies are deliberately narrowed to the supported document/code/text/PDF/Microsoft upload surface; add a parser regression test before enabling another file family.

Project memory (`memory/`): every completed project-scoped run appends a digest to `project_run_ledger` via `RunLedgerAgent`; `MemoryCompactionService` rewrites a versioned `project_memory_snapshot` every 10 runs. Its provider call runs outside a database transaction; only the snapshot plus ledger markers use a short atomic transaction. Cross-JVM generation fencing remains separate. `ProjectMemoryProvider.buildPlannerContext(projectId)` feeds `{{PROJECT_MEMORY}}` into the planner prompt.

Codemap generation fails closed when the source inventory cannot be read. An SCM outage, credential failure or rate limit must not be persisted as a valid empty codemap/architecture input.

Project endpoint questions have a deterministic `list_public_http_surface` read tool. It uses the
canonical linked-repository materialization and `SpringHttpSurfaceExtractor` to combine servlet
context, class, and method mappings into exact method/path/source rows instead of asking the model
to infer a public route from controller annotations alone.

Project chat (`memory/ProjectChatService`): compaction (summarise older N messages, keep last 6 verbatim) + reset + per-bubble copy + branch override on launch. Async-dispatched apply-suggestion uses a dedicated `applyExecutor` (4 threads) so the worker loop does not burn a Tomcat handler thread. `memory/chattools/ProjectChatTools` retains the complete implementation library, but every chat model receives only pure `PROJECT_READ` callbacks: repo/run/PR/memory/architecture reads, generated-document reads, tech-debt summaries, exact repository search, and `read_repo_file_range`. The latter expands a lexical `file:line` hit into at most 200 inclusive source lines through `ProjectRepositorySourceReadTools`, using the canonical revision-pinned materializer and rejecting unlinked repositories, traversal/absolute paths, symlinks, `.git`, oversized files/results, and credential-like paths/content. Semantic code/document search is classified as paid analysis and is not model-bound until it has its own scoped protocol. Explicit architecture generation, document generation, and Code Review use the separate V292 confirmation endpoints below. Architecture, memory, and admitted reference context are auto-injected; repository SYMBOL matching remains deterministic Java/TypeScript/Python heuristics rather than a language-aware call graph. Every paid project/spec/agent chat turn resolves an immutable `ProjectChatPolicy` with actor/tenant/role/capability/provider/model/key-source/budget/confirmation/trust/audit evidence plus an exact V282 reference; its guard revalidates before every model round and tool invocation, and `PROJECT_VIEWER` cannot spend.
Exact project-chat repository search uses `CodeIndexWorkspaceIdentity` plus
`CodeIndexWorkspaceMaterializer`: project/repository/full-revision identities are single-flight,
cloned into a temporary directory, revision-pinned, and atomically published. Poisoned partial
clones are replaced, source failures remain structured, and the clone-only path creates no
pipeline-run evidence. Project-scoped Web Agent turns derive provider-specific GitHub or Bitbucket
credentials from the authenticated server-side principal and never cross-send them.

**V292 Project Chat confirmations:** the model loop remains read-only. Web and CLI/OpenTUI expose explicit human actions for `REGENERATE_ARCHITECTURE`, `GENERATE_PROJECT_DOC`, and `RUN_CODE_REVIEW`; these are not model-generated proposals. `GET /api/projects/{id}/chat/info` supplies the org-override-aware model for each action. Issue/consume binds actor, host org, project, action, exact provider/model, canonical target and hard budget into a hashed, expiring one-shot grant; the plaintext bearer is returned once and held in client memory only. Confirmed execution pins that exact route with no adaptive switch or failover, including async Code Review, attributes usage to the initiating actor while resolving the host project's credential subject, and returns 503 if the route cannot be honored. Shared action caps are architecture **1,000,000 tokens / $10 / 64 provider calls**, generated doc **200,000 / $3 / 2**, and code review **300,000 / $5 / 2**. V292 also makes `activity_audit` append-only in PostgreSQL and serializes hash-chain appends with a transaction advisory lock.

Run chat (`chat/ChatService`, the live post-run copilot — `agent/ConversationAgent` is the legacy path): terminal-runs-only Q&A over the run workspace + validation-report block, history on `pipeline_run.chat_history_json` (cap 50). `chat/ChatHistoryCodec` owns transcript JSON and the bounded provider window (newest 16 prior messages within 32k characters), while `RunChatEvidence` owns bounded server-side lifecycle grounding (specific failure reason, effective planner route, aggregate/phase tokens, cost, and plan-artifact presence); this prevents the umbrella `PLANNER_UNAVAILABLE` status or an empty pre-execution workspace from being misreported as provider downtime or proof that no outline was drafted. `ChatService` owns admission, evidence assembly, routing, and the tool loop. Routes through `LlmRouter` (LIGHTWEIGHT tier) with a manual tool loop (cap `MAX_TOOL_ROUNDS=4`) and a **read-only** tool set `chat/RunChatTools` (`list_run_files` / `read_run_file` capped 8k chars / `search_run_files` regex content search, reusing the worker `tool/SearchFilesTool`) so the copilot grounds suggestions in the actual generated code — strictly advisory, NO write/shell/push/re-run tool; code changes flow through "Iterate as new run" / "Apply to Run" which keep their own gates (RL-2026-05-047). Direct and forced-synthesis responses inspect provider finish reasons; output-limit prefixes are labeled `complete=false` / `PROVIDER_OUTPUT_LIMIT` with an actionable retry notice rather than being presented as complete. Phase-6 F12 applies `ContextSanitizer` to both the submitted message and the model reply before persistence/return, so recognized credential patterns cannot survive in chat history or leak through the response.

Multi-repo PR creation: `ScmPushService.pushMultiRepo` handles GitHub per-subdir push + per-repo PR + cross-link via `GitHubRepoService.updatePullRequestBody`. Publication routing is source-authoritative: an explicit/durable GitHub route cannot be overridden by the presence of Bitbucket credentials; ambiguous dual-credential legacy runs fail closed, and selected Bitbucket project sources are rejected until their provider-neutral run/PR implementation exists. Apply-suggestion publication through `pushApplyChanges` is subject to the same canonical `PublicationAdmissionService` freshness/build/test/workflow/exact-route gate as normal push and deployment, followed by a persisted `BrownfieldHttpContractGuard.assertCompatible` comparison before credential lookup or any existing-branch Git call; blocked apply results carry an additive stable code, blocking gate, eligibility state, retry policy, and remediation. **RR-1668 (2026-07-21) — feature-branch pushes are no longer unconditionally forced.** `GitTool.pushToBranch` / `commitAndPushSubdir` / `pushToBranchBitbucket` (RR-900 only covered the default-branch case) replaced `--force` with an EXPLICIT `--force-with-lease=<branch>:<expected-remote-sha>` when `ScmPushService` can prove the run cloned exactly that branch — the expected SHA is the branch's `run_repo_snapshot.commit_sha` captured at clone time, so a commit a human pushed after the clone aborts the push (`stale info`) instead of being overwritten. The explicit `:<sha>` form is mandatory: a bare `--force-with-lease` compares against the local remote-tracking ref, which an intervening fetch poisons. When the run did NOT clone the push branch (a fresh `executespec/<runId>` branch, or a re-run's `branchNamingPattern` output) there is no snapshot and the push is a plain, non-forced push that creates the branch when absent and rejects a divergent remote (non-fast-forward) rather than clobbering it. A re-run over a branch a human advanced now aborts by design. Fails safe: a snapshot-lookup error falls back to the plain non-forced push, never a blind force. PRs persisted to `pull_request_record` (V169 added `project_id` + `kind`; V170 added `ci_auto_fix_attempts`; V186 added `ci_status` columns). Webhook at `/webhooks/github` flips status (HMAC SHA-256 verified). `PullRequestSnapshot` carries `headSha` so PR refresh polls CI check-runs alongside merge state.

Tech debt (`project/techdebt/`): rolls up `validation_report.issues_json` into category / severity / file hotspots / dimension trend. Surfaced on the project Overview tab. The "Fix" button per category dispatches a patch-mode run via `TechDebtFixService` (autofix Phase 2).

### Models, routing, cost

YAML is the **single source of truth** for every model identifier / context-window / max-output-tokens / pricing rate / display label / fallback chain / free-tier lock / agent→tier map / provider-default model. `ModelRegistry` reads `application.yml model-matrix.*`. `ModelContextWindowMap` was deleted; `ModelController.MODEL_LABELS` reads `display-label` from YAML; frontend `PricingTable` reads `chainTier` via new DTO fields on `PricingRow`.

**Per-org matrix override (F3, ENTERPRISE — V205):** an Enterprise org admin can pin a different model for any `(provider, tier)` cell via `PUT /api/org/me/model-matrix`; unset cells fall back to the YAML defaults. Stored sparsely as JSON on `Org.model_overrides_json`, resolved once per run in `RunOrchestrator` and bound to the pipeline thread via `RunModelOverrides` (ThreadLocal); `ModelResolver.lookupModel` (the chokepoint every tier-routed agent funnels through — worker via `SequentialExecutor.tierConfigFor`, planner, reviewer, …) returns the override when present. `OrgModelOverrideService` validates each cell against the registered matrix (OpenRouter via `OpenRouterModelMapper`'s allow-list) and is lenient on read.

Agent tier mapping (`application.yml`):

| Tier        | Anthropic                   | OpenAI         | Gemini                   | Agents                                                                                                                                                                                            |
| ----------- | --------------------------- | -------------- | ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PLANNING    | `claude-sonnet-5`           | `gpt-5.4`      | `gemini-3.5-flash`       | OutlinePlannerAgent, ReviewerAgent, RepoArchitectureGenerator, ArchitectureDocGenerator, ProjectChatCompiler, PrReviewAgent, CodeReviewAgent                                                      |
| WORKHORSE   | `claude-sonnet-5`           | `gpt-5.4`      | `gemini-3.5-flash`      | WorkerAgent, TaskExpanderAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ProjectDocGeneratorService, ValidationReportGenerator, PromptOptimiserAgent, PreviewRenderAgent             |
| LIGHTWEIGHT | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-3.5-flash-lite` | SmartClassifierAgent, CoherencePlannerAgent, ContextAgent, RunLedgerAgent, RunTitleSummarizer, ProjectChatCompactor, ProjectBriefService, ChatService, ConversationAgent, TestReportGenerator (SpecChatService is WORKHORSE per YAML) |

Default provider: Anthropic (changed from OpenAI in `1.0.0-M1`). **2026-07-02:** Anthropic PLANNING + WORKHORSE default is Sonnet 5 through 2026-08-31 while introductory pricing is active; Sonnet 4.6 remains selectable as the stable fallback. **2026-07-24 catalog refresh:** account-visible Fable 5, GPT-5.6 Sol/Terra/Luna, Gemini 3.6/3.5/3.1 routes, and current OpenRouter agentic models are selectable; stale `gpt-5.5-mini` was demoted to a legacy alias (→ `gpt-5.6-luna`) and four no-longer-listed Fireworks routes were removed. New frontier routes are not tier defaults pending ExecuteSpec quality evaluation; stepped-price models use conservative upper-tier rates so managed-AI spend remains metered without undercharging. Per-provider implicit-cache enabled — Gemini Flash threshold 1024 tokens, Pro 4096; Anthropic explicit cache_control blocks. `RunCostTracker` records `cache_read_input_tokens` on provider calls and trusts `provider_reported_cost_usd` only for OpenRouter, where gateway/upstream cache pricing can differ from the static matrix.

`ReviewerAgent` is PLANNING for every provider (`application.yml` agent-tier-map); no Gemini special case exists — Gemini's PLANNING and WORKHORSE cells simply hold the same model today. **2026-07-24:** the native Gemini registry added GA `gemini-3.6-flash`, GA `gemini-3.5-flash-lite`, and preview `gemini-3.1-pro-preview`; removed the shut-down 3 Pro Preview entry; and maps retired 3 Flash Preview aliases to 3.6 Flash. LIGHTWEIGHT moved to 3.5 Flash-Lite. PLANNING/WORKHORSE deliberately remain on the benchmarked 3.5 Flash until 3.6 completes ExecuteSpec's cross-provider quality eval; 3.6 is selectable in the meantime.

Provider circuit breaker via `ProviderHealthTracker` (Redis-backed, 10 failures → open 180s) on the legacy router; the V2 `LlmRouter` is direct-dispatch with per-provider failover via the YAML `failover-order:` chain. Runtime uses the provider-aware registry path for every primary, preserves the declared order, and resolves targets at the equivalent planning/workhorse/lightweight tier; a provider without an explicit entry has no cross-provider fallback. Since RL-2026-05-029 it also does a **bounded same-provider retry** (up to 4 attempts, exponential backoff 2s/4s/8s) on transient errors (5xx / connection reset / I/O timeout / 429 via `isTransientError`) before giving up — same provider, no mid-run model switch, so per-provider numbers stay honest and a customer run survives a transient upstream blip (permanent 4xx still fail fast). For DeepSeek, the chain is native primary → OpenRouter → Fireworks; Fireworks is not an automatic first choice. Exact-lock and strict-provider requests still prohibit cross-provider failover.

### Observability

V273 executable-verification columns are populated during report generation and concrete-test
rescoring, then reconstructed as deterministic ledger evidence. Code-task goal DONE intersects that
task's successful mutating paths with the admitted baseline-to-final product diff; raw write calls
cannot prove delivery.

Micrometer + OTel → Prometheus + Tempo + Grafana (local) / Cloud Trace + Cloud Monitoring (GCP). `executespec.v2.*` custom metrics for `run.{outcome,latency}`, `planner.{latency,tasks}`, `task.{status,latency,smoketest}`, `worker.{iterations,outcome,toolcall}`, `llm.latency`, `reviewer.verdict`, `context.{save,save.bytes}`. Dump endpoint `/api/v2/test/metrics`. Actuator at `/actuator/prometheus`. **W0 telemetry (RL-2026-06-011 / RL-2026-06-043 / RL-2026-06-044, vix-leverage):** `RunCostTracker` tracks **reasoning tokens** per run + per task (`Snapshot.reasoningTokens` — Gemini `thinking_tokens` / OpenAI-DeepSeek `reasoning_tokens`). `RunEvent` now carries an additive event envelope (`schemaVersion=1`, `data={...}`) while preserving the legacy string `payload`; `TOKEN_USAGE` includes typed cost/cache/reasoning fields, `TOOL_CALL` includes typed remote-tool call fields, `TASK_WAVE_PLAN` exposes the configured bounded safe-parallelism plan in advisory mode, and `GOAL_DECISION` / `CONTINUATION_DECISION` expose task-boundary goal state plus next-action advice without log scraping. `GOAL_DECISION` also carries `recommendedNextStep`, structured task wall-clock, clear environment-block, unsafe-continuation, and low-completion-confidence signatures, and RunCostTracker cost/token/cache/reasoning evidence through SSE, durable task JSON, the run-detail API, the IDE Steps tab, and run-scoped agent-session action events; positive `execution.continuation.max-total-cost-usd`, `max-total-tokens`, or `max-task-wall-clock-ms` values can stop as waste guard before proof of completion, while `max-no-progress-iterations`, `max-false-done-claims`, `max-repeated-failures`, and `max-edit-churn` set the no-diff, false-completion, repeated tool/command/validation failure, and repeated same-file mutation thresholds recorded in goal evidence. Continuation decisions include the applied `execution.continuation.*` limits in live SSE data, durable task JSON, and active run-scoped `agent_action_event` mirrors; the executor persists the durable task JSON before emitting live decision events so `RUN_DETAIL` refreshes read the new proof/proposal; they also record the semantic or complexity-fallback task category, applied category-specific quality threshold, global fallback threshold, resolved category threshold map, and approvalRequiredReason when STOP_BLOCKED needs approval because benchmark confidence or spend confirmation is missing. Automatic selected-candidate reruns emit typed `CONTINUATION_ACTION` events with `status=EXECUTED`, approved blocked continuations emit the same event with `status=APPROVED`, limit-blocked recommendations emit typed `CONTINUATION_ACTION_SKIPPED` events, and all action rows are appended to `TaskSummary.continuationActions` for reloadable task evidence. Validation reports expose `verificationLedger[]` and conservative `runGoalDecision` proof on `/api/runs/{id}/report`, the run-result Evidence tab renders both, and saved reports publish `RUN_VALIDATION_EVIDENCE` with the same proof into active run-scoped agent sessions for reloadable web/CLI/VS Code final evidence summaries. `RunStepUsageRecorder` writes every physical provider attempt — successful calls, same-provider retries, failed calls, and failover targets — to the V283-expanded `run_step_usage` ledger with exact role/step, durable tenant context when supplied, actual key source, status, bounded error class, latency, cache/reasoning tokens, and cost. Prompt/request/response/tool payloads are excluded. `GET /api/admin/analytics/llm-usage-audit` is an own-org ORG_ADMIN/SUPER_ADMIN-only, IANA-timezone-aware calendar-window report with bounded pagination and schema-v4 user/human-role/provider/model/agent-role/feature/project/run/session roll-ups; `/llm-usage-audit.csv` emits at most 10,000 spreadsheet-safe rows with explicit truncation headers. Provider-unreported failed-attempt cost is `null` and aggregate known cost is labeled a lower bound. Run/managed-AI reconciliation uses a documented absolute/relative tolerance; provider-invoice reconciliation remains explicitly unavailable until upstream invoice/request identifiers exist. Recorder persistence/attribution failures emit dedicated `executespec.v2.llm.usage.*` metrics, and Prometheus raises a labeled critical rule on canonical usage-write failure; Alertmanager/pager delivery still requires environment qualification. `/api/runs/{id}/cost` also exposes `schemaVersion`, `source`, `cacheReadTokens`, `cacheWriteTokens`, `reasoningTokens`, and `calls` for live V2 runs. Typed `tool_result` / `step` events remain a W0 follow-up phase.

`SseBroadcaster` uses bounded fixed lock striping to serialize each run's Redis sequence/replay
append, local fan-out, Redis publication, subscription installation, and completion boundary;
unconstrained Spring event listeners run after that critical section. A bounded 24-hour terminal
marker makes post-completion subscribers replay then close instead of becoming live orphans.
Concurrent remote Redis callbacks enter a sequence-aware 64-frame reorder buffer; contiguous frames
drain in id order, typed `COMPLETE` closes peers only after delivery, and a one-second logical gap
(checked by bounded maintenance) retires sockets to durable replay. Application socket writes never run on a pipeline or
Redis-listener thread. Each emitter owns one ordered bootstrap
(connected comment + bounded Redis replay) followed by a 64-frame live FIFO and one virtual-thread
writer. Live frames accepted while replay loads remain behind replay and duplicate sequence ids are
suppressed. Offer/seal share one state lock, so completion cannot overtake an accepted frame. A
15-second heartbeat plus 30-second send-stall watchdog retires quiet half-open subscribers; a
retired writer remains counted against the atomic 15-per-run/500-global admission bounds until its
task actually exits, and response-close attempts are independently permit-bounded at 32. Queue or
stall retirement increments `executespec.sse.emitters.backpressure.evictions`; durable
`Last-Event-ID` replay remains the recovery path.

**2026-07-26 cost evidence correction:** `/api/runs/{id}/cost` exposes distinct
`physicalAttempts` / `successfulCalls` / `failedAttempts` counts; the backward-compatible `calls`
field remains successful-response count. A successful physical attempt with unprojectable cost
uses the negative sentinel only in the raw row, is excluded from known-cost sums, and closes a
dollar-capped durable context before another dispatch.

**V293 usage correction:** `llm_usage_attempt_intent` is written fail-closed before transport and
`run_step_usage` is its append-only terminal fact. Reports start from the intent so
`MISSING_TERMINAL` remains visible, expose correlation/model-tier and ordered attempt causality,
and reconcile managed credits only through the exact `managed_ai_usage_event.usage_event_id`
foreign key. Terminal persistence failure cannot retry a successful provider response; a
secret-free local recovery spool replays by physical-attempt ID. Gemini cache-mint, inline fallback
and internal 429 operations are individually accounted, as are Anthropic forced-tool transport
failure, paid HTTP-200/no-tool fallback and temperature-deprecation retry. This is deterministic
coverage of the known transport paths, not provider-invoice reconciliation or proof of unknown
future SDK-internal requests.

Run-scoped agent sessions also persist advisory task-wave plans as `RUN_TASK_WAVE_PLAN` action events so web/CLI/VS Code clients can reload bounded safe-parallelism evidence after the run SSE stream is gone.

`execution.continuation.parallel-read-tools-enabled` defaults to true and gates the worker's automatic read-only tool-batch parallelism. Pure same-turn read batches (`readFile` / `searchFiles` / `listFiles` / `gitStatus` / `gitDiff`) emit server and legacy `LOG` SSE start/done lines with call labels and per-call `ok`/`err` results; mutating, remote, partial, or timed-out batches fall back to sequential dispatch.

`execution.continuation.parallel-provider-evaluation-enabled` defaults to true and gates bounded parallel model/provider candidate evaluation inside `ModelProviderCandidateService`; it uses `execution.continuation.max-parallel-tasks` as the cap, logs start labels and done result labels, preserves deterministic model-order output, and records whether each candidate was evaluated in the bounded parallel batch or sequentially in the candidate reasons.

`ModelProviderCandidateService` also reads the latest DONE `benchmark_run` rows fail-soft and converts each provider/model resolved rate (`resolved_tasks / total_tasks`) into a benchmark quality prior only when the run reaches `execution.continuation.benchmark-min-sample-tasks` (default 20) and the suite is compatible with the task category. Terminal-Bench backs `TERMINAL_TASK` / `BENCHMARK_PATCH`; SWE-bench backs bug/test/project patch categories; ProgramBench backs bug/test/benchmark patch categories. Smaller or incompatible benchmark rows remain visible in candidate reasons but do not drive quality, so unbenchmarked, under-benchmarked, or unrelated-suite models are not treated as fully known. When no benchmark/local/validation score exists, candidate quality falls back to YAML metadata (`quality-class` first, then `chain-tier` / `cost-class`), so unbenchmarked automatic continuation still has a deterministic quality estimate. Gateway coding rows carry explicit `quality-class` values to avoid accidental under-ranking from generic `UTILITY` / `CAPABLE` tier labels. This informs step-up / step-down / change-provider advice without letting benchmark data override the quality-first ranking policy or mutate a run by itself. `ModelProviderScore.benchmarkQualityPriorAvailable` is true only when a candidate has such usable benchmark evidence.

`InitialRoutingService` / `InitialRoutingDecision` is the bounded first-attempt routing seam. It reuses the candidate collector and ranking policy, rejects explicitly unavailable candidates, and keeps the caller's current provider/model unless the selected route carries observed quality evidence (persisted task outcomes, local success, or a usable benchmark prior). It is side-effect-free and does not yet alter `SequentialExecutor` defaults; callers can persist/emit its considered and rejected candidates plus reasons when enabling an initial-routing rollout.

Cross-model/provider continuation reuses the workspace but starts at iteration 1 with only the bounded durable handoff dossier, never the failed model's full conversation. BUILD attempts enforce `execution.continuation.max-edit-churn` live even when progress-v2 is off, and proven passing maintained build/test evidence resets that per-path counter. A replacement attempt disables legacy zero-write best-guess salvage: zero writes by the replacement model is not an empty run patch because the primary attempt's workspace mutations are already present.

`execution.continuation.acting.enabled` defaults true, so selected-candidate `STEP_UP_MODEL`, `STEP_DOWN_MODEL`, and `CHANGE_PROVIDER` decisions rerun the same task once automatically inside configured provider-switch/model-step ceilings. On primary build-smoke failure, the executor now asks continuation policy before spending the old same-provider build-error retry; if a selected different provider/model is available within limits, that stronger rerun happens immediately. If the selected upgrade is blocked only by benchmark-confidence or confirmation policy, the task is marked `PARTIAL`, the `STOP_BLOCKED` proposal is persisted for approval, and the weak same-provider retry is skipped. Otherwise the legacy same-provider retry continues. The executor passes already-consumed provider-switch / model-step counters, provider-breakdown evidence, and `AvailableProviderResolver` key/org-policy availability into the advisor before each decision; post-review continuation does the same from `RunOrchestrator`. Candidate scoring keeps unavailable providers visible but forces them below threshold, so automatic upgrades cannot select a provider with no usable key or disallowed org policy. Validated ENTERPRISE per-run provider/model pins set `routePinned=true`; automatic step-up, step-down, or provider-change proposals then stop as `STOP_BLOCKED` with `blockedAutomaticContinuationReason=ROUTE_PINNED` instead of silently overriding the user's pinned route. Starting on a cheap/light model while still allowing automatic step-up must be done through YAML/org matrix defaults or per-run `RunRequest.lowComplexityWorkerStartTier=LIGHTWEIGHT`, not a per-run `modelName` pin. Web Compose, spec-chat `submit_run`, and agent-session `create_cloud_run` confirmation all propagate that same non-pinning LOW-start hint. The typed/persisted continuation policy includes those counters plus `providerBehaviorPoor`, `lowComplexityWorkerStartTierConfigured`, and `lowComplexityWorkerStartTierEffective`, so later decisions know when escalation budget is exhausted, when LOW tasks were configured to start cheap, and when a provider switch is being considered because the current route broke down; that signal now covers hard worker `ERROR`s, provider/router/timeout failure text, and zero-write terminal stalls (`STOPPED_WITHOUT_COMPLETE`, `ITERATION_LIMIT_EXCEEDED`, `COMPLETED_BY_STAGNATION`, `WALL_CLOCK_EXCEEDED`). Advisor-backed policy data also carries compact ranking evidence (`rankingPolicy`, `rankingQualityTieBand`, `rankingReasons`, top eligible candidates, and top rejected-below-threshold candidates) so the quality > cost > latency decision can be audited after reload; quality wins outside `execution.continuation.quality-tie-band` (default 1.0), while cost then latency break near-quality ties. Approved blocked-continuation retries seed the same counters from the stored proposed action before the retry runs, append an `APPROVED` row to `agent_task.continuation_actions_json` only after the retry is accepted, and therefore do not reset the escalation budget or lose the approval trail. `execution.continuation.require-benchmark-prior-for-automatic-continuation` is **shipped `true` in application.yml** (the code fallback when the key is absent is false; pinned by `ContinuationConfigDefaultsTest`), so automatic selection prefers benchmark-backed candidates by default. When the server config or per-run `RunRequest.requireBenchmarkPriorForAutomaticContinuation=true` opts into stricter benchmark confidence, automatic selection first prefers benchmark-backed candidates; if no benchmark-backed candidate fits, the otherwise-selected candidate without a usable benchmark quality prior is blocked as `STOP_BLOCKED` and kept visible for approval. If server config `require-confirmation-before-expensive-continuation=true` or per-run `RunRequest.requireConfirmationBeforeExpensiveContinuation=true`, the policy also blocks as `STOP_BLOCKED` but keeps the proposed selected candidate visible for approval. The web Compose advanced options expose that per-run flag as "Confirm model upgrades" and send it on `POST /api/runs`. Web Compose also exposes `RunRequest.requireBenchmarkPriorForAutomaticContinuation` as "Require benchmark evidence"; unchecked omits the per-run override so the server default remains in control, and checked sends an explicit `true` to opt into benchmark-required approval gating. When an automatic `STEP_UP_MODEL` or `CHANGE_PROVIDER` rerun succeeds or completes with warning, the final BUILD-mode reviewer uses that action's provider/model; failed actions, normal task tiering, and `STEP_DOWN_MODEL` do not promote the reviewer.

The run-detail API fails closed when presenting task completion. A persisted `agent_task.status=COMPLETE` remains unchanged for crash-resume bookkeeping, but `TaskSummary.status` is exposed as `PARTIAL` whenever durable `goalDoneness.state` is present and is not `DONE`; the goal-decision reason becomes the fallback `failureReason`. This prevents the Steps UI from claiming `COMPLETE` beside explicit unproven-goal evidence while keeping legacy rows without goal proof read-compatible.

`execution.continuation.max-stale-explanations` defaults to 2 and feeds `GOAL_DECISION` evidence as `staleExplanationCount=current/max`, covering repeated assistant explanations that arrive without new file-write or validation proof.
Approved blocked-continuation retries set `ExecutionRequest.userConfirmedExpensiveContinuation=true` plus the approved action/provider/model scope for the resumed executor pass. The confirmation bypass is exact-match only: it applies to the stored `STEP_UP_MODEL` / `CHANGE_PROVIDER` / `STEP_DOWN_MODEL` proposal and selected provider/model, not to a later different candidate in the same retry. V364 first claims the exact persisted proposal bytes with a durable approval id; CLAIMED duplicates return 409 while QUEUED or COMPLETED duplicates replay that receipt and never queue a second retry. Every server-side approved retry, including task-boundary, post-review, and post-validation sources, runs the task with the selected provider/model inside the reversible correction checkpoint and then reruns a read-only reviewer plus deterministic validation before terminal status. Approved retries snapshot `RunCostTracker` around the worker retry, attribute the delta to the target task via `RunTokenAttributor.attributeWorkerAdditionalTask`, and add reviewer usage from the physical attempt ledger, so upgrade spend is visible in run totals and phase/task rows. The IDE run-detail Steps tab keeps customer steps focused on execution evidence: provider/model rankings appear only for actionable route changes (`STEP_UP_MODEL`, `STEP_DOWN_MODEL`, `CHANGE_PROVIDER`) or blocked approvals, and remain SUPER_ADMIN/operator-only. Same-route, terminal-failure, waste-stop, and completed steps retain concise proof/next-step text while the full `TaskSummary.continuationDecision.policy` remains persisted for operator audit. Source labels still distinguish task-boundary, reviewer/build, and final-validation-triggered actions.
Soft-completed edits also feed `completionConfidenceLow=true` when the worker changed files but did not cleanly prove completion; those task decisions remain progressing but set `anotherModelProviderMightHelp=true` so the benchmark/confirmation-guarded continuation advisor can step up.
Final validation-ledger proof treats reviewer `WARN` / `FAIL` rows as reviewer low-quality failure with `anotherModelProviderMightHelp=true`, so unresolved review becomes an explicit escalation signal. Hard final-proof failure is reserved for pipeline/build/tests/reviewer/code-delivery evidence; soft report issues and optional LLM/internal assessment rows (`report-issues`, spec adherence, trust cap, test-coverage judgment) make the proof `UNCERTAIN` with risks instead of `STOP_FAILED`.
`execution.continuation.post-review.enabled` defaults true and lets `RunOrchestrator` act after BUILD-mode reviewer low-quality evidence (`UNRESOLVED_ISSUES`, `REVIEW_FAILED`, reviewer exception, or `finalBuildPassed=false` after the reviewer fix-build pass). It emits a post-review `CONTINUATION_DECISION` for the latest usable worker task, then automatically executes only `STEP_UP_MODEL` / `CHANGE_PROVIDER` decisions that survive the same benchmark-prior and confirmation guards as task-boundary continuation. The selected action resets that task to `PENDING`, reruns it through `SequentialExecutor.execute(..., ResumeRequest)`, seeds already-used provider-switch / model-step counters including the selected post-review action, persists the new task result, runs lint, re-runs the BUILD-mode reviewer on the updated workspace, and attributes the second worker/reviewer token deltas. The second worker pass writes only the target task row from its task snapshot instead of redistributing across synthetic skipped resume outcomes. The second reviewer pass uses additive phase attribution so `pipeline_run.reviewer_*_tokens` reflects first review + post-review review while run totals only receive the new delta once.
After the validation report is generated, `RunOrchestrator` also treats a low final report (`pipelineResult=FAILED/NEEDS_ATTENTION` or `verifiedScore < execution.continuation.post-review.required-quality-threshold`) as post-validation low-quality evidence. It emits a `POST_VALIDATION_REPORT` continuation decision and can run the same selected `STEP_UP_MODEL` / `CHANGE_PROVIDER` rerun + reviewer + regenerated validation report before final terminal status. If no automatic/approved upgrade runs, the terminal reason is stamped as `VALIDATION_REPORT_LOW_QUALITY` instead of letting a low-score run stay `COMPLETE`.
Reviewer exceptions are materialized as `ReviewerResult.Overall.REVIEW_FAILED`, so terminal status and validation evidence preserve `REVIEWER_FAILED` instead of falling through to vague no-review or final-build-failed reasons.

**Reviewer pass termination (2026-07-21, RR-272).** A timed-out reviewer pass used to return a
synthetic `ERROR` the instant `Future.cancel(true)` was called. `WorkerAgent` only observes
interruption at an iteration boundary, so an in-flight provider response or tool batch kept
running: the reviewer's own later stages (final build, fix-build pass, fix-tests pass) and then
validate / push / preview / cleanup could all measure or publish a workspace another thread was
still editing. `ReviewerAgent.runPassWithTimeout` now returns a `PassRun` carrying explicit
termination proof — after cancelling it re-interrupts and waits up to
`TERMINATION_RESERVE_SECONDS` (330s, one full in-flight provider read plus unwind) on a latch the
pass counts down in its own `finally`. If that latch trips the review proceeds as before. If it
does not, the review is ABANDONED at that stage: `REVIEW_FAILED` with
`ReviewerResult.workspaceQuiescent=false` and **no** build, formatter or test evidence attached,
because none of it can be attributed to a stable workspace generation. **Write authority is now
revocable (RR-272 / RR-1687, 2026-07-21).** `tool/ReviewerMutationScope` binds a shared `Lease`
alongside the thread-local policy; the reviewer control thread holds that lease and calls
`revoke()` the moment it cancels a pass (before the reserve wait begins). Every mutation check
(`validateWriteFile`/`validateEditFile`/`validateDelete`/`validateMove`/`validateShell`/
`validateBackgroundJob`) consults the lease FIRST, before the brownfield gate, so a
`writeFile`/`editFile` from an abandoned pass thread is REFUSED on BOTH the brownfield and
greenfield paths — the runaway thread loses authority, not merely the reviewer's willingness to
vouch for it. **RR-1686:** an unproven-terminated pass records its workspace root in a JVM-scoped
non-quiescent set, and `capturePreReviewBuildError` skips the pre-review build smoke for a
workspace in that set, so a concurrent review of the same workspace does not measure a tree an
abandoned thread may still hold. **Residual:** revocation refuses the tool call but cannot preempt
an in-flight provider/tool turn already past its dispatch check; and the non-quiescent set is
append-only (a workspace flagged once stays flagged for the JVM's lifetime).

Normal worker tool-call logs include `-> OK` / `-> ERR`; repeated-failure evidence groups result-bearing `shell`, `bash`, `buildProject`, and `runTests` failures by stable tool/argument signature, so `execution.continuation.max-repeated-failures` catches the same command or test failing repeatedly even when stderr changes between attempts.

When a terminal waste/failed goal decision still says another model/provider might help, `CONTINUATION_DECISION` now explains when no useful escalation remains within configured limits instead of hiding that stop behind the generic goal reason.

5 published SLOs in [`executespec-docs/backend/operations/slos.md`](../executespec-docs/backend/operations/slos.md): run terminal in 15min ≥95% / verified-score correctness ≥90% / cost estimate within 30% on ≥80% / webhook in 60s ≥99% / hot-path 5xx <0.5%.

### Operator-only Telegram bridge

`chat_id=6475810501` + bot `8489535450`. Push: heartbeat every 600s + on every status-count change. Pull: `getUpdates` polled every 120s, writes to `/tmp/tg-inbox.log`. Used as out-of-band channel for long-running matrix tracking; not a customer-facing integration.

## Code Conventions

- **Base package:** `com.navneet.executespec`
- **Layering:** controller → service → agent → tool → `LlmRouter`.
- **DTOs:** Java records where possible. Lombok `@Data` / `@Builder` for JPA entities.
- **DI:** Constructor injection via Lombok `@RequiredArgsConstructor`. **No** `@Autowired` on fields.
- **LLM prompts:** external files in `src/main/resources/prompts/*.md` plus active `.txt` prompts for legacy/non-tool agents. Assembled via `PromptAssembler.assemble(path, vars)` where applicable — resolves `{{include:fragment}}` markers from `prompts/fragments/` and `{{VAR}}` substitutions. Dead rollback archives were removed in the 2026-07-02 prompt-audit cleanup; rollback uses `PromptAuditEntry.previousContent` and dynamically-created backups for live prompt files.
- **Tool calling:** provider-native end-to-end. Strict JSON schemas on every entry in `tool/`. No XML `<tool_call>` parsing in V2.
- **Naming:** `*Agent.java` for agents, `*Service.java` for services, `*Controller.java` for REST, `*Spec.java` for security specifications.
- **DB migrations:** Flyway `V{n}__description.sql` in `src/main/resources/db/migration/` (current worktree head **V371**, next **V372**, **357 migration files**). Applied migrations are immutable: never rewrite an applied V-file. V360 persists brownfield HTTP-contract evidence, V361 binds validation evidence to an exact workspace generation, V362 persists deterministic workflow/build/test command evidence, V363 adds confirmation-fenced uncertain generated-document retry, V364 atomically fences exact continuation approvals, V365 adds bounded stale-receipt recovery, V366 persists exact score-zero causality, V367 binds report runtime-surface applicability, V368 force-disables legacy Apply-to-Run pending its transactional correction lifecycle, V369 persists crash-recoverable correction authority, V370 converts V369's `CHAR(64)` digest columns to `VARCHAR(64)` to match the `CorrectionAttempt` entity mapping after Hibernate schema-validation crash-looped dev, and V371 persists skipped-test counts separately from passes/totals. Any future consolidation must use a clean-install baseline migration plus forward migrations. Full chronology is in [`executespec-docs/backend/architecture/CODEMAP.md`](../executespec-docs/backend/architecture/CODEMAP.md).

Production Flyway is strict: `application-prod.yml` disables automatic baselining and out-of-order application. Recovery of an unmanaged database is an explicit operator procedure; do not enable those settings globally.

`arch/MigratedSchemaBootSmokeTest` is the untagged CI boot smoke added after the 2026-07-31 dev outage: real pgvector PostgreSQL container, every Flyway migration from V1, then a full application-context boot with `ddl-auto=validate`. It runs in the default `mvn test` gate (unlike the `-Pintegration` suite) so a migration/entity mismatch or an uninstantiable bean fails PR CI instead of crash-looping a deployment. It skips only when Docker is absent.

**V237 adds DeepSeek + Fireworks BYOK key lanes** (`org.deepseek_api_key`, `org.fireworks_api_key`, `app_user.deepseek_api_key`, `app_user.fireworks_api_key`) so direct DeepSeek and Fireworks participate in the same user/org/system key hierarchy as the other providers. **V238 adds benchmark token-tracking columns. V239 defaults on the evidence-safe worker quality gates. V240 creates the CLI/VS Code agent-session protocol tables** (`agent_session`, `agent_turn`, `agent_action_event`, `agent_usage_ledger`) so open-ended conversations stay separate from `pipeline_run` and run credits remain charged only through the existing plan-approval path. **V241 seeds `worker.local-hands.enabled`; V244 flips it default ON** so CLI local execution can be the default while browser/JWT sessions remain rejected for local mode. **V242 adds `app_user.preferred_ai_provider`** plus `/api/auth/me/provider-preference`; web, CLI, and VS Code write the same account preference and use it when a run/chat request omits an explicit provider. **V243 creates `run_step_usage`** for W0 per-call LLM usage; RL-2026-06-044 starts populating its role/step/retry attribution columns. **V245 creates `managed_ai_usage_period`, `managed_ai_usage_event`, and `managed_ai_state_notification`** for AI credits, raw-COGS caps, per-call managed-key debits, step-down/step-up routing state, and notification dedupe. **V246 adds `agent_session.run_id`** so run-scoped web agent sessions survive reloads without relying on a UI-only message prefix. **V247 adds `agent_action_event.action_id`** so planned/action/result events have a stable correlation id across web and terminal clients. **V248 force-sets FREE `tier_config.monthly_run_limit` and `billing_plan.run_limit` to 5** when stale rows still carry any other value, especially the `-1` unlimited sentinel. **V249 seeds `execution.goal-decision.enforcement.enabled` default OFF** so operators can opt in to executor task-boundary aborts after negative goal decisions. **V250 adds `agent_task.goal_doneness_json` and `agent_task.continuation_decision_json`** so run detail clients can show goal state, recommended next step, and next-action advice without scraping SSE logs. **V251 adds `pipeline_run.run_goal_doneness_json`** so final validation-ledger run proof survives reloads on run detail. **V252 adds `agent_task.continuation_actions_json`** so executed/skipped continuation actions survive task-decision overwrites and reloads. **V253 adds `managed_ai_usage_period.purchased_ai_credits`, refreshes active billing-plan/tier rows to Free/Pro/Pro+/BYOK/Team, and seeds AI-credit top-up packages.**

### Patterns: MUST follow

- LLM calls go through `LlmRouter` (native tools or `callText`). Never call providers directly.
- Run ownership fields (`createdByUserId`, `orgId`, `projectId`) and submit-time continuation policy markers stamped BEFORE async pipeline thread starts (race-condition fix).
- Gate-loss fallback: `approve()` → if gate missing → auto-fail with message.
- Context is planned by model window and reserved headroom; the worker keeps full history while compaction is explicitly policy-controlled.
- Token-cost control: `readFile` full reads stream from disk and cap at 60KB/2000 lines with **head-first** truncation at a line boundary (output is byte-exact from line 1 so `editFile` can match it; range reads stream and remain untruncated). The canonical range is `startLine`/`endLine` (1-indexed, inclusive); legacy `offset`/`limit` remains accepted. The standalone CLI local-hands runtime mirrors these caps, exact-source output, aliases, and bounded `searchFiles` modes/paging. Exact/regex repository search uses bounded ripgrep; structured symbol/relationship search keeps content-fresh citations and caps each workspace's decoded cache at 512 snapshots / 8 MiB characters. Lexical relationships link a target only when exactly one definition exists; multi-definition symbols remain explicitly unresolved rather than pointing to an arbitrary file. Full-inventory hashing remains a large-repo optimization target. RL-2026-05-062 replaced the old 5KB head+tail-with-a-hole that made `editFile` unable to match any region the model never saw. `shell` / `buildProject` 6k chars inline (`ToolOutput.budget` — full log spilled to disk + a `readFile` path). NOTE: the V2 worker keeps its FULL conversation history (no sliding window); `ToolOutputCompactor` + `WorkerHistoryCompactor` (default OFF, `worker.tool-compactor.enabled` / `worker.history-compaction.enabled`) are the context-compaction levers. The "first 2 + last 18" sliding window applies only to legacy `FallbackLlmRouter` non-tool agents.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21` before any Maven command on macOS dev.
- Payment-webhook signatures verified before processing (Razorpay HMAC, Stripe `Stripe-Signature`).
- Feature flags via `FeatureFlagService.isEnabled()` — never hardcode toggles. Admin updates write
  through the local cache immediately and publish Redis invalidation only after the database
  transaction commits; publishing inside the transaction can let a subscriber reload stale state
  and undo the update.
- Structured logging with traceId / spanId via Micrometer context propagation (MDC auto-populated).
- Async work: wrap `Thread.ofVirtual().start()` runnables in `ContextSnapshotFactory.captureAll().wrap(runnable)` so traceId + RequestAttributes survive thread boundaries.

### Patterns: MUST NOT use

- Field injection (`@Autowired` on fields).
- Raw String for LLM prompts (use prompt files).
- Direct HTTP calls to LLM providers.
- Swallowed exceptions (log + rethrow or handle explicitly).
- Setting run ownership fields AFTER `submitRun()` returns.

## Documentation hygiene (MANDATORY before every commit)

Standing rule from 2026-04-25: every commit must keep the project's documentation surface in lockstep with the code. **Before staging files for any commit, walk this checklist:**

1. **Root CLAUDE.md** if the change affects architecture, API contracts, file locations, agent tiers, status of in-flight work.
2. **Per-package `CLAUDE.md`** (e.g. `agent/CLAUDE.md`, `state/CLAUDE.md`) when entities, repository methods, agents, or migrations change in that package.
3. **`executespec-docs/backend/architecture/CODEMAP.md`** when new top-level packages, entities, agents, or DB migrations are added.
4. **`executespec-docs/backend/reference/document_map.md`** when a doc is added, removed, or moved in the docs repo.
5. **`executespec-docs/backend/reason-log/2026-06.md`** when an in-flight item changes status or a new decision is recorded.
6. **`README.md`** if a user-visible feature, environment variable, or build step changed. **`executespec-docs/backend/design/*`** when tokens / theming / a11y commitments change.
7. **Migration trail** — every new `Vxxx__*.sql` must be cross-linked from the touching package's `CLAUDE.md` + the entity row.

For trivial typo fixes you may write `skip doc-refresh: trivial typo` in the commit message. Otherwise doc-refresh artefacts go in the same commit as the code, not a separate follow-up.

## Build & test

```bash
# IMPORTANT: macOS dev sets JAVA_HOME explicitly
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# Backend compile
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q

# Backend full verify (includes spotless format gate)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q

# Frontend
npm --prefix frontend run build
npm --prefix frontend run format:check
npm --prefix frontend run lint

# Run locally
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run

# Guarded live-provider fixture (reserve exact provider/model/run scope first)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -Plive-llm-test \
  -Dexecutespec.live-llm.reservation-id="$RESERVATION_ID" \
  spring-boot:run -Dspring-boot.run.profiles=local

# Local observability stack
cd observability && docker compose up -d
# Grafana http://localhost:3001 (admin/admin), Prometheus :9090, Tempo :3200

# V2 eval matrix
scripts/v2-eval.sh   # LOW/MEDIUM/HIGH × anthropic/openai/gemini, outputs scripts/v2-eval-results/
```

## Required environment variables

```bash
# Required — LLM
# The Spring AI provider blocks in application.yml resolve these environment variables at startup.
# Anthropic/OpenAI role beans are optional at boot: if a key is absent, the corresponding role
# remains inactive and the V2 router can still run a configured provider such as native DeepSeek.
OPENAI_API_KEY=
ANTHROPIC_API_KEY=

# Optional — LLM
GOOGLE_API_KEY=                  # Gemini API key; retained for operator regression, buyer-facing Gemini is temporarily darked
OPENROUTER_API_KEY=              # Enterprise gateway — DeepSeek / Qwen / Kimi / MiMo / GLM via openrouter.ai. Empty = provider registered but every call short-circuits with "OpenRouter api-key not configured."
DEEPSEEK_API_KEY=                # Native DeepSeek (api.deepseek.com). Empty = provider disabled.
FIREWORKS_API_KEY=               # Fireworks AI OpenAI-wire route for Chinese open models. Empty = provider disabled; live slug/latency smoke requires a real key.
DEEPSEEK_READ_TIMEOUT_SECONDS=   # Optional native DeepSeek read window; defaults to 300s. Other OpenAI-wire providers stay at 120s.

# Optional — Integrations
# (GITHUB_PAT removed 2026-05-23 — there is no system GitHub PAT; every GitHub op uses the caller's own PAT)
SLACK_CLIENT_ID= / SLACK_CLIENT_SECRET= / SLACK_SIGNING_SECRET=
DASHBOARD_URL=                   # notification / integration link base
APP_SURFACE_RETURN_URL=          # browser OAuth callback, e.g. http://localhost:5173/auth/callback

# Optional — OAuth2 login
GOOGLE_OAUTH_CLIENT_ID= / GOOGLE_OAUTH_CLIENT_SECRET=
GITHUB_OAUTH_CLIENT_ID= / GITHUB_OAUTH_CLIENT_SECRET=
CORS_ALLOWED_ORIGINS=            # comma-separated browser origins; local default includes localhost/127.0.0.1 ports 5173 and 5174 for concurrent Vite worktrees

# Optional — Billing
RAZORPAY_KEY_ID= / RAZORPAY_KEY_SECRET=
STRIPE_SECRET_KEY= / STRIPE_WEBHOOK_SECRET= / STRIPE_PUBLISHABLE_KEY=
STRIPE_TEAM_MONTHLY_PRICE_ID= / STRIPE_ENTERPRISE_PRICE_ID=
BILLING_DEFAULT_GATEWAY=stripe   # prod default (dev defaults to razorpay)
BILLING_STRIPE_ENABLED=true

# Optional — Infrastructure
REDIS_HOST=                      # default localhost

# Optional — Observability
OTEL_SAMPLING_PROBABILITY=1.0
OTEL_METRICS_ENABLED=false
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces
```

## API contracts

### Run lifecycle

| Method | Path                                  | Description                                                                                                                                                                                                                                                                                 |
| ------ | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| POST   | `/api/runs`                           | Submit run (202). Body includes `aiProvider`, `executionMode`, `runMode`, `tddMode`, optional `userCostCap`, optional `figmaUrl`, optional `selectedRepoIds[]`, optional `requireConfirmationBeforeExpensiveContinuation`, optional `requireBenchmarkPriorForAutomaticContinuation`, optional `lowComplexityWorkerStartTier` (`WORKHORSE\|LIGHTWEIGHT`, non-pinning LOW-task start tier), optional `modelName` (ENTERPRISE per-run model pin, Beta — overrides org matrix + YAML via `RunModelOverrides.withRunPin`), optional `localInstallationId` (V278 — required when `localExecution=true` selects LOCAL project sources; admission verifies installation match + ONLINE lease before quota/spend). |
| GET    | `/api/runs`                           | List runs (paginated, filtered by visibility + tier).                                                                                                                                                                                                                                       |
| GET    | `/api/runs/{id}`                      | Run detail (incl. `githubPrUrl`, `verifiedScore`, `statusReason`, `planStrategy`, per-phase costs, and durable `requestedProvider` / `requestedModel` / `routingMode` / `routePinned` intent).                                                                                           |
| GET    | `/api/runs/{id}/requirement-gate`     | Authorized secret-free frozen-version RequirementIR evidence. A missing pre-wait gate returns 204; missing/corrupt/unsupported evidence at approval wait returns 409. |
| POST   | `/api/runs/{id}/approve`              | Plan gate approval; body `{approved, creditsConfirmed?, planFingerprint?, breakingChangeAck?, breakingChangeReportDigest?}`. Charges credits. `planFingerprint` (RR-1459) is the SHA-256 of the exact plan JSON the approver reviewed; mismatch returns 409, omission is recorded as absent/null rather than matched, and ENFORCED RequirementIR requires it. The web plan gate sends it because `creditsConfirmed` proves price, not plan identity. **RR-819:** when a pending contract gate has `requiresTypedAck`, approval is refused with 409 unless `breakingChangeAck` semantically equals `BREAKING` after trimming/case-folding and `breakingChangeReportDigest` matches. New C03C writes persist uppercase `BREAKING`; V348 snapshots and cross-binds acknowledgement timestamp/actor/exact stored text, preserving legacy bytes on replay. |
| GET    | `/api/runs/{id}/plan-contract-gate`   | **RR-819** — the durable plan-approval contract gate report the approver must be shown (`reportJson` rows, `reportDigest`, `requiresTypedAck`). 204 when the plan touches no registered contract. Run-read guarded. |
| POST   | `/api/runs/{id}/pause`                | PAUSED (V168, flag `v2.pause.enabled`, default OFF).                                                                                                                                                                                                                                        |
| POST   | `/api/runs/{id}/resume`               | Same-JVM only; workspace must exist.                                                                                                                                                                                                                                                        |
| DELETE | `/api/runs/{id}`                      | Cancel — `RunOrchestrator.cancel(runId)`. Signals plan gate, interrupts pipeline future, closes opened PRs.                                                                                                                                                                                 |
| POST   | `/api/runs/{id}/archive`              | Archive.                                                                                                                                                                                                                                                                                    |
| POST   | `/api/runs/{id}/iterate`              | Create a lineage-linked BUILD follow-up from a terminal COMPLETE/PARTIAL/FAILED run. Body `{userRequest}`; optional `Idempotency-Key`. The child inherits the source provider/exact model pin, project + selected repos, execution/TDD mode, cost cap and skip-auto-push policy, records `sourceRunId`, and starts the normal asynchronous pipeline from a fail-closed Git-tracked workspace snapshot. Run-write guarded; local-hands sources are rejected because their workspace is not server-owned. |
| POST   | `/api/runs/{id}/tasks/{taskId}/retry` | Retry one failed task. Requires `requireBuildVerification=true` on retry attempt. Does NOT auto-push — use the `Update PR` button on `InlineRunDetail` if needed.                                                                                                                           |
| POST   | `/api/runs/{id}/tasks/{taskId}/continuation/approve` | Approve a blocked continuation proposal. V364 atomically claims the exact run/task/proposal identity and returns `approvalId`, `replayed`, and status `queued`, `already_queued`, or `already_completed`. A duplicate while CLAIMED returns 409; QUEUED/COMPLETED duplicates replay without a second queue or spend, and FAILED/REJECTED receipts require a new proposal. Every server-side approved retry uses the stored provider/model inside the checkpoint → worker → read-only reviewer → deterministic validation → accept/rollback lifecycle. `postReview` remains the proposal-source compatibility flag, not a weaker safety path. |
| GET    | `/api/runs/{id}/events`               | SSE event stream (scoped token). `RunEvent` keeps legacy `payload` and adds `schemaVersion=1` + typed `data`; advisory task waves use `TASK_WAVE_PLAN`; evidence-gated initial route explanations use `INITIAL_ROUTING_DECISION`; goal/continuation decisions use `GOAL_DECISION` and `CONTINUATION_DECISION`; automatic selected-candidate continuation reruns use `CONTINUATION_ACTION`, while limit-blocked rerun recommendations use `CONTINUATION_ACTION_SKIPPED`. Goal/continuation decision/action events also trigger a fresh `RUN_DETAIL` push so the Steps tab sees persisted upgrade proposals live. |
| GET    | `/api/runs/{id}/sse-token`            | Get scoped SSE token (5-min TTL).                                                                                                                                                                                                                                                           |
| GET    | `/api/runs/{id}/event-log`            | Persisted run-event replay (24h Redis `sse:replay:{id}`) — backs the IDE run-detail **Logs** tab (RL-2026-05-046). Run-read guarded.                                                                                                                                                        |
| GET    | `/api/runs/{id}/publication-eligibility` | Read-only deterministic publication preview. Returns ELIGIBLE/BLOCKED plus the exact gate, stable code, remediation, retryability, and evidence/current workspace generations; protected-contract inspection does not rewrite durable comparison evidence. |
| GET    | `/api/executions/{id}/requirement-gate` | Compatibility route resolving the canonical pipeline run after project and run read guards. |
| POST   | `/api/runs/{id}/run-tests`            | Manually trigger test execution (Beta).                                                                                                                                                                                                                                                     |
| GET    | `/api/runs/{id}/prs`                  | List PRs opened by this run with status.                                                                                                                                                                                                                                                    |
| GET    | `/api/runs/{id}/cost`                 | Live token + cost snapshot; V2 live responses include cache/reasoning fields, `schemaVersion=1`, and separate physical/successful/failed attempt counts. Per-attempt `stepCalls[]` detail is ORG_ADMIN/SUPER_ADMIN-only: lower-privileged callers get `stepCallsIncluded=false` + `stepCallsRestrictedReason` + `stepCallsTotal` so the UI can say "withheld", not "none" (RR-1552). |
| POST   | `/api/runs/{id}/sync`                 | Reset workspace to current origin HEAD.                                                                                                                                                                                                                                                     |
| POST   | `/api/runs/{id}/sync/original`        | Reset workspace to the SHAs captured at run start (from `run_repo_snapshot`).                                                                                                                                                                                                               |
| POST   | `/api/repos/{repoId}/webhook`         | Install pull_request webhook with caller's PAT.                                                                                                                                                                                                                                             |

### Validation report + scoring

| Method | Path                                                    | Description                                                                                                                                                               |
| ------ | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `/api/runs/{id}/report`                                 | Get validation report, including `verificationEvidence[]` so the UI can separate deterministic checks, LLM assessments, and the internal score summary.                   |
| GET    | `/api/runs/{id}/report/pdf`                             | Download report as PDF.                                                                                                                                                   |
| POST   | `/api/runs/{id}/report/share`                           | Generate share link.                                                                                                                                                      |
| GET    | `/api/reports/shared/{token}`                           | Public shared report (no auth).                                                                                                                                           |
| POST   | `/api/admin/validation-reports/backfill-verified-score` | SUPER_ADMIN one-shot backfill for existing reports. Optional body `{runId?, limit?, dryRun?}` recomputes both `verifiedScore` and `rubricScore`; use `dryRun=true` first. |

### Preview + deploy

| Method | Path                           | Description                                                                            |
| ------ | ------------------------------ | -------------------------------------------------------------------------------------- |
| POST   | `/api/runs/{id}/preview/start` | Start preview (Cloud Run or Docker, 5-min TTL).                                        |
| POST   | `/api/runs/{id}/preview-render` | **Static UI render (V302, flag `preview.render-agent.enabled`, seeded OFF).** Renders the run's screens as self-contained offline HTML — no container, no deploy, so it works when the build is red. Run-write guarded (spends managed AI credits); 503 while the flag is off. |
| GET    | `/api/runs/{id}/preview-render` | Render job status + surface list + `fidelityNote`. `status=NONE` when never requested. |
| GET    | `/api/runs/{id}/preview-render/surfaces/{surfaceId}` | One admitted bundle as `text/html` with a restrictive CSP (also embedded as a `<meta>` so it survives `srcdoc`). 404 for surfaces that failed to render, so rejected model output is never served. |
| DELETE | `/api/runs/{id}/preview/stop`  | Stop preview.                                                                          |
| POST   | `/api/deployments`             | Deploy to Cloud Run (permanent).                                                       |
| POST   | `/api/runs/{id}/push-github`   | Deprecated GitHub push compatibility route. Destination is the repository already bound to the run; caller `repoUrl` overrides are rejected. |
| POST   | `/api/runs/{id}/push-scm`      | Provider-routed push. Destination is the durable run/selected-source identity; caller `repoUrl` overrides are rejected. |

### Project workspace

| Method | Path                                                                         | Description                                                                                                                                                 |
| ------ | ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `/api/projects/{id}/dashboard`                                               | Aggregated metrics, recent runs/PRs, arch-doc status.                                                                                                       |
| GET    | `/api/projects/{id}/memory` + `/memory/ledger`                               | Project memory snapshot + full ledger.                                                                                                                      |
| GET    | `/api/projects/{id}/scores`                                                  | Score-trend feed.                                                                                                                                           |
| GET    | `/api/projects/{id}/workspace-health`                                        | Per-repo SHA drift + webhook + open-PR rollup.                                                                                                              |
| GET    | `/api/projects/{id}/architecture` + `/versions` + `/diff?from=&to=`          | Latest arch / all versions / source-file changes between versions.                                                                                          |
| POST   | `/api/projects/{id}/architecture/generate-async`                             | Preferred web contract: admit/reuse a durable architecture refresh job and return 202.                                                                      |
| GET    | `/api/projects/{id}/architecture/jobs/{jobId}` + `/jobs/active` + `/jobs/latest` | Read one refresh, resume active polling, or restore the latest terminal/active state.                                                                      |
| POST   | `/api/projects/{id}/architecture/jobs/{jobId}/confirm-retry`                   | Explicitly requeue a NEEDS_CONFIRMATION job; ordinary submissions cannot bypass this spend gate.                                                            |
| POST   | `/api/projects/{id}/architecture/generate`                                   | Compatibility route: admits/reuses the same durable refresh job and returns 202.                                                                               |
| DELETE | `/api/projects/{id}/architecture/jobs/{jobId}`                                | Cooperatively cancel a queued/running refresh; publication remains fenced.                                                                                     |
| GET    | `/api/projects/{id}/repos/{repoId}/architecture`                             | Per-repo HLD/LLD.                                                                                                                                           |
| POST   | `/api/projects/{id}/repos/{repoId}/regenerate-architecture`                  | Admit the project-wide durable refresh boundary (202); avoids mixed-version direct per-repo publication.                                                     |
| POST   | `/api/projects/{id}/docs/{EXPLANATION\|SETUP}/generate` + `GET .../download` | Generated Docs.                                                                                                                                             |
| GET    | `/api/projects/{id}/tech-debt?since=<days>`                                  | Issues-by-category / severity / file hotspots.                                                                                                              |
| POST   | `/api/projects/{id}/analyse?branch=<name>`                                   | Per-branch arch refresh using caller's PAT.                                                                                                                 |
| GET    | `/api/projects/{id}/estimate?requirement=<text>`                             | Heuristic p25/p75 task + cost estimate from project history.                                                                                                |
| POST   | `/api/projects/{id}/prs/refresh`                                             | Poll GitHub + post-merge cascade + CI check-runs.                                                                                                           |
| GET    | `/api/projects/{id}/templates` + POST                                        | Spec templates (project + org-scoped).                                                                                                                      |
| POST   | `/api/projects/{id}/sources/local`                                           | V278 — register/renew a path-free LOCAL folder source (opaque UUIDv4 folder + installation ids, 30-300s lease). Write-guarded; 409 on foreign installation. |
| POST   | `/api/projects/{id}/sources/local/{folderId}/heartbeat`                      | Renew a LOCAL source's lease (holding installation only). Drives the ONLINE/OFFLINE connector state that local-run admission checks.                        |
| POST   | `/webhooks/github`                                                           | PR + check_run webhook (HMAC SHA-256).                                                                                                                      |

### PR Review + Code Review (V172)

| Method | Path                                                                                             | Description                                                                        |
| ------ | ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------- |
| POST   | `/api/pr-reviews`                                                                                | Submit PR review (PRO+); body `{projectId, repoFullName, prNumber}`. 202 + QUEUED. |
| GET    | `/api/pr-reviews/{id}` + `/api/projects/{id}/pr-reviews` + by-PR query                           | Read endpoints.                                                                    |
| POST   | `/api/pr-reviews/{id}/cancel` + `/retry`                                                         | V299 durable-job cancellation or explicit retry; project-write guarded.           |
| POST   | `/api/code-reviews`                                                                              | Submit whole-repo scan (PRO+).                                                     |
| GET    | `/api/code-reviews/{id}` + `/report` (markdown) + `/api/projects/{id}/code-reviews/latest?repo=` | Read endpoints.                                                                    |
| POST   | `/api/code-reviews/{id}/cancel` + `/retry`                                                       | V299 durable-job cancellation or explicit retry; project-write guarded.           |

### External project sharing (V175 + V198)

| Method | Path                                                                                          | Description                                                                                                                                                                                                                 |
| ------ | --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| POST   | `/api/projects/{id}/invites` + GET + DELETE                                                   | Cross-org `ProjectMember` invite by email. **RR-973 (2026-07-21):** every gate is evaluated against the org that OWNS the project, not the inviter's — create requires ORG_ADMIN **of the host org** (or SUPER_ADMIN) AND the **host** org's `external_sharing_enabled=true` AND a paid **host** entitlement. PROJECT_ADMIN alone is not enough, and neither is being an org admin somewhere else. `accept` re-consults the host org's flag, so a standing invite minted under the old rule cannot complete. |
| GET    | `/api/invites/preview?token=` + POST `/api/invites/accept?token=`                             | Accept invite (email-match required).                                                                                                                                                                                       |
| POST   | `/api/projects/{id}/share-links` + GET + DELETE                                               | Public read-only share links (tier-gated). V287 stores only token hashes, requires 1..90-day expiry and returns plaintext once; list/reload is prefix-only.                                                                 |
| GET    | `/api/projects/{id}/share-links/{linkId}/audit`                                               | Latest 100 secret-free append-only lifecycle/access facts; project writers/admins only.                                                                                                                                    |
| GET    | `/api/shared/{token}/{meta,project,run,validation-report,code-review,pr-review,architecture}` | Public read endpoints.                                                                                                                                                                                                      |
| PUT    | `/api/org/me/settings/external-sharing`                                                       | **V198** — toggle the per-org flag. Body `{enabled: bool}`. ORG_ADMIN-of-own-org or SUPER_ADMIN only. Emits `ORG_EXTERNAL_SHARING_CHANGED` audit row.                                                                       |
| GET    | `/api/org/me/model-matrix`                                                                    | **F3** — effective model matrix (defaults + this org's overrides + picker options + `modelLabels`). Any org admin (read-only grid for non-ENTERPRISE).                                                                      |
| PUT    | `/api/org/me/model-matrix`                                                                    | **F3 (V205)** — replace the org's `(provider:tier)→model` overrides (sparse; `{}` clears). ENTERPRISE-only (403 otherwise); each cell validated against the registered matrix.                                              |

### Auth + org

| Method          | Path                                                                        | Description                                                                                                                              |
| --------------- | --------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| POST            | `/api/auth/register` / `/login` / `/refresh` (logout is `DELETE /api/auth/logout`) | JWT auth.                                                                                                                                |
| POST            | `/api/auth/forgot-password` / `/reset-password` / GET `/verify-email`       | Account recovery.                                                                                                                        |
| GET             | `/api/auth/oauth2/init?surface=<x>` / `/authorize` / `/callback/{provider}` | OAuth2 (Google + GitHub).                                                                                                                |
| GET             | `/api/auth/me/permissions`                                                  | Tier-based permissions, including the legacy `monthlyRunLimit` plus explicit quota metadata (`quotaModel`, `includedAiCredits`, `unlimitedAiCredits`, `quotaDescription`) so managed-AI tiers do not look like unlimited-run plans. |
| GET             | `/api/auth/me/data-export`                                                  | GDPR ZIP (manifest + payload + README). Send `Accept: application/zip`; JSON clients receive a clear 406 hint.                                                                                                  |
| POST/GET/DELETE | `/api/auth/me/tokens` (+ `/{tokenId}`)                                      | Personal Access Tokens for CLI / VS Code / CI (V215). Create returns the `es_pat_…` plaintext once; list is secret-free; delete revokes. |
| POST            | `/api/org` / `/api/org/invite`                                              | Org creation/conversion + invitations.                                                                                                   |

### Billing

| Method | Path                                              | Description                                              |
| ------ | ------------------------------------------------- | -------------------------------------------------------- |
| GET    | `/api/billing/plans`                              | Yields YAML-driven pricing rows.                         |
| POST   | `/api/billing/subscribe`                          | Routed to Razorpay or Stripe via `PaymentGatewayRouter`. |
| POST   | `/api/billing/topup/purchase`                     | Buy AI-credit top-ups (`packageId` or custom whole-dollar amount). |
| GET    | `/api/billing/payment-history`                    | Org-scoped payment history.                              |
| POST   | `/api/webhooks/razorpay` + `/api/webhooks/stripe` | Webhook events (HMAC verified, no auth).                 |

### Admin

| Method | Path                                                      | Description                                                                              |
| ------ | --------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| GET    | `/api/admin/payment-audit` + `/export`                    | Payment audit log + CSV (SUPER_ADMIN).                                                   |
| GET    | `/api/admin/webhook-events`                               | Webhook event log.                                                                       |
| GET    | `/api/admin/tiers` + PUT `/{tier}/lifecycle`              | Tier lifecycle (LISTED/HIDDEN/SUNSETTING).                                               |
| POST   | `/api/admin/executions/reconcile`                         | Mark orphaned non-terminal ExecutionRuns FAILED.                                         |
| POST   | `/api/admin/benchmark/generate` (+ `/run` alias) + `/api/admin/programbench/run` | Bench-mode submission.                                                                   |
| POST   | `/api/admin/eval/v2/run`                                  | Tier-eval-v2 — production-shaped per-tier × provider eval (`tiers`, `providers` params). |
| POST   | `/api/admin/projects/{id}/migrate-codemaps`               | Migrate legacy codemaps into `.executespec/codemaps/`.                                   |

### Enterprise readiness — `.executespec/` folder, contracts, standards, ADRs, activity

When `standards.enforcement.enabled` is on for a project run, the effective org/project standards
are resolved before planning from org policy plus the exact selected repositories' canonical
`.executespec/config[/<project-slug>]/standards.yaml` files and injected as untrusted policy
evidence into planner and worker/
reviewer design context. Final deterministic enforcement persists violations and also emits common
validation issues; BLOCKING violations force `PARTIAL/STANDARDS_BLOCKING_VIOLATION`, and a failed
or unavailable configured verifier forces `PARTIAL/STANDARDS_ENFORCEMENT_UNAVAILABLE`. The flag
remains default OFF. Active architecture invariants execute deterministically against the final
project-run workspace; violations, invalid YAML and unsupported rules persist as explicit non-pass
evidence and force an invariant-specific PARTIAL reason. The ARCH_REGEN phase is still open.
Dual-surface project audit rows resolve and persist the owning `orgId` (or the explicit ORG target)
so org-scoped compliance queries do not lose project events; the activity/audit write itself is
still asynchronous and is not yet protected by a transactional outbox.

| Method              | Path                                                                                           | Description                                                          |
| ------------------- | ---------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| GET                 | `/api/projects/{id}/activity`                                                                  | Project activity feed (partitioned-by-month).                        |
| POST                | `/api/projects/{id}/export`                                                                    | ZIP bundle of every `.executespec/` artefact + DB-only counterparts. |
| POST                | `/api/projects/{id}/check-drift` + per-repo + per-contract                                     | Cross-repo contract drift detection.                                 |
| POST                | `/api/projects/{id}/contracts/{contractId}/{refresh-from-source,dismiss-drift}`                | Drift workflow.                                                      |
| GET / POST / DELETE | `/api/projects/{id}/contracts/{contractId}/consumers/{...}`                                    | Consumer pinning.                                                    |
| GET + POST          | `/api/projects/{id}/repos/{repoId}/contract-coverage` + `/rescan`                              | Coverage rollup.                                                     |
| POST                | `/api/projects/{id}/standards/violations/{id}/override` + `/standards/{id}/exception` + DELETE | Standards override + standing exception (≥30-char rationale).        |
| GET                 | `/api/projects/{id}/standards/report?orgId=&sinceDays=`                                        | Standards compliance report.                                         |

All drift-check POSTs require project write authority because detection persists drift/activity
state. Single-contract drift check, refresh, and dismissal validate that the contract's repository is
linked to the guarded path project before reading or mutating it; an accessible project URL cannot
act as authority for a foreign contract UUID.
Contract-consumer list/declare/delete applies the same invariant to the producer contract and also
requires the consumer repository to be linked to that project before persistence or SCM writes.

### Notifications + flags

| Method | Path                                                                          | Description                       |
| ------ | ----------------------------------------------------------------------------- | --------------------------------- |
| GET    | `/api/notifications` + `/unread-count` + POST `/{id}/read` + POST `/read-all` | In-app notifications.             |
| GET    | `/api/models/available`                                                       | Model matrix (YAML-derived).      |
| GET    | `/api/admin/feature-flags` + PUT `/{key}`                                    | Feature flag admin (SUPER_ADMIN). |

## Key file locations

| Purpose                         | Path                                                                                                                                                                                                                                                                              |
| ------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Run orchestrator                | [`integration/RunOrchestrator.java`](src/main/java/com/navneet/executespec/integration/RunOrchestrator.java)                                                                                                                                                                      |
| Correction transaction         | [`integration/AutomaticCorrectionLifecycle.java`](src/main/java/com/navneet/executespec/integration/AutomaticCorrectionLifecycle.java) + `DurableCorrectionLifecycle` + `CorrectionWorkspaceTransaction` + `CorrectionAcceptancePolicy`                                                                          |
| Pre-planner architecture refresh | [`integration/PrePlannerArchitectureRefreshService.java`](src/main/java/com/navneet/executespec/integration/PrePlannerArchitectureRefreshService.java) — owns stale-map detection and non-blocking durable V353 admission; the durable worker owns generation/promotion/cache invalidation so advisory architecture refresh cannot block planning |
| Customer BUILD admission        | [`run/CustomerRunSubmissionService.java`](src/main/java/com/navneet/executespec/run/CustomerRunSubmissionService.java) — shared REST, confirmed Web Agent, fork, and unified Execution View admission boundary; exact project/repository scope, provider/key/spend checks, and durable idempotency precede dispatch                                                                 |
| Plan-gate decision              | [`integration/PlanGateDecisionService.java`](src/main/java/com/navneet/executespec/integration/PlanGateDecisionService.java) + [`integration/PlanGateDecisionEvidence.java`](src/main/java/com/navneet/executespec/integration/PlanGateDecisionEvidence.java) + [`integration/PlanGateDecisionAdmission.java`](src/main/java/com/navneet/executespec/integration/PlanGateDecisionAdmission.java) + [`integration/DecisionEvidenceV1.java`](src/main/java/com/navneet/executespec/integration/DecisionEvidenceV1.java) + [`integration/DecisionRequestBytesV1.java`](src/main/java/com/navneet/executespec/integration/DecisionRequestBytesV1.java) |
| Plan-gate continuation          | [`integration/PlanGateResumeDispatcher.java`](src/main/java/com/navneet/executespec/integration/PlanGateResumeDispatcher.java) + [`integration/PlanGateResumeStore.java`](src/main/java/com/navneet/executespec/integration/PlanGateResumeStore.java)                                                                                              |
| Sequential executor             | [`harness/SequentialExecutor.java`](src/main/java/com/navneet/executespec/harness/SequentialExecutor.java)                                                                                                                                                                        |
| Worker agent                    | [`engine/WorkerAgent.java`](src/main/java/com/navneet/executespec/engine/WorkerAgent.java) (moved from `agent/` — Phase 4)                                                                                                                                                        |
| Outline planner                 | [`agent/OutlinePlannerAgent.java`](src/main/java/com/navneet/executespec/agent/OutlinePlannerAgent.java)                                                                                                                                                                          |
| Smart classifier                | [`agent/SmartClassifierAgent.java`](src/main/java/com/navneet/executespec/agent/SmartClassifierAgent.java)                                                                                                                                                                        |
| Reviewer                        | [`reviewer/ReviewerAgent.java`](src/main/java/com/navneet/executespec/reviewer/ReviewerAgent.java)                                                                                                                                                                                |
| Patch-mode agent                | [`engine/CodeGenEngineImpl.java`](src/main/java/com/navneet/executespec/engine/CodeGenEngineImpl.java) — direct PATCH worker facade                                                                                                                                               |
| LLM router (V2)                 | [`agent/LlmRouter.java`](src/main/java/com/navneet/executespec/agent/LlmRouter.java) + `OpenAiNativeToolClient` + `GeminiNativeToolClient`                                                                                                                                        |
| Tool registry                   | [`tool/ToolRegistry.java`](src/main/java/com/navneet/executespec/tool/ToolRegistry.java) + `tool/*` (11 tools + `CompleteTaskTool`)                                                                                                                                               |
| Context store                   | [`context/ContextStore.java`](src/main/java/com/navneet/executespec/context/ContextStore.java)                                                                                                                                                                                    |
| Cost tracker                    | [`cost/RunCostTracker.java`](src/main/java/com/navneet/executespec/cost/RunCostTracker.java)                                                                                                                                                                                      |
| State store                     | [`state/StateStore.java`](src/main/java/com/navneet/executespec/state/StateStore.java)                                                                                                                                                                                            |
| Startup resumer                 | [`recovery/StartupRunResumer.java`](src/main/java/com/navneet/executespec/recovery/StartupRunResumer.java)                                                                                                                                                                        |
| Finding patch submitter         | [`finding/FindingPatchSubmitter.java`](src/main/java/com/navneet/executespec/finding/FindingPatchSubmitter.java)                                                                                                                                                                  |
| Model registry                  | [`router/ModelRegistry.java`](src/main/java/com/navneet/executespec/router/ModelRegistry.java)                                                                                                                                                                                    |
| Validation report               | [`report/ValidationReportService.java`](src/main/java/com/navneet/executespec/report/ValidationReportService.java) + `ValidationReportGenerator`                                                                                                                                  |
| Project architecture            | [`codeintel/ProjectArchitectureService.java`](src/main/java/com/navneet/executespec/codeintel/ProjectArchitectureService.java)                                                                                                                                                    |
| Repository intelligence port    | [`repositoryintel/`](src/main/java/com/navneet/executespec/repositoryintel/) — typed actor/host-tenant/project/repository/snapshot-bound query + evidence contracts, clean-worktree-only access-checked canonical code-index-clone handle, and a registered conservative native exact/symbol/heuristic-relationship adapter; USER sources remain private to their owner, materialization/snapshot identity is revalidated at read boundaries, no pipeline caller or authenticated-principal factory is migrated, and semantic/AST/codemap/document/architecture/ADR integration remains open |
| Repository explorer contract    | [`repositoryexplorer/`](src/main/java/com/navneet/executespec/repositoryexplorer/) — provider-free invocation policy and bounded, snapshot-validated `TaskEvidenceBundle` assembly over `repositoryintel`; no Spring bean, `LlmRouter` call, provider service, pipeline consumer, feature graduation, or quality-uplift proof exists yet |
| Project memory                  | [`memory/RunLedgerAgent.java`](src/main/java/com/navneet/executespec/memory/RunLedgerAgent.java) + `MemoryCompactionService` + `ProjectMemoryProvider`                                                                                                                            |
| GitHub integration              | [`repo/GitHubRepoService.java`](src/main/java/com/navneet/executespec/repo/GitHubRepoService.java) + `BitbucketRepoService`                                                                                                                                                       |
| Multi-repo push                 | [`integration/scm/ScmPushService.java`](src/main/java/com/navneet/executespec/integration/scm/ScmPushService.java)                                                                                                                                                                |
| Publication admission          | [`integration/scm/PublicationAdmissionService.java`](src/main/java/com/navneet/executespec/integration/scm/PublicationAdmissionService.java) — workspace/build/test/approved-route gate shared by SCM and permanent deployment                                                     |
| SWE-bench adapter               | [`swebench/`](src/main/java/com/navneet/executespec/swebench/)                                                                                                                                                                                                                    |
| ProgramBench adapter            | [`programbench/`](src/main/java/com/navneet/executespec/programbench/)                                                                                                                                                                                                            |
| Terminal-Bench adapter          | [`terminalbench/`](src/main/java/com/navneet/executespec/terminalbench/) — operator-only Harbor launcher for remote-brain/local-hands TB runs                                                                                                                                     |
| Static UI render                | [`previewrender/`](src/main/java/com/navneet/executespec/previewrender/) — `PreviewSurfaceDiscovery` (deterministic screen finder) + `PreviewRenderAgent` (WORKHORSE, one offline HTML doc per screen) + `PreviewRenderGuard` (fail-closed admission) + `PreviewRenderService` / `PreviewRenderController`                                                              |
| Benchmark tracking/trajectory   | [`benchmark/`](src/main/java/com/navneet/executespec/benchmark/) — V297 durable progress/readiness plus bounded, fsynced, secret-redacted NDJSON trajectory capture/export; multi-JVM deployments require a shared persistent trajectory root                                                                                                  |
| Security config                 | [`auth/security/SecurityConfig.java`](src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java) + [`auth/oauth/CookieOAuth2AuthorizationRequestRepository.java`](src/main/java/com/navneet/executespec/auth/oauth/CookieOAuth2AuthorizationRequestRepository.java) |
| Billing service                 | [`billing/service/BillingService.java`](src/main/java/com/navneet/executespec/billing/service/BillingService.java) + `RunTopupService` + `ManagedAiUsageService` / `ManagedAiTierResolver` + `BillingWebhookEventRecorder` + `gateway/PaymentGatewayRouter`                                                                                               |
| Feature flags                   | [`config/featureflag/FeatureFlagService.java`](src/main/java/com/navneet/executespec/config/featureflag/FeatureFlagService.java)                                                                                                                                                  |
| Feature-flag snapshot parity    | `scripts/compare-feature-flag-snapshots.mjs` + `scripts/feature-flag-parity-allowlist.json` — read-only local/dev snapshot comparison that fails on unallowlisted presence/value/type/scope drift and forbids allowlisting release-critical gates; it does not capture environments or prove live parity by itself |
| Rate limiting                   | [`ratelimit/RateLimitFilter.java`](src/main/java/com/navneet/executespec/ratelimit/RateLimitFilter.java)                                                                                                                                                                          |
| Sharing                         | [`sharing/`](src/main/java/com/navneet/executespec/sharing/)                                                                                                                                                                                                                      |
| PR Review agent                 | [`prreview/`](src/main/java/com/navneet/executespec/prreview/)                                                                                                                                                                                                                    |
| Code Review agent               | [`codereview/`](src/main/java/com/navneet/executespec/codereview/)                                                                                                                                                                                                                |
| Activity feed                   | [`activity/`](src/main/java/com/navneet/executespec/activity/)                                                                                                                                                                                                                    |
| Folder writer (`.executespec/`) | [`folder/ExecutespecFolderWriter.java`](src/main/java/com/navneet/executespec/folder/ExecutespecFolderWriter.java)                                                                                                                                                                |
| Standards / ADR / Invariants    | [`standards/`](src/main/java/com/navneet/executespec/standards/), [`adr/`](src/main/java/com/navneet/executespec/adr/), [`arch/invariants/`](src/main/java/com/navneet/executespec/arch/invariants/)                                                                              |
| Cross-repo contracts            | [`contracts/`](src/main/java/com/navneet/executespec/contracts/)                                                                                                                                                                                                                  |
| Notification preferences        | [`notification/pref/`](src/main/java/com/navneet/executespec/notification/pref/)                                                                                                                                                                                                  |
| GDPR data export                | [`auth/service/UserDataExportService.java`](src/main/java/com/navneet/executespec/auth/service/UserDataExportService.java)                                                                                                                                                        |
| Prompt assembler                | [`agent/PromptAssembler.java`](src/main/java/com/navneet/executespec/agent/PromptAssembler.java) + `prompts/fragments/*.md` + `prompts/scaffolds/*.md`                                                                                                                            |
| Agent web tools + chat uploads  | [`specchat/WebSearchChatTools.java`](src/main/java/com/navneet/executespec/specchat/WebSearchChatTools.java) (SSRF-hardened `web_search`/`web_fetch` for the chat surface) + [`agentsession/AgentAttachmentService.java`](src/main/java/com/navneet/executespec/agentsession/AgentAttachmentService.java) + `AgentAttachmentStore.java` (validate/extract/sanitize uploads → Redis → server-side untrusted injection) |
| LLM prompts                     | `src/main/resources/prompts/*.md`                                                                                                                                                                                                                                                 |
| App config                      | `src/main/resources/application.yml`                                                                                                                                                                                                                                              |
| DB migrations                   | `src/main/resources/db/migration/V*.sql` (worktree head V371; next V372; 357 migration files; applied V-files immutable). V360 adds brownfield HTTP evidence; V361 adds workspace-generation binding; V362 adds command/workflow evidence; V363 confirmation-fences uncertain document retries; V364 atomically fences exact continuation approvals; V365 bounds stale-receipt recovery; V366 persists exact score-zero causality; V367 persists runtime-surface applicability; V368 force-disables legacy Apply-to-Run pending transactional correction; V369 persists crash-recoverable correction authority; V370 retypes V369's digest columns CHAR(64)→VARCHAR(64) to satisfy Hibernate schema-validation; V371 persists explicit skipped-test counts. |
| Frontend entry                  | `frontend/src/main.tsx`                                                                                                                                                                                                                                                           |
| Frontend API client             | `frontend/src/lib/api/` (`transport.ts` + domain DTO contracts, with `core.ts` retaining compatibility re-exports)                                                                                                                                                              |
| Observability stack             | `observability/docker-compose.yml`                                                                                                                                                                                                                                                |
| V2 eval framework               | `scripts/v2-eval.sh` + `scripts/v2-eval-specs/{low,medium,high}.txt`                                                                                                                                                                                                              |
| Tier-eval-v2                    | [`eval/v2/`](src/main/java/com/navneet/executespec/eval/v2/) (`TierEvalRunner` + `TierEvalController` + `cases/`) + `scripts/tier-eval-v2.sh`                                                                                                                                     |

## Current state

### Working

- V2 BUILD-mode pipeline end-to-end: classify → outline → sequential per-task worker → reviewer → COMPLETE → auto-preview (5-min TTL) → background validation (report + test gen + test exec). Hierarchical planning (LARGE/LEGACY).
- V2 PATCH-mode pipeline: SWE-bench Lite + ProgramBench bench-mode + 4 customer-facing autofix surfaces (PR Review / TechDebt / CI failure webhook / Code Review severity buckets).
- Per-run codemap + 3-step project architecture pipeline + Generate Docs.
- Project memory + project chat with compaction.
- Always-on RAG over user docs via pgvector + Tika + OpenAI embeddings.
- External project sharing: cross-org `ProjectMember` invites + public read-only share links (V175). Per-org feature flag `org.external_sharing_enabled` (V198, default false; ORG_ADMIN/SUPER_ADMIN to toggle) gates the cross-org invite surface; share-links are independent.
- PR Review + Code Review agents with markdown report download (V172).
- Multi-repo runs: per-repo workspace + per-repo codemap + per-repo PR + cross-link.
- Tier lifecycle state machine + SUPER_ADMIN tier console.
- Cost-proportional run credits + voluntary `userCostCap` + BYOK cost-cap exemption + visible `CostProgressBar`.
- Multi-gateway billing (Razorpay + Stripe) with AI-credit top-ups.
- Dual-mode previews: Cloud Run (dev/prod, 5-min TTL, managed HTTPS) or Docker Compose (local, Caddy + Cloudflare Origin Certificate).
- JWT auth + OAuth2 (Google, GitHub) with RBAC, org management, project scoping.
- 8-layer security + 3 guardrail levels.
- Containerized test execution (Docker `--read-only --cap-drop ALL` + memory/CPU limits + env-var whitelist).
- In-app notifications (run completion + quota warning + gate-opened events).
- Pause / resume on V2 runs (interrupt + resume-from-disk; flag `v2.pause.enabled`).
- GDPR data-export endpoint.
- `AccessGuardCoverageTest` ArchUnit pin — tenant-guard coverage + write-handler-uses-write-guard (RL-2026-05-040); `KNOWN_LEGACY_DRIFT` empty (35/35 cleared).
- Local Grafana dashboards + GCP Cloud Trace + Cloud Monitoring exporters (`@Profile("gcp")`).
- Model evaluation framework (`eval/`) + tier-eval-v2 (`eval/v2/`) — production-shaped per-tier (LIGHTWEIGHT / PLANNING / WORKHORSE) × provider eval that drives the live agent prompts + tool schemas through `LlmRouter` and scores tool-call validity, cache hit rate, latency, cost. Admin endpoint `POST /api/admin/eval/v2/run`; CLI wrapper `scripts/tier-eval-v2.sh`.

### In progress / not-yet-merged

- **Static render admission clarification (2026-07-26):** the manifest exposes `renderAllowed`, and the paid render POST requires run-baseline evidence of at least one changed product file. Planning failures and clean bootstrap workspaces receive an honest disabled state / HTTP 409 without provider spend; an already-admitted active job remains reloadable even if workspace evidence later disappears.

- **P1 audit-remediation sweep (2026-07-22, branch `codex/brownfield-rca-remediation-20260717`)** — a systematic pass over the `/private/tmp/executespec-repo-review-2026-07-20/review-findings.md` ledger (154 P0 + 1012 P1 + 520 P2). **~418 P1 findings fixed and pushed** across ~20 merge-train batches (per-package worktree lanes, each verify-then-fix + gated at the repo root with `mvn -B spotless:check test`, then boot-smoked for migrations). The code-fixable P1 backlog is exhausted; the remainder is escalated in `~/Downloads/executespec-pending-founder-decisions.md`: **Category B** (`@Version` optimistic locking on hot entities — app-wide save-path blast radius), **Category C** (outbox/inbox/saga durability features — own-PR scope), **Category D** (product/pricing/policy decisions), plus ~90 `executespec-cli`-repo findings out of this repo's scope. New helper classes extracted to hold god-class ceilings while fixing: `deployment/CloudRunProcess`, `integration/{AutoPushEligibilityPolicy,WorkspaceGitCommitService}`, `cost/CostVisibilityPolicy`, `auth/service/{UserSettingsUpdateService,AdminUserMutationService}`, `billing/service/{TopupPackageAdminService,BillingWebhookSequencingService,ManagedAiUsagePeriodInitializer}`, `router/BenchmarkQualityPriorProvider`, `harness/ContinuationEscalationBudget`, `engine/WorkerAgentSupport`, `security/{UstarTarReader,EnvScanSafety}`, `state/PlanGraphValidator`, `frontend/src/ide/agentIcons.tsx`, `frontend/src/signal/pages/AcceptInvitePage.tsx`. CI change: the PR gate skips JaCoCo by default (coverage behind `-Pcoverage`); Surefire fork-parallelism was tried and reverted (exposed timing-flaky tests + forced high local RAM).

- **Static UI preview render — `previewrender/` (2026-07-19, V302, flag `preview.render-agent.enabled` seeded OFF)** — a preview that renders instead of deploying. `PreviewSurfaceDiscovery` deterministically finds up to 6 screens in the run workspace under a character budget (so token cost is known before any provider call), ranking the run's own changed product files first via `RunChangeEvidenceService`. `PreviewRenderAgent` (WORKHORSE, no tools) turns each screen into ONE self-contained offline HTML document with agent-invented realistic sample data. Four targets with declared fidelity: `WEB` (CLOSE), `REACT_NATIVE` (STRUCTURAL — RN primitives map onto flexbox), `ANDROID_NATIVE` and `IOS_NATIVE` (APPROXIMATE — Compose/XML and SwiftUI/UIKit are interpreted from source, never executed). This is the only iOS preview path that can exist, since iOS cannot run in a container; the existing `REACT_NATIVE_EXPO` container path is Android-only. **Because it never builds, it works on runs whose build is red — the case the container preview cannot serve at all.** `PreviewRenderGuard` is a fail-closed admission gate on model-authored markup: no external URL (SVG/MathML namespaces exempted), no `fetch`/`XHR`/`WebSocket`/`EventSource`/dynamic-import, no `window.top`/`parent`, no storage or cookie access, size cap, plus `GeneratedSecretScanner`; rejection reasons carry a category only, and rejected HTML is never persisted. Bundles are re-admitted before being served and mount via `srcdoc` on `sandbox="allow-scripts"` **without** `allow-same-origin` (opaque origin), with the CSP embedded in the document because a response header does not govern `srcdoc`. **Decorative by construction: no scorer reads these tables and no validation evidence is published** — an LLM's drawing of a screen is not proof the screen works, and the UI carries a permanent fidelity note saying so. Surfaced as a **Screens** tab on the run result (`frontend/src/ide/IdeRunScreens.tsx`). Dev live proof on 2026-07-23: the flag was enabled through the admin API and run `87530d93-e9da-40a4-8104-271ed803416e` discovered `index.html`, completed a DeepSeek WORKHORSE render, persisted a READY WEB bundle, and served it through the sandboxed iframe. Render output remains decorative and must never affect scoring.

- **Enterprise hardening + benchmark publication programme (2026-07-17, branch `codex/brownfield-rca-remediation-20260717`)** — immutable scope and completion gates are in [`docs/plans/enterprise-hardening-benchmark-master-plan-2026-07-17.md`](../executespec-docs/backend/plans/source-repository/enterprise-hardening-benchmark-master-plan-2026-07-17.md); continuously changing evidence/status is in [`docs/plans/enterprise-hardening-benchmark-status-2026-07-17.md`](../executespec-docs/backend/plans/source-repository/enterprise-hardening-benchmark-status-2026-07-17.md). The programme re-audits the current three-repository tuple before inheriting prior closure claims, keeps release and paid benchmark gates closed until Enterprise product QA passes, and requires a GPT-5.6 Luna handoff before benchmark provisioning/execution.
- **SWE-bench generation integrity (2026-07-18):** cleanroom patch capture treats the host-owned bind mount as an exact Git `safe.directory`, validates the dataset base commit as a hex object id, includes untracked files without filename-splitting, and fails the benchmark run instead of converting Git errors to an empty patch. `GenerateResult` exposes post-run input/output tokens for the tracking manifest. The admin generation request accepts an optional `hardCapUsd` that can tighten the configured server cap; benchmark operators may explicitly set `enforceHardCap=false` to run under campaign-only accounting. Lite uses the official SWE-bench standard harness; `score-watcher.py` is Pro-only, and `k-eval.py` requires fail-closed tracking, an exact frozen task count, campaign reservations and exact manifest/result coverage before `RUN_DONE` (serial when no per-task cap is used).
- **Harness resolution (branch `feature/harness-resolution`, 2026-07-04)** — flag-gated implementation of the adversarial-assessment resolution path ([`docs/plans/executespec-codegen-harness-adversarial-assessment-2026-07-04.md`](../executespec-docs/backend/plans/source-repository/executespec-codegen-harness-adversarial-assessment-2026-07-04.md); decision/handoff log [`infra/benchmark/HARNESS-RESOLUTION-LOG-2026-07-04.md`](../executespec-docs/release-evidence/archive/benchmark-history/HARNESS-RESOLUTION-LOG-2026-07-04.md)). V340 graduates the lean server-worker toolset after live release-blocker validation; compatibility aliases remain for local-hands and replay. The remaining harness controls stay flag-gated: progress-v2 worker engine (**successful** novelty progress; failed/forbidden variants are remembered but cannot reset stagnation, plus repetition force and bounded text-only retries), language-aware `runTests` (resolver-driven go/npm/cargo/mvn/gradle + pytest), gitDiff budget+pathspec, writeFile overwrite-with-read-recency, server-side auto-verify with failure-output injection + VBC repair detail + reviewer fix-tests pass (including deterministic completion when the first post-green turn is read-only inspection), window-aware dossier compaction + context-overflow recovery, per-provider capability profiles (YAML dark), deterministic repo-orientation warm start, and the rolling run journal. Run-journal recovery accepts legacy bare-array files only for unscoped compatibility reads; run-bound reloads fail closed because that format has no run identity. As of the planner contract pass, the same orientation block is also injected into the outline planner when the flag is enabled, so repository evidence is not rediscovered between planning and execution. SWE-bench Pro blind spec now injects the official `requirements` + `interface` dataset fields (leaderboard-legal; pinned by `BenchmarkBlindSpecTest`).

- **Terminal-Bench 2.1 internal benchmark path (RL-2026-06-004 + 2026-07-02 readiness pass)** — ExecuteSpec's own `WorkerAgent` runs on Terminal-Bench via remote-brain/local-hands: server-side worker loop, `TOOL_CALL` SSE, Harbor adapter executes tools in the TB container and returns envelopes through `POST /api/runs/{id}/tool-results`. Operator-only endpoint: `terminalbench/TerminalBenchController` under `/api/v2/test/terminal-bench/start`, `local|dev|benchmark` profile + SUPER_ADMIN. The keystone bug was that the V2 worker manually dispatches tools, so the remote branch had to be added at `WorkerAgent.dispatchOne` through `ToolRegistry.isRemoteRun/dispatchRemote`; the old `ContextBoundToolCallback` branch alone never exercised remote mode for real worker runs. Current harness contract: Harbor shell/build/test non-zero exits must return failed remote envelopes, terminal finalizer completion must still pass deliverable/critic gates, the critic reads visible `/tests` plus likely `/app` candidate files when explicit output extraction is weak, and when `/tests` exists the worker cannot complete from a self-authored smoke alone. The internal TB v2.1 run is **k=1 only, no leaderboard submission**, tracked in `/benchmark`, and limited to DeepSeek V4 Pro native, DeepSeek V4 Flash native, Sonnet 5, Sonnet 4.6, and GLM 5.2 via OpenRouter (`infra/benchmark/scripts/run-tb-internal-once-matrix.sh`). Gemini, GPT, and Sarvam are intentionally excluded from this internal pass.
- **In-app benchmark tracking — common / multi-suite layer (`benchmark` branch, 2026-06-09)** — a suite-agnostic in-app layer that **tracks** any benchmark run + submission + events inside ExecuteSpec (in-login `/benchmark` dashboard). ExecuteSpec is the event **sink + display, NOT an orchestrator**: an external verifier/harness (Harbor for Terminal-Bench; SWE-bench + ProgramBench next) publishes the **same** event shapes (`TASK_SCORED {task,attempt,reward,esRunId?,cost}` / `RUN_PROGRESS` / `RUN_DONE` / `SUBMISSION_*`) to one ingest endpoint; ExecuteSpec ingests → stores → re-broadcasts (SSE) → shows. **V236** (`benchmark_run` / `benchmark_task_result` / `benchmark_event`) + `benchmark/BenchmarkTrackingController` (`/api/v2/test/benchmark`, SUPER_ADMIN + `@Profile({"local","dev","benchmark"})`) + `BenchmarkTrackingService` (idempotent task upsert + resolved%/cost aggregation, reuses `sse/SseBroadcaster` keyed by the benchmark-run id). Suite discriminated by `BenchmarkSuite` (String column → new suites need no migration). Named **distinctly** from the SWE-bench _execution_ harness `BenchmarkController` to avoid a class/path collision. Phases 1-2 (persistence + ingest/SSE), **Phase 3 Terminal-Bench bridge (`infra/benchmark/tb/tb-bench-bridge.py`, launched by `run-tb.sh`)**, and **Phase 4 operator `/benchmark` dashboard — IDE-native `frontend/src/ide/IdeBenchmark.tsx`, SUPER_ADMIN, polls list 5s / detail 3s** are in place for TB v2.1 internal tracking. Design: [`../executespec-docs/backend/plans/benchmark-closed-loop-2026-06-09.md`](../executespec-docs/backend/plans/benchmark-closed-loop-2026-06-09.md). RL-2026-06.
- **Engine extraction Phase 5+6 — `ToolOutputCompactor` + prompt-cache discipline (`feat/benchmark-codegen-harness`, 2026-05-28)** — bulky worker tool output (`readFile` / `searchFiles` / `listFiles` / `shell` / `buildProject` / `runTests`, > 3 KB) is routed through the LIGHTWEIGHT tier (Haiku / Flash) before being appended to the WORKHORSE worker's history; only the summary lands in the decision context. Mirrors Anthropic's Claude Code CLI internal pattern. Skip rules: tool in `{writeFile, editFile, gitCommit, completeTask, gitDiff, gitStatus}` (already short/actionable), most-recent-tool-call (preserve verbatim), output < 3 KB. Fail-safe — any LLM error → keep raw. Default OFF behind `worker.tool-compactor.enabled` (V216 seeds `false`), so production runs are byte-identical until eval data justifies flipping. New `tool/ToolOutputCompactor.java` + `prompts/tool-output-compactor.md` + `application.yml agent-tier-map ToolOutputCompactor: LIGHTWEIGHT`. WorkerAgent integration: post-`ToolResult.Ok` content flows through `maybeCompact` before envelope-ing (null-guarded so existing unit tests pass `null` for the dependency). RL-2026-05-052.
- **Native DeepSeek client + Qwen3-Coder via OpenRouter (`feat/benchmark-codegen-harness`, 2026-05-28)** — adds `DEEPSEEK` to `LlmProvider` enum + `callDeepSeek` dispatch case in `LlmRouter` (reuses `OpenAiNativeToolClient` plumbing with per-call base-URL pointing at `api.deepseek.com`). Eliminates the ~3-4× routing overhead vs the existing OpenRouter path for the same `deepseek-v4-pro` model — measured 18-min/instance avg on the 2026-05-28 mixed10 eval via OpenRouter vs Anthropic's native ~8-min. Qwen3-Coder added to `OpenRouterModelMapper` allow-list (`qwen3-coder` → `qwen/qwen3-coder`) — quality tied Gemini at 3/10 RESOLVED on mixed10, but at the OpenRouter latency penalty. Native Qwen client deferred. `application.yml`: `spring.ai.deepseek.{base-url,api-key}` plus native DeepSeek V4 rows; pricing refreshed 2026-07-02 from DeepSeek docs (`deepseek-v4-pro-native` $0.435 cache-miss in / $0.003625 cache-hit in / $0.87 out per 1M; Flash $0.14 / $0.0028 / $0.28). RL-2026-05-052.
- **BUILD-mode TDD-iteration tool — `runWorkspaceTests` (`feat/benchmark-codegen-harness`, 2026-05-28)** — new worker tool wrapping `WorkspaceTestRunner` (resolver-detected pytest / mvn / gradle / npm / cargo / go), exposed so BUILD-mode workers can validate their own tests in-loop before `completeTask` rather than only learning about test failures in the post-completion scoring phase. Distinct from `runTests` (pytest-only, PATCH-mode shaped — takes specific failing-test ids). Flag-gated `worker.build-tdd.enabled` (V217, default OFF) — when off, the tool returns an informative `{available: false}` Ok envelope so the worker can learn it's unusable on this run; when on, runs the whole suite and returns parsed pass/fail counts + verified flag. Goal: +8-12pp on SWE-bench-like sets (largest of the TRANCHE 3 quality levers from the 2026-05-28 roadmap). 6 unit tests pin the gate + envelope shape. RL-2026-05-052. **2026-05-31 (RL-2026-05-059) — WIRED + ON for dev:** `runWorkspaceTests` is auto-bound (`@Component implements Tool`) but was unmentioned in the worker prompt, so the worker never called it. The worker `VERIFY` directive (`SequentialExecutor.SYSTEM_PROMPT_BASE`) now CALLS it before `completeTask` and fixes any test the change broke; **both `worker.build-tdd.enabled` (V217) AND `build.test-exec-gate.enabled` (V209) flipped ON for dev** (prod defaults stay OFF → byte-identical). This is the verification-in-loop lever for the _wrong-half-fix_ class that dominates DeepSeek/Gemini/GPT residuals (0 no-edits each; 22–26 real-but-wrong source patches). Round-2 eval is the owed no-regression gate before flipping defaults. **⚠️ 2026-06-01 (RL-2026-05-061) — REVERTED: `runWorkspaceTests`-in-prompt DROPPED + V217/V209 back OFF.** The tool in native-DeepSeek's schema caused reasoning-only no-edit stalls (astropy-14182: verification ON = 0 B no-edit → OFF = 1079 B edit). The GPT win instead came from a symptom-anchored rewrite of `SYSTEM_PROMPT_BASE` points 2+4 (reproduce with the issue's verbatim MWE; "if your edit didn't change the output you're in the wrong place"; match the issue's stated contract) — generic, no new tool, GPT 0/11 → 4/11 on the addressable gap (control: ~3 reliable on re-run + **0 regression** on every provider's passing set → GPT ~19–20; net full-50 still owed). See RL-2026-05-061 for the SWE-bench eval-improvement track.
- **CLI + VS Code clients — ✅ SPLIT OUT to the standalone `executespec-cli` repo (2026-06-07, RL-2026-06-014).** The entire `clients/` workspace (`@executespec/core` + `cli` + `vscode`) **and its `Clients` CI workflow moved out** so the clients version/release independently; **only the server-side OAuth device-login fix and the API endpoints they consume remain in THIS repo.** The history below is retained for context. Originally (`claude/modest-sagan-6Tge7`, 2026-05-26) — thin clients on a **local hands / remote brain** model: the server-side agent loop is unchanged but tool execution is proxied to the user's machine (code + artifacts stay local; nothing published by default). **PAT auth landed** (V215 `personal_access_token`; `POST/GET/DELETE /api/auth/me/tokens`) — purely additive, JWT path untouched. **Node/TS shared core landed** (`clients/core`: typed REST/SSE client, PAT token store, SSE consumer with scoped-token reconnect, local `ToolRuntime` mirroring the server `tool/*` contract). **Backend tool-proxy landed dormant** (`RemoteToolDispatcher` + `RemoteToolController` `POST /api/runs/{id}/tool-results` + `RunEvent.toolCall` + the null/`isRemote`-guarded `ToolRegistry` branch) — inert until a run registers a session, so the default pipeline is byte-identical. **CLI landed** (`clients/cli` — `@executespec/cli`, `executespec`/`es` bins: auth + run submit/watch/list/view/approve/reject/cancel/report + experimental local-hands `run local`). **VS Code extension landed** (`clients/vscode` — native MVP: SecretStorage PAT auth, Runs TreeView, Submit Spec, live OutputChannel stream, plan-gate Approve/Reject modal, **Cancel Run (inline Runs-view action + palette) + cancellable watch** (RL-2026-06-007); esbuild-bundled). Remaining, all flag-gated `LOCAL` mode with zero impact on the default path: the `RunOrchestrator` activation wiring (register/unregister + skip clone + skip auto-push), a rich run-detail webview, and the token-management web UI. RL-2026-05-043. **NOTE (2026-05-30):** `clients/core` + `clients/cli` now **build + run clean locally** (`npm install` + `tsc`; `executespec`/`es` verified via `npm link`) — the original reasoned-not-compiled caveat is cleared for those two (and the VS Code esbuild bundle now builds locally too — `dist/extension.js` ~31kb, RL-2026-06-007). Each client folder now carries its own `CODEMAP.md` + `ARCHITECTURE.md` (`clients/{cli,vscode}/`), and the folders are **slated to split into individual repos**. Build/verify steps moved with the standalone `executespec-cli` repo. **CLI robustness hardening (2026-06-07, RL-2026-06-005/006, pushed):** stdout/stderr split, SIGINT→server-cancel (exit 130), run-outcome exit codes (0/1/3), `--format text|json|stream-json` (NDJSON), `run submit -` stdin, `run attach` (event-log backfill), PAT pre-flight + enum-flag validation, structured result envelope, `--version` from `package.json`; core gained an `Idempotency-Key` on `submitRun` + a default per-request HTTP timeout, and the **server-side dedupe landed** (`run/RunIdempotencyService` — Redis `run:idem:{userId}:{key}`, 24h, fail-open — consulted by `RunController.submit` _before_ the quota/usage increment, so a retried submit can't duplicate or double-charge a run). The **`run local` shell-permission gate also landed** (`ToolRuntime` is now fail-closed — `shell`/`buildProject`/`runTests`/`gitCommit` denied without an approval policy; `createInteractivePermissions`/`allowAllPermissions` added, RL-2026-06-006); the local-hands handshake is now explicit: `RunController` rejects `localExecution=true` while `worker.local-hands.enabled` is OFF, and accepted submit summaries echo `localExecutionAccepted=true` (V241, RL-2026-06-033). **CLI rich chat TUI (2026-06-07, RL-2026-06-008):** bare `executespec` on a TTY launches an **Ink** (React-for-CLIs) chat UI (`clients/cli/src/tui/chat.tsx` — `<Static>` transcript + live streaming region + status line + `ink-text-input`; Ctrl-C cancels the turn/exits) over `core.ChatSession`/`POST /api/spec/chat`; `--plain` or a non-TTY falls back to the readline REPL. Adds `ink`/`react`/`ink-text-input` deps; tested via `ink-testing-library`. Needs `spec.chat.enabled` (V229) ON for real chat. **Onboarding + chat-experience round (2026-06-07, RL-2026-06-014, not pushed):** (1) the **OAuth device-login path is fixed** — Google/GitHub now carry the `/cli-login` device-auth return URL across the provider round-trip via new `auth/oauth/OAuthRedirectCookieFilter` (a short-lived `SameSite=Lax` `es_oauth_redirect` cookie, `/cli-login`-only) → `OAuthSuccessHandler` (reads+clears, appends `&redirect=` to `/auth/callback`) → `AuthCallbackPage` (honours it); the email+password path already preserved it. Without this, OAuth sign-in from the CLI hung (landed on `/home`, never reached Authorize). `OAuthRedirectCookieFilterTest` (5). Local-only: `app.mail.base-url`→`:8080` in gitignored `application-local.yml` so OAuth lands on the backend-served origin, not Vite `:5173`. (2) **auto-login on first run** (`interactiveChat` in `clients/cli/src/index.ts`) — bare `executespec` on a TTY with no token opens the browser sign-in then enters chat. (3) **inline run streaming** (`chat.tsx`) — a chat-submitted run streams plan/tasks/files/score in the TUI with an inline `[y/N]` gate via `core.streamRunEvents` + a terminal summary (`chat-run-stream.test.mjs`). (4) a **welcome screen** (gradient banner + signed-in line + tips + footer, zero new deps). CLI suite 14/14. Remaining toward a full Gemini/Claude-Code feel: the live local-hands e2e and richer markdown/diff rendering in `executespec-cli`.
  Current endpoint shape (RL-2026-06-040 follow-up): CLI and VS Code chat now prefer `POST /api/agent/sessions/{id}/turns/stream`, which records durable turns and usage in `agent_usage_ledger`; `/api/spec/chat` remains only as a compatibility fallback for older servers. That compatibility turn
no longer runs on the servlet thread (RR-539): `specchat/SpecChatTurnDispatcher` admits it onto a
context-propagating virtual thread under global and per-user concurrency ceilings
(`spec.chat.max-concurrent-turns`, default 8 / `-per-user`, default 2), over-ceiling turns are
refused with HTTP 429, and the emitter carries a 10-minute hard timeout instead of no timeout at
all. **RL-2026-06-035:** when the session carries a `projectId`, `SpecChatService` binds the existing project/code tool surface (`ProjectChatTools`) alongside run-control tools, so common chat can answer grounded project/code questions instead of only submitting or managing runs. Duplicate tool names are filtered; `list_recent_runs` scopes to the linked project when present. **RL-2026-06-036:** provider selection is now cross-surface and account-scoped through `app_user.preferred_ai_provider`; web, CLI, and VS Code update the same preference and absent provider inputs resolve through it. **RL-2026-06-038:** CLI-approved read-only folder scans carry `EXECUTESPEC_LOCAL_CONTEXT: approved_read_only=true`; `SpecChatService` must treat that as real local evidence and must not answer "I can't inspect" when the snapshot is present. **RL-2026-06-039:** `/api/runs/{id}/tool-results` normalizes local-hands tool-result envelopes server-side; malformed client replies become bounded `INTERNAL` errors instead of arbitrary JSON entering the worker context. **RL-2026-06-195:** the web/CLI agent chat read-only surface now also binds `web_search`/`web_fetch` (`specchat/WebSearchChatTools`, SSRF-hardened, results returned `untrusted`-flagged) so it can browse public URLs, and accepts validated file uploads via `POST /api/agent/sessions/{id}/attachments` — `agentsession/AgentAttachmentService` magic-byte/size/extract/injection-validates each PDF/Markdown/text file and stores it sanitized (`AgentAttachmentStore`, Redis 2h) for server-side untrusted-delimited injection through `StreamTurnRequest.attachmentIds`; images are a tracked fast-follow. Confirmed `create_cloud_run` submissions may carry `lowComplexityWorkerStartTier=LIGHTWEIGHT` so agent clients can start LOW tasks cheap without setting a route-pinning `modelName`. `spring.servlet.multipart` is capped at 10/12 MB.
- **Agent control plane + managed AI launch work (2026-06-19, branch `codex/agent-control-plane-managed-ai-plan`)** — unified execution plan lives in [`../executespec-docs/backend/plans/agent-control-plane-managed-ai-2026-06-19.md`](../executespec-docs/backend/plans/agent-control-plane-managed-ai-2026-06-19.md) with the root entrypoint [`AGENT_CONTROL_PLANE_MANAGED_AI_PLAN.md`](AGENT_CONTROL_PLANE_MANAGED_AI_PLAN.md). Scope: make the web Console a first-class agent control plane over the existing agent-session protocol, keep CLI/VS Code as local-hands clients, normalize action contracts (`create_cloud_run` as the public run-creation action), add a reloadable agent-turn history endpoint, wire POST-SSE agent streaming into web, and introduce AI credits (`$1.25 = 100 AI credits`) with per-call managed raw-COGS enforcement, model step-up/down, BYOK bypass, and in-app/email notifications on routing-state transitions. Implemented on this branch so far: `create_cloud_run` public action contract, surface-neutral chat prompt wording, `GET /api/agent/sessions/{id}/turns`, `frontend/src/lib/api/agent.ts` with a POST-SSE parser plus route/proposal handlers, protected `/agent` + `/projects/:id/agent` + `/runs/:id/agent` Console routes with reducer state, action cards, local-hands handoff cards, gate rail, managed-AI balance rail, and WEB capabilities, the RL-2026-06-055 route-gated orchestrator decision for `POST /api/orchestrator/turn` + `/api/agent/sessions/{id}/turns/stream`, the RL-2026-06-057 456-case deterministic routing corpus, the RL-2026-06-058 prompt-eval harness plus confirmed `POST /api/orchestrator/turn/confirm` submission gate, the RL-2026-06-059 web agent control-plane UI, the RL-2026-06-060 managed-AI ledger/services/router/API/UI slice, run/project chat new-send migration onto scoped WEB agent sessions while preserving legacy chat history for read continuity, and the RL-2026-06-196 chat-first web-agent redesign (centered conversation, honest "Waiting for you"/"Running" status, a pinned live run card with phase/cost/progress polled from `getRun`+`getLiveCost`, a settings popover holding the de-jargoned internals, and an auto-titled Chats drawer). Managed-AI state is visible in the web agent, billing page, plan gate, compose/provider-model picker, and org settings effective-policy panel; the launch policy remains YAML-configured rather than admin-editable. Terminal/CLI work is separately grouped and must carry `TERM-AUDIT-*` tags (`TERM-AUDIT-SERVER-CONTRACT`, `TERM-AUDIT-SERVER-LOCAL-HANDS`, `TERM-AUDIT-SERVER-BILLING-VIEW`, `TERM-AUDIT-CLIENT-TUI`, `TERM-AUDIT-CLIENT-LOCAL-ACTIONS`, `TERM-AUDIT-CLIENT-STREAMING`) so it can be audited independently from web and managed-AI work. Remaining code gaps: legacy `/api/spec/chat` compatibility path does not yet expose the structured route decision, live provider prompt-eval execution requires a credentialed SUPER_ADMIN/operator smoke, and managed-AI transition email delivery still needs a deployed Gmail OAuth smoke. Coordinate with the standalone `executespec-cli` session by keeping local-only actions out of WEB capabilities and preserving server contracts rather than editing the split client repo here.
- **Agent-session history reset (2026-07-15)** — `DELETE /api/agent/sessions/{id}/history` is the canonical cross-surface `/clear` contract. It ownership-checks the session, deletes durable events and turns transactionally, retains the session shell and immutable usage ledger, and is idempotent. Web, CLI, and TUI use it with a local fallback only when the server is unavailable.
- **Web Agent remote sandbox operator UX plan + frontend/backend slices (2026-06-29, branch `codex/web-agent-remote-sandbox-ux-plan`)** — root plan lives in [`WEB_AGENT_REMOTE_SANDBOX_OPERATOR_PLAN.md`](../executespec-docs/backend/plans/source-repository/WEB_AGENT_REMOTE_SANDBOX_OPERATOR_PLAN.md). Product direction: Web Agent should become the remote sandbox operator, not a browser clone of the local-hands TUI. CLI/VS Code retain local file/shell authority; WEB remains remote-sandbox, plan-gate, evidence, cost, and PR/deploy control. Landed slices: remote-sandbox launcher empty state, typed sandbox proposal card, local-hands CLI handoff card, richer live sandbox cockpit, inline plan-gate decision card, evidence-first terminal actions, Console active-work banner suppression, operator command-palette entries, a Chats drawer rebuilt as a sandbox operator queue with awaiting-approval grouping, reload recovery that hydrates the pinned sandbox run from durable `AgentTurnResponse.linkedRunId`, and additive `AgentSessionResponse.latestLinkedRun*` backend fields so cross-session drawer rows can show the latest sandbox title/status. The second PDF-remediation pass collapses old transcript dominance into a compact Recent answer disclosure only for settled assistant history, keeps pending user prompts visible, adds a visible pre-run sandbox contract and trust boundary to the launcher/proposal/composer/gate surfaces, upgrades the approval inbox with project/route/age/risk/tasks/credits/estimate/cap evidence, de-jargons settings into Sandbox policy with diagnostics hidden by default, replaces the old AI credit label with Managed AI balance plus explicit not-configured state, makes mobile `/agent` show a labeled Sandbox CTA plus pending-approval pill, fixes scoped route selection so project/run agent routes cannot reuse stale all-project sessions, and splits `SpecChatService` prompts so WEB read-only streaming no longer advertises unavailable legacy run-control tools. Capability questions now bypass provider prose and return a deterministic contract covering linked-context inspection, confirmation-gated remote-run proposals, the plan gate, execution, and evidence. Current gate DTOs still do not expose requester/owner, so the approval queue cannot truthfully display owner until the backend read model adds it. Remaining plan slices: local verification, dev redeploy, and screenshot-backed adversarial persona + competitor UX review.
- **Web semantic parity slice (2026-07-11)** — protected Web agent turns accept PNG/JPEG/GIF/WebP uploads through the existing validated attachment endpoint; the composer shows a local-only thumbnail while `attachmentIds` remain the only request input and the server supplies provider-native media bytes. Route decisions are also rendered inline in the transcript with route, confidence, and whether a confirmed run action is available; diagnostics remain in the settings popover.
- **PR #170 (open against `dev`, 2026-05-24)** — codemap/arch regenerate-loop fix (`.executespec/`-only diffs no longer force regen) + Sync connection-leak fix (`syncRepo` is metadata-only + non-`@Transactional`; heavy regen moved to the new explicit `POST /api/projects/{id}/repos/{repoId}/regenerate-architecture`) + repo-card Language/Size/PR-link. RL-2026-05-025 + RL-2026-05-026.
- **dev → main: in sync (2026-05-24)** — the V199→V203 honesty-pass release (PRs #155/#156) and every subsequent fix were promoted to `main` (latest via PR #169); `origin/dev` is 0 commits ahead of `origin/main`. No promotion pending. (Includes the planner over-decomposition relaxation, RL-2026-05-011.)
- **`feature/programbench-benchmark`** — V2/V3 unification pass (88+18 file moves, 25 class renames) + ProgramBench adapter + step-2 wire-in into V2 worker stack. Validated locally across 3 providers (OpenAI / Anthropic / Gemini) on a Python single-shot spec; pipeline healthy. PR open against dev.
- **`feature/swebench-harness`** (this branch, 2026-05-24) — clean-slate **blind** SWE-bench harness (new `benchmark/` package) that runs the REAL production pipeline in **PATCH** run-mode, blind (issue-only spec; hidden tests + gold `test_patch` applied externally at scoring). **Supersedes the PR #88 oracle adapter below** (which injected the gold test into the prompt → non-publishable; its 67%/60% numbers are **discarded**). Also lands two production-relevant fixes: `LlmRouter` bounded same-provider transient retry (RL-2026-05-029) + PATCH test-verification gate scoped by run source (RL-2026-05-028). Empirical (blind, small-N): Lite Hard-5 — Anthropic 4/4, Gemini 4/4, OpenAI 2/4; uplift study ~7% single-shot baseline → 67% with the system. Validated locally on Lite + Live × 3 providers; not merged. RL-2026-05-028 + RL-2026-05-029.
- **`feature/swebench-adapter`** (PR #88, NOT merged — **superseded** by `feature/swebench-harness` above) — SWE-bench adapter + patch-mode pipeline + 4 autofix surfaces + 14 reliability/cost commits (empty-patch kill-chain via Lever A+B, per-provider cost gap fixes, iter-cap calibration, $4→$8 default platform cap, BYOK exemption). Its 67%/60% matrix numbers were measured **oracle-mode** (gold test injected into the prompt) and are **discarded** — the harness branch re-measures blind. The 4 autofix surfaces it introduced are live and unaffected.
- **`feature/hierarchical-planner`** + **`feature/enterprise-readiness-eval`** — earlier in-flight tracks; both functional on their branches; not merged to `dev`/`main`. Operational truth in `../executespec-docs/backend/reason-log/2026-05.md` and per-package `CLAUDE.md` files.
- Mid-task checkpoint replay + per-provider mid-LLM-call abort + cross-JVM workspace shipping via GCS — all scoped + bounded; today's pause is "interrupt + resume-from-disk" semantics. Tracked in [`../executespec-docs/backend/reason-log/2026-05.md`](../executespec-docs/backend/reason-log/2026-05.md).
- V2 batch execution — retired in ExecuteSpec 2.0; BATCH approvals return HTTP 410 while historical V61/V117 data remains read-compatible.

### Known tech debt

**Historical source facts (2026-07-18, V299 checkpoint):** V298 durably staged exact terminal LLM-usage facts for fenced any-JVM materialization; V299 made Code Review and PR Review jobs leased, restart-recoverable and atomically fenced through terminal result/cancellation/failure publication. At the later V350 source checkpoint, benchmark event ingestion serialized per-run sequence allocation and task-attempt idempotency across JVMs, with a database uniqueness backstop. Concurrent active benchmark attempts, timestamps, ETA, terminal failure classification, trajectory location/hash and submission readiness were DB-authoritative and reloadable after backend restart; SSE carried the same progress projection for live consumers. The benchmark bridge distinguished setup failures from ordinary failed rewards and refused to mark incomplete trial sets DONE; benchmark-prior continuation and initial routing remained evidence-gated. Brownfield runs failed admission when a requested linked repository was missing, unlinked, unclonable, or had no source inventory. LOCAL project sources used opaque UUIDv4 identities and expiring connector leases without transmitting a filesystem path; cloud execution rejected them, while `localExecution=true` required the submitting CLI installation to match an ONLINE lease. Validation and generated-test claims used the baseline-to-final product diff. Exact Enterprise provider/model routes were durable and failed closed at dispatch; provider-only routes retained tier-aware models inside the chosen provider. Project-document uploads were admitted from bytes and became DB-authoritative for restart recovery. Project exports were durable jobs; project RAG exposed semantic versus lexical readiness and did not hide a global OpenAI dispatch behind non-OpenAI `OWN_KEYS` policy. Project Chat resolved immutable actor/org/project/role/route/budget/trust policy per turn, bound pure-read tools, and required V292 scoped one-shot authority before supported paid or mutating actions. Cursor-aware event replay, symlink-component rejection, project-write authority, manual-readiness solution binding, linked-repository PR-review access, and governed validation-report sharing were already enforced. These statements describe historical checkpoints, not the current V365 migration tuple.

**Route truth (Adaptive C1 Steps 1-3, 2026-07-19):** cross-provider failover is ENABLED in production
(V266) and was NOT suppressed for controlled workers, so an unpinned SWE-bench task could be served
by a different physical provider while every campaign artifact reported the requested one. Three
corrections landed. (1) `LlmExecutionPolicyFactory.forRun` now computes
`allowProviderFailover = !exact && !ownKeysProviderLock && !isControlledWorkerRun(run)` from the
`pipeline_run.sourceType` `benchmark`/`swebench` prefix — the same discriminator
`CodeGenEngineImpl.requireTestVerification` uses. Controlled runs take a distinct
`route-v3-controlled-worker` policy generation so a cached or resumed context cannot serve the old
permissive policy; `router.failover.enabled` remains the only operator kill switch and the BYOK term
stays a separate leading term. (2) V304 adds server-derived `provider` / `model` /
`route_attribution` (`LEDGER` | `MIXED` | `UNATTRIBUTED`) to `benchmark_task_result`, populated at
`TASK_SCORED` ingest and by `reconcileUsage` from `run_step_usage` via `es_run_id`; a database CHECK
makes a MIXED attempt carrying a single provider unstorable, and the client event contract
deliberately gains no route fields. (3) `BenchmarkGenerationService.GenerateResult` carries the
served route alongside the requested one. `cost/EffectiveRouteProjection` is the shared derivation.
**Proven by tests only** — no live provider campaign has been run, so a zero-divergence rate in
practice is not claimed.

**Submit-time control ordering (2026-07-21, V323):** three controls were persisted AFTER the thing
they control had already started. `RunOrchestrator.SubmitPolicy` now carries all of them across the
submit boundary on the submitting thread (same mechanism V261 used for `localExecutionRequested`)
and they are stamped inside the initial `PipelineRun.builder()`. `userCostCap` was written after
`submitRun` returned, so a fast planner clamped `hard_cap_usd` to the platform cap before the
customer's tighter ceiling existed. The "stage this, do not publish it" consent was a Redis-only key
whose own contract loses the intent on a JVM crash and resumes default auto-push — it is now
`pipeline_run.skip_auto_push`, and the auto-push gate fails CLOSED when the policy row cannot be
read (the legacy Redis key is still honoured for in-flight runs). A project-scoped run's `orgId` is
now derived from the authorized host `Project` returned by `ProjectAccessGuard.assertCanWrite`
rather than the caller's home org, so an external collaborator's run no longer carries a host
`projectId` with a personal `orgId`; the actor remains `createdByUserId`. V323 backfills existing
rows and adds a `NOT VALID` project/org check for operators to validate after the backfill is
confirmed. Follow-up: managed-AI policy should resolve from the run's payer org rather than from the
submitting user now that the payer org is correct. **Addressed by committed backend checkpoint
`13ead3f40` (2026-07-28; backend and named PostgreSQL gates verified locally):**
`pipeline_run.billing_org_id` now preserves the actor's payer org independently from host-tenant
`org_id`; every durable LLM context separately stores `credential_org_id`. Pipeline, Ask, and
preview keep the host org as their isolation scope while credential-policy revalidation and
managed-AI debit use the immutable payer org.

**Execution-gate integrity (2026-07-21, V315):** the plan gate is now the single authoritative
checkpoint before provider spend and publication, and it cannot be disabled by a request field.
`integration/PlanGateBypassPolicy` is the only place that decides bypass authority: `skipApproval`
is honoured solely for server-internal automation surfaces (`finding:*`, inbound integration source
types, operator benchmark harnesses, and the approval-proven `EXECUTION_VIEW_APPROVED` solution
executor), and `POST /api/runs` rejects a customer-supplied `source` that names one of them. Ordinary
REST submits, forks and agent submissions always take the human gate. `POST /api/runs/{id}/fork` now
inherits `executionMode` (the PRODUCTION/PROTOTYPE governance axis) instead of
`approvedExecutionMode` (the REALTIME/BATCH scheduling axis) — the confusion meant every fork
persisted "REALTIME", failed the exact-`PRODUCTION` gate test, and executed unapproved; forks also
preserve the source run's exact repository selection and its (now durable, V315) `tdd_mode`. The
optional fork body accepts `aiProvider`; changing provider clears the inherited planner-model pin
so provider retries reuse the persisted project/repository scope without carrying an incompatible
model identifier across providers. Forks also retain the customer's durable no-auto-push intent and
explicit cost ceiling.
`POST /api/runs/{id}/approve` accepts an optional `planFingerprint` (SHA-256 of the persisted plan
JSON) and rejects a mismatch with 409, because `creditsConfirmed` is a price check, not a plan
identity. V348 records omission as absent/null rather than a successful match; supplied values must
match, and ENFORCED RequirementIR approval requires one. Launch-side: `projectApprovedPlan` runs only after synchronous canonical admission and
gate resolution, writes the V3 approval projection after that authority exists, and fences the V2
run when a concurrent abort prevents the projection; `executeApprovedPlan` (and therefore
`POST /api/executions/{id}/resume`)
requires a durable `planApprovedAt` and a non-terminal launch; terminal status is derived from the
per-solution outcomes rather than unconditionally COMPLETE, and FAILED/CANCELLED joined the
protected terminal set; abort atomically claims cancellation from a non-terminal state and performs
PR-closing compensations only for the caller that won that claim. Boot recovery takes a V315 fenced
lease (`execution_run.recovery_attempts` / `recovering_instance` / `recovery_heartbeat`) so a rolling
deploy no longer re-dispatches every EXECUTING launch on every instance. Persisted repository
selections fail closed on both sides — an unparseable or non-UUID value is an error, never
"every linked repository" — and re-review's task reconstruction no longer maps null/unknown/
non-terminal task rows to success. `PipelineExecutorPool` now exposes three distinct executors so a
LARGE plan's expansion fan-out never blocks on the pool the waiter runs on.

**Atomic plan-gate decision and continuation authority (2026-07-28, V348):** every
customer-facing APPROVE or REJECT and every pre-approval CANCEL now crosses
`PlanGateDecisionService`. It locks run → gate → continuation outbox → optional contract report →
RequirementIR/policy rows, evaluates admission, and writes one immutable canonical
`DecisionEvidenceV1` receipt plus a SHA-256 `DecisionRequestBytesV1` request fingerprint in the
same transaction. The receipt binds the exact plan, continuation, RequirementIR snapshot,
resolution source, actor/effective authority, payer, provider/key provenance, managed-AI,
governance, the run's provider/execution mode, distinct fixed-six-decimal `estimateHighUsd` and
nullable `hardCapUsd`, expected and confirmed credits, and breaking-change outcomes without
credential material. When a contract report requires typed acknowledgement, the canonical receipt
snapshots and cross-binds the report's exact digest, `requiresTypedAck`, acknowledgement timestamp,
acknowledging actor, and exact stored text. New acknowledgements persist uppercase `BREAKING`;
complete pre-V348 acknowledgement tuples remain byte-exact while validity is checked by trimmed,
case-insensitive `BREAKING`. REJECT/CANCEL preserve such tuples with outcome `NOT_EVALUATED`. Exact same-decision
replay returns the stored receipt without re-running mutable policy; an opposite decision conflicts.
A legacy client that omits `planFingerprint` is recorded honestly with no supplied fingerprint and
`planFingerprintMatched=null`, never as a match; supplied fingerprints must match, and ENFORCED
RequirementIR approval requires one.
APPROVE atomically moves gate `PENDING → APPROVED`, run `AWAITING_APPROVAL → APPROVAL_RESUMING`,
and outbox `WAITING → READY`. REJECT and pre-approval CANCEL are distinct decisions and atomically
cancel the run/outbox; cancellation after approval preserves the immutable APPROVE receipt.

The 400-line transaction coordinator delegates the exact 73-field receipt plus replay verification
to package-private `PlanGateDecisionEvidence` (478 lines), and locked policy/credential/governance/
acknowledgement evaluation to `PlanGateDecisionAdmission` (266 lines). This keeps each production
class below the 600-line ceiling without moving transaction ownership or lock ordering. Its focused
compile, receipt, request, service and architecture cohort passes 35/35. C03C is now `VERIFIED`:
all six independent PostgreSQL classes pass 73/73 (12+32+18+5+5+1), the
active-registry/continuation/dispatcher cohort passes 31/31, architecture ceilings pass, and clean
Java 21 `mvn clean verify -q` passes 5,630/5,630 with zero failures, errors, or skips.

`PlanGateResumeDispatcher` consumes only durable READY/expired PROCESSING rows under a fenced lease,
validates schema/digests and cross-row identity, runs a leased transactional preflight before any
workspace restore, keeps the lease alive through bounded restore, then repeats the identity fence
while atomically handing off run `APPROVAL_RESUMING → WORKING` and outbox
`PROCESSING → COMPLETED`. Here COMPLETED means durable handoff to normal WORKING recovery, not run
completion. Before its Future starts, the run carries a process-unique `:plan-gate-queued:` owner;
only the dispatcher can reclaim that queued lane, and it restores/checks the workspace while
renewing the same durable fence. The Future's first instruction transfers ownership atomically to
the normal runtime host, after which any crash is handled only by ordinary WORKING checkpoint
recovery. Executor dispatch occurs only after that commit. An AFTER_COMMIT event is a latency hint;
scheduled/startup sweeps are authoritative, and `StartupRunResumer` no longer submits
APPROVAL_RESUMING or queued COMPLETED handoffs directly. Corrupt, unsupported, stale, cancelled, and exhausted work is
fenced or dead-lettered. Direct deletion cannot erase a versioned gate/outbox receipt or a
referenced/acknowledged contract report while its parent run remains, while deletion of the parent
run still drives the existing retention/GDPR cascade. The report→run FK is deliberately `NOT VALID`
so historical orphan rows do not fail deployment; PostgreSQL enforces it for all new writes.
Paused/crash recovery and continuation dispatch pre-register a `FutureTask` in the active-run map
before giving it to the executor, so an inline/fast execution cannot finish, remove itself, and then
be replaced by a stale handle; submission rejection removes the registration. `requirement.ir.gate.mode` remains `LEGACY`; this checkpoint does not
activate RequirementIR enforcement, change prompts, or call providers.
`PlanGateResumeStore` is 564 lines after extracting the 220-line
`PlanGateResumeIntegrityValidator`; `PlanGateResumeDispatcher` is 475 lines and
`ActiveRunRegistry` is 152 lines. Registry admission fails closed unless its durable ownership
claim updates exactly one row, rolls back the exact new registration on a zero-row claim or
repository exception, restores a replaced prior registration when replacement fails, and fences
stale cleanup from removing a newer Future. This verifies the plan-gate-to-WORKING handoff only;
ordinary post-transfer WORKING tool/provider calls and terminal output generation still lack
independent per-attempt fences.

**Breaking-change approval gate + continuation locality + publication fencing (2026-07-21, V329).**
Four orchestrator-side corrections. (1) **RR-819** — `PlanBreakingChangeReport.requiresTypedAck`
demanded a typed `BREAKING` and a disabled approve button, but nothing in the API, frontend or CLI
consumed it: the classifier ran AFTER the worker wrote the change and its verdict became a MINOR
issue with a `[REQUIRES ACK]` suffix, so consumer-breaking API/schema changes could be approved and
auto-published with no friction and no record. `contracts/gate/PlanContractGateService` now composes
a report while the run parks at `AWAITING_APPROVAL` — which registered contracts this plan's
`touchesFiles` name, and which repositories declare themselves consumers — persists it with a
SHA-256 digest (V329 `plan_contract_gate_report`), and `POST /api/runs/{id}/approve` refuses with
409 until the approver's typed `BREAKING` arrives bound to that exact digest. It asserts INTENT, not
BREAKING-ness: no new content exists at approval time, so the post-run classifier
(`integration/RunBreakingChangeDetector`, extracted from the orchestrator) stays as the second,
tighten-only gate. Composition is gated by the run reaching the plan gate with a project and
selected repositories; a run with no persisted report is `NOT_REQUIRED`. (2) **RR-1693** — post-review
and post-validation continuation built a plain server-side `ExecutionRequest` with no
`localHandsMode`, so the RR-479 defect was reachable through a path that fires automatically.
`maybeActOnPostReviewContinuation` now refuses when `pipeline_run.local_execution_requested` is set.
(3) **RR-826 remainder** — an absent final validation report was being stamped
`PARTIAL / VALIDATION_REPORT_LOW_QUALITY`, which claims a measurement that never happened, and it
fed post-validation continuation, so a persistent report-generation bug would have triggered an
automatic model-upgrade rerun on every run. `PostReviewQualityPolicy.finalVerification` is now
`PASSED | LOW_QUALITY | UNAVAILABLE`; UNAVAILABLE stamps `PARTIAL / VALIDATION_REPORT_UNAVAILABLE`
and does NOT escalate. The RR-826 fail-closed behaviour itself is unchanged. (4) **RR-1689** —
`integration/RunPublicationFence` refuses auto-push from an Execution-View child that the current
solution row no longer names (superseded by a RETRY) or whose solution an operator skipped. It fails
OPEN on repository error and never touches ordinary customer runs.

**RR-819 web frontend (2026-07-21).** The gate is now satisfiable from the UI. `frontend/src/ide/
IdePlan.tsx` fetches `GET /api/runs/{id}/plan-contract-gate` (204 = no gate = unchanged approval
flow), renders `PlanContractGateCard.tsx` with the contract name, type, detected path, declared
consumer repositories and responsible plan steps, disables approve until the literal `BREAKING` is
typed, and echoes `breakingChangeAck` + the fetched report's `breakingChangeReportDigest` on
approve. The two 409s get distinct recoveries, and the report is subject to the same RR-1459
run-identity discipline as the plan. **Still not closed:** CLI/VS Code send no acknowledgement, so a
gated run remains unapprovable from those clients; manual push endpoints do not consult
`RunPublicationFence`; the V278 `localInstallationId` is still not re-checked on retry.

**Solution decision authority + child fencing (2026-07-21, V327).** The Execution-View per-solution
gate had no state machine. `SolutionExecutionService.processDecision` applied SKIP / RETRY / FIX /
ATTEMPT_ANYWAY to a solution in ANY state and merely logged an unknown action, and
`signalManualFixDone` marked any matching solution DONE without proving a prior FIX — so a stale,
repeated or crafted call could skip QUEUED/RUNNING work, mark it complete, retry an already-finished
solution, and resume the parent launch without the human decision the gate represents; two concurrent
RETRYs could each launch a paid child run (RR-530). Each action is now an atomic conditional UPDATE
(`ExecutionSolutionRepository.claimDecision` / `claimManualFixDone`) out of exactly one legal prior
state — `FAILED` for the four decisions, and the new `AWAITING_MANUAL_FIX` state a FIX decision
creates for manual-ready — stamping actor/action/time (V327) inside the same statement, so only the
caller that won performs the JIRA/SSE/resume side effects. `processDecision` returns a
`DecisionOutcome`; `POST /api/executions/{id}/solutions/{solutionId}/decide` and `/manual-ready` now
answer **409** on an illegal or replayed decision and **400** on an unknown action instead of 202.
Separately, a solution whose child V2 run exceeded the 2h poll was recorded FAILED but never
cancelled, so the child — submitted with `skipApproval=true` — kept spending, mutating the workspace
and could open/push a customer PR after the parent was paused, retried or skipped (RR-531). Timeout
now requests idempotent cancellation through `RunLifecycleService.cancel` and waits up to 30s for a
terminal acknowledgement; a child that had actually finished is reconciled to its real terminal
status rather than reported as a timeout, an unconfirmed stop is stated as unconfirmed, and a RETRY
decision fences the superseded child before claiming. **Not closed:** push authority is still not
bound to the current solution attempt generation — that lives in the publication path, not here.

**Route truth (Adaptive C1 Steps 4-7, 2026-07-19):** the per-task poisoned-feedback finding is now
corrected at the task layer. V305 adds `agent_task.effective_provider` / `effective_model` /
`effective_route_attribution`, populated by `RunTokenAttributor.attributeWorker` from the
`run_step_usage` ledger filtered to that task's successful attempts, with a database CHECK making a
MIXED task carrying a single provider unstorable. `agent_task.provider`/`model` KEEP their existing
meaning as the requested, tier-resolved route and were deliberately not redefined; the requested
route is used for the effective columns only when the ledger proves nothing, and the attribution
then stays `UNATTRIBUTED` so an unproven fallback is never mistaken for proof. `RunDetail` gains a
DERIVED (not stored) `effectiveProvider` / `effectiveModel` / `effectiveRouteAttribution` /
`routeDiverged`, and `/api/runs/{id}/cost` `stepCalls[]` gains `modelTier`, `attemptKind`,
`physicalAttemptId`, `parentAttemptId` and `logicalCallId` so a failover is distinguishable from a
same-provider retry. `cost/RunEffectiveRouteService` is the single read seam.

**Which layer carries which route.** REQUESTED / tier-resolved: `TaskOutcome.dispatchedProvider` and
`dispatchedModel` (javadoc corrected — these were falsely documented as "actually used"),
`agent_task.provider`/`model`, `pipeline_run.requested_provider`/`requested_model`/`worker_model`,
`RunDetail.requestedProvider`/`requestedModel`, `benchmark_run`'s client-declared route, and
`model_task_metric.provider`/`model_name`. EFFECTIVE / physically served: `run_step_usage`
(canonical), `agent_task.effective_*` (V305), `benchmark_task_result.provider`/`model`/
`route_attribution` (V304), `GenerateResult.effective*`, and `RunDetail.effective*`.

**Known remaining gap:** `model_task_metric` is still keyed on the REQUESTED route, so
`ModelProviderCandidateService` can still ingest a wrong quality prior. The write key was NOT
switched in this change because re-keying splits the rolling newest-200-per-key window across two
keys — the effective-route key would start below `execution.continuation.outcome-min-sample-tasks`
(default 5) and return no prior at all, while the stale requested-route key keeps serving a wrong
one from retained history. That is a routing-ranking change requiring a measured sample-count check
against real data plus its own candidate-ranking regression pass. Also unproven: containment for
managed-AI FREE-tier locks is systematic (every FREE managed call is forced to
`deepseek-v4-flash-native`), so the requested/served divergence there is by design and high-volume,
not a rare tail.

**Spend-quota correction (2026-07-20, RR-186/225/226/255/321/322/392/557/825):** provider spend is
now bounded by controls that actually stop. `ManagedAiRoutingPolicy.recomputeState` treats the
configured raw-COGS hard stop as an INDEPENDENT tighten-only gate, so a managed tier reaches
EXHAUSTED on EITHER its credit grant or its raw ceiling (a credit top-up lifts only the former).
`ManagedAiTierResolver` no longer raises a user's managed-AI billing tier from an external host
project's tier, so a scoped collaboration entitlement cannot grant uncapped platform-key spend in
the collaborator's own org. `cost/CostCapValues` is the single validated hard-cost-cap type; NaN /
±Infinity / out-of-band values are rejected at `StateStore.setUserCostCap` and at the agent
confirmation gate, closing the `POST /api/orchestrator/turn/confirm` path that could disable the
worker ceiling. `engine/WorkerAgent` evaluates the hard cap on EVERY iteration and no longer makes
the deliberate over-cap Lever-B salvage call. Its normal, salvage, terminal-finalizer, and critic
requests carry the active ceilings into `LlmRouter`, which performs conservative projected-next-call
admission after managed routing and again for an exact failover route so a full-history request is
not knowingly dispatched when that physical route's estimated cost crosses the remaining cap.
This remains an estimate rather than an exact provider-invoice guarantee; unpriced routes retain
observed-spend enforcement and log that projection is unavailable.
`agent/ProviderDispatchAuthorization` is now the real
production boundary: `LlmRouter` and `project/rag/EmbeddingService` both call it instead of the
router re-implementing the kill switch inline and the embedding transport having none.
`executioncontext/LlmExecutionBudgetGate` (V316 `llm_execution_context.reserved_provider_calls`)
evaluates every budget dimension under a pessimistic row lock and commits a provider-call
reservation before transport, making `maxProviderCalls` a hard ceiling under concurrency. Output
tokens are also reserved: the call being admitted uses its actual request `max_tokens`, while any
already-authorized concurrent calls use the worst-case `max-output-tokens` of the largest model the
policy admits. This avoids false first-call rejection for tightly bounded chat turns while keeping
unknown concurrent reservations conservative, so `maxOutputTokens` is a hard concurrent ceiling too; input tokens and
`maxCostUsd` remain serialized-and-reconciled because a call's input count and dollar cost are not
knowable until it returns. The dispatch guard contains an expired-lease / superseded-attempt fence,
but it is **built, not wired**: `LlmExecutionContextService.claim` has no production caller and no
caller sets `LlmRequest.llmExecutionAttempt`, so every real context stays QUEUED with a null lease
and the fence never fires (RR-322/RR-1666). Wiring it live (claim on ownership, heartbeat, propagate
the attempt onto each request, release on terminal, then restrict dispatch to RUNNING) is tracked
open; `executioncontext/CLAUDE.md` records the exact remaining steps. `SubagentProtocolService` reserves against the ROOT session's whole-tree budget rather
than the immediate parent's, so nesting can no longer multiply the advertised 48-turn/$8 ceiling.
`UsageTrackingService.reserveWithinLimits` adds an atomic admit-and-increment primitive; the legacy
`assertWithinLimits` read remains but has no production caller (RUNS admission is managed-AI
credits).

**Spend-admission serialization (2026-07-21, V331, RR-1664/RR-1665).** The live submission gate —
`ManagedAiUsageService.assertCanStartRun` at `POST /api/runs`, not RR-186's caller-less
`assertWithinLimits` — was itself check-then-act: an unlocked read, a recompute, and a throw only if
the state was ALREADY `EXHAUSTED`, with nothing reserved, so N concurrent submissions at the last
credit were all admitted. `billing/service/ManagedAiAdmissionGate` applies the RR-321 shape one layer
up: it evaluates under a pessimistic row lock in a `REQUIRES_NEW` transaction and commits a
`managed_ai_usage_period.reserved_ai_credits` claim before the async pipeline can start. The
reservation is the MINIMUM a spending run consumes (1 credit), not an estimate of its cost, and is
reconciled to `max(reserved, ai_credits_used)` on every later admission so recorded usage subsumes
it and an unspent run leaks nothing — no release path, and therefore no crash leak. The raw-COGS
ceiling is serialized under the same lock but still reconciled after the fact, because a future run's
dollars are not knowable. `authorizeCall` and `authorizeFixedCapabilityCall` (RR-1665) now take the
same row lock `recordSuccessfulCall` already used, so the per-call decision is no longer made on
state a committed sibling has superseded. The persisted routing state stays denominated in RECORDED
usage: a reservation is a claim on headroom, not proof of spend, and must not make an unspent period
look exhausted to the balance and notification surfaces.

**Historical correction (2026-07-21, V309 checkpoint):** V300 adds durable idempotent
failed-run quota compensation; V301 snapshots the human project/org/global role on immutable LLM attempt
intents so later membership edits cannot rewrite historical usage exports. V302 adds the preview-render
bundle tables for the flag-gated `previewrender/` prototype and is treated as permanently occupied even
if that prototype is later discarded. V303 adds durable run-submission idempotency; V304 adds
benchmark per-attempt route attribution; **V327** adds Execution-View solution decision actor/action
evidence (RR-530); V305 adds `agent_task` effective-route columns; V306 adds
preview-render single-flight; V307 adds run sandbox lifecycle; **V308** retypes
`contract_version.inline_content` from jsonb to text (RR-1629 — inline contract bodies are not always
valid JSON, so the jsonb column rejected legitimate content); **V309** gives `file_lock` a run-scoped
composite `(run_id, file_path)` identity (RR-1544 — the prior key let one run's lock collide with
another's). The current source head is V371; the older V299-V309 statements above describe prior source tuples.

**V310/V315/V316/V317 shipped** from the audit-remediation fix wave (billing, execution-gate, spend-quota, scoring); V311–V314 — like 318, 319, 321, 322, 324–326, 328, 330, 332, 333 — were never used and are permanent gaps. Allocate new migrations from the current head forward only. **V320** is the integrations lane of the same
wave: it adds explicit `integration_run.org_id` / `created_by_user_id` ownership columns so
`GET /api/integrations/runs` can be scoped to the caller's org (RR-339) and so an interactive
trigger records the actor that caused it (RR-340). Legacy rows keep a NULL `org_id` and are visible
only to SUPER_ADMIN.

**Inbound integration webhooks fail closed (RR-637, 2026-07-21).** Bitbucket and ServiceNow
previously returned successfully from `verifySignature` when no webhook secret was configured —
"no secret" was treated as "skip verification", so any network caller could submit attacker-authored
PR/incident text as a pipeline run under the configured runner identity. All four inbound adapters
(Jira, Confluence, PagerDuty, Bitbucket, ServiceNow) now reject every request when their
`agent.integrations.<source>.webhook-secret` is missing or blank. **Behaviour change:** any live
Bitbucket/ServiceNow integration running without a configured secret stops working until the secret
is set. Manual Jira/Confluence triggers additionally require ORG_ADMIN, run under the caller's own
user/org rather than the global `run-on-behalf-of-user-id` runner, and always stop at the plan gate
regardless of the adapter's `skip-approval` setting.
SWE-bench and ProgramBench terminal capture now dispatch through the dedicated virtual-thread
benchmark-capture executor instead of self-invoking ineffective `@Async` methods; capture restart
ownership remains open.

**Scoring-verification honesty pass (2026-07-21, V317).** Six places where the product claimed
something was verified when it was not are corrected. (1) The BUILD-mode `WorkspaceTestRunner` now
treats the runner's process exit code as outranking the parsed summary: a suite printing "all
passed" while exiting non-zero (coverage threshold, plugin/compilation error, package failure,
teardown error) is discarded as unproven instead of published as green (RR-283). (2) Surefire/Gradle
report directories are reset before launch and only reports postdating the launch are admitted, so a
command that dies before writing any report cannot have the previous attempt's green XML scored as
its own (RR-284). (3) `V317 validation_report.test_execution_verified` persists that
freshness/provenance token three-valued, and the evidence ledger restores `DETERMINISTIC`/HIGH for
the `tests` and count-derived `executable-verification` rows only on TRUE — `null` remains unproven,
never proven-false (RR-1660, completing RR-1646). (4) `ReviewerAgent` now models build and test
evidence for ONE final workspace generation: the fix-tests pass is followed by a rebuild and a test
rerun whose results are persisted on `ReviewerResult`, and red-or-unprovable concrete tests
tighten the verdict to `UNRESOLVED_ISSUES` (RR-273). (5) `RunChangeEvidenceService` no longer uses a
repository's root commit as a run baseline unless the history is a single commit (RR-287). (6) An
absent final validation report is `UNAVAILABLE`, not a pass: `PostReviewQualityPolicy` stops
returning "not failed" for a null report (RR-826). `SequentialExecutor` also stops promoting a
twice-failed build smoke with zero worker writes to SUCCESS/COMPLETE — it terminalises `PARTIAL`,
because "delivered nothing and verification is red" was being read as "environmental" (RR-262), and
`WorkerAgent`'s terminal verified-pass now requires a structured exit-zero verdict rather than
`{"ok":true}` transport success, which `ShellTool` returns for failing commands by design (RR-258).
**Expect verified scores to move DOWN, never up, apart from proven-test runs whose ledger label
recovers.**

**Release-blocker generated-output audit (2026-07-23).** TDD planning now keeps failing tests and
implementation in one cohesive task instead of emitting mirrored test-only then implementation-only
passes. An admitted partial requirement caps accuracy at 79/C; an admitted missing requirement caps
it at 69/D. The reviewer/accuracy/test-planner prompts explicitly challenge documented default
startup, persistence fixtures that bypass production wiring, cross-operation idempotency collisions,
raw internal error leakage, and repeated migration advancement. These checks were added after a
96/96 self-green generated FastAPI suite failed all four independent acceptance probes.

The 2026-07-24 deployed inventory rerun then reported 97/100 and 55/55 while failing 6/6 independent
acceptance checks: zero and negative reservations were accepted, a caller-controlled customer-id
header was treated as authentication, unauthenticated read/cancel succeeded, cancellation restored
stock for the unauthenticated caller, and startup bypassed Alembic via ORM `create_all`. Accuracy
assessment now explicitly distinguishes a trusted authenticated principal from raw identity input,
checks positive numeric boundaries and negative-stock arithmetic, and requires runtime migration
wiring rather than a migration file alone. Genuinely unverified requirements cap accuracy at 89/B,
render spec evidence WARN, and keep final goal proof UNCERTAIN instead of falsely DONE.

The parallel React/Kanban rerun reported 84 with 81/81 generated tests, yet failed 3/3 independent
functional acceptance checks: a fresh user could not create the first card without seeding demo
data, restored state accepted a board missing two required columns, and restore accepted a blank
title rejected by the interactive form. Its claimed mobile test asserted only invariant DOM order,
and CSS tokens used 30%/40% white on `#1f2228` (about 2.69:1/3.72:1) despite the README claiming
WCAG AA. Planner, expander, worker, reviewer, and accuracy prompts now preserve cross-feature clean-
state journeys, whole-state recovery invariants, and actual CSS/computed/browser evidence across all
provider routes; isolated tests may not encode a regression or substitute DOM shape for visual proof.

The 2026-07-24 live rerun exposed a separate execution deadlock: worker `shell`, `runTests`,
`buildProject`, and final `WorkspaceTestRunner` host/container paths waited for the child before
reading merged output, so verbose pytest/build processes blocked forever in `pipe_write` once the OS
pipe filled. Those paths now share `testgen.BoundedProcess`, which drains concurrently, caps captured
output, enforces a reachable timeout, and destroys the process tree on expiry.

The same rerun proved deployment shutdown ordering was unsafe: orchestration threads kept making
provider/tool calls after Redis and JPA had stopped, so checkpoints and terminal usage writes failed
before systemd killed the old JVM. `PipelineExecutorPool` is now a maximum-phase `SmartLifecycle`:
it rejects/interrupts all orchestration and expansion work before infrastructure destruction, and
the original/resumed pipeline owners recognize that lifecycle stop as a resumable interruption
instead of marking the run FAILED. `@PreDestroy` remains only an idempotent fallback.

The interrupted Java/webhook run also exposed two reporting defects. Exception paths in the original,
resumed, and retry pipelines now call structured termination with `statusReason=INTERNAL_ERROR`
instead of passing that value as an unstructured note through the compatibility overload. Task
`GOAL_DECISION` evidence now reads `RunCostTracker.snapshotTask`; the run-wide snapshot is retained
only for configured total-spend ceilings. The Steps tab must not label cumulative usage as each
later task's cost/token consumption.

A second Java/webhook plan generated after dependency-contract propagation still contradicted itself
before approval: migrations omitted signing-secret columns consumed by auth/tests, production API-key
lookup used SHA-256 while a later fixture specified BCrypt, production HMAC used lowercase hex while
a later concurrency test specified Base64, and lifecycle tests required a status absent from the
schema/enum. The coherence pass previously saw only the first 240 description characters and was
explicitly told not to repair description conflicts. Full/skeleton planning and expansion now put
exact producer/consumer contracts in `hardConstraints`; coherence receives bounded high-signal
contract excerpts from the full descriptions and appends canonical overriding constraints to every
affected task. Model/tool/JSON/no-applicable-patch protocol failures now fail hierarchical planning
closed rather than silently returning the unpatched plan. Spending worker turns on a known-
incoherent approved plan is a process failure.

The Go rate-limiter rerun exposed another token loop: 295 worker/reviewer calls had already submitted
about 12.65M input tokens, including a general reviewer that reached 38 iterations while repeatedly
running a red `buildProject`. The build tool returns a transport-level Ok envelope even when the
child exits non-zero; the worker incorrectly counted that as progress and continually reset the
stagnation watchdog. Build progress now requires the existing structured exit-zero verdict. This is
provider-neutral and applies to BUILD, PATCH, and reviewer consumers of the shared worker loop.

The retained Go artifact passed its generated suite and `go test -race`, but failed 5/5 independent
acceptance probes: concurrent same-key callers produced different decisions and consumed quota
twice; delimiter-concatenated tenant/subject keys collided; 501 idle limiter buckets remained after
clock advancement; cancellation could not reach an in-flight core `Check`; and a raw caller-selected
`X-Tenant-ID` was accepted as authentication. `main` also started eviction on a detached store while
`New(clock)` constructed the store actually used by requests. Shared worker, reviewer, and accuracy
policy now require atomic first-decision persistence, collision-safe structured keys, observable
eviction of the live instance, propagated cancellation, and verified credential-derived tenant
identity. Generated concurrency tests must force the initial-miss race rather than rely on scheduler
luck.

A separate medium FastAPI reservation run was reported as `CLEAN_COMPLETION`, verified score 97,
and 55/55 tests, yet failed 4/4 independent acceptance probes: negative quantity increased stock,
zero quantity created a reservation, a caller-selected `X-Customer-Id` was accepted as
authentication, and default startup called ORM `create_all` rather than the required Alembic
migration. The accuracy assessor's six-file heuristic had omitted the runtime database/startup
path while generated fixtures manually created schema. Single-repo accuracy evidence is now bounded
at 10 files, with three reserved runtime evidence slots; multi-repo evidence uses 8 files per repo
up to 24 total, still constrained by the existing 100K source-character cap.
The generation defect began earlier than scoring: the planner rewrote "authenticated customer" as
an unverified `X-Customer-Id` header and planned fixtures that manually created schema despite an
Alembic requirement. The shared full/skeleton planner security fragment, task expander, coherence
planner, worker, reviewer, and accuracy prompts now all preserve verified-principal identity,
positive numeric domains, and migration-backed default startup/test boundaries. These prompts are
provider-neutral and therefore apply identically to Anthropic, OpenAI, Gemini, DeepSeek,
OpenRouter, and Fireworks routes.

Post-review continuation now opens only for completed, measured red review evidence. A reviewer
exception/timeout remains `REVIEWER_FAILED` and cannot trigger provider spend, because a stronger
coding model cannot repair unavailable verification. When measured review or final-validation
quality does justify continuation, the target selector prefers the latest executable task and skips
documentation-only tail tasks (while retaining a docs-only fallback). This closes the 2026-07-23
failure where a timed-out wiring verifier switched providers and reran `README.md` instead of the
API/service task that contained the defects.

Reviewer evidence is now protocol-complete or failed: any general/wiring pass that does not reach
`completeTask` returns `REVIEW_FAILED` while retaining deterministic build evidence; a salvage or
iteration-limit summary can no longer combine with a narrower clean pass into `CLEAN`. At task
boundaries, a normally completed worker with no failure reason is never classified as provider
breakdown merely because its success text contains broad phrases such as "API errors"; this prevents
green tasks from triggering false cross-provider reruns.

The same audit found a contract-loss boundary in the generation pipeline: exact plan requirements
were persisted but workers saw only planner task paraphrases, and reviewer passes saw neither the
original spec nor the requirement ledger. BUILD workers now receive bounded `ORIGINAL_SPEC` plus
task-scoped `TASK_REQUIREMENTS` evidence; reviewers receive bounded `ORIGINAL_SPEC` plus the full
`PLAN_REQUIREMENTS` ledger. All provider routes share these blocks and the same adversarial
clause-level test directive. Planner full/skeleton/expander/coherence prompts now preserve compound
runtime, idempotency, version, rollback, and error contracts and no longer impose a contradictory
MEDIUM task-count floor. They also preserve forward migration as an ordered/versioned upgrade of an
existing schema instead of accepting initialization, and reject standalone OpenAPI/Swagger/auth/
deploy/dashboard tasks invented from generic "production-quality" wording. Accuracy assessment no
longer lets lexical workspace hits override a
semantic MISSING finding. Python preview discovery scans bounded nested packages and converts
`app/main.py` to `app.main:app` instead of generating a broken `main:app` container command.

The 2026-07-26 difficult Web rerun exposed two additional provider-created contradictions at the
last plan boundary: one literal parameterized GET route carried both item-read and collection-read
semantics, and a monolithic Python module (`models.py`) collided with a marker-only shadow package
(`models/__init__.py` / `models/base.py`). Final normalization now preserves the first exclusive GET
cardinality declaration and removes only a later opposite-cardinality clause; uncertain duplicates
still fail closed. A marker-only package yields to the already established monolithic module and
its owner is moved to the parent package marker. `__init__.py` is intrinsically a marker, but a
same-name `base.py` is removable only when every owning task explicitly establishes that it is an
empty/no-runtime marker; a substantive `models/base.py` (for example SQLAlchemy `DeclarativeBase`)
remains authoritative and leaves the module/package collision fail-closed. Both repairs are
idempotent, stack-neutral, and run
before the strict final contract validator. Reviewer policy also inspects framework-native HTTP and
request-validation handlers, not only a catch-all, across all six provider prompt routes.
The same final boundary now removes a stale `PENDING row ... replay` branch when canonical
`COMPLETED` replay is already present (or rewrites only that state when it is the sole replay
branch). Scope reassignment also refuses to make a task own a `/models/` artifact that the task
itself explicitly classifies as a service-layer file; unresolved layer/path contradictions remain
fail-closed instead of receiving a synthetic owner binding.

**IMPLEMENTER_V1 (2026-07-11):** The guarded local-hands writer seam now has an opt-in bounded model executor. `POST /api/agent/sessions/{id}/writer/execute` runs a WORKHORSE pass over a strict read/search/diff/edit/build/test allowlist, reauthorizes the exact writer reservation before every round and mutation, persists `IMPLEMENTER_CHECKPOINT` fingerprints and a terminal `AgentTurn`, and releases the reservation on success or failure. The feature flag `agent.subagents.implementer.enabled` remains default-off; WEB sessions and shell/git/network authority are rejected.

**Benchmark trajectory checkpoint (2026-07-18):** eligible benchmark SSE is captured outside the
500-event replay cap as bounded, append-only, fsynced, recursively redacted NDJSON and revalidated
before guarded export. The runner independently verifies event count/SHA and stores only a safe
relative reference. Local proof is green; it does not replace deployed restart/browser/harness
evidence, and horizontally scaled JVMs require a shared persistent trajectory root.

> **Full register: [`../executespec-docs/backend/reference/tech-debt.md`](../executespec-docs/backend/reference/tech-debt.md)** (audited 2026-05-24 — backend + frontend, severity-ranked). The highlights below are the items most likely to bite next.

- ✅ ~~`/api/v2/test/**` unauthenticated in prod~~ — **fixed 2026-05-24** (RL-2026-05-027, PR #170). The nine `test/*Controller`s (Planner/Router/Executor/Worker/Reviewer/NativeTools/ToolRegistry/ContextStore/Metrics) are now `SUPER_ADMIN`-gated in `SecurityConfig` (was `permitAll()` — an unauthenticated LLM-pipeline surface). The operator-only `GET /api/v2/test/worker` smoke accepts an optional `runId` query parameter so controlled provider-budget reservations can use an exact run scope; it defaults to `worker-test-run` for compatibility.
- **Provider handoff fixture harness (RL-2026-07-044)** — `POST /api/v2/test/provider-handoff` is local/dev/benchmark-only and `SUPER_ADMIN`-gated. It requires a versioned fixture id, exactly two allowed providers, distinct per-pass reservations, and an explicit handoff trigger; worker passes set `strictProvider=true`, and the fallback is never selected by ordinary YAML failover. The v1 fixture set covers repository localization, edit/test/repair, and deliberate stuck/handoff behavior. This is a reproducible contract only; no provider-quality or uplift claim follows without a separately reconciled live cohort.
- **Repository Intelligence / PlanIR Phase-0 fixture (RI-P0-C01)** —
  `src/test/resources/eval/repository-intelligence/v1/manifest.json` freezes the zero-provider-spend
  source tuple, prompt/tool fingerprints, provider budgets and cross-language Java/TypeScript/Python
  corpus. The four requirement cases cover explicit cross-repository contracts, omission, conflict
  and ambiguity. `RepositoryIntelligenceEvaluationManifestTest` validates containment, required
  case coverage and byte-exact asset hashes; it is deterministic baseline evidence, not an explorer
  quality or generated-code claim.
- **Repository-intelligence persisted evidence checkpoint (uncommitted, 2026-07-28)** — the
  registered native adapter now reads already-persisted CODEMAP and per-repository ARCHITECTURE
  artifacts through the same authorized-handle, current-snapshot, deadline and result-budget
  boundary as native search. CODEMAP evidence is deterministic, confidence 1.0 and unambiguous,
  pinned to the exact handle/source SHA, with model-generated `Purpose` lines excluded and partial
  inventories preserved as partial. ARCHITECTURE evidence remains explicitly model-derived
  (confidence 0.65, ambiguity UNKNOWN) and is accepted only when its source SHA and exact source
  codemap ID are current. Project documents, ADRs, AST relationships, semantic retrieval and all
  planner/worker/reviewer integration remain open; this is not a quality claim.
- **RequirementIR schema-first and atomic-open slices (IR-P1-C01 through C03C-OPEN; enforcement disabled)** —
  `agent/requirement/` owns a separate schema-v1 requirement/acceptance document, deterministic
  exact-source compiler, structural/completeness validator, canonical JSON codec and plan/source/
  contract-bound digest. It preserves exact UTF-16 source spans and USER_STATED/INFERRED
  provenance, retains
  legacy requirement/task IDs through an explicit adapter boundary, and blocks omission,
  ambiguity, conflict, missing task ownership and missing executable acceptance without modifying
  legacy `Plan`/`PlannedTask` JSON. V345 persists immutable exact-plan artifacts, V346 adds the
  binding/admission and continuation-outbox foundation, V347 wires atomic gate opening, and V348
  adds atomic canonical decisions plus leased durable handoff. `requirement.ir.gate.mode` remains
  `LEGACY`; the shadow flag remains OFF and no provider, prompt, UI, or enforcement behavior is
  activated.
- **Benchmark usage reconciliation (RL-2026-07-18-017)** — `POST /api/v2/test/benchmark/{id}/reconcile-usage` is benchmark-profile + SUPER_ADMIN only. It repairs historical tracker projections exclusively from the canonical `run_step_usage` rows linked by each task's `esRunId`; clients cannot supply replacement usage. Score, outcome and task identity remain immutable, and a changed repair appends `USAGE_RECONCILED` while exact replay is a no-op.
- **Deterministic protected-Web QA fixture** — `test/WebQaFixtureController` is `@Profile("web-qa")` only and SUPER_ADMIN-gated at `/api/v2/test/web-fixture`. It creates provider-free terminal success/failure/approval runs, tasks, validation/test evidence, a workspace, Redis SSE replay, and an agent-session action card for current-SHA browser capture; the approval fixture includes the same coherent durable `LEGACY`/`UNBOUND` pending plan-gate row expected from a historical run rather than manufacturing `AWAITING_APPROVAL` without a gate. `DELETE` removes its `web-qa-*` gates/fixtures plus the exact authenticated real-browser session id supplied by the harness. `AgentStreamTurnRunner` emits its bounded scripted token/tool/result stream only for the reserved `web-qa-*` namespace when `web-qa` is the sole active profile and `executespec.live-llm.enabled=false`; local/dev/test, mixed profiles, live dispatch, and ordinary session ids cannot receive fabricated stream evidence. The dedicated `application-web-qa.yml` profile disables live LLM dispatch without activating the local/dev flag initializer; `scripts/run-web-provider-free-functional.sh` runs that profile against disposable PostgreSQL/Redis, supplies a generated test identity without reading a credential file, exercises real protected session creation plus fixture-backed run surfaces, and fails if the before/after feature-flag digest changes. Child processes receive purpose-specific allowlisted environments. Existing local/dev capture callers remain compatible, but only the isolated profile produces `safety.safe=true`. The former provider-free `RECOVERY` transition was removed on 2026-07-19 because it manufactured terminal success without the fenced reviewer/validation lifecycle; restart evidence must now use the real `RunLifecycleService.resumeAfterCrash` path. It is not a customer or production endpoint and must never be enabled outside those QA profiles.
- **God classes** — `RunOrchestrator.java` is at exactly 4,322 LOC — AT its ratchet ceiling with zero headroom; the next added line fails `GodClassLineCeilingTest`; residual backend decomposition is tracked in `pending.md` §E1/I1. `SubagentExecutionService` and `AgentStreamTurnRunner` delegate lifecycle/failure mechanics to focused package collaborators, while `RunMapper` delegates per-agent token projection and `ProjectArchitectureJobService` delegates DB/config-local admission; all remain below the 600-line new-class gate. Frontend debt remains concentrated in the shared DTO registry `frontend/src/lib/api/core.ts` (1,681 LOC after terminal-outcome extraction) and the isolated transport `frontend/src/lib/api/transport.ts` (~191 LOC); `signal/pages/ExtraPages.tsx` is now 377 LOC after the prior split. These remain high-coupling and under-tested areas.
- **Governance truthfulness** — org standards YAML is validated before persistence and a missing configured lint adapter is reported as UNAVAILABLE, producing critical evidence and a PARTIAL status rather than a silent pass. Durable contract/ADR/invariant source ingestion and restart-safe activity projection remain open.
- **Frontend: residual coupling and browser coverage debt** — vendor and route-level code splitting are active (`vite.config.ts` `manualChunks` plus `React.lazy`), and the Vitest suite is enforced in CI (current: 83 files / 533 tests). Remaining debt is concentrated in the shared DTO registry `frontend/src/lib/api/core.ts` (~1,650 LOC), plus the absence of broad end-to-end browser coverage.
- ~~**Bug #1c (router strict-provider bypass)**~~ — **mostly mitigated** (corrected 2026-05-24). `ModelResolver` now gates adaptive routing behind `!strict && featureFlagService.isEnabled("routing.adaptive-routing.enabled")` ([line 84-88](src/main/java/com/navneet/executespec/router/ModelResolver.java)), flag default OFF — so `-Dexecutespec.strict-provider=true` DOES block the switch now. DeepSeek is additionally excluded from adaptive replacement so native-first ordering remains deterministic; explicit provider selection still wins. Residual: non-DeepSeek adaptive switches remain silent when the flag is ON; no per-org opt-out.
- ~~**Planner model sourced from run model, not tier**~~ — FIXED / stale note (RL-2026-05-015). Planner/worker/reviewer all resolve via `ModelResolver` (worker through `SequentialExecutor.tierConfigFor`); YAML-matrix + F3 per-org overrides take effect.
- ~~**10 legacy access-guard sites**~~ — **resolved** (corrected 2026-05-24). `AccessGuardCoverageTest.KNOWN_LEGACY_DRIFT` is now an empty, closed set (35/35 cleared); any new violator fails the build.
- **Run completion DTO truthfulness** — persisted COMPLETE remains an execution/recovery boundary,
  not sufficient customer proof. If any task has durable non-DONE goal evidence, run detail and
  list DTOs expose the task and run as PARTIAL with `TASK_PROOF_INCOMPLETE` without rewriting
  recovery state.
- **Multi-task worker scope (2026-07-24)** — worker evidence omits the full original spec, names
  later tasks/files as reserved future scope, and makes the current task description/constraints
  the sole deliverable. Existing generated code/tests are untrusted intermediate output: later
  security tasks remove contradictory bypasses, default/source-known credentials, and legacy
  fallbacks rather than preserving them for generated-test compatibility.
- **Provider-neutral plan contract closure (2026-07-25)** — exactly one implementation task owns
  canonical IdempotencyRecord persistence and its migration; test-only tasks cannot acquire schema
  ownership. SQLite exact replay uses one `BEGIN IMMEDIATE` serialization point without polling.
  Requirement-derived audit operation values are applied across providers, including one
  `TRANSFER` event per atomic transfer, and an explicit async database scaffold resolves downstream
  session alternatives to `AsyncSession`.
  Premature scaffold-owned “all tables” migrations are deferred to the final domain-schema
  producer so versioned DDL is generated only after prerequisite model contracts exist; order
  lifecycle normalization does not rewrite similarly named columns such as `Inventory.reserved`.
- **Execution View approved-solution dispatch identity (2026-07-28, backend `13ead3f40`; deterministic
  verification green)** — the existing V90
  `execution_solution_run(solution_id, attempt_number)` unique row is now the pre-dispatch durable
  claim. Attempt generation is explicitly `retry_count + 1`, the row reserves the child UUID before
  `RunOrchestrator` starts, and concurrent/crash-recovery callers reuse that same UUID. Reserved
  pipeline identities use a short `persist`/`flush` INSERT transaction so JPA cannot merge over a
  canonical row. A scheduled/startup observer-recovery path reattaches to RUNNING approved-solution
  children and uses the existing launch recovery lease plus a terminal projection claim so only one
  observer publishes terminal side effects. The current implementation is designed to close the old
  submit-then-record and lost-observer windows without adding a migration or moving provider/SCM
  side effects into a database transaction. The dispatch-reservation race/replay PostgreSQL gate
  passes independently, and clean backend verification passes 5,738/5,738.
- **Iteration and finding payer identity (2026-07-28, backend `13ead3f40`; deterministic verification green)** —
  BUILD iterations inherit the source run's host tenant and run policy but capture the current
  iteration actor's organization as the new child payer/provider-policy authority. A losing
  idempotency contender is cancelled and must be durably accounted for before the canonical child
  can be returned. Server-internal finding PATCH submission separately resolves the active finding
  actor and active host project, then stamps host tenant plus actor payer through `SubmitPolicy`.
  Neither path may silently collapse the two organizations for an external collaborator.
- **Current local verification boundary (2026-07-28)** — clean `mvn clean verify -q` passes
  5,738/5,738 with zero failures, errors, or skips. The PostgreSQL gates passed independently for
  V350 migration/immutability, ExecutionSolution dispatch reservation race/replay, external-
  collaborator lifecycle, PlanGateDecisionService (35 tests), PlanGateResumeStore,
  PlanGateDecisionIntegrityMigration, and PlanGatePayerTenantMigration; no aggregate PostgreSQL
  count is claimed. Frontend Vitest passes 83 files / 533 tests, with production build, format
  check, and lint green. This is deterministic control-plane evidence only: no provider was called,
  so generated-code quality remains unproven.
- **Run Ask evidence safety (2026-07-24)** — run, project, and web-agent chat suppress textual
  DSML/XML tool protocol instead of exposing provider internals. Run Ask treats the verified score
  as a summary, never equates a passing configured build with test execution, and applies a
  deterministic last-mile correction when a provider claims `finalBuildPassed` proves pytest/test
  success while the report has no concrete test count.
- **JAVADOC.md is stale** — head reference corrected V202→V208 (2026-05-24), but a full rewrite is still queued; the authoritative surface lives in CLAUDE.md / ARCHITECTURE.md / per-package `CLAUDE.md`.
- **Enterprise-demo terminal/error contract batch (2026-08-02, local branch
  `codex/enterprise-litmus-error-contracts`)** — terminal run DTOs carry one server-owned additive
  `terminalOutcome` projection for Web, email, in-app notifications, and future CLI consumption.
  Controlled limits/policy/validation/cancellation are separate from leak-safe platform failures;
  spend/cap, publication impact, one recovery action, and support code are included when known.
  Run sync attempts durable workspace restore before `WORKSPACE_UNAVAILABLE` (410), reconstructs
  archive-omitted Git metadata from immutable run snapshots plus credential-free project SCM
  coordinates, and reports transient restore faults as retryable `WORKSPACE_RESTORE_FAILED` (503).
  Project
  architecture/document admission returns stable provider/matrix 409/503 contracts before durable
  work or dispatch. Agent streams preserve typed projected-call and turn-budget failures. The
  planner command gate ignores generic framework-list prose while retaining real command
  validation. This is not release evidence until local gates, merge, deploy-SHA verification, and
  live litmus complete.
- **Enterprise-demo evidence-trust batch (2026-08-02, local branch
  `codex/enterprise-litmus-evidence-trust`)** — workspace preparation writes generated/vendor
  ignore rules only to repository-local `.git/info/exclude`, so ExecuteSpec does not edit or commit
  a customer's `.gitignore`. Review scoring derives changed production/test files from exact JGit
  status instead of clone timestamps. Read-only reviewer passes can request deterministic file
  line/byte metadata and may invoke `buildProject` only once, preventing estimated line-count
  claims and repeated build-only retries. V371 separately persists skipped-test counts; a fresh,
  exit-zero suite with at least one pass and `passed + skipped == total` may satisfy publication,
  while all-skipped, malformed, failed, errored, or unverified suites remain blocked. Active result
  URLs route back to the live run surface until terminal evidence exists, and strict sandbox command
  routing rejects new `backgroundJob` starts so they cannot bypass container execution through a
  host process while preserving status/log/kill authority for jobs started before a policy
  transition. Onboarding sandbox provisioning now locks the user row and reuses only an ACTIVE,
  non-deleted sandbox owned by the same user/org; archived, missing, ordinary, or foreign project
  pointers are not reported or reused, and cleanup refuses to archive a sandbox while any
  non-terminal or unknown-status run still references it. The local strict-container smoke proves basic non-root,
  network/socket/host-path, capability, cgroup, noexec, metadata, and toolchain behavior, but cold
  dependency resolution remains unavailable under `--network none`. These are local changes only
  until deterministic gates, merge, exact deployed-SHA proof,
  the sandbox matrix, and the frozen-repository live cohort are complete.
