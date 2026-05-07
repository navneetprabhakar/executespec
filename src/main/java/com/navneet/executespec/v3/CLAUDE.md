# Package: `com.navneet.executespec.v3`

> **What this is — and what it isn't.**
>
> - ✅ **The third-generation customer product / UX surface.** Landing page logic, anonymous run dispatcher, referrals, per-user entitlements, lifetime credits — i.e. things a logged-out or low-tier consumer interacts with.
> - ❌ **NOT a pipeline architecture.** The active pipeline is V2 (`com.navneet.executespec.v2.*`). There is no V3 pipeline. References like `ReviewerAgentV3` live inside the V2 pipeline and are unrelated to this package.
>
> The "v3" name comes from the **UX overhaul v3 (Graphite+Signal)** shipped in v2.6.0 — the third generation of the consumer-facing surface. It runs in parallel to (not replacing) the V2 pipeline.

## Subpackages

| Subpackage | Responsibility |
|---|---|
| `landing/` | Landing-page stats, sample runs, exec snapshot card. `V3LandingController` serves the unauth marketing surface. |
| `anonrun/` | Anonymous-run flow for the consumer Vibe surface — session store, abuse heuristic, rate limiter, dispatcher (real + stub for tests). |
| `referral/` | Referral invite system — issue/redeem invites, hook into run completion to credit referrer. |
| `entitlements/` | Per-user entitlement resolution — what features the current user can access based on tier + product surface. |
| `credit/` | `LifetimeCreditService` — non-billing free-tier and promotional credits separate from monthly run quotas. |
| `api/` | Controllers exposing v3-only routes (referrals, entitlements). |

## What this package does NOT touch

- **Run execution** — that's the V2 pipeline (`v2/`)
- **Project shell / workspace** — that's `project/` (Pilot v3.0, different concept also called "v3")
- **Billing subscriptions** — `billing/` owns paid-tier flow; `v3/credit/` only handles non-billable promotional/lifetime credits
- **Authentication** — `auth/` handles login; this package consumes the resolved principal

## Cross-references

- Backend pipeline: see `v2/package-info.java`
- Frontend counterpart: `frontend/src/v3/`
- Naming convention rationale: top of `docs/ARCHITECTURE.md` and root `CLAUDE.md`
