# GitHub Integration — User-Facing Test Scenarios

> Last reviewed: 2026-04-13.
> Scope: what a user can do with GitHub *inside* ExecuteSpec, from a QA point of view.
> No tier gating on GitHub — all tiers can connect + push.

Each scenario is written as **Given / When / Then** so it can be dropped straight into a test plan.

---

## 1. Connect / Disconnect

### TC-GH-1.1 — Connect GitHub with valid PAT
- **Given** a signed-in user with no GitHub credentials.
- **When** the user opens `/settings`, pastes a valid PAT + GitHub username, clicks Save.
- **Then** the GitHub card shows the green **Connected** badge; `hasGithubPat = true` on `/api/auth/me`; reloading the page keeps the state.

### TC-GH-1.2 — Connect with malformed PAT
- **Given** user at `/settings`.
- **When** PAT field is empty / whitespace / too short.
- **Then** inline validation error; Save disabled or returns 400 "Invalid token format".

### TC-GH-1.3 — Disconnect
- **Given** a user with connected GitHub.
- **When** they clear the PAT field + username in `/settings` and Save.
- **Then** badge flips to **Not connected**; `hasGithubPat = false`; any in-flight run's PR link keeps working (snapshot at submission time).

### TC-GH-1.4 — PAT rotated externally (still valid)
- **Given** a user connected with PAT A.
- **When** they paste a new valid PAT B over the existing config.
- **Then** the new token replaces the old; next push uses B.

### TC-GH-1.5 — PAT revoked on GitHub side
- **Given** a user connected with PAT A that has been revoked on github.com.
- **When** they attempt to push a completed run.
- **Then** the push fails with 401 "Authentication failed. Check your token."; the `/settings` card still shows **Connected** (we don't proactively probe) — user must re-paste a fresh PAT.

---

## 2. Submitting a run against an existing GitHub repo

### TC-GH-2.1 — Happy path: public repo user owns
- **Given** a connected user with a public repo `github.com/alice/demo`.
- **When** they open `NewRunModal`, pick **"Start from existing repo"**, paste the URL, enter spec, submit.
- **Then** backend clones shallow → the run's workspace reflects the repo contents → run proceeds normally → on completion a PR is opened from branch `executespec/<runId-short>` to the repo's default branch → PR URL appears on Overview tab as a clickable link.

### TC-GH-2.2 — Private repo the user can access
- **Given** PAT has `repo` scope, repo is private but user is a collaborator.
- **When** same flow as 2.1.
- **Then** same outcome.

### TC-GH-2.3 — Repo user cannot access
- **Given** user pastes a repo they don't have access to.
- **When** they submit.
- **Then** submission accepted (URL not validated upfront), but clone fails inside the run → run reaches FAILED with reason "Repository not found or not accessible (404)".

### TC-GH-2.4 — Repo URL malformed
- **Given** user pastes `not-a-github-url` in the repo URL field.
- **When** they try to submit.
- **Then** modal-side validation rejects before POST; if bypassed (e.g., via API), backend returns 400 "Invalid repository URL".

### TC-GH-2.5 — Repo URL with trailing slash, `.git` suffix, or SSH form
- **Given** variations: `https://github.com/org/repo/`, `https://github.com/org/repo.git`, `git@github.com:org/repo.git`.
- **When** user submits any of them.
- **Then** backend normalises to the canonical HTTPS form; run proceeds as 2.1.

### TC-GH-2.6 — Repo with protected default branch
- **Given** repo's default branch has branch protection (required reviews).
- **When** run completes + PR opens.
- **Then** PR is created but not merged; user sees PR URL normally; merge happens only after human review on GitHub.

### TC-GH-2.7 — Repo has no default branch (empty repo)
- **Given** user points run at a brand-new empty repo.
- **When** run completes.
- **Then** backend pushes to `main` as the first commit; no PR (there is no base); `scmPrUrl` null or shows the repo URL.

### TC-GH-2.8 — Submit without PAT
- **Given** user has not connected GitHub.
- **When** they open `NewRunModal` and select "Existing repo".
- **Then** UI nudges "Connect GitHub in Settings first"; submit with `repoUrl` still accepted for PROTOTYPE mode (no push), but no PR will be created at completion.

---

## 3. "Create new repo" flow

### TC-GH-3.1 — New repo created on completion
- **Given** connected user with `repoMode = NEW`.
- **When** they submit a spec + run completes.
- **Then** a private repo named e.g. `executespec-<runTitle-slug>` appears under their GitHub account; code pushed to `main`; run's Overview tab shows the repo URL (no PR).

### TC-GH-3.2 — Repo name collision
- **Given** a repo with the candidate name already exists.
- **When** the run completes.
- **Then** backend appends a short suffix (`-<runId-short>`) and retries. No failure visible to the user.

### TC-GH-3.3 — PAT lacks `repo` scope
- **Given** user's PAT only has `read:user` scope.
- **When** run completes and tries to create a new repo.
- **Then** 403 "Insufficient permissions: token needs `repo` scope"; run's GitHub step shows FAILED but run itself still COMPLETE; user can re-push manually after updating PAT (TC-GH-4.x).

---

## 4. Manual push from a completed run

### TC-GH-4.1 — Push button happy path
- **Given** a COMPLETE run with no PR yet and a connected GitHub account.
- **When** user clicks **Publish / Push to GitHub** on the run detail toolbar.
- **Then** button enters loading state → response returns PR URL → Overview tab updates to show the link → toast "Pushed to GitHub".

### TC-GH-4.2 — Push when no PAT configured
- **Given** user clicks Push without a PAT.
- **When** backend returns 400 "GitHub credentials not configured".
- **Then** frontend opens inline "Connect GitHub" modal; after user pastes PAT + saves, the push retries automatically.

### TC-GH-4.3 — Push a PARTIAL run
- **Given** a run that ended PARTIAL (some tasks failed).
- **When** user clicks Push.
- **Then** push succeeds (PARTIAL is allowed per backend); PR description notes "Generated by ExecuteSpec; N/M tasks complete".

### TC-GH-4.4 — Push a FAILED run
- **Given** a run that FAILED entirely.
- **When** user clicks Push.
- **Then** 409 "Only COMPLETE or PARTIAL runs can be pushed"; button is disabled in the UI.

### TC-GH-4.5 — Re-push already-pushed run
- **Given** a run whose PR already exists (`scmPrUrl` populated).
- **When** user clicks Push again after further changes.
- **Then** backend force-updates the feature branch (same name); existing PR picks up the new commits; UI does NOT open a second PR.

### TC-GH-4.6 — Push while run still executing
- **Given** run in WORKING / REVIEWING.
- **When** user attempts Push.
- **Then** 409; button disabled in UI with tooltip "Wait for run to complete".

---

## 5. PR link surfacing

### TC-GH-5.1 — PR link appears on Overview tab
- **Given** a COMPLETE run with a PR.
- **When** user opens RunDetailTabs → Overview.
- **Then** a clickable "View PR" badge / icon is rendered using `scmPrUrl` (or legacy `githubPrUrl`).

### TC-GH-5.2 — PR link persists across reloads
- **Given** run detail page.
- **When** user reloads.
- **Then** PR link is still rendered (no re-fetch required).

### TC-GH-5.3 — PR link persists after disconnecting GitHub
- **Given** user disconnects GitHub after a run's PR was created.
- **When** they revisit the run.
- **Then** PR link still renders (the URL is durable data on the run).

### TC-GH-5.4 — PR URL points to correct PR
- **Given** multiple runs against the same repo.
- **When** each run completes.
- **Then** each run's Overview shows its *own* PR — no cross-contamination. Feature branch names are unique (`executespec/<runId-short>`).

---

## 6. Error paths & rate limits

### TC-GH-6.1 — GitHub rate limit hit
- **Given** user has exhausted their GitHub API quota.
- **When** a push is attempted.
- **Then** 429 "GitHub API rate limit exceeded, retry in N minutes"; UI surfaces the reset time. Run status unchanged; user can retry after reset.

### TC-GH-6.2 — GitHub temporarily down (5xx)
- **Given** GitHub returns 500/502/503.
- **When** push is attempted.
- **Then** retried once with exponential backoff; if still failing, 502 surfaced with "GitHub is currently unavailable" toast.

### TC-GH-6.3 — Network timeout between ExecuteSpec and GitHub
- **Given** the clone or push exceeds the configured timeout.
- **When** the operation times out.
- **Then** user-visible toast "GitHub request timed out"; workspace state is rolled back (no half-pushed branch).

### TC-GH-6.4 — Push to branch with merge conflict against main
- **Given** default branch has diverged since the clone.
- **When** the PR is opened.
- **Then** PR is still created (GitHub handles the rebase/conflict marker); user sees conflict marker on github.com; ExecuteSpec shows the PR link regardless.

### TC-GH-6.5 — Force-push blocked by branch protection
- **Given** the feature branch already exists and branch protection forbids force-push.
- **When** user re-pushes (TC-GH-4.5).
- **Then** 403 "Force-push blocked by branch protection"; toast explains; user must push a new run (fresh branch).

---

## 7. OAuth2 login via GitHub (different from PAT push)

### TC-GH-7.1 — New user signs in with GitHub OAuth
- **Given** unauthenticated visitor.
- **When** they click **Sign in with GitHub** on `/login`.
- **Then** redirect to GitHub consent → `/auth/callback` → account created (FREE tier) → land on `/`.

### TC-GH-7.2 — Existing email signs in via GitHub for the first time
- **Given** user originally signed up with email `x@y.com`; GitHub account uses same email.
- **When** they use the GitHub OAuth button.
- **Then** accounts are merged; user lands on their existing account (no duplicate).

### TC-GH-7.3 — GitHub OAuth consent denied
- **Given** user clicks **Sign in with GitHub**, then clicks "Deny" on GitHub.
- **When** callback runs.
- **Then** user returns to `/login` with toast "GitHub sign-in cancelled".

### TC-GH-7.4 — OAuth state mismatch (CSRF guard)
- **Given** an attacker-crafted callback with mismatched `state`.
- **When** the callback is hit.
- **Then** redirect to `/login` with error toast; no session issued.

---

## 8. Download-zip fallback

### TC-GH-8.1 — Download zip redirects to GitHub when workspace is GC'd
- **Given** a run completed >N days ago; local workspace purged by cleanup.
- **When** user clicks **Download zip**.
- **Then** backend redirects to GitHub's archive URL (`github.com/owner/repo/archive/refs/heads/<branch>.zip`); browser downloads from GitHub directly.

### TC-GH-8.2 — Zip download before PR exists
- **Given** run is COMPLETE but Push hasn't happened.
- **When** user clicks Download zip.
- **Then** served from local workspace (standard path); no GitHub involvement.

---

## 9. Negative / edge

### TC-GH-9.1 — Two users in same org, different PATs
- Each user's push uses **their own** PAT; no cross-user leakage.

### TC-GH-9.2 — User downgrades tier
- Downgrade from PRO → FREE does NOT invalidate GitHub connection. All GH scenarios continue to work.

### TC-GH-9.3 — Super-long run title → branch name collision
- Branch name is derived from `runId`, not title → no collision possible.

### TC-GH-9.4 — Repo deleted mid-run
- Submit against `github.com/alice/demo`; Alice deletes the repo while the run is executing.
- On push at the end: 404 "Repository not found"; run still COMPLETE, push step shows FAILED with retry CTA.

### TC-GH-9.5 — Concurrent pushes from two machines for same run
- Two tabs, two push clicks almost simultaneously.
- Server-side idempotency: only one PR is created; second request returns the same `scmPrUrl`.

---

## 10. Admin / audit

### TC-GH-10.1 — Audit log entry on every push
- `/audit` shows an entry `run.push_scm` per push attempt, with run ID + success/failure + HTTP status.

### TC-GH-10.2 — Disabling GitHub integration via feature flag
- **Given** SUPER_ADMIN flips `integration.github.enabled = false`.
- **When** any user tries to push.
- **Then** 403 "GitHub integration disabled by platform administrator".

---

## Verification matrix

| Area | # of scenarios | Manual-first | Automatable |
|---|---|---|---|
| Connect / disconnect | 5 | yes | yes (via API) |
| Existing-repo flow | 8 | yes | partial (needs test GH org) |
| New-repo flow | 3 | yes | partial |
| Manual push | 6 | yes | yes |
| PR surfacing | 4 | yes | yes (headless) |
| Errors & rate limits | 5 | yes | yes (mock GH) |
| OAuth sign-in | 4 | yes | yes (mock OAuth) |
| Download fallback | 2 | yes | yes |
| Edge / negative | 5 | yes | partial |
| Admin | 2 | no | yes |
| **Total** | **44** | | |

All scenarios should be runnable against the eval harness once we add a `--scm github` fixture. Integration-test priority: TC-GH-1.1, 2.1, 4.1, 4.2, 5.1, 6.1 cover 80% of the real-world paths.
