# Audit: `codeintel/` — 3-step project-architecture pipeline + codemaps + generated docs

Status: **DONE** · Updated 2026-05-29 · 26 files / ~3,700 lines · all read in full

## Files
**Services / generators (14):**
- `ProjectArchitectureService.java` (774) — 3-step pipeline orchestrator ✓
- `CodeMapGenerator.java` (465) — Trees-API codemap, no-clone, regex+JavaParser symbol extraction ✓
- `RepoArchitectureGenerator.java` (129) — Step 2 per-repo HLD/LLD ✓ · `ArchitectureDocGenerator.java` (184) — Step 3 cross-repo synthesis ✓
- `StalenessService.java` (170) ✓ · `RunCodeMapService.java` (281) ✓ · `FilePurposeSummaryService.java` (240) ✓
- `ProjectBriefService.java` (121) ✓ · `VersionRetentionService.java` (150) ✓ · `ProjectDocGeneratorService.java` (360) ✓
- `JavaAstSymbolExtractor.java` (109) ✓ · `ArchitectureDiffService.java` (101) ✓
- `ProjectArchitectureController.java` (312) ✓ · `ProjectDocGeneratorController.java` (117) ✓

**Entities (6):** `ProjectArchitectureDoc(64)` · `ProjectCodeMap(42)` · `RepoArchitectureDoc(81)` · `RunCodeMap(66)` · `FilePurposeSummary(69)` · `ProjectGeneratedDoc(91)` — all ✓
**Repositories (6):** `FilePurposeSummary(37)` · `ProjectArchitectureDoc(29)` · `ProjectCodeMap(20)` · `ProjectGeneratedDoc(46)` · `RepoArchitectureDoc(31)` · `RunCodeMap(12)` — all ✓

## What it does
The project-intelligence layer. A 3-step idempotent pipeline turns each linked repo into architecture docs: **(1)** `CodeMapGenerator` reads the repo via the GitHub **Trees API** (no clone) + bounded regex/JavaParser symbol extraction → `ProjectCodeMap` (per-repo, versioned, SHA-keyed); **(2)** `RepoArchitectureGenerator` (PLANNING tier) turns each codemap into a per-repo HLD/LLD `RepoArchitectureDoc`; **(3)** `ArchitectureDocGenerator` (PLANNING tier) synthesises a cross-repo `ProjectArchitectureDoc`. `ProjectDocGeneratorService` produces EXPLANATION/SETUP human docs from the arch. `StalenessService` keeps the dashboard STALE badge honest; `VersionRetentionService` caps each artifact at the latest 20 versions; `ArchitectureDiffService` diffs two arch versions via the GitHub compare API. `RunCodeMapService` is the per-run (non-project) codemap for single-shot existing-repo runs.

## Findings — the cleanest package audited so far. Cost-discipline is the design center.

### Cost / efficiency (mostly 🟢 — the good model the rest of the codebase should copy)
- 🟢 **Pervasive cache-before-LLM short-circuits.** Three independent layers all skip the expensive LLM call when inputs are unchanged: codemap idempotent by repo **SHA**; repo-arch idempotent by **source-codemap-id**; combined-arch + generated-docs short-circuit on a **(arch-version, sorted-SHA fingerprint)** tuple (PR-K / PR-Q — `ProjectArchitectureService.serialiseFingerprint`, `ProjectGeneratedDoc.sourceArchVersion`+`sourceShasFingerprint`). The `.executespec/`-only-diff loop-breaker (RL-2026-05-025) further suppresses regen when the only changes are the pipeline's own output files. This is the single best cost-aware subsystem in the codebase — every LLM call is gated behind a deterministic freshness check.
- 🟢 **Per-artifact cost telemetry persisted at generation time.** Every entity (`ProjectArchitectureDoc`/`RepoArchitectureDoc`/`ProjectGeneratedDoc`/`FilePurposeSummary`/`RunCodeMap`) carries `cost_usd` + `input/output_tokens` + `model_used`, populated from the pricing matrix when generated (V174), and each repo exposes a `SUM(costUsd)` rollup query. Dashboard spend never back-calculates from tokens × today's prices — it reads the cost captured at the time. Contrast the [report.md](report.md) `UsageReportService` finding (re-estimates at stale Haiku rates).
- 🟢 **No-clone codemap.** `CodeMapGenerator` uses the GitHub Trees API + targeted blob fetches with hard bounds (≤40 files × ≤30 functions for symbol extraction) instead of cloning — cheap, fast, and bounded regardless of repo size.
- 🟢 **`FilePurposeSummaryService` is the textbook-correct LLM caller** — routed through `FallbackLlmRouter`, cost-tracked, SHA-cached (`(repoFullName, filePath, contentSha)` triple), flag-gated (`codemap.purpose-summaries`, default OFF). It is the direct foil to the bypass debt below: same package, two opposite patterns.
- 🟢 `VersionRetentionService` eagerly prunes all but the latest 20 versions of each artifact — unbounded version growth is the obvious failure mode for a regen-on-every-push pipeline, and it's handled.
- 🟡 **Deliberately NOT `@Transactional`.** `ProjectArchitectureService` documents that it avoids holding a Hikari connection across the 60s+ LLM calls (would pin the pool). Correct call, but means partial-failure recovery is manual (each step persists independently); acceptable given idempotency makes re-run safe.

### Cost / correctness (the one real hole)
- 🟡 **`RunCodeMapService.summarise` calls `anthropicModel.call()` directly — bypassing `LlmRouter` + `RunCostTracker`** ([RunCodeMapService.java](../../../src/main/java/com/navneet/executespec/codeintel/RunCodeMapService.java)). This is the **2nd confirmed direct-bypass** of the cost tracker (1st = `assessment/FeatureAssessmentService`, audit E#35 — see [assessment.md](assessment.md)). The compressed-summary LLM spend for per-run codemaps lands in `RunCodeMap.actualCostUsd` (a local column) but never flows through `RunCostTracker`, so it's **invisible to the run's `actual_cost_usd` / per-phase attribution / cache accounting**. Ironic given the rest of the package is the cost-tracking exemplar. Also pins the stale-OpenAI contrast: it hardcodes Anthropic while the project default is now Anthropic — coincidentally right, but for the wrong reason (it should route, not pick a provider). Fix: route through `LlmRouter`/`FallbackLlmRouter` like its sibling `FilePurposeSummaryService` already does.
- 🟡 **`@Autowired` field injection in `RunCodeMapService`** — violates the CLAUDE.md MUST-NOT (constructor injection via `@RequiredArgsConstructor`). Same file as the bypass; a rewrite fixes both.

### Security / correctness
- 🟢 `ProjectArchitectureController` + `ProjectDocGeneratorController` — **every** endpoint gates on `projectAccessGuard.assertCanRead`/`assertCanWrite` (paid regen endpoints are write-gated); `readRepoFile` has a path-traversal guard + 1 MB cap. Clean tenant hygiene — contrast the legacy access-guard drift called out elsewhere.
- 🟢 `StalenessService` is Redis-cached (5-min TTL, multi-MIG-safe), branch-aware (V192 — probes the same branch the codemap came from, fixing the permanent-STALE-badge bug), and ignores `.executespec/`-only diffs. Careful, correct.
- 📝 **Stale provider/tier comments.** `ProjectBriefService` + `ProjectDocGeneratorService` + `ProjectArchitectureService` default `aiProvider` to `"OPENAI"` (the recurring stale default; CLAUDE.md says Anthropic — latent, the caller passes a provider). `RepoArchitectureGenerator` javadoc says "LIGHTWEIGHT tier" but the YAML matrix routes it **PLANNING** — doc drift, behaviour is correct.

### Tech-debt (minor)
- 📝 `FilePurposeSummaryRepository.findByGeneratedAtBefore` is a **staged-but-unwired** eviction-sweeper hook (no live caller — the comment is honest about it). Low-risk forward-staging, not dead code per se, but the sweeper has been deferred indefinitely; the SHA-keyed cache grows unbounded across file churn until it lands.

## Cross-refs
Direct-`anthropicModel.call` cost-bypass twin → [assessment.md](assessment.md) (`FeatureAssessmentService`, audit E#35). Cost-tracking exemplar contrasts → [report.md](report.md) (`UsageReportService` stale-rate re-estimate), [cost.md](cost.md) (`RunCostTracker`). Codemap/arch consumed by → planner `{{REFERENCE_DOCS}}`/`{{PROJECT_MEMORY}}` ([memory] Tier 4), `ProjectChatTools` doc-access. Regenerate-loop + Sync connection-leak fix → PR #170 (root CLAUDE.md). Tenant guard pattern (clean here) contrasts → [state.md](state.md) `AccessGuardCoverageTest`. Recurring OPENAI-default theme → [integration.md](integration.md), [state.md](state.md), [report.md](report.md).
