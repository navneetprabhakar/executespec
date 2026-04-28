# v3-issues handoff for fresh thread (queued-work mode · 2026-04-28)

## TL;DR

This thread shipped **Phase 1 Group A** (D-125 RepoAttachPopover
finishings) end-to-end across 5 commits on
`feature/v3-platform-expansion`.  R-321 → R-325 are all in.
**Phase 1.5 (R-326 doc-tree refresh)** is in flight — Cluster A (root
docs + this handoff) lands first; clusters B (docs/), C (docs/plans/),
and D (per-package CLAUDE.md) come next per the per-cluster commit
cadence the user requested.  **Phase 2** (agent builder → ready-mades
→ use cases → CLI → plugins) is unchanged: audit-first, scope to user,
execute on go.

Per founder direction: **no merge to dev unless explicitly told.**
The GH Action `deploy.yml` will NOT auto-fire on every batch — work
lands on the feature branch + sits there until the founder calls
deploy.

`origin/dev` and `origin/main` stay aligned at `cc80c1c4` until then.

This file is the entry point for the next thread. Read top-to-bottom;
queued work is in §3 (status of Group A — now SHIPPED) + §3.5
(Phase 1.5 doc-refresh) + §4 (Phase 2 future-scope queue) in that
exact order.

---

## 1 · Current state

### Branch + worktree

| Field | Value |
|---|---|
| **Working branch** | **`feature/v3-platform-expansion`** ← cut off `cc80c1c4` |
| Worktree path | **`/Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf/`** |
| Current branch HEAD (post Phase 1 Group A) | `6bf09fc3` (R-325 big-repo opt-in commit; pushed) |
| `origin/feature/v3-platform-expansion` tip | `6bf09fc3` until the next push |
| `origin/feature/v3-conversion` tip | `cc80c1c44dfc25074db6bac10a126ce44cc7318b` (frozen — do NOT add to it) |
| `origin/dev` tip | `cc80c1c44dfc25074db6bac10a126ce44cc7318b` (matches the frozen `feature/v3-conversion`) |
| `origin/main` tip | `cc80c1c44dfc25074db6bac10a126ce44cc7318b` (matches dev) |
| Phase 1 Group A commit trail (this thread) | `dbfaa649` R-321/R-322/R-323 · `9e83ff05` R-324 ckpt 1 · `fe976dcd` R-324 ckpt 2 · `8e86599c` R-324 ckpt 3 · `6bf09fc3` R-325 |
| Uncommitted edits | **none** between batches; only `src/main/resources/static/trust-pack.pdf` may appear untracked — Vite build artifact, do not commit |

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

**`R-326`** (Phase 1.5 doc-tree refresh, in flight at handoff time —
Cluster A pushed; clusters B/C/D pending). Next free: **`R-327`**.
Use sequential R-3## IDs for new work below.

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

## 3 · Phase 1 — Group A SHIPPED (D-125 RepoAttachPopover finishings)

All five items shipped on `feature/v3-platform-expansion` between
`a6f58d88` and `6bf09fc3` this thread.  Commit trail per item:

```
R-321 (Q1bc) — PAT inline form + "ask your admin" copy-link
R-322 (Q2a)  — .executespec/root.yml marker file detection
R-323 (Q3a)  — hard size threshold reject + helpful copy
              ↓ Batch 1: dbfaa649

R-324 (Q4c)  — multi-repo project flow finish (3 checkpoints)
              ↓ Ckpt 1 schema + planner scope:    9e83ff05
              ↓ Ckpt 2 frontend "Attach another": fe976dcd
              ↓ Ckpt 3 codemap-filter regression: 8e86599c

R-325 (Q3d)  — big-repo "Attach anyway" opt-in
              ↓ 6bf09fc3
```

### Important audit correction

The pre-thread handoff claimed V143/V144 "half-shipped" the multi-repo
flow. **That was wrong.** V143 is the product-split ENUM
('vibe'/'executespec') + `app_user_product_access` join table; V144
is per-seat TEAM tier reinstatement. Neither touches multi-repo.

The actual multi-repo machinery was substantially built across the
existing codebase (ExecutionSolution.repoMappings, V2RunOrchestrator
multi-clone, ExecutionPlannerService per-repo decomposition); the gap
was that `req.repoIds()` was being dropped on the floor by the
orchestrator. R-324 added V155 `execution_run.selected_repo_ids` JSONB
column + serializer + planner-side filter so the user's selection now
actually drives planning.

### What's now live in the popover

| Surface | Behaviour |
|---|---|
| No PAT panel | inline PAT input + Test/Save + ask-admin (mailto + copy-link) + tertiary `/v3/integrations` link |
| Monorepo subdir | detected from `.executespec/root.yml` on attach + sync; chip on RepoCard shows `root: <subdir>` |
| Repo size | hard reject above `executespec.repo.max-size-kb` (default 250 MB); the 413 message names the threshold + opt-in path |
| Big-repo opt-in | "Attach anyway (counts more credits)" CTA on the size-gate error; persists `attached_anyway` + `size_kb` on the row; chip on RepoCard surfaces "big repo · counts more credits" |
| Existing-project attach | new "+ Attach repo" button on V3ProjectHome pulse row; popover invokes `onAttached` → re-loads the project store in place |

### Deferred (intentionally) from R-321..R-325

- **Q1 (a)** GitHub App org-install flow — needs the App registered first.
- **Per-run credit surcharge formula** when any selected repo has
  `attached_anyway=TRUE`. Tracked as a TODO in the cost-tracker code
  path; should land alongside the next pricing pass.

> The per-item scope tables for R-321 → R-325 (the original
> approval-time spec) lived here and have been retired now that the
> work is shipped.  The commit messages on each commit hash above
> capture the as-built scope; CLAUDE.md + USER_MANUAL.md +
> per-package CLAUDE.md files capture the runtime contract.

---

## 3.5 · Phase 1.5 — R-326 doc-tree refresh (in flight)

User directive 2026-04-28: "before starting Phase 2, update all
documents in the project including docs/ and docs/plans/" so the
written record matches the live code post-D-125 + post-Phase-1
Group A.

### Cluster cadence (one commit per cluster)

| Cluster | Scope | Status |
|---|---|---|
| **A** — Root + handoff | `/CLAUDE.md`, `/README.md`, `/CHANGELOG.md`, this handoff doc | shipped this commit |
| **B** — `docs/` user-facing + architecture | ARCHITECTURE / CODEMAP / DEPLOYMENT / JAVADOC / PRD / RBAC / USER_MANUAL / WORKFLOWS / document_map / frontend-api-usage | next |
| **C** — `docs/plans/` | classify every plan SHIPPED / SUPERSEDED / STILL-PENDING; rewrite stale "we will do X" claims | next |
| **D** — per-package CLAUDE.md sweep | `frontend/CLAUDE.md`, `src/main/java/.../*/CLAUDE.md` | next |

### Cluster A (this commit) — what changed

- `README.md` line 3: "Premium feature: ENTERPRISE + MAX tiers" →
  "ENTERPRISE + TEAM tiers" (V141 renamed MAX → TEAM on dev/main; the
  per-seat TEAM + MAX co-existence only lives on the un-merged
  `feature/product-split-prep` branch).
- `CLAUDE.md` line 108: same MAX → TEAM rename note.
- `docs/v3-issues-handoff.md`: full refresh — Phase 1 Group A marked
  SHIPPED with commit trail; Phase 1.5 R-326 added as the new
  in-flight section; per-question scope tables retired since the
  approval-time spec is now obsolete.

### Why the audit was tighter than expected

The pre-R-326 audit found the documentation surface remarkably clean.
The R-190 + R-191 cleanup pass already retired the user-facing Vibe-A
/ Mission / "Don't prompt. Specify." vocabulary; the v2.7 changelog
captured what landed; the README plans/index already classified every
`docs/plans/` file accurately.  The remaining stale claims are the
two MAX → TEAM tier-name drifts above, plus this handoff doc itself.

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
