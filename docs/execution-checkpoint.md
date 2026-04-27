# Execution checkpoint (multi-wave)

> Refreshed every 3 items + at every wave-boundary transition.
> Replaces `docs/wave1-progress-handoff.md` (which now redirects here).
> Mirror at `/tmp/executespec-checkpoint.md` for fast in-session access.

## State at checkpoint (Wave 1 → Wave 2 boundary)

**Branch:** `feature/v2.7-foundation` (no fork; push enabled)
**Last pushed commit:** `8efc35b0` — `feat(ux): mission rename copy-only sweep (M-001)`
**Tracking:** `origin/feature/v2.7-foundation`
**Worktree path:** `/Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf`
**Wave:** Wave 1 CLOSED · Wave 2 STARTING

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

Plus 2 housekeeping commits: `6699ddc2` (handoff note) + `ab487cfa` (checkpoint rename).

ApiContractTest green after Wave 1 close.

## Wave 2 — remaining (~16 items, ~10 days)

In execution order:

1. **M-003** — NotificationPrefController + minimal Settings page. V139 schema + `NotificationPrefService` already exist; need `@RestController` + Settings UI matrix (Email/InApp × event-type). Default UX: minimal toggle matrix per parked decision #7.
2. **M-004** — SSO config UI. V127 schema + `SsoConfigController` exist; need a Settings → SSO panel for ORG_ADMIN to set sso_provider/metadata_url/domain/enforced.
3. **M-008** — Drawer wires (NewRunModal "Add API key" + "Add GitHub PAT") per parked decision #8. Drawer primitive ships; needs the two embedded sites.
4. **M-010** — halt-runs admin tool. SUPER_ADMIN endpoint to mark all non-terminal runs CANCELLED on demand (incident response).
5. **M-013** — Unified `/governance/audit` page collapsing V22 (activity_audit) + V133 (activity_feed) into one filtered table per parked decision #4.
6. **M-014** — ADR controller + page. `Adr` entity + `AdrFrontmatterParser` exist; need REST + Settings → ADRs panel.
7. **M-015** — Invariant controller + page. `ArchInvariant` + `InvariantChecker` exist; need REST + Project → Invariants tab.
8. **M-023** — TemplateGrid wire (where? sample-spec gallery on RunsNewLanding likely).
9. **M-024** — Standards CRUD UI. `StandardsOverrideController` exists; org-level standards CRUD missing.
10. **M-027** — BEST VALUE chip move on PlansPage (currently on MAX/Team — confirm which tier should wear it post-V144 ladder).
11. **M-028** — TEAM seats CTA from /admin (potentially redundant with M-002 — log+skip if so).
12. **M-034** — Project activity feed page. `GET /api/projects/{id}/activity` exists (V133); need a Project → Activity full feed.
13. **M-043** — RunCard verdict pill (apply VerdictPill to RunCard rows in RunList).
14. **M-044** — Payment-history doc (markdown only — explain Razorpay vs Stripe payment-history sources).
15. **M-045** — Seat default 2 in PlanCard (TEAM_DEFAULT_SEATS 3 → 2 per parked decision #9).
16. **M-046** — `/exec` drill-down (clickable KPI cards → filtered project list).

## Wave 3 — remaining (~12 items, ~6 weeks)

M-011 (Razorpay seats), M-016 (SAML enforcement), M-017 (DSAR), M-018 (status page — Better Stack per parked #1), M-019 (signed audit chain), M-020 (DPA flow), M-022 (per-team dashboard), M-029 (Stripe price IDs ops-only — flag), M-030 (ProductSurfaceFilter wiring), M-031 (cookie domain — needs maintenance window — flag), M-032 (plan-approval policy), M-033 (per-project spend cap), M-039 (repo allowlist), M-051 (TEAM-BYOK plan per parked #2)

## Backlog (~16 items)

M-021 (VerdictPill backfill), M-035 (multi-org), M-036 (custom roles), M-037 (SCIM), M-038 (audit retention UI), M-040 (per-team RBAC), M-041 (security questionnaire), M-042 (audit diff), M-047 (OAuth init wire), M-048 (conversational default), M-049 (activation funnel reads), M-050 (multi-tenant seed), M-052 (ENT floor — anchor "Contact sales" per parked #3), M-053-054 (notif body), M-055-058 (housekeeping), M-064-066 (nits)

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

- **JWT:** `POST http://localhost:8081/api/auth/login` with `{"email":"admin@executespec.ai","password":"REDACTED_FOR_PUBLIC_RELEASE4"}` returns `{accessToken, user}`. Role `SUPER_ADMIN`, tier `ENTERPRISE`, org `Executespec` (`511aa951-73e4-4202-b566-25ae9da56a40`).
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
