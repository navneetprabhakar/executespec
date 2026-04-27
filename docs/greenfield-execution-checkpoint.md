# Greenfield Execution — Wave 2 Checkpoint #2

> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).
> Wave 2 — 6 of 11 R-IDs shipped (55%).

## Done in Wave 2 so far

| ID | Title | Verified |
|---|---|---|
| R-141 | `/v3/billing` — single-source plan card + usage + 3-tier grid | ✓ Free + Enterprise live |
| R-150 | `/v3/pricing` — public hero + 3-card grid + FAQ | ✓ logged-out live |
| R-163 | Free-cap toast + blocking modal | ✓ balance≤1 + balance=0 live |
| R-125 | Repo-attach popover (OAuth + repo-picker + auto-name) | ✓ list + no-PAT phases live; closes the v2 cohabitation gap |
| R-144 | `/v3/integrations` grid | ✓ 8 cards across 4 groups (Source · Notifications · Ticketing · IR) |
| R-143 | `/v3/team` (members + seats + invite + SSO) | ✓ Free / Team / Enterprise variants live; SSO panel renders only on ENTERPRISE |

## TypeScript / Maven

`./node_modules/.bin/tsc --noEmit` clean.
No backend changes in Wave 2 yet — backend tests untouched (23/23 still green from Wave 1).

## Cohabitation invariant intact

Wave 2 has NOT touched any v2 files non-additively. Every new component lives under `frontend/src/v3/`; the only `lib/api.ts` consumption uses existing v2 helpers. v2 routes / chrome / billing surface still work at their existing paths.

## Up next (closes Wave 2)

R-162 (referral mechanism — backend wire-up to V152's `referred_by_user_id` + frontend completes the loop opened by R-163's invite form) → R-151 (`/v3/trust` Amara unlock) → R-152 (`/v3/insights` Devon unlock) → R-153 (HelpModal) → R-170 (mobile sweep).

## Decisions still parked with defaults

| Tag | Default | Status |
|---|---|---|
| D-141a tier prices | Pro $20 · Team $39/seat · Enterprise sales | ✓ shipped |
| D-141b currency default | USD; INR auto-detect | ✓ shipped |
| D-163 nudge timing | Toast ≤1, modal at 0 | ✓ shipped |
| D-125 repo-attach gaps | TODO comments for the 4 deferred design Qs | ✓ shipped (TODOs in popover) |
| D-144 integrations real-vs-soon | Real: GitHub App + PAT + Slack + Bitbucket; schema-only: Jira + Confluence; soon: ServiceNow + PagerDuty | ✓ shipped |
| D-151 SOC2 status | "Audit in progress with [Auditor TBD]" placeholder | Pending — lands with R-151 |

## Servers

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/...` | up |
| v2 backend | 8080 | proxied via Vite | up (pre-V152) |
