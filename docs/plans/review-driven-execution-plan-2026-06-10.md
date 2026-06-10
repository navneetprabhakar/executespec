# Review-Driven Execution Plan

Date: 2026-06-10

Source of truth: the `review/` folder only. This plan intentionally does not use `CLAUDE.md`, `docs/`, source code, or memory as authority. The ordering below is derived from the review documents and checkpoint files in `review/`.

## Inputs Read

- `review/02-tech-stack.md`
- `review/07-product-and-positioning.md`
- `review/08-models-strategy.md`
- `review/09-model-matrix-consistency-plan.md`
- `review/10-aggregator-architecture-openrouter-fireworks.md`
- `review/11-ux-review.md`
- `review/checkpoints/PROGRESS.md`
- `review/checkpoints/models-eval-evidence.md`
- `review/checkpoints/tb-sweep-plan.md`
- `review/checkpoints/ux-live-notes.md`
- `review/checkpoints/be-core-agents.md`
- `review/checkpoints/be-worker-engine.md`
- `review/checkpoints/be-orchestration.md`
- `review/checkpoints/be-tools-reviewer.md`
- `review/checkpoints/be-router-cost.md`
- `review/checkpoints/be-state-exec.md`
- `review/checkpoints/be-project-intel.md`
- `review/checkpoints/be-deployment.md`
- `review/checkpoints/be-billing.md`
- `review/checkpoints/be-governance.md`
- `review/checkpoints/be-bench-eval.md`
- `review/checkpoints/be-api-platform.md`
- `review/checkpoints/infra-build.md`
- `review/checkpoints/fe-ide.md`
- `review/checkpoints/fe-signal-console.md`
- `review/checkpoints/fe-lib-stores.md`
- `review/checkpoints/infra-prompts.md`

## Execution Principles

1. Fix trust boundaries before adding surface area: cancellation, money, authorization, shell safety, scoring, and cost accounting come before UX polish or model expansion.
2. Keep the product kernel explicit: spec -> plan gate -> execution -> review -> verified score -> PR/autofix/project workbench/benchmark.
3. Freeze or delete dormant lanes before refactoring around them. The review repeatedly flags dead execution, validator, prompt, preview, governance, and frontend surfaces.
4. Make verified score and cost truth reliable before publishing benchmark or model claims.
5. Fix frontend transport/auth state before screen polish. The review shows several UI defects are data-layer or route/proxy issues.
6. Keep model matrix, Fireworks, OpenRouter/aggregator strategy, provider display, and tier ladder changes for the final phase.

## Phase 0 - Lock the Review Baseline

Goal: preserve the review packet as the operating record and prevent unreviewed provider/model work from bleeding into earlier stabilization.

Work:

- Treat `review/` as the frozen audit evidence for this execution cycle.
- Create a tracker from this plan with one item per phase and explicit acceptance gates.
- Identify any already-present Fireworks, Mimo, model-matrix, or provider-routing changes and quarantine them until Phase 9.
- Do not merge, expand, or expose model/provider changes while P0 correctness work is open.

Acceptance:

- A single execution tracker exists.
- Any in-flight model/provider deltas are labelled as Phase 9 work.
- No source-of-truth drift from outside `review/` is introduced into this plan.

## Phase 1 - P0 Correctness: Runs, Money, Security

Goal: close defects that can corrupt run state, charge/refund incorrectly, leak tenant data, or allow unsafe execution.

### 1.1 Run Lifecycle and Terminal State

Fix:

- `terminate(startedAt = null)` NPE that can skip terminal events.
- Cancel/pause being overwritten by later blind phase writes, allowing cancelled runs to complete, push PRs, or miss refunds.
- Crash resume of production planning runs skipping approval/credit gates.
- `RunControlPort` terminal path dropping status reason.
- PATCH-mode pipeline bypassing cancel guards, cost finalization, SSE close, and metrics close.
- Retry-failed path not persisting terminal task status.
- Active-run registration race and check-then-act races on resume/retry.

Tests:

- Cancelled run cannot transition to complete/partial or push.
- Paused run cannot be clobbered by worker/reviewer/validation writes.
- Planning crash resume returns to the gate, not direct execution.
- PATCH terminal path writes final cost, closes SSE, and emits metrics.
- Retry failure leaves no task stranded in `WORKING`.

### 1.2 Billing and Payment Integrity

Fix:

- Stripe activation chain storing checkout session id while webhook lookup expects subscription id.
- Credit charge/refund asymmetry where submit charges one credit and refund can reverse `creditsCharged`.
- Payment-history cross-org disclosure.
- Billing page `0 runs / $0` root cause: wrong admin endpoint, lowercase org id, case-sensitive backend, and user branch excluding org id.
- Stripe cancel behavior: implementation cancels immediately while copy implies period-end.
- Upgrade flow cancelling old subscription before new payment succeeds.
- Seat mutation endpoint changing seats without billing.
- FREE/top-up/bonus run accounting contradictions.
- SUNSETTING tier behavior for paid subscriptions.

Tests:

- Checkout -> webhook -> tier activation succeeds.
- Failed run refund reverses only the amount charged for that run.
- Payment history is org-scoped.
- Normal user billing page reads non-admin, org-scoped data.
- Upgrade failure leaves prior subscription intact.

### 1.3 Security and Tenant Isolation

Fix:

- Deployment list/live endpoints leaking deployments across orgs.
- Standards read path accepting arbitrary `orgId`.
- Invite/share-link revoke cross-project IDOR.
- Project repo/template services using org-only checks where project membership matters.
- Project chat mutations missing write gates.
- Shell sandbox newline-separated command bypass.
- V2 write path bypassing code abuse scanning.
- PAT leak in clone failure output.
- Rate limiting using rightmost `X-Forwarded-For` and creating a global unauthenticated bucket behind proxies.
- TEAM rate limit falling through to FREE limits.

Tests:

- Cross-org users cannot list, live-view, revoke, mutate, or read restricted data.
- Newline command payload is segmented and blocked correctly.
- Worker writes are abuse-scanned before persistence.
- Proxy IP parsing is deterministic and safe.
- TEAM limits differ from FREE limits.

### 1.4 Feature Flag Safety

Fix:

- Feature flag reload clearing the cache non-atomically and creating a temporary all-flags-false window.
- Flagged worker tools being bound into model schema while inert, where the review flags schema-stall risk.

Tests:

- Flag reload swaps atomically.
- Disabled tools are absent from schemas, not merely inert, where the review calls for that behavior.

Exit Gate:

- No known P0 run-state, billing, authz, shell-safety, or rate-limit finding from the review remains untriaged.
- Targeted tests cover every fixed P0 path.

## Phase 2 - Score, Cost, Events, and State Truth

Goal: make the platform's stated outcomes reliable before benchmark or product claims depend on them.

### 2.1 Verified Score and Test Execution

Fix:

- Verified-score backfill parsing the wrong JSON shape and applying the wrong formula.
- Case-sensitivity drift in scoring.
- Workspace test runner wait/read deadlock.
- Maven/Gradle parsing that does not produce reliable pass/fail counts.
- Accuracy assessment grep using quoted Java regex semantics in a way that prevents the safety net from demoting.

Tests:

- Backfill parses current report shape.
- Score formula matches the intended scoring version.
- Maven, Gradle, pytest, and failure-output samples produce deterministic parsed counts.
- Accuracy demotion triggers on known missing-output cases.

### 2.2 Cost Accounting

Fix:

- OpenRouter-only models recording `$0` actual cost because pricing lookup uses the wrong provider key.
- Cached input double-billing for Claude via OpenRouter.
- Classifier LLM cost being orphaned under a synthetic run id.
- Run cost tracker gaps on PATCH finalization.
- Direct LLM calls or legacy paths that bypass normal router/cost tracking where the review identifies them.

Tests:

- OpenRouter cost records non-zero when pricing exists.
- Cached-input cost is not double-counted.
- Classifier cost attaches to the real run.
- PATCH runs finalize cost snapshots.

### 2.3 Events, Outbox, and Replay

Fix:

- Saga outbox executor self-invocation/transaction issue and `PROCESSING` claim mismatch.
- SSE replay sequence break after 500 events.
- RunEvent constructor argument mixups that put payload into task id.
- Legacy state/event paths that still influence live IDE behavior.

Tests:

- Outbox entry is claimed, processed, and terminally marked.
- SSE replay preserves monotonic event order past 500 events.
- RunEvent DTO serialization puts payload/task fields in the right slots.

Exit Gate:

- A run's terminal state, score, cost, and replay stream agree.
- Benchmark dashboards and public claims are still blocked until Phase 8.

## Phase 3 - Freeze or Delete Dormant Lanes

Goal: reduce the review's "large coat" problem by removing dead or misleading surfaces before deeper refactors.

Work:

- Delete or hard-disable the dormant `validator/` package that shells subprocesses at startup.
- Decide the legacy execution lane fate: freeze, hide, or reroute live tendrils to the real run path.
- Disable or remove UI/actions that depend on dead execution gates, readymade migration, or orphaned migration plan endpoints.
- Delete dead prompt files and fragments identified by the prompt review after live prompt bugs are fixed.
- Remove dead frontend modules, orphaned Signal/console components, unused deps, old scripts, tracked eval results, and committed generated artifacts called out by the review.
- Delete dormant deployment executors/templates only if no live route depends on them.
- Inventory write-dead tables and dormant entities; defer destructive DB drops until the stabilized app has shipped.

Exit Gate:

- Live routes do not call deleted code.
- Dead surfaces no longer show clickable controls that cannot complete.
- Build/test/lint does not rely on orphaned generated artifacts.

## Phase 4 - Worker, Orchestration, and Prompt Contracts

Goal: simplify the execution loop enough that fixes do not fight invisible self-invocation, prompt, or tool-schema traps.

### 4.1 Spring Self-Invocation and Async Boundaries

Fix:

- `persistTaskStatus` annotations not applying due self-invocation.
- `ExecutionOrchestrator` `@Async` self-invocation blocking request threads.
- Project document indexing `@Async` self-invocation running synchronously.
- Benchmark capture loops `@Async` self-invocation blocking 202 responses.
- Seeder and side-effect transaction issues called out in governance/state reviews.

Tests:

- Transactional and async work routes through proxied collaborators.
- HTTP 202 endpoints return promptly while async work continues.

### 4.2 Worker Loop Reliability

Fix:

- Tool-output compaction potentially stripping verification gate markers.
- Cross-provider retry crash when resolver returns null.
- Salvage outcomes being mislabeled.
- Context snapshots leaking on non-happy paths.
- Nudge text referring to `completionNotes` when the complete-task schema does not have that argument.
- Unbounded gate refusals.
- PATCH mode lacking a wall clock.
- Long plan gates and planning fanout sharing one fixed pipeline pool.

Tests:

- Compaction cannot remove required verification status.
- Retry resolver failure fails the task cleanly, not the JVM pipeline.
- Salvage statuses match actual action taken.
- PATCH wall-clock timeout terminates with finalization.
- Gate waits do not occupy the same scarce executor as active worker tasks.

### 4.3 Tool and Reviewer Safety

Fix:

- `runTests` shell execution despite arg-array safety comments and test-id injection risk.
- Background job cleanup not being called.
- Background jobs running host-side even for benchmark/remote contexts if enabled.
- Workspace context losing worktree override.
- Reviewer third pass firing on tool-error build output.
- `commitWorkspaceOutputs` and subprocess helpers hanging due unconsumed pipes.

Tests:

- Tool args are passed without shell interpolation where expected.
- Job registry cleanup runs at run end.
- Reviewer does not treat tool infrastructure failure as a normal build failure.
- Subprocess helpers drain stdout/stderr safely.

### 4.4 Prompt Contract Repairs

Fix:

- `PR_REVIEW_COMMENTS` being silently discarded or blank.
- `execution_planner` asking for a nonexistent tool while caller expects JSON.
- Patch-agent prompt telling the model to use shell tests when the finding gate requires `runTests`.
- Critical live prompts missing from prompt verification.
- Worker base prompt living outside prompt governance; externalize without behavior drift.
- Duplicate prompt blocks and fragile injection guards.

Defer:

- Provider-specific prompt strategy changes that depend on model/provider lanes. Those belong in Phase 9.

Exit Gate:

- Worker, reviewer, and prompt contract tests cover the corrected schemas and outputs.
- No enabled prompt asks for a tool the caller does not expose.

## Phase 5 - Project Intelligence and Workbench Correctness

Goal: make project docs, RAG, architecture, and chat trustworthy enough to support the core product kernel.

Fix:

- Uploaded binary docs being corrupted by bytes -> ISO-8859-1 string -> UTF-8 write.
- Generated doc fingerprint column too short.
- LLM/GitHub network calls wrapped in large transactions.
- Missing tier-map for file-purpose summary.
- Run codemap route counts using files/functions rather than real routes.
- Repo sync/stack detection not passing PAT.
- Project ledger `run_number` races.
- Dashboard latest score selecting oldest result.
- Code RAG not evicting deleted files.
- Minified-file skip ineffective.
- Direct model calls in codemap/doc paths bypassing normal routing/cost seams.
- Project chat and project workspace write operations with insufficient membership/write checks.

Tests:

- Binary doc upload round-trips bytes and indexes extracted text.
- Async indexing is actually async.
- Deleted source files disappear from code search after reindex.
- Project members see only permitted data and cannot mutate without write role.
- Dashboard latest score returns latest terminal run.

Exit Gate:

- Project workbench can be used as grounding for planner/chat without silent corruption or cross-tenant leakage.

## Phase 6 - Frontend Transport, Auth, and High-Impact UX

Goal: fix the UI defects that block normal use before visual redesign.

### 6.1 API Client and Auth Store

Fix:

- `req()` spreading options after headers and dropping `Authorization`.
- Duplicate `ValidationResult` interfaces merging into a false type.
- Missing token refresh/401 recovery causing stale tabs to error spam after access-token expiry.
- `isLoading` never set, making protected-route loading states unreachable.
- 200 non-JSON responses throwing raw `SyntaxError`.
- Missing `AbortSignal`/timeouts.
- SSE unauth fallback reconnect loop and execution SSE timer leak.

Tests:

- Authorization survives custom headers.
- 401 refresh retries once and then fails cleanly.
- Non-JSON success returns a controlled error/result.
- SSE stops reconnecting after auth failure.

### 6.2 Route and Screen Blockers

Fix:

- `/api-keys` blank page caused by Vite proxy matching `/api-keys`; proxy should target `/api/`, not every `/api*` prefix.
- Mobile Code tab hiding tree/tabs with no file selector fallback.
- Project code browsing having the same mobile file-selection gap.
- Missing ErrorBoundary.
- Completed-without-score rendering as "In progress".
- REVIEWING status redirect fallthrough.
- Status bucket disagreement across files.
- Undefined `--ide-bg` token.
- Status-bar tier badge contrast and overflow.
- Seat count using stale field/copy.
- CSV export without escaping.
- Notification synthetic popstate behavior.

Tests:

- Route tests for `/api-keys`.
- Mobile and desktop browser checks for run detail Code tab and project code browser.
- Shared status helper tests for terminal/reviewing/score states.
- CSV escaping unit test.

### 6.3 Console, Signal, and Public Surface

Fix:

- Console button styling lost to `.signal-app button` specificity.
- Login flow losing marketing/spec handoff query.
- Public pricing toggle and trial copy that promise unsupported behavior.
- CLI authorize card dark-mode token issue.
- Console home double-counting gate runs and showing archived/deleted projects.
- Legal/trust claims that are inconsistent or overstate implementation.
- Provider/model/internal jargon leaking into user-visible surfaces.
- Raw enum/model/provider names in run result screens.
- Too many run-detail tabs/actions before core actions are clear.

Exit Gate:

- Browser-verified desktop and mobile flows: login, API keys, submit/run detail, code browsing, billing, console home.
- UI no longer exposes misleading paid/trial/legal/model claims.

## Phase 7 - CI, Build, Deployment, and Infra Hygiene

Goal: make regressions visible and deployment failure recoverable.

Fix:

- Re-enable disabled app workflows once the immediate failures are addressed.
- Add a Spring context smoke that is not excluded from the default gate.
- Add workflow concurrency to avoid deploy races.
- Add deploy health check with rollback behavior instead of leaving a broken JAR live.
- Move eslint/npm audit from decorative warning-only toward enforced gates in stages.
- Remove committed generated/vendor artifacts and tracked eval outputs called out by the review.
- Remove literal secrets from tracked dev config and rotate if required.
- Replace weak JWT fallbacks and placeholder preview secrets with explicit required config for non-local profiles.
- Harden GCE/service account/app user where the review flags broad permissions or root execution.

Tests/Gates:

- Default CI runs backend tests, context smoke, frontend build, frontend tests, lint/format.
- Deploy workflow cannot run two overlapping deploys for the same environment.
- Failed health check rolls back or clearly fails before promotion.

Exit Gate:

- Mainline can reject broken context/config/frontend regressions before deploy.

## Phase 8 - Benchmark and Evaluation Reliability

Goal: make benchmark results publishable only after scoring, cost, and execution reliability are fixed.

Fix:

- Benchmark capture `@Async` blocking behavior.
- Cleanroom setup failure not aborting the run and later spending on a junk workspace.
- SWE-bench `/generate` path drifting from `/run` blind/diff-strip behavior.
- Eval-v2 planning using unsubstituted prompt templates.
- Shell/process helper deadlocks on large output.
- Benchmark tracking ingest not concurrency-safe.
- Terminal-Bench remote timeout exceeding Harbor wall-clock budget.
- Remote finalizer/session cleanup leaks.
- Cleanroom/capture duplication across benchmark adapters.
- Hardcoded model matrices in test controllers, without changing the production model matrix yet.

Run after fixes:

- Repeat a small SWE-bench blind sanity set.
- Repeat Terminal-Bench first-20 baseline using the already-reviewed sweep procedure.
- Store benchmark evidence in the tracking layer only after idempotency and cost truth are fixed.

Exit Gate:

- Benchmark dashboard shows internally consistent run, score, cost, model, and artifact data.
- No public benchmark claim depends on a known `$0` cost route, hidden oracle prompt, or failed cleanroom setup.

## Phase 9 - Last: Chinese Models, Model Matrix, Aggregator Routes, and Fireworks

Goal: only after platform truth is stable, make the Chinese/cheap-model strategy real and internally consistent.

This phase is intentionally last because the review documents repeatedly show that model/provider work is currently entangled with inaccurate cost records, half-wired providers, display leakage, route latency, and inconsistent tier vocabularies. The updated model context is that DeepSeek, Qwen, Kimi, GLM, and Mimo are model lanes; OpenRouter and Fireworks are routes/transports, not product-facing providers.

### 9.1 Preconditions

Do not start this phase until:

- Phase 1 P0 defects are closed or explicitly accepted.
- Phase 2 cost and score truth is fixed.
- Phase 8 benchmark capture is reliable enough to compare models and routes.
- Fireworks key, slugs, pricing, and transport behavior are verified in a non-production environment.
- Existing uncommitted Fireworks/Mimo/provider changes are reviewed against the matrix consistency and aggregator documents.

### 9.2 Model-First Route Architecture

Implement:

- Own the LLM client layer explicitly instead of half-using framework abstractions where native clients dominate.
- Treat OpenRouter/Fireworks as internal routes/transport options, not necessarily as customer-facing provider brands.
- Make aggregator choice invisible in user-facing provider/model display unless explicitly needed for operator diagnostics.
- Centralize model id, vendor, chain tier, display label, route transport, pricing key, and API-key source semantics.
- Ensure failover cannot silently move DeepSeek/Qwen/Kimi/GLM/Mimo calls onto unrelated platform keys or different model classes.
- Ensure BYOK behavior is explicit for every route lane and unavailable lanes fail clearly.
- Prefer route-first failover: same model on another route before class-level fallback to a different model.

Acceptance:

- One source of truth maps display model -> route transport -> pricing -> key source.
- A model route cannot record `$0` unless explicitly free.
- A disabled, unkeyed, or unpriced route cannot appear as selectable.

### 9.3 Model Matrix Consistency

Implement:

- Resolve the two tier vocabularies flagged by the review: execution tiers versus product/model tiers.
- Update request validation so surfaced providers/models are accepted consistently.
- Add or update matrix rows only after slug, pricing, context window, max output, key plumbing, and smoke tests pass.
- Add Fireworks route rows only after Fireworks has complete dispatch, pricing, BYOK/key handling, prompt fragment handling if needed, and test coverage.
- Collapse native/aggregator twins into one model identity with multiple routes after compatibility aliases are in place.
- Remove hardcoded model matrices in test controllers or mark them as test fixtures that cannot drift into product truth.
- Validate enterprise per-run pin and org override inputs against the canonical registry.

Acceptance:

- ModelController/API/client all agree on available models, route eligibility, and labels.
- Unknown model ids are rejected.
- Provider enum/count tests align with real supported providers.
- No model row exists without pricing and a successful smoke.

### 9.4 Cheap-Model Strategy and Tier Ladder

Implement:

- Make the tier ladder express the reviewed evidence: cheap/fast models for easy and medium work where they tie flagships, flagship lanes for high-complexity work where evidence supports it.
- Separate "quality tier" from "commercial plan" so pricing pages do not imply unsupported model behavior.
- Promote models based on eval gates, not availability alone.
- Keep direct/low-latency routes for Chinese/open models where aggregator latency is the bottleneck.
- Treat Mimo's slow OpenRouter behavior as route evidence, not proof the model lane is bad.
- Keep DeepSeek native as the first cheap anchor; bring Qwen/Kimi/GLM through verified priced routes only.
- Do not expose raw provider jargon, model ids, or internal tiers in primary product UX.

Acceptance:

- The product can explain model choice in plain user terms.
- Internal routing still records exact provider/model/cost for audit.
- Tier-eval and Terminal-Bench evidence can justify each promoted Chinese/open model lane.

### 9.5 Provider-Specific Prompt Strategy

Implement:

- Split provider prompt fragments by run mode where the review found BUILD/PATCH contradictions.
- Attach provider strategy at the right seam rather than relying on default-off fragments that conflict with base prompts.
- Verify prompt contracts for every provider lane used in the matrix.

Acceptance:

- No active provider fragment contradicts mandatory test/reproduction instructions.
- Prompt verifier covers all active prompts for the promoted provider/model set.

### 9.6 Final Sweep

Run:

- Tier eval for the promoted matrix.
- Terminal-Bench first-20 sweep for selected open/cheap/flagship lanes.
- Small SWE-bench blind sanity run for promoted coding lanes.
- Cost and latency report by route provider and display model.

Publish internally:

- Models that are promoted, experimental, or hidden.
- Cost per resolved benchmark item where measured.
- Latency caveats for aggregator-backed routes.
- Known unsupported BYOK lanes.

Exit Gate:

- Matrix, API, frontend display, pricing, BYOK, failover, eval scripts, and benchmark tracking all agree.
- Model matrix changes are the last functional change set in this execution sequence.

## Suggested PR Sequence

1. P0 run lifecycle and cancel/pause terminal guards.
2. Billing/payment integrity.
3. Tenant isolation, shell safety, rate limit, feature-flag safety.
4. Scoring/test execution/cost truth.
5. Events/outbox/SSE replay.
6. Dead-lane freeze/delete pass.
7. Worker orchestration and prompt contract repairs.
8. Project intelligence and RAG correctness.
9. Frontend API/auth transport fixes.
10. Frontend route/mobile/status/billing UX fixes.
11. Console/public surface cleanup.
12. CI/deploy/infra hardening.
13. Benchmark capture/tracking reliability.
14. Final model matrix/provider/aggregator/Fireworks phase.

## Non-Goals Until Phase 9

- No model matrix expansion.
- No Fireworks production exposure.
- No provider picker redesign.
- No cheap-model tier promotion.
- No public benchmark/model claim based on current model-route costs.
- No aggregator branding decision in product UX.
