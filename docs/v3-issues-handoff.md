# v3-issues handoff for fresh thread (queued-work mode · 2026-04-28)

## TL;DR

The previous thread shipped three batches end-to-end (4-concern fix
batch + R-190/R-191 post-cutover cleanup + BETA LIVE pill nudge
+ anon-submit /login redirect) and diagnosed one failed run.

**A new branch `feature/v3-platform-expansion` is cut off `cc80c1c4`**
for all queued work. Per founder direction: **no merge to dev unless
explicitly told.** This means the GH Action `deploy.yml` will NOT
auto-fire on every batch — work lands on the feature branch + sits
there until the founder calls deploy.

`origin/dev` and `origin/main` stay aligned at `cc80c1c4` until then.

This file is the new thread's entry point. Read top-to-bottom; queued
work is in §3 (Group A: D-125) + §4 (future-scope: agent builder →
ready-mades → use-cases → CLI → plugins) in that exact order.

---

## 1 · Current state

### Branch + worktree

| Field | Value |
|---|---|
| **Working branch (new thread)** | **`feature/v3-platform-expansion`** ← cut off `cc80c1c4` this turn |
| Worktree path | **`/Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf/`** (already checked out on the new branch) |
| Current branch HEAD | `cc80c1c4` (matches the parent at branch-cut time) |
| `origin/feature/v3-platform-expansion` tip | (set by the handoff-doc commit pushed this turn — see §11 below) |
| `origin/feature/v3-conversion` tip | `cc80c1c44dfc25074db6bac10a126ce44cc7318b` (frozen — last shipped batch's branch; do NOT add to it) |
| `origin/dev` tip | `cc80c1c44dfc25074db6bac10a126ce44cc7318b` (matches the frozen `feature/v3-conversion`) |
| `origin/main` tip | `cc80c1c44dfc25074db6bac10a126ce44cc7318b` (matches dev) |
| Uncommitted edits at branch-cut | **none** (only `src/main/resources/static/trust-pack.pdf` untracked — Vite build artifact, regenerated on every `npm run build`; do not commit) |

### Deployed surfaces (frozen at `cc80c1c4` until founder calls deploy)

| Surface | URL | State |
|---|---|---|
| Backend dev | `https://dev.executespec.ai` | Healthy at `cc80c1c4`. Last deploy GH Action `25050515826` green at T+354s; `/actuator/health` returns `{"status":"UP"}`. **Will not auto-update from new-branch work.** |
| Cloudflare prod | `https://executespec.ai` | Worker `square-bar-e1dc` version `bb2087de-beaa-48b6-934b-776bd4050987`. Standard grep gates all green: hero "AI-written pull requests" present, "for one repo or for twenty" present (3×), 0 stale jargon, only `admin@executespec.ai` email, theme color `#0B0D10`, 5 `/v3/` sitemap entries. **Will not auto-update from new-branch work.** Cloudflare deploys are manual via `wrangler deploy` from the worktree path. |

### Local stack — currently NOT up

The previous thread's local stack (Vite :5173 + JAR :8081 + static :8989) was killed earlier in the session. Bring it up if you need local QA:

```bash
cd /Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -Dspring-boot.run.profiles=local   # backend
npm --prefix frontend run dev   # frontend (different terminal)
```

### Last known R-### used

**`R-320`** (Wave 8 Trust Pack PDF). Next free: **`R-321`**. Use sequential R-3## IDs for new work below.

---

## 2 · Already-decided product calls (don't re-litigate)

| Decision | What | Source |
|---|---|---|
| Tier ladder | 4 tiers: Free / Pro $20/mo / Team $39/seat (min 2) / Enterprise Custom. **Don't reintroduce MAX.** | V141 + Wave 7 |
| Pricing surface | Only `/v3/pricing` is customer-facing. `/admin/model-rates` is internal. | Wave 7 R-314 |
| /login tagline | "Sign in to ship a spec." — **not** "Don't prompt. Specify." | R-191 |
| Vibe UI surface | Deleted (R-190). Backend Vibe-B (V143 ENUM, ProductSurfaceFilter, etc.) intentionally untouched — separate decision required. |
| "What's new" / changelog | Removed (TourBanner unmounted, V3Changelog page deleted). |
| Anonymous-run real LLM dispatch flag | **Stays OFF** unless user explicitly says otherwise. Stub dispatcher serves anonymous trials. |
| SOC 2 ETA copy | **Withdrawn** until a real audit engagement is signed (R-319). Trust page reads "Not yet certified." |
| Email rules | All automated emails TO field = `admin@executespec.ai`. All visible website + in-app emails = `admin@executespec.ai`. SMTP creds: Gmail app password from `src/main/resources/application-dev.yml`. |
| Anon `/v3/landing` submit | Redirects to `/login` directly (skipping anonymous-trial UI). Typed spec stashed in `sessionStorage.v3-anon-spec` for post-signup hydration. |
| Root route `/` for anon | Lands on `/v3/landing` (not `/login`). Authed users keep prior behavior. |

---

## 3 · Phase 1 — Group A queued (D-125 RepoAttachPopover, ALL APPROVED)

User approved every recommendation. Execute as a focused mini-wave on `feature/v3-platform-expansion`.

### Suggested batching

```
Batch 1 (one quick mini-wave — mostly XS-S effort):
  R-321 — Q1bc: PAT inline form + "ask your admin" copy-link in RepoAttachPopover
  R-322 — Q2a:  .executespec/root.yml marker file detection (fall back to repo root)
  R-323 — Q3a:  hard size threshold reject + helpful copy

Batch 2 (own mini-wave — M effort):
  R-324 — Q4c:  finish the multi-repo project flow that V143/V144 half-shipped

Batch 3 (rides on R-324):
  R-325 — Q3d:  "yes attach anyway" big-repo opt-in (per-run override)
```

### R-321 — Q1: Private repo, no org-level GitHub App access

| Field | Value |
|---|---|
| Source | `frontend/src/v3/components/RepoAttachPopover.tsx` docstring §1 + line 392 `TODO(D-125 #1)` |
| Approved option | **(b) PAT inline form** + **(c) "ask your admin" copy-link**, both visible on a single "no access?" panel |
| Deferred | (a) GitHub App org-install flow — needs the App registered first (external dep) |
| Effort | XS (b) + XS (c) — both 1-2 hours total |
| Acceptance | (a) When the popover detects the PAT-list returns 0 repos OR `user.hasGithubPat === false`, render a panel with two side-by-side affordances: a "Paste a PAT" form (inline; same behavior as Settings page; saves to `appUser.githubPat`) and an "Ask your admin to install" pre-filled mailto + a "Copy install link" button. (b) Both are dismissible; once a PAT is saved, the panel collapses. (c) The "(a) GitHub App install" path remains a TODO comment with a link to /v3/integrations as the fallback. |
| Files likely touched | `RepoAttachPopover.tsx`, possibly a new `<NoRepoAccessPanel>` component if it gets large, `lib/api.ts` for the PAT save endpoint (already exists — `validateGithubPat` + `updateGithubPat`) |

### R-322 — Q2: Mono-repo subdirectory

| Field | Value |
|---|---|
| Source | `RepoAttachPopover.tsx` line 174 `TODO(D-125 #2)` + docstring §2 |
| Approved option | **(a) `.executespec/root.yml` marker file detection** + fall back to repo root |
| Effort | S (~half-day) |
| Acceptance | (a) On repo attach, the backend reads `.executespec/root.yml` from the default branch via GitHub Contents API. (b) If present, parses `root: <subdir>` field and uses that as the project root for codemap + clone operations. (c) If absent or unparseable, falls back to repo root (current behavior). (d) The marker is documented in the README + a comment on `ProjectRepo.subdir` (new column) explaining the convention. |
| Files likely touched | New `Vxxx__project_repo_subdir.sql` migration adding `project_repo.subdir` column, `ProjectRepo.java` entity, `GitHubRepoService.java` to read the marker, `WorkspaceInitService.java` to use the subdir on clone, frontend `RepoCard.tsx` shows the subdir if non-null |
| Dovetail | This pairs naturally with R-325 (sparse-checkout for big repos) |

### R-323 — Q3: Repo-too-big quota gate

| Field | Value |
|---|---|
| Source | `RepoAttachPopover.tsx` docstring §3 |
| Approved option | **(a) hard size limit + reject with helpful copy** NOW. **(d) "yes attach anyway" opt-in** layered on R-324 multi-repo work (deferred). |
| Effort | XS (a) — read repo `size` from GitHub repo-meta API + reject if > threshold |
| Acceptance | (a) On repo attach, if GitHub returns `repo.size_kb > 250_000` (250 MB clone equivalent), reject with copy: "This repo is too large for the platform's clone-time budget. Try attaching a smaller repo, or ping `admin@executespec.ai` to discuss large-repo support." (b) Threshold is config-tunable via `application.yml` `executespec.repo.max-size-kb`. (c) Per-tier overrides land later as part of R-325. |
| Files likely touched | `GitHubRepoService.java` size check, `ProjectRepoService.java` reject path, `application.yml` config, frontend error rendering in `RepoAttachPopover.tsx` |

### R-324 — Q4: Finish multi-repo project flow (own mini-wave, M effort)

| Field | Value |
|---|---|
| Source | V143 (`product_split.sql`) + V144 (`reinstate_per_seat_team_tier.sql`) shipped half the schema; the UX completion was deferred. |
| Approved option | **(c) finish the multi-repo project flow that V143/V144 already half-shipped** |
| Effort | **M** — own mini-wave, expect per-3-checkpoint commit cadence |
| Why high-leverage | Once it lands, **R-325 (per-run repo override) becomes free** (b) and (d) options from the original Q4 audit become trivial UI add-ons; the entire 2.4.0 multi-repo arc closes. |
| Acceptance (audit-first) | New thread should audit V143 + V144 + the existing `ExecutionSolution` per-repo entity + `ExecutionRun.target_repos` array + the partial `MultiRepoExecutionService` (if exists) before scoping. Surface the audit + propose a wave plan. **Do not start the wave without user-confirmed scope.** |

### R-325 — Q3 layer: "yes attach anyway" big-repo opt-in (rides on R-324)

| Field | Value |
|---|---|
| Approved option | **(d) big-repo opt-in toggle** — "Yes, attach anyway (will count more credits)" button |
| Why deferred | The opt-in needs per-run cost-cap awareness which the multi-repo work in R-324 will provide (since multi-repo runs already have to negotiate per-repo cost). |
| Acceptance | After R-324 lands: when R-323's threshold-reject fires, surface an opt-in button that bypasses the gate but adds a per-repo credit surcharge tracked in `RunCostLog`. |

---

## 4 · Phase 2 — Future-scope queue (in this exact order)

User directive: queue these in priority order. New thread executes them one-by-one, **audit-first** then **scope confirmation** then **wave execution**.

Memory file: `executespec_post_ux_scope.md` in the user's persistent memory.

### #1 — Agent builder

**Description:** Net-new product capability. Probably a "compose your own agent" UI surface that lets users define custom agent personalities + tool sets + prompt fragments.

**Audit prep (read these first):**
- `src/main/java/com/navneet/executespec/v2/agent/PlannerAgentV2.java` (the canonical agent shape)
- `src/main/java/com/navneet/executespec/v2/agent/WorkerAgentV2.java`
- `src/main/java/com/navneet/executespec/v2/tool/ToolRegistryV2.java` (tool plumbing)
- `src/main/resources/prompts/v2/*.md` (agent prompts)
- `src/main/resources/application.yml` `model-matrix:` (provider × tier mapping)
- `agent-tier-map:` config — agent → tier mapping

**Suggested wave plan (TBD by audit):**
- New `AgentDefinition` entity + table for user-defined agents
- A `prompts/user/<userId>/<agentName>.md` file pattern (or DB-backed)
- Tool-set picker UI on a new `/v3/agents/builder` route
- Wire user-defined agents into the WorkerAgentV2 dispatch path
- Tier-gated: agent-builder availability per tier (probably TEAM+ENTERPRISE only)

### #2 — Readymade solutions

**Description:** Pre-built solution templates. Gallery surface — "Build a Stripe checkout page", "Add OAuth to my Next.js app", etc. Fastest way for a new user to ship value.

**Likely surfaces:** `/v3/landing` chip section, `/v3/p/empty` (the V3EmptyProject component shipped in R-310), and a new `/v3/templates` gallery page.

**Audit prep:**
- `frontend/src/v3/pages/V3Landing.tsx` SAMPLE_PROMPTS (already a 6-item list of canned specs — readymades are a richer version of this)
- `project/template/ProjectSpecTemplate.java` (V102 introduced spec templates — readymades may extend this entity)
- `prompts/scaffolds/*.md` (per-project-type scaffolds — readymades may BE a scaffold + a spec pre-bundle)

**Decision-needed:** is a readymade just (template prompt + project-type scaffold + tier-gated)? Or does it need a richer model (e.g. tagged + searchable + hosted gallery + user-contributed)?

### #3 — Most common use cases pre-built

**Description:** Likely overlaps with #2 but with a different framing. Where #2 is "solution-first" (here's a Stripe checkout page), #3 is "use-case-first" ("I want to add user authentication" → here are 3 readymades for that use case).

**Surface candidates:** A taxonomy layer over the readymade gallery. `/v3/templates?usecase=auth` etc. Or a use-case-pickerion surface on `/v3/p/empty`.

**Decision-needed:** scope-merge with #2, or keep separate? If merge: this becomes a navigation/categorization layer over readymades. If separate: this is a new content surface with different metadata.

### #4 — CLI

**Description:** Command-line interface. `executespec` binary. Authenticate, submit specs, watch runs, download workspaces.

**Distribution:** npm or Homebrew (both likely; npm for cross-platform JS devs, Homebrew for macOS shell). Probably a small Node.js or Go binary that wraps the existing `/api/runs` REST endpoints.

**Repository:** Probably a new repo (`executespec-cli`) for clean separation. Could also live under `cli/` in the main repo. Decision-needed.

**Audit prep:**
- `src/main/java/com/navneet/executespec/api/controller/RunController.java` (existing REST endpoints the CLI will call)
- `src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java` (CLI needs a token-based auth path; probably reuses the JWT flow or adds a `cli-token` scope)
- `frontend/src/lib/api.ts` (canonical API shape — CLI mirrors this)
- `docs/plans/cli-plan.md` if exists (Wave 6 stub doc was placeholder; check current state)

### #5 — Plugins

**Description:** Plugin SDK + first-party plugin examples. Probably extends agent capabilities (custom tools / model providers / linters / scaffolds).

**Likely architecture:** Plugin = a typed Java/Kotlin module that registers itself with `ToolRegistryV2` (for new tools) or `LlmRouterV2` (for new model providers) or `ReviewerAgentV3` (for custom validators). Plus a marketplace surface on `/v3/plugins`.

**Audit prep:**
- `ToolRegistryV2.java` registration pattern (current Tool contract is `v2/tool/Tool.java`)
- `LlmRouterV2.java` provider dispatch (current providers: anthropic / openai / gemini)
- `ReviewerAgentV3.java` review-pass plugin points

**Decision-needed:** first-party-only OR third-party-allowed? If third-party: need sandboxing + signed-bundle review + a plugin-store schema. Big scope.

---

## 5 · Standing rules + invariants

### ⚠️ Branch + deploy rules CHANGED for this thread

- **Working branch:** **`feature/v3-platform-expansion`** (NOT `feature/v3-conversion` — that one is frozen at `cc80c1c4`).
- **No merge to dev unless founder explicitly says so.** Founder direction 2026-04-28: *"all development will be in a new branch and no merge in dev unless i say so."*
- **No automatic FF dev → push dev** pattern. Work lands on the feature branch + pushes to origin so it survives + is reviewable, but:
  - **GH Action `deploy.yml` will NOT fire** (it triggers on push to `dev`, not feature-branch push).
  - **`https://dev.executespec.ai` will NOT auto-update** with this thread's work. It stays at `cc80c1c4` until founder calls deploy.
  - **Cloudflare deploys are also gated** — `wrangler deploy` only on founder direction.
- When founder calls deploy: at that point, FF `dev` from `feature/v3-platform-expansion` (whatever its tip is then), push, watch GH Action, post-deploy verify. Same playbook as before — just deferred.

### Carry-over invariants (unchanged)

- **No PR opens.** Push enabled to the feature branch.
- **Cohabitation invariant:** keep new work under `frontend/src/v3/` etc. v2 untouched **except** sanctioned shared-source edits (per the strict-UX handoff): `EventName.java` (additive enum), `SecurityConfig.java` (one `permitAll`), `V2RunOrchestrator.java` (R-162 referral wire-point), `ProtectedRoute.tsx` (concern 1 root-route special-case), and the R-190/R-191 v2 cleanup files (post-cutover sanctioned). Any new v2 edit needs commit-body justification.
- **Per-3-items checkpoint:** commit + push to `feature/v3-platform-expansion` + dispatch update. Per-wave-boundary checkpoint also.
- **Each commit:** `feat(v3):` / `fix(v3):` / `chore(v3):` + R-### in subject + co-author footer.
- **Verify before push:**
  - `tsc --noEmit` after frontend changes
  - `mvn -q -DskipTests compile` after backend changes
  - Full `mvn test` between batches (NOT skipped — `AsyncAllowlistTest` is sensitive to new `@Async` methods + must be in sync; failure cascades the GH Action deploy WHEN the deploy eventually fires)
- **Stop only for:** real product decision the user must make / item turns out already done / red tests / hard blocker.

### Deploy protocol (when founder calls it)

```
# At deploy time, NOT after every batch:
git push origin feature/v3-platform-expansion       # already done per checkpoint
git push origin feature/v3-platform-expansion:dev   # FF dev — NOW triggers deploy.yml
gh run list --branch dev --limit 1                  # grab the run id
# Monitor stage transitions through to step 19 Health check
# Run post-deploy verification on dev.executespec.ai
git fetch origin && git push origin origin/dev:main # FF main (per founder direction this session)
# Cloudflare website (separate, manual):
cd /Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf/website
npx wrangler deploy
```

Health-check step 19 sometimes returns 503 mid-cutover for ~30 s window (Spring context init time on this branch's bean count). Self-resolves on retry. Don't panic.

---

## 6 · Stack URLs / creds

| Surface | URL | Notes |
|---|---|---|
| Local backend | `http://localhost:8081` | Spring profile `local` — `mvn spring-boot:run` |
| Local frontend | `http://localhost:5173` (Vite default) | `npm --prefix frontend run dev` |
| Local static website | `http://localhost:8989` | Optional |
| Deployed dev | `https://dev.executespec.ai` | Frozen at `cc80c1c4`. Only updates when founder calls deploy. |
| Production website | `https://executespec.ai` | Cloudflare Worker `square-bar-e1dc` version `bb2087de`. Frozen until founder calls deploy. |

### Test users

| Email | Password | Tier | Where |
|---|---|---|---|
| `admin@executespec.ai` | **`REDACTED_FOR_PUBLIC_RELEASE`** | ENTERPRISE / SUPER_ADMIN | **dev.executespec.ai (note: 3 not 4)** |
| `admin@executespec.ai` | **`REDACTED_FOR_PUBLIC_RELEASE4`** | ENTERPRISE / SUPER_ADMIN | LOCAL stack only — different password! |
| `freshux-{free,pro,team,enterprise}@test.local` | `FreshUx@1234!` | various | local-stack tier-test users; may or may not exist on dev |

---

## 7 · Memory references

In `/Users/navneetprabhakar/Library/Application Support/Claude/.../memory/`:

- **`executespec_post_ux_scope.md`** — the future-scope queue (now being acted on per §4 above)
- **`executespec_email_recipient.md`** — both email rules (TO field + visible website)

---

## 8 · Known parked items — do NOT touch unless asked

| Item | Why parked | Re-open trigger |
|---|---|---|
| **R-204** Hero video on `/v3/landing` | Real-asset task — needs a 30-second screen-recording captured | Drop `hero-demo.mp4` (≤8 MB H.264) + `hero-demo-poster.jpg` in `frontend/public/` + flip `USE_REAL_VIDEO` constant in `V3Landing.tsx`. Reminder set for 2026-05-05. |
| **R-313** Real customer logos on TrustedByStrip | Need signed customer permission + logo SVG/PNG | 5-min config edit in `V3Landing.tsx` |
| **R-318** Real Cal.com URL for Enterprise demo CTA | Cal.com account setup needed | 1-line href swap on `PricingGrid.tsx` |
| **R-319** SOC 2 ETA copy on `/v3/trust` | Withdrawn 2026-04-28 — "Not yet certified" copy is live | When a real audit engagement is signed |
| **SSO config screen** on `/v3/team` | V127 schema in place; UI deferred until live IdP for round-trip QA (Okta / Auth0 tenant) | Live IdP test tenant + ~30-min click-through QA |
| **R-141** GST tab on `/v3/billing` | Needs `org.country` field on `/api/v3/entitlements`; backend has no country field today | Decide country source (Stripe / self-declare / Geo-IP) + 1-row migration + 5-line entitlements DTO patch |
| **Anonymous-run real LLM dispatch flag** (`v3.anonymous-trial.real-dispatch`) | Stays OFF; cost-cap design pending | Founder greenlight + aggregate-counter shipping first (S effort, ~30 min) |
| **R-190 / R-191 backend Vibe-B** (V143 product_split ENUM, ProductSurfaceFilter, etc.) | UI Vibe-A deleted; backend is a different system using same English word | Separate decision — would require Flyway migration + ENUM rename + route-mapping rewrite |
| **Slice-5 auto-replay** for orphan runs (deferred platform fix) | Runs killed by JVM restarts mid-execution land FAILED + need user resubmit. Real fix is `runLargePipeline.continueExpansion(...)` on JVM boot, skipping EXPANDED tasks. | Eng decision to commit the slice-5 work |

---

## 9 · Recent gotchas / state-at-handoff

### Failed run worth checking on dev: `c0fe9c62-bde8-4b0e-a29c-51ed90e5e7a7`

A user-submitted "Medical Clinic Management System" run was killed at 08:51:19 UTC by the morning's 4-concern deploy's JVM restart (08:51:18 UTC). The orphan-run resumer marked it FAILED with the proper failureReason. **User was charged 7 credits ($35) at plan-approval; actual spend ~$0.18**. Confirm `RunQuotaRefundListener.onRunFailed` actually fired and the credits were returned. If not, that's a separate bug worth surfacing.

### Parent repo branch state

`/Users/navneetprabhakar/Documents/antigravity/executespec/` is on branch `dev` at tip `cc80c1c4`. Earlier this morning it was on `feature/event-tracking-jsonb-fix` which caused a stale Cloudflare deploy. Always deploy Cloudflare from the **worktree** path to be safe.

### Cloudflare / website redeploy pattern (when founder calls it)

```bash
cd /Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf/website
npx wrangler deploy
```

Wrangler is OAuth-authenticated on this machine for `admin@executespec.ai` with `workers:write`. No further setup needed.

### Vite asset artifacts

- `src/main/resources/static/index.html` — Vite re-bundles this on every `npm run build`. The asset hash bumps. **Don't commit those refreshes** — they're auto-regenerated on every backend deploy.
- `src/main/resources/static/trust-pack.pdf` — Vite copies from `frontend/public/trust-pack.pdf`. Untracked; ignore.
- `src/main/resources/static/assets/index-*.js`, `index-*.css` — content-hashed, regenerated each build.

When committing batches, **stage source files explicitly** (`git add frontend/src/...`) instead of `git add -A` to avoid sweeping in build artifacts.

### Docs hygiene rule from CLAUDE.md

Every commit should keep documentation in lockstep with code. Before staging files for any commit:

1. Root `CLAUDE.md` — update if architecture, API contracts, file locations, agent tiers, or in-flight status changed
2. Per-package `CLAUDE.md` — update if entities / repository methods / agents / migrations changed in that package
3. `docs/CODEMAP.md` — refresh on new top-level packages / entities / agents / DB migrations
4. `docs/document_map.md` — refresh on every commit (it's the doc index)
5. Architecture docs under `docs/` — per-feature plans get added/moved as work lands

If a commit is genuinely doc-trivial: explicit "skip doc-refresh: trivial" in the commit message.

### AsyncAllowlistTest sensitivity

Every new `@Async` method in `src/main/java/...` must be added to `src/test/java/com/navneet/executespec/arch/AsyncAllowlistTest.java::APPROVED`. Failure to do so cascades the GH Action `deploy.yml` build at step 6 "Build JAR (run unit tests)" — *which will only matter when founder eventually calls deploy*. Catch it locally with `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q test -Dtest=AsyncAllowlistTest` before pushing.

### What might NOT have been caught by R-190/R-191

The previous thread shipped fast across many surfaces. Possible residuals worth a defensive grep before any new wave:

```bash
grep -rEi "vibe|mission|don't prompt|specify\." frontend/src/ src/main/java/ 2>/dev/null
```

Any user-visible "Vibe" / "Mission" hits should be 0 (R-190 + R-191 covered them). Any "Don't prompt" / "Specify." should be 0 (R-191 covered). If new ones appear, sweep again with the same playbook.

### Audit-the-tier-mapping reminder for Phase 2 #1 (agent builder)

The agent builder will likely need new agent-tier mappings. Reading `application.yml` `agent-tier-map:` + every entry in `prompts/v2/*.md` is essential prep — the platform's current agent contract (each agent declares a tier, the LlmRouterV2 resolves the tier × provider matrix) is the foundation user-defined agents will plug into.

### Project-wide CLAUDE.md is the canonical doc

Read `/Users/navneetprabhakar/Documents/antigravity/executespec/CLAUDE.md` before scoping any of the Phase 2 items. It's the single source of truth on architecture, agent tiers, tier matrix, security layers, and the v2 vs v2.5 vs v2.6 layering. Per-package `CLAUDE.md` files (e.g. `state/CLAUDE.md`, `router/CLAUDE.md`) cover the local invariants.

---

## 10 · How to use this doc

1. Read §1 (current state) + §2 (decided product calls) before touching anything.
2. Read §5 (standing rules) carefully — **the branch + deploy rules CHANGED**: no merge to dev without founder direction.
3. Pick from §3 (Group A, ready to execute) or §4 (future-scope, audit-first).
4. Check §8 for parked items — do NOT incidentally touch those.
5. Follow §6 (creds) for execution.
6. Update this doc at every wave boundary so the next handoff is clean.

---

## 11 · This handoff commit

This file was committed at the cut of `feature/v3-platform-expansion` from `cc80c1c4`. The commit's tip SHA on `origin/feature/v3-platform-expansion` is in the dispatch reply for this turn.

---

**You're cleared. The new branch is cut, the docs are honest, the queue is in priority order. Pick R-321 and start.**
