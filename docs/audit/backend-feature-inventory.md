# Backend Feature & Capability Inventory

> Generated 2026-05-22. Source of truth: every `@RestController` / `@Controller` under
> `src/main/java/com/navneet/executespec/`. Purpose: an exhaustive catalogue of every backend
> capability so we can detect which ones the UI never surfaces.

## How to read this

- **Controller count:** `grep -rl "@RestController\|@Controller"` returns **104 files**. Of those,
  **3** are `@RestControllerAdvice` exception handlers (`GlobalExceptionAdvice`,
  `OptimisticLockAdvice`, `ValidationAdvice`) and **1** is the SPA static-asset fallback
  (`SpaFallbackController`). That leaves **100 REST controllers** that expose API endpoints.
- **Endpoint count:** **448 endpoint methods** catalogued below — **438** on the customer/prod
  surface plus **10** non-prod `/api/v2/test/*` debug endpoints. (The SPA fallback's 7 catch-all
  mappings are described in prose, not tabulated, and are excluded from this count.)
- **Access column** — derived from class/method `@PreAuthorize`, in-controller guard calls
  (`ProjectAccessGuard`, `RunAccessGuard`, `requireOrgAdmin`, `assertTierAndQuota`, …), and the
  central `SecurityConfig` matcher rules. Key `SecurityConfig` rules that override per-controller
  silence:
  - `/api/admin/**` → **SUPER_ADMIN** (so admin controllers without an explicit annotation are
    still SUPER_ADMIN-gated).
  - `/api/auth/**` → **Public** except `/api/auth/me`, `/api/auth/me/**`, `/api/auth/settings/**`,
    `POST /api/auth/change-password` (all Authenticated).
  - Explicit **Public** (no auth): payment + GitHub webhooks, `/api/health`, `/api/shared/**`,
    `GET /api/reports/shared/*`, `GET /api/invites/preview`, `/api/v3/anonymous-runs[/*]`,
    `GET /api/v3/referrals/lookup`, `/api/v3/landing/*`, `/api/v3/public/**`,
    `/api/runs/*/events` (SSE — token in query param), `/api/integrations/slack/oauth/callback`,
    `/api/integrations/*/webhook`, `POST /api/internal/reminder/send`, `/api/v2/test/**`.
  - Everything else under `/api/**` → **Authenticated**.
- **"Tier-gated"** means the controller/service enforces a minimum subscription tier (FREE / PRO /
  TEAM / ENTERPRISE) in addition to authentication.

---

## Auth & account

Controllers: `AuthController` (`/api/auth`), `DsarController` (`/api/dsar`),
`SsoConfigController` (`/api/auth/sso`). OAuth2 login (Google/GitHub) is handled by the Spring
Security filter chain at `/api/auth/oauth2/authorize` + `/api/auth/oauth2/callback/{provider}` (no
dedicated controller).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | Register a new account (auto-provisions personal org) | Public |
| POST | `/api/auth/login` | Email/password login → access + refresh JWT | Public |
| POST | `/api/auth/refresh` | Exchange refresh token for a new access token | Public |
| DELETE | `/api/auth/logout` | Revoke session / log out | Public |
| GET | `/api/auth/verify-email` | Verify email via emailed token | Public |
| POST | `/api/auth/resend-verification` | Resend the email-verification link | Public |
| POST | `/api/auth/forgot-password` | Trigger password-reset email | Public |
| POST | `/api/auth/reset-password` | Reset password with reset token | Public |
| POST | `/api/auth/change-password` | Change password for the logged-in user | Authenticated |
| GET | `/api/auth/me` | Current user profile + org-member context | Authenticated |
| POST | `/api/auth/invalidate-sessions` | Force-logout all of my sessions (JWT epoch bump) | Authenticated |
| GET | `/api/auth/me/permissions` | Tier-derived permission map for the UI | Authenticated |
| GET | `/api/auth/me/data-export` | GDPR self-service data export (ZIP) | Authenticated |
| DELETE | `/api/auth/me/data` | GDPR right-to-erasure (delete my data) | Authenticated |
| PUT | `/api/auth/settings/github` | Save personal GitHub PAT | Authenticated |
| POST | `/api/auth/settings/github/validate` | Validate a GitHub PAT before saving | Authenticated |
| PUT | `/api/auth/settings/bitbucket` | Save personal Bitbucket credentials | Authenticated |
| POST | `/api/auth/settings/bitbucket/validate` | Validate Bitbucket credentials | Authenticated |
| PUT | `/api/auth/settings/ai-keys` | Save personal BYOK LLM provider keys | Authenticated |
| POST | `/api/auth/settings/ai-keys/validate` | Validate a BYOK LLM key | Authenticated |
| PUT | `/api/auth/settings/jira` | Save personal Jira integration settings | Authenticated |
| POST | `/api/auth/settings/jira/validate` | Validate Jira credentials | Authenticated |
| PUT | `/api/auth/settings/confluence` | Save personal Confluence integration settings | Authenticated |
| POST | `/api/auth/settings/confluence/validate` | Validate Confluence credentials | Authenticated |
| PUT | `/api/auth/settings/view-mode` | Persist UI chrome preference (V2 sidebar vs V3 topbar) | Authenticated |
| GET | `/api/auth/generate` | Dev helper: bcrypt-hash a password | Public (dev utility) |
| GET | `/api/dsar/me` | DSAR — export my personal data as JSON | Authenticated |
| GET | `/api/dsar/users/{userId}` | DSAR — export any user's data (compliance) | SUPER_ADMIN |
| GET | `/api/auth/sso/config` | Read org SSO (SAML/OIDC) configuration | ORG_ADMIN / SUPER_ADMIN |
| PUT | `/api/auth/sso/config` | Create/update org SSO configuration | ORG_ADMIN / SUPER_ADMIN |
| DELETE | `/api/auth/sso/config` | Delete org SSO configuration | ORG_ADMIN / SUPER_ADMIN |

## Org & members

Controller: `OrgController` (`/api/org`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/org` | Create an organization | Authenticated |
| GET | `/api/org/me` | Get my org's details | Authenticated |
| PUT | `/api/org/me` | Update org name/settings | ORG_ADMIN / SUPER_ADMIN |
| GET | `/api/org/me/members` | List org members | Authenticated |
| DELETE | `/api/org/me/members/{userId}` | Remove a member from the org | ORG_ADMIN / SUPER_ADMIN |
| PUT | `/api/org/me/members/{userId}/suspend` | Suspend a member | ORG_ADMIN / SUPER_ADMIN |
| PUT | `/api/org/me/members/{userId}/role` | Change a member's role | ORG_ADMIN / SUPER_ADMIN |
| POST | `/api/org/invite` | Invite a user to the org by email | ORG_ADMIN / SUPER_ADMIN |
| GET | `/api/org/me/invitations` | List pending org invitations | ORG_ADMIN / SUPER_ADMIN |
| DELETE | `/api/org/me/invitations/{id}` | Revoke a pending invitation | ORG_ADMIN / SUPER_ADMIN |
| POST | `/api/org/me/invitations/{id}/resend` | Resend an invitation email | ORG_ADMIN / SUPER_ADMIN |
| POST | `/api/org/accept-invite` | Accept an org invitation | Authenticated |
| GET | `/api/org/me/audit-logs` | Read org-scoped audit log | ORG_ADMIN / SUPER_ADMIN |
| PUT | `/api/org/me/settings/key-source` | Set key-source mode (SYSTEM / OWN_KEYS / HYBRID) | ORG_ADMIN / SUPER_ADMIN |
| PUT | `/api/org/me/settings/external-sharing` | Toggle per-org external-sharing flag (V198) | ORG_ADMIN / SUPER_ADMIN |
| PUT | `/api/org/me/settings/ai-keys` | Save org-level BYOK LLM keys | ORG_ADMIN / SUPER_ADMIN |
| GET | `/api/org/me/model-matrix` | Effective model matrix (defaults + org overrides + options) | ORG_ADMIN / SUPER_ADMIN |
| PUT | `/api/org/me/model-matrix` | Set per-org (provider,tier)→model overrides (F3) | ORG_ADMIN / SUPER_ADMIN (ENTERPRISE tier) |
| PUT | `/api/org/me/settings/jira` | Save org Jira integration settings | ORG_ADMIN / SUPER_ADMIN |
| POST | `/api/org/me/settings/jira/validate` | Validate org Jira credentials | ORG_ADMIN / SUPER_ADMIN |
| PUT | `/api/org/me/settings/confluence` | Save org Confluence integration settings | ORG_ADMIN / SUPER_ADMIN |
| POST | `/api/org/me/settings/confluence/validate` | Validate org Confluence credentials | ORG_ADMIN / SUPER_ADMIN |
| POST | `/api/org/me/seats/adjust` | Adjust paid seat count (TEAM tier) | ORG_ADMIN / SUPER_ADMIN |

## Billing & credits

Controllers: `BillingController` (`/api/billing`), `BillingWebhookController` (`/api/webhooks`),
`InvoiceController`, `PaymentAuditController`, `AccountingController` (`/api/accounting`),
`AdminBillingController` (`/api/admin/billing`), `TierLifecycleController` (`/api/admin`),
`api/EntitlementsController` (`/api/v3`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/billing/plans` | List subscription plans (YAML-driven pricing rows) | Authenticated |
| GET | `/api/billing/founder-pricing/{tier}` | Founder/promo pricing for a tier | Authenticated |
| GET | `/api/billing/status` | Current subscription + credit balance | Authenticated |
| POST | `/api/billing/subscribe` | Start a subscription (Razorpay/Stripe) | Authenticated |
| POST | `/api/billing/verify` | Verify a subscription payment | Authenticated |
| POST | `/api/billing/cancel` | Cancel the subscription | Authenticated |
| POST | `/api/billing/seats` | Update seat count on the subscription | Authenticated |
| GET | `/api/billing/topup/packages` | List run-credit top-up packages | Authenticated |
| POST | `/api/billing/topup/purchase` | Buy a run-credit top-up | Authenticated |
| POST | `/api/billing/topup/verify` | Verify a top-up payment | Authenticated |
| GET | `/api/billing/profile` | Get billing/tax profile | Authenticated |
| PUT | `/api/billing/profile` | Update billing/tax profile | Authenticated |
| GET | `/api/billing/invoices` | List my invoices | Authenticated |
| GET | `/api/billing/invoices/{id}` | Invoice detail | Authenticated |
| GET | `/api/billing/invoices/{id}/pdf` | Download invoice PDF | Authenticated |
| GET | `/api/billing/payment-history` | Org-scoped payment history | ORG_ADMIN / BILLING_ADMIN / SUPER_ADMIN |
| GET | `/api/v3/entitlements` | Single-source entitlements (tier, limits, gates) for v3 | Authenticated |
| POST | `/api/webhooks/razorpay` | Razorpay payment webhook (HMAC verified) | Public (webhook) |
| POST | `/api/webhooks/stripe` | Stripe payment webhook (signature verified) | Public (webhook) |
| GET | `/api/accounting/revenue` | Revenue metrics | SUPER_ADMIN |
| GET | `/api/accounting/gst-summary` | GST summary | SUPER_ADMIN |
| GET | `/api/accounting/gstr1-export` | Export GSTR-1 file | SUPER_ADMIN |
| GET | `/api/accounting/trial-balance` | Accounting trial balance | SUPER_ADMIN |
| GET | `/api/accounting/entries` | Accounting ledger entries (paged) | SUPER_ADMIN |
| GET | `/api/admin/invoices` | List all invoices (admin) | SUPER_ADMIN |
| GET | `/api/admin/invoices/{id}/pdf` | Download any invoice PDF (admin) | SUPER_ADMIN |
| POST | `/api/admin/invoices/{id}/credit-note` | Issue a credit note | SUPER_ADMIN |
| POST | `/api/admin/invoices/{id}/void` | Void an invoice | SUPER_ADMIN |
| POST | `/api/admin/invoices/{id}/send` | Resend an invoice email | SUPER_ADMIN |
| GET | `/api/admin/seller-config` | Read seller/tax config | SUPER_ADMIN |
| PUT | `/api/admin/seller-config` | Update seller/tax config | SUPER_ADMIN |
| GET | `/api/admin/payment-audit` | Payment audit log (paged) | SUPER_ADMIN |
| GET | `/api/admin/payment-audit/export` | Export payment audit as CSV | SUPER_ADMIN |
| GET | `/api/admin/webhook-events` | Webhook event log | SUPER_ADMIN |
| POST | `/api/admin/billing/seed-plans` | Seed billing plans into DB | SUPER_ADMIN |
| GET | `/api/admin/tiers` | List tier lifecycle states | SUPER_ADMIN |
| PUT | `/api/admin/tiers/{tier}/lifecycle` | Set tier lifecycle (LISTED/HIDDEN/SUNSETTING) | SUPER_ADMIN |
| PUT | `/api/admin/billing-plans/{planId}/lifecycle` | Set a billing plan's lifecycle state | SUPER_ADMIN |

## Run lifecycle

Controllers: `RunController` (`/api/runs`), `RolloutRunController` (`/api/v2/runs`),
`AnonymousRunController` (`/api/v3`), `UserRunsController` (`/api`), `SseController` (`/api/runs`),
`api/SignupClaimController` (`/api/v3/auth`), `api/ReferralController` (`/api/v3/referrals`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/runs` | Submit a spec run | Authenticated |
| GET | `/api/runs` | List runs (paged, visibility/tier filtered) | Authenticated |
| GET | `/api/runs/{runId}` | Run detail (PR URL, score, status reason, costs) | Authenticated (run guard) |
| GET | `/api/runs/{runId}/cost` | Live token + cost snapshot | Authenticated (run guard) |
| POST | `/api/runs/{runId}/clarify` | Submit clarifying context for a run | Authenticated (run guard) |
| POST | `/api/runs/{runId}/merge` | Approve/merge the run's PR | Authenticated (run guard) |
| POST | `/api/runs/{runId}/retry` | Retry an entire run | Authenticated (run guard) |
| POST | `/api/runs/{runId}/fork` | Fork a run into a new run | Authenticated (run guard) |
| POST | `/api/runs/{runId}/pause` | Pause an active run (flag-gated) | Authenticated (run guard) |
| POST | `/api/runs/{runId}/resume` | Resume a paused run (same JVM) | Authenticated (run guard) |
| POST | `/api/runs/{runId}/edit-and-restart` | Edit the spec and restart the run | Authenticated (run guard) |
| PATCH | `/api/runs/{runId}/project` | Move/assign a run to a project | Authenticated (run guard) |
| DELETE | `/api/runs/{runId}` | Cancel a run (signals gate, closes PRs) | Authenticated (run guard) |
| POST | `/api/runs/{runId}/archive` | Archive a run | Authenticated (run guard) |
| POST | `/api/runs/{runId}/unarchive` | Unarchive a run | Authenticated (run guard) |
| POST | `/api/runs/{runId}/soft-delete` | Soft-delete a run | Authenticated (run guard) |
| POST | `/api/runs/{runId}/restore` | Restore a soft-deleted run | Authenticated (run guard) |
| POST | `/api/runs/{runId}/tasks/{taskId}/retry` | Retry a single failed task | Authenticated (run guard) |
| POST | `/api/runs/{runId}/implement-suggestions` | Apply reviewer/chat suggestions as a run | Authenticated (run guard) |
| POST | `/api/runs/{runId}/rework` | Re-run with rework instructions | Authenticated (run guard) |
| POST | `/api/runs/{runId}/reopen-merge` | Reopen a closed merge | Authenticated (run guard) |
| GET | `/api/runs/{runId}/recovery` | Recovery info for a stuck/failed run | Authenticated (run guard) |
| GET | `/api/runs/{runId}/messages` | Run pipeline message/event log | Authenticated (run guard) |
| GET | `/api/runs/{runId}/task-graph` | Task dependency graph | Authenticated (run guard) |
| GET | `/api/runs/{runId}/sse-token` | Mint a scoped SSE token (5-min TTL) | Authenticated (run guard) |
| GET | `/api/runs/{runId}/events` | SSE event stream for a run | Public (token in query param) |
| GET | `/api/user-runs-summary` | Aggregate of my runs for the dashboard | Authenticated |
| POST | `/api/v2/runs` | V2 rollout-shaped run submit (canary surface) | Authenticated |
| GET | `/api/v2/runs/{runId}` | V2 rollout-shaped run detail | Authenticated |
| POST | `/api/v3/anonymous-runs` | Anonymous trial run (no signup) | Public (rate-limited) |
| GET | `/api/v3/anonymous-runs/{sessionToken}` | Poll anonymous-trial session status | Public |
| POST | `/api/v3/auth/claim-anonymous-session` | Claim an anonymous session after signup | Authenticated |
| POST | `/api/v3/referrals/invite` | Send a referral invite (growth loop) | Authenticated |
| GET | `/api/v3/referrals/state` | My referral invite state | Authenticated |
| GET | `/api/v3/referrals/lookup` | Resolve a referral token (magic-link landing) | Public |

## Plan / approval

Controllers: `RunController` (`/api/runs`), `RunConversationController` (`/api/runs`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/runs/{runId}/approve` | Approve the plan gate (charges credits) | Authenticated (run guard) |
| GET | `/api/runs/{runId}/plan-versions` | List plan versions for a run | Authenticated (run guard) |
| POST | `/api/runs/{runId}/edit-plan` | Edit the plan before approval | Authenticated (run guard) |
| POST | `/api/runs/{runId}/retry-validation` | Re-run post-pipeline validation | Authenticated (run guard) |
| GET | `/api/runs/{runId}/chat` | Get run-scoped chat history | Authenticated (run guard) |
| POST | `/api/runs/{runId}/chat` | Send a message to the run chat | Authenticated (run guard) |
| POST | `/api/runs/{runId}/apply` | Apply a chat suggestion (async worker) | Authenticated (run guard) |
| GET | `/api/runs/{runId}/apply/status` | Poll apply-suggestion status | Authenticated (run guard) |
| POST | `/api/runs/{runId}/iterate` | Iterate a completed run with new instructions | Authenticated (run guard) |

## Validation & scoring

Controllers: `ValidationReportController` (`/api`), `TimelineController` (`/api`),
`MetricsController` (`/api/metrics`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/runs/{runId}/report` | Get the validation report (verified score, slots, issues) | Authenticated (run guard) |
| POST | `/api/runs/{runId}/report/share` | Generate a public share link for the report | Authenticated (run guard) |
| GET | `/api/runs/{runId}/report/pdf` | Download the validation report as PDF | Authenticated (run guard) |
| GET | `/api/reports/shared/{shareToken}` | Public shared validation report | Public |
| GET | `/api/runs/{runId}/timeline` | Per-phase run timeline | Authenticated |
| GET | `/api/model-comparison` | Model-vs-model comparison data | Authenticated |
| GET | `/api/metrics` | Aggregate run/quality metrics for the dashboard | Authenticated |

## Files & code

Controller: `RunController` (`/api/runs`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/runs/{id}/files` | List generated workspace files | Authenticated (run guard) |
| GET | `/api/runs/{id}/files/status` | File-generation status | Authenticated (run guard) |
| GET | `/api/runs/{id}/file` | Read a single workspace file (text) | Authenticated (run guard) |
| GET | `/api/runs/{id}/file/diff` | Diff for a single workspace file | Authenticated (run guard) |
| GET | `/api/runs/{id}/download` | Download the workspace as an archive | Authenticated (run guard) |
| GET | `/api/runs/{runId}/openapi.yaml` | Generated OpenAPI spec (text) | Authenticated (run guard) |

## Source control & sync (push / PR)

Controllers: `RunController` (`/api/runs`), `PullRequestController`, `PrCommentInboxController`
(`/api/runs/{runId}/pr-comments`), `GitHubWebhookController` (`/webhooks`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/runs/{runId}/push-github` | Push run output to GitHub (single-repo) | Authenticated (run guard) |
| POST | `/api/runs/{runId}/push-scm` | Push run output to SCM (multi-repo branch) | Authenticated (run guard) |
| POST | `/api/runs/{runId}/sync` | Reset workspace to current origin HEAD | Authenticated (run guard) |
| POST | `/api/runs/{runId}/sync/original` | Reset workspace to run-start SHAs | Authenticated (run guard) |
| GET | `/api/runs/{runId}/prs` | List PRs opened by this run + status | Authenticated (run guard) |
| POST | `/api/projects/{projectId}/prs/refresh` | Poll GitHub for PR/merge/CI status | Authenticated (project guard) |
| GET | `/api/prs/snapshot` | PR snapshot (head SHA, merge state, CI runs) | Authenticated |
| GET | `/api/runs/{runId}/pr-comments` | List inbound PR review comments for a run | Authenticated (run guard) |
| GET | `/api/runs/{runId}/pr-comments/pending-count` | Count of unresolved PR comments | Authenticated (run guard) |
| POST | `/api/runs/{runId}/pr-comments/{commentId}/dismiss` | Dismiss a PR comment | Authenticated (run guard) |
| POST | `/api/runs/{runId}/pr-comments/{commentId}/apply` | Auto-fix a PR comment (patch run) | Authenticated (run guard) |
| POST | `/webhooks/github` | GitHub PR + check_run webhook (HMAC) | Public (webhook) |

## Preview

Controllers: `PreviewController` (`/api/runs`), `PreviewVmController` (`/api/v1/previews`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/runs/{runId}/preview/analyze` | Analyze run for previewability | Authenticated (run guard) |
| GET | `/api/runs/{runId}/preview/status` | Preview status | Authenticated (run guard) |
| POST | `/api/runs/{runId}/preview/start` | Start an ephemeral preview (5-min TTL) | Authenticated (run guard) |
| POST | `/api/runs/{runId}/preview/rebuild` | Rebuild the running preview | Authenticated (run guard) |
| DELETE | `/api/runs/{runId}/preview/stop` | Stop the preview | Authenticated (run guard) |
| GET | `/api/runs/{runId}/preview/logs` | Preview container logs | Authenticated (run guard) |
| GET | `/api/runs/{runId}/preview/probe-api-docs` | Probe preview for API docs | Authenticated (run guard) |
| POST | `/api/runs/{runId}/preview/exec` | Exec a command inside the preview | Authenticated (run guard) |
| DELETE | `/api/v1/previews/{previewId}` | Tear down a VM-hosted preview | Authenticated |
| POST | `/api/v1/previews/{previewId}/extend` | Extend a preview's TTL | Authenticated |
| GET | `/api/v1/previews/active` | List active previews | Authenticated |
| GET | `/api/v1/previews/stats` | Preview usage stats | Authenticated |
| GET | `/api/v1/previews/stats/vm` | Per-VM preview stats | Authenticated |

## Deployments

Controllers: `DeploymentController` (`/api/deployments`), `CredentialController`
(`/api/credentials/gcp`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/deployments` | Deploy run output to Cloud Run (permanent) | Authenticated |
| GET | `/api/deployments` | List deployments | Authenticated |
| GET | `/api/deployments/{id}` | Deployment detail | Authenticated |
| DELETE | `/api/deployments/{id}` | Delete a deployment record | Authenticated |
| POST | `/api/deployments/{id}/rollback` | Roll a deployment back | Authenticated |
| GET | `/api/deployments/live` | List live deployments | ORG_ADMIN (in-controller guard) |
| POST | `/api/deployments/analyze` | Analyze deploy readiness (preflight) | Authenticated |
| POST | `/api/deployments/confirm` | Confirm + execute a deployment | Authenticated |
| POST | `/api/deployments/{id}/engine-rollback` | Roll back via the deploy engine | Authenticated |
| DELETE | `/api/deployments/{id}/engine-destroy` | Destroy engine-managed infra | Authenticated |
| GET | `/api/credentials/gcp/required-permissions` | List GCP permissions the deployer needs | Authenticated |
| POST | `/api/credentials/gcp` | Store a GCP service-account credential | Authenticated |
| GET | `/api/credentials/gcp` | Get the stored GCP credential summary | Authenticated |
| GET | `/api/credentials/gcp/all` | List all stored GCP credentials | Authenticated |
| GET | `/api/credentials/gcp/validate` | Validate the stored GCP credential | Authenticated |
| DELETE | `/api/credentials/gcp` | Delete the stored GCP credential | Authenticated |

## Projects & workspace

Controllers: `ProjectController` (`/api/projects`), `ProjectDashboardController`,
`ProjectLaunchController`, `ProjectRepoController`, `ProjectScoresController`,
`execution/ScaffoldController`.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/projects` | Create a project | ORG_ADMIN / DEVELOPER / SUPER_ADMIN |
| GET | `/api/projects` | List my projects | Authenticated |
| GET | `/api/projects/{id}` | Project detail | Authenticated (project guard) |
| PATCH | `/api/projects/{id}/settings` | Update project settings | ORG_ADMIN / SUPER_ADMIN |
| PUT | `/api/projects/{id}/archive` | Archive a project | ORG_ADMIN / SUPER_ADMIN |
| PUT | `/api/projects/{id}/unarchive` | Unarchive a project | ORG_ADMIN / SUPER_ADMIN |
| DELETE | `/api/projects/{id}` | Delete a project | ORG_ADMIN / SUPER_ADMIN |
| POST | `/api/projects/{id}/restore` | Restore a deleted project | ORG_ADMIN / SUPER_ADMIN |
| GET | `/api/projects/{id}/members` | List project members | Authenticated (project guard) |
| POST | `/api/projects/{id}/members` | Add a project member | ORG_ADMIN / SUPER_ADMIN |
| DELETE | `/api/projects/{id}/members/{userId}` | Remove a project member | ORG_ADMIN / SUPER_ADMIN |
| GET | `/api/projects/{projectId}/dashboard` | Aggregated project dashboard | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/workspace-health` | Per-repo SHA drift + webhook + open-PR rollup | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/post-merge-bar` | Post-merge quality bar status | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/estimate` | Heuristic p25/p75 task + cost estimate | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/launches` | Paged launch (execution) history | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/overview` | Project overview rollup | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/launches/validate-repo-access` | Validate repo-owner access before a launch | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/scores` | Score-trend feed | Authenticated (project guard) |
| GET | `/api/scaffolds` | List available starter scaffolds | Authenticated |
| GET | `/api/repos/github` | List the caller's GitHub repos | Authenticated |
| POST | `/api/repos` | Register a repo | Authenticated |
| GET | `/api/repos` | List registered repos | Authenticated |
| POST | `/api/repos/{repoId}/sync` | Sync a repo's metadata | Authenticated |
| POST | `/api/repos/{repoId}/webhook` | Install a pull_request webhook on a repo | Authenticated |
| DELETE | `/api/repos/{repoId}` | Remove a registered repo | Authenticated |
| GET | `/api/projects/{projectId}/repos` | List repos linked to a project | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/repos/{repoId}` | Link a repo to a project | Authenticated (project guard) |
| DELETE | `/api/projects/{projectId}/repos/{repoId}` | Unlink a repo from a project | Authenticated (project guard) |
| PUT | `/api/projects/{projectId}/repos/{repoId}/relationships` | Set inter-repo relationships | Authenticated (project guard) |

## Architecture (codemaps / HLD / versions / diff / docs)

Controllers: `ProjectArchitectureController`, `ProjectDocGeneratorController`.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/projects/{projectId}/architecture/generate` | Regenerate codemaps + cross-repo arch doc | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/analyse` | Per-branch arch refresh (caller's PAT) | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/summary` | Project summary doc | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/architecture/staleness` | "STALE" badge status | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/architecture` | Latest architecture doc | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/architecture/versions` | All architecture-doc versions | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/architecture/diff` | Source-file diff between two arch versions | Authenticated (project guard) |
| PUT | `/api/projects/{projectId}/architecture/annotate` | Annotate the architecture doc | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/repos/{repoId}/codemap` | Per-repo codemap | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/repos/{repoId}/architecture` | Per-repo HLD/LLD | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/repos/{repoId}/files` | List a repo's files | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/repos/{repoId}/file` | Read a single repo file | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/docs/{docType}/generate` | Generate a doc (EXPLANATION / SETUP) | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/docs/{docType}` | Get the latest generated doc | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/docs/{docType}/download` | Download the generated doc | Authenticated (project guard) |

## Contracts & drift

Controllers: `ContractConsumerController`, `ContractCoverageController`, `ContractDriftController`.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/projects/{projectId}/contracts/{contractId}/consumers` | List declared contract consumers | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/contracts/{contractId}/consumers` | Declare/pin a contract consumer | Authenticated (project guard) |
| DELETE | `/api/projects/{projectId}/contracts/{contractId}/consumers/{consumerRepoId}` | Remove a contract consumer | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/repos/{repoId}/contract-coverage` | Contract-coverage rollup for a repo | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/repos/{repoId}/contract-coverage/rescan` | Rescan contract coverage | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/contracts/{contractId}/check-drift` | Check drift for one contract | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/repos/{repoId}/check-drift` | Check drift across a repo's contracts | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/check-drift` | Check drift across the whole project | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/contracts/{contractId}/refresh-from-source` | Refresh a contract from source | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/contracts/{contractId}/dismiss-drift` | Dismiss a drift finding | Authenticated (project guard) |

## Standards, ADRs & invariants

Controllers: `OrgStandardController` (`/api/orgs/{orgId}/standards`), `StandardsOverrideController`,
`StandardsComplianceController`, `AdrController`, `InvariantController`.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/orgs/{orgId}/standards` | List org coding standards | ORG_ADMIN / SUPER_ADMIN |
| GET | `/api/orgs/{orgId}/standards/{standardId}` | Get a standard | ORG_ADMIN / SUPER_ADMIN |
| POST | `/api/orgs/{orgId}/standards` | Create a standard | ORG_ADMIN / SUPER_ADMIN |
| PUT | `/api/orgs/{orgId}/standards/{standardId}` | Update a standard | ORG_ADMIN / SUPER_ADMIN |
| DELETE | `/api/orgs/{orgId}/standards/{standardId}` | Delete a standard | ORG_ADMIN / SUPER_ADMIN |
| POST | `/api/projects/{projectId}/standards/violations/{violationId}/override` | Emergency-override a violation | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/standards/{standardId}/exception` | Add a standing exception (≥30-char rationale) | Authenticated (project guard) |
| DELETE | `/api/projects/{projectId}/standards/{standardId}/exception` | Remove a standing exception | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/standards/report` | Standards compliance report | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/adrs` | List ADRs (optionally accepted-only) | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/adrs/{id}` | Single ADR with full body | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/invariants` | List architecture invariants | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/invariants/violations` | Recent invariant violations | Authenticated (project guard) |

## Tech debt

Controller: `ProjectDashboardController`.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/projects/{projectId}/tech-debt` | Issues by category / severity / file hotspots / trend | Authenticated (project guard) |

## Code review

Controller: `CodeReviewController`.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/code-reviews` | Submit a whole-repo code-review scan | Tier-gated (PRO+) |
| POST | `/api/code-reviews/{id}/autofix` | Autofix issues by severity bucket (PR per severity) | Tier-gated (PRO+) |
| GET | `/api/code-reviews/{id}` | Code-review scan + issues | Authenticated |
| GET | `/api/code-reviews/{id}/report` | Download code-review report (markdown) | Authenticated |
| GET | `/api/projects/{projectId}/code-reviews` | List code reviews for a project | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/code-reviews/latest` | Latest code review for a repo | Authenticated (project guard) |

## PR review

Controller: `PrReviewController`.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/pr-reviews` | Submit a PR review | Tier-gated (PRO+) |
| POST | `/api/pr-reviews/{id}/autofix` | Autofix PR-review comments (pushes to PR head) | Tier-gated (PRO+) |
| GET | `/api/pr-reviews/{id}` | PR review detail | Authenticated |
| GET | `/api/pr-reviews` | List PR reviews (by-PR query) | Authenticated |
| GET | `/api/projects/{projectId}/pr-reviews` | List PR reviews for a project | Authenticated (project guard) |

## Project chat & memory

Controllers: `ProjectChatController` (`/api/projects/{projectId}/chat`), `ProjectMemoryController`
(`/api/projects/{projectId}/memory`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/projects/{projectId}/chat` | Get chat history | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/chat/info` | Chat info (compaction state, token count) | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/chat` | Send a chat message | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/chat/compile` | Compile chat into a spec | Authenticated (project guard) |
| DELETE | `/api/projects/{projectId}/chat` | Clear/reset the chat | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/chat/compact` | Compact older chat messages | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/memory` | Project memory snapshot | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/memory/ledger` | Full project run-ledger | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/memory/compact` | Force a memory-snapshot rewrite | Authenticated (project guard) |

## RAG documents

Controller: `ProjectDocumentController` (`/api/projects/{projectId}/documents`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/projects/{projectId}/documents` | Upload a reference doc (async index into pgvector) | Authenticated (project guard) |
| GET | `/api/projects/{projectId}/documents` | List uploaded docs + index status | Authenticated (project guard) |
| DELETE | `/api/projects/{projectId}/documents/{documentId}` | Delete a doc + its embeddings | Authenticated (project guard) |

## Activity feed

Controller: `ActivityFeedController` (`/api/projects/{projectId}/activity`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/projects/{projectId}/activity` | Windowed, filterable project activity feed | Authenticated (project guard) |

## Sharing & invites

Controllers: `ProjectInviteController`, `ProjectShareLinkController`, `ProjectSharePublicController`,
`ProjectExportController`.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/projects/{projectId}/invites` | Invite a cross-org member by email | Tier-gated (PRO+) + ORG_ADMIN + org flag |
| GET | `/api/projects/{projectId}/invites` | List pending project invites | Authenticated (project guard) |
| DELETE | `/api/projects/{projectId}/invites/{inviteId}` | Revoke a project invite | Authenticated (project guard) |
| POST | `/api/invites/accept` | Accept a project invite (email-match) | Authenticated |
| GET | `/api/invites/preview` | Preview an invite by token | Public |
| POST | `/api/projects/{projectId}/share-links` | Create a public read-only share link | Tier-gated (project guard, write) |
| GET | `/api/projects/{projectId}/share-links` | List a project's share links | Authenticated (project guard) |
| DELETE | `/api/projects/{projectId}/share-links/{linkId}` | Revoke a share link | Authenticated (project guard) |
| GET | `/api/shared/{token}/meta` | Public: share-link metadata | Public |
| GET | `/api/shared/{token}/run` | Public: shared run detail | Public |
| GET | `/api/shared/{token}/validation-report` | Public: shared validation report | Public |
| GET | `/api/shared/{token}/code-review` | Public: shared code review | Public |
| GET | `/api/shared/{token}/pr-review` | Public: shared PR review | Public |
| GET | `/api/shared/{token}/architecture` | Public: shared architecture doc | Public |
| POST | `/api/projects/{projectId}/export` | Export all `.executespec/` artefacts as a ZIP | ORG_ADMIN / BILLING_ADMIN / SUPER_ADMIN |

## Spec templates

Controller: `SpecTemplateController`.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/projects/{projectId}/templates` | List spec templates (project + org scoped) | Authenticated (project guard) |
| POST | `/api/projects/{projectId}/templates` | Create a project-scoped spec template | Authenticated (project guard) |
| POST | `/api/orgs/{orgId}/templates` | Create an org-scoped spec template | Authenticated |
| DELETE | `/api/projects/{projectId}/templates/{templateId}` | Delete a spec template | Authenticated (project guard) |

## Library, migrations & onboarding content

Controllers: `ReadymadeController` (`/api/library`), `MigrationController` (`/api/migrations`),
`OnboardingController` (`/api/onboarding`), `LandingController` (`/api/v3`),
`contact/ContactController` (`/api/contact`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/library` | List ready-made spec templates | Authenticated |
| GET | `/api/library/{slug}` | Ready-made template detail | Authenticated |
| GET | `/api/library/categories` | Library category facets | Authenticated |
| POST | `/api/library/{slug}/resolve` | Resolve template variant choices | Authenticated |
| POST | `/api/library/{slug}/instantiate` | Instantiate a ready-made into a run/spec | Authenticated |
| POST | `/api/library/favourite/{slug}` | Favourite a ready-made | Authenticated |
| DELETE | `/api/library/favourite/{slug}` | Un-favourite a ready-made | Authenticated |
| GET | `/api/migrations` | List migration recipes | Authenticated |
| GET | `/api/migrations/{slug}` | Migration recipe detail | Authenticated |
| POST | `/api/migrations/{slug}/dry-run` | Dry-run a migration recipe | Authenticated |
| POST | `/api/migrations/{slug}/apply` | Apply a migration recipe | Authenticated |
| GET | `/api/onboarding/sample-specs` | List onboarding sample specs | Authenticated |
| POST | `/api/onboarding/pick-sample` | Pick a sample spec to start | Authenticated |
| GET | `/api/onboarding/sandbox-status` | Onboarding sandbox status | Authenticated |
| GET | `/api/v3/landing/stats` | Public landing-page stats | Public |
| GET | `/api/v3/landing/sample-runs` | Public landing sample runs | Public |
| GET | `/api/v3/public/exec-snapshot` | Public execution snapshot for the landing page | Public |
| POST | `/api/contact` | Submit contact / feedback form | Authenticated |

## Integrations (GitHub / Slack / Jira / Confluence / Bitbucket)

Controllers: `IntegrationController` (`/api/integrations`), `IntegrationStatusController`,
`SlackIntegrationController` (`/api/integrations/slack`), `GitHubAppController`
(`/api/auth/github-app`), `ConversationController` (`/api/conversation`). (Jira/Confluence/Bitbucket
*credential* endpoints live under Auth & Org above.)

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/integrations/{source}/webhook` | Inbound integration webhook (generic source) | Public (webhook) |
| POST | `/api/integrations/jira/trigger` | Trigger a run from a Jira issue | Authenticated |
| POST | `/api/integrations/confluence/trigger` | Trigger a run from a Confluence page | Authenticated |
| GET | `/api/integrations/runs` | List integration-sourced runs | Authenticated |
| GET | `/api/integrations/status` | Aggregate integration-connection status | Authenticated |
| GET | `/api/integrations/slack/status` | Slack connection status (alias) | Authenticated |
| GET | `/api/integrations/slack/install` | Slack OAuth install URL | Authenticated |
| GET | `/api/integrations/slack/oauth/callback` | Slack OAuth callback | Public (OAuth callback) |
| DELETE | `/api/integrations/slack/disconnect` | Disconnect Slack | Authenticated |
| GET | `/api/integrations/slack/status` | Slack status | Authenticated |
| GET | `/api/integrations/slack/channels` | List Slack channels | Authenticated |
| GET | `/api/integrations/slack/projects/{projectId}/channels` | List project→channel mappings | Authenticated |
| POST | `/api/integrations/slack/projects/{projectId}/channels` | Add a project→channel mapping | Authenticated |
| DELETE | `/api/integrations/slack/projects/{projectId}/channels/{channelId}` | Remove a channel mapping | Authenticated |
| PUT | `/api/integrations/slack/projects/{projectId}/channels/{channelId}/verbosity` | Set channel notification verbosity | Authenticated |
| POST | `/api/integrations/slack/test` | Send a Slack test message | Authenticated |
| GET | `/api/integrations/slack/logs` | Slack notification log | Authenticated |
| GET | `/api/auth/github-app/install-url` | GitHub App install URL | ORG_ADMIN / SUPER_ADMIN |
| GET | `/api/auth/github-app/status` | GitHub App install status | ORG_ADMIN / DEVELOPER / PROJECT_ADMIN / SUPER_ADMIN |
| POST | `/api/auth/github-app/callback` | GitHub App install callback | ORG_ADMIN / SUPER_ADMIN |
| DELETE | `/api/auth/github-app/install` | Uninstall the GitHub App | ORG_ADMIN / SUPER_ADMIN |
| GET | `/api/auth/github-app/install` | Get GitHub App install record | ORG_ADMIN / SUPER_ADMIN |
| POST | `/api/conversation` | Conversational spec-builder turn (pre-run) | Authenticated |

## Notifications

Controllers: `NotificationController` (`/api/notifications`), `NotificationPrefController`
(`/api/notifications/preferences`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/notifications` | List in-app notifications | Authenticated |
| GET | `/api/notifications/unread-count` | Unread notification count | Authenticated |
| POST | `/api/notifications/{id}/read` | Mark one notification read | Authenticated |
| POST | `/api/notifications/read-all` | Mark all notifications read | Authenticated |
| GET | `/api/notifications/preferences` | Get notification preferences | Authenticated |
| PUT | `/api/notifications/preferences` | Update notification preferences | Authenticated |

## Models & pricing

Controllers: `ModelController` (`/api/models`), `PricingController` (`/api/pricing`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/models/available` | Model matrix (YAML-derived) for the picker | Authenticated |
| GET | `/api/pricing` | List pricing rows (per provider/model) | Authenticated |
| GET | `/api/pricing/{provider}/{model}` | Pricing row for one model | Authenticated |
| PUT | `/api/pricing/{provider}/{model}` | Update a model's pricing | SUPER_ADMIN (admin surface) |

## Admin (users / orgs / tiers / flags / config / audit / eval)

Controllers: `SuperAdminController` (`/api/admin`), `AdminAnalyticsController`
(`/api/admin/analytics`), `ActivationFunnelController` (`/api/admin/activation-funnel`),
`ConfigAdminController` (`/api/admin/config`), `FeatureFlagController` (`/api/admin/feature-flags`),
`MigrationAdminController` (`/api/admin/migrations`), `ReadymadeAdminController`
(`/api/admin/readymades`), `ExecutespecMigrationController` (`/api/admin/projects`),
`EvalController` (`/api/admin/eval`), `TierEvalController` (`/api/admin/eval/v2`),
`SwebenchController` (`/api/admin/swebench`), `ProgramBenchController` (`/api/admin/programbench`),
`AdminReminderController` (`/api/internal/reminder`). All `/api/admin/**` paths are SUPER_ADMIN via
`SecurityConfig`.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/admin/guardrail` | Current security guardrail level | SUPER_ADMIN |
| POST | `/api/admin/executions/reconcile` | Mark orphaned non-terminal runs FAILED | SUPER_ADMIN |
| POST | `/api/admin/validation-reports/backfill-verified-score` | Backfill verified scores | SUPER_ADMIN |
| POST | `/api/admin/runs/{runId}/tasks/{taskId}/force-fail` | Force-fail a task | SUPER_ADMIN |
| POST | `/api/admin/runs/{runId}/re-review` | Re-run the reviewer for a run | SUPER_ADMIN |
| GET | `/api/admin/audit-chain/verify` | Verify the tamper-evident audit chain | SUPER_ADMIN |
| POST | `/api/admin/runs/halt-all` | Emergency halt of all active runs | SUPER_ADMIN |
| POST | `/api/admin/prompts/{name}/rollback` | Roll a prompt back to a prior version | SUPER_ADMIN |
| GET | `/api/admin/prompts/{name}/history` | Prompt version history | SUPER_ADMIN |
| POST | `/api/admin/usage-reports/trigger` | Trigger usage-report generation | SUPER_ADMIN |
| GET | `/api/admin/users` | List all users | SUPER_ADMIN |
| GET | `/api/admin/users/{id}` | User detail | SUPER_ADMIN |
| PUT | `/api/admin/users/{id}/status` | Set user status (active/suspended) | SUPER_ADMIN |
| PUT | `/api/admin/users/{id}/tier` | Override a user's tier | SUPER_ADMIN |
| PUT | `/api/admin/users/{id}/role` | Set a user's role | SUPER_ADMIN |
| POST | `/api/admin/users/{id}/grant-runs` | Grant run credits to a user | SUPER_ADMIN |
| GET | `/api/admin/orgs` | List all orgs | SUPER_ADMIN |
| GET | `/api/admin/orgs/{id}` | Org detail | SUPER_ADMIN |
| PUT | `/api/admin/orgs/{id}/status` | Set org status | SUPER_ADMIN |
| PUT | `/api/admin/orgs/{id}/tier` | Override an org's tier | SUPER_ADMIN |
| PUT | `/api/admin/orgs/{id}/run-limit` | Set an org's run limit | SUPER_ADMIN |
| PUT | `/api/admin/orgs/{id}/api-keys` | Set an org's platform API keys | SUPER_ADMIN |
| POST | `/api/admin/orgs/{id}/grant-runs` | Grant run credits to an org | SUPER_ADMIN |
| GET | `/api/admin/tier-config` | List tier configs | SUPER_ADMIN |
| PUT | `/api/admin/tier-config/{tier}` | Update a tier config | SUPER_ADMIN |
| POST | `/api/admin/invite` | Admin-invite a user/org | SUPER_ADMIN |
| GET | `/api/admin/invitations/escalated` | List escalated invitations | SUPER_ADMIN |
| POST | `/api/admin/invitations/{id}/approve` | Approve an escalated invitation | SUPER_ADMIN |
| POST | `/api/admin/invitations/{id}/reject` | Reject an escalated invitation | SUPER_ADMIN |
| GET | `/api/admin/audit-logs` | Global audit log | SUPER_ADMIN |
| POST | `/api/admin/test-email` | Send a test email | SUPER_ADMIN |
| GET | `/api/admin/topup-packages` | List top-up packages | SUPER_ADMIN |
| POST | `/api/admin/topup-packages` | Create a top-up package | SUPER_ADMIN |
| PUT | `/api/admin/topup-packages/{id}` | Update a top-up package | SUPER_ADMIN |
| DELETE | `/api/admin/topup-packages/{id}` | Delete a top-up package | SUPER_ADMIN |
| GET | `/api/admin/analytics/usage` | Usage metrics | SUPER_ADMIN |
| GET | `/api/admin/analytics/members` | Per-member usage | SUPER_ADMIN |
| GET | `/api/admin/analytics/billing` | Billing overview | SUPER_ADMIN |
| GET | `/api/admin/analytics/cost-breakdown` | LLM cost breakdown | SUPER_ADMIN |
| GET | `/api/admin/activation-funnel` | Activation-funnel headline counts | SUPER_ADMIN |
| GET | `/api/admin/config` | List runtime config values | SUPER_ADMIN |
| PUT | `/api/admin/config/{key}` | Set a runtime config value | SUPER_ADMIN |
| DELETE | `/api/admin/config/{key}` | Remove a runtime config value | SUPER_ADMIN |
| GET | `/api/admin/config/secrets` | List secret names | SUPER_ADMIN |
| PUT | `/api/admin/config/secrets/{name}` | Set a secret | SUPER_ADMIN |
| POST | `/api/admin/config/refresh` | Refresh runtime config from GCS | SUPER_ADMIN |
| GET | `/api/admin/feature-flags` | List feature flags | SUPER_ADMIN |
| GET | `/api/admin/feature-flags/public` | Public-facing flag values (for the UI) | Authenticated |
| PUT | `/api/admin/feature-flags/{key}` | Toggle a feature flag | SUPER_ADMIN |
| POST | `/api/admin/migrations/reseed` | Reseed migration recipes | SUPER_ADMIN |
| POST | `/api/admin/readymades/reseed` | Reseed ready-made templates | SUPER_ADMIN |
| POST | `/api/admin/projects/{projectId}/migrate-codemaps` | Migrate legacy codemaps into `.executespec/` | SUPER_ADMIN |
| POST | `/api/admin/eval/run` | Run the (legacy) model-eval framework | SUPER_ADMIN |
| POST | `/api/admin/eval/v2/run` | Run tier-eval-v2 (per-tier × provider) | SUPER_ADMIN |
| POST | `/api/admin/swebench/run` | Submit a SWE-bench task | SUPER_ADMIN |
| GET | `/api/admin/swebench/preview/{instanceId}` | Preview a SWE-bench instance | SUPER_ADMIN |
| GET | `/api/admin/swebench/instances` | List SWE-bench instances | SUPER_ADMIN |
| POST | `/api/admin/programbench/run` | Submit a ProgramBench task | SUPER_ADMIN |
| GET | `/api/admin/programbench/preview/{taskId}` | Preview a ProgramBench task | SUPER_ADMIN |
| POST | `/api/internal/reminder/send` | Internal cron trigger for reminder emails | Public (internal; locked down at infra) |

## Health & ops

Controller: `HealthController` (`/api/health`). Actuator endpoints (`/actuator/**`) are framework-
provided, not controllers: `/actuator/health` + `/actuator/prometheus` are Public; all other
`/actuator/**` require SUPER_ADMIN.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/health` | Liveness probe | Public |
| GET | `/api/health/ready` | Readiness probe (DB/Redis dependencies) | Public |

## Test generation

Controllers: `TestGenController` (`/api`), `TestResultController` (`/api`).

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/runs/{runId}/test-plan` | Generated test plan for a run | Authenticated (run guard) |
| GET | `/api/runs/{runId}/test-graph` | Test dependency graph | Authenticated (run guard) |
| GET | `/api/runs/{runId}/test-report` | Test execution report | Authenticated (run guard) |
| POST | `/api/runs/{runId}/run-tests` | Manually trigger test execution (Beta) | Authenticated (run guard) |
| POST | `/api/runs/{runId}/apply-test-fixes` | Apply auto-generated test fixes | Authenticated (run guard) |
| PUT | `/api/test-results/{id}/mark-invalid` | Mark a generated test as invalid | Authenticated |

## Execution engine (V3 multi-solution lane)

Controller: `ExecutionController`. The V3 "execution" surface (multi-solution architecture-review →
plan → solution decide → runs) parallel to the V2 run lifecycle.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/projects/{projectId}/executions` | Create an execution | Authenticated (project guard) |
| GET | `/api/executions/{id}` | Execution detail | Authenticated |
| GET | `/api/projects/{projectId}/executions` | List executions (paged) | Authenticated (project guard) |
| POST | `/api/executions/{id}/analyse` | Trigger architecture analysis | Authenticated |
| PUT | `/api/executions/{id}/arch-review` | Approve the architecture review | Authenticated |
| POST | `/api/executions/{id}/plan` | Generate the plan | Authenticated |
| PUT | `/api/executions/{id}/plan/approve` | Approve the plan | Authenticated |
| POST | `/api/executions/{id}/solutions/{solutionId}/decide` | Decide on a candidate solution | Authenticated |
| POST | `/api/executions/{id}/solutions/{solutionId}/manual-ready` | Mark a solution manually ready | Authenticated |
| POST | `/api/executions/{id}/resume` | Resume an execution | Authenticated |
| POST | `/api/executions/{id}/abort` | Abort an execution | Authenticated |
| GET | `/api/executions/{id}/solutions/{solutionId}/runs` | Runs under a solution | Authenticated |
| GET | `/api/executions/{id}/report` | Execution report | Authenticated |
| GET | `/api/projects/{projectId}/prs/{prNumber}/reviews` | PR review comments for an execution PR | Authenticated (project guard) |
| GET | `/api/executions/{id}/sse-token` | Mint scoped SSE token for an execution | Authenticated |
| GET | `/api/executions/{id}/events` | SSE event stream for an execution | Public (token in query param) |

## Deprecated / retired endpoints

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| POST | `/api/feasibility` | **Retired (410 GONE)** — feasibility estimate; submit a run instead | Authenticated |

## Non-prod debug surface (`local` / `dev` profiles only)

`@Profile({"local","dev"})` — these `/api/v2/test/*` controllers are not registered in `prod`/`gcp`
and are `permitAll` where they do run. Listed for completeness; **excluded** from the customer
surface count.

| Method | Path | Capability | Access |
| --- | --- | --- | --- |
| GET | `/api/v2/test/context` | Inspect ContextStore internals | Non-prod only |
| GET | `/api/v2/test/executor` | Drive the SequentialExecutor directly | Non-prod only |
| GET | `/api/v2/test/metrics` | Dump custom V2 metrics | Non-prod only |
| GET | `/api/v2/test/native-tools` | Exercise one native tool | Non-prod only |
| GET | `/api/v2/test/native-tools/all` | Exercise all native tools | Non-prod only |
| GET | `/api/v2/test/planner` | Drive the OutlinePlannerAgent directly | Non-prod only |
| GET | `/api/v2/test/reviewer` | Drive the ReviewerAgent directly | Non-prod only |
| GET | `/api/v2/test/router` | Drive the LlmRouter directly | Non-prod only |
| GET | `/api/v2/test/tool-registry` | Inspect the ToolRegistry | Non-prod only |
| GET | `/api/v2/test/worker` | Drive the WorkerAgent directly | Non-prod only |

## SPA static fallback (not an API)

`SpaFallbackController` (`@Controller`) forwards non-asset, non-API paths to `index.html` so the
React SPA can client-side route (`/`, `/favicon.ico`, and up to 5 levels of `/{path:[^\.]*}`
catch-alls). Not a customer capability — listed only to account for the controller.
