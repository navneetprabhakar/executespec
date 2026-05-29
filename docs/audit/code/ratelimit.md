# Audit: `ratelimit/` — Redis-backed API rate limiting

Status: **DONE** · Updated 2026-05-29 · 3 files / 482 lines · all read in full

## Files
- `RateLimitFilter.java` (301) — `OncePerRequestFilter`, Redis INCR + in-memory fallback ✓
- `RateLimitPolicy.java` (167) — per-group/tier limits + guardrail multiplier ✓
- `RateLimitProperties.java` (14) — `executespec.rate-limit.enabled` master switch ✓

## What it does
Per-request rate limiting after the JWT filter. Classifies each request into a group (AUTH / RUNS / WEBHOOK / READ / WRITE / PREVIEW / DEPLOY), resolves an identity (userId for authed, IP for auth/webhook), and enforces a per-minute bucket via Redis `INCR` with 60 s TTL. Limits scale by tier (FREE/PRO/ENTERPRISE) and tighten under the guardrail level.

## Findings — clean, production-grade. No defects.
- 🟢 **Correct fail-open with a safety net**: on Redis failure it falls back to an in-memory counter at **50 % of the normal limit** + increments an `executespec.redis.failopen` Prometheus counter, so an outage degrades capacity rather than locking everyone out *or* removing the limit entirely. Local counters are bucket-rotated (no memory growth).
- 🟢 **Anti-spoof IP extraction**: uses the **rightmost** `X-Forwarded-For` entry (infra-set) rather than the client-controlled leftmost — correct for a proxied deployment (Cloudflare → GCE). Auth/webhook keyed by IP, everything else by userId.
- 🟢 Tier resolved via `TierEnforcementService.resolveUserTier` (org>user) with a 2-min Redis cache; canonical 429 envelope shared by both Redis + fallback paths (BUG-007 fix — matches `GlobalExceptionAdvice` shape so the frontend parser handles it uniformly); `HEAD` correctly classified as READ (was wrongly WRITE).
- 🟡 **Guardrail tightening is OFF at default LOW** (same theme as [security.md](security.md)): MEDIUM halves WRITE/DEPLOY/PREVIEW, HIGH quarters everything — but LOW (default) returns base limits. So the "halve abuse-prone groups under load" knob is a manual incident-response flip, not automatic. Base limits themselves are reasonable (FREE: 5 runs/min, 60 writes/min, 300 reads/min).
- 📝 Back-compat no-arg `RateLimitPolicy()` constructor (resolver=null → LOW) for tests; as a `@Component` it's DI'd correctly.

## Cross-refs
Guardrail multiplier driven by → [security.md](security.md) (`GuardrailLevelResolver`). Tier resolution shares logic with → `auth/TierEnforcementService` (next this tier) + [entitlements.md](entitlements.md) (`EntitlementResolver` — note both resolve "org>user tier" independently). Registered in the filter chain after JWT → `auth/security/SecurityConfig` (next).
