# SSO / SAML wiring (M-016, Wave 3)

> Live ENT-procurement unblocker; the V127 schema and config UI
> (M-004) shipped already. This doc captures the actual wire-in
> needed to make SSO enforce — what library, what handshake, how
> per-IdP testing works, and the lockout-prevention guard now in
> code (`SsoConfigController.putConfig`).

## Where we are today

- **V127 schema** on `org`: `sso_provider`, `sso_enforced`,
  `sso_metadata_url`, `sso_domain`, `github_installation_id`,
  `github_installed_at`. Live on `dev` since v2.6.1 Wave 3.
- **`SsoConfigController`** (M-004): CRUD on the four SSO columns.
  Validates `provider ∈ {GOOGLE_OIDC, OKTA_SAML, AZURE_AD_SAML}`.
  M-016 now adds a **lockout-prevention guard** — enforcing SSO
  while the calling admin's own session is non-SSO returns 409 with
  an explainer.
- **Frontend `SsoConfigCard`** (M-004): provider dropdown +
  metadata URL + email-domain + Enforce SSO checkbox + Save / Clear.
- **`/api/auth/oauth2/init`** + `OAuthSurfaceResolver` already
  handle Google / GitHub OAuth. Same redirect bridge can carry the
  SAML response back to the right surface.

## What's missing (and why it's not in this branch)

The actual SAML2 handshake requires:

1. A library with metadata-URL parsing + assertion validation +
   per-IdP testing. **Spring Security SAML2** (per parked decision
   #6) is the right pick — maintained, MFA-friendly, Spring Security
   integration, ~3 dependency adds.
2. A test rig per IdP: at minimum Okta + Azure AD. Each requires a
   sandbox tenant, an SP metadata XML upload, and a test user.
3. SP metadata exposed at `/saml2/service-provider-metadata/{regId}`
   (Spring Security SAML2 default).
4. AssertionConsumerService (`/login/saml2/sso/{regId}`) wired into
   `SecurityConfig` alongside the existing OAuth2 chains.
5. A new `SamlPrincipalResolver` that maps SAML name-id → existing
   `AppUser`, calling `findOrCreateFromOAuth` with the assertion
   attributes (email, given_name, family_name).
6. JWT issuance after SAML assertion (re-use `JwtUtil.issueAccessToken`).

This is ~3-5 days of engineering + tenant setup + a security review.
Out of scope for the auto-execute branch; tracked here so the next
engineer can pick it up cold.

## The lockout-prevention guard (in this branch)

`SsoConfigController.putConfig` now blocks the
**OFF → ENFORCED** transition unless the calling admin's own
session was minted via SSO. The check:

```java
boolean transitionsToEnforced =
        Boolean.TRUE.equals(body.enforced) && !org.isSsoEnforced();
if (transitionsToEnforced && !"SAML".equals(principal.getUser().getOauthProvider())
        && !"SSO".equals(principal.getUser().getOauthProvider())) {
    throw new ResponseStatusException(HttpStatus.CONFLICT,
        "Verify your own SSO login first. Sign out, sign back in via the "
            + "configured IdP, then re-attempt enforcement. This guard "
            + "prevents locking the entire org out of the platform.");
}
```

Returns 409 (not 400) so the UI can surface a "verify your SSO
login first" hint. The frontend's `SsoConfigCard` shows the error
text verbatim from the response body.

This is the only foot-gun from M-004's design doc that we can fix
without the full SAML wire — and it's the highest-impact one
because it's the difference between "we ship SSO, the first admin
locks themselves out" and "we ship SSO, the first admin tries
once, sees the guard, does it right."

## Lifecycle when the wire-in lands

1. **OFF (sso_enforced = false):** every login path open
   (password / Google OAuth / GitHub OAuth / SAML if configured).
2. **CONFIGURED (sso_provider != null, sso_enforced = false):**
   members can log in via SAML if they choose, but password +
   OAuth still work. **Admin uses this state to verify the IdP
   before flipping enforcement.**
3. **ENFORCED (sso_enforced = true):** middleware in
   `SecurityConfig` rejects password / OAuth login attempts for
   any user whose `email` ends in `@<sso_domain>`. SUPER_ADMINs
   bypass (escape hatch for incident response).

## Per-IdP setup notes (will be expanded in the wire-in PR)

- **Okta:** SP metadata URL → Admin → Applications → Create App
  Integration → SAML 2.0 → upload our SP metadata XML.
- **Azure AD / Entra:** Enterprise applications → New application
  → Create your own application → SAML SSO → upload our SP
  metadata XML.
- **Google Workspace OIDC:** Already handled via OAuth2 (existing
  flow). Setting `sso_provider=GOOGLE_OIDC` + enforcing routes the
  domain through the existing Google OAuth flow with a hosted-domain
  hint (`hd=<sso_domain>`).

## Acceptance criteria for the wire-in PR (when it lands)

- [ ] SP metadata XML reachable at `/saml2/service-provider-metadata/executespec`.
- [ ] AssertionConsumerService responds at `/login/saml2/sso/executespec`.
- [ ] An Okta sandbox-tenant test user can complete SSO login →
      `JwtUtil.issueAccessToken` returns a valid JWT.
- [ ] An Azure AD sandbox-tenant test user can complete the same.
- [ ] `sso_enforced=true` blocks password login for matching domains.
- [ ] SUPER_ADMIN bypass still works.
- [ ] Lockout-prevention guard above is unchanged (already in tree).

## Where this wires (when shipped)

| Component | Role |
|---|---|
| `pom.xml` | Add `spring-security-saml2-service-provider`. |
| `SecurityConfig` | New `.saml2Login(...)` block; AssertionConsumer URL pinned to `/login/saml2/sso/executespec`. |
| `SamlPrincipalResolver` (NEW) | Map SAML assertion → AppUser via `AuthService.findOrCreateFromOAuth`. |
| `OAuthSuccessHandler` | Branch on `principal.getOauthProvider() == 'SAML'` to reuse the surface-aware redirect. |
| `AppUser.oauthProvider` | New value: `SAML`. (Already accepted by the column — VARCHAR.) |
| `application.yml` | New `spring.security.saml2.relyingparty.registration.executespec.*` block; metadata URL templated from `org.sso_metadata_url` per-request via a custom relying-party-resolver. |

## Out of scope (tracked separately)

- SCIM provisioning (M-037 backlog) — separate from auth.
- Multi-org tenant resolution at the metadata layer — currently we
  use per-org `sso_metadata_url`, so there's no multi-tenant
  metadata table.
- Just-in-time provisioning for unknown emails — current
  `findOrCreateFromOAuth` already handles this; no new work.
