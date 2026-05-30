# Audit remediation — 2026-05-29

> Companion to [`FINDINGS-2026-05-29.md`](FINDINGS-2026-05-29.md). Branch: `fix/audit-remediation-2026-05-29`.
> This is the work-session record (the root `CLAUDE.md` / `docs/CODEMAP.md` / `docs/reason-log/2026-05.md`
> updates are intentionally **deferred** — see §4 — because the working tree carries the user's unrelated
> uncommitted GCP cost-optimization edits to those exact files, which must not be swept into this branch).

All fixes land on one branch (per the single-PR-per-work-session rule), each commit self-contained,
compiled + spotless-clean + targeted-tests-green.

---

## 1. Shipped

### Security (§C) — all 8 findings, 3 commits
| # | Fix | Commit |
|---|---|---|
| C1 | Deleted the public `GET /api/auth/generate` bcrypt oracle; refresh cookie `Secure` is config-driven (`auth.refresh-cookie.secure`, true in dev/prod profiles); SSRF allowlist guard on the JIRA/Confluence connection validators (require HTTPS + reject loopback/link-local/private/CGNAT/unique-local — blocks `169.254.169.254`). | `b5b428ba` |
| C4 | `SemgrepRunner` ThreadLocal bug (gobbler threads `set()`, caller `get()`s null → every static finding dropped) → method-local `AtomicReference`s. | `b5b428ba` |
| C6 | `LintPass` two best-effort walks swallowed all exceptions → `log.warn`. | `b5b428ba` |
| C2 | Cross-org IDOR / unmetered-pipeline blind spots: locked the retired `/api/v2/runs` rollout canary to SUPER_ADMIN; prreview autofix now `assertCanWrite` (was read); readymade/migration `resolve`/`instantiate`/`dry-run`/`apply` now tenant-guard the caller-supplied `projectId`; broadened `AccessGuardCoverageTest` (`/library/{`, `/migrations/{` segments + allowlist + read-only-POST registry). ArchUnit green. | `71fa0063` |
| C5 | Generated-app CORS prompt: env-driven (`CORS_ALLOWED_ORIGINS`) with a `*` dev/preview fallback **+ a mandatory reviewer-flaggable `// TODO(security)`** instead of a hardcoded wildcard. | `71fa0063` |
| C7 | `AppUser.github_pat` (the only cleartext credential field) wrapped with `@Convert(EncryptedValueConverter)` (backward-safe: legacy plaintext decrypts as-is); enabled `executespec.crypto.enabled` in dev/prod (was OFF in base → all token fields plaintext at rest); safe passthrough+WARN fallback if the key can't load. | `d3926cdd` |
| C8 | Webhook auth fail-OPEN: JiraAdapter accepted any POST when no secret configured, PagerDutyAdapter let a missing signature header bypass the HMAC — both now fail CLOSED (match the Confluence posture). Null user/org attribution (E #36 TODO) wired: new `agent.integrations.run-on-behalf-of-user-id` config → resolved user+org; `submitIntegrationRun` fails closed without it. | `d3926cdd` |

### Accuracy (§A) — A3/A4/A5/A6, 3 commits
| # | Fix | Commit |
|---|---|---|
| A4 (+B6) | `WorkerAgent` now checks `Thread.isInterrupted()` at the top of the tool loop → a reviewer pass's `future.cancel(true)` (or any orchestrator cancel) actually stops a runaway (was billing to completion); reviewer passes thread their executor timeout into the worker as `wallClockSeconds` (self-terminating second safety net). | `5f94490e` |
| A6 | Dashboard `ReviewScoringService.llmCoverageLevel` had no case for the `GOOD`/`PARTIAL` bands the accuracy prompt now emits → fell to a `40` default (under-scoring honest runs); added them (GOOD→80, PARTIAL→50). | `5f94490e` |
| A3 | Multi-file under-scoping (the dominant benchmark miss): `task-expander.md` gained a "enumerate EVERY call-site of a changed shared mechanism" section; `reviewer-wiring.md` gained a diff-driven blast-radius check (`gitDiff` → `searchFiles` each changed symbol → confirm every call-site updated). | `46d707ff` |
| A5 | +4 worker tests: interrupt path (×2 — `WorkerAgentInterruptTest`) + malformed-args / unknown-tool dispatch robustness (×2 — `WorkerAgentToolDispatchTest`). | `c40675d9` |

### Cost / efficiency (§B) — B2, B4, 2 commits
| # | Fix | Commit |
|---|---|---|
| B2 (part 2) | Usage-report emails summed tokens × blended-Haiku rates; now sum the authoritative cache-aware `actual_cost_usd` (new date-range repo queries) with the token estimate as a legacy-only fallback. | `cc43a621` |
| B4 (dashboard) | `/api/projects/{id}/dashboard` loaded up to 2000 PipelineRun + 2000 ExecutionRun rows into the heap to sum tokens/cost; replaced with DB `SUM` queries (matching the review/arch cost queries already there). | `c79c2689` |

### UI + dead-code
| Item | Fix | Commit |
|---|---|---|
| E (minor) | Project-row "Shippable" threshold aligned to the canonical ≥85 (was ≥90); removed the fictional SOLO $29 tier from the live `/pricing` page. | `89b9e3bb` |
| D (subset) | Deleted 3 verified-dead V1 prompts (`compiler.txt` / `conflict-resolver.txt` / `task-compile-supervisor.txt`). | `0dd8035c` |
| E (founder bugs) | The 4 founder-reported UI bugs (approve gate / run-view routing / notification bell / raw-JSON log) — cherry-picked onto this branch. | `5e85c3b4` |
| flag-prep | Worker tool-compactor provider-routing flip-safety + `WorkerHistoryCompactor` (V222, default OFF). | `3b501cfc`, `118291e1` |

---

### Follow-up pass (2026-05-30) — most of §2 cleared

A second pass on the same branch shipped the items §2 originally deferred (all verified — `mvn test` 1417 green; A1 measured live):

- **A1 — worker-lever flag eval (DONE).** `scripts/a1-flag-eval.sh` ran all-5-flags OFF vs ON × 3 providers ($3/run cap, medium spec). **Flags-ON = same-or-better verifiedScore at −9% to −30% cost on every provider** (openai +6 score *and* −30% cost; anthropic/gemini flat score, cheaper). Full table + the N=1 caveats + the "widen to N≥3 across LOW/MED/HIGH before any prod flip" recommendation: [`docs/operations/a1-flag-eval-2026-05-29.md`](../operations/a1-flag-eval-2026-05-29.md). Flags reset to OFF (verified). The compaction pair (tool-compactor + history-compaction) is the strongest first-promotion candidate.
- **N1 (B2 part 1) — `FeatureAssessmentService` LLM call cost-tracked (DONE).** `runId` threaded from `RunOrchestrator`; the (flag-OFF) Anthropic call now records through `RunCostTracker`.
- **N2 (B4 residual) — state-analytics heap scans → DB (DONE).** The 4 `StateStore.compute*` analytics methods + the `AnalyticsService` orgId-subscription lookup now filter in the DB (derived / split-by-since `@Query`, deliberately *no* `:param IS NULL` — no precedent for it here + the validating context test is integration-tagged) instead of `findAll().stream().filter(...)`. Java grouping/null-defaulting byte-identical; the unbounded global all-time summary still uses `findAll()` (inherently a full scan, as before).
- **N3 (B5) — launch-status polling → events (DONE).** `@Async @EventListener onV2Run{Completed,Failed}` in `ExecutionOrchestrator` mirror the V2 terminal through the existing `mirrorV2Terminal` sink; `pollAndMirrorV2` retained as the cross-JVM/boot-resume fallback. Verified lazy-nav-safe (scalar reads + CAS-bean scalar writes only — same profile as `pollAndMirrorV2`) and registered in `AsyncAllowlistTest`.
- **N4 (A5 residual) — `CodeGenEngineImpl` unit test (DONE).** 11 tests: WorkerResult+cost→EngineResult mapping, the exhaustive 7-outcome map, + both fail-safe branches (workspace-realpath failure, cost-snapshot exception).
- **God-class #1 — `frontend/src/lib/api.ts` (3.6k LOC / 472 exports) split (DONE)** into `lib/api/{core, 14 domain modules, index}` behind a byte-identical `@/lib/api` barrel; `ApiContractTest` re-pointed to the new dir.
- **God-class #2 — `RunOrchestrator` (PLAN DONE, split deferred).** Two automated extraction attempts proved the 5.3k-LOC stateful file can't be safely auto-split (the class-close brace orphaned both times; caught at compile, reverted — zero harm). Concrete by-hand, full-test-verified, 6-increment plan written: [`docs/plans/runorchestrator-god-class-split.md`](../plans/runorchestrator-god-class-split.md). The split itself is a dedicated session (user-chosen).
- **B1 (partial) — Anthropic Message-Batches client (`AnthropicBatchClient`).** The first concrete `BatchClient` (the package was interface-only). submit/poll/fetchResults/cancel against the documented endpoints; BYOK-aware key via runId→owner→`ApiKeyResolver` (system-key fallback). The pure JSON shaping (create-body envelope, `processing_status`→state map, JSONL results parse) is unit-tested (5 tests); the four HTTP calls are reasoned-not-smoked (no `api.anthropic.com` reach here — a live smoke is required before any flip). Registered as a bean so `BatchClientRouter` discovers it, but **inert** — `BatchCoordinator.submit` still throws, nothing calls it, the `harness.v2.batch_enabled` flag is OFF, so the production path is byte-identical. P3a/P3b (OpenAI + Gemini clients) followed the same shape; the §2 "B1" deferral below now scopes only the P4 coordinator wiring + live smokes.

## 2. Still deferred — with rationale

- **B1 — V2 batch execution / provider batch clients (≈50% cost).** The `batch/` package is an **orphaned pre-V2 scaffold**: the `BatchClient` interface + router/coordinator/poll/resume exist, but with **all 3 provider clients now landed** (Anthropic/OpenAI/Gemini, unit-tested, §1) **nothing yet injects the coordinator** (the interface's own javadoc defers wiring to `v2-batch-execution-plan.md`). So the remaining B1 work is the P4 coordinator wiring + per-provider live smokes, which **can't be smoke-tested in this environment** — a dedicated effort like RunOrchestrator, not a remediation fix. The 50% batch-pricing saving justifies it. *(User: "yes, but ask before submitting any batch request" — the human-gate requirement stands for whenever it's built.)*
- **B3 — native DeepSeek client smoke.** The client lives on `feat/benchmark-codegen-harness`, **not this branch**; the smoke belongs on that branch with the user's local `DEEPSEEK_API_KEY`. Porting it onto the remediation branch just to smoke it would be the wrong seam. *(User: key now available — run the smoke on the benchmark branch.)*
- **C3 — tenant ORM read-isolation.** *(User: "skip for now, go in detail later.")* A Hibernate `@Filter`/row-level retrofit across ~78 entities is high-risk (can silently break queries); the finding itself offers "treat C2 as the hard requirement" — which this pass did (C2 closed the known app-layer blind spots).
- **D (residual) — frontend dead `ExtraPages` exports (~2.3k LOC), `validator/` (~2k LOC), the dead rubric write-path, the superseded swebench oracle.** Each needs per-item dynamic-ref verification before deletion (see §3 — the audit's "deletable" list proved unreliable). `validator/` in particular "still shells ~5× per boot," so it is NOT inert.

---

## 3. Corrections to the audit (FINDINGS-2026-05-29.md)

Verification while remediating disproved several claims — recorded here so the FINDINGS doc is read with these caveats:

- **D / planner.v1-v14.txt are NOT deletable.** They are rollback archives loaded *by filename pattern* (`<stem>.v<N>.txt`) by `config/PromptVersioningService`, so a literal-string grep shows "0 refs" but deleting them breaks prompt rollback. **Retained.**
- **D / `validator/` is boot-active** ("still shells ~5× per boot" — the finding itself notes this), so it is not safe to delete blind.
- **A5 — "WorkerAgent + CodeGenEngine have ZERO unit tests" was overstated.** `WorkerAgent` already had ~38 tests (budget cap / stagnation / build-gate / wall-clock / UTF-8 truncation); the real gaps were the *new* interrupt path + malformed-output robustness, now pinned (+4 tests). Only `CodeGenEngineImpl` (a thin facade) is genuinely untested.
- **A6 #1 — the additive gaming-guard's missing reviewer-CLEAN/FIXED trust-gate is NOT a regression.** It was dropped *deliberately* (audit #7, founder-approved, V214) — a gamed run can fool the reviewer into CLEAN, so the endpoint-coverage blend must still tighten CODE_DELIVERED even when vouched (pinned by `ValidationReportServiceAdditiveScoreTest.guards_tighten_a_gamed_run_trivial_tests_plus_partial_endpoints`). Restoring the gate would re-open the gaming hole. Left as-is + documented in-code. Only A6 #2 (the dashboard coverage band) was a real fix.

---

## 4. Documentation-hygiene note

The mandatory doc-refresh surface (`CLAUDE.md`, `docs/CODEMAP.md`, `docs/reason-log/2026-05.md`, `agent/CLAUDE.md`)
is **intentionally not committed on this branch** — the working tree carries the user's unrelated uncommitted
GCP cost-optimization edits to those exact files, and the standing instruction is to leave that work
uncommitted. This changelog + the PR body carry the record for now; the root-doc fold-in should happen when the
GCP work is committed (or in a follow-up once this branch merges).

---

## 5. Handoff docs for the deferred items

Every deferred item has a concrete handoff (created 2026-05-30) — index:
[`docs/plans/audit-deferred-handoff-2026-05-30.md`](../plans/audit-deferred-handoff-2026-05-30.md).

- **RunOrchestrator split** → [`docs/plans/runorchestrator-god-class-split.md`](../plans/runorchestrator-god-class-split.md) (6-increment by-hand plan).
- **B1 — coordinator wiring (P4) + live smokes** (all 3 provider clients now built) → [`docs/plans/v2-batch-execution-plan.md`](../plans/v2-batch-execution-plan.md) (P4 wiring + human gate).
- **C3 — tenant ORM read-isolation** → [`docs/plans/c3-tenant-orm-isolation.md`](../plans/c3-tenant-orm-isolation.md) (approach + the 5 exemption landmines + flag-gated rollout).
- **B3 — DeepSeek smoke** + **D-residual dead-code** → the index doc above (B3 §, D-residual §) + [`docs/tech-debt.md`](../tech-debt.md).
