# Bucket A restore plan — surface dark backend capabilities (2026-05-22)

> Decision (founder, 2026-05-22): restore backend subsystems that no current UI reaches, in **both**
> Signal (production chrome) and IDE. Source: [`feature-gap-comparison.md`](feature-gap-comparison.md).
> Production users are on Signal, so each feature lands in **Signal first, then mirrored to IDE**.

## Triage

### BUILD (backend ready, clear value, V2/active path)

| Wave | Feature | Endpoints | Where |
| --- | --- | --- | --- |
| A1 | **Run power-ops** — fork, rework, edit-and-restart, archive/unarchive, soft-delete/restore, recovery, run messages log, task-graph | `/runs/{id}/fork,rework,edit-and-restart,archive,unarchive,soft-delete,restore,recovery,messages,task-graph` | Run detail (Result) |
| A1 | **PR-comment inbox** — inbound review comments → dismiss / auto-fix | `/runs/{id}/pr-comments[/pending-count,/{c}/dismiss,/{c}/apply]` | Run detail PR tab |
| A1 | **Plan editing** — edit plan before approval + plan-versions | `/runs/{id}/edit-plan`, `/plan-versions` | Plan gate |
| A2 | **Deployments** — analyze → confirm → create → rollback → detail + live list | `/deployments[/{id},/analyze,/confirm,/{id}/rollback,/live,/engine-rollback,/engine-destroy]` | New Deploy surface |
| A2 | **GCP credentials** — store/list/validate/delete + required-permissions | `/credentials/gcp[/all,/validate,/required-permissions]` | Deploy settings |
| A2 | **Preview management** — status/logs/rebuild/exec + VM stats/active/extend | `/runs/{id}/preview/*`, `/api/v1/previews/*` | Run detail Preview |
| A3 | **Library live** — replace hardcoded stubs with real ready-mades + instantiate/favourite | `/library[,/{slug},/categories,/{slug}/resolve,/instantiate,/favourite]` | Library page |
| A3 | **Migrations live** — real recipes + dry-run/apply | `/migrations[,/{slug},/dry-run,/apply]` | Migrations page |
| A3 | **Onboarding samples** + **spec-template create/delete** | `/onboarding/*`, `/projects/{id}/templates` POST/DELETE | Compose / Templates |
| A3 | **Test depth** — test-plan, test-graph, apply-test-fixes, mark-invalid | `/runs/{id}/test-plan,test-graph,apply-test-fixes`, `/test-results/{id}/mark-invalid` | Result Tests tab |
| A4 | **Admin depth** — user/org mgmt, grant-runs, analytics (usage/members/billing/cost), config+secrets, topup-package CRUD, escalated invites, ops (halt-all, audit-chain, prompt rollback/history, force-fail, re-review, usage-reports) | `/admin/**` (~50) | Admin console |
| A4 | **Accounting/finance** — revenue, GST summary, GSTR-1 export, trial balance, ledger; admin invoicing (credit-note/void/send), seller-config | `/accounting/*`, `/admin/invoices/*`, `/admin/seller-config` | Admin → Finance |
| A4 | **Slack project wiring** — channel mapping, verbosity, test message, logs | `/integrations/slack/projects/*`, `/test`, `/logs` | Integrations |
| A4 | **Misc** — project export ZIP, post-merge bar, project scores/overview/summary, model-comparison, metrics, contact form | various | Project + Insights |
| A5 | **DSAR + right-to-erasure** — export my data (JSON), delete my data | `/dsar/me`, `DELETE /auth/me/data` | Account → Privacy |
| A5 | **SSO config** — SAML/OIDC get/put/delete | `/auth/sso/config` | Org settings |
| A5 | **Org depth** — seat-adjust, suspend-member, audit-logs | `/org/me/seats/adjust`, `/members/{id}/suspend`, `/audit-logs` | Org settings |

### CONFIRM with founder before building
- **V3 multi-solution execution lane** (`/api/executions/*`, 16 eps) — root CLAUDE.md says V2 is "the only execution path"; likely dormant. **Not building unless confirmed live.**

### Already partially surfaced (finish, don't rebuild)
- Run lifecycle core, billing, org members, architecture (Signal), code/PR review (Signal) — covered by Bucket B parity work (Waves 1–4) for the IDE.

## Sequencing principle
Each wave: build in **Signal first** (real users), verify locally against the live backend, commit (ask before push), then mirror the same feature into the IDE chrome. One PR per session on the existing branch.
