# Audit: `adr/` — Architecture Decision Records (cache + planner injection)

Status: **DONE** · Updated 2026-05-29 · 5 files / 553 lines · all read in full

## Files
- `AdrFrontmatterParser.java` (173) — YAML-frontmatter → typed `Parsed` ✓
- `AdrController.java` (139) — list + detail REST ✓ · `AdrInjectionService.java` (119) — `{{PROJECT_ADRS}}` composer ✓
- `Adr.java` (98) entity ✓ · `AdrRepository.java` (24) ✓

## What it does
Caches Architecture Decision Records from `.executespec/arch/adrs/<NNNN>-<slug>.md` (customer git is source-of-truth) into the `adr` table, and injects **ACCEPTED** ADRs into the planner's `{{PROJECT_ADRS}}` so the agent treats prior decisions as binding. Third sibling of the enterprise-readiness trio (`contracts`/`standards`/`adr`).

## Findings — clean, small, correctly wired into the planner.
- 🔵 **`AdrInjectionService` IS wired** into `RunOrchestrator` + `agent/PlannerRequest` (grep-confirmed) — accepted ADRs flow into planner context (same injection shape + threshold pattern as `ContractInjectionService`; gated with the rest of the enterprise inject set, `enterprise.inject`, V211). Live-capable, dormant-by-flag.
- 🟢 **`AdrFrontmatterParser` uses `SafeYamlLoader`** (no unsafe YAML deserialization), collects every validation error in one pass, validates required fields (`id`/`title`/`status`/`date`), tolerates bad cross-ref ids (drops, doesn't fail). Same disciplined parser pattern as `standards/StandardsParser`.
- 🟢 `AdrController` tenant-gated (`projectAccessGuard.assertCanRead`) with cross-project not-found masking (won't leak that an ADR id exists in another project). Note: the class javadoc still says "RBAC: project membership advisory in v1" but the **code does enforce `assertCanRead`** (GA-readiness fix landed) — stale doc comment, behaviour is correct.
- 🟡 **`getAdr` planner tool not wired** (audit L#66, acknowledged in-code) — for projects with >15 ACCEPTED ADRs the planner only gets the INDEX_ONLY teaser list, and the prompt was honestly edited to stop promising tool retrieval ("the getAdr tool is not yet wired"). Same unwired-`getContract`/`getAdr` theme as [contracts.md](contracts.md). Only bites >15-ADR projects.
- 🟢 Forward-compat String status enum (`statusEnum()` → PROPOSED fallback); `findAcceptedForProject` JPQL filters to ACCEPTED. No defects.

## Cross-refs
Injection wired at → [integration.md](integration.md) (`RunOrchestrator`), planner `{{PROJECT_ADRS}}` via `agent/PlannerRequest` (Tier 1). Cross-linked from standards (`Standard.relatedAdrs`) → [standards.md](standards.md). Safe-YAML + all-errors parser twins → [standards.md](standards.md), [contracts.md](contracts.md). Threshold-injection + unwired-fetch-tool twin → [contracts.md](contracts.md).
