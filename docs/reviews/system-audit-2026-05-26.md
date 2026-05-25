# ExecuteSpec — Unbiased System Audit (2026-05-26)

> **Method.** Two independent due-diligence passes (a skeptical principal-engineer tech pass and a
> product+security+business pass), each instructed to verify claims against source rather than echo
> the project's own docs, then reconciled here against this session's first-hand evidence. The brief
> was explicitly *unbiased*: lead with the hard truths, credit the real strengths, no flattery. All
> file:line references are against `feature/swebench-harness` @ `2f95ecdb`.

## Bottom line

**Overall grade: C+ — a strong, genuinely-built system with real bones, carrying two HIGH-severity
structural liabilities and a strategic positioning gap that gate it from being sold as the enterprise
product it's framed as.** It is fit for a controlled beta on a persistent VM today. It is **not** ready
to be told "scale me to 100× on Cloud Run and sell me to an enterprise" without a focused
hardening-and-positioning cycle. The good news: the gaps are concrete, mostly known, and none are
fatal — this is one disciplined cycle away from defensible.

## Scorecard

| Dimension | Grade | One-line |
|---|---|---|
| Architecture soundness | **C+** | Consistent conventions, but `RunOrchestrator` (5,262 LOC / 73 methods / **63 injected deps**) is a true god-object every concern funnels through; two parallel LLM routers. |
| Reliability & failure modes | **B−** | Crash-recovery, plan-gate, and cost-cap correctness are genuinely well-built — but mid-run work lives only on local disk (lost on an ephemeral-disk restart, after credits are charged). |
| Scale | **C** | Single GCE VM (prod+dev); one in-JVM 16-thread pool; the per-tier/provider concurrency config is **dead code**; provider rate-limits ungated. Indices + one partition are fine. |
| Test coverage — backend | **B−** | 1,369 tests / ~4,025 assertions (≈2.9/test — not assertion-light), 0 disabled, gates CI. But the 5,262-LOC orchestrator has 2 narrow tests; ~25 packages have none. |
| Test coverage — frontend | **D** | Confirmed: zero running unit tests, no test runner, CI is prettier + warn-only eslint. ~35K LOC ships blind. |
| LLM cost & routing | **B−** | Cost cap is correctly run-cumulative (no N×-task blow-out). Real risk is the inverse: a 12× *under*-estimate truncates a paid run mid-work. |
| Code-quality signals | **B** | 13 TODO/FIXME in 156K LOC (clean); 29 flags, centralized (not sprawl). Real debt: 2 god-files, dual codemap systems, dead scanners. |
| Product coherence & value prop | **C** | The spec→PR loop runs and the verified-score is honestly built, but the *measured* 59% is PATCH/existing-repo — not the greenfield BUILD path that is the actual product. |
| Enterprise readiness | **D** | SSO is an explicit skeleton ("save intent so marketing can show 'SSO ✓'"); no SCIM; no intra-org least-privilege; enterprise governance engines shipped **OFF**. |
| Security posture | **C+** | Real defense layers + the worst IDOR is genuinely fixed — but a **live cross-org write hole** remains and one advertised layer (`SupplyChainScanner`) is dead code. |
| Trust & correctness | **B−** | The additive verified-score is the strongest asset (honest, real test exec, "not measured" disclosure) — but it vouches for AI-graded-by-AI and awards 30/100 for emitting files. |

## What is genuinely strong (do not lose this in the cleanup)

1. **Scoring honesty.** The additive verified-score (`report/ValidationReportService`) runs the worker's *real* tests via `WorkspaceTestRunner`, damps LLM-judged grades, and the UI leads with a "measured vs. not-measured" disclosure. This is more intellectually honest than most competitors' scores.
2. **Crash-recovery + the plan gate.** `GateStore` is a thoughtful 3-tier design (in-memory future + Redis write-through + DB record, atomic double-approve guard). `StartupRunResumer` is honest and careful (workspace-missing precheck, salvage path, resume caps).
3. **Engineering culture signal.** The team caught and *transparently disclosed* its own 3× benchmark scorer error (false 17% → real 59%, 4 distinct bugs). 13 TODOs in 156K LOC and the disciplined default-OFF flag-rollout pattern (zero production blast radius) corroborate real discipline.
4. **The core insight is right.** Existing-repo PATCH is where agentic scaffolds add the most lift, and the system measurably delivers it (≈59% on SWE-bench Lite, blind).

## Top risks (ranked, cross-cutting, with evidence)

### 🔴 1. Strategic: the measured differentiator is not the product
The 59%/52% SWE-bench result + the "5–17× lift" are **PATCH mode** (existing-repo bug-fix — only the 4 autofix surfaces + bench adapters use it). The customer-facing "submit a spec → PR" product is **BUILD mode** (greenfield; `RunMode` defaults to BUILD for product entry points). BUILD has **no comparable external benchmark** — its quality rests on the self-graded `scripts/v2-eval.sh` (LLM-judged accuracy). *The headline number a buyer hears does not measure the thing they'd buy.* Either benchmark the BUILD path externally, or reposition around existing-repo PATCH (where the moat is also strongest) — but that's exactly where Cursor/Cline/Devin already compete at 60–66% Verified.

### 🔴 2. Reliability: mid-run work is not durable
Workspaces are local disk at `<agent.workspace>/<runId>/`; the GCS upload + archive job fire **only on terminal events** (`deployment/WorkspaceGcsUploadListener.java:50`, `WorkspaceArchiveJob.java:55`). Crash-resume requires the workspace on the same node — on ephemeral disk (the documented Cloud Run target) a restart maps to "workspace gone → FAILED," **after credits were charged at plan approval**. This is the only risk that *loses paid customer work*. Fix: pin execution to the persistent VM (and document Cloud Run = previews only) or checkpoint to GCS on task boundaries.

### 🔴 3. Scale: concurrency limits are dead code
Dispatch is a single per-JVM `newFixedThreadPool(clamp(4,16,cores))` with an unbounded queue (`RunOrchestrator.java:291`). The YAML per-tier/provider `concurrent-run-limit` + `ProviderRunPoolProperties` have **zero consumers**; there is **no `Semaphore` in the dispatch path**. At load, everything contends on 16 threads + 30 DB connections, and provider 429s are ungated. Either enforce the config (per-provider/tier semaphore + bounded queue + fast-fail) or delete it (stop implying a control that doesn't exist).

### 🔴 4. Security: live cross-org write hole
The recent hardening fixed the share-link IDOR + contracts guards (verified, unconditional) — but **not** this class: `project/controller/ProjectRepoController.java` and `project/rag/ProjectDocumentController.java` use `assertCanRead` on **write** paths (link/unlink repos, edit relationships, upload/delete RAG docs). A cross-org `PROJECT_VIEWER` invited read-only can still mutate those. Compounded by **no intra-org least privilege** (`ProjectAccessGuard` admits *any* in-org member to *any* project) and an `AccessGuardCoverageTest` that checks a guard is *called* but not that it's the *write* guard — so this ships green. **Recommend fixing immediately** (swap `assertCanRead`→`assertCanWrite` on the write handlers; tighten the test to distinguish read/write). I can do this in ~30 min on request.

### 🟠 5. Architecture: the `RunOrchestrator` god-object
5,262 LOC, 73 methods, **63 dependencies**, owning classification → planning → gate → execution → review → cost → SCM → reporting → resume → injection. Effectively un-unit-testable (2 narrow tests exist); every pipeline change risks it. Carve out `CostCreditService`, `ValidationReportOrchestration`, `CrashResumeService`, `ContextInjectionService`; target <3,000 LOC / <30 deps.

### 🟠 6. Product honesty: enterprise governance + code-intel ship OFF
`StandardsRunner`, contract/ADR injection, the breaking-change classifier, and codemap-injection are now *wired* — but all behind **default-OFF** flags (V209–V213), and the flip is gated on a v2-eval no-regression run the exec report lists as not-yet-fully-done. `InvariantChecker` still has zero callers. So in the shipped default, defining standards/contracts/ADRs **still changes nothing the AI does**. One flag-flip from real; not flipped.

### 🟠 7. Trust: the verified-score vouches for AI-graded-by-AI
The TESTS slot reads the *worker's own* tests (green proves self-consistency, not correctness); CODE_DELIVERED awards **30/100 for `filesGenerated>0`** alone. So a run can earn "Shippable ≥85" with no link to the customer's acceptance criteria. The honesty framing is real, but the badge implies more than it knows.

### 🟠 8. Enterprise: SSO/SCIM is a costume
`auth/sso/SsoConfigController.java` literally saves "intent" so marketing can show "SSO ✓"; SAML is hard-rejected with 409; only Google OIDC works; **no SCIM anywhere**. The ENTERPRISE tier's #1 procurement checkbox cannot be delivered today.

### 🟡 9. Maintainability/cost: two LLM routers + dual codemaps + dead code
`FallbackLlmRouter` is framed as "legacy" but has **33 callers** (~14 live agents) with *different* retry/failover semantics than the "canonical" `LlmRouter` — a structural fork, not a vestige. `ProjectCodeMap` vs `RunCodeMap` are two separately-generated, never-reconciled repo views (double LLM cost on big repos). `SupplyChainScanner` (advertised defense layer) and the saga branch-delete are unwired; the Spring-AI-1.1.4 reroute workaround is stale (now on 1.1.6, though self-healing).

### 🟡 10. Surface area vs. footprint
115 entities / 213 migrations / 104 controllers / 72 packages for one product on one VM — much of it on unmerged branches (V3 launches, migration recipes, readymade solutions, dual SWE-bench harnesses). The migration log self-documents churn (tier limits set→loosen→revert; product-split added then dropped). Not fatal, but it's carrying cost.

## Prioritized roadmap (what to fix, in order)

**Now (days, ship-blockers):**
1. **Cross-org write hole** (#4) — `assertCanRead`→`assertCanWrite` on the write handlers + tighten `AccessGuardCoverageTest`. *Security; ~hours.*
2. **Mid-run durability** (#2) — pin execution to the persistent VM + document, or checkpoint to GCS on task boundaries. *Only thing that loses paid work.*
3. **Concurrency** (#3) — enforce the config with a semaphore + bounded queue, or delete the dead config.

**Next (1–2 weeks):**
4. Flip the enterprise/code-intel flags after the v2-eval gate (#6) — make the governance story real.
5. Frontend safety net (#frontend-D) — wire vitest into CI + `manualChunks`/`React.lazy` (prereq for touching `api.ts`).
6. Begin carving `RunOrchestrator` (#5).

**Strategic (the positioning call):**
7. Either externally benchmark the BUILD path or reposition the headline around existing-repo PATCH (#1) — and decide the verified-score's relationship to *customer intent*, not just self-consistency (#7).

**Cleanup (opportunistic):** consolidate the two routers + dual codemaps; delete `SupplyChainScanner`/stale reroute/no-op saga branch-delete (#9); deliver real SAML+SCIM before leaning on the ENTERPRISE tier (#8).

## Verdict

ExecuteSpec is a legitimately ambitious, largely-built platform whose strongest assets are real — an
honest verified-score with actual test execution, well-engineered crash recovery and gating, a candid
engineering culture, and a defensible insight that existing-repo PATCH is where the lift lives. But
today the gap between the pitch (enterprise spec→PR platform with a 59% headline) and the shipped
default (greenfield BUILD that's unbenchmarked, enterprise governance disabled, SSO a skeleton, a live
cross-org write hole, paid work lost on a restart) is wide enough to block a serious org from buying
and relying on it. The bones are strong; the path to credibility is concrete and mostly known to the
team. **C+ — one focused hardening-and-positioning cycle from defensible.**
