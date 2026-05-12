# ExecuteSpec — User Manual

> **Version:** 1.0.0-M1
> **Last updated:** 2026-05-12
> **Audience:** developers, product managers, tech leads using ExecuteSpec to ship code.

## Getting started

1. **Sign up** at `executespec.ai` — email + password, or sign in with Google / GitHub.
2. **Onboarding** — first login lands on `/welcome`. Pick one of three sample specs (Web API · Frontend · CLI) for the fastest path to your first run, or skip and link your own GitHub repo.
3. **Approve the plan** — when your run reaches the AWAITING_APPROVAL stage, review the task list / cost estimate / repos touched and click Approve.
4. **Watch the run** — live SSE events show each task progressing. Cost progress bar tracks budget.
5. **Review the PR** — on terminal, the auto-preview spins up (5-min TTL) and a PR is opened on GitHub (or per-repo PRs for multi-repo runs, cross-linked).

## Concepts

### Run
A single end-to-end execution. Lifecycle states: `QUEUED → PLANNING → AWAITING_APPROVAL → WORKING → REVIEWING → COMPLETE / PARTIAL / FAILED / CANCELLED`. PAUSED is a non-terminal pause (flag-gated).

### Plan
Output of the planner. Contains an ordered list of `tasks`, each tagged with complexity (`LOW` / `MEDIUM` / `HIGH`), the files it touches, and which `requirements` it covers. Approving the plan charges credits.

### Task
One unit of work the worker executes. Tier-routed per complexity: LOW → LIGHTWEIGHT model · MEDIUM → WORKHORSE · HIGH → PLANNING.

### Run modes
- **BUILD** (default) — full pipeline. New feature / spec-driven work.
- **PATCH** — single thin-agent loop against a failing-test spec. Used internally by the four autofix surfaces (PR Review · Tech-debt · CI failure · Code Review) and the SWE-bench / ProgramBench adapters.

### Verified score
A deterministic 0-100 score on every completed run. Multiplicative formula: `baseAccuracy × buildOK × testRatio × reviewerMultiplier − criticalPenalty`. No LLM self-rating in the score path. The legacy `accuracyScore` is retained as a secondary "Est." chip for pre-V106 rows.

### Status reasons
PARTIAL terminals carry one of: `CLEAN_COMPLETION`, `CLEAN_WITH_INTERMEDIATE_WARNINGS`, `FINAL_BUILD_FAILED`, `TESTS_FAILED:N_OF_M`, `REVIEWER_UNRESOLVED`, `REVIEWER_FAILED`, `ALL_TASKS_FAILED`. The status badge tooltip surfaces the reason in human copy.

### Project
A long-lived workbench bound to one or more linked GitHub / Bitbucket repos. Holds memory, architecture, chat history, validation history, and per-tier resource caps.

## Linking a repo

1. Open project dashboard → Repos tab → **Link a repo**.
2. Paste a GitHub URL OR pick from the org's available repos (requires `GITHUB_PAT` in Settings).
3. Choose scope: **ORG** (shared across the org) or **USER** (personal, only visible to you).
4. ExecuteSpec clones, runs `StackDetector`, and triggers the 3-step architecture pipeline (per-repo codemap → per-repo HLD/LLD → cross-repo synthesis). First analysis takes 1–3 minutes per repo.
5. Optionally install the GitHub webhook (`/webhooks/github`) — flips PR status automatically on merge / unmerge / CI check-run change.

## Submitting a run

1. Project dashboard → "+ New run".
2. Pick your repos (multi-select for multi-repo runs).
3. Enter your spec. The debounced **heuristic estimate chip** under the textarea shows ~M-N tasks · ~$X-Y based on the project's last 20 completed runs.
4. (Optional) **TDD mode** — planner leads with test tasks; implementation tasks `dependsOn` the test IDs.
5. (Optional) **Cost cap** — type an explicit $X.XX value to lower the platform ceiling.
6. (Optional) **Spec template** — pick from project-scoped or org-scoped templates with the Template picker.
7. (Optional) **Figma URL** — drives the design-compliance dimension on the validation report.
8. Click **Generate**.

## The plan gate

After classification + planning, runs in `PRODUCTION` mode pause at AWAITING_APPROVAL. You see:

- Task list with per-task complexity + files-touched + requirements covered.
- Cost band (low / high estimate) + credits charged on approve.
- Pre-plan feature assessment — complexity / risks / cross-repo deps.
- "Approve" or "Cancel" buttons.

Approving locks in the estimate, charges credits, and starts execution. Cancelling burns nothing.

## Cost + billing

- **Credits per run:** `max(1, ceil(estimate × 1.10 / $5.0))`. Charged at plan approval, refunded on FAILURE.
- **FREE tier** is capped at 1-credit specs (LOW/MEDIUM complexity only).
- **`userCostCap`** — optional per-run override ($0.10–$1000). Clamped against the platform cap. BYOK orgs skip the platform cap entirely; `userCostCap` is still honoured.
- **Top-up credits** — buy extra runs via `POST /api/billing/topup/purchase` (Razorpay / Stripe). Not available on TEAM / ENTERPRISE (BYOK).
- **`CostProgressBar`** on the run's Credits panel shows live spend vs budget (green 0-80% / amber 80-100% / red ≥100%).
- **Tier lifecycle** — PRO is HIDDEN (grandfathered). New buyers select FREE / TEAM / ENTERPRISE.

## Watching a run

The run detail page streams SSE events:

- `PLAN_READY` — planner emitted plan.
- `RUN_STATUS` — status transitions.
- `TASK_STATUS` — per-task progress.
- `LIVE_LOG` — worker tool calls + LLM completions.
- `RUN_COST` — live cost snapshot.
- `EXEC_COMPENSATION_*` — cancel rollback (closing opened PRs).
- `COMPLETE` — terminal `{status}|{status_reason}` payload.

## Auto-preview + PR

On terminal status COMPLETE / PARTIAL:

- **Preview** spins up on Cloud Run (dev/prod) or Docker Compose (local). 5-min TTL. URL surfaced on the run detail page.
- **GitHub PR** opens on the linked repo's branch `executespec/{runId-short}` (single-repo) or per-repo `executespec/{runId-short}` branches (multi-repo, cross-linked in PR bodies).
- **Cancel rollback** — aborting a run closes any opened PRs (idempotent) with a live cleanup view via `EXEC_COMPENSATION_*` SSE events.

## Project workbench

### Dashboard tabs

- **Pulse** — last-run hero + verified-score ring + signal chips + workspace health rollup.
- **Repos** — linked repos + sync state + webhook + open PRs.
- **Runs** — recent runs with status + verdict pill.
- **Code** — Generated Docs (EXPLANATION / SETUP) + Code Review sub-tab.
- **Chat** — full-height project chat panel with compaction + reset + per-bubble copy + branch override on launch.

### Project memory

Every completed project run appends a digest to `project_run_ledger`. After every 10 runs, `MemoryCompactionService` rewrites a versioned snapshot. The planner reads this as `{{PROJECT_MEMORY}}` so new runs know what was built, what patterns were set, and what debt was left.

### RAG corpus

Upload PDFs / docs / markdown via the Knowledge tab → Tika extracts text → 500/50-token chunks → OpenAI `text-embedding-3-small` → pgvector store. Both the planner (`{{REFERENCE_DOCS}}`) and chat use narrowed retrieval at run-time.

### Tech debt

The Tech-debt panel rolls up `validation_report.issues_json` across the project into category / severity / file-hotspots / dimension-trend. Click "Fix" per category to dispatch an autofix Phase 2 run.

### PR Review + Code Review (PRO+)

- **PR Review** (`POST /api/pr-reviews`) — emits verdict + line-anchored comments on one PR. Monthly quota per tier.
- **Code Review** (`POST /api/code-reviews`) — whole-repo scan with semgrep + LLM augmentation; downloadable markdown report.

### External sharing (V175)

- **Cross-org invite** — invite a collaborator by email. They get a `PROJECT_VIEWER` read-only seat that doesn't count against their org's seat cap.
- **Public read-only share link** — share a project surface (overview / run / validation report / code review / PR review / architecture) via a tokenised URL. Tier-gated by the active-link cap.

## Settings

### AI provider keys (BYOK)

TEAM / ENTERPRISE orgs configure their own keys for Anthropic / OpenAI / Gemini in Settings → AI Provider Keys. The Test button (`POST /api/auth/settings/ai-keys/validate`) verifies a key without saving.

### GitHub PAT

Per-user PAT in Settings → GitHub PAT. Required for private-repo clone + webhook install. Scope: `repo` + `admin:repo_hook`.

### Integrations

- **Slack** — org-level OAuth, per-project channel routing, Block Kit messages on run terminal + PR merge.
- **JIRA + Confluence** — inbound webhooks create runs from issues; outbound comments on `MergeCompleted`.
- **PagerDuty + ServiceNow** — incident integrations.

### Notifications

- **In-app** — `NotificationBell` in the header. Run complete + run failed + plan-gate opened + quota warning (≥80% of monthly cap, dedupe per cycle).
- **Email** — same events, opt-in via Settings.
- Per-event-type / per-channel opt-in/out matrix via `user_notification_pref` (V139).

## Roles

- **ORG_ADMIN** — full org control + billing + integrations + AI keys.
- **BILLING_ADMIN** — invoices + payment audit + accounting.
- **DEVELOPER** — submit runs, view metrics, chat.
- **PROJECT_ADMIN** — manage one project's settings + members.
- **PROJECT_DEVELOPER** — read + write within a project.
- **PROJECT_VIEWER** — read-only (V175 cross-org invitees).
- **SUPER_ADMIN** — internal only (platform admin).

See [`docs/RBAC.md`](RBAC.md) for the full permission matrix.

## Pause + resume (flag-gated)

Behind feature flag `v2.pause.enabled` (default OFF in prod):

- `POST /api/runs/{id}/pause` — valid only from WORKING / REVIEWING. Interrupts the active pipeline future, preserves workspace files on disk.
- `POST /api/runs/{id}/resume` — same-JVM only (workspace dir must still exist). Cross-JVM resume returns 409 with a retry hint.
- PAUSED is non-terminal — `cancel` + `halt-all` still work on PAUSED runs.

## Per-task retry

`POST /api/runs/{id}/tasks/{taskId}/retry` re-runs one task with `requireBuildVerification=true`. Does **not** auto-push — use the "Update PR" button on `InlineRunDetail` to push the retry result to the existing PR.

## GDPR data export

`GET /api/auth/me/data-export` returns a ZIP bundle:

- `manifest.json` — export metadata + GDPR Article 15/20 attribution.
- `payload.json` — your user + orgs + projects with sensitive fields redacted.
- `README.md` — usage + erasure path at `admin@executespec.ai`.

Sensitive fields stripped: password hash, all 3 LLM API keys, GitHub PAT. Capability flags ARE included so you can confirm settings without re-exposing the secret.

## Pricing summary

| Tier | $/mo | Runs | Seats | BYOK | Retention |
|------|------|------|-------|------|-----------|
| FREE | $0 | 5 / month, 1-credit only | 1 | — | 30d |
| PRO | $20 | 20 / month | 1 | — | 90d |
| TEAM | $39 / seat (min 2) | Unlimited | 2-20 | Required | 180d |
| ENTERPRISE | Custom | Unlimited + SLA | Unlimited | Optional | Unlimited |

PRO is HIDDEN from new buyers — existing PRO subscribers continue to renew. Solo/MAX tiers do not exist on `dev`/`main` (V179/V185 retired both).

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Run hangs in `WORKING` after a deploy | JVM restart before run terminated | `StartupRunResumer` resumes on boot (3-attempt cap). If exhausted, run is marked FAILED with `recovery_exhausted`. |
| Empty PR opened | Multi-repo run wrote files at workspace root before the worker prompt fix landed | Re-submit; current pipeline always routes writes into `repos/<name>/` subdirs. |
| Tier badge says FREE but I'm on PRO | Old session token cached before `EntitlementResolver.resolveTier` picked the higher of (user.tier, org.tier) | Force-logout + log back in. |
| 402 on `POST /api/runs` | Run count exhausted for the month / no credits | Top up via Billing page, upgrade tier, or wait for the monthly reset. |
| 402 on `POST /api/org/members/invite` | TEAM seat cap exceeded | Adjust seats via Org admin dashboard `POST /api/org/me/seats/adjust`. |
| Preview never starts on local | Docker daemon not running | Start Docker Desktop. Run still completes; auto-preview is best-effort. |
| Reviewer verdict is REVIEW_FAILED but build passes | Reviewer timed out (15-min wall cap was 60s in v2.5.x — fixed in v2.5.13) | Re-run; if reproducible report via `/help`. |
