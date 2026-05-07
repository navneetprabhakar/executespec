# Single-surface retirement plan

**Date:** 2026-05-06.
**Goal:** Retire the v2 chrome + everything pre-GA shouldn't carry. One Shell, one navigation model, one mental model.

---

## Audit findings

### Feature branches — nothing to pull
| Branch | Ahead of `dev` | Status |
|---|---|---|
| `feature/product-split-prep` | (no longer on origin) | Already merged: V143/V144, VerdictPill, Glossary, ApiContractTest |
| `feature/code-review-agents` | 0 | Already merged: V172, codereview/, prreview/ |
| `feature/enterprise-readiness-eval` | 0 | Already merged: contracts/, standards/, adr/, V131-V139 |
| `feature/hierarchical-planner` | 0 | Already merged: SmartClassifier, OutlinePlanner, PlanStrategyCircuitBreaker |
| `feature/worker-runaway-fixes` | 0 | Already merged: WorkerAgent stagnation logic |

The CLAUDE.md was written before these landed and never updated. The branches are safe to delete.

### Surface mismatch on the SPA
Two coexisting chromes:
- **v2 (AppLayout)** — left sidebar shell, used by `/runs`, `/admin/*`, `/audit`, `/invoices`, `/accounting`, `/exec`, `/org/*`, `/projects/:projectId/dashboard`, `/projects/:projectId/executions/*`, `/welcome`, `runs/new/sandbox`, `/deployments`, `legacy/billing`.
- **v3 (Shell)** — top-bar shell, used by `/projects/:slug`, `/projects/:slug/runs/:runShort`, `/projects/empty`, `/insights`, `/billing`, `/pricing`, `/settings`, `/integrations`, `/team`, `/trust`, `/library`, `/migrations`, `/exec-demo`, `/home`, `/claim`.

Same auth, same data, two different navigations. New users see both within 10 seconds of registering.

---

## Decision

**Single surface = Shell (top-bar).** Everything else gets moved or deleted.

Two project pages survive **for now** because they serve different jobs and porting one into the other is a multi-day refactor we don't need pre-GA:

- `/projects/:slug` — chat-thread + composer (v3 ProjectHomePage)
- `/projects/:projectId/dashboard` — dense KPI / tabs view (the page formerly known as ProjectDashboardPage), now rendered in Shell

They cross-link. Day-to-day surface is the chat; deeper inspection routes through the dashboard. We can collapse them in a follow-up once we know which one users actually live in.

---

## Concrete plan (this commit)

### Phase A — chrome unification
1. `App.tsx`: move every protected route from inside `<AppLayout>` into `<Shell>`.
2. Delete `frontend/src/components/layout/AppLayout.tsx`.
3. Delete `frontend/src/components/layout/Sidebar.tsx`.
4. Delete `frontend/src/components/layout/Header.tsx` (the v2 top-bar — Shell has its own).
5. Delete `frontend/src/components/layout/CommandPalette.tsx` (v3 has `components/CmdK.tsx`).
6. Keep `NotificationBell.tsx` + `NotificationPanel.tsx` — Shell wires them in.

### Phase B — drop dead pages
1. `pages/DashboardPage.tsx` — already a redirect to /insights. Drop.
2. `pages/PlansPage.tsx` — already a redirect to /pricing. Drop.
3. `pages/BillingPage.tsx` — superseded by `pages/BillingPage.tsx`. Drop.
4. `pages/RunsPage.tsx` — admin/dev run-list view. Drop the route + redirect /runs to /home; the avatar dropdown surfaces the relevant admin views.
5. `pages/SharedProjectPage.tsx` is a stub but useful — keep as-is (the audit flagged it but the page is intentional).

### Phase C — defer
- Multi-day work to merge `/projects/:slug` and `/projects/:projectId/dashboard` into one route. Skip for now; revisit after GA.
- Deleting v2 components under `components/runs/`, `components/dashboard/`. Many still used by `ProjectDashboardPage`. Audit + delete in a follow-up — they don't affect the user-facing surface today.

### Phase D — verify
- mvn compile -q, mvn test-compile -q, npx tsc --noEmit
- Browser walk: login, project home, dashboard, /admin, /audit, /insights, /billing, sign out

---

## Branches to delete after merge

```bash
git branch -D feature/code-review-agents feature/enterprise-readiness-eval \
  feature/hierarchical-planner feature/worker-runaway-fixes \
  audit/v2-5-11-ux-product chore/google-style-refactor \
  feature/conversion-redesign feature/pr-refresh-loader-ux \
  feature/project-cost-pool-tuning feature/ux-* fix/* hotfix/* \
  refactor/nomenclature-pr1-services
```

Same upstream — they're all behind `dev`.

---

## What's NOT touched in this commit

- The PR review / Code Review agents (already shipped).
- The hierarchical planner (already shipped).
- Contracts / Standards / ADRs (already shipped).
- The product-split groundwork (V143 schema). Pre-GA we have one product; the schema discriminator is dormant. We can drop V143 in a future migration if/when we decide the split is permanently off-strategy. Keeping for now is harmless.
