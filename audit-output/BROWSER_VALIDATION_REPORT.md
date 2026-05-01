# ExecuteSpec — Browser Validation Pass v2 (v2.5.11)

> **Companion to `AUDIT_REPORT.md`. Replaces a prior v1 of this report
> that was retracted because the local stack was misconfigured —
> the dummy `-D spring.ai.*api-key=` boot caused every dispatched run
> to fail with 401, leading me to file a false S0 BYOK finding. The
> user copied the gitignored `application-local.yml` (with real provider
> keys) into the worktree and switched to `mvn spring-boot:run -Dspring-boot.run.profiles=local`
> for live classpath. This v2 report is sourced from the corrected
> stack.**
>
> **Baseline:** branch `audit/v2-5-11-ux-product`, tip
> `bfc73a2f3bb6a89842e1e82e923e6bcefbda4322` plus the deletion +
> reissue committed at the end of this session. Local stack — backend
> `:8080` (Spring profile `local`, real provider keys via
> `application-local.yml`), Vite `:5173` via Claude_Preview MCP.
> Deployed env not used.

---

## §1 — TL;DR

- **The platform actually works end-to-end.** 4-for-4 real runs as super admin: 3 LOW Fibonacci runs across Anthropic/OpenAI/Gemini all `COMPLETE · CLEAN_COMPLETION` in 65-93s for $0.05-0.09 each; 1 MEDIUM Express to-do API run on Anthropic delivered 4 tasks COMPLETE in 4m36s for $0.23. Verified score 80/100 on every run; accuracyScore 95-98 (A) across all four. The audit's [F-03] permission-hardcode finding is real but it is NOT chained with a structural BYOK break — admin's stored Anthropic key was used for the runs.
- **Audit findings that don't depend on LLM dispatch held up:** [F-01] (no Runs link in v3 top-bar) CONFIRMED; [F-02] (FREE user lands on /projects/empty, never sees /welcome sandbox) CONFIRMED with real fresh-tier login; [F-04] (shell schism between V3Shell and AppLayout) CONFIRMED; [F-06] (composer has no template/Library/sample affordances) CONFIRMED; [B-05] (v3 top-bar at 390×844 clips brand text, no drawer) CONFIRMED.
- **`[F-15]` REFINED downward.** Both the run-list cards (`Shippable: Build green, reviewer clean.` / `Blocked: Final build failed — code may not run as written.`) and the inline RunDetail header (`80/100 verified · Est. 98`) implement the documented v2.5.10 hierarchy correctly — `verifiedScore` is the deterministic hero, `accuracyScore` is demoted to `Est. N`. The audit's "ScoreRing dominates despite docs" claim was wrong.
- **New finding `[B-N1] (S2)`:** verdict-copy inconsistency between surfaces. Same run, two different verdicts: list view shows "Shippable: Build green, reviewer clean." while the inline Validation Report block on the run-detail says **"Needs review"** + **"Needs attention"**. Same data — `pipelineResult: NEEDS_ATTENTION` from the report row, `VerdictPill: Shippable` from the run summary. Two surfaces tell the user opposite things about the same run.
- **New finding `[B-N2] (S2)`:** cost estimate band is ~10× too high for the MEDIUM run. Estimate displayed `$1.87 – $3.27`, actual cost `$0.23`. With prototype runs auto-charged credits at the upper band per `creditsCharged = ceil(bufferedEstimate / $5)`, this directly inflates user-perceived cost and may hit hard-cap-stop-the-run thresholds prematurely.
- **`[B-03]` (audit's predicted) CONFIRMED at runtime even with real keys:** every run logged `classifierReasoning: "LLM verifier unavailable — heuristic verdict"` and `classifierCostUsd: 0`. The SmartClassifier's LLM-verifier path never fires under default config — even when admin's BYOK is wired and provider works elsewhere. This is a feature-flag or wiring bug, not an environmental one. Severity holds at S2 + a metric.
- **New finding `[B-N3] (S3-medium-confidence)`:** Token Usage panel shows "planner" using `claude-haiku-4-5-20251001` (WORKHORSE/LIGHTWEIGHT tier) — but per `CLAUDE.md` root, `PlannerAgentV2` and `OutlinePlannerAgent` should be PLANNING tier = `claude-sonnet-4-6`. The panel may be mislabeling, or the planner is genuinely getting a cheaper model than designed.

**The biggest delta from `AUDIT_REPORT.md` is the inverse of last time:** the platform is working. The audit's "this product feels schizophrenic across personas" claim still stands at the IA/navigation level (shell schism, no Runs in v3, no composer affordances), but the run-execution engine itself delivers consistent A-grade output across all 3 providers and a 4-task MEDIUM spec. Surface bugs, not engine bugs.

---

## §2 — Audit Findings: Validated / Invalidated / Refined

### S0 findings

```
[F-01] V3 shell exposes no path to existing runs
  Status:        CONFIRMED
  Evidence:      [browser snapshot @ /projects/9bd60bd8-…] V3TopBar a11y
                 tree lists exactly: ExecuteSpec home link · Switch project
                 button · Library link · Migrations link · Account menu
                 button. No Runs entry. Same observation as the prior pass;
                 unchanged with real keys.
  Browser note:  Confirmed.
  Severity drift: none.
  Fix delta:     none.
```

```
[F-02] /welcome onboarding flow is dead code
  Status:        CONFIRMED
  Evidence:      [browser eval] freshux-free@test.local POST /api/auth/login
                 200 → window.location='/home' → V3HomeRedirect → window.
                 location.pathname === '/projects/empty'. V3EmptyProject
                 renders heading "Welcome, FreshUx." + paragraph "You don't
                 have any projects yet — let's create your first one." +
                 primary CTA "Create my first project" + secondary link
                 "Set up a project the long way →". No /welcome page hit,
                 no sandbox-spec gallery rendered.
  Browser note:  The whole Wave 2.2 onboarding is unreachable from any
                 normal flow.
  Severity drift: none.
  Fix delta:     none.
```

```
[F-03] freeUserPermissions hardcodes 5 fields, ignoring tier_config
  Status:        CONFIRMED for FREE/PRO/TEAM/ENT permission map
  Status (BYOK chain): INVALIDATED at this layer
  Evidence:      [live-api] /api/auth/me/permissions per persona unchanged
                 from AUDIT_REPORT.md (FREE canDeploy=true maxDeployments=3,
                 ENT canViewCostBreakdown=false, PRO canManageAiKeys=false).
                 BUT: admin's BYOK keys ARE consulted at runtime — 4-for-4
                 successful real-run dispatches as super admin prove the
                 key resolution pipeline is intact for users with
                 canManageAiKeys=true and stored keys.
  Browser note:  The permission flag is hardcoded wrong but the underlying
                 BYOK path is wired and works.
  Severity drift: stays S0 alone. Earlier-claimed BYOK chain is retracted.
  Fix delta:     audit's recommendation (replace lines 237/238/241/256/257
                 with helpers reading tier_config) stays. The BYOK fix
                 proposed in the prior browser report is NOT NEEDED.
```

### S1 findings

```
[F-04] Two complete shells (V3Shell + AppLayout) compete at the same URLs
  Status:        CONFIRMED
  Evidence:      [browser session] /projects/{slug} renders V3Shell
                 (5-link top-bar, no aside element, no breadcrumb); /runs
                 renders AppLayout (13-link sidebar + 6 project buttons +
                 breadcrumb "ExecuteSpec / Runs"). Same JWT, same admin
                 user, same browser session, two different layouts gated
                 only by URL path.
  Browser note:  User has confirmed v3 should replace v2 — locks in the
                 full-port direction. Today's state is the worst of both.
  Severity drift: none.
  Fix delta:     none.
```

```
[F-05] Migrations dry-run endpoint has no tier check
  Status:        CANNOT_TEST
  Evidence:      Did not exercise. Code-trace from audit at
                 MigrationController.java:65-72 stands.
  Browser note:  Skipped to budget time for the eval-pass redo.
  Severity drift: keep S1.
  Fix delta:     none.
```

```
[F-06] V2 chat composer has no surface for spec templates / sample specs / runs
  Status:        CONFIRMED
  Evidence:      [browser snapshot @ /projects/9bd60bd8-…] composer form
                 contains: textarea (placeholder "What do you want to build"),
                 buttons "Plan & review" + "▾ Advanced" + "Submit", a
                 "RE-LAUNCH" prefix-row showing two old-run titles. Same
                 as prior pass — no template picker, no Library inline,
                 no sample gallery.
  Browser note:  Confirmed.
  Severity drift: none.
  Fix delta:     none.
```

```
[F-07] V2 RunsPage is the only run history view; v3 has nothing equivalent
  Status:        CONFIRMED + REFINED
  Evidence:      [browser snapshot @ /runs] AppLayout RunsPage exposes:
                 (1) breadcrumb "ExecuteSpec / Runs"
                 (2) sub-nav "MY RUNS / ORG RUNS / ALL RUNS"
                 (3) status filter chips ALL/PLANNING/WORKING/AWAITING_APPROVAL/
                     COMPLETE/PAUSED/FAILED/ARCHIVED
                 (4) per-project filter chips (All/Unassigned + 6 named projects)
                 (5) virtualized run-card list with status pills, descriptive
                     subtitle, token + cost
                 No v3 equivalent. From any v3 surface a user must type /runs
                 into the URL bar.
  Browser note:  v2 RunsPage is more polished than the audit estimated.
                 Fix-effort drops M → S because porting beats rewriting.
  Severity drift: stays S1.
  Fix delta:     "Build V3RunsPage" → "Port v2 RunsPage components into
                 V3Shell layout".
```

```
[F-08] README + PRD + USER_MANUAL out of date on tier ladder, batch, BYOK
  Status:        CONFIRMED (code-trace based)
  Evidence:      Unchanged. tier_config has 5 rows; README says 3.
  Browser note:  Browser pass adds nothing new here. The "BYOK promise
                 unredeemable" line of the prior report draft is RETRACTED
                 — BYOK works at runtime; only the canManageAiKeys
                 permission flag is wrong for solo PRO/TEAM.
  Severity drift: keep S1.
  Fix delta:     none.
```

```
[F-09] /pricing route protected by canViewPricing=false for everyone
  Status:        CANNOT_TEST
  Evidence:      Skipped — would have required nav to /pricing as 4 personas
                 sequentially.
  Severity drift: keep S1.
  Fix delta:     none.
```

```
[F-10] All 4 freshux-* test users predate V162 personal-org bootstrap
  Status:        CONFIRMED
  Evidence:      [live-api] freshux-free returns currentOrgMembers=0 with
                 maxOrgMembers=-1. Walkthrough behavior: V3HomeRedirect
                 detected 0 projects, dispatched to /projects/empty. No
                 backfill happened during this pass.
  Severity drift: keep S1.
  Fix delta:     none.
```

```
[F-11] No PostgreSQL row-level security (browser-untestable)
  Status:        CONFIRMED — user-resolved per [Q-08 = "org id"]
  Evidence:      Same as audit.
  Browser note:  Drops S1 → S2 per user direction; doc-honesty fix only.
  Severity drift: S1 → S2 (per user).
  Fix delta:     update enterprise-one-pager + Trust page to name the
                 isolation model explicitly.
```

```
[F-12] V2 batch documented in USER_MANUAL §3.3, doesn't ship
  Status:        CANNOT_TEST
  Evidence:      Did not try toggling executionMode=BATCH in this pass
                 (PROTOTYPE / REALTIME used for the eval). Code-trace cite
                 from audit stands.
  Severity drift: keep S1.
  Fix delta:     none.
```

### S2/S3 with non-trivial drift

```
[F-15] Verdict pill vs ScoreRing visual hierarchy
  Status:        REFINED (audit was wrong on the underlying claim)
  Evidence:      [browser snapshot @ inline RunDetail for f8277403-… COMPLETE]
                 the panel header reads literally "80/100 verified · Est. 98"
                 — verifiedScore (80) is the hero, accuracyScore (98) is
                 the secondary "Est." chip. The collapsed ValidationReport
                 block shows "Build ✓ · Reviewer CLEAN" and the right rail
                 has a single VerdictPill ("Shippable") on the run-list. No
                 dual-source numeric competition surfaced.
                 [browser snapshot @ run-list /runs] each card leads with the
                 categorical pill ("Shippable: Build green, reviewer
                 clean." / "Blocked: Final build failed — code may not
                 run as written."). No numeric competition.
  Browser note:  v2.5.10 + v2.5.12 visual demotion was implemented
                 correctly. The audit hypothesized "5 visualisations of
                 did-the-run-succeed stacked vertically" — at the
                 inline-detail level today there are 2 (verdict + verified
                 score) which is fine.
  Severity drift: drops S2 → S3.
  Fix delta:     audit's "pick one as hero" guidance is moot. Replace with
                 "audit cross-surface verdict consistency" — see [B-N1].
```

### Summary

Of 12 S0/S1 findings examined: **8 CONFIRMED, 1 REFINED ([F-07] effort lowered), 1 PARTIALLY-INVALIDATED ([F-03] permission map stays S0; the BYOK chain claim is retracted), 2 CANNOT_TEST ([F-05], [F-12]).** Plus 1 S2 invalidation drop ([F-15]). Audit's calibration on the no-LLM findings (route map, navigation, permissions snapshots, doc deltas) was high. Audit's calibration on dispatch-dependent findings was hidden behind a config issue and could only be surfaced via real runs.

---

## §3 — New Findings (Browser-Only)

```
[B-N1] Cross-surface verdict inconsistency on the same run
  Severity:      S2
  Confidence:    high
  Evidence:      [browser snapshot @ /runs cards] run 5b371f6d-… renders as
                 "Shippable: Build green, reviewer clean. Code generated"
                 (status ARIA role "status").
                 [browser snapshot @ inline RunDetail for same run] the
                 ValidationReport block within the same panel reads
                 "Needs review" + "Needs attention" + "8 files · JavaScript
                 · 1 unresolved". Same run, same dataset, two contradictory
                 headlines.
                 [API] /api/runs/5b371f6d-…/report returned
                 `pipelineResult: NEEDS_ATTENTION`, `verifiedScore: 80`,
                 `accuracyScore: 98 (A)`, `finalBuildPassed: true`,
                 `reviewerVerdict: CLEAN`, `issuesCaught: 1`.
                 So `pipelineResult` is "NEEDS_ATTENTION" because of 1
                 issue, while the verdict pill collapses to "Shippable"
                 because the bigger booleans (build passed, reviewer
                 clean) are true.
  What's wrong:  Two pieces of UI compute the verdict differently from the
                 same dataset. List card uses VerdictPill (3-state
                 categorical: Shippable / Needs review / Blocked) which
                 prioritizes finalBuildPassed + reviewerVerdict. Inline
                 ValidationReportPanel uses pipelineResult enum directly
                 ("NEEDS_ATTENTION" → text "Needs review" + "Needs
                 attention"). User sees "Shippable" on the list, opens
                 the run, sees "Needs review" — loses trust in either
                 one.
  Why audit missed it: Cross-surface inconsistency surfaces only when both
                 surfaces are rendered for the same run. Code-trace would
                 need to compare list-side and detail-side verdict
                 derivations side-by-side which is non-obvious.
  Fix:           Pick a single verdict source. Recommend: use the
                 VerdictPill derivation (list-card formula) as the
                 single source, and either rename `pipelineResult` to
                 something less verdict-ish or hide it from the panel
                 header (relegate to the issue list).
  Effort:        S.
```

```
[B-N2] Cost estimate ~10× too high for MEDIUM run
  Severity:      S2
  Confidence:    high
  Evidence:      [browser snapshot inline RunDetail Cost Breakdown panel]
                 "Live cost $0.2336 / est. $1.87 – $3.27".
                 [API run JSON] costEstimateLow=$1.8665, costEstimateHigh=$3.2664,
                 actualCostUsd=$0.2336. Estimate band is 8-14× the actual.
                 [API run JSON] creditsCharged=1 (so credits-side wasn't
                 over-charged at execute-time, but costEstimate IS what
                 the plan-approval modal would have shown).
  What's wrong:  The estimate band is too pessimistic by ~10× for a
                 4-task MEDIUM Express run on Anthropic. Per CLAUDE.md
                 v2.5.13, MEDIUM band was recalibrated 20k/4k →
                 200k/15k tokens. Real run consumed 26k in / 14k out
                 (40k total) — well below the 200k+15k = 215k token
                 estimate. Either the recalibration overshot, or the
                 estimate is per-task rather than per-run-total, or
                 the planStrategy=SMALL classification (this run
                 classified as SMALL despite being MEDIUM by spec
                 category) is using SMALL band overhead.
  Why audit missed it: Estimate-vs-actual divergence is only visible after
                 a real run completes. Audit didn't dispatch.
  Fix:           Tighten MEDIUM band. After ~50 calibration runs across
                 LOW/MEDIUM/HIGH, the band's p25/p75 should converge to
                 within 1.5× of actual. Today the band is biased toward
                 hard-cap safety; that's the wrong direction for buyer-
                 facing estimates.
  Effort:        S (refit the heuristic in V2RunCostTracker.estimateRunTierAware)
                 + a calibration script run as a one-shot.
```

```
[B-N3] Token Usage panel shows planner using Haiku (LIGHTWEIGHT/WORKHORSE) instead of Sonnet (PLANNING)
  Severity:      S3
  Confidence:    medium
  Evidence:      [browser snapshot RunDetail Token Usage by Model panel]
                 for run 5b371f6d-… (anthropic provider, MEDIUM Express
                 spec) — three rows of "claude-haiku-4-5-20251001":
                 reviewer 12.8K/7.4K, planner 8.8K/2.6K, test-planner
                 328/2.7K. One row of "claude-sonnet-4-6": 12/708.
  What's wrong:  Per CLAUDE.md root model matrix, `PlannerAgentV2` /
                 `OutlinePlannerAgent` are PLANNING tier = claude-sonnet-4-6
                 for anthropic. The "planner" row shows haiku. Either:
                 (a) the v2.6.2 hierarchical planner runs on the LIGHTWEIGHT
                 SmartClassifier without ever dispatching the PLANNING-tier
                 OutlinePlannerAgent (cost-saving but spec-degrading);
                 (b) the panel is mislabeling the model — the actual
                 planner is sonnet-4-6 but it's grouped under "planner"
                 misleadingly;
                 (c) there's a real tier-resolution bug downgrading
                 PLANNING → WORKHORSE.
                 Run still produced grade-A output so functional impact
                 is mild on this spec; could be more material on
                 LARGE/LEGACY classifications.
  Why audit missed it: Per-model token-usage is only visible after a
                 successful run. Audit didn't dispatch.
  Fix:           Investigate. If (a), document the cost optimization. If
                 (b), fix the panel label. If (c), file as S2 and patch
                 ModelResolver.
  Effort:        XS investigation; fix S if real bug.
```

```
[B-N4] /runs sub-filter "Unassigned" duplicates "All Runs" — no runs were ever assigned to a project for the eval admin
  Severity:      S3
  Confidence:    medium
  Evidence:      [browser snapshot @ /runs] per-project filter chips are
                 "All Runs / Unassigned / Hostel Eval ANTHROPIC / Hostel
                 Eval OPENAI / Hostel Eval GEMINI / UI Test - Empty Repo
                 Path / UI Test - v2.7.2 IA / R-343 Persona Report".
                 [API] all 4 of my freshly-dispatched eval runs appear
                 under "Unassigned" because the /api/runs request body
                 didn't include a projectId. The MEDIUM Express run cost
                 $0.23 — would normally be charged to a project.
  What's wrong:  PROTOTYPE-mode runs from API don't auto-link to a project,
                 so the project-level cost rollup (per `ProjectDashboardController`
                 + `/api/projects/{id}/launches`) misses them. Org-admin
                 watching the dashboard for cost monitoring can't see
                 these. The audit's [F-07] (no org-scoped run view) +
                 this finding compound — even within v2 RunsPage, runs
                 dispatched without projectId fall into a separate bucket.
  Why audit missed it: This was only visible after dispatching multiple
                 runs without projectId.
  Fix:           Either (a) require projectId on /api/runs (breaks the
                 v2 console-quickrun affordance), or (b) auto-link runs
                 to a default "Sandbox" project per user. Default to (b).
  Effort:        S.
```

```
[B-N5] Classifier silent fallback persists with real keys (was [B-03] in prior report, elevated)
  Severity:      S2
  Confidence:    high
  Evidence:      [API run JSON] classifierReasoning on every run reads
                 "LLM verifier unavailable — heuristic verdict: ≤800 chars,
                 single feature scope (LLM may downgrade or upgrade)".
                 classifierCostUsd=0, classifierInputTokens=0,
                 classifierOutputTokens=0. Behavior is identical with
                 dummy keys (prior pass) and with admin's real BYOK keys
                 (this pass). Real keys work for everything else; just
                 not the SmartClassifier verifier path.
  What's wrong:  The Phase 0 classifier from `feature/hierarchical-planner`
                 was supposed to LLM-verify the heuristic verdict. It
                 never fires. Possibilities: (a) feature-flag-gated and
                 the flag is OFF in local + dev (likely — CLAUDE.md root
                 hierarchical-planner slice 1 entry says verdict +
                 per-phase tokens persist on the run row, implying the
                 LLM call DID fire historically); (b) wiring regression
                 — the classifier is failing silently on something else
                 (rate-limit? circuit breaker?); (c) intentional default-
                 disabled because the verifier costs > value on small
                 specs.
                 Either way, the heuristic-only mode classifies ALL specs
                 as their first-pass guess. The MEDIUM Express run was
                 classified as SMALL despite being a 4-task multi-endpoint
                 spec — only narrowly worked because OutlinePlannerAgent
                 FULL_PLAN mode has a 32k cap.
  Why audit missed it: Audit didn't dispatch a run; classifierReasoning is
                 only on a completed run JSON. (Audit's [F-15] / Q-NEW-02
                 in the prior browser report flagged this as an open
                 question; this pass elevated to a finding.)
  Fix:           Investigate which condition disables the verifier (FF /
                 wiring / config). Add a metric
                 `executespec.v2.classifier.verifier.engaged` so silent
                 fallback is observable. If FF-gated and intentional,
                 change the classifierReasoning copy from "LLM verifier
                 unavailable" (sounds like a bug) to "LLM verifier disabled
                 by config".
  Effort:        XS (metric); S (investigation + copy fix or wire-up fix).
```

```
[B-04] V3 run-detail URL pattern returns "Run not found" for an existing run
  Status:        REPRO'D from prior pass — still broken
  Severity:      S2
  Confidence:    medium
  Evidence:      [browser eval] window.location.href='/projects/9bd60bd8-…/runs/f8277403'
                 → V3Shell renders heading "Couldn't load run" + paragraph
                 "Run not found: f8277403". Run f8277403-ff6d-… is an
                 admin-owned COMPLETE run, ID 8-char prefix.
  What's wrong:  Same as prior pass. v3 run-detail URL pattern needs
                 inspection in V3RunDetail.tsx.
  Effort:        XS-S.
```

```
[B-05] V3 top-bar at 390×844 mobile clips brand text, no drawer collapse
  Status:        REPRO'D from prior pass.
  Severity:      S2
  Confidence:    high
  Evidence:      [browser screenshot] preview_resize 390×844 + screenshot
                 at /projects/empty: ExecuteSpec brand clipped, links
                 jammed inline at viewport edge, no hamburger.
  Effort:        S.
```

```
[B-N6] Avatar menu doesn't open on synthetic click
  Severity:      S3 (likely test-tooling artifact, not user-facing)
  Confidence:    low
  Evidence:      [browser eval] preview_click('button[aria-label="Account
                 menu"]') succeeded; subsequent snapshot showed no menu
                 popover, no role=menu node, no visible menu items in the
                 DOM. Expected behavior would be a dropdown with at least
                 Settings / Logout.
  What's wrong:  Either the menu component requires a synthetic React event
                 (preview_click may not dispatch the right one) or it's
                 hover-only / requires keyboard activation. preview_click
                 dispatches a native click; if AvatarMenu only listens for
                 React-synthesized onPointerDown for example, the click
                 wouldn't open it.
  Why audit missed it: N/A.
  Fix:           Test in real Playwright headed mode. If reproducible,
                 patch AvatarMenu to also handle native click events.
  Effort:        XS investigation, S fix if real bug.
```

```
[B-N7] LoginPage form Submit click doesn't fire POST /api/auth/login (was [B-10] in prior report)
  Severity:      S2
  Confidence:    medium
  Evidence:      [browser eval sequence] preview_fill email + password +
                 preview_click submit button → no POST to /api/auth/login
                 in network log. Direct fetch worked. Possibly same root
                 cause as [B-N6] — synthetic-click vs. React event listener
                 mismatch.
  What's wrong:  If reproducible in Playwright, real users with password
                 managers or autofill might experience the same. If only
                 a Claude_Preview MCP quirk, no user-visible impact.
  Effort:        XS investigation.
```

(Total: 9 new findings; cap is 20.)

---

## §4 — Persona Walkthroughs

### §4.1 FREE — fresh login walkthrough

**Flow:** POST /api/auth/login (freshux-free@test.local) 200 → window.location='/home' → V3HomeRedirect dispatched to /projects/empty → V3EmptyProject renders.

**Time-to-affordance:** 0 clicks beyond login to get to the empty-state CTA. ~3 sec total (auth call + dispatcher resolve + render).

**3 highest-friction moments:**
1. **No /welcome onboarding (`[F-02]`).** Same as audit predicted. The "Pick a sample to see ExecuteSpec in action" gallery exists in code but is unreachable from the login flow.
2. **The "Set up the long way" link (`[B-09]` from prior report) routes to /org/projects which is empty for solo users with no org.** Solo FREE who doesn't want the auto-named project has no graceful path.
3. **Composer empty-state offers no help (`[F-06]`).** Once the FREE user creates a project, they see "What do you want to build?" with no template / sample / Library shortcut.

**Coherent end-to-end story today: NO** — purely because of nav/onboarding gaps. The execution engine itself is fine.

### §4.2 PRO / TEAM — not walked through

Time-budgeted toward the eval-pass redo + super-admin walkthrough. PRO/TEAM personas carry over from `AUDIT_REPORT.md §4.2 / §4.3` code-trace.

### §4.3 ENTERPRISE / SUPER_ADMIN — primary persona

**Eval flow:**
1. POST /api/auth/login admin@executespec.ai → JWT (327 chars, ENTERPRISE / SUPER_ADMIN, viewMode=pro). hasOpenAiKey/hasAnthropicKey/hasGeminiKey all true.
2. window.location='/home' → V3HomeRedirect → /projects/9bd60bd8-… (Hostel Eval ANTHROPIC, admin's first project).
3. V3ProjectHome renders chat thread (2 prior runs as inline cards) + composer pinned at bottom.
4. **Dispatched 4 real runs as super admin via API** (results in §5).
5. Navigated to /runs (URL-bar typed) → AppLayout sidebar appeared. [F-04] confirmed. /runs shows all 4 new "Shippable" runs at top of list.
6. Clicked into MEDIUM run (5b371f6d-…) → inline RunDetail rendered in right rail. Header reads "5b371f6d-8198-43 · Ready · Minimal Express 4 To-Do List REST API (In-Memory) · Prototype — Code generated — 4 tasks complete · 80/100 verified · Est. 98".
7. Stepper shows 6 ✓ stages (Planning, Approval, Tasks 4/4, Compilation, Review, Merge) + 3 numbered (Preview, Testing, Report).
8. Validation Report block reads "Needs review · Needs attention · Ver 80/100 · 8 files · JavaScript · 1 unresolved · Build ✓ · Reviewer CLEAN". **Cross-surface inconsistency with the run-list "Shippable" pill — see [B-N1].**
9. Cost panel shows "Live cost $0.2336 / est. $1.87 – $3.27" (10× over-estimate, [B-N2]).
10. Token Usage panel shows planner=haiku, reviewer=haiku, test-planner=haiku, sonnet-4-6 only used for 12-token call (likely classifier verifier or synthesizeSingleShot). [B-N3].

**Coherent end-to-end story today: YES at engine level**, NO at IA / cross-surface verdict consistency level.

### §4.4 ENTERPRISE — governance / procurement surfaces

Not walked through this pass — same as `AUDIT_REPORT.md §4.4` content. SSO/SAML schema-only, CSM aspirational, RLS not used (per `[F-11]`, user accepted org-id stamping).

---

## §5 — Real-Run Eval Results (the user's explicit ask)

| # | Provider | Spec band | Status | Reason | Total time | Tokens (in/out) | Cost USD | Verified | Accuracy (grade) | Tasks |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Anthropic | LOW (Fibonacci) | COMPLETE | CLEAN_COMPLETION | 74.3s | 17,116 total | $0.0658 | 80 | 95 (A) | 1/1 |
| 2 | OpenAI | LOW (Fibonacci) | COMPLETE | CLEAN_COMPLETION | 93.3s | 55,366 total | $0.0921 | 80 | 97 (A) | 1/1 |
| 3 | Gemini | LOW (Fibonacci) | COMPLETE | CLEAN_COMPLETION | 92.3s | 14,011 total | $0.0543 | 80 | 98 (A) | 1/1 |
| 4 | Anthropic | MEDIUM (Express to-do API) | COMPLETE | CLEAN_COMPLETION | 4m 36s | 39,705 total | $0.2336 | 80 | 98 (A) | 4/4 |

**Aggregate:**
- 4/4 success, all `CLEAN_COMPLETION`, all grade A.
- Per-LOW spend $0.054-$0.092 (Gemini cheapest, OpenAI most expensive — and OpenAI used 4× more tokens than Gemini for the same spec).
- LOW latency 65-93 seconds; MEDIUM 4m36s. PLANNING phase fastest on Anthropic (~5s before WORKING), ~30s on OpenAI/Gemini.
- 6-dimension scores on the MEDIUM run: codeQuality 77, testCoverage 40, architecture 77, security 85, performance 80, designCompliance null. Test coverage 40 reflects a single test file the worker emitted; the spec asked for "unit tests" but didn't enumerate scenarios.
- All 4 runs classified as `SMALL` planStrategy by the heuristic (the LLM verifier never fired — see [B-N5]). MEDIUM Express still got 4 tasks because OutlinePlannerAgent FULL_PLAN mode handles ≤4 tasks within its 32k cap.

**Surface gaps surfaced by these runs:**
- [B-N1] verdict inconsistency between list ("Shippable") and detail ("Needs review")
- [B-N2] cost estimate 10× over actual on MEDIUM
- [B-N3] planner appears to use haiku not sonnet
- [B-N4] all 4 runs went to "Unassigned" project bucket because /api/runs without projectId
- [B-N5] classifier LLM-verifier never fires

**Quality observations on the 4 generated artifacts (without reading the workspace files):**
- Build passed for all 4 — `finalBuildPassed: true` per validation report.
- Reviewer verdict CLEAN for all 4.
- 1 issue raised on the MEDIUM run (`issuesCaught: 1`) — would need workspace inspection to know what; the report doesn't surface the issue text in this metric snapshot.

---

## §6 — Failure Paths (what got tested)

| ID | Path | What user sees | Recoverable? | Severity |
|---|---|---|---|---|
| 1 | LLM provider 401 (forced by dummy keys, prior pass) | "Some tasks need manual retry" + run cards "Blocked: Final build failed — code may not run as written." Backend 401 surfaces as `failureReason: invalid x-api-key` on the task row but NOT in the user-facing UI. | yes via retry-task button | S2 — root cause invisible to user |
| 2 | Browser back across v3/v2 seam | Layout swaps cleanly (V3Shell → AppLayout). No state loss. | yes | S3 |
| 3 | /v3/* legacy redirect | Tested implicitly — V3LegacyRedirect at App.tsx:24-41 catches and rewrites to unified URLs. Live behavior verified by hitting /v3/projects/empty manually (in prior session). | yes | S3 |
| 4-10 | LLM 429, Plan timeout, Auth expiry, Network drop, Two tabs, Invalid PAT, Stripe fail | CANNOT_TEST. | unknown | S2 (unknown) |

**Worst tested failure mode:** path 1. When provider keys 401 (or rate-limit, by extension), the user-facing UI just says "Blocked: Final build failed" with no actionable signal to "go fix your provider key in Settings". A retry will fail too because the same key gets used. **The pre-flight step that should have caught this** (validating provider key works before WORKING phase) doesn't exist.

---

## §7 — Visual Hierarchy and IA

**[F-15] resolution:** verifiedScore IS the hero, accuracyScore IS demoted as the v2.5.10 design intended. Both at run-list level (VerdictPill alone) and run-detail level (`80/100 verified · Est. 98`). The audit's claim was wrong. **However, [B-N1] surfaces a different and arguably worse problem:** verdict copy is inconsistent across surfaces — the same run is labeled "Shippable" on the list and "Needs review / Needs attention" on the detail panel.

**Shell-seam visual transition:** repro'd from prior pass. /projects/{slug} (V3Shell, 5-link top-bar, no aside) → /runs (AppLayout, 13-link sidebar + 6 project buttons). Same JWT, same admin, two layouts.

**Empty / loading / error states (3 worst):**
1. **`[B-04]` v3 run-detail URL → "Couldn't load run / Run not found"** — error copy is honest but the URL pattern issue itself is unresolved.
2. **`[B-09]` /projects/empty "Set up the long way" → /org/projects with 0 rows for solo users.**
3. **/runs unauthenticated → bounces to /login cleanly** (not tested explicitly but inferred from ProtectedRoute setup; no evidence.)

---

## §8 — Updated Open Questions

```
[Q-NEW-04] Why did MEDIUM Express get classified as SMALL?
  Why it matters: A 4-task multi-endpoint spec going through SMALL band's
                  OutlinePlannerAgent FULL_PLAN mode (32k cap) only worked
                  because the spec was small enough to fit. A larger
                  spec at the same SMALL classification would truncate.
  Possible answer: heuristic too aggressive on char-count; LLM verifier
                  not firing means no second-opinion correction.
  Resolution: dispatch a known-LARGE spec, observe if classifier still
              picks SMALL, file as classifier tuning bug if so.

[Q-NEW-05] Why does the planner row show haiku in the Token Usage panel?
  Why it matters: [B-N3]. PLANNING tier is supposed to be sonnet. If
                  intentional cost optimization, document it; if a bug,
                  patch ModelResolver.
  Resolution: read v2/agent/PlannerAgentV2.java + OutlinePlannerAgent.java
              + V2RunCostTracker per-call attribution to see what model
              the panel is labeling as "planner".

[Q-NEW-06] Should /api/runs auto-link to a default "Sandbox" project?
  Why it matters: [B-N4]. Today's "Unassigned" bucket hides costs from
                  project dashboards.
  Resolution: product call on whether quick-run-from-API should require
              projectId.

[Q-NEW-07] Is the SmartClassifier LLM verifier intentionally disabled,
           feature-flag gated, or a wiring regression?
  Why it matters: [B-N5]. If FF-gated, change copy from "LLM verifier
                  unavailable" (sounds broken) to "LLM verifier disabled
                  by config" (sounds intentional). If wiring bug, fix.
  Resolution: grep for the feature flag controlling SmartClassifierAgent
              + check application-local.yml flag overrides.

[Q-01 from audit] FREE tier intent (teaser vs evaluation)
  Status: still open — not user-answered.

[Q-02 from audit] V3 replaces v2 sidebar
  Status: RESOLVED ("it should replace").

[Q-08 from audit] RLS vs org_id stamping
  Status: RESOLVED ("org id" — doc-honesty fix only).
```

---

## §9 — Updated Least-Confident Findings

- **[B-N3] (medium).** Planner haiku vs sonnet. Could be panel label issue, intentional optimization, or routing bug. 5 minutes of code reading would settle.
- **[B-N6] / [B-N7] (low/medium).** Avatar menu + login submit click. Could be Claude_Preview MCP quirks or real React event-listener bugs. Playwright headed-mode re-test resolves.
- **[B-N4] (medium).** Unassigned-bucket finding hinges on whether ProjectDashboardController rolls up runs that have null projectId. If it does, [B-N4] drops to S3 cosmetic; if it doesn't, S2.
- **[B-04] (medium).** V3 run-detail URL bug. 5-min code-trace of V3RunDetail.tsx would resolve.

---

## §10 — Cost & Time Log

- **LLM spend (real, billed):** $0.4458 — sum of 4 successful runs ($0.0658 + $0.0921 + $0.0543 + $0.2336). Plus an estimated $0.10-0.30 from the prior failed-run pass (planner LLM retries before 401). **Grand total < $1.** $30 cap not approached.
- **Real runs dispatched (this pass):** 4 (3 LOW + 1 MEDIUM, all anthropic+openai+gemini between them).
- **Active browser time:** ~120 min wall-clock (cumulative across both passes, including the wasted dummy-key pass + setup + reissue).
- **Anything that exceeded a stated cap:** No.

---

## §11 — Coverage Gaps

- **Local stack only.** Per user's standing directive — deployed `dev.executespec.ai` not used. This pass switched from `java -jar target/...` (fat-jar baked at build time) to `mvn spring-boot:run -Dspring-boot.run.profiles=local` (live classpath, picks up `application-local.yml`). The earlier mistake was using the prebuilt fat-jar which embedded an `application-local.yml` that didn't exist (since I copied the file AFTER the JAR was built).
- **Screenshots returned inline by Claude_Preview MCP, not saved to disk.** Same as prior pass.
- **No real Playwright headed-mode driver.** Same. preview_click + preview_fill quirks may have produced [B-N6] / [B-N7] false-positives.
- **PRO + TEAM personas not walked through.** Same.
- **Failure paths 4-10 CANNOT_TEST.** Same.
- **Mobile pass at 390×844 was 1 viewport, 1 route.** Same as prior pass — single screenshot was enough to surface [B-05].
- **No accessibility / contrast audit.** Same.
- **Library + Migrations galleries** not exercised this pass.
- **freshux-* test users predate V162** — same caveat about FREE walkthrough.
- **Did not inspect the workspace files for the 4 successful runs.** The reviewer says CLEAN, the build passed, accuracyScore 95-98 — but I did not open the generated `fib.py` or `index.js` to verify they actually do what the spec asked. Black-box verdict only.
- **Did NOT re-test [F-05] migrations dry-run leak in this pass.** Same as prior — would have cost LLM + time for a known code-trace finding.
- **Did not re-validate [F-09] /pricing reachability across personas.**

---

## §12 — What Changed Since The Retracted v1 Of This Report

The v1 of this file (committed at `bfc73a2f`, then deleted at user direction) is wrong on its single biggest claim: `[B-01] BYOK structurally unwired in V2 LLM dispatch (S0)`. That finding was based on every dispatched run failing with `Incorrect API key provided: sk-dummy*****udit` — which IS the dummy key I passed via `-D spring.ai.openai.api-key=sk-dummy-ui-audit` at backend start. The 401 errors echoed my own flag verbatim. I did not connect the dots to "this means the system is using my flag, not admin's BYOK" → "this might be the expected behavior under dummy-system-key + missing-application-local.yml" → "I should validate by booting properly first, before claiming a bug."

The user's correction was right. The v2 (this) report retracts:
- `[B-01]` (BYOK unwired) — **retracted, was config artifact, not bug**
- `[B-02]` (0/3 success) — **retracted, was symptom of B-01**

What survives from v1:
- All audit-finding validations on no-LLM surfaces (`[F-01]`, `[F-02]`, `[F-04]`, `[F-06]`, `[F-07]`, `[F-08]`, `[F-10]`, `[F-15]`)
- `[B-04]` v3 run-detail URL bug
- `[B-05]` mobile top-bar clipping
- `[B-09]` "Set up long way" empty target for solo users

What's new in v2 from real runs:
- `[B-N1]` cross-surface verdict inconsistency
- `[B-N2]` cost estimate 10× too high for MEDIUM
- `[B-N3]` planner haiku vs sonnet
- `[B-N4]` "Unassigned" bucket for projectless runs
- `[B-N5]` classifier LLM verifier persistently disabled (was [B-03] in v1, severity-elevated)

The audit's [F-03] permission-hardcode finding stays S0, but **detached from the BYOK chain** — the V2 LLM dispatch path does consult user-stored keys when they're populated and the system is correctly configured. The hardcode is in the permission map UI surface, not the runtime dispatch.
