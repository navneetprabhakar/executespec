# System docs + repository cleanup plan — 2026-06-10

## Scope

This cleanup runs after the model-first routing work lands. The goal is to make the repository smaller,
more current, and less misleading:

- Recreate the system documents from source truth.
- Clean `docs/` down to curated, current documents.
- Delete superseded point-in-time artifacts once their decisions are captured in canonical docs.
- Remove stale comments and implementation notes that now mislead readers.

This is not a formatting pass. Any deletion must be backed by one of: replacement in a canonical doc,
supersession by a newer plan/report, no code reference, or git-history recoverability.

## Source-of-truth order

1. Code and config in the current branch.
2. Flyway migrations and API DTO/controller contracts.
3. Current review docs in `review/`, especially:
   - `review/09-model-matrix-consistency-plan.md`
   - `review/10-aggregator-architecture-openrouter-fireworks.md`
   - `review/checkpoints/be-router-cost.md`
   - `review/11-ux-review.md`
4. Current run/test evidence and fix report.
5. Existing docs only when they still match source.

## Must regenerate after model-first routing

- `README.md`
- `CLAUDE.md`
- `docs/ARCHITECTURE.md`
- `docs/CODEMAP.md`
- `docs/document_map.md`
- `docs/DEPLOYMENT.md` if environment variables or provider key setup changed.
- `docs/frontend-api-usage.md` if model/provider DTOs or UI API contracts changed.
- `docs/openapi.yaml` if request/response contracts changed.
- `docs/PRD.md`, `docs/USER_MANUAL.md`, and `docs/WORKFLOWS.md` if the UI shifts from provider-first to model-first.
- Per-package `CLAUDE.md` files for touched packages, especially `router/`, `agent/`, `cost/`, `auth/`, `api/`, and frontend docs if UI changes land.

## Immediate cleanup candidates

These are safe candidates to delete or fold, subject to a final grep/reference check in the cleanup PR.

| Candidate | Action | Reason |
| --- | --- | --- |
| `docs/.DS_Store` | Delete | macOS artifact, not documentation. |
| `docs/HANDOFF-2026-06-09.md` | Fold then delete | Handoff content should move into final report, reason-log, and canonical docs after Fireworks/model-route work lands. |
| `docs/plans/review-driven-execution-plan-2026-06-10.md` | Fold then delete | Execution artifact; keep only if it remains the active plan. |
| `docs/plans/review-execution-fix-report-2026-06-10.md` | Keep until final report, then delete or move to `docs/reports/` | Valuable evidence now, but not a long-term system doc. |
| `docs/audit/` | Deleted 2026-06-10 | Superseded audit/action docs overlapped with `review/` and the new review execution reports; traceability remains in git history. `review/` is preserved by instruction. |
| `docs/reviews/` | Deleted 2026-06-10 | Dated review artifacts folded into `tech-debt.md`, `reason-log`, and canonical feature/system docs. |
| `docs/reports/system-audit-updated.*` | Deleted 2026-06-10 | Stale generated audit artifacts linked back to deleted review docs; point-in-time evidence remains in git history. |
| `docs/reports/swebench-exec-brief.*` | Keep if externally quoted | This may be a canonical benchmark result; do not delete unless replaced by benchmark server reporting. |
| `docs/competitive-analysis/**` | Reassess | Keep only if still driving engine/CLI architecture; otherwise fold useful findings into one `docs/design/codegen-engine.md` style doc and delete the rest. |
| Old design/plans under `docs/design/` and `docs/plans/` | Consolidate | Many are useful but overlapping. Fold active work into `ROADMAP.md`, `pending.md`, or package docs. |

## Execution status after 2026-06-10 pass

Completed:

- Deleted `docs/.DS_Store`.
- Deleted the superseded `docs/audit/` tree after explicit permission; `review/` was not touched.
- Deleted `docs/reviews/` and stale `docs/reports/system-audit-updated.{html,pdf}` after folding live references into `docs/tech-debt.md` and `docs/reason-log/2026-05.md`.
- Refreshed `README.md`, root `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/CODEMAP.md`, and `docs/document_map.md` for migration head `V237`, DeepSeek/Fireworks BYOK lanes, OpenRouter latency routing, Fireworks matrix rows, and benchmark/review artifacts.
- Recreated the root `docs/*.md` system/user/ops set from current code paths: `PRD`, `USER_MANUAL`, `WORKFLOWS`, `RBAC`, `JAVADOC`, `ROADMAP`, `pending`, `user-journeys`, `project-user-journeys`, `tech-debt`, cookie/session, DPA/DSAR, enterprise one-pager, enterprise SLA, frontend API usage, payment history, readymades catalog, migration-recipes catalog, SSO/SAML, status/health, Stripe price IDs, and deployment drift fixes.
- Refreshed `src/main/java/com/navneet/executespec/router/CLAUDE.md` and `src/main/java/com/navneet/executespec/agent/CLAUDE.md` for package-level model/key-routing truth.
- Removed one duplicate `Frontend API usage` entry from the live document map by confirming only one row remains.
- Verified with a focused stale-reference scan over root docs, deleted-folder check for audit/review docs, and `git diff --check`.

Preserved deliberately:

- `review/` was not deleted or modified per explicit instruction.
- `docs/HANDOFF-2026-06-09.md`, `review-driven-execution-plan`, and `review-execution-fix-report` were indexed in `docs/document_map.md` instead of deleted because they are still active evidence for this workstream.
- Larger dated design/plan/report artifacts outside the deleted audit/review folders were not deleted in this pass because they need a separate fold/delete decision with `rg` evidence and owner confirmation.

## Code/comment cleanup targets

Do not remove comments merely because they are long. Remove or rewrite comments when they are stale,
duplicate what the code says, reference deleted components, or encode old branch history instead of a
current invariant.

Initial targets from review inventory:

- Frontend stale comments from `review/checkpoints/fe-signal-console.md`, especially auth/spec handoff,
  deleted Signal pages, `useUiStore` Signal references, and deleted `v3` token references.
- Router/model comments that still describe provider-first routing after route-first routing lands.
- Cost-tracker comments that assume OpenRouter pricing follows only Anthropic/OpenAI/Gemini owner
  prefixes.
- Handoff-style comments in code that explain old incidents instead of current behavior. Preserve the
  reason only when it protects a non-obvious invariant.
- Dead commented branch markers like "old", "legacy", and "temporary" where the compatibility path has
  either been deleted or can be expressed as a test.

## Execution phases

### Phase 1 — model-route truth lands

- Implement model-first route manifest and resolver.
- Fix OpenRouter/Fireworks/DeepSeek key lanes and route pricing.
- Add route telemetry and OpenRouter provider routing controls.
- Update API/UI contracts to hide aggregators except key setup.

### Phase 2 — canonical docs rebuild

- Recreate `README.md`, `CLAUDE.md`, `ARCHITECTURE.md`, `CODEMAP.md`, and `document_map.md` from code.
- Add a concise model-routing section: model, vendor, route, transport, override hierarchy, free-tier
  behavior, BYOK semantics, route-first failover.
- Remove stale provider-first examples and obsolete model twins (`*-native`) from docs once aliases exist.

### Phase 3 — docs deletion pass

- Produce a deletion list with `rg` evidence for each file.
- Delete macOS/build artifacts immediately.
- Delete or fold dated plans/reviews/audits after their active decisions are represented elsewhere.
- Regenerate `docs/document_map.md` so it only lists live curated docs.

### Phase 4 — stale code-comment pass

- Start with grep-verified stale comments from `review/checkpoints/fe-signal-console.md`.
- Then sweep touched backend packages.
- Keep comments that explain invariants, security decisions, wire-protocol quirks, or migration
  compatibility.
- Delete comments that narrate obvious code or describe deleted surfaces.

### Phase 5 — verification

- `git diff --check`
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -DskipTests compile -q`
- Focused backend tests for router/cost/key resolution.
- `npm --prefix frontend run build`
- `npm --prefix frontend run lint`
- Live smoke both servers for model picker/key setup/run submission surfaces if UI changed.

## Deletion rule

A doc can be deleted when all are true:

1. It is not externally canonical or customer-facing.
2. No current doc map entry depends on it, or the entry is being updated in the same change.
3. `rg` finds no live code/doc reference that still expects it.
4. Any current decision in it is represented in a surviving canonical doc.
5. Recovery from git history is acceptable.

## Non-goals

- Do not delete benchmark result artifacts that are still used for advisor/customer claims.
- Do not rewrite every historical reason-log entry.
- Do not remove compatibility comments for live legacy data paths unless tests cover the compatibility.
- Do not clean comments in unrelated packages while model-route work is still unstable.
