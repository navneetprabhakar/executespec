# Backend/API local validation report

Date: 2026-07-22 (Asia/Kolkata)  
Branch observed: `fix/p0-remainder-20260721`  
Backend: local Spring profile, PostgreSQL schema V339, health HTTP 200.

## Result summary

Validated 41 HTTP assertions across authentication, tenant isolation, run admission, idempotency, SSE, pagination/input boundaries, and webhook signature rejection. The initial pass found one product defect: logout revoked the refresh grant but left the previously issued access JWT usable. The implementation was repaired and the exact live sequence was rerun successfully. The Enterprise/SUPER_ADMIN fixture remained blocked by an unexplained authentication failure despite narrow fixture repair.

## Identity/fixture status

- FREE, PRO, TEAM: documented credentials authenticate (HTTP 200), `/api/auth/me` and permissions return 200.
- ENTERPRISE: documented credential initially returned 401.
- SUPER_ADMIN: credential file contains an invalid trailing dot in the email; removing it still returned 401.
- Authorized local-fixture repair changed only `password_hash` on `revalidate-ent@test.local` and `admin@executespec.ai`. First, both were assigned the known-good FREE fixture bcrypt hash; a second independent bcrypt hash for the same documented password was also tried. Rows are `ACTIVE`, email-verified, not deleted, and Enterprise has an `ORG_ADMIN` membership. Both logins still return 401.
- This rules out password hash, status, verification, deletion, and Enterprise membership as the immediate cause. `AuthService.login` catches every remaining authentication exception and rewrites it to `Invalid credentials`, preventing diagnosis from HTTP/log evidence. Admin additionally has no `org_member` row, although SUPER_ADMIN authentication should not require one.
- PAT lifecycle was validated with FREE: create 201, PAT `/me` 200, revoke 204, revoked PAT 401, repeated revoke 204.

All plaintext JWTs, PATs, refresh cookies, and SSE tokens used for the checks were removed from the evidence directory. Persisted evidence contains only sanitized responses/summaries.

## Cross-tenant and RR-1551 matrix

Three distinct personal orgs were exercised (FREE, PRO, TEAM).

| Actor -> run owner | Detail | Cost |
|---|---:|---:|
| FREE -> FREE | 200 | 200 |
| PRO -> PRO | 200 | 200 |
| TEAM -> TEAM | 200 | 200 |
| Every six cross-org combinations | 403 | 403 |

The first 20 visible run IDs for each identity had zero intersections across FREE/PRO, FREE/TEAM, and PRO/TEAM.

Identical new production submissions settled at the plan gate and produced tenant/model-specific estimates:

| Org | State | estimate low/high | credits | hard cap |
|---|---|---:|---:|---:|
| FREE | `AWAITING_APPROVAL` | 0.001 / 0.00345 | 1 | 5.0 |
| PRO | `AWAITING_APPROVAL` | 0.04930725 / 0.14792175 | 1 | 5.0 |
| TEAM | `AWAITING_APPROVAL` | 0.04930725 / 0.14792175 | 1 | 5.0 |

This proves cost responses are access-guarded and submissions calculate independently. It does not alone prove the historical estimator cannot consume another tenant's rows; that stronger RR-1551 proof remains the repository/unit pin or a controlled history-fixture experiment. Source inspection confirms both strict and loose history queries require `h.orgId = :orgId`.

## Auth/logout

- Login + authenticated `/me`: 200 for FREE/PRO/TEAM.
- Initial defect/repro: login, retain the access JWT and refresh cookie, call `DELETE /api/auth/logout`, then call `/api/auth/me` with the retained access JWT. Before the repair, logout returned 204 and the old refresh cookie returned 401, but the old access JWT incorrectly returned 200.
- Repair: logout now blocklists the refresh JTI and advances the user's token invalidation epoch. Refresh rotation retains the narrower JTI-only revocation path. JWTs carry a full-precision `issued_at` claim with legacy `iat` fallback to avoid a same-second logout/re-login collision.
- Corrected live result: login `/me` 200; logout 204; pre-logout access JWT 401; pre-logout refresh cookie 401; immediate re-login `/me` 200.
- Regression coverage explicitly exercises old access rejection, old refresh rejection, and acceptance of new access/refresh tokens whose standard JWT `iat` collides in the same second with the old session.

## Run admission, plan gate, and idempotency

- PRO production submission returned 202 and reached `AWAITING_APPROVAL`, proving production does not skip directly to execution (RR-1519).
- Same successful `Idempotency-Key` + same body: both responses 202 and identical run ID.
- Same key + changed body: 409.
- A permanently failed claim (PRO attempted Enterprise-only exact model pin, 403) replayed as 409; changed body with that key also returned 409.
- Negative page: 400; oversized page: 400.

## Access/security negatives

- Unauthenticated run list: 401.
- Cross-tenant run detail/cost: 403.
- Cross-tenant SSE-token mint: 403; own token mint: 200.
- Encoded traversal in run path: 400.
- Invalid run UUID: 404 without data disclosure.
- GitHub webhook missing signature: 401; bad signature: 401.
- Stripe webhook missing signature: 401.
- Razorpay webhook missing signature: 401.

## SSE reconnect

The same valid scoped SSE token established two sequential event connections. Both returned HTTP 200 and a 12-byte keepalive before the intentional two-second client timeout. This proves reconnect acceptance locally; it does not simulate replica-to-replica Redis echo behavior for RR-424.

## Honest limitations

- Enterprise, BYOK exemption (RR-1338), SUPER_ADMIN-only endpoints, missing-tier fail-closed mutation (RR-830), invite-admin role matrices, and privileged feature-flag paths were not dynamically reachable because the Enterprise/SUPER_ADMIN fixtures remain unable to authenticate.
- Webhook valid-signature replay/idempotency was not exercised because no local webhook secret was disclosed and fabricating one would weaken the test.
- RR-1551's strongest adversarial proof (foreign high-cost history injected while holding target history constant) was not performed; only API isolation, independent calculations, and the tenant predicate were verified.
- No full LLM execution was approved; created production runs were intentionally left at the plan gate to avoid uncontrolled spend.
