# SSO / SAML wiring

This document describes what the code actually does today for enterprise SSO. The short version: **per-org SSO configuration can be stored, but only `GOOGLE_OIDC` works end-to-end**. SAML metadata can be saved but SAML enforcement is rejected at the API layer because no SAML handshake is wired.

This is engineering-honest documentation — what ships, what doesn't, and what would need to land to close the gap.

---

## 1. Surface area

### 1.1 Controller

[`auth/sso/SsoConfigController.java`](../src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java) is the entire SSO surface (195 lines).

```
@RestController
@RequestMapping("/api/auth/sso")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
public class SsoConfigController { ... }
```

Three endpoints:

| Method | Path                  | Body                          | Returns          | Role                    |
| ------ | --------------------- | ----------------------------- | ---------------- | ----------------------- |
| GET    | `/api/auth/sso/config` | —                            | `SsoConfigDto`   | SUPER_ADMIN or ORG_ADMIN |
| PUT    | `/api/auth/sso/config` | `SsoConfigUpdate`             | `SsoConfigDto`   | SUPER_ADMIN or ORG_ADMIN |
| DELETE | `/api/auth/sso/config` | —                            | 204              | SUPER_ADMIN or ORG_ADMIN |

`SsoConfigUpdate` carries four fields with bean-validation:

```
record SsoConfigUpdate(
    @Pattern(regexp = "GOOGLE_OIDC|OKTA_SAML|AZURE_AD_SAML") String provider,
    Boolean enforced,
    @URL @Pattern(regexp = "^$|^https://.+") @Size(max = 1024) String metadataUrl,
    @Size(max = 255) String domain
)
```

Empty-string `metadataUrl` is intentional so partial updates can pass through.

`SsoConfigDto` (returned by GET / PUT) is the same five fields plus a derived `boolean configured` (true when `provider != null`):

```
record SsoConfigDto(
    String provider, boolean enforced, String metadataUrl, String domain, boolean configured
) {
    static SsoConfigDto from(Org o) { ... }
}
```

### 1.2 `ALLOWED_PROVIDERS`

A private `Set<String>` in `SsoConfigController`:

```
private static final Set<String> ALLOWED_PROVIDERS =
    Set.of("GOOGLE_OIDC", "OKTA_SAML", "AZURE_AD_SAML");
```

Any provider string outside this set returns 400. The set is the union of what the schema can persist; **enforcement** (see §3) restricts further.

---

## 2. Persistence — per-org `sso_*` columns

[`auth/entity/Org.java`](../src/main/java/com/navneet/executespec/auth/entity/Org.java) declares four columns:

| Column            | Type           | Purpose                                                            |
| ----------------- | -------------- | ------------------------------------------------------------------ |
| `sso_provider`    | VARCHAR(32)    | `GOOGLE_OIDC` / `OKTA_SAML` / `AZURE_AD_SAML`                      |
| `sso_enforced`    | BOOLEAN        | When true, members of this org must SSO; password login blocked.    |
| `sso_metadata_url`| VARCHAR(1024)  | XML metadata URL (SAML) or discovery URL (OIDC).                    |
| `sso_domain`      | (string)       | Email-domain hint for the identity provider.                        |

Columns were introduced by an earlier migration (the bucket research notes do not show a `V127` SSO-specific migration; column-add is part of the historical baseline). Subsequent organisation-related migrations leave them untouched.

The columns hold configuration only. **No row in `org` ever causes a SAML/OIDC handshake by itself** — the auth handshake is gated by Spring Security's OAuth2 client registration (see §4), not by these columns.

---

## 3. The two enforcement guards inside `PUT /api/auth/sso/config`

The PUT handler runs two distinct guards in sequence, both before persisting.

### 3.1 M-016 lockout-prevention guard

Before allowing `enforced=true` for an admin's own org, the handler refuses if the calling admin has not themselves logged in via the SSO provider currently being set. Rationale: if an org admin flips `enforced=true` while still logged in via password, they would lock themselves and every other admin out the moment their access token expires. The guard returns 409 Conflict with a message directing the admin to log out, log back in via SSO, and retry.

### 3.2 [J-14] SAML-enforcement reject

If the request sets `provider IN ('OKTA_SAML','AZURE_AD_SAML')` AND `enforced=true`, the handler returns **409 Conflict**:

> SAML enforcement is not yet supported. Save the configuration without `enforced=true`, or use `GOOGLE_OIDC` instead.

Rationale: there is no Spring code that completes a SAML handshake. Permitting `enforced=true` for SAML providers would lock out every member of the org with no recovery path. This is a deliberate code-level refusal.

Both guards are enforced server-side regardless of UI; curl-bypassing the UI still hits the rejection.

---

## 4. Authentication providers actually wired

Two completely separate auth surfaces exist. Neither uses the `org.sso_*` columns.

### 4.1 Password (email + password)

The default surface: `POST /api/auth/login` → [`auth/service/AuthService.java`](../src/main/java/com/navneet/executespec/auth/service/AuthService.java) → BCrypt strength-12 compare → JWT issue.

### 4.2 OAuth2 social login (Google, GitHub)

Configured in [`auth/security/SecurityConfig.java`](../src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java) only when client-id + client-secret env vars are set for that provider (otherwise `oauthEnabled=false` and the OAuth2 filter chain is skipped entirely). Provider config lives in Spring application YAML:

```
spring.security.oauth2.client.registration.google:
  client-id:     ${GOOGLE_OAUTH_CLIENT_ID:}
  client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET:}
  scope:         openid,email,profile
  redirect-uri:  https://executespec.ai/api/auth/oauth2/callback/{registrationId}

spring.security.oauth2.client.registration.github:
  client-id:     ${GITHUB_OAUTH_CLIENT_ID:}
  client-secret: ${GITHUB_OAUTH_CLIENT_SECRET:}
  scope:         user:email,read:user
```

The success handler [`auth/oauth/OAuthSuccessHandler.java`](../src/main/java/com/navneet/executespec/auth/oauth/OAuthSuccessHandler.java) maps the provider profile via [`auth/oauth/OAuthUserMapper.java`](../src/main/java/com/navneet/executespec/auth/oauth/OAuthUserMapper.java) (Google direct, GitHub falls back to `GET /user/emails` when the user has email private), then `AuthService.findOrCreateFromOAuth(provider, oauthId, email, name, avatarUrl)` does email-merge / auto-link, mints JWTs, and redirects to the SPA at `dashboardUrl + /auth/callback?token=...&refresh=...`.

**Important:** This is plain OAuth2 social login, not enterprise SSO. It uses the *application's* shared client credentials, not per-org SSO config. The presence of `GOOGLE_OIDC` in `ALLOWED_PROVIDERS` is *aspirational*: in production today, choosing `GOOGLE_OIDC` and setting `enforced=true` will:

1. Store the choice in `org.sso_provider` and `org.sso_enforced`.
2. Pass the M-016 lockout guard if the admin has previously logged in via the application's Google OAuth2 (because GOOGLE_OIDC and the OAuth2-Google flow share an identity provider on the user side — the same Google account works).
3. Allow other org members to keep using Google OAuth2 (since they presumably authenticate to the same Google domain).

That's the path that "works end-to-end" — but the enforcement that should block password login when `enforced=true` is **not visible in the security filter chain** as it stands. The `sso_enforced` column is read by no Spring Security code path inspected. Treat `enforced=true` for `GOOGLE_OIDC` as policy declaration rather than enforced redirect.

OKTA_SAML and AZURE_AD_SAML cannot reach an `enforced=true` state because of [J-14] (§3.2).

---

## 5. No SAML dependency in the build

Confirmed from `pom.xml` ([`bucket-root-build-research.md`](.)): the project depends on:

- `org.springframework.boot:spring-boot-starter-security`
- `org.springframework.boot:spring-boot-starter-oauth2-client`

It does **not** depend on `org.springframework.security:spring-security-saml2-service-provider`, nor on any standalone SAML library (no `spring-security-saml2`, no `OpenSAML`, no `Pac4j-SAML`). There is no servlet filter for `/api/auth/saml/login` or `/api/auth/saml/sso`. The SAML provider strings (`OKTA_SAML`, `AZURE_AD_SAML`) exist in `ALLOWED_PROVIDERS` because the schema is forward-looking — the work to wire the handshake hasn't landed.

---

## 6. Tier gate

SSO configuration is gated by tier at the UX layer; the controller does not check tier directly. Per `tier_config`, only TEAM and ENTERPRISE expose the SSO settings panel:

- FREE: no panel.
- PRO: no panel (PRO is the personal "Solo" tier — single-user; SSO doesn't apply).
- TEAM: panel visible, `GOOGLE_OIDC` works.
- ENTERPRISE: panel visible, full ALLOWED_PROVIDERS set; SAML enforcement still rejected per [J-14].

When SAML support lands, the tier gate becomes the natural place to require ENTERPRISE for SAML providers specifically.

---

## 7. Known gaps

These are limitations to document, not bugs to file:

1. **No `spring-security-saml2` dependency.** Adding it would require, at minimum:
   - Maven dependency `spring-security-saml2-service-provider`.
   - A `RelyingPartyRegistrationRepository` bean keyed by org (since each customer brings their own IdP metadata).
   - A filter that resolves the org from the request host or a tenant cookie before the SAML AuthnRequest is generated.
   - A `Saml2AuthenticatedPrincipal` → `AppUser` find-or-create bridge analogous to `OAuthSuccessHandler`.

2. **`sso_enforced=true` is not load-bearing today.** No Spring filter rejects password login based on `org.sso_enforced=true`. Adding that requires a new pre-`UsernamePasswordAuthenticationFilter` filter that consults the user's org SSO config and 403s if password login is attempted against an SSO-enforced org.

3. **No just-in-time provisioning.** SAML JIT (auto-create the AppUser from the SAML assertion's email + groups attributes) is not implemented because the upstream handshake is missing.

4. **No SCIM endpoint.** Customer-side automated user provisioning (Okta SCIM, Azure AD SCIM) is also not implemented.

5. **No "force-logout on SSO config change" path.** Today an admin flipping `sso_provider` does not invalidate sessions for org members; they would just keep working with their existing JWTs. The platform-wide force-logout epoch (read by `JwtUtil.isTokenValid`) is the closest existing mechanism; it would need to be made per-org to fit this case.

---

## 8. Operational recipe for TEAM customers today

Until SAML lands, the supported "SSO" story for TEAM customers is:

1. Customer ensures every team member has a Google account on the same domain.
2. Customer's ORG_ADMIN logs in to executespec.ai via the **Sign in with Google** button at `POST /api/auth/oauth2/init?surface=...`.
3. Customer invites team members via `POST /api/org/invite` (their `OrgInvitation` accept link triggers a Google-OAuth login).
4. ORG_ADMIN optionally `PUT /api/auth/sso/config` with `{provider:"GOOGLE_OIDC", enforced:false, domain:"customer.com"}` to record the policy intent.
5. ORG_ADMIN ensures `org.domain_enforcement_mode='ENFORCED'` (separate column) so off-domain invitees escalate to SUPER_ADMIN approval.

This works today on the `executespec` surface, with the caveat that nothing in the auth filter chain actively blocks a member from using `POST /api/auth/login` with a password if they have one. The "enforcement" is a policy statement.

---

## 9. Audit-evidence

Every change to SSO config emits an `activity_audit` row through [`auth/service/ActivityAuditService.java`](../src/main/java/com/navneet/executespec/auth/service/ActivityAuditService.java) (hash-chained, tamper-evident). Events include `SSO_CONFIG_UPDATED` and `SSO_CONFIG_CLEARED`. The diff is captured in `old_value` / `new_value` columns (the old + new `SsoConfigDto` serialised to JSON).

ORG_ADMIN can review their own org's events via `GET /api/org/me/audit-logs?eventType=SSO_CONFIG_UPDATED`; SUPER_ADMIN sees all orgs via `GET /api/admin/audit-logs`.

---

## 10. Files referenced

- [`auth/sso/SsoConfigController.java`](../src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java)
- [`auth/entity/Org.java`](../src/main/java/com/navneet/executespec/auth/entity/Org.java) (columns `sso_provider`, `sso_enforced`, `sso_metadata_url`, `sso_domain`)
- [`auth/security/SecurityConfig.java`](../src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java) (OAuth2 client filter chain, `oauthEnabled` flag)
- [`auth/oauth/OAuthSuccessHandler.java`](../src/main/java/com/navneet/executespec/auth/oauth/OAuthSuccessHandler.java)
- [`auth/oauth/OAuthUserMapper.java`](../src/main/java/com/navneet/executespec/auth/oauth/OAuthUserMapper.java)
- [`auth/service/AuthService.java`](../src/main/java/com/navneet/executespec/auth/service/AuthService.java) (`findOrCreateFromOAuth`)
- [`auth/service/ActivityAuditService.java`](../src/main/java/com/navneet/executespec/auth/service/ActivityAuditService.java)
- `pom.xml` (no `spring-security-saml2` dependency)
- Related: [`docs/RBAC.md`](RBAC.md) for the full tier + role matrix.
