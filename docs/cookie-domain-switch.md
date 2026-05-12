# Cookie domain switch (Phase 2 surface split)

> Operator runbook + maintenance-window plan for the JWT cookie-domain
> change required to share auth across the still-pending consumer
> (`app.executespec.ai`) and B2B (`build.executespec.ai`) surfaces.
> Last reviewed: 2026-05-12 (1.0.0-M1).
>
> **Status:** the surface split is pending. V143 prototyped the schema +
> `ProductSurfaceFilter` + `OAuthSurfaceResolver`, then V179 + V185
> retired the consumer/B2B `product_id` discriminator. The cookie-domain
> switch below is reserved for when the surface split is actually
> deployed; today's prod is single-surface (`executespec.ai` →
> `dev.executespec.ai`) and the cookie stays single-host.

## Why this exists

When the surface split is revived, the JWT cookie has to be issued at the
parent domain (`.executespec.ai`) instead of the current single-host
(`api.executespec.ai`). Switching that requires a maintenance
window because every existing session cookie becomes invalid.

## Status today

- Auth cookie issued by `JwtUtil.issueRefreshTokenCookie` at the
  current host.
- `CorsConfig` already extends the allow-list to both
  `app.executespec.ai` and `build.executespec.ai` via
  `application-prod.yml:cors.allowed-origins` (Plan 1 #5
  pre-emptively widened).
- `OAuthSurfaceResolver` (Plan 1 #5) already routes OAuth callbacks
  to the right surface — the only piece missing is the cookie
  scope.

## What changes

### Code
- `JwtUtil.issueRefreshTokenCookie` — new `cookie-domain` config
  knob in `application-prod.yml`. Empty/null = current behavior
  (single-host); `.executespec.ai` = parent-domain scope.
- `application-prod.yml`:
  ```yaml
  auth:
    cookie:
      domain: ${AUTH_COOKIE_DOMAIN:}     # blank = single-host (legacy)
      same-site: ${AUTH_COOKIE_SAMESITE:Lax}
      secure: ${AUTH_COOKIE_SECURE:true}
  ```
- The Plan 1 CORS allow-list already covers both subdomains, so no
  CORS change.

### DNS
- `app.executespec.ai` → CNAME or A → consumer surface backend.
- `build.executespec.ai` → CNAME or A → B2B surface backend.
- Both backends must share session storage (Redis already org-wide).

### Stripe + Razorpay webhook URLs
- Webhook endpoints already point at `api.executespec.ai`. Keep
  them there — they're API-side, not UI-side. No change.

## Maintenance window plan

This is a 30-minute window with all-hands oncall. Communicated
via in-app banner + email 14 days in advance + status page entry.

### T-14 days
- Email all customers: "session reset on YYYY-MM-DD HH:MM UTC due
  to platform-architecture change. You'll need to log in again."
- In-app banner on every page header (existing
  `LegacyBillingBanner` pattern works).
- Status page scheduled-maintenance entry.

### T-1 hour
- Reminder email + final in-app banner ("session reset in 1
  hour").
- Lock new signups (feature flag `auth.signup-disabled=true`) so
  we don't mint new cookies right before they get invalidated.

### T0 (window start)
1. Set `AUTH_COOKIE_DOMAIN=.executespec.ai` on the prod env.
2. Restart the prod jar (rolling restart on Cloud Run; ~3 min).
3. Verify the new cookie is issued at `.executespec.ai` scope
   on a fresh login.
4. Force-logout every existing session (toggle the
   `auth.logout-epoch` feature flag — existing tooling per
   `ForceLogoutService`). Every active JWT becomes invalid.
5. Watch error-rate dashboards for 5 min.

### T+30 min (window end)
- Re-enable signups (`auth.signup-disabled=false`).
- Update status-page entry → resolved.
- Clear in-app banner.
- Email customers: "platform updated; please log back in if you
  haven't already".

### Rollback (if cookie scope breaks something unexpected)
1. Unset `AUTH_COOKIE_DOMAIN`.
2. Restart prod jar.
3. Force-logout again (clears the parent-domain cookies).
4. Customers log back in; everything's back to single-host scope.

Total rollback time: ~10 minutes.

## What this enables (post-flip)

- A user logged in at `app.executespec.ai` can hit
  `build.executespec.ai` without re-auth.
- The new product-surface filter (Plan 1 #2) reads the JWT on
  either subdomain.
- OAuth init at one surface returns the user to that surface's
  origin.

## Testing the switch in lower envs

Before prod:

1. **Local:** Set `AUTH_COOKIE_DOMAIN=.executespec.local` in
   `application-dev.yml` + add `app.executespec.local` and
   `build.executespec.local` to `/etc/hosts` pointing at
   `127.0.0.1`. Verify a login at `app.…` is recognized at
   `build.…` without re-auth.
2. **Staging:** Same flag flip on the staging env;
   internal-only smoke test with a few accounts.
3. **Prod:** This runbook.

## Out of scope (separate tickets)

- The actual surface split itself (M-030 ProductSurfaceFilter
  call-site wiring) — different work, can land before or after
  the cookie switch.
- SCIM provisioning across both surfaces (M-037 backlog).
- Cross-subdomain CSRF token handling — Spring Security's
  `CookieCsrfTokenRepository` honours the same domain config
  the JWT cookie uses; no extra work.
