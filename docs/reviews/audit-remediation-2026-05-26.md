# Audit remediation — top-20 disposition + Critical proposals (2026-05-26)

> Branch `fix/audit-remediation` off `dev`. Remediates `docs/reviews/system-audit-2026-05-26.md`.
> Disposition per the founder's triage: SSO/SCIM **parked** (until customers), Fallback LLM **deprecated**,
> RAG **skipped for now**, intra-org least-privilege **deferred**, spend-ceiling **already UI-exposed**,
> signal pages **should be removed** (IDE-only migration). Big refactors are **scoped, not rushed**.

## Disposition table

> **Batch 2 update (2026-05-26, RL-2026-05-041):** the founder triaged the top risks into a 12-item
> work order. 10 of 12 are now DONE on this branch (full `mvn verify` green — spotless + ~1345
> tests); the two god-object items (#4 backend / #11 frontend) are deferred to a dedicated branch per
> the founder's own "high risk → scoped, not rushed" guidance.

| # | Risk | Disposition |
|---|---|---|
| 1 | 🔴 BUILD path unbenchmarked | **✅ Both (batch 2)** — durability checkpoints give BUILD a multi-node/low-latency execution path (scale half); `scripts/build-eval.sh` + `build-eval-cases/` give it a blind held-out-test BUILD eval (measurement half, harness scaffold + 1 worked case). |
| 2 | 🔴 Mid-run durability | **✅ Fixed (batch 2)** — `GcsTaskCheckpointService` checkpoints the workspace to GCS at each task boundary; `resumeAfterCrash` restores from the latest checkpoint before failing `WORKSPACE_GONE`; credits already refund on that terminal. Flag `durability.task-checkpoint.enabled` (default ON, no-op when GCS unconfigured). 4 unit tests. |
| 3 | 🔴 Concurrency dead code | **✅ Fixed (batch 1)** — deleted `ProviderRunPoolProperties` + `agent.provider-pools` YAML. Founder confirmed deletion is the right fix (no real per-provider control needed now). |
| 4 | 🔴 Intra-org least privilege | **⏭️ Deferred** (revisit later, per founder) |
| 5 | 🟠 `RunOrchestrator` god-object | **🔭 Deferred to dedicated branch (batch 2 decision)** — 4828 LOC; an in-band decomposition alongside 10 other changes is the "rushed" risk the founder flagged. Scoped multi-PR plan below. |
| 6 | 🟠 Enterprise governance OFF | **🔧 Next batch** — flip flags after a v2-eval no-regression gate (not in the founder's 12-item order) |
| 7 | 🟠 Verified-score vouches for AI | **✅ Fixed (batch 2)** — founder-approved A+B+E anti-gaming guards: CODE_DELIVERED linked to deterministic endpoint-coverage intent + TESTS slot blended with coverage quality. Flag `scoring.gaming-guards` (V214, default ON, tighten-only). 2 new pinning tests. |
| 8 | 🟠 SSO/SCIM skeleton | **⏸️ Parked** (until customers, per founder) |
| 9 | 🟠 Cost estimator under-estimates | **✅ Fixed (batch 2)** — skeptical review confirmed a 42% over-the-high miss on a real run (anthropic/high $2.32 vs est $1.64). Root cause = reviewer multi-pass under-modeled; bumped reviewer-pass token bands + added the fix-build third-pass term. Validated by the estimator unit suite. |
| 10 | 🟠 Two LLM routers | **✅ Deprecated (batch 1)** — `FallbackLlmRouter` marked deprecated (no new callers); full migration is the long pole. |
| 11 | 🟠 Incomplete audit trail | **✅ Fixed (batch 2)** — `RunActivityFeedListener` emits run lifecycle (SUBMITTED/PLAN_READY/COMPLETED/FAILED) to the feed; `PullRequestRecordService` emits PR_OPENED/MERGED/CLOSED; `ProjectInviteService` emits MEMBER_ADDED; model-matrix overrides now write an `activity_audit` row (org settings were already audited at 18 sites). |
| 12 | 🟠 RAG fragility | **⏸️ Skipped for now** (revisit once the rest land, per founder) |
| 13 | 🟠 No default spend ceiling | **✅ Acknowledged** — per-run `userCostCap` is UI-exposed at submit; org-level default cap is optional hardening (noted) |
| 14 | 🟡 Dual codemap systems | **✅ Fixed (batch 2)** — `RunCodeMapService.generateForRun` now reuses the project's canonical `ProjectCodeMap` (0 LLM cost) when the run is project-scoped; standalone runs fall through to legacy generation. No schema change. |
| 15 | 🟡 Dead `SupplyChainScanner` | **✅ Fixed (batch 1)** — deleted the unwired interface + Noop impl. |
| 16 | 🟡 Saga branch-delete no-op | **✅ Fixed (batch 2)** — `SideEffectExecutor.compensateGitHubBranch` now calls `deleteBranch` (PAT resolved from the run owner, never stored); `ScmPushService` enqueues `COMPENSATE_GITHUB_BRANCH` when a branch is pushed but PR creation fails. The 30s outbox poller is live. |
| 17 | 🟡 Frontend no safety net | **✅ Fixed (batch 2)** — vitest wired (`vitest.config.ts` + `npm test` + CI step in `lint.yml`); previously-dormant `contrast.test.ts` now runs (18/18, after fixing a real WCAG-AA border violation it caught); `manualChunks` split the 802 KB bundle into app + react-vendor + vendor. |
| 18 | 🟡 God frontend files + dead signal pages | **🔭 Deferred to dedicated branch (with #5)** — `api.ts` (3679 LOC) + `ExtraPages.tsx` shrink + dead-signal-page removal is a careful refactor, not safe to rush in-band. |
| 19 | 🟡 Stale Spring-AI reroute | **✅ Fixed (batch 2)** — removed both OPENAI→ANTHROPIC reroutes + the in-memory `run.aiProvider` mutation. `callWithFallbackTracked` already routes OpenAI via `OpenAiNativeToolClient` (not Spring AI), so the `extra_body` 400 is structurally impossible on that path — the reroute guarded a code path that isn't taken. |
| 20 | 🟡 Over-engineered surface | **📝 Partially** — the batch-1 deletions; broader trimming is opportunistic. |

---

## ✅ Fixed in this PR (safe, validated)

- **#3 — concurrency dead code cleared.** Deleted `config/ProviderRunPoolProperties.java` (zero Java consumers) + the `agent.provider-pools` YAML block. Per-provider concurrency was never enforced (no `Semaphore` in the dispatch path), so this removed a control the config only *implied*. The per-tier `concurrent-run-limit` (a real `TierConfig` field) is unrelated and retained. **Real concurrency limiting is scoped below.**
- **#15 — dead `SupplyChainScanner` removed.** Deleted the `SupplyChainScanner` interface + `NoopSupplyChainScanner` (an advertised "8-layer defense" component with **zero callers** — it never ran). `DependencySecurityPolicy` (used by `ShellSandboxPolicy`) + the live registry-allowlist/audit deps policy are kept.
- **#10 — `FallbackLlmRouter` deprecated.** Javadoc now flags it DEPRECATED with a no-new-callers directive + the migration target (`LlmRouter`); the divergent retry/failover semantics (risk #10) are documented as a *transitional* fork. (No `@Deprecated` annotation yet — it would warn-cascade across the 33 live call sites before the migration lands.)

---

## 📐 Critical #1 — how to measure BUILD, and where

**The gap.** The headline 59%/52% is **PATCH** (existing-repo, SWE-bench). The product's primary path is **BUILD** (greenfield spec→PR), which has **no objective benchmark** — its quality rests on `scripts/v2-eval.sh`'s `accuracyScore`, which is **LLM-judged (self-graded)**. There is no clean external greenfield-project benchmark to borrow (HumanEval/MBPP are function-level; SWE-bench is existing-repo).

**Proposal — an internal *blind, held-out-test* BUILD eval (the BUILD analog of the SWE-bench methodology).**

- **What:** a fixed corpus of greenfield specs, each paired with a **hidden acceptance-test suite** authored by us (not the worker) that encodes the spec's requirements — the BUILD analog of SWE-bench's hidden FAIL_TO_PASS tests.
- **How (the scoring loop):** run the real BUILD pipeline **blind** (spec only) → take the generated workspace → run the **held-out** suite against it → score = **% of held-out acceptance tests passing**. Objective, deterministic, **$0 at scoring** (no LLM), exactly like the official SWE-bench harness.
- **Why it's different from today:** the verified-score's TESTS slot runs the **worker's own** tests (self-consistency); this runs **our** tests (correctness vs. the spec). That's the credibility difference.
- **Where:** extend the existing greenfield eval infra — `scripts/v2-eval.sh` already drives specs through the pipeline. Add `eval/build/` (a `BuildEvalHarness` + `cases/` of `{spec.md, acceptance/}`), and a `scripts/build-eval.sh` that runs the pipeline then executes `acceptance/` via the existing multi-language `WorkspaceTestRunner`. Report a single **BUILD-resolved %** per complexity band — the number a buyer should actually hear.
- **Start small:** 10–15 cases across LOW/MED/HIGH (REST API, CLI tool, small web app), 3–5 hidden tests each. Grows over time; the harness is the deliverable.

**Net:** this gives BUILD a defensible, repeatable, objective metric and closes the "the measured thing isn't the sold thing" gap (risk #1) without chasing an external leaderboard.

## 📐 Critical #2 — mid-run durability (suggested fix)

**The gap.** Workspaces live on local disk; GCS upload + the archive job fire **only on terminal events**. A node/process restart on **ephemeral disk** (Cloud Run) maps to "workspace gone → FAILED" — *after credits were charged at the plan gate*. This is the only risk that **loses paid customer work**.

**Suggested fix — hybrid, cheap-now + robust-later:**

1. **Now (closes the paid-work-loss risk cheaply):** **pin execution to the persistent-disk GCE VM** and document **Cloud Run = previews only**. On the VM a process restart keeps the disk, so `StartupRunResumer`'s resume-from-disk already works. This is a config + docs change + a guard that refuses to *dispatch* a run on an ephemeral-disk node. Low effort, removes the data-loss path immediately.
2. **Later (enables multi-node / Cloud-Run execution):** add a `WorkspaceCheckpointService` that snapshots the workspace (tar or the committed git state) to GCS **on each task boundary** (after `applyExecutionResultToTasks`), and have `StartupRunResumer` **restore from the latest GCS checkpoint** when the local workspace is missing — instead of terminating `WORKSPACE_GONE`. This makes mid-run work durable across node loss.

**Recommendation:** do (1) now (it's the safety fix); schedule (2) when execution needs to leave the single VM. Either way, also **refund the charged credits on a `WORKSPACE_GONE` terminal** so a node loss never bills the customer for nothing.

---

## ⏭️ #19 — why the Spring-AI reroute is NOT being deleted yet (verify-first)

The audit called the OPENAI→ANTHROPIC reroute in `RunOrchestrator.generateValidationReport` "stale (now on Spring AI 1.1.6)." **But it is currently load-bearing:** this session's v2-eval showed OpenAI runs *successfully* produced accuracy reports (97–100) **with** the reroute active. Those calls go through `FallbackLlmRouter` (Spring AI), and we have **not** verified that 1.1.6 fixed the `extra_body`-on-OpenAI bug the reroute works around. Deleting it blindly risks reintroducing a 400 (degraded/missing accuracy + narrative on every OpenAI run). **Action:** verify the 1.1.6 `extra_body` behavior with one `FallbackLlmRouter` OpenAI call; remove the reroute only if confirmed fixed. Until then it stays (self-healing — it restores the original provider). The stale "1.1.4" comment has been left intact pending that verification.

---

## 🔭 #5 — `RunOrchestrator` god-object: scoped, not rushed

5,262 LOC / 73 methods / 63 deps. A safe decomposition is a **dedicated, multi-PR effort** (extract `CostCreditService`, `ValidationReportOrchestration`, `CrashResumeService`, `ContextInjectionService`; add unit tests as each carves out; target <3,000 LOC / <30 deps). Doing it in-band with the other fixes would be reckless given it's the single most change-sensitive file and has only 2 narrow tests today. Recommend its own branch + PR sequence after the bounded items land.

## Additional cleanup this session (doc-refresh + cosmetic pass, 2026-05-26)

Bundled with the doc-hygiene sweep, not new feature work:

- **Stale comments corrected (3).** `PullRequestController:97` ("assertCanRead" → now correctly documents the `assertCanWrite` it calls — the refresh mutates PR status); `SideEffectExecutor:199` (was "GitHubRepoService doesn't yet expose deleteBranch" → `deleteBranch` now exists, but the compensation stays log-only because `COMPENSATE_GITHUB_BRANCH` is never enqueued + the payload carries no PAT); `SpecTemplateController.createForOrg` (added a comment noting the tenant boundary is enforced in the service, since the org-scoped URL is outside the `AccessGuardCoverageTest` pattern — verified the service rejects cross-org with 403).
- **Test message corrected.** `FailoverOrderEnumAlignmentTest.testEnumItselfContainsExpectedProviders` no longer tells maintainers to update the deleted `agent.run-pool.providers` / `agent.provider-pools` section.
- **Old reports cleared to git history.** The 7 dated SWE-bench intermediates under `docs/benchmarks/` + `docs/reports/exec-report-2026-05-25.md` (superseded/discarded oracle numbers) removed per the `document_map.md` point-in-time-artefact policy; canonical SWE-bench record is now `docs/reports/swebench-exec-brief.{html,pdf}`. Dangling links in `document_map.md` / `reason-log` / `benchmark/CLAUDE.md` softened to plain text.
- **Doc surface synced** to the deletions/deprecation: root `CLAUDE.md`, `router/CLAUDE.md`, `CODEMAP.md`, `JAVADOC.md`, `tech-debt.md` (#3/#15 struck, #7 re-scoped).

Verified: `mvn compile` + `spotless:check` clean; `FailoverOrderEnumAlignmentTest` + `AccessGuardCoverageTest` + `AsyncAllowlistTest` green (5/0/0).

## Next batch (the 🔧 items) — deferred, NOT done in this wrap-up
In rough impact×safety order: signal-pages removal + frontend code-split/vitest (#17/#18), audit-trail events (#11), saga branch-delete (#16), governance flag-enable (#6), cost-estimator calibration (#9), verified-score CODE_DELIVERED floor (#7), dual-codemap consolidation (#14). Each is a feature/refactor effort that lands as its own commit/PR with the full suite green before the next — **intentionally not rushed into this doc-refresh + cleanup wrap-up** ("scoped, not rushed", per the founder's triage). #18's "dead signal pages" are unrouted components *inside* the 92 KB `ExtraPages.tsx` (not whole files — every signal page file is still referenced), so removal is a careful refactor, not a delete.
