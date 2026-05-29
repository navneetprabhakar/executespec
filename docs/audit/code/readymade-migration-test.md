# Audit: `readymade/` + `migration/` (recipe catalogs) + `test/` (diagnostic controllers) + main

Status: **DONE** · Updated 2026-05-29 · 49 files / ~5,700 lines · all read in full · **completes all backend Java (Tiers 1–7)**

## What it is
- **`readymade/`** (24) — the "Readymade Solutions" library (drop-in features: Login-with-Google, Stripe checkout, …). Catalog (`ReadymadeSolution`+`Variant`+`Slot`+`Dependency`+`Favourite`) seeded from `classpath:readymades/*.yml`; `ReadymadeService` reads, `VariantResolver` picks a variant from the repo's detected stack, `ReadymadeOrchestrator.instantiate` composes a spec + dispatches.
- **`migration/`** (24) — the parallel "Migration Recipes" library (Express→Fastify, Mongoose→Prisma, …). Same shape + a `MigrationDryRunService` (halt-and-inspect: per-repo applicability + cost band + cached sample diff, **no LLM**) and `MigrationDryRunCache`.
- **`test/`** (9) — `/api/v2/test/*` diagnostic controllers that drive the real planner/worker/router/reviewer/executor/tools against canned specs in temp workspaces.
- **`ExecutespecApplication`** — the Spring Boot main (excludes Spring-AI auto-configs for graceful no-API-key startup; CLI retired).

## Findings — well-built products with ONE real cross-tenant gap; test/ is correctly fenced.

### 🟡 IDOR — readymade/migration project-scoped operations have NO project-ownership guard
`ReadymadeOrchestrator.instantiate`, `MigrationOrchestrator.apply`, `MigrationDryRunService.dryRun`, and `VariantResolver.resolve` all take a **user-supplied `projectId`**, `projectRepo.findById(it)` it with **no ownership check**, and proceed. The controllers (`/api/library/{slug}/instantiate`, `/api/migrations/{slug}/{apply,dry-run}`, `/api/library/{slug}/resolve`) call **no `projectAccessGuard`** — they only check tier (recipe/solution `Tier` ladder) + auth-present. `ExecutionOrchestrator.createExecution` (the dispatch target) explicitly **trusts its caller** (stamps `project.getOrgId()`, no internal guard — confirmed lines 157-216). So an authenticated user who supplies **another org's `projectId`** can:
- **apply/instantiate** → create a real (metered) `ExecutionRun` **stamped with the victim org's orgId** → cross-tenant billing/quota attribution + a phantom run in the victim's project. (Repo cloning still uses the *caller's* PAT, so actual code access is PAT-bounded; the linkage check on `repoIds` further constrains — but the run row + cost attribution land cross-org.)
- **dry-run/resolve** → cross-tenant info disclosure: confirm which repos are linked to the victim's project + their detected stack + cached sample diff + cost bands.
- **Why it slipped:** `AccessGuardCoverageTest` only pins URLs containing `/projects/{`,`/runs/{`,`/repos/{`,… — `/api/library/*` and `/api/migrations/*` match none, so the ArchUnit guard is blind here. **Fix:** add `projectAccessGuard.assertCanWrite(projectId, userDetails)` (apply/instantiate) / `assertCanRead` (dry-run/resolve) at the controller entry. Mitigated today by projectId being an unguessable UUID.
- 📝 Both controllers' javadocs say apply/instantiate is "stubbed in W3 — returns 501" but the code is **fully wired** (R-331/R-339). Stale doc.

### 🟢 The meta-finding this completes: `AccessGuardCoverageTest` has URL-pattern blind spots
Across the audit, **4 mutating surfaces** with non-matching URL shapes either under-guard or skip the guard, all invisible to the ArchUnit pin:
1. `rollout` `POST /api/v2/runs` — no guard, no tier/quota/cost ([tier7-longtail.md](tier7-longtail.md)).
2. `prreview` `POST /api/pr-reviews/{id}/autofix` — `assertCanRead` on a code-pushing action ([review-autofix.md](review-autofix.md)).
3. `readymade`/`migration` instantiate/apply/dry-run/resolve — no guard (this doc).
The test should broaden its matcher set (or guards should be enforced by a method-level annotation the test can see) so new URL shapes can't silently miss tenancy.

### 🟢 test/ is correctly fenced (not a prod exposure)
All 9 `/api/v2/test/*` controllers are **`@Profile({"local","dev"})`** (absent in prod/gcp) **AND** `/api/v2/test/**` → SUPER_ADMIN in SecurityConfig (RL-2026-05-027). Clean diagnostic harnesses: temp workspaces deleted in `finally`, canned specs, no user data. `NativeToolsTestController` calls provider beans directly — intentional (it *proves* native tool calling), profile-gated, non-prod.

### 🟢 Everything else clean + well-built
- 🟢 **Dispatch goes through the metered path.** Both orchestrators dispatch via `ExecutionOrchestrator.createExecution` (the project "Launch" lane with the credit/cost system) — NOT the unmetered `rollout` path. Tier-gated (`isLocked`), slot-validated (`SlotValidator` incl. FILE_PATH traversal guard + unknown-key rejection), spec composed (`SpecComposer`, secrets-not-logged).
- 🟢 **Seeders are safe + idempotent** — `SafeYamlLoader` (no arbitrary deserialization), upsert-by-slug, delete-and-replace children, per-file try/catch, `@PostConstruct` boot-load + SUPER_ADMIN on-demand reseed (`ReadymadeAdminController`/`MigrationAdminController`). Hand-rolled JSON serialization with a proper `escape()` (minor — could use Jackson).
- 🟢 **Correct Spring self-invocation handling** — `deleteBy*` repo methods carry their own `@Transactional` because the seeder calls `reseed()` via `this` (bypassing its own `@Transactional` proxy). Documented + correct.
- 🟢 **Dry-run is genuinely LLM-free** (cost bands from recipe/cache, sample diff cache-only) — a good cost-aware "halt-and-inspect" gate. F-05 added the recipe-tier gate (was leaking applicability+cost to FREE on locked recipes); the *project-ownership* gate is the one still missing (above).
- 📝 **Forward-staged W8 columns** on `MigrationRecipe` (`deterministic_recipe_ref`, `ai_fallback_prompt_path`, `preservation_contract`) for a future deterministic-AST migration path — documented, currently AI-only. Same "forward-staged" pattern seen elsewhere, honestly labelled.

## Cross-refs
Metered dispatch path → [execution.md](execution.md) (`ExecutionOrchestrator.createExecution` — confirmed no internal tenant guard). Unmetered contrast → [tier7-longtail.md](tier7-longtail.md) (`rollout`). AccessGuardCoverageTest blind-spot meta → [review-autofix.md](review-autofix.md), [state.md](state.md) (the test itself). SlotValidator/SpecComposer → [tier7-longtail.md](tier7-longtail.md) (`composer/`). Tier ladder → `auth/`+`billing/` (Tier 5). Test controllers SUPER_ADMIN+profile → [auth.md](auth.md) SecurityConfig.
