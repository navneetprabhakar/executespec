# Wave 3 — SSO/SAML + GitHub App + procurement one-pager + team trial

> **Window:** 2026-05-21 → 2026-06-03 (2 weeks)
> **Status:** PLANNED. V127 schema shipped; controller/flow work is incremental.
> **Origin:** [persona-roadmap-2026-04-22.md §5.3](persona-roadmap-2026-04-22.md).

## Scope (in-repo code)

### W3.1 — Activation-driven iteration
- **Depends on:** Wave 2 baseline (data from PostHog funnel after 2 weeks live).
- **Out of scope for this wave** until baseline is captured. Commit a follow-up doc from `docs/reports/activation-funnel-baseline.md` after Wave 2 ships + 14 days of data.

### W3.2 — SSO/SAML at MAX (+ Google OIDC enforcement)
**Schema (V127):**
- `org.sso_provider VARCHAR(32)` — null / `GOOGLE_OIDC` / `OKTA_SAML` / `AZURE_AD_SAML`
- `org.sso_enforced BOOLEAN NOT NULL DEFAULT FALSE`
- `org.sso_metadata_url VARCHAR(1024)` — SAML metadata URL
- `org.sso_domain VARCHAR(255)` — Google Workspace domain (hd= param)

**Backend work (not yet committed):**
- `OrgSsoController` — `PUT /api/org/me/sso` (ORG_ADMIN) to set provider + metadata
- `AuthService.login()` — reject email+password when `sso_enforced=true` + user's email domain matches `sso_domain`; redirect to `/api/auth/oauth2/authorize?registrationId=google&hd=<domain>`
- `SamlConfig` — wire `spring-security-saml2-service-provider` starter, one registration per enforced org (lazy-loaded from `sso_metadata_url`)
- Frontend `/org/settings/sso` page — SSO provider picker, test-SAML-connection button

**Non-goals:**
- ENTERPRISE-only SSO gate (this ships SSO at MAX per persona roadmap §5.3 W3.2)
- Multi-IdP per org (one SSO provider per org in v1)

### W3.3 — GitHub App (org-wide install)
**Schema (V127):**
- `org.github_installation_id BIGINT` — numeric id from GitHub webhook
- `org.github_installed_at TIMESTAMPTZ` — audit + dashboard

**Backend work:**
- Create ExecuteSpec GitHub App in the Anthropic org's GitHub settings (webhook + `repo` + `pull_request` + `workflow` scopes)
- `GitHubAppClient` — server-to-server install-token minting via JWT + `POST /app/installations/{id}/access_tokens`
- `GitHubWebhookController` — handle `installation.created` / `installation.deleted` events to write `org.github_installation_id`
- `GitHubRepoService` — prefer App token over `AppUser.githubPat` when the org has an installation id
- Frontend "Install GitHub App" button on org settings → GitHub install flow → callback

**Fallback:** per-user PAT still works; App is preferred but not required. Useful for solo developers.

### W3.4 — Team trial CTA + procurement one-pager
**Schema (V127):**
- `team_trial_request` table — work email, company, team size, intended use, UTM attribution, status machine (PENDING / CONTACTED / CONVERTED / DECLINED)

**Backend:**
- `POST /api/onboarding/request-trial` — public (rate-limited to 3/min per IP)
- `GET /api/admin/team-trials` (SUPER_ADMIN) — list queue
- `PUT /api/admin/team-trials/{id}` — update status + convert_org_id

**Frontend:**
- `TrialCtaButton` component on PlansPage hero + website pricing page
- `RequestTrialModal` — 4 fields (email, company, team size, intended use) + thank-you state
- `/admin/team-trials` page — SUPER_ADMIN queue management

**Content:**
- `docs/enterprise-one-pager.md` — checked into repo for email-attachment use
- `website/enterprise.html` — public procurement page linking to the one-pager PDF + MSA/DPA templates

## What's committed in Wave 3 (first pass)

- V127 migration (schema only — all columns nullable/safe defaults)
- `docs/enterprise-one-pager.md` (content)
- This plan doc

Subsequent commits inside the Wave 3 window add controllers, services, frontend.

## Manual work / external dependencies

- GitHub App creation in the Anthropic org's GitHub settings (5 min, UI)
- Legal review of MSA/DPA templates before publishing (external)
- Google Workspace OIDC registration for enforced SSO (customer-side, not ours)
- Okta/Azure AD SAML: document integration steps; implementation is per-customer during onboarding

## Timeline

| Day | Work |
|---|---|
| 1 | V127 schema + this plan doc (shipped) |
| 2 | `OrgSsoController` + frontend `/org/settings/sso` |
| 3 | `AuthService.login()` SSO enforcement branch |
| 4 | GitHub App registration + `GitHubAppClient` skeleton |
| 5 | Webhook handlers for `installation.*` events |
| 6 | `GitHubRepoService` prefers App token when installation id present |
| 7 | `team_trial_request` endpoints + `/admin/team-trials` page |
| 8 | TrialCtaButton + RequestTrialModal on PlansPage |
| 9 | `docs/enterprise-one-pager.md` + `website/enterprise.html` |
| 10 | End-to-end dev smoke + commit validation report |

## Success criteria

- [ ] Org admin can enable Google OIDC + enforce SSO for their org
- [ ] User attempting email+password login on an SSO-enforced org gets redirected to Google OAuth cleanly
- [ ] GitHub App installable on a test org; subsequent runs use App token (no PAT required)
- [ ] Team trial CTA visible on PlansPage; submission writes a row; admin can triage
- [ ] Enterprise one-pager linkable from the pricing page

## Non-goals (explicit)

- SCIM user provisioning (ENTERPRISE tier, separate future plan)
- Just-in-time user provisioning via SAML (provisioning is still invite-based)
- IdP-initiated SSO (our v1 is SP-initiated only — login flow starts on our site)
- Multi-domain SSO for the same org (single `sso_domain` value)
