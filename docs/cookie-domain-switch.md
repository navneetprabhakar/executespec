# Cookie & Session Model

> What ExecuteSpec writes to the browser, exactly how, and from where in the code. This file documents the live behaviour — including the known gaps (e.g. `Secure=false` in dev, OAuth-tokens-via-URL-fragment) so operators can plan rotations and migrations against accurate facts.

## 1. Session model at 30k feet

ExecuteSpec uses a **hybrid** token model:

1. **Access token** — short-lived JWT delivered as `Authorization: Bearer <token>` on every API call. Issued by [`JwtUtil.generateAccessToken(AppUser, OrgMember)`](../src/main/java/com/navneet/executespec/auth/security/JwtUtil.java).
2. **Refresh token** — longer-lived JWT delivered via an HTTP-only refresh cookie. Issued by [`JwtUtil.generateRefreshToken(AppUser)`](../src/main/java/com/navneet/executespec/auth/security/JwtUtil.java); the cookie is set in `AuthController.setRefreshCookie(...)`.
3. **SSE scoped token** — 5-minute JWT with `scope=sse` and a `runId` claim. Delivered in the `?token=` query param on the SSE URL because `EventSource` cannot send `Authorization` headers; the [`JwtAuthenticationFilter`](../src/main/java/com/navneet/executespec/auth/security/JwtAuthenticationFilter.java) explicitly accepts this fallback.

All three are HS256 JWTs minted in [`JwtUtil`](../src/main/java/com/navneet/executespec/auth/security/JwtUtil.java) using `app.jwt.secret`. There is no opaque-token store; all auth is JWT-based.

## 2. Access token (Bearer) — the primary path

| Concern | Value | Source |
| ------- | ----- | ------ |
| Algorithm | HS256 | `JwtUtil` |
| Carrier | `Authorization: Bearer <token>` header | extracted by [`JwtAuthenticationFilter`](../src/main/java/com/navneet/executespec/auth/security/JwtAuthenticationFilter.java) |
| Default TTL | **15 minutes** in the code (`app.jwt.access-token-expiry-minutes` defaults to `15` in `JwtUtil`'s ctor) — but `application.yml` overrides this to **360 minutes** (6 h). Prod must explicitly tune this. | `JwtUtil`; `application.yml` |
| Claims | `sub=userId`, `email`, `globalRole`, `tier`, `orgId`, `orgRole`, plus standard `iat`/`exp` | `JwtUtil.generateAccessToken` |
| Per-user invalidation | `app_user.token_invalidated_at` Instant — `JwtAuthenticationFilter` rejects any token issued before this epoch | per-user JWT epoch in [`AppUser`](../src/main/java/com/navneet/executespec/auth/entity/AppUser.java) |
| Global force-logout | feature-flag-driven global epoch — flips invalidate every JWT in flight | `JwtUtil`, `FeatureFlagService` |

Honest gap: there's a documented drift between the in-code default (15 min) and the configured value (360 min). Cleanup is on the followup list, but for ops the value to trust is the active YAML.

## 3. Refresh cookie

Set in `AuthController.setRefreshCookie(...)` on three paths:

- `POST /api/auth/login` — on successful authentication.
- `POST /api/auth/refresh` — on rotation (the old refresh JTI gets added to `refresh_token_blocklist`).
- `GET /api/auth/verify-email` — F-29 single-step verification → auto-session.

Cleared on `DELETE /api/auth/logout`.

### Cookie attributes (live values from `AuthController.setRefreshCookie`)

| Attribute | Value | Notes |
| --------- | ----- | ----- |
| Name | (refresh-token cookie) | set by `AuthController.setRefreshCookie` |
| `HttpOnly` | `true` | not readable from JS |
| `Secure` | `false` (with comment `"set true in production (HTTPS)"`) | relies on infra HTTPS termination at Cloudflare, not on the cookie attribute itself |
| `SameSite` | (controller-set, see code; behaviour is server-side same-site default — no cross-site send) |
| `Path` | (default; cookie scoped to the API host) |
| `Domain` | not set — cookie defaults to the response host | see §5 on cross-subdomain handling |
| `Max-Age` | `app.jwt.refresh-token-expiry-days * 86400` seconds (default 7 d) | `JwtUtil.getRefreshTokenExpiryDays()` |

The comment "`set true in production (HTTPS)`" is in the source — the flag is **not** environment-conditional. In every environment (local, dev, prod) the same `setSecure(false)` line runs; production relies on Cloudflare upstream + Spring's `server.forward-headers-strategy: framework` to ensure the cookie is only ever set on responses to HTTPS requests in practice. This is a known gap that should be tightened (env-conditional `Secure=true` in prod/dev profiles).

### Rotation semantics

`POST /api/auth/refresh`:

1. Reads the refresh cookie.
2. Validates the JWT signature + expiry + checks the `jti` against [`RefreshTokenBlocklistRepository`](../src/main/java/com/navneet/executespec/auth/repository/RefreshTokenBlocklistRepository.java).
3. Issues a **new** access token and a **new** refresh token (new `jti`).
4. Inserts the old `jti` into the blocklist via the native `insertIfAbsent` (ON CONFLICT DO NOTHING) so replay is rejected.
5. Sets the new refresh cookie on the response.

### Logout

`DELETE /api/auth/logout`:

1. Reads the refresh cookie.
2. Pushes the JTI into `refresh_token_blocklist` with `expires_at` matching the original token's expiry.
3. Clears the cookie on the response (`Max-Age=0`).

Note: this kills the refresh path but does not kill in-flight access tokens. To kill all tokens for one user immediately, `POST /api/auth/invalidate-sessions` (which flips `app_user.token_invalidated_at`) is the per-user lever, and the global force-logout flag flips the platform epoch.

## 4. JWT delivery for SSE

[`SecurityConfig`](../src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java) permits `/api/runs/*/events` unauthenticated at the path level — the auth check happens in [`JwtAuthenticationFilter`](../src/main/java/com/navneet/executespec/auth/security/JwtAuthenticationFilter.java) using the `?token=...` query param fallback. The token must be either:

- A regular access token (back-compat), or
- A purpose-scoped SSE token from `GET /api/runs/{runId}/sse-token` — 5-min TTL, `scope=sse`, `runId` claim. `JwtUtil.validateSseToken(token, runId)` checks the scope and that the token's `runId` claim matches the URL.

This path is why `?token=` is recognised in addition to the Bearer header. The SSE controller is [`SseController`](../src/main/java/com/navneet/executespec/api/controller/SseController.java).

## 5. Cross-subdomain handling

In production we serve from multiple subdomains under `*.executespec.ai`:

- `executespec.ai`, `www.executespec.ai` — marketing.
- `app.executespec.ai`, `build.executespec.ai` — product SPAs.
- `dev.executespec.ai` — dev environment.
- `preview-engine.executespec.ai` — on-demand previews.

### Current cookie design

The refresh cookie's `Domain` attribute is **not** set in `AuthController.setRefreshCookie`. That means the cookie is scoped to whichever host the API responds from. As a consequence:

- Frontends and the API live on the same host in prod (the API is mounted at `/api/*` on the same origin) — so the cookie works.
- If a frontend were ever split onto a sibling subdomain (e.g. `app.executespec.ai` pointing at API on `api.executespec.ai`), the refresh cookie would not be sent cross-subdomain. The fix at that point would be either `Domain=.executespec.ai` on the cookie or moving to a same-origin BFF.

This is the "cookie domain switch" lever this document is named after: today, cookies are host-scoped; the lever to flip is the `Domain` attribute in `setRefreshCookie`.

### Cloudflare interaction

Cloudflare sits in front of `*.executespec.ai` and terminates TLS. Spring is configured with `server.forward-headers-strategy: framework` in `application-prod.yml` and `application-dev.yml`, so Spring's `ForwardedHeaderFilter` reads `X-Forwarded-Proto: https` from Cloudflare and renders absolute URLs correctly. The cookie itself is still emitted with `Secure=false` (see §3 known gap); we rely on Cloudflare upstream to make the practical attack-surface small.

## 6. CORS configuration

CORS is configured in [`SecurityConfig.corsConfigurationSource()`](../src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java), reading `app.cors.allowed-origins` (`@Value` field). The value is a comma-separated list and `allowCredentials=true` is set so the refresh cookie flows on cross-origin XHR when allowed.

### Per-profile origins

| Profile | `app.cors.allowed-origins` | Source |
| ------- | -------------------------- | ------ |
| `application.yml` (base / local) | `http://localhost:5173` | `application.yml` |
| `application-local.yml` | `http://localhost:5173,http://localhost:5174` | `application-local.yml` |
| `application-dev.yml` | includes `dev.executespec.ai`, `app.dev`, `build.dev`, plus marketing `executespec.ai` / `www.executespec.ai` | `application-dev.yml` |
| `application-prod.yml` | defaults to `https://executespec.ai,https://www.executespec.ai,https://app.executespec.ai,https://build.executespec.ai`, env-overridable | `application-prod.yml` |

Anomaly noted in research: there is a stale block of YAML in `application.yml` near `app.cors.allowed-origins` (lines ~700-705) where two retired V143 product-routing keys (`app-return-url`, `build-return-url`) sit at the wrong indentation — they appear nested under `cors` after the V143 retirement (V179 rolled it back). The behaviour is benign because the CORS reader only consumes `allowed-origins`, but the YAML should be cleaned up.

## 7. OAuth2 sign-in flow (Google, GitHub)

When the user signs in via Google or GitHub, control passes through Spring Security's OAuth2 client and lands in [`OAuthSuccessHandler.onAuthenticationSuccess(...)`](../src/main/java/com/navneet/executespec/auth/oauth/OAuthSuccessHandler.java). On success:

1. The handler resolves the OAuth profile via [`OAuthUserMapper`](../src/main/java/com/navneet/executespec/auth/oauth/OAuthUserMapper.java) — Google extracts `email` from `OAuth2User`; GitHub falls back to `GET /user/emails` with the OAuth2 access token if email is private.
2. `AuthService.findOrCreateFromOAuth(...)` either links to an existing `AppUser` (matching `oauth_provider + oauth_id`, or by email if the email is verified) or creates a new account.
3. The handler mints access + refresh tokens via `JwtUtil`.
4. The handler redirects the browser to `${app.mail.base-url}/auth/callback?token=<accessToken>&refresh=<refreshToken>`.

Honest gap: **tokens-in-redirect.** Both the access and refresh tokens are sent via URL-encoded query params on the redirect. The frontend SPA picks them up, posts them into local state, and clears the URL. This is a common SPA pattern, but it means:

- Both tokens appear in the browser history of whichever browser executed the OAuth dance.
- Server access logs that capture the redirect URL (Cloudflare access logs, any intermediate proxy) would see both tokens.
- A future tightening would be to set the refresh cookie server-side on the OAuth callback (instead of redirecting with `refresh=`) and only return the access token in the URL — or, better, set both cookies server-side and redirect with no token in the URL. Currently neither path is taken.

If `OAuthEmailConflictException` is thrown (email already linked to a different provider), the handler redirects to `/login?error=oauth_email_conflict`. Other failures fall through to `/login?error=oauth_failed`.

## 8. Auth filter chain (high-level)

```
HTTP request
    -> CorsConfigurationSource (from app.cors.allowed-origins)
    -> SecurityFilterChain
       -> JwtAuthenticationFilter   (before UsernamePasswordAuthenticationFilter)
            * Bearer header  OR  ?token= query param (SSE)
            * validates signature + expiry
            * compares iat vs app_user.token_invalidated_at
            * loads UserDetails via UserDetailsServiceImpl
       -> RateLimitFilter            (after JWT — see ratelimit/)
    -> Controller handler
```

`SecurityConfig` is `stateless` when OAuth is disabled and `RequestAttribute`-scoped when OAuth is enabled (Finding 8 fix — see comments in `SecurityConfig`). `@EnableMethodSecurity` is on so `@PreAuthorize` works on controllers.

### Public paths
`SecurityConfig.filterChain` permits the following unauthenticated:

- `/api/auth/**` (except `/me/**` and `/settings/**` and `change-password`), `/api/auth/oauth2/**`, `/oauth2/**`, `/login/oauth2/**`.
- `/api/webhooks/{razorpay,stripe}` — signature-secured, JWT-bypass intentional.
- `/api/health/**`, `/actuator/health/**`, `/actuator/prometheus`.
- `/api/integrations/*/webhook`, `/webhooks/github`, `/api/internal/reminder/send`, `/api/integrations/slack/oauth/callback`.
- `/api/runs/*/events` — SSE; the JWT filter does the auth via `?token=`.
- `/api/reports/shared/*`, `/api/shared/**`, `/api/invites/preview`.
- Anonymous trial: `/api/v3/anonymous-runs[/*]`, `/api/v3/referrals/lookup`, `/api/v3/landing/stats`, `/api/v3/landing/sample-runs`, `/api/v3/public/**`.
- `/api/admin/feature-flags/public`.
- `/api/v2/test/**` — flagged temporary; profile-gated (`local`/`dev`) on every controller via `@Profile`.

Everything else under `/api/**` requires authentication. `/api/admin/**` requires `SUPER_ADMIN`. `/actuator/**` (other than health/prometheus) requires `SUPER_ADMIN`.

## 9. Security headers

Configured in `SecurityConfig` via `SecurityProperties`:

- **CSP** — flag-driven (`app.security.csp.enabled=true` by default).
- **HSTS** — `max-age=31536000`, `include-sub-domains=true`. Preload toggle is feature-flagged (UX audit B29).
- **X-Frame-Options** — `SAMEORIGIN`.

The 401 entry point is [`JsonAuthenticationEntryPoint`](../src/main/java/com/navneet/executespec/auth/security/JsonAuthenticationEntryPoint.java) — returns a stable JSON envelope `{timestamp, status, error, message, path}` for any unauthenticated `/api/**`, `/actuator/**`, `/webhooks/**`, `/oauth2/**`, `/login/oauth2/**` request instead of the default 302 to the login page.

## 10. Known gaps & TODOs

1. **`Secure=false` on the refresh cookie.** Hard-coded in `AuthController.setRefreshCookie`; production relies on Cloudflare TLS termination + `server.forward-headers-strategy`. The flag should become env-conditional (`Secure=true` for non-local profiles) before the next browser-security audit pass.
2. **Cookie `Domain` not set.** Today the refresh cookie is host-scoped. If a future frontend split moves the SPA off the API origin, the cookie won't flow. The lever is in `AuthController.setRefreshCookie`.
3. **OAuth tokens in redirect URL.** `OAuthSuccessHandler` passes both `token=` and `refresh=` query params on the callback redirect. Refresh tokens belong in cookies, not in URLs that get logged.
4. **JWT access-token TTL drift.** Code default is 15 min in `JwtUtil`'s ctor; `application.yml` overrides to 360 min. Pick a single source-of-truth.
5. **Stale CORS YAML.** `application.yml` has misplaced V143 product-routing keys (`app-return-url`/`build-return-url`) indented under `app.cors` — benign, but worth cleaning.
6. **`/api/v2/test/**` permitted unauthenticated.** Profile-gated to `local`/`dev` by controller annotation; SecurityConfig still has the `permitAll` line marked "temporary, removed once LlmRouter lands" — `LlmRouter` is live, so the matcher can be removed.
