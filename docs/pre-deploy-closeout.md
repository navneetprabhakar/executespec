# ExecuteSpec — Pre-Deploy Close-Out

**Date:** 2026-04-28
**Branch:** `feature/v3-conversion`
**Tip:** `ee500fc6` (Wave 8 R-320 Trust Pack PDF)
**Verdict:** ✅ **READY FOR DEPLOY**

---

## TL;DR

Eight waves of UX work + one F1/F2 audit + one persona report + one pre-deploy sweep are all in. **Every actually-shippable open item is shipped.** The 13 remaining items are documented as parked with explicit re-open triggers — none of them block a deploy. Migrations are 2 additive Flyway versions (V152 + V153). No new env vars required from ops. Cohabitation invariant intact (v2 routes still live + opt-in-flagged into v3).

---

## What shipped this final wave (Wave 8)

| ID | Commit | What |
|---|---|---|
| **R-320** | `ee500fc6` | **Trust Pack PDF** — `/v3/trust` "Download Trust Pack" button now serves a real 296 KB PDF at `frontend/public/trust-pack.pdf` (was an `alert()` stub). Generator script at `scripts/trust-pack/render.mjs` for re-rendering after copy changes. |

Plus the audit doc:

| Commit | What |
|---|---|
| `b44d4189` | `docs/plans/pre-deploy-open-items.md` — comprehensive sweep across waves 1-7 + handoff + checkpoints. 1 shippable + 6 product-decision-parked + 4 external-asset-parked + 2 post-cutover + 1 withdrawn. |

---

## Full ledger of waves 4-8 on `feature/v3-conversion`

| Wave | Theme | Commits | Items |
|---|---|---|---|
| Wave 4 | Branch consolidation + ghost-route fixes + Trusted-by + competitor table + hero-video slot | `39288737`, `302c7104`, `96088ff5` | R-200 / R-201 / R-202 / R-203 / R-204 |
| Wave 5 | Website refresh — verbatim hero, retire stale jargon, sitemap/manifest, OG re-render | `3706a9cf`, `3626b882`, `ff8bec35` | G2-G14 |
| Wave 6 | Design-system primitives, 3-palette unification, brand-pop in app, hex sweep, light-mode unlock, OG re-render | `29f5220d`, `5bd4c587`, `8683ac71` | R-300 – R-309 |
| Wave 7 | F1 + F2 follow-ups: empty-state, access-denied, upgrade nudge, landing proof, value-anchor copy, anon team gating, admin AccessDenied, TCO calc, demo CTA, SOC 2 (withdrawn) | `5bffbd90`, `4f338a10`, `69b93e00`, `5af48c3c`, `cb98ed67` | R-310 – R-319 (R-319 withdrawn) |
| Wave 8 | Pre-deploy close-out — Trust Pack PDF | `b44d4189`, `ee500fc6` | R-320 |

**Total feature commits since the handoff baseline (`d8feb6b5`):** 16
**Aggregate diff vs `origin/main`:** ~76 frontend files + 36 backend files + 2 migrations.

---

## Deferred items at deploy-time

Categorized — every entry has a re-open trigger.

### parked-on-product-decision (6)

| Item | Re-open trigger |
|---|---|
| **R-141** GST tab on `/v3/billing` | Decide country source (Stripe / self-declare / Geo-IP) → 1-row migration on `Org` → flip `showGst` to `org.country === 'IN'`. |
| **D-125 #1** Private repo / GitHub App auto-detect | Product decision: PAT-fallback-only OR auto-switch to App-install copy inline. |
| **D-125 #2** Mono-repo subdir Advanced expander | A QA mono-repo + a `subdir` schema field decision. |
| **D-125 #3** Repo-too-big quota threshold | Product decision on threshold (file count? cloned size?) + a `size` field on the repo-list API. |
| **D-125 #4** Per-run repo override | Multi-repo telemetry shows it's worth the extra UI surface. |
| **Anonymous-run real LLM dispatch** | Ops greenlight on cost-cap design (hard $0.01/call cap + outline-only prompt + abuse classifier + ops spend dashboard). Flag stays OFF. |

### parked-on-external-asset (4)

| Item | Re-open trigger |
|---|---|
| **R-204** Hero video on `/v3/landing` | Drop `hero-demo.mp4` (≤ 8 MB H.264) + `hero-demo-poster.jpg` in `frontend/public/` → flip `USE_REAL_VIDEO` constant. |
| **R-313** Real customer logos on Landing TrustedByStrip | Signed permission + logo SVG/PNG + 5-min config edit. |
| **R-318** Cal.com URL for Enterprise demo CTA | A working Cal.com URL — 1-line href swap on `PricingGrid.tsx`. |
| **SSO config screen** on `/v3/team` | Live IdP test tenant (Okta / Auth0) + 30-min click-through QA. |

### parked-as-post-cutover-only (2)

| Item | Re-open trigger |
|---|---|
| **R-190** Vibe View deletion | v2 surface fully retired (no `/dashboard` v2 traffic). |
| **R-191** Sidebar / breadcrumb / Mission-vocabulary purge | Same cutover gate. |

### withdrawn (1)

| Item | Re-open trigger |
|---|---|
| **R-319** SOC 2 ETA copy on `/v3/trust` | A real audit engagement signed → re-issue with actual auditor name + completion month. |

---

## What a deployer needs to know

### Migrations (additive, no schema breakage)

| ID | Description | Source |
|---|---|---|
| **V152** | `app_user.lifetime_run_credit_balance` column for the referral-credit ledger (D-002 default: 5 lifetime + 5 per invite, cap 25). | `src/main/resources/db/migration/V152__lifetime_run_credit_balance.sql` |
| **V153** | `referral_invite` table — replaces the in-memory `ConcurrentHashMap` from Wave 2. | `src/main/resources/db/migration/V153__referral_invite.sql` |

Both are additive (new columns + new table only). Safe to apply on a live DB without downtime.

**Apply order:** Flyway handles ordering — `V152` before `V153`. No manual sequencing needed.

### Env vars (no new ones required from ops)

The waves landed entirely within existing env vars. **Nothing new required.**

For reference, these were already in use before this branch (deployer should already have them):

| Var | Purpose | Already wired |
|---|---|---|
| `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GOOGLE_API_KEY` | LLM providers | ✓ |
| `SMTP_PASSWORD` | Transactional mail (referral invites + auth) | ✓ |
| `RAZORPAY_*` / `STRIPE_*` | Payment gateways | ✓ |
| `JWT_SECRET` | Auth | ✓ |
| `REDIS_HOST` | Rate limiting + anon-run session store | ✓ |
| `OTEL_*` | Tracing (optional) | ✓ |

**Anonymous-run real LLM dispatch flag** (`v3.anonymous-trial.real-dispatch`) **stays OFF**. Do NOT flip without the cost-cap impl in place — see deferred-items §parked-on-product-decision.

### Manual deploy steps

None. Everything is build-time + Flyway-time.

```bash
# Backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -DskipTests package
# Frontend
npm --prefix frontend run build
```

Both produce the deployable artifacts in standard locations (`target/*.jar` + `frontend/dist/`). Flyway runs at app boot and applies V152 + V153 idempotently.

### Monitoring to watch in the first 24 h

| Signal | Why |
|---|---|
| `executespec.v2.run.outcome` per status | Confirms the pipeline still ships at the same rate as pre-deploy. |
| `/api/v3/entitlements` 200 vs 401 ratio | The SPA boot probe — 401 is normal for anon, but a sudden spike of authenticated 401s would mean JWT plumbing regressed. |
| `/api/projects/{id}` 404 rate | If R-310 didn't fully eliminate the empty-slug funnel, this would spike. Should be near zero. |
| `/api/runs?status=COMPLETE` p95 latency | The R-312 upgrade nudge fires this query; should be under 200 ms. |
| `/v3/public/exec-snapshot` p95 latency | The R-313 landing proof line fires this; cached, should be fast. |
| `frontend/public/trust-pack.pdf` 200 vs 404 | New asset — confirm CDN / static-server picked it up. |

### Rollback plan

This branch is purely additive on top of `origin/main`. If a deploy goes sideways:

1. Revert deploy artifacts to last-good (standard ops procedure).
2. The two migrations (V152, V153) are forward-only but harmless to leave applied — they only add columns / tables, never modify existing data.
3. Frontend is a static bundle — previous bundle redeploys cleanly.
4. No data backfill required either way.

### Cohabitation invariant

The v2 surface (`/dashboard`, `/projects`, `/billing`, etc.) is **still live** behind the `V2OptInGate` wrapper. Every v2 → v3 redirect triggers only when the user has flipped `localStorage.v3-opt-in`. This is the soft-rollout mechanism — no big-bang cutover. The `R-190` + `R-191` post-cutover cleanup happens later, after v2 traffic has fully decayed.

---

## Verdict

✅ **READY FOR DEPLOY.**

- Wave 8 closes the only actually-shippable open item.
- All 13 deferred items have explicit re-open triggers.
- Two additive migrations, no env-var asks, no new manual steps.
- Cohabitation invariant intact — v2 stays alive while v3 ramps.
- `tsc --noEmit` clean. Backend compiles clean.
- All commits on `origin/feature/v3-conversion`.

Last sanity check before the deploy command goes:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -DskipTests compile && \
  npm --prefix frontend run build
```

If both pass, the branch is shippable as-is.
