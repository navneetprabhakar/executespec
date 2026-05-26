# Audit remediation — top-20 disposition + Critical proposals (2026-05-26)

> Branch `fix/audit-remediation` off `dev`. Remediates `docs/reviews/system-audit-2026-05-26.md`.
> Disposition per the founder's triage: SSO/SCIM **parked** (until customers), Fallback LLM **deprecated**,
> RAG **skipped for now**, intra-org least-privilege **deferred**, spend-ceiling **already UI-exposed**,
> signal pages **should be removed** (IDE-only migration). Big refactors are **scoped, not rushed**.

## Disposition table

| # | Risk | Disposition |
|---|---|---|
| 1 | 🔴 BUILD path unbenchmarked | **📐 Proposal below** (how + where to measure BUILD) |
| 2 | 🔴 Mid-run durability | **📐 Proposal below** (suggested fix) |
| 3 | 🔴 Concurrency dead code | **✅ Fixed this PR** — deleted `ProviderRunPoolProperties` + `agent.provider-pools` YAML |
| 4 | 🔴 Intra-org least privilege | **⏭️ Deferred** (revisit later, per founder) |
| 5 | 🟠 `RunOrchestrator` god-object | **🔭 Dedicated effort** — scoped below (not safe to rush in-band) |
| 6 | 🟠 Enterprise governance OFF | **🔧 Next batch** — flip flags after a v2-eval no-regression gate |
| 7 | 🟠 Verified-score vouches for AI | **🔧 Next batch** — targeted (CODE_DELIVERED floor + intent linkage) |
| 8 | 🟠 SSO/SCIM skeleton | **⏸️ Parked** (until customers, per founder) |
| 9 | 🟠 Cost estimator under-estimates | **🔧 Next batch** — calibrate + widen the multi-repo buffer |
| 10 | 🟠 Two LLM routers | **✅ Deprecated this PR** — `FallbackLlmRouter` marked deprecated (no new callers); migration is the long pole |
| 11 | 🟠 Incomplete audit trail | **🔧 Next batch** — emit run/PR/membership/setting events |
| 12 | 🟠 RAG fragility | **⏸️ Skipped for now** (revisit once the rest land, per founder) |
| 13 | 🟠 No default spend ceiling | **✅ Acknowledged** — per-run `userCostCap` is UI-exposed at submit; org-level default cap is optional hardening (noted) |
| 14 | 🟡 Dual codemap systems | **🔧 Next batch** — consolidate `RunCodeMap` → `ProjectCodeMap` reuse |
| 15 | 🟡 Dead `SupplyChainScanner` | **✅ Fixed this PR** — deleted the unwired interface + Noop impl |
| 16 | 🟡 Saga branch-delete no-op | **🔧 Next batch** — implement `GitHubRepoService.deleteBranch` + wire the compensation |
| 17 | 🟡 Frontend no safety net | **🔧 Next batch** — vitest in CI + `manualChunks`/`React.lazy` |
| 18 | 🟡 God frontend files + dead signal pages | **🔧 Next batch** — remove the dead signal pages (IDE-only migration leftover) + shrink `api.ts`/`ExtraPages` |
| 19 | 🟡 Stale Spring-AI reroute | **⏭️ Deferred — verify first** (see below; it is *load-bearing* today) |
| 20 | 🟡 Over-engineered surface | **📝 Partially** — the 3 deletions above; broader trimming is opportunistic |

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

## Next batch (the 🔧 items)
In rough impact×safety order: signal-pages removal + frontend code-split/vitest (#17/#18), audit-trail events (#11), saga branch-delete (#16), governance flag-enable (#6), cost-estimator calibration (#9), verified-score CODE_DELIVERED floor (#7), dual-codemap consolidation (#14). Each lands as its own commit on this branch with the full suite green before the next.
