# Project Features — Full UI→Backend Audit (2026-05-25)

> Line-by-line audit of the project workbench across 4 clusters (project-core, code-intelligence,
> enterprise-readiness/sharing, frontend). Answers: feature map · org-sense · architecture/codemap
> wiring · gaps · risks · PATCH-fit. **One-line verdict: the generation/CRUD/UI breadth is real and
> impressive, but the load-bearing wiring is missing in three places — code-intel is never consumed
> by the agents, the enterprise enforcement engines are dead code, and there are real cross-org
> security leaks.**

## 1. FEATURE MAP (UI → api.ts → endpoint → backend → status)

**Project-core (mostly works):** dashboard ✅, workspace-health ✅, tech-debt rollup ✅ (+ autofix ⚠️ ungated), estimate ✅, per-branch analyse ✅ (no UI), repo link/sync/regen/webhook ✅, PR records ✅, activity feed engine ✅ (UI broken), templates ✅.
**Code-intelligence (generation works, consumption doesn't):** per-repo codemap ✅gen, per-repo HLD/LLD ✅gen, cross-repo synthesis ✅gen, staleness ✅, arch diff ✅, RAG ingest ⚠️(binary-corrupt), RAG retrieve ✅(uploaded docs only), project memory ✅.
**Enterprise (CRUD works, enforcement dead):** `.executespec/` writer ✅, bootstrap ✅, export ⚠️(thin, no UI), contract detection ✅, drift ✅, consumer-pinning ⚠️(no UI), **breaking-change classifier 💀DEAD**, **contract→planner injection 💀DEAD**, standards CRUD ✅, **standards enforcement 💀DEAD**, standards report ⚠️(violations always empty), **ADRs 💀(never populated)**, **invariants 💀(never populated)**, cross-org invites ✅, share-links ⚠️**INSECURE**.
**Frontend:** 10 IDE project sub-tabs, most WIRED; **PRs tab + Activity tab permanently empty; Settings silently drops 4/6 fields; RAG upload 401s** (4 runtime bugs).

## 2. DO THE FEATURES MAKE SENSE FOR AN ORG? — concept yes, delivery decorative

- **Multi-repo is genuinely enterprise-shaped** (links, per-repo health/arch, relationships, cross-link PRs, ORG-vs-USER repo scoping with owner-allowlist + tier caps). This part holds up.
- **The "enterprise knowledge" story is a costume.** Contracts/standards/ADRs/invariants exist to (a) steer the AI and (b) block bad changes — **neither happens.** `StandardsRunner`, `ContractInjectionService`, `PlanBreakingChangeReport`, `AdrInjectionService`, `InvariantChecker` are fully built, unit-tested, and have **zero production callers**. `{{CONTRACTS}}`/`{{PROJECT_ADRS}}` are absent from the live prompts. So an enterprise can define standards/contracts/ADRs and it changes nothing the AI does and blocks nothing. Empty panels + theater.
- **The audit trail is incomplete** — the activity feed omits run/PR/membership/setting events (RunOrchestrator emits none); retention defaults to dry-run; tier retention isn't enforced at read. Insufficient for SOC2 positioning.
- **Missing for real org use:** SSO/SCIM, per-project least privilege (any in-org member can write any project), complete+immutable audit, a real members roster in export.

## 3. ARCHITECTURE / CODEMAP WIRING — generation sound, **consumption is the liability**

The 3-step pipeline (codemap by-SHA → per-repo HLD/LLD → cross-repo synthesis) is **well-engineered**: cheap (GitHub Trees API, no clone), 3 idempotency gates, `.executespec/`-confined-diff escape hatch, Redis staleness, DB-authoritative storage + disk/`.executespec/` mirrors, 20-version retention. **But the expensively-generated codemap/arch docs are almost never read back into the run pipeline:**
- **Planner (project runs):** gets repo-names + language-parity summary — **NOT codemap content.** `ProjectArchitectureService` is called only to *trigger regen on staleness*; its output doc is never injected.
- **Worker (all runs):** gets base prompt + workspaceSummary (only if "MULTI-REPO") + task RAG — **no codemap, no symbol map, no arch doc.** Localizes from scratch via grep/ls/read.
- **PATCH agent (all runs):** gets **only** the user request — zero code-intel.
- **Two disconnected codemap systems** (`ProjectCodeMap` for project runs [unused by planner] vs `RunCodeMap` for non-project runs [used by planner]) double cost + diverge.
- **RAG indexes only uploaded docs, not code** — "reference docs" can't answer "which file implements X."
- **Lacking:** symbol/line granularity (codemap stops at file→function-*names*; no signatures/callgraph/def-site), incremental updates, code-aware retrieval.

**Verdict:** the most valuable intelligence the system produces is **write-only** — shown to humans + chat, withheld from the planner, worker, and PATCH agent, which is exactly where localization accuracy is decided.

## 4. WHERE CAN WE MAKE MISTAKES (RISKS, ranked)

1. **🔴 CRITICAL — public share-link cross-org IDOR (data leak, exploitable today).** `ProjectSharePublicController` resolves `scopeId` (run/report/scan/PR-review) **without checking it belongs to `link.projectId`**, and `createLink` never validates `scopeId` ownership. Any project admin can mint a **no-auth public link** pointing at *another org's* run/report/scan and read its full payload (reportMarkdown, PR comments, cost). One-line ownership check at create+read.
2. **🔴 HIGH — contracts controllers have no `ProjectAccessGuard`.** `ContractDrift/Consumer/Coverage` controllers use only local `requireAuth` → any authenticated user, any org, can read contract metadata/source-paths + fire drift/consumer events on another org's project. The "gated upstream" comment is false (no `/api/projects/**` matcher in SecurityConfig).
3. **🟠 `AccessGuardCoverageTest` is a paper tiger** — matches any `assert*/require*/ensure*/check*` method, so it green-lights `assertCanRead`-as-write-gate AND the contracts' local `requireAuth`; it does **not** verify tenant boundaries. New IDORs ship green. CLAUDE.md's "35/35 cleared" claim overstates it.
4. **🟠 Cost-abuse via tech-debt fix** — `TechDebtFixService` → `FindingPatchSubmitter` with **no tier/credit gate** + read-only-bypassable; PATCH autofix runs are uncredited platform-wide ($8 hard-cap each). A FREE/viewer seat can burn the org's LLM budget.
5. **🟠 RBAC drift** — the entire `project/` package + `ProjectDocumentController` use `assertCanRead` on **write** paths → cross-org `PROJECT_VIEWER` can link/unlink repos, edit relationships, CRUD templates, upload/delete RAG docs, trigger fix runs.
6. **🟠 RAG binary corruption** — `ProjectDocumentService.upload` reads bytes as ISO-8859-1 → String → UTF-8 commit; PDFs/DOCX/XLSX mangle on the round-trip before Tika sees them.
7. **🟡 4 frontend runtime bugs** — PRs tab (`openPrs` field doesn't exist) + Activity tab (`events` vs `entries`) permanently empty; Settings PATCH drops name/description/branch fields silently; RAG upload uses `localStorage 'token'` (never set) → 401.
8. **🟡 Private-repo stack-refresh broken post-PAT-removal** (null PAT in `StackRefreshJob`); embeddings OpenAI-only (BYOK-no-OpenAI org → upload throws); ivfflat index built on empty table (degraded recall); heavy arch regen runs synchronously in the plan-gate thread.

## 5. PATCH LEARNING — where, how, and do we need it?

| Surface | PATCH-fit | Need it? |
|---|---|---|
| **Codemap/arch → PATCH localization** | **HIGHEST-leverage win.** PATCH agent gets *zero* context today. Inject the codemap symbol map (+ code-aware RAG on the issue) → turns "grep the whole tree" into "symbol X is in file Y." For project autofix surfaces (have `projectId`+codemap) it's a string-concat in `PatchAgent.run`; for SWE-bench, generate an ephemeral codemap from the clone via `CodeMapGenerator`. | **YES** — the codemap is fully built but disconnected from PATCH; this is the obvious lever (we showed 88% localization even *blind*). |
| **Standards-violation autofix** | Once `StandardsRunner` actually runs + writes violations, route through `FindingPatchSubmitter` (`source="finding:STANDARDS"`), mirroring TechDebt/CodeReview. Deterministic lint/AST violations are ideal PATCH targets. | **YES, but downstream** of wiring the (dead) enforcement engine first. |
| **Tech-debt fix / CI-failure autofix** | Already wired to PATCH correctly. | Already done — just needs tier/credit gating (#4). |
| **Contract-drift refresh** | Deterministic source→`.executespec/` copy; already a plain PR. | **NO** — don't put an LLM on a deterministic copy. |
| **ADRs / invariants** | Constraints on *planning* — best delivered by prompt injection (prevent), not after-the-fact patch (fix). | **NO** (use the dead injection services instead). |
| **Read/aggregate surfaces** (dashboard/scores/estimate/activity) | No code mutation. | **NO.** |

**Net PATCH answer:** we need it in exactly **two** places — (1) **feed code-intel into PATCH localization** (highest leverage, ties directly to the 5–17× lift finding), and (2) **standards autofix** (after the enforcement engine is actually wired). Everywhere else PATCH is either already present or the wrong tool.

## 6. PRIORITIZED FIX LIST
1. **🔴 Security:** share-link `scopeId`↔`projectId` ownership check (create+read); add `ProjectAccessGuard` to the 3 contracts controllers; tighten `AccessGuardCoverageTest` to verify tenant guards (not just any `assert*`).
2. **🟠 Cost/RBAC:** tier+credit gate on tech-debt/CI autofix; swap `assertCanRead`→`assertCanWrite` on all `project/` + document write paths.
3. **🟠 Frontend bugs:** the 4 runtime bugs (2 dead tabs, lossy settings, upload auth) — cheap, high-visibility.
4. **🟢 The strategic wins (PATCH/quality):** wire **code-intel → PATCH localization** (#5 row 1); then wire the **dead enforcement engines** (standards/contracts/ADRs) into the planner/worker so the enterprise story is real, with standards-autofix via PATCH.
5. **🟢 Robustness:** RAG binary handling; embeddings provider-neutral; private-repo PAT in the refresh job.
