# Cookie And Session Model

> Recreated 2026-06-10 from the current auth code. This document describes the live browser/session contract, not historical cookie experiments.

## Token Model

ExecuteSpec uses two browser-facing credentials:

| Credential | Issued by | Transport | Lifetime | Purpose |
| --- | --- | --- | --- | --- |
| Access JWT | `AuthService` via `AuthController` | Frontend stores and sends `Authorization: Bearer ...` | 1 hour | Normal REST calls |
| Refresh token | `AuthController.setRefreshCookie` | `HttpOnly` cookie named `refresh_token` | 7 days | `/api/auth/refresh` rotation |

Programmatic clients can also use Personal Access Tokens with the `es_pat_` prefix. PATs are bearer tokens too, but they are not browser cookies.

## Refresh Cookie

The refresh cookie is set by:

- `src/main/java/com/navneet/executespec/auth/controller/AuthController.java`
- `POST /api/auth/login`
- `GET /api/auth/verify-email`
- `POST /api/auth/refresh`

Important attributes:

- name: `refresh_token`
- path: `/api/auth`
- `HttpOnly`: true
- `Secure`: controlled by `auth.refresh-cookie.secure`
- max age: 7 days

Refresh rotates the token: the old refresh token is logged out/revoked, a new token is minted, and the cookie is replaced.

Logout clears only the refresh cookie path and revokes the presented refresh token. Existing access JWTs are invalidated separately through `POST /api/auth/invalidate-sessions`, which sets the user's token invalidation epoch.

## OAuth State Cookie

OAuth authorization requests are persisted in short-lived signed cookies keyed by `state`, instead of relying only on the Tomcat session:

- `src/main/java/com/navneet/executespec/auth/oauth/CookieOAuth2AuthorizationRequestRepository.java`
- wired in `SecurityConfig` via `oauth2Login(...).authorizationEndpoint().authorizationRequestRepository(...)`

Important attributes:

- cookie name: `es_oauth2_req_<sha256(state)[..32]>` (one per in-flight OAuth state)
- path: `/api/auth/oauth2/callback`
- `HttpOnly`: true
- `Secure`: set when the request is HTTPS
- `SameSite`: `Lax`
- TTL: 5 minutes
- payload signed (HMAC-SHA256, `app.jwt.secret`); a bad/missing signature is ignored, and the cookie is cleared on callback

This makes the provider callback resilient to missing, stale, duplicate, or overwritten session cookies and to parallel OAuth attempts, which previously surfaced as `authorization_request_not_found`.

## OAuth Redirect Cookie

The CLI/device-login OAuth fix uses a separate short-lived redirect helper cookie:

- `src/main/java/com/navneet/executespec/auth/oauth/OAuthRedirectCookieFilter.java`
- `OAuthSuccessHandler`

Important attributes:

- cookie name: `es_oauth_redirect`
- path: `/`
- `HttpOnly`: true
- `Secure`: set when the request is HTTPS
- `SameSite`: `Lax`
- max age: 300 seconds

The cookie captures the `?redirect=/cli-login...` return URL on the authorize request and preserves it across Google/GitHub OAuth; `OAuthSuccessHandler` reads, clears, and forwards back to `/cli-login`. Only `/cli-login` targets are eligible (open-redirect guard). It is separate from the refresh cookie and should not be treated as an authenticated session.

## SSE Tokens

Run event streams do not reuse the long-lived access JWT directly. The frontend asks for a scoped run token:

- `GET /api/runs/{runId}/sse-token`
- `GET /api/runs/{runId}/events`

The scoped token has a short TTL and is tied to the run id. This limits blast radius if an EventSource URL is logged by the browser or proxy.

## CORS And Domains

Configured origins live under `app.cors.allowed-origins` in `src/main/resources/application.yml` and profile-specific configuration.

Current contract:

- Local Vite development uses localhost origins.
- Dev/prod domains are served behind Cloudflare.
- The backend is the API origin and also serves the production frontend bundle from `static/`.
- Cookies must be tested per environment because local HTTP and Cloudflare HTTPS differ on the `Secure` attribute.

## Security Notes

- Do not put access tokens in cookies unless the CSRF model is redesigned.
- Do not widen refresh-cookie path beyond `/api/auth` without a CSRF review.
- Keep OAuth redirect state short-lived and allowlist redirect targets.
- When auth works in one environment but not another, check `auth.refresh-cookie.secure`, CORS origins, and OAuth callback base URLs first.

## Code Pointers

- `src/main/java/com/navneet/executespec/auth/controller/AuthController.java`
- `src/main/java/com/navneet/executespec/auth/security/JwtAuthenticationFilter.java`
- `src/main/java/com/navneet/executespec/auth/oauth/CookieOAuth2AuthorizationRequestRepository.java`
- `src/main/java/com/navneet/executespec/auth/oauth/OAuthRedirectCookieFilter.java`
- `src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java`
- `src/main/java/com/navneet/executespec/api/controller/SseController.java`
- `frontend/src/lib/api/core.ts`
