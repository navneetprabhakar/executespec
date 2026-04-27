# Execution checkpoint (multi-wave)

> Refreshed every 3 items + at every wave-boundary transition.
> Replaces `docs/wave1-progress-handoff.md` (which now redirects here).
> Mirror at `/tmp/executespec-checkpoint.md` for fast in-session access.

## State at checkpoint (Backlog batch 2 — M-042 / M-049 / M-053 + M-054)

**Branch:** `feature/v2.7-foundation` (no fork; push enabled)
**Last pushed commit:** `4441ed5d` — `feat(notifications): rich failure body + cost/duration signals (M-053 + M-054)` (about to push)
**Tracking:** `origin/feature/v2.7-foundation`
**Worktree path:** `/Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf`
**Wave:** **Backlog IN PROGRESS** — 7 of ~16 items shipped

## Master list

`/tmp/executespec-master-actions.md` (66 items, IDs M-001..M-066). PDF: `/tmp/executespec-master-actions.pdf`.

## Wave 1 — completed (8 of 8)

| M-ID | Title | Commit |
|---|---|---|
| M-009 | `/legacy/billing` soft banner | cb47b1ce |
| M-026 | Sidebar expanded by default for first-time users | cb47b1ce |
| M-007 | `/plans` Annual/Monthly toggle | cb47b1ce |
| M-012 | Project export `@PreAuthorize` ORG_ADMIN+ | 919387d4 |
| M-006 | Glossary applied to 3 new sites (4 total inline wires) | 919387d4 |
| M-005 | GitHub App install card on IntegrationsPage | 919387d4 |
| M-002 | First-seat-purchase on-ramp (`/admin → /plans?tier=team&seats=N&from=admin`) | a443d398 |
| M-001 | Mission rename copy-only sweep (Header / RunsPage / RunList / NewRunModal / RunDetail / lib/utils / CommandPalette / ProjectDashboardPage) | 8efc35b0 |

Plus 2 housekeeping commits: `6699ddc2` (handoff note) + `ab487cfa` (checkpoint rename) + `3bc0ec5a` (post-Wave-1 checkpoint refresh).

ApiContractTest green after Wave 1 close.

## Wave 2 — completed so far (11 of ~16)

| M-ID | Title | Commit |
|---|---|---|
| M-003 | NotificationPrefController + minimal Settings matrix (Email/InApp × event-type) | 82774005 |
| M-004 | SSO config card on /org/settings (provider + metadata URL + domain + enforced) | 9565498d |
| M-008 | Inline Drawer wires (Add GitHub PAT + Add API keys) on NewRunModal | c5c07dee |
| M-010 | halt-all-active-runs SUPER_ADMIN tool (V2RunOrchestrator.haltAllActiveRuns + AdminPage button) | 36e83345 |
| M-013 | Unified governance audit + activity page (V22 + V133 at /audit + /governance/audit alias) | 4ddc39cf |
| M-014 | ADR controller + Project → Code → ADRs panel | 35080611 |
| M-015 | Invariant controller + Project → Code → Invariants panel | 4f31c314 |
| M-023 | TemplateGrid wired into EmptyProjectLanding + ExecutionSetup pre-fill plumbing | 61c925fa |
| M-024 | OrgStandardController CRUD + OrgStandardsManager UI on /org/settings | 703bc874 |
| M-045 | TEAM_DEFAULT_SEATS 3 → 2 in PlanCard (parked decision #9) | f871169f |
| M-028 | TEAM seats CTA from /admin — **SHIPPED-DUPLICATE** under M-002 (a443d398). Marked closed. | (a443d398) |
| M-034 | Deep-linkable `/projects/:id/activity` URL → opens dashboard with `?drawer=activity` | a190a586 |
| M-043 | VerdictPill on RunCard rows for terminal runs (statusReason-based) | 723425b8 |
| M-044 | docs/payment-history.md — unified Razorpay+Stripe operator + customer reference | f2fd1a3f |
| M-046 | /exec KPI tile drill-downs (clickable buttons → filtered Missions list / projects / scroll-to sections) | 235e77fa |

**M-027 (BEST VALUE chip move) — annotated only (commit 7acbb6ef);
needs product decision.** Two defensible placements: keep on MAX
($29 BYOK lowest-cost-of-entry) vs move to TEAM ($49/seat
managed-key shared pool — higher ARPU upsell). Master list flagged
this as ambiguous; requires user direction. Inline TODO added.

M-003 / M-004 / M-010 / M-014 / M-015 / M-024 all need a backend
restart to surface live data on `:8081` (which still runs the
pre-batch jar). Frontend shells render gracefully until the next deploy.

ApiContractTest green throughout.

## Wave 2 — remaining (1 blocked, 0 active)

- **M-027** — BLOCKED-PRODUCT-DECISION (BEST VALUE chip placement). Annotation only at 7acbb6ef. Awaits user direction: keep on MAX ($29 BYOK lowest cost-of-entry) vs move to TEAM ($49/seat managed shared pool, higher ARPU).

**Wave 2 closed at 15/16. M-027 will be revisited once direction lands.**

## Wave 3 — completed so far (3 of ~12)

| M-ID | Title | Commit |
|---|---|---|
| M-018 | docs/status-page.md — Better Stack lock-in + 7-component setup runbook + canary-runner spec + Prometheus integration | 6032b45d |
| M-019 | activity_audit hash chain (V147) — prev_hash/row_hash/chain_seq + verifier + GET /api/admin/audit-chain/verify | 78af28da |
| M-022 | /team/:userId per-member dashboard + drill-down link from OrgAdminDashboard members table | 2d0ecf08 |
| M-016 | SsoConfigController lockout-prevention guard + docs/sso-saml-wiring.md spec | e81b403c |
| M-017 | DsarController + GET /api/dsar/me + GET /api/dsar/users/{userId} JSON export | 7da764db |
| M-020 | docs/dpa-flow.md — operator runbook for DPA request → signature → activation | f9298039 |
| M-032 + M-033 + M-039 | GovernanceGuard + V148 schema (plan-approval policy + per-project spend cap + repo allowlist) | dab08f7e |
| M-011 | Razorpay seat-adjust 501 with manual-flow pointer + PaymentGateway.updateSubscriptionQuantity contract | 6cc38dac |
| M-029 + M-031 | docs/stripe-price-ids-ops.md + docs/cookie-domain-switch.md (ops-side runbooks) | d34a8028 |
| M-030 | OrgService.changeOrgTier stamps org.product_id via TierProductSurfaceMapper on admin tier changes (BillingService Stripe path already did this for webhook-driven flips) | ac6ba11c |
| M-051 | V149 TEAM-BYOK plan ($39/seat / ₹3,199/seat) per parked decision #2 | 444d8f46 |

**WAVE 3 CLOSED — 14/14 items shipped.**

## Backlog — completed so far (3 of ~16)

| M-ID | Title | Commit |
|---|---|---|
| M-047 | LoginPage routes OAuth via /api/auth/oauth2/init?surface=… for Phase-2 surface stash | 0bc2a31f |
| M-021 | VerdictPill backfill on InlineRunDetail header — closes the Plan-1 VerdictPill rollout | e6891cae |
| M-038 | activity.retention.days flag-tunable + V150 seed — SUPER_ADMIN can adjust without deploy | 26aad86c |
| M-042 | "Verify audit chain" SUPER_ADMIN button on AdminPage — surfaces V147/M-019 verifier | a706569e |
| M-049 | ActivationFunnelController + AdminPage Reports tab panel (period dropdown + stacked-bar) | e80e3a9d |
| M-053 + M-054 | RunCompletionNotifier failure body now leads with cost/duration signals (mirrors completion path) | 4441ed5d |

## Backlog (~16 items)

M-021 (VerdictPill backfill), M-035 (multi-org), M-036 (custom roles), M-037 (SCIM), M-038 (audit retention UI), M-040 (per-team RBAC), M-041 (security questionnaire), M-042 (audit diff), M-047 (OAuth init wire), M-048 (conversational default), M-049 (activation funnel reads), M-050 (multi-tenant seed), M-052 (ENT floor — anchor "Contact sales" per parked #3), M-053-054 (notif body), M-055-058 (housekeeping), M-064-066 (nits)

## Final pass (run in this exact order at the very end)

1. **M-067 — Doc-tree refresh from current code state.** Recreate root .md + every file under `docs/`. Reassess every `docs/plans/` file and either mark SHIPPED with live-code refs, mark SUPERSEDED-BY-XXX with a pointer, or rewrite as a still-pending plan matching post-execution reality. One commit per logical doc cluster. Effort: L. Source: user dispatch 2026-04-27. Reason it's last: docs can only be accurate after every other M-item lands.
2. **M-068 — Final acceptance UX pass — all personas — consolidated PDF report.** AFTER M-067: cleanup any stale :8081/:5174 stack, restart both servers from `feature/v2.7-foundation`, then end-to-end test ALL personas (VIBE: Sara/Raj/Priya/Marcus/Theo · TEAM/ENT: Eric/Erin/Carmen/Dharma/Priya-2 + SUPER_ADMIN). Authenticate via the real login endpoint (admin@executespec.ai/Admin@1234 + provisioned non-admin test users). Tag findings `[browser]`/`[live-api]`/`[code-trace]` per the v2 fidelity standard. Screenshot every meaningful screen per (persona × mode × feature). Deliverables: `/tmp/executespec-final-acceptance-report.md` (severity-tagged punch-list, per-persona verdicts table, per-feature verdicts, "what changed since v2 assessment" appendix) + matching mobile PDF. Email to navneet.prabhakar007@gmail.com via Gmail SMTP. Effort: XL. Source: user dispatch 2026-04-27. Reason it's last-of-the-last: validates the entire shipped surface against every persona using the *post-execution* code + the *post-refresh* docs as ground truth.

## Authorized scope

**Wave 1 → Wave 2 → Wave 3 → Backlog, in order. Do not pause for wave-boundary confirmation.** Continuous execution through all waves until either everything in the master list is shipped or you hit a hard blocker that needs user input.

## Execution protocol

- **Dispatch cadence:** every 3 items completed, PLUS at each wave-boundary transition
- **Stop only for:**
  - (a) non-trivial product decision the user must make (don't guess on pricing, copy, anything user-visible the user hasn't approved a default for)
  - (b) item turns out already done — log and skip, update master list entry
  - (c) red tests + fix not obvious in 5 min
  - (d) hard blocker (creds, infra, missing service, etc.)
- **Branch:** `feature/v2.7-foundation` only — no forks even if commits sprawl past 15
- **Push enabled** — push every commit (or in logical batches); upstream already set
- **No PR opens.** Local + push only.
- **Confirm in every dispatch:** which wave + how many items remain in it
- **Email recipient for any reports:** `navneet.prabhakar007@gmail.com` via Gmail SMTP from `application-dev.yml`
- **Checkpoint discipline (NEW post-/compact):** refresh BOTH checkpoint files in a single `chore: checkpoint after M-XXX/M-YYY/M-ZZZ` commit every 3 items + at every wave-boundary. Push immediately so the checkpoint survives auto-compact.

## Parked decisions table

| # | Decision | Default I'm using | Why this default | Where it'd block | Cost to change later |
|---|---|---|---|---|---|
| 1 | Status-page provider | Better Stack ($29/mo) | Cheaper, modern UI per `docs/status-page.md` | Wave 3 / M-018 | Low — provider swap is config + 7-component re-config (~1 day) |
| 2 | TEAM-BYOK at $39/seat alongside managed-key TEAM at $49 | Yes — ship in Wave 3 | Closes margin risk on heavy users; matches Cursor/Cody BYOK options | Wave 3 / M-051 | Low — 1 row in `billing_plan` + Stripe price ID |
| 3 | ENTERPRISE floor at $24k/yr publicly listed | No — stay "Contact sales" | Until 5 paying ENT customers exist, anchor varies per deal | Backlog / M-052 | Low — copy + a billing_plan row |
| 4 | Audit log: V22 + V133 collapse vs keep both | Keep both, route into one UI page (`/governance/audit`) | V22 has 200+ lifetime audit events on dev; collapsing now = data migration risk | Wave 2 / M-013 | Medium — backfill SQL + re-test |
| 5 | Mission rename scope (M-001) | **Copy-only** — URL paths + DTO keys keep "Run" | **APPLIED in Wave 1 / M-001 (commit 8efc35b0)** | — | High — schema rename is breaking + costly later |
| 6 | SAML library | Spring Security SAML2 (stock) | Maintained, MFA-friendly, integrates with existing stack | Wave 3 / M-016 | Low pre-customer; expensive after first ENT customer |
| 7 | Notification prefs UX shape (M-003) | Minimal toggle matrix (Email/InApp × event-type) | Simpler to ship + iterate | Wave 2 / M-003 | Low — UI redesign without schema change |
| 8 | Drawer first-sites (M-008) | NewRunModal "Add API key" + "Add GitHub PAT" | Most-trafficked bounces per assessment P-1 | Wave 2 / M-008 | Trivial — sites are independent |
| 9 | TEAM seat picker default 3 vs min 2 (M-045 nit) | Change to 2 | Matches `included_seats=2` | Wave 2 / M-045 | Trivial — 1-line PlanCard change |
| 10 | PROJECT-default routing for paying users | Conservative — keep `/` for everyone | Avoids surprise navigation | Wave 2-3 | Low — single redirect rule |

**No new decisions adopted in Wave 1.**

## Guardrails

- **Branch:** `feature/v2.7-foundation` (no fork)
- **Push:** enabled — `--set-upstream` already configured to `origin/feature/v2.7-foundation`
- **No PR opens.** Local + push only.
- **Recipient for emails:** `navneet.prabhakar007@gmail.com`
- **SMTP:** Gmail, `smtp.gmail.com:587`, user `admin@executespec.ai`, password from `src/main/resources/application-dev.yml` (`smdpsanatyrtlcxa`)
- **Don't burn LLM credits on agent harness runs** — assessments are about UX, not pipeline runs
- **Standing memory rules:**
  - `feedback_no_push_without_approval.md` — relaxed for this branch by user
  - `feedback_branching_strategy.md` — feature/* → dev → main (apply when merging)
  - `feedback_check_migrations.md` — `ls V*.sql` before new Flyway file
  - `feedback_flyway_never_modify_applied.md` — no edits to applied migrations
  - `feedback_versioning.md` — confirm major/minor/patch with user before bump
  - `feedback_test_locally_first.md` — run `mvn test` + `npm build` before push
- **JAVA_HOME** must be set: `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for every mvn command

## Stack URLs + ports

| Service | URL | Notes |
|---|---|---|
| Backend (mine) | http://localhost:8081 | Spring Boot, `local` profile, jar from this branch |
| Frontend (mine) | http://localhost:5174 | Vite dev, env `VITE_BACKEND_URL=http://localhost:8081` |
| Backend (other worktree) | http://localhost:8080 | Running from `funny-proskuriakova-6508dd` (`main` branch) — leave alone, user-protected |
| Frontend (other worktree) | http://localhost:5173 | Same — leave alone |
| Preview MCP server | id `30207673-bdd1-4102-a6b5-2e5d63caaa77` (`vite-cranky`) | Use for browser verification of UI changes |

## Auth

- **JWT:** `POST http://localhost:8081/api/auth/login` with `{"email":"admin@executespec.ai","password":"Admin@1234"}` returns `{accessToken, user}`. Role `SUPER_ADMIN`, tier `ENTERPRISE`, org `Executespec` (`511aa951-73e4-4202-b566-25ae9da56a40`).
- For browser verification: inject token via `localStorage.setItem('access_token', '<JWT>')` then navigate. The `useAuthStore` reads localStorage at module init; navigate AFTER set.
- For headless screenshots: use `addInitScript` (per Playwright `tour.mjs`) so token is set before SPA boots.

## Tooling

- **Headless browser:** Playwright 1.59 + Chromium 1217. Install dir `~/Library/Caches/ms-playwright/`. Local install at `/tmp/es-report-cranky/node_modules/playwright`.
- **PDF builder:** `python3 -m markdown` + Playwright `page.pdf({width:'4.5in',...})`. Template scripts at `/tmp/es-report-cranky/build_pdf*.py` and per-doc `send_*.py`.
- **Mobile PDF spec:** 4.5″ width, 14 px body, severity tags as inline pills, tables collapsed to stacked rows, screenshots as base64 data URIs (self-contained).

## Resume instructions for fresh session

1. **Read this file first.**
2. `cd /Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf`
3. Verify state: `git log --oneline -5` → top should match the "Last pushed commit" above
4. Verify stack: `lsof -ti:8081` and `lsof -ti:5174` should both return PIDs (mine — leave the 8080/5173 ones alone)
5. Set up todos with all remaining waves' items per the "Wave order" section
6. **Continue from the next pending Wave-N item** at the top of the wave-N list above
7. Continuous execution through Wave N → Wave N+1 → Backlog
8. Stop only on the 4 conditions in "Stop only for"; otherwise keep shipping
9. Dispatch every 3 items + at every wave-boundary; refresh checkpoint + push after each batch
10. Final wrap-up message when all items done OR when blocked: include shipped count, wave-by-wave status, what's blocked + why

## Commit-message protocol

- `feat:` for shipped behavior, `fix:` for bug fixes, `chore:` for infra/config (incl. checkpoints), `test:` for tests, `docs:` for docs
- Reference M-IDs in subject line for action-list commits
- 1-2 sentence body explaining the why + what changed
- Always include `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`
- Use HEREDOC for multi-line messages
