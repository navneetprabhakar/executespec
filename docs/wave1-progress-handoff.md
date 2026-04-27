# Wave 1 progress — handoff

## State at compact

**Branch:** `feature/v2.7-foundation` (no fork; user enabled push)
**Last pushed commit:** `919387d4` — `feat: Wave 1 batch 2 — export gating + glossary sweep + GitHub App card (M-012 / M-006 / M-005)`
**Tracking:** `origin/feature/v2.7-foundation`
**Worktree path:** `/Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf`

## Master list

`/tmp/executespec-master-actions.md` (66 items, IDs M-001..M-066). PDF: `/tmp/executespec-master-actions.pdf`.

## Wave 1 — completed (6 of 8)

| M-ID | Title | Commit |
|---|---|---|
| M-009 | `/legacy/billing` soft banner | cb47b1ce |
| M-026 | Sidebar expanded by default for first-time users | cb47b1ce |
| M-007 | `/plans` Annual/Monthly toggle | cb47b1ce |
| M-012 | Project export `@PreAuthorize` ORG_ADMIN+ | 919387d4 |
| M-006 | Glossary applied to 3 new sites (ResetWorkspaceSheet, RunDetailTabs Classifier, ValidationReportPanel Reviewer) — total ≥4 inline wires | 919387d4 |
| M-005 | GitHub App install card on IntegrationsPage (Install / Uninstall / Refresh status; ORG_ADMIN-gated) | 919387d4 |

Both batches browser-verified (Playwright/Chromium @ 1440×900) before commit.

## Wave 1 — remaining (2 of 8)

In execution order:

1. **M-002** — First-seat-purchase on-ramp. `OrgAdminDashboard` "+seat" button currently 402s when org has no active TEAM sub. Add path: when `org.tier != 'TEAM'`, redirect to `/plans?tier=team&seats=N&from=admin` with TEAM card pre-selected at the right seat count. Effort: S.
2. **M-001** — Mission rename completion. **Copy-only per the parked-decision default** (URL paths + DTO keys keep "Run"). Touch points: `Header.tsx` breadcrumb resolver, `RunsPage` h1, `RunCard` "Run cancelled" status text, `RunDetail` right-pane "Select a run", "+ New run" button, `NewRunModal` title. Effort: M.

After both: final commit + push + `mvn test` + `npm run build` + dispatch.

## Authorized scope (UPDATED)

**Wave 1 → Wave 2 → Wave 3 → Backlog, in order. Do not pause for wave-boundary confirmation.** Continuous execution through all waves until either everything in the master list is shipped or you hit a hard blocker that needs user input.

## Execution protocol

- **Dispatch cadence:** every 3 items completed, PLUS at each wave-boundary transition (announce "Wave 1 done, Wave 2 starting" etc.)
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

## Wave order (from master list)

- **Wave 1 — 8 items, ~3-5 days:** M-009, M-026, M-007, M-012, M-006, M-005 (all done) + M-002, M-001 (next)
- **Wave 2 — ~14 items, ~10 days:** M-003 (Notif prefs controller+UI), M-004 (SSO config UI), M-008 (Drawer wires), M-010 (halt-runs), M-013 (unified audit page), M-014 (ADR controller+page), M-015 (Invariant controller+page), M-023 (TemplateGrid wire), M-024 (Standards CRUD), M-027 (BEST VALUE move), M-028 (TEAM seats CTA), M-034 (activity feed page), M-043 (RunCard verdict pill), M-044 (payment-history doc), M-045 (seat default 2), M-046 (/exec drill-down)
- **Wave 3 — ~12 items, ~6 weeks:** M-011 (Razorpay seats), M-016 (SAML enforcement), M-017 (DSAR), M-018 (status page), M-019 (signed audit chain), M-020 (DPA flow), M-022 (per-team dashboard), M-029 (Stripe price IDs ops-only — flag), M-030 (ProductSurfaceFilter wiring), M-031 (cookie domain — needs maintenance window — flag), M-032 (plan-approval policy), M-033 (per-project spend cap), M-039 (repo allowlist), M-051 (TEAM-BYOK plan)
- **Backlog:** M-021 (VerdictPill backfill), M-035 (multi-org), M-036 (custom roles), M-037 (SCIM), M-038 (audit retention UI), M-040 (per-team RBAC), M-041 (security questionnaire), M-042 (audit diff), M-047 (OAuth init wire), M-048 (conversational default), M-049 (activation funnel reads), M-050 (multi-tenant seed), M-052 (ENT floor), M-053-054 (notif body), M-055-058 (housekeeping), M-064-066 (nits)

## Wave 1 was

- Dispatch update every 3 items completed (dispatch #1 after M-009/M-026/M-007; dispatch #2 after M-012/M-006/M-005; final dispatch after M-002 + M-001)

## Parked decisions table (full — the truncated version was re-sent earlier)

| # | Decision | Default I'm using | Why this default | Where it'd block | Cost to change later |
|---|---|---|---|---|---|
| 1 | Status-page provider | Better Stack ($29/mo) | Cheaper, modern UI per `docs/status-page.md` | Wave 3 / DB-5 | Low — provider swap is config + 7-component re-config (~1 day) |
| 2 | TEAM-BYOK at $39/seat alongside managed-key TEAM at $49 | Yes — ship in Wave 3 | Closes margin risk on heavy users; matches Cursor/Cody BYOK options | Wave 3 / pricing #1 | Low — 1 row in `billing_plan` + Stripe price ID |
| 3 | ENTERPRISE floor at $24k/yr publicly listed | No — stay "Contact sales" | Until 5 paying ENT customers exist, anchor varies per deal | Wave 3 / pricing #2 | Low — copy + a billing_plan row |
| 4 | Audit log: V22 + V133 collapse vs keep both | Keep both, route into one UI page (`/governance/audit`) | V22 has 200+ lifetime audit events on dev; collapsing now = data migration risk | Wave 2 / M-013 | Medium — backfill SQL + re-test |
| 5 | Mission rename scope (M-001) | **Copy-only** — URL paths + DTO keys keep "Run" | Per consolidated plan §1.1 #16; breaking DTO change requires API client coordination | **Wave 1 — M-001 next batch** | High — schema rename is breaking + costly later |
| 6 | SAML library | Spring Security SAML2 (stock) | Maintained, MFA-friendly, integrates with existing stack | Wave 3 / M-016 | Low pre-customer; expensive after first ENT customer |
| 7 | Notification prefs UX shape (M-003) | Minimal toggle matrix (Email/InApp × event-type) | Simpler to ship + iterate | Wave 2 / M-003 | Low — UI redesign without schema change |
| 8 | Drawer first-sites (M-008 backlog) | NewRunModal "Add API key" + "Add GitHub PAT" | Most-trafficked bounces per assessment P-1 | Wave 2 / M-008 | Trivial — sites are independent |
| 9 | TEAM seat picker default 3 vs min 2 (M-045 nit) | Change to 2 | Matches `included_seats=2` | Wave 2 nit | Trivial — 1-line PlanCard change |
| 10 | PROJECT-default routing for paying users | Conservative — keep `/` for everyone | Avoids surprise navigation | Wave 2-3 | Low — single redirect rule |

**Wave 1 only blocks on #5** — assuming copy-only for M-001 unless overridden.

## Guardrails

- **Branch:** `feature/v2.7-foundation` (no fork)
- **Push:** enabled — `--set-upstream` already configured to `origin/feature/v2.7-foundation`
- **No PR opens.** Local + push only.
- **Recipient for emails:** `navneet.prabhakar007@gmail.com`
- **SMTP:** Gmail, `smtp.gmail.com:587`, user `admin@executespec.ai`, password from `src/main/resources/application-dev.yml` (`smdpsanatyrtlcxa`)
- **Don't burn LLM credits on agent harness runs** — assessments are about UX, not pipeline runs
- **Standing memory rules:**
  - `feedback_no_push_without_approval.md` — was relaxed for this branch by user
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

## Git status at compact

```
git log --oneline origin/dev..HEAD | wc -l   # 28 commits ahead of origin/dev
git status -sb                                # clean (last commit was 919387d4)
git log -1 --format='%h %s'                   # 919387d4 feat: Wave 1 batch 2 …
```

## Commit-message protocol

- `feat:` for shipped behavior, `fix:` for bug fixes, `chore:` for infra/config, `test:` for tests, `docs:` for docs
- Reference M-IDs in subject line for Wave-1 commits
- 1-2 sentence body explaining the why + what changed
- Always include `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`
- Use HEREDOC for multi-line messages

## Recently emailed deliverables (for context)

1. `/tmp/executespec-assessment-v2.md` + PDF — VIBE/PRO UX assessment (sent earlier)
2. `/tmp/executespec-assessment-team-enterprise.md` + PDF — TEAM/ENT UX assessment (sent earlier)
3. `/tmp/executespec-team-enterprise-business-readiness.md` + PDF — sent earlier
4. `/tmp/executespec-master-actions.md` + PDF — sent at start of Wave 1

## Resume instructions for fresh session

1. **Read this file first.**
2. `cd /Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf`
3. Verify state: `git log --oneline -3` → should show `919387d4` at top
4. Verify stack: `lsof -ti:8081` and `lsof -ti:5174` should both return PIDs (mine — leave the 8080/5173 ones alone)
5. Set up todos with all remaining waves' items per the "Wave order" section above
6. **Start with M-002** (First-seat-purchase on-ramp), then M-001 (Mission rename copy-only), then final-of-Wave-1 dispatch
7. **Roll into Wave 2** without pausing (per authorized scope) — start with M-003 NotifPref controller + UI
8. Continuous execution through Wave 2 → Wave 3 → Backlog
9. Stop only on the 4 conditions in "Stop only for"; otherwise keep shipping
10. Dispatch every 3 items + at every wave-boundary; push after each batch
11. Final wrap-up message when all items done OR when blocked: include shipped count, wave-by-wave status, what's blocked + why
