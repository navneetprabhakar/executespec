# Cookie & session model

> Last refreshed: 2026-05-16. Source of truth:
> `src/main/java/com/navneet/executespec/auth/controller/AuthController.java`,
> `src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java`,
> `src/main/java/com/navneet/executespec/auth/security/JwtAuthenticationFilter.java`,
> `src/main/resources/application*.yml`.

This document describes what cookies the backend issues, how JWTs are
delivered, and how cross-subdomain behaviour is configured today.

---

## 1. JWT delivery — header is primary, cookie is for refresh only

Two flavours of JWT are in circulation:

| Token         | Lifetime                       | How it reaches the server                                                                       |
| ------------- | ------------------------------ | ----------------------------------------------------------------------------------------------- |
| Access token  | `app.jwt.access-token-expiry-minutes` (360 min default — `application.yml:697`) | `Authorization: Bearer …` header                                                |
| Access token  | same                           | `?token=<jwt>` query param fallback (SSE only — `EventSource` cannot set headers)               |
| Refresh token | `app.jwt.refresh-token-expiry-days` (7 days — `application.yml:698`)            | `refresh_token` HTTP cookie                                                     |
| SSE token     | 5 minutes (`JwtUtil.generateSseToken` line 87-98) | `?token=<jwt>` query param on `/api/runs/{id}/events`                          |

### Filter

`JwtAuthenticationFilter.extractBearerToken` (`auth/security/JwtAuthenticationFilter.java:60-72`):

```java
// 1. Standard Authorization header
String header = request.getHeader("Authorization");
if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
  return header.substring(7);
}
// 2. Query-param fallback for SSE (EventSource can't set headers)
String queryToken = request.getParameter("token");
if (StringUtils.hasText(queryToken)) {
  return queryToken;
}
return null;
```

Access tokens are **not** read from cookies. The only cookie the backend
issues is the refresh-token cookie described below.

---

## 2. The `refresh_token` cookie

Set in three places, all on `AuthController`:

- `setRefreshCookie` (lines 613-620) on `POST /api/auth/login`,
  `POST /api/auth/refresh`, `GET /api/auth/verify-email`.
- Cleared (zero-value, `maxAge=0`) on `DELETE /api/auth/logout`
  (lines 87-91).

### Code

```java
private void setRefreshCookie(HttpServletResponse response, String refreshToken) {
  Cookie cookie = new Cookie("refresh_token", refreshToken);
  cookie.setHttpOnly(true);
  cookie.setSecure(false); // set true in production (HTTPS)
  cookie.setPath("/api/auth");
  cookie.setMaxAge(REFRESH_COOKIE_MAX_AGE_SECONDS);
  response.addCookie(cookie);
}
```

`REFRESH_COOKIE_MAX_AGE_SECONDS = 7 * 24 * 3600` (line 49 — 7 days).

### Attribute summary

| Attribute     | Value                                  | Notes                                                       |
| ------------- | -------------------------------------- | ----------------------------------------------------------- |
| Name          | `refresh_token`                        |                                                             |
| HttpOnly      | `true`                                 | Browser JS cannot read it.                                  |
| Secure        | `false` (set true in prod — TODO)      | **Known gap**: the code-level value is `false`; production should override. See §3. |
| Path          | `/api/auth`                            | Cookie only sent on `/api/auth/*` requests.                 |
| Domain        | *unset* (defaults to request host)     | See §5 on cross-subdomain implications.                     |
| Max-Age       | 604 800 seconds (7 days)               |                                                             |
| SameSite      | *unset* (Tomcat default)               | Spring Boot 3.x defaults to `Lax` for `Servlet.setCookie`.  |
| Version       | RFC 2109 / Servlet default             |                                                             |

There is **no** `setSameSite` call anywhere in the codebase
(`grep -rn "setSameSite" src/main/java` returns no results in the auth
package). Spring Boot 3.x leaves `SameSite=Lax` as the effective default
unless overridden globally — confirmed by the absence of a
`CookieSameSiteSupplier` bean in the security configuration.

---

## 3. Known gap: `Secure=false` is set in code

The comment on line 616 of `AuthController.java` explicitly flags this as a
production todo:

```java
cookie.setSecure(false); // set true in production (HTTPS)
```

This means **as-shipped, the refresh cookie has `Secure=false`** — a browser
will send it over both HTTP and HTTPS. The two operational mitigations:

1. The Cloudflare edge + GCE Caddy proxy in front of the JVM redirect every
   HTTP request to HTTPS, so in practice the cookie never travels on plain
   HTTP in dev/prod. The HSTS header (configured in `SecurityProperties`)
   reinforces this for repeat visitors.
2. The cookie's `Path=/api/auth` keeps it off any non-auth endpoint, so
   even if it were exfiltrated by a mixed-content attack the blast radius
   is restricted to refresh + logout flows.

If this code path is reused for a future "store access token in cookie"
move, the `setSecure(true)` flip is mandatory and should be conditioned on
the active profile (`Environment.acceptsProfiles("prod", "dev")`).

---

## 4. Logout — explicit cookie clearing

`AuthController.logout` (lines 81-93):

```java
Cookie cookie = new Cookie("refresh_token", "");
cookie.setHttpOnly(true);
cookie.setPath("/api/auth");
cookie.setMaxAge(0);
response.addCookie(cookie);
```

Notable: the logout-clear cookie **does not call `setSecure(true)`** even
in principle; the browser will accept the clear cookie regardless. The
`AuthService.logout(refreshToken)` call additionally inserts the token's
JTI into `refresh_token_blocklist` so a leaked copy cannot be redeemed
even before its natural expiry.

---

## 5. Cross-subdomain behaviour

### What's configured

- The cookie's `Domain` attribute is **not** set explicitly. Per the
  Servlet spec / RFC 6265, the cookie domain defaults to the request host
  with no leading dot. So a cookie issued at `dev.executespec.ai` is sent
  only when the browser navigates back to `dev.executespec.ai`.
- The CORS allow-origin is configured per-environment via the
  `app.cors.allowed-origins` property
  (`application.yml:700`, overridable via env). On `prod` it's set to
  `https://executespec.ai`; on `dev` it's `https://dev.executespec.ai`;
  local dev defaults to `http://localhost:5173`.

### Implication

The current refresh-cookie model is **single-host**. If you want
authenticated requests against both `executespec.ai` and `dev.executespec.ai`
from the same browser, the user has to log in on each host separately —
the cookies don't share.

If a future change wants to make the refresh cookie span subdomains, the
two-line edit is:

1. Add `cookie.setDomain(".executespec.ai")` to `AuthController.setRefreshCookie`.
2. Pick a SameSite policy explicitly (probably `None; Secure` for the
   cross-subdomain redirect case, or `Lax` if all traffic stays on first-party
   navigation).

That change should be paired with a documented migration window because
existing cookies (which lack a domain attribute) will silently coexist
with the new domain-scoped cookies for up to 7 days until the old ones
expire.

### Cloudflare interaction

The platform sits behind Cloudflare for `*.executespec.ai`. Two relevant
behaviours:

- **`X-Forwarded-Proto: https` propagation** — both the GCE Caddy proxy
  and Cloudflare set this header. `application-dev.yml:79-88` and
  `application-prod.yml:75-79` set
  `server.forward-headers-strategy: framework` so Spring's
  `ForwardedHeaderFilter` honours it. Without this, Spring sees every
  request as HTTP and rebuilds OAuth `redirect_uri` values with the wrong
  scheme. This is the documented reason for the setting in both profile
  YAMLs.
- **No Cloudflare-specific header parsing** — the code does not consult
  `CF-Connecting-IP` or `CF-Ray`. The originating IP for rate limiting +
  audit comes from the standard `X-Forwarded-For` chain via
  `ForwardedHeaderFilter`, not Cloudflare-proprietary headers
  (verified by `grep -rn "cf-" src/main/java` returning no auth-related
  results).

---

## 6. CORS configuration

`SecurityConfig.corsConfigurationSource` (lines 270-282):

```java
CorsConfiguration config = new CorsConfiguration();
config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
config.setAllowedHeaders(List.of("*"));
config.setAllowCredentials(true);
config.setMaxAge(3600L);

UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
source.registerCorsConfiguration("/**", config);
```

Key points:

- `allowCredentials(true)` is required so the browser sends the
  `refresh_token` cookie on `POST /api/auth/refresh` from the frontend
  origin. This is incompatible with `Access-Control-Allow-Origin: *`, so
  `allowedOrigins` must be an explicit list.
- The allowed-origin list comes from `app.cors.allowed-origins`
  (line 700 of `application.yml`, default `http://localhost:5173`).
  Production overrides to the customer-facing domain.
- `OPTIONS /**` is whitelisted in the filter chain
  (`SecurityConfig:224-225`) so preflight requests don't need auth.

---

## 7. Session management

`SecurityConfig.filterChain` lines 121-127:

```java
.sessionManagement(
    sm ->
        sm.sessionCreationPolicy(
            oauthEnabled
                ? SessionCreationPolicy.IF_REQUIRED
                : SessionCreationPolicy.STATELESS))
```

- When **no OAuth2 client** is configured (`oauthEnabled == false`), session
  creation is `STATELESS`. Pure JWT auth.
- When **Google or GitHub OAuth** is enabled, the policy becomes
  `IF_REQUIRED` because Spring's authorisation-code flow round-trips state
  via an `HttpSession`. That session is **only** created during the OAuth
  handshake.

The follow-up at lines 135-139 closes a related leak (adversarial-test
finding 8): the default `HttpSessionSecurityContextRepository` was writing
the authenticated `SecurityContext` back to the session after every JWT
call, creating a `JSESSIONID` cookie even on stateless requests. The
explicit `RequestAttributeSecurityContextRepository` keeps JWT auth fully
stateless and prevents an unnecessary session cookie from being issued.

### Net cookie inventory under normal use

After a successful email + password login:

| Cookie               | Issuer                   | Notes                                                              |
| -------------------- | ------------------------ | ------------------------------------------------------------------ |
| `refresh_token`      | `AuthController.login`   | HttpOnly, Path=/api/auth, 7d. Secure=false in code (see §3).      |
| no `JSESSIONID`      | (Servlet default)        | Suppressed by `RequestAttributeSecurityContextRepository`.        |

After a Google OAuth login:

| Cookie               | Issuer                          | Notes                                                  |
| -------------------- | ------------------------------- | ------------------------------------------------------ |
| `refresh_token`      | `AuthController` (post-redirect) | Same as above (set when the frontend calls /refresh)  |
| `JSESSIONID`         | Spring oauth2Login flow         | Cleared by the auth filter after the SecurityContext is repackaged into the SecurityContextHolder. |

A `JSESSIONID` may briefly appear during the OAuth round-trip; it should
not persist after the access token is delivered to the frontend.

---

## 8. References

- `auth/controller/AuthController.java`
  (`setRefreshCookie` line 613, logout cookie clear line 87,
  `REFRESH_COOKIE_MAX_AGE_SECONDS` line 49).
- `auth/security/SecurityConfig.java`
  (`sessionManagement` line 122, `securityContext` line 135,
  `corsConfigurationSource` line 270).
- `auth/security/JwtAuthenticationFilter.java`
  (`extractBearerToken` line 60).
- `auth/security/JwtUtil.java`
  (`generateAccessToken` line 45, `generateRefreshToken` line 68,
  `generateSseToken` line 87).
- `application.yml` lines 695-700 — JWT + CORS config.
- `application-prod.yml` / `application-dev.yml` —
  `server.forward-headers-strategy: framework` for proxy chain.
