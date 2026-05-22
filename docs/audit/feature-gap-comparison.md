# Feature gap comparison — Backend vs Signal vs IDE (2026-05-22)

> Answers: **what can the product do, what does the UI actually expose, and where were features lost?**
> Source documents:
> - [`backend-feature-inventory.md`](backend-feature-inventory.md) — 100 controllers, **448 endpoints**, 31 domains.
> - [`ui-feature-inventory.md`](ui-feature-inventory.md) — 47 Signal screens, 22 IDE-native surfaces.
> - [`api-usage-matrix.tsv`](api-usage-matrix.tsv) — every `api.ts` export × which chrome consumes it.

## Headline numbers

| Measure | Count |
| --- | --- |
| Backend endpoints (prod surface) | **438** |
| Endpoints the frontend client (`api.ts`) wraps | **304** |
| → wired into the running app (any chrome/store/hook) | **145** |
| → **referenced nowhere in the frontend** (client method exists, no caller) | **159** (~153 real after dropping 6 helpers) |
| api fns used by **Signal** pages | 130 |
| api fns used by **IDE** surfaces | **45** |
| api fns in **Signal but dropped from IDE** | **86** |

Two distinct losses, exactly as suspected:

- **Loss #2 — Signal → IDE.** The IDE wires **45** of the 130 endpoints Signal uses. 86 capabilities that work in Signal today are absent from the IDE chrome.
- **Loss #1 — (older chromes) → Signal.** ~153 client methods are wired into *no* current screen, and ~134 backend endpoints aren't even wrapped in `api.ts`. These are whole backend subsystems the current product UI never reaches. (The pre-Signal V2/V3 page code is already gutted to redirect stubs, so this can't be diffed from source — but the backend capability is real and dark in both chromes.)

## Per-domain coverage

Legend: **Full** = primary capability surfaced · **Partial** = some endpoints, key ones missing · **None** = not surfaced.

| Domain | Backend eps | Signal | IDE | Notes |
| --- | --- | --- | --- | --- |
| Auth (login/register/verify/reset) | 31 | Full | Partial | IDE: login works; register/verify/forgot/reset routes are public Signal pages. GDPR export Signal-only. DSAR + right-to-erasure + SSO config: **None** in either. |
| Org & members | 23 | Partial | None (fallback) | Signal OrgMembers/OrgSettings. Seat-adjust, suspend-member, audit-logs thin. IDE has no native org surface. |
| Billing & credits | 38 | Partial | None (fallback) | Signal Billing/Plans. **Accounting/GST/GSTR-1/trial-balance, admin invoicing (credit-note/void/send), seller-config: None in either.** |
| Run lifecycle | 35 | Partial | Partial | Core submit/list/detail/cancel/retry ✓. **fork, pause, resume, soft-delete, restore, unarchive, edit-and-restart, rework, reopen-merge, clarify, merge, recovery, messages, task-graph: dark in both.** |
| Plan / approval | 9 | Partial | Partial | approve ✓ both. **edit-plan, plan-versions, retry-validation: dark in both.** Run chat Signal-only. |
| Validation & scoring | 7 | Full | Partial | IDE missing Timeline; model-comparison + metrics dark in both. |
| Files & code | 6 | Full | Partial | IDE: no workspace download, no real diff on MODIFIED files; openapi.yaml dark in both. |
| Source control / PR | 12 | Partial | Partial | push ✓. **PR-comment inbox (list/pending/dismiss/apply), PR snapshot, sync/sync-original, getRunPrs: dark in both.** |
| Preview | 13 | Partial | Partial | start/stop ✓. status/logs/rebuild/exec/analyze thin. **VM preview mgmt (`/api/v1/previews/*`): None.** |
| Deployments + GCP creds | 16 | Partial | None | Signal DeploymentsPage lists; **create/confirm/rollback/engine-destroy + entire GCP credential CRUD: dark in both.** |
| Projects & workspace | 30 | Full | Partial | IDE missing **link-repo** (can't add a repo), scaffolds, overview, post-merge-bar, scores-trend. |
| Architecture (codemaps/HLD/versions/diff/docs) | 15 | Full | None→Current-only | IDE renders only the current doc. **versions, diff, staleness, annotate, generate, per-repo HLD, generated docs: None in IDE.** |
| Contracts & drift | 10 | Partial | None | Signal Architecture→Contracts. drift check/dismiss/refresh thin even in Signal. |
| Standards / ADRs / invariants | 13 | Partial | None | Signal surfaces report/ADRs/invariants. **Org-standard CRUD: dark in both.** |
| Tech debt | 1 | Full | None | `getTechDebt` + `dispatchTechDebtFix` Signal-only. |
| Code review | 6 | Partial | None | Signal CodeQualityPanel. submit/get-detail/report/list largely dark even in Signal. |
| PR review | 5 | Partial | None | Signal autofix-comments path. **submit/get-detail/list-for-pr/list-for-project: dark.** |
| Project chat & memory | 9 | Full | Partial | IDE: no compact/clear, no memory digest, **no TEAM+ gate**. |
| RAG documents | 3 | Full | None | upload/list/delete docs Signal-only. |
| Activity feed | 1 | Full | Partial | IDE: no filter, no CSV export, no poll. |
| Sharing & invites | 15 | Partial | None | Signal invites + share-links. IDE Members is view+remove only. Project export ZIP dark in both. |
| Spec templates | 4 | Partial | None | Signal templates page; create/delete thin. IDE none. |
| Library / migrations / onboarding | 18 | **Static stubs** | None | **Signal Library + Migrations pages render hardcoded content and call no API** — 18 endpoints dark. Strong example of a Signal-era loss. |
| Integrations | 23 | Partial | Partial | GitHub/Slack connect ✓. **Slack channel mapping/verbosity/test/logs, Jira/Confluence trigger, validate flows: dark or read-only.** |
| Notifications | 6 | Full | None | list/unread/read/prefs Signal-only; IDE has none. |
| Models & pricing | 4 | Partial | Partial | picker ✓. pricing-edit (admin) dark. |
| Admin (users/orgs/tiers/flags/config/audit/eval) | 60 | Partial | Partial | IDE Admin: funnel+tiers+flags only. **~50 admin endpoints dark in both:** user/org management, grant-runs, analytics (usage/members/billing/cost), config+secrets, topup-package CRUD, escalated invites, halt-all, audit-chain verify, prompt rollback/history, force-fail, re-review, usage-reports, swebench/programbench/eval triggers. |
| Execution engine (V3 multi-solution lane) | 16 | None | None | **Entire parallel `/api/executions` surface (arch-review → plan → multi-solution decide → runs + SSE) is dark in both chromes.** |
| Test generation | 6 | Partial | Partial | test-report ✓. **test-plan, test-graph, apply-test-fixes, mark-invalid: dark or IDE-missing.** |

## Bucket A — UI-dark in BOTH chromes (the deeper, pre-Signal loss)

Whole backend subsystems with **no surface in either Signal or IDE**. These are the "we used to have more" features:

1. **Execution engine / multi-solution lane** (16 eps) — architecture-review → plan → candidate-solution decide → per-solution runs, with its own SSE stream. Completely unreachable.
2. **Deployments + GCP credentials** (create/confirm/rollback/engine-destroy + GCP cred CRUD, ~12 eps) — Signal only *lists*; you can't drive a deploy.
3. **Library / Migrations / Onboarding content** (18 eps) — the Signal Library & Migrations pages are hardcoded stubs that call nothing.
4. **Accounting & finance** (revenue, GST summary, GSTR-1 export, trial balance, ledger; admin invoicing: credit-note/void/send/resend, seller-config) — ~11 eps, fully built, zero UI.
5. **DSAR / compliance** — `GET /api/dsar/me`, `GET /api/dsar/users/{id}`, `DELETE /api/auth/me/data` (right-to-erasure). GDPR export exists in Signal; erasure + DSAR do not.
6. **SSO config** (SAML/OIDC get/put/delete) — enterprise login config, no UI.
7. **Run-lifecycle power-ops** — fork, pause/resume, soft-delete/restore, unarchive, edit-and-restart, rework, reopen-merge, clarify, merge-PR, recovery, run messages, task-graph.
8. **PR-comment inbox** — list / pending-count / dismiss / apply-as-fix. PR snapshot. `getRunPrs`.
9. **VM preview management** (`/api/v1/previews/*`) — active list, stats, per-VM stats, extend, teardown.
10. **Admin operations** (~50 eps) — user/org management, grant-runs, tier-config, top-up-package CRUD, analytics (usage/members/billing/cost-breakdown), runtime config + secrets, escalated-invite approval, emergency halt-all, audit-chain verify, prompt rollback/history, force-fail-task, re-review, usage-report trigger, bench/eval triggers.
11. **Org standards CRUD**, **spec-template create/delete**, **project export ZIP**, **Slack channel mapping/verbosity/test/logs**, **model-pricing edit**, **plan editing + plan-versions**, **test-plan / test-graph / apply-test-fixes / mark-invalid**, **contact form**, **model-comparison**, **metrics dashboard**, **post-merge bar**, **project scores-trend / overview / summary**.

## Bucket B — In Signal, dropped from IDE (the IDE regression — 86 endpoints)

Grouped by feature area (all of these work in Signal today):

- **Project Architecture (5 tabs → 1):** versions, diff, staleness, invariants, ADRs, contract coverage, drift check (repo/project), rescan, refresh-from-source.
- **Project Code (6 sub-tabs → 1):** tech-debt + autofix dispatch; code-review scan + report; standards report; generated docs (get/generate/download); health.
- **RAG documents:** list / upload / delete.
- **Project memory:** snapshot, compact.
- **Project chat:** clear, compact (IDE has send/history only).
- **Repos:** `listRepos`, `linkProjectRepo` (IDE can't link a repo to a project).
- **Runs/PRs:** `refreshProjectPrs`, `fetchPrReviewComments` (PR autofix), `getFilesWithStatus`.
- **Run loop:** `getTimeline` (Result Timeline tab), `getRunChat`/`sendRunChatMessage`/`conversationChat` (run chat + iterate-as-new-run), `applyTestFixes`, `estimateRun` (p25/p75).
- **Members & sharing:** `inviteMember`, `removeMember`, `changeMemberRole`, `createShareLink`, `resendInvitation`, `acceptProjectInvite`.
- **Notifications:** all 6 (list/unread/read/read-all/prefs get+update).
- **Org settings:** `getOrgModelMatrix`/`updateOrgModelMatrix` (F3), `updateOrgAiKeySettings`/`updateAiKeySettings`, SSO get/update/clear, Jira/Confluence/Bitbucket settings, external-sharing, `getMyOrg`/`getOrgMembers`/`getOrgInvitations`/`updateOrg`.
- **Account:** notification preferences, GDPR export, full plan-limits.
- **Deployments:** `getDeployments`, `rollbackDeployment`, `deleteDeployment`.
- **Spec templates:** `listSpecTemplates`, `createProjectSpecTemplate`, `deleteSpecTemplate`.
- **Auth pages:** register / verify-email / forgot / reset (Signal public pages; IDE inherits them, not rebuilt).
- **Admin/analytics:** `adminListOrgs`, `getAuditLogs`, `getAnalyticsUsage`, `getAnalyticsBilling`, `getMetrics`, `getModelComparison`, `getPricing`, `retryValidationReport`, `getUserRunsSummary`, `uninstallGitHubApp`, `disconnectSlack`.

The full per-function list is in [`api-usage-matrix.tsv`](api-usage-matrix.tsv) (`signal=1, ide=0`).

## Recommended sequencing

1. **Close the IDE regression (Bucket B)** before any IDE rollout — these features exist and work in Signal; the IDE is simply thinner. Tracked as Waves 1–4 (project Code/Architecture/Repos, run-loop depth, account cluster).
2. **Then decide on Bucket A** per-domain — these are product decisions (e.g. is the V3 execution lane being kept? should Library/Migrations be live or stay static? does Deployments/GCP get a UI?), not just rebuild work. Recommend triaging Bucket A with the founder rather than auto-building.
