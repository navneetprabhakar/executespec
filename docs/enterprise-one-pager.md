# ExecuteSpec — Enterprise tier

> What customers on the ENTERPRISE tier get, derived from the code that
> implements it. Each capability cites the file or migration that makes
> it real.

---

## 1. Unlimited monthly runs (with BYOK)

`TierEnforcementService.effectiveOrgLimit(Org)` in
`src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java`
(lines 538-556):

```java
public int effectiveOrgLimit(Org org) {
  String effectiveTier = org.getTier() != null ? org.getTier() : "FREE";

  // ENTERPRISE with own keys = unlimited (BYOK contract).
  if ("ENTERPRISE".equals(effectiveTier) && org.isApiKeysProvided()) return -1;

  if (org.getMonthlyRunLimitOverride() != null) return org.getMonthlyRunLimitOverride();

  return getMonthlyLimit(effectiveTier);
}
```

When an ENTERPRISE org sets `org.api_keys_provided = TRUE` (via the
ORG_ADMIN settings page that backs `PUT /api/org/me/settings/ai-keys`), the
return value is `-1` (unlimited). Without BYOK keys, the org falls through
to `tier_config.monthly_run_limit` for ENTERPRISE, currently seeded at
`5000` (`V6__tier_config_and_org_limits.sql:13-15`).

Solo ENTERPRISE users (single-member personal org per V162) follow the same
path because `TierPermissionService.getPermissions` routes them through
`effectiveUserLimit` for the cap check, which still resolves to the
ENTERPRISE tier_config row.

---

## 2. BYOK with full cost-cap exemption

Two interlocking pieces:

### Per-org key resolution

`Org.keySourceMode` (lines 96-100 of `auth/entity/Org.java`):

- `SYSTEM` — always use platform-provided keys.
- `OWN_KEYS` — only use user/org keys; no platform fallback.
- `HYBRID` (default) — user/org keys first, fall back to platform.

`Org.enabledProviders` lets the ORG_ADMIN narrow the allowed set
(`"anthropic,openai,gemini"` — null = all allowed). Both are set via
`PUT /api/org/me/settings/key-source` (OrgController line 203-213).

### Cost-cap exemption

The platform-wide cost cap defaults to `$8` per run (per
`docs/CLAUDE.md` references; verified by `RunOrchestrator` not by re-read
here). When `org.keySourceMode == OWN_KEYS`, the cap is bypassed — the run
is paid for entirely by the customer's own LLM provider account, so
ExecuteSpec has no cost exposure.

The customer can still set a voluntary `userCostCap` on the run request
(see API contract for `POST /api/runs` in the project CLAUDE.md). This is
clamped against the platform cap on non-BYOK orgs; BYOK skips clamping.

---

## 3. Single Sign-On (TEAM and ENTERPRISE)

**What works today**: `GOOGLE_OIDC` enforced SSO. An ORG_ADMIN sets the
provider + email domain via `PUT /api/auth/sso/config` and flips
`enforced = true`. Future logins for the org's email domain land on Google's
authorisation code flow and are auto-bounced back into ExecuteSpec.

**What's saved-but-not-enforced**: `OKTA_SAML` and `AZURE_AD_SAML`. The
schema columns exist (`V127__wave3_sso_githubapp_trial.sql`) and the
configuration UI accepts the metadata URL + domain, but enforcement is
rejected with 409 Conflict — the SAML2 ACS / SP-metadata wiring has not
shipped. See `docs/sso-saml-wiring.md` for the full picture and the
operator runbook.

The save itself is gated to `SUPER_ADMIN` or `ORG_ADMIN`
(`@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")` at every method on
`auth/sso/SsoConfigController.java`). The tier gate is currently a
front-end cosmetic gate, not a server-side hard gate.

---

## 4. Audit log (TEAM and ENTERPRISE)

Every meaningful state change (settings updates, role changes, payment
events, run lifecycle, invitations, SSO toggles, sharing toggles) is
recorded as an `ActivityAudit` row by `ActivityAuditService.record(...)`.

Org-scoped query endpoint:

```
GET /api/org/me/audit-logs
  ?entityType=&eventType=&actorId=&entityId=&from=&to=&page=&size=
```

Annotated `@PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")`
(`auth/controller/OrgController.java:179`). Returns a paginated
`AuditLogPage` DTO. Backed by `ActivityAuditService.query(...)`.

Auth-side event types in the audit log include `USER_REGISTERED`,
`EMAIL_VERIFIED`, `PASSWORD_RESET`, `PASSWORD_CHANGED`,
`USER_GITHUB_SETTINGS_CHANGED`, `USER_AI_KEYS_CHANGED`,
`USER_JIRA_SETTINGS_CHANGED`, `USER_CONFLUENCE_SETTINGS_CHANGED`,
`USER_BITBUCKET_SETTINGS_CHANGED`, `ORG_EXTERNAL_SHARING_CHANGED` (V198).
The audit table has a hash-chain integrity field (`hash_chain_value`); the
chain is verified by an internal job and is **not** exposed in DSAR exports.

`TierPermissionService.orgMemberPermissions` (ORG_ADMIN branch, lines
207-236) sets `canViewAudit = true`; the FREE / PRO front-end does not
surface the menu item. ENTERPRISE customers see it by default.

---

## 5. External cross-org collaboration (V175 + V198)

ENTERPRISE orgs (and any PRO+ tier where the org admin has enabled it) can
invite collaborators from outside the org as `is_external = TRUE`
`ProjectMember` rows on a single project. The flow is:

1. ORG_ADMIN flips `org.external_sharing_enabled = TRUE` via
   `PUT /api/org/me/settings/external-sharing` (`OrgController:228-237`).
2. ORG_ADMIN calls `POST /api/projects/{id}/invites` with `{email,
   projectRole}`. Role can be `PROJECT_VIEWER` (read-only), `PROJECT_DEVELOPER`,
   or `PROJECT_ADMIN`.
3. Invitee receives an email with a magic link, accepts via
   `POST /api/invites/accept?token=…`, gets a `project_member` row added.
4. From that moment, `ProjectAccessGuard.assertCanRead` admits them on any
   project sub-resource via the cross-org branch; writes are blocked for
   `PROJECT_VIEWER` only.

Three gates are enforced by `ProjectInviteService.assertCanInvite`
(`sharing/service/ProjectInviteService.java:99-147`):

- Tier: caller must be `PRO`, `TEAM`, `MAX`, or `ENTERPRISE`.
- Role (V198): caller must be SUPER_ADMIN, or ORG_ADMIN of their own org.
- Org flag (V198): the caller's `org.external_sharing_enabled` must be
  `true`. SUPER_ADMIN bypasses the last two.

A separate **public share link** surface (`project_share_link` table) lets
an admin generate signed, read-only URLs for specific surfaces
(`PROJECT_OVERVIEW`, `RUN_DETAIL`, `CODE_REVIEW`, `PR_REVIEW`,
`VALIDATION_REPORT`, `ARCHITECTURE_DOC`). Tier-gated by
`tier_config.share_link_active_cap` (FREE=3, PRO=25, ENTERPRISE
unlimited).

---

## 6. Standards compliance + architecture invariants

`src/main/java/com/navneet/executespec/standards/` houses the per-org coding
standards engine. ORG_ADMINs can:

- Add per-org standards (`POST /api/admin/standards/...`, gated to
  `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")` at every method
  on `standards/OrgStandardController.java`).
- Override or grant standing exceptions on violations via
  `POST /api/projects/{id}/standards/violations/{id}/override` and
  `POST /api/projects/{id}/standards/{id}/exception` (≥30-char rationale
  required).
- Query the standards compliance report at
  `GET /api/projects/{id}/standards/report?orgId=&sinceDays=`.

`arch/invariants/` holds architecture invariants — declarative rules that
the planner / reviewer enforce on generated code (no field injection, prompt
files only via `PromptAssembler`, etc.). These are the same rules the
platform itself follows; they ship as defaults for enterprise customers and
can be customised per org.

---

## 7. Other ENTERPRISE-grade features (one-liners with code anchors)

| Feature                          | Anchor                                                                                                                       |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Org-wide JIRA + Confluence creds  | `auth/entity/Org.java:160-187`; managed via `PUT /api/org/me/settings/{jira,confluence}` (ORG_ADMIN only)                     |
| Per-org domain enforcement       | `org.domain_enforcement_mode` (`V21__tier_permissions.sql:11`); ENTERPRISE is always `ENFORCED` regardless of column          |
| Block common consumer domains    | `org.block_common_domains` — ENTERPRISE always `TRUE` (`V21__tier_permissions.sql:24`)                                        |
| Per-org GitHub owner allowlist   | `org.allowed_github_owners` (V148, M-039) — `ProjectRepoService` rejects links outside the list                              |
| Admin-approval threshold         | `org.require_admin_approval_above_usd` (V148, M-032) — plan-gate approval requires ORG_ADMIN above the USD threshold         |
| Suspend / un-suspend members     | `PUT /api/org/me/members/{userId}/suspend` (`OrgController:93-103`)                                                          |
| GitHub App installation          | `org.github_installation_id` (`V127__wave3_sso_githubapp_trial.sql:26-31`); preferred over per-user PATs when set            |
| Seat management (TEAM tier)      | `POST /api/org/me/seats/adjust` — proration via Stripe/Razorpay (`OrgController:389-398`)                                    |
| Multi-repo runs                  | `auth/entity/Project.java` + `project_repo` join — per-repo workspace + per-repo PR cross-link                                |
| Data retention                   | `tier_config.retention_days` — ENTERPRISE=365 days (`auth/entity/TierConfig.java:85-87`)                                     |
| GDPR data export                 | `GET /api/auth/me/data-export` — ZIP bundle. See `docs/dpa-flow.md`                                                          |
| DSAR (legal-team workflow)       | `GET /api/dsar/me` + `GET /api/dsar/users/{id}` (SUPER_ADMIN only)                                                            |
| Per-org cleanup / erasure        | `DELETE /api/auth/me/data` — soft-deletes the caller's runs; full account closure is policy / contractual via admin@         |

---

## 8. Onboarding workflow

The customer-facing onboarding pattern (verified by reading code, not
inferring from marketing):

1. **Tier provisioning** — SUPER_ADMIN sets `org.tier = "ENTERPRISE"` (and
   typically `api_keys_provided = TRUE` once the customer has shared their
   LLM keys). Billing path: either `BILLING_DEFAULT_GATEWAY=stripe` with the
   `STRIPE_ENTERPRISE_PRICE_ID`, or a manual override row written via
   `PaymentAuditService` (event `MANUAL_OVERRIDE`).
2. **ORG_ADMIN onboarding** — One ORG_ADMIN account from the customer's
   domain. Either signs up via Google OAuth (and is then promoted) or via
   email + password followed by manual `app_user.global_role` update.
3. **Domain enforcement** — `org.domain_patterns = "acme.com"` so future
   members from that domain auto-attach. `block_common_domains` is always
   true on ENTERPRISE per `V21__tier_permissions.sql`.
4. **SSO setup** — Today this means GOOGLE_OIDC. SAML is on the roadmap (see
   `docs/sso-saml-wiring.md`). The M-016 lockout guard requires the
   onboarding admin to themselves have logged in via Google before
   enforcement can be flipped on.
5. **External sharing decision** — V198 flag stays off unless the customer
   asks for cross-org collaboration. Toggle via the Org Settings page.
6. **GitHub App install (preferred)** — installs at the org level and writes
   `org.github_installation_id`. Avoids requiring every member to upload a
   PAT.
7. **First project + repos** — created by the ORG_ADMIN via
   `POST /api/projects` and `POST /api/projects/{id}/repos`. Tier caps
   (`tier_config.max_projects_per_org`, `max_repos_per_project`) default to
   `-1` (unlimited) for ENTERPRISE.

---

## 9. SLA + support

For uptime targets, support response times, maintenance windows, and
incident reporting, see `docs/enterprise-sla.md`. This document does not
restate those terms.

---

## 10. References

- `auth/service/TierEnforcementService.java` — limit + BYOK exemption logic.
- `auth/entity/Org.java` — every org-level capability column.
- `auth/entity/TierConfig.java` — per-tier feature toggles + caps.
- `auth/service/TierPermissionService.java` — capability matrix derived
  from tier + role.
- `auth/controller/OrgController.java` — all ORG_ADMIN-gated settings.
- `auth/sso/SsoConfigController.java` — SSO config (see also
  `docs/sso-saml-wiring.md`).
- `sharing/service/ProjectInviteService.java` — V175 + V198 cross-org
  invite gates.
- `V6__tier_config_and_org_limits.sql`, `V21__tier_permissions.sql`,
  `V127__wave3_sso_githubapp_trial.sql`, `V148__governance_caps.sql`,
  `V175__external_project_sharing.sql`, `V198__org_external_sharing_flag.sql`
  — schema deltas that underlie each capability.
