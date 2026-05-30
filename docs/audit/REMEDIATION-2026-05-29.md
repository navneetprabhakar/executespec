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

## 2. Deferred — with rationale

These are genuinely out of scope to complete (or to complete *safely*) in this pass. Each notes why + a concrete next step.

- **A1 — flip the 4 dormant worker flags (`worker.tool-compactor` V216 / `worker.build-tdd` V217 / `worker.provider-prompt-tuning` V219 / `worker.cross-provider-fallback` V220).** These are *eval-gated by design* — flipping blind is the opposite of the intent. The flip-safety prep (provider routing) + the context-compaction half (A2, `WorkerHistoryCompactor`) shipped. **Next:** run the blind `benchmark/` harness per-flag then combined on `dev` (needs LLM budget + keys) and promote the winners. This is the single highest-leverage accuracy move and it's *validation*, not building.
- **B1 — `harness.v2.batch_enabled` (≈50% cost).** The `batch/` package is an unbuilt scaffold (every method throws `UnsupportedOperationException`, 0 client beans). This is *build + validate against real provider batch APIs*, not a flip — can't be built/validated without provider access. **Next:** scope the provider batch-API client beans; the 50% LLM-bill saving justifies a dedicated effort.
- **B3 — native DeepSeek client.** Written on `feat/benchmark-codegen-harness`; awaits the user's local DeepSeek key for smoke. Not a code task here.
- **B5 — launch-status polling → events.** Feasible (the `PipelineEvent.RunCompleted`/`RunFailed` bus exists, used by 3 listeners) but targets the **flag-OFF** V3 Launch lane (`executionViewEnabled=false`), so the wasted per-launch thread costs ~nothing in prod today. A correct swap must verify event publish-completeness across all 5 V2-terminal statuses (COMPLETE/PARTIAL/FAILED/CANCELLED/ABORTED) + keep the boot-resume poll as a fallback + make `mirrorV2Terminal` idempotent. **Next:** add `@EventListener`s in `ExecutionOrchestrator` keyed by `pipelineRunId` that call `mirrorV2Terminal`, retaining `pollAndMirrorV2` only as the boot-resume/cross-JVM fallback.
- **B2 (part 1) — `FeatureAssessmentService` direct Anthropic call.** Latent (the LLM path is flag-OFF `assessment.llm.enabled`) and `assess(projectId,…)` carries no `runId`, so a cost-tracked route needs a `runId` threaded from `RunOrchestrator` — half-fixing risks the flag-on path. Documented; low urgency.
- **B4 (residual) — state-analytics heap scans.** `referral.recordInvite` is now **fixed** (indexed `findByEmail` instead of a full user-table scan, commit on this branch). The remaining `StateStore` + billing `AnalyticsService` admin-analytics `findAll`-then-filter sites are admin-only / low-traffic and their conversions carry aggregation-correctness risk, so they stay documented rather than converted blind.
- **A5 (residual) — `CodeGenEngineImpl` has no unit test.** It's a thin Phase-2 facade over `PatchAgent`; its coverage is integration-level (the benchmark harness), not a unit target.
- **C3 — tenant ORM read-isolation.** A Hibernate `@Filter`/row-level retrofit across ~78 entities is high-risk (can silently break queries); the finding itself offers "treat C2 as the hard requirement" — which this pass did (C2 closed the known app-layer blind spots). A safe ORM-filter rollout needs a dedicated, heavily-tested effort.
- **God-class splits (`RunOrchestrator` ~4.8k LOC, `lib/api.ts` 3.7k).** Large mechanical refactors; high regression surface; deferred (the prior `fix/audit-remediation` also deferred these).
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
