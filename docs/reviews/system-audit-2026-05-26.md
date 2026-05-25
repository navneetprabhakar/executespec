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
| Security posture | **C+** | Real defense layers; the share-link IDOR + the cross-org write hole are now fixed (this PR) — but **no intra-org least privilege** (any in-org member writes any project) remains, and one advertised layer (`SupplyChainScanner`) is dead code. |
| Trust & correctness | **B−** | The additive verified-score is the strongest asset (honest, real test exec, "not measured" disclosure) — but it vouches for AI-graded-by-AI and awards 30/100 for emitting files. |

## What is genuinely strong (do not lose this in the cleanup)

1. **Scoring honesty.** The additive verified-score (`report/ValidationReportService`) runs the worker's *real* tests via `WorkspaceTestRunner`, damps LLM-judged grades, and the UI leads with a "measured vs. not-measured" disclosure. This is more intellectually honest than most competitors' scores.
2. **Crash-recovery + the plan gate.** `GateStore` is a thoughtful 3-tier design (in-memory future + Redis write-through + DB record, atomic double-approve guard). `StartupRunResumer` is honest and careful (workspace-missing precheck, salvage path, resume caps).
3. **Engineering culture signal.** The team caught and *transparently disclosed* its own 3× benchmark scorer error (false 17% → real 59%, 4 distinct bugs). 13 TODOs in 156K LOC and the disciplined default-OFF flag-rollout pattern (zero production blast radius) corroborate real discipline.
4. **The core insight is right.** Existing-repo PATCH is where agentic scaffolds add the most lift, and the system measurably delivers it (≈59% on SWE-bench Lite, blind).

## Top 20 risks (ranked)

> Severity: 🔴 critical · 🟠 high · 🟡 medium. Each is the synthesis of two independent passes verified
> against source. **Two items the first pass flagged were fixed in this same PR** — listed at the end,
> not counted in the 20.

### 🔴 Critical

1. **Strategic — the measured differentiator isn't the product.** The 59%/52% + "5–17× lift" are **PATCH** (existing-repo); the product sold is **BUILD** (greenfield; `RunMode` defaults to BUILD for product entry points), which has **no external benchmark** — quality rests on the self-graded `scripts/v2-eval.sh`. The headline doesn't measure what a buyer buys. Either benchmark BUILD externally or reposition around PATCH (where Cursor/Cline/Devin already sit at 60–66%).
2. **Reliability — mid-run work isn't durable.** Workspaces live on local disk; GCS upload + archive fire **only on terminal events** (`WorkspaceGcsUploadListener.java:50`). A restart on ephemeral disk (the documented Cloud Run target) → "workspace gone → FAILED," **after credits were charged at plan approval**. The only risk that *loses paid customer work*.
3. **Scale — concurrency limits are dead code.** Single per-JVM 16-thread pool + unbounded queue (`RunOrchestrator.java:291`); the YAML per-tier/provider `concurrent-run-limit` + `ProviderRunPoolProperties` have **zero consumers**; no `Semaphore` anywhere; provider 429s ungated. Enforce it or delete the config.
4. **Security — no intra-org least privilege.** `ProjectAccessGuard` admits **any in-org member to any project** (its own javadoc concedes it). A plain `DEVELOPER` not assigned to a project can drive it, open PRs, and spend LLM budget. (The cross-org-viewer write hole was fixed this cycle; this is the remaining open class.)

### 🟠 High

5. **`RunOrchestrator` god-object** — 5,262 LOC / 73 methods / **63 dependencies**; effectively un-unit-testable (2 narrow tests); every pipeline change risks it. Carve out cost/credit, validation-report, crash-resume, context-injection services.
6. **Enterprise governance ships OFF.** Standards / contract / ADR / codemap injection are all **default-OFF** flags (V209–V213); `InvariantChecker` has zero callers. In the shipped default, defining governance **changes nothing the AI does** — one flag-flip from real, not flipped.
7. **Verified-score vouches for AI-graded-by-AI.** The TESTS slot reads the worker's *own* tests (self-consistency, not correctness); CODE_DELIVERED awards **30/100 for `filesGenerated>0`** alone. "Shippable ≥85" doesn't map to customer intent.
8. **SSO/SCIM is a skeleton.** `auth/sso/SsoConfigController.java` saves "intent" so marketing can show "SSO ✓"; SAML is 409-rejected; only Google OIDC works; **no SCIM anywhere**. The ENTERPRISE tier's #1 procurement checkbox is undeliverable today.
9. **Cost estimator under-estimates → truncates paid runs.** A documented **12× miss** (`RunCostTracker.java:288`); low estimate → low hard-cap → the run stops mid-work and the customer is charged a credit for a half-finished PR (refund only on FAILURE, not a low-quality PASS).
10. **Two LLM routers, divergent semantics.** `FallbackLlmRouter` ("legacy") has **33 callers / ~14 live agents** with different retry/failover than the "canonical" `LlmRouter` — a structural fork; a transient outage behaves differently depending on the caller.
11. **Incomplete + mutable audit trail.** The activity feed omits run / PR / membership / setting events (RunOrchestrator emits none); retention defaults to dry-run. Insufficient for SOC2 / compliance positioning the ENTERPRISE tier implies.
12. **RAG fragility.** OpenAI-only embeddings (a BYOK-no-OpenAI org throws on upload), binary corruption on the upload round-trip (PDF/DOCX mangled before Tika), and an ivfflat index built on an empty table (degraded recall).
13. **No default spend ceiling.** `GovernanceGuard` org spend caps are **null-by-default** (opt-in) → most orgs have no ceiling; combined with the uncredited tech-debt/CI autofix surfaces, a low-tier seat can burn the org's LLM budget.

### 🟡 Medium

14. **Dual codemap systems.** `ProjectCodeMap` (8 consumers) vs `RunCodeMap` (3) — two separately-generated, never-reconciled repo views → double LLM cost on big repos + drift.
15. **Dead `SupplyChainScanner`** — an advertised "8-layer defense" component (`security/dependency/`) with **zero callers**. Security theater.
16. **Saga branch-delete is a no-op** — `SideEffectExecutor` can't `deleteBranch`, so failed/cancelled runs leak orphan branches on customer repos.
17. **Frontend has no safety net** — zero running unit tests; CI is prettier + warn-only eslint; no code-splitting (single **~802 KB** bundle). ~35K LOC ships blind, slow first paint on the public path.
18. **God frontend files** — `lib/api.ts` (**3,679 LOC** / 307 fns) + `signal/pages/ExtraPages.tsx` (2,782 LOC). High-coupling, dangerous to refactor without the (missing) test net.
19. **Stale Spring-AI-1.1.4 reroute on a hot path** — `RunOrchestrator` reroutes OPENAI→ANTHROPIC for a bug fixed in the **1.1.6** the project now runs; self-healing (restores the provider) but mutates the run entity and is surprising.
20. **Over-engineered surface area** — 115 entities / 213 migrations / 104 controllers for one product on one VM, much on unmerged branches; the migration log self-documents churn (tier limits set→revert; product-split added then dropped). Cognitive + maintenance load.

**✅ Fixed in this PR (were risks in the first pass, now closed):** the **cross-org write hole** (21 write handlers swapped to `assertCanWrite`, RL-2026-05-040) and the **`AccessGuardCoverageTest` read-guard-on-write blind spot** (now pins write-handler → write-guard).

## Prioritized roadmap (what to fix, in order)

**Now (days, ship-blockers):**
1. **Intra-org least privilege** (#4) — make `ProjectAccessGuard` check per-project membership/role, not just same-org. *Security; the larger remaining auth item now that the cross-org write hole is fixed (RL-2026-05-040, this PR).*
2. **Mid-run durability** (#2) — pin execution to the persistent VM + document, or checkpoint to GCS on task boundaries. *Only thing that loses paid work.*
3. **Concurrency** (#3) — enforce the config with a semaphore + bounded queue, or delete the dead config.

**Next (1–2 weeks):**
4. Flip the enterprise/code-intel flags after the v2-eval gate (#6) — make the governance story real.
5. Frontend safety net (#frontend-D) — wire vitest into CI + `manualChunks`/`React.lazy` (prereq for touching `api.ts`).
6. Begin carving `RunOrchestrator` (#5).

**Strategic (the positioning call):**
7. Either externally benchmark the BUILD path or reposition the headline around existing-repo PATCH (#1) — and decide the verified-score's relationship to *customer intent*, not just self-consistency (#7).

**Cleanup (opportunistic):** consolidate the two LLM routers (#10) + dual codemaps (#14); delete `SupplyChainScanner` (#15) / stale Spring-AI reroute (#19) / no-op saga branch-delete (#16); deliver real SAML+SCIM before leaning on the ENTERPRISE tier (#8).

## Verdict

ExecuteSpec is a legitimately ambitious, largely-built platform whose strongest assets are real — an
honest verified-score with actual test execution, well-engineered crash recovery and gating, a candid
engineering culture, and a defensible insight that existing-repo PATCH is where the lift lives. But
today the gap between the pitch (enterprise spec→PR platform with a 59% headline) and the shipped
default (greenfield BUILD that's unbenchmarked, enterprise governance disabled, SSO a skeleton, no
intra-org least privilege, paid work lost on a restart) is wide enough to block a serious org from buying
and relying on it. The bones are strong; the path to credibility is concrete and mostly known to the
team. **C+ — one focused hardening-and-positioning cycle from defensible.**
