# SSO / SAML wiring — implementation status

> Last refreshed: 2026-05-16. Source of truth:
> `src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java`,
> `src/main/java/com/navneet/executespec/auth/entity/Org.java`,
> `src/main/resources/db/migration/V127__wave3_sso_githubapp_trial.sql`.

This document is intentionally honest about what is and isn't wired today.
Treat any claim not citing a source file as **policy / contractual, not
code-enforced** and verify before signing a customer commitment.

---

## 1. Current state in one paragraph

Per-org SSO **configuration** is fully wired: an org admin can save the
provider + metadata URL + email domain via `GET/PUT/DELETE /api/auth/sso/config`,
and the org row carries four columns (`sso_provider`, `sso_enforced`,
`sso_metadata_url`, `sso_domain`) added by `V127__wave3_sso_githubapp_trial.sql`.
The **enforcement path** (i.e. actually rejecting password login and bouncing
the user to an IdP) is currently honoured only for `GOOGLE_OIDC`, which
piggybacks on the existing Spring Security OAuth2 flow. `OKTA_SAML` and
`AZURE_AD_SAML` configurations can be saved, but `sso_enforced = TRUE` is
rejected with `409 Conflict` at save time — the SAML2 ACS / SP-metadata
endpoints and assertion validation pipeline have not shipped, and there is
no `spring-security-saml2` dependency in the project. SSO itself is a
**TEAM / ENTERPRISE** tier-gated capability per the persona roadmap.

---

## 2. Allowed providers

`SsoConfigController.ALLOWED_PROVIDERS`
(`auth/sso/SsoConfigController.java:49-50`) is the closed set:

```java
private static final Set<String> ALLOWED_PROVIDERS =
    Set.of("GOOGLE_OIDC", "OKTA_SAML", "AZURE_AD_SAML");
```

The DTO validator (record `SsoConfigUpdate`, lines 167-182) further enforces
this via a Bean Validation `@Pattern` annotation:

```java
@Pattern(
    regexp = "GOOGLE_OIDC|OKTA_SAML|AZURE_AD_SAML",
    message = "provider must be one of GOOGLE_OIDC, OKTA_SAML, AZURE_AD_SAML")
String provider
```

Any other value yields `400 Bad Request` ("Unknown SSO provider — supported:
…").

---

## 3. Per-org schema (V127)

`V127__wave3_sso_githubapp_trial.sql` lines 13-20 add four columns to the
`org` table:

| Column              | Type            | Default | Purpose                                                                |
| ------------------- | --------------- | ------- | ---------------------------------------------------------------------- |
| `sso_provider`      | `VARCHAR(32)`   | NULL    | `null` / `GOOGLE_OIDC` / `OKTA_SAML` / `AZURE_AD_SAML`                 |
| `sso_enforced`      | `BOOLEAN NOT NULL` | `FALSE` | When true, password login is rejected — SSO required for members.   |
| `sso_metadata_url`  | `VARCHAR(1024)` | NULL    | IdP metadata endpoint (https-only at validation layer)                 |
| `sso_domain`        | `VARCHAR(255)`  | NULL    | Email-domain → SSO mapping ("acme.com" → use this org's SSO)           |

A partial index supports lookups by configured-providers:

```sql
CREATE INDEX idx_org_sso_provider ON org(sso_provider)
    WHERE sso_provider IS NOT NULL;
```

Entity mapping is in `auth/entity/Org.java:206-220`. All four columns are
nullable (apart from the boolean, which has a default), so applying the
migration is a no-op for existing tenants.

---

## 4. The CRUD surface

Class-level `@RequestMapping("/api/auth/sso")` +
`@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")` on every method.

### `GET /api/auth/sso/config`

Returns the caller's org's SSO config (`SsoConfigDto` — record defined at
lines 184-194). Shape:

```json
{
  "provider": "GOOGLE_OIDC | OKTA_SAML | AZURE_AD_SAML | null",
  "enforced": true | false,
  "metadataUrl": "https://… | null",
  "domain": "acme.com | null",
  "configured": true | false
}
```

`configured = (provider != null)`.

### `PUT /api/auth/sso/config`

Partial update — any field left null in the body is left unchanged on the
row. Body shape (`SsoConfigUpdate` — lines 167-182):

```json
{
  "provider": "GOOGLE_OIDC | OKTA_SAML | AZURE_AD_SAML | null",
  "enforced": true | false | null,
  "metadataUrl": "https://… | \"\" | null",
  "domain": "acme.com | \"\" | null"
}
```

Bean-validation rules (SDET 2026-05-16, BUG-005):

- `metadataUrl` must pass `@URL`, must match `^$|^https://.+`, max length 1024.
  Empty string is allowed because PUT is a partial update.
- `domain` must match `^$|^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$`, max length 255.
- `provider` must match the `@Pattern` enum.

The handler then runs **four gates** in order (lines 67-122):

1. **Unknown provider** → `400 Bad Request`.
2. **Enforcing without a provider** → `400` ("Cannot enforce SSO without
   configuring a provider first.").
3. **`[J-14]` SAML enforcement gate** → `409 Conflict` (see §6 below).
4. **M-016 lockout-prevention gate** → `409` (see §5 below).

On success, the row is saved and the new `SsoConfigDto` is returned. The
`SsoConfigController` logs the change at `INFO` level with `org`, `provider`,
`enforced`, `domain`, and `updatedBy` fields (line 131-138).

### `DELETE /api/auth/sso/config`

Clears all four columns back to defaults (`provider = NULL`,
`enforced = FALSE`, `metadataUrl = NULL`, `domain = NULL`). Returns 204.

---

## 5. M-016 — lockout prevention guard

`auth/sso/SsoConfigController.java:79-122`.

The most common foot-gun in SSO setup is an org admin who flips
`sso_enforced = TRUE` **before** they have themselves verified the IdP
handshake — every org member (including the admin) is now locked out of
password login but the IdP redirect doesn't work, requiring a database
intervention to recover.

The guard:

```java
boolean transitionsToEnforced = Boolean.TRUE.equals(body.enforced) && !org.isSsoEnforced();

// Lockout-prevention — only allow the enforcement transition if the calling
// admin has themselves logged in via SSO/SAML/GOOGLE on the current session.
if (transitionsToEnforced
    && !"SAML".equals(principal.getUser().getOauthProvider())
    && !"SSO".equals(principal.getUser().getOauthProvider())
    && !"GOOGLE".equals(principal.getUser().getOauthProvider())) {
  throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "Verify your own SSO login first. Sign out, sign back in via the "
          + "configured IdP, then re-attempt enforcement. This guard "
          + "prevents locking the entire org out of the platform.");
}
```

This only fires on the **transition** to enforced (not on idempotent saves
of `enforced=true`).

The check trusts `principal.getUser().getOauthProvider()` — the column
populated when the user last logged in via OAuth2 (Google or GitHub today;
`SsoConfigController` accepts `SAML` and `SSO` strings too in anticipation of
the SAML wiring shipping). For the **current** SSO state, this effectively
means the calling admin must have last signed in through Google OAuth before
enforcement is permitted.

---

## 6. `[J-14]` — SAML enforcement is rejected up-front

`auth/sso/SsoConfigController.java:88-111`. Verbatim from the controller:

```
[J-14] (audit Sprint 3, 2026-05-01) — refuse enforcement on
SAML providers until the actual handshake ships. Per
docs/sso-saml-wiring.md the OKTA_SAML / AZURE_AD_SAML handshake
is schema-only (V127 columns + this controller); the
assertion-validation + AssertionConsumerService wiring is ~3-5
days of unscoped work that hasn't landed. Today only
GOOGLE_OIDC is a functional SSO route (it piggybacks on the
existing OAuth2 flow). Allowing enforced=TRUE on SAML providers
creates a setting the platform cannot honor — the IdP redirect
never fires, users hit the regular login form, and either the
M-016 guard locks the calling admin out (best case) or the
setting silently no-ops (worst case). Reject up-front.
```

The runtime check:

```java
String effectiveProvider = body.provider != null ? body.provider : org.getSsoProvider();
if (transitionsToEnforced
    && effectiveProvider != null
    && !"GOOGLE_OIDC".equals(effectiveProvider)) {
  throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "Enforcement is currently only supported for GOOGLE_OIDC. The "
          + "SAML2 handshake for OKTA_SAML / AZURE_AD_SAML is on the "
          + "roadmap (see docs/sso-saml-wiring.md). Configure the "
          + "provider as GOOGLE_OIDC, or contact support to schedule "
          + "SAML enablement.");
}
```

Customers can **save** an OKTA_SAML / AZURE_AD_SAML configuration (provider +
metadata URL + domain), but `enforced = true` will be rejected with 409 until
the SAML2 acceptance flow ships.

---

## 7. What's actually wired for `GOOGLE_OIDC`

GOOGLE_OIDC works because it shares Spring Security's existing OAuth2
authorisation-code flow. The relevant moving parts:

- **Spring Security registration** — `application-dev.yml:62-77` and
  `application-prod.yml:58-73`. Both Google + GitHub are registered as
  `oauth2.client.registration.*` providers with scope `openid, email, profile`
  for Google and `user:email, read:user` for GitHub.
  Environment overrides: `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`,
  `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET`.
- **Redirect URIs** —
  `https://dev.executespec.ai/api/auth/oauth2/callback/{registrationId}`
  (dev) and
  `https://executespec.ai/api/auth/oauth2/callback/{registrationId}`
  (prod).
- **Filter chain hook** — `SecurityConfig.filterChain` lines 253-265 enables
  Spring's `oauth2Login` when at least one OAuth client is configured
  (`oauthEnabled` boolean computed in the constructor at lines 65-69). When
  no OAuth providers are configured, the entire `oauth2Login` block is
  skipped.
- **Success handler** — `auth/oauth/OAuthSuccessHandler.java`. Resolves the
  OAuth profile, calls `AuthService.findOrCreateFromOAuth`, generates the
  access + refresh JWTs, and redirects to
  `{dashboardUrl}/auth/callback?token=…&refresh=…`.
- **Identity linking** — `AuthService.findOrCreateFromOAuth`
  (`auth/service/AuthService.java:530-635`) follows three branches:
  1. Exact match on `(oauth_provider, oauth_id)` → return existing user,
     update `last_login_at`.
  2. Email match with no/different OAuth provider → **link** the OAuth
     identity to the existing user (sets `oauth_provider`, `oauth_id`,
     forces `email_verified = true`, status `ACTIVE`).
  3. No match → create new user (no password, `ACTIVE`, `email_verified =
     true`), auto-create FREE billing subscription, bootstrap personal org,
     bind any pending referral, send welcome email.

The session-management policy is `IF_REQUIRED` when OAuth is enabled
(SecurityConfig line 125-127) because Spring's OAuth2 authorization-code flow
needs an HttpSession to round-trip the state parameter.

**There is no code that consults `org.sso_provider` or `org.sso_enforced` at
login time.** That logic is a follow-up — today, when SSO is "enforced", the
guarantee is the M-016 admin-already-on-SSO requirement, not an active
rejection of password logins for the affected org. Password login continues
to work for any user whose `app_user` row has a `password_hash`.

---

## 8. What's NOT implemented (be explicit)

- **No SAML2 dependency**. There is no `spring-security-saml2-service-provider`
  artefact in `pom.xml` (verified by `grep -n "saml" pom.xml`). Implementing
  OKTA_SAML / AZURE_AD_SAML requires adding the Spring Security SAML2 starter,
  exposing a Service-Provider metadata endpoint
  (`/api/auth/saml2/service-provider-metadata/{registrationId}`), implementing
  an AssertionConsumerService, validating signed SAML assertions, mapping
  attributes to `AppUser` fields. The controller javadoc estimates this as
  "3-5 days of unscoped work".
- **No SP-initiated SAML redirect**. The frontend login page would need a
  "Sign in with SSO" button that redirects to the SAML SP-init URL; that
  button does not exist today.
- **No per-org login-time SSO enforcement**. `JwtAuthenticationFilter` /
  `AuthService.login` do not query `org.sso_enforced` and do not redirect
  affected users to an IdP. A user whose org has `sso_enforced = TRUE` can
  still sign in with email + password as long as their `app_user` row has a
  `password_hash`.
- **No domain → org auto-discovery on login**. The `sso_domain` column is
  written by the admin but not consulted by any login-side code. The intent
  is "user enters `alice@acme.com` on the login page, system looks up the org
  by domain match, redirects to that org's configured IdP" — that lookup path
  has not been built.
- **No JIT user provisioning from SAML assertions**. The third branch in
  `findOrCreateFromOAuth` (create new user from external identity) exists for
  OAuth but there is no equivalent for SAML.
- **No SCIM** — directory sync (user / group provisioning, deprovisioning) is
  not in scope. Enterprise customers who want it should be told this is
  contractual / future work.

---

## 9. Tier gating

SSO is tier-gated to **TEAM** and **ENTERPRISE** per the persona roadmap
(`docs/plans/persona-roadmap-2026-04-22.md` §5.3 — referenced in
`SsoConfigController` javadoc at line 38-41).

The gate is **not** enforced at the `@PreAuthorize` level today — both GET
and PUT only check `hasAnyRole('SUPER_ADMIN','ORG_ADMIN')`. The controller
javadoc explicitly says:

```
Tier-gating: SSO is a TEAM/ENTERPRISE feature (per persona-roadmap §5.3).
Enforcement happens at the @PreAuthorize level — only ORG_ADMIN can edit;
tier check is a separate concern at the front-end gate (PlansPage marketing
copy + a `locked` flag in the response if the org is on FREE/PRO).
```

The `locked` flag is not present in the current `SsoConfigDto` — the
front-end tier gate is implemented in `frontend/src/v3/pages/OrgSettings…`
(via `TierPermissionService` capability flags) rather than at the API
response level. A FREE / PRO org admin can technically still PUT a config;
the cosmetic gate is the only block.

`tier_config` does not have a dedicated `sso_enabled` column today. If we
need a hard server-side gate, the recommended path is:

1. Add a `tier_config.sso_enabled BOOLEAN` column in the next migration,
   default `FALSE`, set `TRUE` for TEAM / ENTERPRISE.
2. Have `SsoConfigController.putConfig` look up the caller's org tier and
   reject with `402 Payment Required` if `sso_enabled = FALSE`.

---

## 10. Operator runbook for GOOGLE_OIDC enforcement

To turn on enforced GOOGLE_OIDC for an existing org:

1. As an ORG_ADMIN of the target org, sign out and sign back in via the
   Google "Continue with Google" button. This sets your `app_user.oauth_provider
   = GOOGLE` so the M-016 guard will pass.
2. `PUT /api/auth/sso/config` with body
   `{ "provider": "GOOGLE_OIDC", "domain": "acme.com" }` to set the provider
   without enforcing.
3. Verify with `GET /api/auth/sso/config` that `configured = true`.
4. Test from a second test account in the same org — sign in via Google and
   confirm the user lands in the org.
5. `PUT /api/auth/sso/config` with body `{ "enforced": true }` to flip the
   flag. The M-016 and `[J-14]` guards will both pass (you're on Google,
   provider is GOOGLE_OIDC).

To roll back: `DELETE /api/auth/sso/config` clears all four columns; users
can sign in with password again (assuming `app_user.password_hash` is still
populated).

---

## 11. References

- `auth/sso/SsoConfigController.java` — controller + DTO + validation.
- `auth/entity/Org.java:206-220` — entity-level SSO columns.
- `auth/oauth/OAuthSuccessHandler.java` — OAuth2 login success → JWT.
- `auth/service/AuthService.java:530-635` — `findOrCreateFromOAuth`.
- `auth/security/SecurityConfig.java:253-265` — Spring oauth2Login wiring.
- `src/main/resources/application-dev.yml:62-77` — Google + GitHub
  registration on dev.
- `src/main/resources/application-prod.yml:58-73` — same on prod.
- `src/main/resources/db/migration/V127__wave3_sso_githubapp_trial.sql` —
  schema delta.
