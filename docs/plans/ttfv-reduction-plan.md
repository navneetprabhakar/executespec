# TTFV reduction — signup to first-run in under 3 minutes

> **Scope:** compress the signup-to-first-verified-run experience from today's 10-15 min of clickwork to under 3 min median. Ship a **sample spec + sandbox repo on signup** so a new user feels the product before deciding whether to link a real GitHub repo.
> **Origin:** PM-view persona analysis 2026-04-22. Ranked P0 #2. Every persona flagged this — Engineer: "I'll burn 5 runs in an hour and leave"; VP: "useless for evaluation"; C-Suite: "bad ROI".
> **Target ship:** 2026-05-07 → 2026-05-20 (2 dev-weeks, runs **in parallel** with activation-instrumentation). Surfaces don't overlap — activation is backend instrumentation, TTFV is signup frontend + sandbox infra.
> **Status:** PLANNED, not started.
> **Last updated:** 2026-04-22.
> **Related:** [`activation-instrumentation-plan.md`](activation-instrumentation-plan.md) — measurement, [`team-tier-plan.md`](team-tier-plan.md), [`persona-roadmap-2026-04-22.md`](persona-roadmap-2026-04-22.md).

## 1 · Why this exists

From the PM-view persona analysis:
> "Walking the signup path on paper: email verify → project create → link repo (needs GH PAT) → write spec → wait for assessment → approve plan → wait for execution → see result. That's 10-15 minutes of clickwork + 5-10 minutes of LLM wait. Cursor's TTFV is <60 seconds. Claude Code's is <30 seconds."

The single biggest rate-limiter on activation is: **before a user can feel the product, they must link a real GitHub repo and write a real spec.** Both are non-trivial for someone who signed up to "try it out." Most bounce.

Compare:
- **Cursor:** download + open → type + tab-complete. 60s.
- **v0 / Lovable / Bolt:** type prompt → see app appear. 90s.
- **Claude Code:** `npx claude` → type. 30s.
- **ExecuteSpec today:** signup → verify email → create project → get GH PAT → paste PAT → pick repo → write spec → wait for assessment → approve plan → wait for execution → see output. 10-15 min.

If the persona analysis's "aha = verified score ≥ 85 on a real spec" is right, we must deliver that moment **in under 3 minutes** or new users leave before the insight lands.

## 2 · The "sandbox repo on signup" idea

On signup, auto-provision a **scratch workspace** pre-seeded with:
1. A writable sandbox git repo (not the user's own — a blank, disposable one)
2. A pre-filled sample spec in the submit textarea (LOW complexity, realistic target)
3. Optional: a 1-click "submit this sample" button that bypasses the plan gate for first-run only

User experience:
```
Signup → email verify → land on dashboard → see "Submit your first run" card → click → spec already filled → Submit → plan gate (with 1-click approve or review) → run executes → verified score visible → see PR link (on the sandbox repo)
```

Target: **under 3 minutes median from `signup` event to `run_complete` event.**

## 3 · Sample specs — the content

Three templates, picked based on a single onboarding question: "What are you building?"

| Answer | Sample spec | Project type | Target runtime |
|---|---|---|---|
| **Web app / API** (default) | "Add a POST /api/contacts endpoint that accepts {name, email} and returns 201 with the created row, stored in SQLite." | `spring-boot-sqlite` or `node-express-sqlite` | ~90 sec LLM + ~20 sec smoke |
| **Frontend / landing page** | "Create a landing page for a SaaS tool called 'NotesAI' with a hero, 3 feature cards, and a signup form. Use Tailwind. Responsive." | `node-frontend-vite` | ~60 sec LLM + ~15 sec build |
| **CLI / script** | "A Python CLI that reads lines from stdin, counts word frequency, and prints top-10 words as JSON." | `python-cli` | ~45 sec LLM + ~10 sec smoke |

All three are LOW-complexity (1 credit). All three produce a visible, shareable output. All three have a verified-score target of 85+.

**Why 3 templates not 1:** different personas activate on different surfaces. An engineer thinking about APIs doesn't feel the product from a landing page. A solo founder thinking about frontends doesn't feel it from a POST endpoint. 3 covers 90%+ of real intent.

## 4 · Sandbox repo — the mechanism

### 4.1 Option A (recommended): GitHub org-owned throwaway repos

- We pre-create a GitHub org `executespec-sandbox` with ~100 empty repos named `sb-001` through `sb-100`.
- On signup, auto-assign one to the user as `sandboxRepoUrl`.
- User runs push to this repo via the existing GH push path.
- After 24h of inactivity, we reset the repo (hard-reset to empty) and recycle it.
- Visible to the user during the 24h window; they can share the PR link.

**Pros:** reuses existing GitHub push infra. Real PR URL. Real preview deploy.
**Cons:** requires 100-repo pool + rotation logic. GitHub API rate limits on reset.

### 4.2 Option B: Ephemeral workspace (no repo)

- Signup creates a server-side workspace directory (same `workspace/<runId>/` path).
- Run executes, produces output files + preview deploy.
- No GitHub push; user sees preview URL + file tree + verified score.
- After 24h workspace is GC'd.

**Pros:** zero external dependency. Faster to ship.
**Cons:** no PR link — weakens the "aha." No share-with-friends moment.

### 4.3 Option C: Hybrid

- Ship Option B in v1 (2-week window).
- Add Option A post-v1 if activation data shows "PR link missing" is a real drop-off point.

**Recommendation: Option C.** Ship B now, measure with activation instrumentation, upgrade to A if the data justifies.

## 5 · Scope — what ships (v1, Option B path)

**IN scope:**
1. New signup flow: after email verification, land on `/welcome` not `/projects`
2. `/welcome` page — "What are you building?" 3-option picker
3. On pick, auto-create a sandbox project + preload sample spec
4. 1-click "Submit this sample" button on the pre-filled spec
5. First-run plan gate: auto-approve enabled by default (toggle "Review plan first" visible but off by default)
6. Sandbox workspace GC: 24h idle → delete
7. Celebrate-on-complete: after `run_complete` fires, show a full-screen moment: "Verified 92/100 · Preview running · Share this run"
8. "Link your own repo" CTA only appears AFTER first `run_complete` — not during onboarding
9. `app_user.sandbox_project_id` column (so we know which project was the sandbox)
10. Activation instrumentation (§activation-instrumentation-plan.md) captures each step

**OUT of scope:**
- Option A (GitHub sandbox repo pool) — defer to v2 if needed
- Sample specs for mobile (RN/Expo) — defer; mobile runs are slower + riskier for first-run
- Multiple simultaneous sandboxes per user — one at a time
- Editing the sample spec before submit — v1 = submit as-is. Power-user edit is v2.
- A/B testing different sample specs — need activation data first

## 6 · Day-by-day execution (2026-05-07 Thu → 2026-05-20 Wed, 10 working days)

**Day 1 (May 7, Thu):** Backend schema + sandbox project creation
- Flyway V125: `app_user.sandbox_project_id VARCHAR(64)` + `project.is_sandbox BOOLEAN`
- `SandboxProvisioningService` — on first login after email verify, auto-create a hidden project and attach to user
- Idempotent: if user already has a sandbox, reuse it (don't provision twice)

**Day 2 (May 8, Fri):** Sample-spec bank + onboarding API
- `src/main/resources/sample-specs/{web-api, frontend, cli}.yml` — spec text + project type + scaffold
- `GET /api/onboarding/sample-specs` — returns the 3 options (already-signed-in user)
- `POST /api/onboarding/pick-sample` — body `{sampleId}`, provisions sandbox + pre-fills spec, returns `{projectId, prefilledSpec}`

**Day 3 (May 9, Sat):** Auto-approve first-run plan gate
- New `PipelineRun.auto_approve_first_run` flag (derived from user's first-run status)
- If `SandboxProvisioningService` created the project AND this is user's first run, skip plan-gate wait OR auto-approve after 2 seconds (so user sees the plan briefly)
- Config: `onboarding.first-run.auto-approve.enabled=true` (default on)

**Day 4 (May 10, Sun):** Workspace GC
- New `SandboxCleanupJob` @Scheduled(cron = "0 0 * * * *") @SchedulerLock — hourly
- Iterates `is_sandbox=true` projects where `last_run.started_at < now() - 24h` → delete workspace dir + mark project archived
- Metrics: count of deletions per run

**Day 5 (May 11, Mon):** Frontend — /welcome page
- New `frontend/src/pages/WelcomePage.tsx` at `/welcome`
- 3-card picker UI (web-api / frontend / cli)
- On pick, POST to `/api/onboarding/pick-sample`, navigate to `/runs/new` with prefilled spec
- Redirect rule: authenticated users without `sandbox_project_id` land here on first visit

**Day 6 (May 12, Tue):** Frontend — prefilled spec + 1-click submit
- Modify `NewRunModal` / `ExecutionSetup` to honor `?prefill=<specId>` URL param
- "Submit sample" button (primary CTA) + "Edit before submitting" (secondary)
- Auto-select provider (default OpenAI for web-api, Anthropic for frontend, Gemini for CLI — pick the fastest-provider-per-stack for TTFV)

**Day 7 (May 13, Wed):** Frontend — celebrate-on-complete screen
- New `frontend/src/components/runs/FirstRunCelebration.tsx`
- Shows after first `run_complete` event with `verifiedScore ≥ 0` (positive framing — don't hide PARTIAL scores)
- Elements: score ring, "Run another" CTA, "Link your own repo" CTA, share link

**Day 8 (May 14, Thu):** Dev deploy + end-to-end timing
- Deploy to dev
- Fresh Gmail account signup on dev.executespec.ai, stopwatch from page-land to `run_complete`
- Repeat 3 times with the 3 sample specs
- Target: median under 3 minutes. If > 5 min, identify the bottleneck and fix on Day 9.

**Day 9 (May 15, Fri):** Tune + polish
- Based on Day 8 timing, optimize the slowest step. Likely candidates:
  - Email verification (link should pre-auth + auto-redirect)
  - Plan-gate 2s auto-approve window (may need to drop to 0.5s)
  - Preview spin-up (parallel with worker execution, not serial)
  - Sandbox project creation latency
- Copy pass on /welcome and celebration screen

**Day 10 (May 16, Sat) — or move to May 18 Mon if weekend not worked:** Prod cutover
- PR dev → main, merge, deploy prod
- Smoke with a fresh Gmail signup on executespec.ai
- Verify activation funnel shows compressed times post-deploy
- Document measured TTFV in `docs/reports/ttfv-baseline-2026-05-18.md`

## 7 · Success metrics

Only measurable **after** activation instrumentation ships (week before). If activation lands first, we have baseline + post-deploy comparison.

| Metric | Baseline (today, guesstimated) | Target | Data source |
|---|---|---|---|
| Median time signup → run_complete | 10-15 min | **< 3 min** | PostHog funnel time-to-event |
| Users who hit run_complete within 5 min of signup | < 20% | **> 60%** | PostHog cohort |
| Users who hit run_complete within 24h of signup | ~50% | **> 85%** | PostHog cohort |
| Users who run a second run within 24h | ~30% | **> 50%** | PostHog repeat-action event |
| Users who link own repo within 7 days | ~40% | **> 55%** | `project_repo` table join |

**Kill criteria:** if median TTFV is not under 5 min after Day 10 tune, roll back the auto-approve flag and revisit the sandbox approach. Something structural is wrong and we should not ship a half-working onboarding.

## 8 · Risk + mitigation

| Risk | Mitigation |
|---|---|
| Sample spec fails (LLM hallucinates, build errors) → first-run experience is a FAIL not a celebration | Pre-run each sample spec on all 3 providers before Day 10, verify ≥ 85 verified score. Make the samples deterministic. |
| LLM cost for every signup (sandbox run consumes ~$0.10-0.15) | LOW-complexity specs cap cost. Estimated $30-50/mo extra LLM cost at 200 signups/mo. Absorbed as CAC. |
| Sandbox cleanup job deletes active data | Add `project.deletion_eligible_at` timestamp; GC only deletes if past-due AND no active runs. |
| User writes sensitive content in sample spec input | v1 = sample is pre-filled but editable; explicit warning in hover text that sandbox is disposable. |
| Auto-approve bypass bypasses security checks | Verify: sandbox specs still route through `InputSanitizer` + `CodeAbuseScanner`. Auto-approve only skips the *human-gate*, not the safety checks. |
| Rate limit: too many signups in a burst (e.g. PH launch day) spawn too many workspaces | Per-hour signup limit (already in `RateLimitFilter`); sandbox workspace reuses a pool if full. v1 = accept that PH day may hit caps; add pool in v2. |

## 9 · What this plan deliberately does NOT do

- **No multi-language onboarding.** Samples are English only. v2 can localize if we expand to non-US markets.
- **No personalization beyond 3 templates.** Deeper personalization (e.g. "based on your GitHub bio") is cool but v3.
- **No "connect your editor" mid-onboarding.** VS Code extension is a separate item (P2). Don't conflate.
- **No skipping the plan gate permanently.** Auto-approve is FIRST-RUN ONLY. Second run goes through the real gate.
- **No paid-tier onboarding.** This ships for FREE signups (the highest-impact audience). Paid-tier onboarding (TEAM admin setup) is a separate follow-up.

## 10 · Pre-flight checklist

- [ ] Activation instrumentation (§activation-instrumentation-plan.md) deployed first so we have baseline
- [ ] Sample specs pre-validated on all 3 providers (LOW cost so we can burn a few dollars testing)
- [ ] Decide: 1-click auto-approve, or 2-second preview-then-approve? (recommend 2-sec with skip option)
- [ ] Decide: sandbox workspace Cleanup after 24h idle or after first `run_complete + 1h`? (recommend 24h idle for flexibility)
- [ ] Legal: Terms + Privacy copy mentions sandbox workspaces are disposable
