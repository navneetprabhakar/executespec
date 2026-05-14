# Local Test Playbook

> Last updated: 2026-05-13 (PR-F). Author: ExecuteSpec team.

Operational checklist for verifying changes locally **before** pushing to `dev`. This exists
because CI is necessary but not sufficient — by the time a regression shows up in
`dev.executespec.ai` the developer is two compactions away from the original context and the
rollback is much more painful than catching the issue on their workstation.

The standing rule from MEMORY: **before every push to `dev`, run the change locally (Flyway
migrate + Spring boot + smoke request)**. This playbook is the muscle-memory version of that
rule.

## When to run this

- ✅ Backend change touching DB schema, an entity, or a controller
- ✅ Frontend change touching the API client (`frontend/src/lib/api.ts`) or a Zustand store
- ✅ Migration (Flyway `V*.sql`) — _always_ exercise locally before push
- ✅ Async work (`@Async`, `@Scheduled`, SSE) — CI doesn't catch lazy-collection bugs
- ❌ Trivial typo, comment-only change, README tweak — skip with the conventional
  `skip doc-refresh: trivial typo` commit suffix

## 0. Pre-flight (every session)

```bash
# Catch silent-divergence: pull only if fast-forward-able, otherwise stop + rebase manually.
git pull --ff-only

# Confirm where you are. Pre-push playbook only runs on feature/* branches.
git status
git branch --show-current

# JAVA_HOME on macOS dev — set in every shell, never inherited.
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

## 1. Build gates (must pass before anything else)

```bash
# Backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spotless:apply -q   # formats; never edits intent
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spotless:check -q   # then re-check
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test -q             # full suite (~3-5 min)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q           # includes ArchUnit guards

# Frontend
npm --prefix frontend run build         # tsc + vite production build
npm --prefix frontend run format        # prettier --write
npm --prefix frontend run format:check  # re-check
npm --prefix frontend run lint          # ESLint (0 errors required; warnings OK)
```

**Stop conditions:**
- `mvn test` exits non-zero → fix tests; do NOT push with red bar
- `spotless:check` fails → re-run `spotless:apply`, commit format diff separately if large
- `format:check` fails → re-run `npm run format`
- ArchUnit fails (AccessGuardCoverageTest, AsyncAllowlistTest, ApiContractTest) → these guard
  catastrophic risks (tenant leak / lazy-init / API drift). Read the failure message and either
  fix the underlying issue or update the allowlist with a justification comment.

## 2. Start local stack

Prereqs: Postgres + Redis on localhost.

```bash
# Postgres + Redis via Docker (one-time, or after a reboot)
docker compose -f local/docker-compose.yml up -d postgres redis

# Backend (spring profile=local hydrates the local DB / Redis / mock SMTP)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -Dspring-boot.run.profiles=local
# Expect: "Started ExecutespecApplication in N seconds"
# Expect: Flyway migrate log showing your new Vxxx__*.sql

# Frontend dev server (Vite, proxies /api → :8080)
npm --prefix frontend run dev
# Expect: "Local: http://localhost:5173/"
```

## 3. Smoke tests

### 3.1 Auth + login (60s)

Use one of the local creds (auto-provisioned by `BootstrapService` on first start):
```
navneet.prabhakar007+freetier@gmail.com / Test@1234
navneet.prabhakar007+protier@gmail.com / Test@1234
navneet.prabhakar007+enterprise@gmail.com / Test@1234
```

- Hit `http://localhost:5173/login` → log in → land on `/v3/projects` (V3 shell) or `/` (V2)
- Network panel: confirm `/api/auth/me/permissions` returns the expected tier in the response

### 3.2 The change you actually made (the important bit)

Pick the relevant section for what you touched:

**Touched a migration?**
- Check Spring boot log for `Migration of schema "public"` lines mentioning your new `Vxxx`
- Connect to Postgres + `\d <table>` confirm columns landed
- Reverse-check: if the migration adds a column with a default, query a few existing rows + confirm the default
- If the migration's a backfill UPDATE, run it twice (Flyway only runs it once, but re-running
  manually via `psql -f` shouldn't break anything if the migration is idempotent)

**Touched a controller / API client?**
- Open the affected page in the browser, trigger the action, watch the Network panel
- Confirm the request URL + method + body match what `frontend/src/lib/api.ts` sends
- Confirm the response matches the DTO record in `src/main/java/.../api/dto/*.java`

**Touched async / SSE?**
- Open browser devtools → Network → filter for `events?token=` (the SSE stream)
- Trigger the action that should fire the event
- Confirm the event appears in the SSE stream within expected latency
- Watch the backend log for the corresponding emitter line — both sides should be alive

**Touched cost / billing / tier enforcement?**
- Login as FREE tier user
- Hit the action that should be tier-gated (e.g. submit a 6th run)
- Confirm the right HTTP status comes back (402 for billing, 403 for tier, 429 for rate)
- Confirm Postgres `run_cost_log` rows get written + `pipeline_run.credits_charged` updates

**Touched archive / delete (PR-B-part-2 surface)?**
- Login → projects list → click "Archive project" → confirm row moves to archived list
- Click "Unarchive" → confirm it comes back to active
- Click "Delete" → confirm the 30-day window prompt + row disappears
- Hit `POST /api/projects/{id}/restore` via curl → confirm row restored
- Refresh → confirm `archived_at` / `deleted_at` columns in the DB match the UI state

### 3.3 Telemetry sanity (30s)

- Backend log: `tail -f` should not show `WARN` / `ERROR` lines tied to your change
- `/actuator/prometheus` should expose any new Micrometer counters you added (search the body
  for your counter name; should appear with `0.0` if the path hasn't fired yet)

## 4. Pre-push gate (the moment of truth)

```bash
# Everything below MUST be green before `git push`. If any fail, stop + fix.
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test -q       # one last time
npm --prefix frontend run build                          # one last time

git status                  # clean working tree (or only intended changes)
git diff --stat HEAD~1      # what's actually going to land
git log --oneline -3        # the commit you're about to push reads well
```

## 5. After push

- Watch GitHub Actions in the PR's "Checks" tab — the lint workflow runs `format:check + lint`
- If CI fails on something local didn't catch, treat that as a playbook gap and add a step here
- If you're on a feature branch heading to `dev`: never push directly to `dev`; only merge via
  PR + at least one self-review pass through the diff

## 6. Common gotchas

- **`Flyway out-of-order` is on locally** but OFF in prod. A migration numbered below the head
  works locally but will fail prod migrate. Use the next-sequential number from
  `ls src/main/resources/db/migration/`.
- **`@Async` + JPA = lazy-init time bomb.** `AsyncAllowlistTest` pins every `@Async` method;
  new ones must be reviewed for lazy-collection safety and explicitly allowlisted.
- **SSE token lives 5 minutes.** A run that opens a connection + then sits idle past 5 min will
  reconnect; this is expected — don't chase the SSE_RECONNECT line in the log.
- **`mvn spring-boot:run` without `-Dspring-boot.run.profiles=local`** picks up the prod
  config and tries to reach Cloud SQL / production Redis. Always pass the profile.
- **Frontend cache invalidation after a backend DTO change**: rebuild + hard-reload
  (Cmd-Shift-R on Chrome) — Vite's HMR caches stale generated types.

## Related docs

- [`docs/operations/slos.md`](slos.md) — the 5 published SLOs
- root `CLAUDE.md` — "Build & test" section is the canonical command reference
- `frontend/CLAUDE.md` — frontend-specific gates (pa11y, contrast tests, prettier deviations)
- `src/main/java/com/navneet/executespec/state/CLAUDE.md` — migration log
