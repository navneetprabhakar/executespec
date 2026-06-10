# SSO And SAML Wiring

> Recreated 2026-06-10 from `SsoConfigController`. Current truth: SSO configuration CRUD exists; SAML login does not.

## Current API

Controller:

```text
src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java
```

Endpoints:

| Method | Path | Role |
| --- | --- | --- |
| `GET` | `/api/auth/sso/config` | `SUPER_ADMIN` or `ORG_ADMIN` |
| `PUT` | `/api/auth/sso/config` | `SUPER_ADMIN` or `ORG_ADMIN` |
| `DELETE` | `/api/auth/sso/config` | `SUPER_ADMIN` or `ORG_ADMIN` |

Supported stored provider values:

- `GOOGLE_OIDC`
- `OKTA_SAML`
- `AZURE_AD_SAML`

Stored org fields:

- `sso_provider`
- `sso_enforced`
- `sso_metadata_url`
- `sso_domain`

## What Works

- ORG_ADMIN/SUPER_ADMIN can save/clear SSO configuration.
- Metadata URL is validated as HTTPS when present.
- Domain is validated as a DNS-like string.
- Enforcing SSO without a provider is blocked.
- Enforcing SSO before the calling admin has verified their own SSO/OAuth login is blocked.
- `GOOGLE_OIDC` enforcement can piggyback on the existing Google OAuth path.

## What Does Not Work

SAML is not wired end-to-end:

- no Spring Security SAML service-provider dependency,
- no SP metadata endpoint,
- no assertion consumer service,
- no IdP-initiated or SP-initiated SAML flow,
- no SAML assertion validation or attribute mapping.

`OKTA_SAML` and `AZURE_AD_SAML` can be stored as planned configuration, but `enforced=true` is rejected for them.

## Implementation Boundary

Do not claim enterprise SAML support until these land:

1. SAML dependency and security filter chain.
2. SP metadata endpoint.
3. ACS endpoint and assertion validation.
4. Per-org IdP metadata resolution.
5. Email/domain binding and JIT provisioning policy.
6. Admin self-test flow before enforcement.
7. Integration tests with at least one IdP.

## Frontend

Frontend helpers live in:

- `frontend/src/lib/api/misc.ts`
- `frontend/src/lib/api/core.ts` (`SsoConfig`, `SsoProvider`)

The UX must make the same distinction as the backend: "configuration saved" is not the same as "SAML login active."
