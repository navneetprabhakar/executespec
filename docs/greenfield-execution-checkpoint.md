# Greenfield Execution — Wave 2 Checkpoint #1

> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).
> Wave 2 — 3 of 11 R-IDs shipped (27%).

## Done in Wave 2 so far

| ID | Title | Verified |
|---|---|---|
| R-141 | `/v3/billing` — single-source plan card + usage + 3-tier grid | ✓ live (Free + Enterprise states verified via stub against R-142 contract) |
| R-150 | `/v3/pricing` — public hero + 3-card grid + FAQ | ✓ live (logged-out, USD/INR + Monthly/Annual toggles working) |
| R-163 | `<FreeCapToast>` + `<FreeCapBlockingModal>` | ✓ live (toast at balance≤1, blocking modal at 0; Composer submit gated correctly) |

## Files touched

* `frontend/src/v3/lib/pricing.ts` — single source of v3 pricing copy (PRO=$20, TEAM=$39/seat, ENTERPRISE=footer-only)
* `frontend/src/v3/components/PricingGrid.tsx` — shared by /v3/billing + /v3/pricing
* `frontend/src/v3/pages/V3Billing.tsx` — Plan card · Usage card · Pricing grid · Receipts
* `frontend/src/v3/pages/V3Pricing.tsx` — Public hero + grid + 6-question FAQ
* `frontend/src/v3/components/FreeCapNudge.tsx` — toast + modal + `useFreeCap` hook
* `frontend/src/v3/stores/useV3Ui.ts` — extended with cap state + dismiss flag + modal-open flag
* `frontend/src/v3/components/V3Shell.tsx` — mounts `<FreeCapToast>` + `<CapModalMount>`
* `frontend/src/v3/components/Composer.tsx` — gates Submit on `cap.balance <= 0` (paid tiers bypass)
* `frontend/src/v3/components/AvatarMenu.tsx` — Pricing → `/v3/pricing`, Billing → `/v3/billing`
* `frontend/src/v3/components/CmdK.tsx` — billing + pricing destinations live
* `frontend/src/v3/lib/events.ts` — `PRICING_CTA_CLICKED` event added
* `frontend/src/App.tsx` — mounts `/v3/billing` + `/v3/pricing`

## TypeScript / Maven

`./node_modules/.bin/tsc --noEmit` clean.
No backend changes in this batch — backend tests untouched.

## Live-test caveat (still in effect)

Running 8080 backend is pre-V152. R-141 + R-163 both consume `/api/v3/entitlements`; we verified the success paths via `addInitScript` fetch stubs (Free + Enterprise + balance=1 + balance=0 cases). When the next backend restart picks up `feature/conversion-redesign`, the same UIs will run against real data with no code change.

## Up next

R-125 (repo-attach popover — closes the v2-cohabitation gap) → R-144 (`/v3/integrations` grid) → R-143 (`/v3/team`) → checkpoint #2.

## Decisions still parked with defaults

| Tag | Default | Status |
|---|---|---|
| D-141a tier prices | Pro $20 · Team $39/seat · Enterprise sales | ✓ shipped in `lib/pricing.ts` |
| D-141b currency default | USD; INR auto-detect | ✓ shipped in `detectDefaultCurrency()` |
| D-163 nudge timing | Toast ≤1, modal at 0 | ✓ shipped in `FreeCapNudge` |
| D-125 repo-attach gaps | TODO comments for the 4 deferred design Qs | Pending implementation |
| D-151 SOC2 status | "Audit in progress with [Auditor TBD]" placeholder | Pending |
| D-144 integrations real-vs-soon | GitHub App + PAT + Slack + Bitbucket real; Jira/Confluence schema-only; ServiceNow/PagerDuty soon | Pending |

## Servers

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/...` | up |
| v2 backend | 8080 | proxied via Vite | up (pre-V152) |
