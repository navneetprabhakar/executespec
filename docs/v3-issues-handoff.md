# v3-issues handoff for fresh thread (4-concern batch · 2026-04-28)

## TL;DR

You're inheriting a 4-concern fix batch already coded + tsc-clean +
locally committed but **not pushed**. Three local commits ahead of
`origin/feature/v3-conversion`. Push them, FF dev, watch the deploy,
verify.

## Branch + worktree

| Field | Value |
|---|---|
| Working branch | **`feature/v3-conversion`** |
| Worktree path | **`/Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf/`** |
| `origin/feature/v3-conversion` tip | `27e468c80d2b49faf1dc0d00aaa5fd8a9ce96b67` |
| Local tip (3 commits ahead) | **`c260d3fc`** |
| `origin/dev` tip | `27e468c80d2b49faf1dc0d00aaa5fd8a9ce96b67` (same as origin's feature tip) |
| `origin/main` tip | `da26b175405a12bbf059f4735271f3d7af069707` (49 commits behind dev — see "Other open asks") |

## Local commits ahead of origin (push these)

```
c260d3fc  wip(v3): partial — handoff to fresh thread (concerns 2 + 3 + 4)
12d59e18  fix(v3): root route → /v3/landing for anon (concern 1 — funnel fix)
6903173c  chore(scripts): redirect slice-email.py recipient to admin@executespec.ai
```

All three have **passed `tsc --noEmit`** at commit time. Frontend
build + backend `mvn compile` were **not** run by the previous thread
because of the handoff. Run both before pushing.

There are **no uncommitted edits** in the worktree (only `src/main/resources/static/trust-pack.pdf` is untracked — that's a Vite build artifact + already in `.gitignore` semantics; ignore).

## The 4 in-flight concerns

### Concern 1 (CRITICAL) — Root route → `/v3/landing` for anon · **DONE**

**Verbatim:** When an anonymous visitor hits `https://dev.executespec.ai/`, it sends them to `/login`. It should land them on `/v3/landing` — that's the entire conversion-optimized public landing built across Waves 4-8. Sending anon → login defeats the funnel.

**Status:** **Committed locally as `12d59e18`. Not deployed.**

What it does: in `frontend/src/components/auth/ProtectedRoute.tsx`, the anon-fallback now special-cases `/`: anon visitor at `/` → `/v3/landing` (public marketing surface). Every other protected path keeps the prior `/login` redirect + `from`-state so the post-signin round-trip still works. `/v3/landing` is verified public (no auth wrapper, no auth gate in the component).

**Verification gate (run after deploy):**
- `curl -sL https://dev.executespec.ai/` should land on `/v3/landing` HTML (or follow the SPA redirect)
- Anon Playwright visit to `/` → page contains `"AI-written pull requests"` and does NOT contain a login form

### Concern 2 — Remove "What's new" surface · **DONE**

**Verbatim:** R-181 TourBanner mounted in V3Shell. Unmount (delete the file too if the import sweep is clean — your call). Comment in the commit message: "removed per user direction; revisit later."

**Status:** **Committed locally as part of `c260d3fc`. Not deployed.**

What it does: `V3Shell.tsx` no longer mounts `<TourBanner />`. The component file `frontend/src/v3/components/TourBanner.tsx` was deleted entirely (only consumer was V3Shell — clean import sweep). A revival comment in V3Shell documents how to revive (re-import + restore mount + restore both files + restore App.tsx route).

### Concern 3 — Remove `/v3/changelog` page · **DONE**

**Verbatim:** Delete the route from App.tsx + delete `frontend/src/v3/pages/V3Changelog.tsx` + remove every reference (nav, Cmd-K, footer, telemetry events).

**Status:** **Committed locally as part of `c260d3fc`. Not deployed.**

What it does:
- `frontend/src/v3/pages/V3Changelog.tsx` deleted
- `App.tsx` import + `<Route path="changelog" element={<V3Changelog />} />` removed
- `V3Landing.tsx` footer "Read the changelog" link removed (the "live numbers" → /v3/exec-demo link is preserved)
- No telemetry V3_EVENTS for changelog/tour existed (verified via grep) — nothing else to clean up
- TourBanner (concern 2) was the only other consumer; it was deleted in the same commit

### Concern 4 — Sweep visible emails to `admin@executespec.ai` · **DONE**

**Verbatim:** Replace public/visible email addresses across `website/` + `frontend/src/v3/`. Leave dev/test fixtures, runtime user-email variables, sample placeholder data, third-party emails.

**Status:** **Committed locally as part of `c260d3fc`. Not deployed.**

What it does — replaced (10 surfaces total):
- **website/index.html** — Organization schema email + Enterprise contact mailto + FAQ contact (3 swaps)
- **website/llms.txt** — Sales/enterprise contact line
- **website/press-kit/README.md** — Sales / enterprise contact
- **V3Trust.tsx** — `security@` subscribe-to-changes mailto + button label + `onContactSales` mailto + `enterprise@` demo CTA mailto (5 swaps)
- **V3Landing.tsx** — `hello@` TrustedByStrip "Add your logo" mailto
- **V3Team.tsx** — `enterprise@` Team-tier demo mailto + SSO config mailto (2 swaps)
- **PricingGrid.tsx** — `sales@` Enterprise demo CTA mailto (R-318 hint)

What it does NOT touch (per leave-classification):
- `V3Team.tsx` `PreviewMembersCard` sample roster (`you/marcus/priya/finance@yourco.com`) — illustrative example data
- `<input placeholder>` hint text (`you@example.com`, `teammate@example.com`, `friend@example.com`) — UI affordances, not contacts
- `CAPTURE_GUIDE.md` mention of `admin@executespec.ai` — already correct
- Any test fixtures (`*@test.local`)

**Verification gate (run after deploy):**
```
curl -s https://executespec.ai/ | grep -oE '[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\.[a-z]{2,}' | sort -u
# → should return only admin@executespec.ai (+ legit third-party if any)
```

## What the new thread needs to do

| Step | Command / action |
|---|---|
| 1 | Verify build clean: `npm --prefix frontend run build && JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -DskipTests compile` |
| 2 | `git push origin feature/v3-conversion` (3 commits) |
| 3 | FF dev: `git push origin feature/v3-conversion:dev` (server-side, 3 commits land on dev) |
| 4 | Watch GH Action: `gh run list --branch dev --limit 1` → Monitor stage transitions |
| 5 | Post-deploy verify (table below) |
| 6 | Final dispatch: commit SHAs, deploy run ID, verification table, screenshots |

### Post-deploy verification table (per original directive)

| Probe | Expected |
|---|---|
| `curl -sL https://dev.executespec.ai/ \| head -50` | v3-landing HTML, not login redirect |
| Headless Playwright anon visit to `https://dev.executespec.ai/` | page text contains `"AI-written pull requests"`; no `<form>` with password field |
| `curl -s -o /dev/null -w '%{http_code}' https://dev.executespec.ai/v3/changelog` | SPA fallback (200 with the catch-all redirect since it's a SPA route) — confirm via Playwright that `/v3/changelog` doesn't render the V3Changelog component (it doesn't exist) |
| Confirm no TourBanner element on `/v3/landing` or `/v3/home` (Playwright `page.$$eval`) | 0 matches |
| Email sweep on prod website: `curl -s https://executespec.ai/ \| grep -oE '[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\.[a-z]{2,}' \| sort -u` | only `admin@executespec.ai` (Cloudflare deploy needs a separate `cd .../website && npx wrangler deploy` — see "Cloudflare deploy" below) |

### Cloudflare deploy (concern 4 website edits)

The website lives on a separate Cloudflare Worker (not on the dev/main GH Action path). After the dev push lands, the website edits in `website/` need a separate deploy:

```bash
cd /Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf/website
npx wrangler deploy
```

Worker name: `square-bar-e1dc` (custom domains: `executespec.ai` + `www.executespec.ai`).
Wrangler is authenticated as `admin@executespec.ai` (OAuth token on this machine).
Last deploy version (pre-this-batch): `30d0d193-3d10-473f-b6ac-3f0696ef4b0c`.

**Important:** deploy from the **worktree** path above, NOT from the parent repo at `/Users/navneetprabhakar/Documents/antigravity/executespec/website/`. The parent repo had stale content earlier today and pushed pre-Wave-5 copy; the parent has now been switched to `dev` branch (see "Recent gotchas") so it's also safe, but the worktree is where the live edits are.

## Other open asks (NOT in this batch — keep parked)

| Item | Status |
|---|---|
| Push `6903173c` (slice-email.py recipient swap) | Now part of the 3-commit local stack — gets pushed automatically with concern 1+2+3+4 |
| Cut `main` to `dev@<new-tip>` | User said "park until this batch lands; come back after". Don't FF main without explicit go-ahead. |
| D-125 RepoAttachPopover — 4 product decisions | User wants to revisit. Recommendations from prior thread surfaced but not approved. Don't ship without product nod. The 4 q's: (Q1) private-repo-no-org-app-access, (Q2) mono-repo subdir, (Q3) repo-too-big quota, (Q4) per-run repo override. Prior recommendations: Q1=staged (b)+(c) ship now / (a) defer; Q2=`.executespec/root.yml` marker file; Q3=hard threshold + opt-in toggle; Q4=finish multi-repo project flow (V143/V144 partial). |
| Anonymous-run real LLM dispatch flag flip (`v3.anonymous-trial.real-dispatch`) | User said "ignore for now". Audit complete — flag stays OFF. Aggregate count tracker (S effort, ~30 min) is the recommended next step if they want count visibility on stub mode; not yet shipped. |

## Standing rules + invariants

- **Branch:** `feature/v3-conversion` is the working branch. **No PR opens. Push enabled.**
- **Cohabitation:** keep new work under `frontend/src/v3/`. v2 untouched **except** `EventName.java` (additive enum) + `SecurityConfig.java` (one `permitAll`) — those are the only sanctioned v2 edits per the strict-UX handoff. ProtectedRoute (touched in concern 1) is a v2-shared component but the change is invariant-preserving (anon `/` only).
- **Per-3-items checkpoint:** commit + push + dispatch update.
- **Each commit:** `feat(v3):` / `fix(v3):` / `chore(v3):` + concise subject + co-author footer.
- **Verify before push:**
  - `tsc --noEmit` after frontend changes
  - `mvn -q -DskipTests compile` after backend changes
  - Full `mvn test` between batches (NOT skipped — the AsyncAllowlistTest is sensitive to new `@Async` methods + must be in sync; failure cascades the GH Action deploy)
- **Stop only for:** real product decision the user must make / item turns out already done / red tests / hard blocker.

## Email rules

- **Automated email TO field:** `admin@executespec.ai` (was `navneet.prabhakar007@gmail.com` until 2026-04-28).
- **Visible website + in-app emails:** `admin@executespec.ai`. (Concern 4 handles the sweep; should be 0 non-admin executespec.ai addresses left after deploy.)
- **SMTP creds:** Gmail app password from `src/main/resources/application-dev.yml` (`app.mail.smtp.password`). Sender FROM is always `admin@executespec.ai`.
- **In-repo sender script:** `scripts/slice-email.py` — `RECIPIENT` constant updated to `admin@executespec.ai` in commit `6903173c`.
- **/tmp/ scratch senders:** all 21 of them updated by the previous thread (sed bulk-replace). They're scratch and won't survive a `/tmp` wipe; the in-repo `slice-email.py` is the persistent rule.

## Stack URLs / ports for QA

| Surface | URL |
|---|---|
| Local backend | `http://localhost:8081` (Spring profile `local`) |
| Local frontend | `http://localhost:5173` or whatever Vite picks (`VITE_PORT` env override available) |
| Local static website | `http://localhost:8989` (during the F1 sanity walks last week) |
| Deployed dev | `https://dev.executespec.ai` (deploys via push to origin/dev → `deploy.yml` GH Action) |
| Production website | `https://executespec.ai` (Cloudflare Worker `square-bar-e1dc`) |

The local stack from earlier in the session was likely killed when the user closed terminal sessions. New thread should bring it back up if local QA is needed:
```bash
# Backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -Dspring-boot.run.profiles=local
# Frontend (in another terminal)
npm --prefix frontend run dev
```

## Test users for QA on dev

| User | Tier | Role | Notes |
|---|---|---|---|
| `admin@executespec.ai / REDACTED_FOR_PUBLIC_RELEASE` | ENTERPRISE | SUPER_ADMIN | **dev.executespec.ai password — note the `REDACTED_FOR_PUBLIC_RELEASE` (3 not 4)** |
| `admin@executespec.ai / REDACTED_FOR_PUBLIC_RELEASE4` | ENTERPRISE | SUPER_ADMIN | LOCAL stack password — different! |
| `freshux-{free,pro,team,enterprise}@test.local / FreshUx@1234!` | FREE/PRO/TEAM/ENTERPRISE | various | Local-stack tier-test users; may or may not exist on dev |

## Deploy protocol (recap)

Push to `origin/dev` automatically fires `deploy.yml` GH Action. Stage order:

1. Set up job / Checkout / Java 21 / Node.js
2. **Build frontend** (Vite → `src/main/resources/static/`)
3. **Build JAR + run unit tests** (702 expected; `AsyncAllowlistTest` is sensitive — every new `@Async` method must be in the allowlist or the build fails)
4. Authenticate to GCP / gcloud CLI / GCS upload
5. **Deploy JAR to GCE (dev)**
6. **Wait for app startup** (Flyway migrations apply here; if a migration fails the JVM never comes up)
7. **Health check**

Last successful run: `25040076734` (2026-04-28T07:40:49Z, 355s wall-clock).

## Memory references

The user's persistent memory (in `/Users/navneetprabhakar/Library/Application Support/Claude/...`) holds:
- `executespec_post_ux_scope.md` — queued future scope (CLI / agent builder / readymade solutions / plugins / pre-built use cases)
- `executespec_email_recipient.md` — both email rules above (TO field + visible website)

## Recent gotchas / state-at-handoff

- **Parent repo branch.** `/Users/navneetprabhakar/Documents/antigravity/executespec/` was on `feature/event-tracking-jsonb-fix` earlier today, which caused a stale Cloudflare deploy (pushed pre-Wave-5 website content). The previous thread switched it to `dev` so it's now safe at `27e468c8` matching dev tip. **Always deploy Cloudflare from the worktree path, not the parent.** (See "Cloudflare deploy" above.)
- **`src/main/resources/static/trust-pack.pdf`** — untracked artifact in the worktree. Vite re-creates it on every `npm run build` (it's the R-320 Trust Pack PDF copied from `frontend/public/trust-pack.pdf`). Don't commit it; it's regenerated.
- **Wrangler auth** is OAuth-based (per `npx wrangler whoami`). Token is on this machine for `admin@executespec.ai` account. Permissions include `workers:write`.
- **`docs/pre-deploy-closeout.md`** is the document the previous thread wrote at the prior cut-line (Wave 8 close). The 13 deferred items there are still deferred — none of the 4 concerns in this batch override anything in that doc.

## Done — what to expect on first dispatch

The new thread should reply with:
1. Build verification pass/fail (frontend build + backend compile)
2. Push result (`origin/feature/v3-conversion` tip + dev tip)
3. GH Action run ID + watch result
4. Post-deploy verification table
5. Cloudflare deploy result (separate `wrangler deploy` from worktree)
6. Anything broken that wasn't broken locally

Then loop back for next directive.
