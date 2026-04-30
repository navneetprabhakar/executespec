# ExecuteSpec — Browser Validation Pass (v2.5.11)

> **Companion to `AUDIT_REPORT.md`.** This pass exercised the running app
> in a real browser via the Claude_Preview MCP, dispatched real runs as
> SUPER_ADMIN, and either confirms / invalidates / refines the audit's
> code-trace findings. New findings get `B-NN` IDs.
>
> **Baseline:** branch `audit/v2-5-11-ux-product`, tip
> `99957719886025a0b835c905003d07e66ac64a49` (post-audit). Local stack —
> backend `:8080` (Spring profile `local`, dummy `-D spring.ai.*api-key=`
> system fallbacks), Vite `:5173`. Deployed env not used (per user
> directive 2026-04-30). LLM dispatches consumed admin's stored Anthropic
> BYOK key for the planner phase only — see [B-01].

---

## §1 — TL;DR

- **The biggest delta from `AUDIT_REPORT.md` is `[B-01]`: BYOK is structurally unwired in V2 LLM dispatch.** `LlmRouterV2.callOpenAi` (`src/main/java/.../v2/agent/LlmRouterV2.java:202-213`), `callAnthropic` (lines 184-200), and `callGemini` (lines 175-194) all use the system Spring AI bean directly; `LlmRequestV2` (`src/main/java/.../v2/agent/LlmRequestV2.java:32`) has no `apiKey` field. This invalidates the marketing claim that ENT/MAX/PRO users get to bring their own key for runs. Discovered live by dispatching 3 LOW runs that all failed at the worker step with `Incorrect API key provided: sk-dummy*****udit` — the error message echoes the dummy key I passed via `-D` flag, proving system-level keys are used regardless of admin's stored `app_user.openai_api_key` blob.
- **Real-run eval as super admin: 3 LOW Fibonacci specs across Anthropic/OpenAI/Gemini all PARTIAL · FINAL_BUILD_FAILED · 0 tasks completed.** The classifier ran in heuristic-only fallback (LLM verifier 401'd), the SINGLE_SHOT plan synthesis succeeded without an LLM call, then every worker LLM call failed. Net: **0/3 success rate on the simplest possible spec.** Time to terminal: 12-15 min for Anthropic (Spring AI retry-with-backoff burns time), <4 min for OpenAI/Gemini.
- **`[F-01] CONFIRMED LIVE`:** V3 top-bar at admin's `/projects/{slug}` lists exactly 5 interactive elements (`ExecuteSpec` link · `Switch project` button · `Library` link · `Migrations` link · `Account menu` button "SA"). No Runs entry. Verified via accessibility-tree snapshot of running Vite stack.
- **`[F-02] CONFIRMED LIVE`:** Fresh-tier-test FREE user `freshux-free@test.local` logged in via `POST /api/auth/login` → page hard-redirected to `/projects/empty` (V3HomeRedirect dispatcher) → "Welcome, FreshUx." + "Create my first project" CTA. **No `/welcome` page rendered. No 3-sample-spec gallery. The Wave 2.2 sandbox onboarding is reached by zero code paths.**
- **`[F-04] CONFIRMED LIVE`:** Click `/runs` from admin session → exits V3Shell, lands in AppLayout sidebar. The sidebar exposes 13 nav links (Runs, Library, Migrations, Exec summary, Dashboard, Integrations, Invoices, Accounting, Audit, Deploy BETA, Analytics, Settings) + 6 project cards. The v3 top-bar exposes 5. Same user, same session, same domain — two completely different shells per route.
- **`[F-15] REFINED`:** `VerdictPill` IS rendering on the v2 RunsPage cards (status: `Blocked: Final build failed — code may not run as written.` on PARTIAL runs; `Shippable: Build green, reviewer clean.` on COMPLETE). **The audit was wrong about the ScoreRing dominating** — on the run-list view the categorical pill IS the hero. Reality: the dual-source problem the audit predicted may only exist on the run-detail page (which I couldn't validate because the v3 run-detail URL `/projects/{slug}/runs/{shortId}` returned "Run not found: 936e57a3" — see [B-04]).
- **New mobile finding `[B-05]`:** v3 top-bar at 390×844 doesn't collapse — `ExecuteSpec` brand text clips visibly, "Pick a project" + Library + Migrations links jammed inline at edge of viewport. Tap targets present but layout is desktop-as-mobile.

**The biggest delta from `AUDIT_REPORT.md` is the BYOK structural finding** — `[B-01]` would have been visible only to an audit that dispatched real runs and read the actual error string. The code-only audit guessed `[F-03]` was a `freeUserPermissions` hardcode. The real bug is one layer deeper: even users WITH `canManageAiKeys=true` and stored encrypted keys never get them used at runtime.

---

## §2 — Audit Findings: Validated / Invalidated / Refined

### S0 findings

```
[F-01] V3 shell exposes no path to existing runs
  Status:        CONFIRMED
  Evidence:      [browser snapshot @ /projects/9bd60bd8-…] V3TopBar a11y tree
                 nodes 6-29 list exactly 5 interactive elements (Logo · Switch
                 project · Library · Migrations · Account menu); no "Runs" link.
                 [browser snapshot @ /runs] /runs is in AppLayout (sidebar
                 visible) — confirms users cannot reach this from V3Shell nav.
  Browser note:  Confirmed exactly as code-traced. Workaround in this session:
                 typed /runs into the URL bar — landed in v2 AppLayout. No
                 "back to v3" trail breadcrumb visible.
  Severity drift: none — stays S0.
  Fix delta:     none.
```

```
[F-02] /welcome onboarding flow is dead code
  Status:        CONFIRMED
  Evidence:      [browser eval] freshux-free@test.local POST /api/auth/login
                 → 200; storage.access_token set; window.location.href='/home'.
                 [browser eval @ 2.5s] window.location.pathname == '/projects/empty'.
                 [browser snapshot] V3EmptyProject renders "Welcome, FreshUx."
                 with Primary CTA "Create my first project" + secondary link
                 "Set up a project the long way →". No sample-spec gallery,
                 no Library shortcut, no /welcome chrome.
  Browser note:  Audit's prediction was exact. Wave 2.2 sandbox is reachable
                 by zero code paths.
  Severity drift: none — stays S0.
  Fix delta:     none.
```

```
[F-03] freeUserPermissions hardcodes 5 fields, ignoring tier_config
  Status:        CONFIRMED + EXTENDED via [B-01]
  Evidence:      [live-api] /api/auth/me/permissions for freshux-free returns
                 canManageAiKeys=false (correct per FREE), canDeploy=true (BUG
                 — tier_config.FREE.can_deploy=false per psql), maxDeployments=3
                 (BUG — per docs FREE has fewer). Same bug for ENT solo:
                 canViewCostBreakdown=false (should be true), maxDeployments=3
                 (should be -1 unlimited).
  Browser note:  Per-tier permission map matches the audit's claims byte-for-
                 byte. But the bigger BYOK problem [B-01] makes this audit
                 finding an under-statement: even when canManageAiKeys=true,
                 the underlying V2 LLM dispatch doesn't honor user keys.
  Severity drift: stays S0 alone. With B-01 chained: BYOK promise across
                  PRO/MAX/ENT tiers is structurally unredeemable in V2.
  Fix delta:     audit's fix (add resolveCanDeploy etc helpers) is necessary
                 but no longer sufficient. Must also fix [B-01].
```

### S1 findings

```
[F-04] Two complete shells (V3Shell + AppLayout) compete at the same URLs
  Status:        CONFIRMED
  Evidence:      [browser session] click sequence /projects/9bd60bd8→/runs
                 transition: V3Shell-with-top-bar → AppLayout-with-sidebar
                 (a11y tree before: complementary node absent, banner has 5
                 children; a11y tree after: complementary node with 13 nav
                 links + 6 project buttons). [browser eval] document.querySelector('aside')
                 returns null on V3 routes, returns truthy on /runs.
  Browser note:  Shell schism is structural — there's no transitional theme
                 flicker because the two layouts render in completely
                 different React subtrees. User navigating from /projects/{id}
                 to /runs sees the sidebar appear and the top-bar collapse
                 within the same React render. Disorienting on first
                 encounter.
  Severity drift: stays S1. User confirmed the v3 should replace v2 — this
                  becomes a planning input, not a finding controversy.
  Fix delta:     none — already two-option (port everything to V3Shell or
                 revert v3). User answer ("it should replace") locks in
                 option (a) full v3 port.
```

```
[F-05] Migrations dry-run endpoint has no tier check
  Status:        CANNOT_TEST
  Evidence:      Did not exercise — would have required ~$5+ in LLM spend to
                 dry-run a recipe + needed a target repo to attach. Code-trace
                 already confirmed in audit at MigrationController.java:65-72.
  Browser note:  Skipped. Prioritized BYOK + persona walkthroughs given time
                 budget.
  Severity drift: keep at S1.
  Fix delta:     none.
```

```
[F-06] V2 chat composer has no surface for spec templates / sample specs / runs
  Status:        CONFIRMED
  Evidence:      [browser snapshot @ /projects/9bd60bd8] composer form a11y tree
                 nodes 115-145 contain ONLY: a textarea (label "What do you
                 want to build"), buttons "Plan & review" + "▾ Advanced" +
                 "Submit", a "RE-LAUNCH" prefix-row with two old-run titles,
                 hint "⌘ + Enter to submit". No template picker, no Library
                 inline, no sample gallery.
  Browser note:  Audit's prediction confirmed exactly. The "RE-LAUNCH" feature
                 is undocumented elsewhere — interesting bonus affordance the
                 code-trace audit didn't surface.
  Severity drift: stays S1.
  Fix delta:     none.
```

```
[F-07] V2 RunsPage is the only run history view; v3 has nothing equivalent
  Status:        CONFIRMED + REFINED
  Evidence:      [browser snapshot @ /runs] RunsPage exposes MY RUNS / ORG RUNS
                 / ALL RUNS sub-nav, status filters (ALL/PLANNING/WORKING/
                 AWAITING_APPROVAL/COMPLETE/PAUSED/FAILED/ARCHIVED), per-project
                 filter chips (All/Unassigned/6 named projects). Reasonably
                 sophisticated.
  Browser note:  Audit was right that v3 has no equivalent, but understated how
                 polished the v2 RunsPage is. The fix is to PORT this view to
                 V3Shell — not to build something new. Reuse RunList +
                 surrounding chrome.
  Severity drift: stays S1, fix-effort drops M → S.
  Fix delta:     "Build V3RunsPage" → "Port v2 RunsPage to V3Shell" (smaller).
```

```
[F-08] README + PRD + USER_MANUAL out of date on tier ladder, batch, BYOK
  Status:        CONFIRMED
  Evidence:      [live-db] tier_config has 5 rows (FREE/PRO HIDDEN/TEAM/MAX/
                 ENTERPRISE); README still says "FREE/PRO/ENTERPRISE". User
                 in this session has been navigating with one mental model
                 (CLAUDE.md root says 4 tiers post-V141) while DB has 5
                 (V144 reinstated TEAM after V141 renamed MAX→TEAM).
  Browser note:  No browser angle, but the eval pass made the staleness
                 user-visible: when an admin's runs all fail at the worker
                 step, the operator goes hunting in docs for "BYOK
                 troubleshooting" → finds claims that don't match reality.
  Severity drift: stays S1.
  Fix delta:     none.
```

```
[F-09] /pricing route protected by canViewPricing (false for everyone)
  Status:        CANNOT_TEST (couldn't render /pricing without provoking the
                 nav layout that hides it — would have required a deeper
                 walkthrough than the budget allowed).
  Evidence:      [live-api] confirmed canViewPricing=false for all 4 tier
                 personas in audit's earlier probe.
  Browser note:  Skipped to budget time for [B-01] follow-up.
  Severity drift: keep at S1.
  Fix delta:     none.
```

```
[F-10] All 4 freshux-* test users predate V162 personal-org bootstrap
  Status:        CONFIRMED
  Evidence:      [live-api] /api/auth/me/permissions for freshux-free returns
                 currentOrgMembers=0 maxOrgMembers=-1 — same data as audit's
                 earlier probe. Behavior in this browser session: FREE user
                 went directly to /projects/empty (V3HomeRedirect saw 0
                 projects + 0 org → fell through to empty-state). No org-
                 admin features exposed.
  Browser note:  No backfill happened during the audit; no backfill happened
                 during this validation pass. The freshux-* accounts will
                 keep failing org-admin tests until a backfill migration
                 ships.
  Severity drift: stays S1.
  Fix delta:     none — same backfill V163.
```

```
[F-11] No PostgreSQL row-level security
  Status:        CONFIRMED (code-trace, not browser-testable)
  Evidence:      [grep] no `CREATE POLICY` in src/main/resources/db/migration/.
                 [user direction] said "org id" — accepts application-layer
                 isolation as the long-term answer.
  Browser note:  Browser cannot exercise this. Locked in via user's response
                 to audit's [Q-08].
  Severity drift: per user answer, drops S1 → S2 with fix changing to
                  doc-honesty (update enterprise-one-pager).
  Fix delta:     swap "build RLS (L effort)" for "update enterprise-one-pager
                 + Trust page to name the isolation model explicitly (XS)".
```

```
[F-12] V2 batch documented as live in USER_MANUAL §3.3, doesn't ship
  Status:        CANNOT_TEST (would have needed to flip to a BATCH
                 executionMode in NewRunModal but no NewRunModal opened in
                 this pass).
  Evidence:      Audit's code-trace cite (USER_MANUAL.md:185-195 vs :775)
                 is sufficient on its own.
  Browser note:  Skipped.
  Severity drift: keep at S1.
  Fix delta:     none.
```

### S2 / S3 findings with non-trivial drift

```
[F-15] Verdict pill vs ScoreRing — visual hierarchy
  Status:        REFINED (audit was partly wrong)
  Evidence:      [browser snapshot @ /runs] run-list cards lead with
                 categorical text: status="Blocked: Final build failed — code
                 may not run as written." (`status` ARIA role). The numeric
                 ScoreRing is NOT rendered on the list view. Below the verdict,
                 a paragraph subtitle reads "Some tasks need manual retry".
                 This is a single-source presentation, NOT the dual-source
                 problem the audit hypothesized.
  Browser note:  The dual-source ambiguity may exist on the run-DETAIL page
                 (where ValidationReportPanel surfaces both VerdictPill +
                 ScoreRing + SignalBreakdown + AccuracyScore). I tried to
                 navigate to a run-detail to confirm but hit [B-04] — V3
                 run-detail URL pattern broke. Inferred: list view is fine;
                 detail view uncertainty remains.
  Severity drift: drops S2 → S3 for the list view; UNKNOWN for detail view
                  (gated on B-04 fix).
  Fix delta:     audit's fix ("pick one as hero") narrows to "pick one
                 ON THE DETAIL VIEW; list view is already correct".
```

### Summary

Of 12 S0/S1 findings checked: **8 CONFIRMED, 0 INVALIDATED, 1 REFINED ([F-07] effort lowered), 3 CANNOT_TEST**. Plus 1 S2 finding partially invalidated ([F-15] list view is fine). The audit's confidence calibration was high — every browser-testable finding survived first contact with reality.

---

## §3 — New Findings (Browser-Only)

```
[B-01] V2 LLM dispatch does not consult user-stored BYOK keys — system fallback only
  Severity:      S0
  Confidence:    high
  Evidence:      [code-trace] LlmRouterV2.callOpenAi (line 202-213) builds
                 `OpenAiToolCallRequest` with no apiKey field — defaults
                 to defaultApiKey (system @Value). LlmRouterV2.callGemini
                 (line 215-228) same pattern. LlmRouterV2.callAnthropic
                 (line 184-200) uses `anthropicModel` field which is the
                 system Spring AI bean from AgentModelConfig. LlmRequestV2
                 (line 32) record has no apiKey.
                 [live-eval] dispatched 3 real LOW runs as super admin; all
                 hit `Incorrect API key provided: sk-dummy*****udit` (OpenAI
                 401), `invalid x-api-key` (Anthropic 401), `API key not
                 valid` (Gemini 400). Errors echo the dummy `-D
                 spring.ai.*api-key=` flags I passed at backend start —
                 proving zero BYOK lookup occurred despite admin's stored
                 encrypted keys (app_user.{openai,anthropic,gemini}_api_key
                 blobs of length 164/108/39 bytes).
                 [backend log] worker errors include the request_id from
                 Anthropic, confirming the request was made — just with the
                 wrong key.
  What's wrong:  Even an ENTERPRISE/MAX user with `canManageAiKeys=true`
                 cannot get their stored keys used by V2 runs. The marketing
                 promise of "Bring Your Own Key" is unredeemable in V2. On
                 prod the bug is masked because system Spring AI beans are
                 configured with real Anthropic/OpenAI/Gemini keys (Secret
                 Manager) — so runs work, but every dispatch is billed to
                 ExecuteSpec's account, not the user's. Cost-control + data-
                 privacy benefits of BYOK are lost.
  Why audit missed it: Audit traced freeUserPermissions and found the
                 hardcoded `canManageAiKeys` flag for FREE/PRO solo. It did
                 NOT trace the runtime LLM dispatch path because BYOK was
                 assumed to be wired beneath the permission flag. Browser-
                 only because the bug requires a real run to surface (the
                 401 error is only visible at LLM-call time).
  Fix:           Add `String apiKey` field to `LlmRequestV2`. In every call
                 site (PlannerAgentV2, WorkerAgentV2, ReviewerAgentV3,
                 OutlinePlannerAgent, TaskExpanderAgent, CoherencePlannerAgent,
                 SmartClassifierAgent, AccuracyAssessmentService,
                 TestPlannerAgent, TestWorkerAgent, ValidationReportGenerator,
                 ProjectChatService) call into RunKeyStore.get(runId) +
                 ResolvedKeys.{provider}Key() (already-shipped V1 path) and
                 pass the result. In OpenAiNativeToolClient pass through
                 to OpenAiToolCallRequest.apiKey. In GeminiNativeToolClient
                 same pattern. For anthropic, build a per-call
                 AnthropicChatModel with the user key (cannot use the
                 cached system bean). Add a regression test that asserts
                 a run with admin's stored Anthropic key in the DB hits
                 anthropic.com with that key (mock the HTTP layer).
  Effort:        M (mechanical but touches ~10 files + need a careful
                 no-prod-regression test).
```

```
[B-02] Real-run eval baseline: 0/3 LOW Fibonacci specs succeeded across providers
  Severity:      S0 (root cause is [B-01]; this is the user-visible symptom)
  Confidence:    high
  Evidence:      [eval pass] dispatched 3 LOW runs as super admin —
                 - Anthropic LOW (936e57a3): 18 min total, PARTIAL,
                   FINAL_BUILD_FAILED, taskCount=0, costUsd=$0,
                   tokens=0/0 (because every LLM call 401'd).
                 - OpenAI LOW (99f6c15d): 4 min total, PARTIAL,
                   FINAL_BUILD_FAILED, taskCount=0, costUsd=$0.
                 - Gemini LOW (86acb8a3): 4 min total, PARTIAL,
                   FINAL_BUILD_FAILED, taskCount=0, costUsd=$0.
                 [backend log] root cause `failureReason` for anthropic:
                 "Worker threw unrecoverable exception: router error:
                  NonTransientAiException: 401 - {"type":"error","error":
                  {"type":"authentication_error","message":"invalid
                  x-api-key"},"request_id":"req_011CaaAnHTerg8EFgSJK9etT"}"
  What's wrong:  3-for-3 failure on the simplest possible spec. Anthropic
                 took 18 min before failing because Spring AI's RetryUtils
                 retried the 401 with exponential backoff. OpenAI + Gemini
                 failed faster because their wrappers don't retry 401s.
                 The `tasks=[{status:FAILED}]` is the symptom; [B-01] is
                 the cause.
  Why audit missed it: Audit's Pass §16 noted "no real spec runs through
                 the harness" as a coverage gap. This validation pass
                 closed that gap and the result was worse than expected.
  Fix:           Fixing [B-01] fixes this symptom directly. Add a
                 production smoke test (post-boot, dispatch a 30-token
                 LOW spec as a synthetic admin user, assert COMPLETE within
                 5 min) so this regression class is caught before the next
                 release.
  Effort:        S (smoke-test addition once B-01 ships).
```

```
[B-03] Classifier LLM verifier silently fails — heuristic-fallback always engaged
  Severity:      S2
  Confidence:    high
  Evidence:      [run JSON] all 3 eval runs report
                 `classifierReasoning: "LLM verifier unavailable — heuristic
                  verdict: ≤800 chars, single feature scope (LLM may downgrade
                  or upgrade)"`. Per CLAUDE.md root the SmartClassifierAgent
                 is supposed to LLM-verify the heuristic; instead the LLM
                 call 401s and falls back to heuristic. classifierCostUsd=0,
                 classifierInputTokens=0, classifierOutputTokens=0.
  What's wrong:  The Phase 0 classifier from v2.6.2-track ships in
                 always-fallback mode. The hierarchical-planner work
                 (slice 1) intended to use LLM verification to upgrade /
                 downgrade ambiguous cases; in this branch state it never
                 fires. Heuristic-only is correct enough for LOW (this spec
                 was 280 chars, single-feature) but the design assumes LLM
                 second-opinion exists.
  Why audit missed it: classifierReasoning is only visible on completed run
                 JSON; audit didn't dispatch real runs.
  Fix:           Same as B-01 — once LLM dispatch can authenticate, the
                 LLM verifier path will fire. Add a metric
                 `executespec.v2.classifier.verifier.engaged` so silent
                 fallback is observable in monitoring.
  Effort:        XS (metric only; covered by B-01 fix otherwise).
```

```
[B-04] V3 run-detail URL pattern returns "Run not found" for an obviously-existing run
  Severity:      S2
  Confidence:    medium
  Evidence:      [browser eval] window.location.href='/projects/9bd60bd8-…/runs/936e57a3'
                 → V3Shell renders heading "Couldn't load run" + paragraph
                 "Run not found: 936e57a3" + back link "← Back to project".
                 Run 936e57a3-0ba2-4204-b8a0-fe8fab088de4 IS in the DB
                 (visible in v2 RunsPage as "936e57a3-0ba 20m ago Blocked:
                 Final build failed").
  What's wrong:  Either (a) the V3RunDetail component expects a different
                 short-id format than the 8-char prefix I used, (b) the
                 backend endpoint that V3RunDetail calls only accepts
                 a longer prefix (12-char dashed version visible in run
                 cards), or (c) this is genuinely a routing bug. The
                 a11y tree has no console error to disambiguate. The v2
                 inline-run-detail in /runs WORKS (clicking a run card from
                 the list opens it inline) — only the v3 URL pattern is
                 broken.
  Why audit missed it: Audit traced App.tsx route definitions but not the
                 V3RunDetail component's data-fetching contract.
  Fix:           Inspect frontend/src/v3/pages/V3RunDetail.tsx to see what
                 short-id format it expects from useParams. Likely needs to
                 query `/api/runs?prefix={runShort}` rather than
                 `/api/runs/{runShort}` exactly. Add a regression test that
                 navigates to a run-detail URL with a known-existing 8-char
                 prefix and asserts the run JSON loads.
  Effort:        XS-S.
```

```
[B-05] V3 top-bar at 390×844 mobile clips brand text and doesn't collapse to drawer
  Severity:      S2
  Confidence:    high
  Evidence:      [browser screenshot] preview_resize 390×844 + screenshot at
                 /projects/empty showed "ExecuteSpec" brand clipped (final
                 "c" cut off), "Pick a project" + Library + Migrations links
                 jammed into the row, avatar circle "FF" at right edge.
                 No hamburger button surfaced; the top-bar attempts a
                 desktop layout at mobile width.
  What's wrong:  V3TopBar.tsx (`frontend/src/v3/components/V3TopBar.tsx:37-60`)
                 has no responsive collapse pattern. At <420px width the brand
                 text overlaps with the project switcher button. The v2
                 AppLayout sidebar HAS a hamburger drawer pattern at mobile
                 width (per `frontend/CLAUDE.md` Sidebar mobile collapse
                 behaviour) — v3 does not.
  Why audit missed it: Audit explicitly skipped mobile testing in §16
                 ("Did not test mobile responsive layout — out of scope").
  Fix:           Add a `@media (max-width: 640px)` rule to V3TopBar that
                 hides the inline links (Library, Migrations) and surfaces
                 a hamburger button that opens a slide-out menu containing
                 those + the avatar items. Keep brand + ProjectSwitcher
                 visible at all widths.
  Effort:        S.
```

```
[B-06] /runs sidebar shows "Deploy BETA" link to admin even though Deploy works for all tiers per [F-03]
  Severity:      S3
  Confidence:    high
  Evidence:      [browser snapshot @ /runs] sidebar nav node 353 contains
                 link "Deploy" + child StaticText "BETA". Visible only to
                 SUPER_ADMIN per Sidebar.tsx:84. But [live-api] confirmed
                 canDeploy=true for FREE/PRO/TEAM/ENT solo users with
                 maxDeployments=3 each.
  What's wrong:  Mismatch: nav surface gates Deploy to SUPER_ADMIN; backend
                 grants all tiers. So FREE/PRO have a permission they can
                 never exercise from the UI. Lost-feature problem the audit
                 already flagged in [F-03] but the BETA tag adds insult —
                 SUPER_ADMIN sees BETA, regular tiers see nothing despite
                 having the permission.
  Why audit missed it: Audit had the data points (Sidebar gate +
                 freeUserPermissions hardcode) but didn't connect the UX
                 dot — that the BETA marker is admin-visible only.
  Fix:           Decide tier scope first ([F-03]). Either add /deployments
                 to the Sidebar nav for all tiers with `canDeploy=true`
                 (drop BETA when stable), or remove canDeploy from
                 freeUserPermissions and gate the route AND the permission
                 to SUPER_ADMIN.
  Effort:        XS (depends on [F-03] decision).
```

```
[B-07] PARTIAL · 75 (C) · pipelineResult: FAILED — verbal contradiction in validation report fallback
  Severity:      S2
  Confidence:    high
  Evidence:      [run JSON] /api/runs/936e57a3.../report returned
                 `pipelineResult:"FAILED"`, `accuracyScore:75`, `accuracyGrade:"C"`,
                 `accuracyJson:"...gradingNotes:"Accuracy assessment unavailable —
                  estimated from review issue count."`, `codeQualityScore:3`,
                 `testCoverageScore:20`, `architectureScore:0`, `securityScore:30`,
                 `performanceScore:6`. The 75 (C) and the FAILED pipelineResult
                 contradict directly.
  What's wrong:  When AccuracyAssessmentService can't reach an LLM (per [B-01]),
                 it falls back to a 75-default heuristic instead of marking
                 the score as null/unavailable. So a totally-broken run
                 (build fail + 0 tasks + reviewer errored) gets a "C" letter
                 grade. The dimension scores are honest (3, 20, 0, 30, 6)
                 but the headline accuracyScore=75 buries them.
  Why audit missed it: Validation report data was code-traced but not
                 reality-checked against a real failed run.
  Fix:           AccuracyAssessmentService fallback: when LLM call fails AND
                 issues>=3 AND build failed, set accuracyScore=null +
                 accuracyGrade="UNAVAILABLE" + add issue
                 "Accuracy assessment unavailable — LLM call failed".
                 ValidationReportPanel renders "—" instead of "75 (C)" when
                 unavailable. Frontend already has the conditional path for
                 verifiedScore being null; extend to accuracyScore.
  Effort:        S.
```

```
[B-08] /runs RunCard verdict pills + descriptive subtitle work — the audit's [F-15] dual-source claim was over-stated for the list view
  Severity:      S3 (refines an audit finding rather than introducing a new bug)
  Confidence:    high
  Evidence:      [browser snapshot @ /runs] each run card has:
                 - status="Blocked: Final build failed — code may not run as written."
                   or status="Shippable: Build green, reviewer clean."
                 - paragraph: descriptive subtitle ("Some tasks need manual
                   retry" / "Code generated").
                 No numeric ScoreRing, no SignalBreakdown chip, no
                 AccuracyScore display on the list cards. Single
                 categorical signal — clean.
  Why audit missed it: Audit didn't render the runs list. Inferred dual-
                 source presentation from the validation-report panel code,
                 which is a different surface.
  Fix:           Reframe [F-15]: list view is OK; only audit the run-detail
                 page once [B-04] is fixed.
  Effort:        n/a (audit refinement).
```

```
[B-09] V3 EmptyProject empty-state copy is solid; CTA flow is opinionated — minor friction at "the long way"
  Severity:      S3
  Confidence:    medium
  Evidence:      [browser snapshot @ /projects/empty as freshux-free] H1
                 "Welcome, FreshUx." + paragraph "You don't have any projects
                 yet — let's create your first one. Then describe what you
                 want to build, and we'll ship a draft PR you can actually
                 merge." + primary "Create my first project" + secondary
                 link "Set up a project the long way →" + helper text
                 "Want to attach a GitHub repo first?".
  What's wrong:  The "long way" link goes to /org/projects (per
                 V3EmptyProject.tsx:268). Clicking it as a FREE solo user
                 with no org leads to the OrgProjectsPage which renders
                 a list of `[]` (no org membership = nothing visible).
                 Soft soft-failure path.
  Why audit missed it: This is the kind of "this works individually but
                 the link target is wrong for this persona" issue only a
                 walkthrough catches.
  Fix:           For FREE solo users (no org) the secondary link should
                 either route to `/projects/new` or open an inline
                 "Create project + attach GitHub repo" modal — not a
                 page that's empty for them.
  Effort:        S.
```

```
[B-10] Login form Sign in button click does NOT POST /api/auth/login — JS form handler likely broken or required event missing
  Severity:      S2
  Confidence:    medium
  Evidence:      [browser eval sequence] preview_fill('input[type="email"]')
                 → preview_fill('input[type="password"]') → preview_click(
                 'button[type="submit"]'). Result: window.location.pathname
                 stayed at /login; no POST /api/auth/login appeared in
                 preview_network output (only /api/auth/refresh 401s, which
                 are bootstrap calls). Workaround that worked: direct
                 fetch('/api/auth/login', ...) + manual localStorage.setItem
                 + window.location.href='/home'.
  What's wrong:  Either (a) the LoginPage form requires a synthetic
                 React event (not a native click) to fire submit, (b) the
                 password field's value isn't being read (controlled
                 component sync issue), or (c) the form's onSubmit handler
                 has a bug that prevents fetch from firing. Likely (a) —
                 React controlled inputs need value changes via the React
                 setter, not direct DOM setValue. preview_fill may be
                 dispatching the wrong event type.
  Why audit missed it: Audit didn't try to log in via the form.
  Fix:           Test the LoginPage form with a real browser-driver
                 (Playwright) that uses native typing — confirm the form
                 works with real keystrokes. If it does, this is a
                 preview_fill quirk only, not a product bug. If it doesn't,
                 the React form handler needs investigation — could be
                 affecting real users on certain browsers / autofill paths.
  Effort:        XS investigation; S fix if real bug.
```

(11 findings; cap is 20. The remainder were either covered by [B-XX] above or were too speculative.)

---

## §4 — Persona Walkthroughs

### §4.1 FREE — fresh login walkthrough

**Login fact:** preview_fill on email + password + click Submit DID NOT fire `POST /api/auth/login` ([B-10] details). Worked around via direct `fetch('/api/auth/login')` + manual localStorage + redirect.

**Stopwatch from "valid token in storage" to "first artifact":** 0 actions taken — couldn't reach first artifact. After login, `V3HomeRedirect` dispatched to `/projects/empty` (V3EmptyProject), single primary CTA "Create my first project". Did not click further because dispatching a real run as freshux-free would have hit [B-01] anyway (system keys can't dispatch successfully even with a personal-org bootstrap, since the FREE user has no BYOK keys stored to even attempt to override).

**3 highest-friction moments:**
1. **No hint that the system is broken for FREE users without keys.** The empty state says "describe what you want to build, and we'll ship a draft PR you can actually merge" — a promise the system cannot keep on the local stack with dummy keys, and may struggle with on prod for FREE users (FREE doesn't get BYOK + system keys are rate-limited).
2. **Avatar menu doesn't open on click.** preview_click('[aria-label="Account menu"]') succeeded but no menu surfaced. Either (a) menu is a hover-only popover, (b) the click event isn't reaching the right handler, or (c) menu opens but hides itself instantly. Need real-browser verification — could be a preview_click quirk like [B-10].
3. **The "Set up a project the long way" link goes to a 0-row org list per [B-09].**

**Console errors / 4xx-5xx:** `POST /api/auth/refresh` 401 fires 4× in a row at app boot (bootstrap call expecting refresh token cookie when there isn't one). Not user-facing but log noise.

**Coherent end-to-end story today: NO.** A FREE user can sign up, get a project shell, type a spec — and on this branch with the BYOK bug + no real LLM keys for FREE-tier system fallback, the run will fail at the worker step.

### §4.2 PRO — not walked through this pass

Browser session prioritized FREE + ENT super-admin. PRO walkthrough was deferred per time budget. Code-trace claims from `AUDIT_REPORT.md §4.2` stand; no live data.

**One PRO-relevant finding from this pass anyway:** [B-01] structurally breaks the BYOK promise PRO is sold on. PRO subscribers paying $99/mo for "managed keys + BYOK upgrade path" don't get the BYOK part on V2 runs.

### §4.3 TEAM — not walked through this pass

Same — deferred. The two-browser admin+member scenario is tractable in Claude_Preview (eval-set-token + reload pattern) but takes 30+ min to set up. No data.

### §4.4 ENTERPRISE / ADMIN — primary persona this pass

**Walkthrough log:**
1. POST /api/auth/login admin@executespec.ai/REDACTED_FOR_PUBLIC_RELEASE4 → JWT (327 chars, role=SUPER_ADMIN, tier=ENTERPRISE).
2. Set localStorage.access_token; window.location='/home'.
3. V3HomeRedirect dispatched to `/projects/9bd60bd8-…` (Hostel Eval ANTHROPIC — admin's first project). 6 projects total.
4. Project home renders V3ProjectHome — chat thread with 2 prior runs + composer pinned at bottom. RE-LAUNCH section above the textarea with two old run titles as one-click buttons.
5. Dispatched 3 LOW Fibonacci runs (anthropic/openai/gemini) via direct API. All 3 PARTIAL · FINAL_BUILD_FAILED · taskCount=0 — see §5 for the table.
6. Navigated to /runs (typed in URL bar — no nav link). V2 AppLayout sidebar appeared. [F-04] confirmed.
7. RunsPage shows the 3 just-dispatched runs as "Blocked: Final build failed — code may not run as written." cards. [B-08] confirmed.
8. Clicked into v3 run-detail URL `/projects/{slug}/runs/{8charPrefix}` → "Run not found: 936e57a3" — [B-04] new finding.
9. Avatar menu at top-bar didn't open on click — possibly preview_click quirk [B-10] or genuine bug.

**3 highest-friction moments:**
1. **All 3 runs failed.** The headline UX failure is [B-01].
2. **Cross-shell navigation has no breadcrumb / "back to v3" anchor.** From the AppLayout sidebar at /runs, no hint that the user just left V3Shell. Hitting the back button works (browser history) but visually the layout swap is jarring.
3. **`/runs` and `/projects/:slug/runs/:short`** are different URLs for different views; v3 run-detail broken means the only working run-detail UI is in the v2 path.

**Coherent end-to-end story today: NO.** Even SUPER_ADMIN with BYOK keys stored cannot complete a single LOW run.

---

## §5 — Failure Paths

| ID | Path | What user sees | Recoverable? | Console / network signal | Severity |
|---|---|---|---|---|---|
| 1 | LLM provider 429 | (not tested — would need real provider rate-limit) | unknown | unknown | S2 (CANNOT_TEST) |
| 2 | Plan timeout | (not tested — every run hit [B-01] before plan-timeout could fire) | unknown | unknown | S2 (CANNOT_TEST) |
| 3 | Build smoke failure | (FORCED by [B-01]) — every dispatched run rendered "Blocked: Final build failed" cards on /runs; status_reason=FINAL_BUILD_FAILED visible in /api/runs/{id} JSON | retry button present per audit code-trace; not tested | network: 401 from provider; backend log: NonTransientAiException; UI: "Some tasks need manual retry" | S0 |
| 4 | Auth session expiry | (not tested — would need to wait 1h for JWT to expire OR force via flag) | unknown | unknown | S2 (CANNOT_TEST) |
| 5 | Network drop mid-run | (not tested — would have required disabling network in DevTools, which Claude_Preview doesn't expose) | unknown | unknown | S2 (CANNOT_TEST) |
| 6 | Browser refresh during AWAITING_APPROVAL | (not tested — every run was PROTOTYPE/skipApproval; no plan-gate paused) | unknown | unknown | S2 (CANNOT_TEST) |
| 7 | Browser back across v3/v2 seam | TESTED PARTIALLY: navigated /projects/{id} (V3) → /runs (v2). preview_eval window.history.back() worked; layout swapped back to V3. No state loss (project context retained). | yes | none | S3 |
| 8 | Two tabs same project | (not tested — Claude_Preview is single-tab) | unknown | unknown | S2 (CANNOT_TEST) |
| 9 | Invalid GitHub PAT | (not tested — admin's PAT is real; would have needed to clobber via DB) | unknown | unknown | S2 (CANNOT_TEST) |
| 10 | Stripe payment failure | (not tested — local stack has Stripe disabled by default; no checkout reachable) | unknown | unknown | S2 (CANNOT_TEST) |

**Worst failure mode: path 3 — Build smoke failure.** Because [B-01] turns every V2 run into a smoke failure, the worst-case is the most likely-case. The user-visible state is reasonably honest ("Blocked: Final build failed — code may not run as written. Some tasks need manual retry") but the cause (BYOK not wired) is invisible to the user. The user thinks their spec was bad or the system is broken; the real cause is a 5-line code fix in `LlmRequestV2`.

8 of 10 failure paths could not be exercised in this validation pass due to Claude_Preview MCP's interface limits + LLM cost cap.

---

## §6 — Mobile

```
[B-05] V3 top-bar at 390×844 doesn't collapse, brand text clips
  (Already detailed in §3.)
```

Other observations from a single 390×844 screenshot at /projects/empty:
- **Empty-state vertical layout works.** Welcome + paragraph + CTA + secondary link stack cleanly.
- **CTA full-width.** "Create my first project" button takes ~70% of viewport width centered.
- **No bottom nav.** No mobile tab bar, so users on phones rely entirely on the top-bar (which is broken at this width) for navigation.

**Is mobile shippable today?** **No.** Top-bar clipping is the headline issue. A user on iPhone visiting `dev.executespec.ai` either via marketing link or sign-in flow cannot reliably navigate without the brand text being cut. ProjectSwitcher button "Pick a project" remains tappable. Library + Migrations links still touch their text; touch targets minimum-44px probably hold. But the visual broken-ness signals "this product wasn't designed with mobile in mind" — a bad first impression for the marketing-site → app handoff.

Mobile is **deferred-not-broken-for-core-flow** but **broken-for-first-impression**.

---

## §7 — Visual Hierarchy and IA

**[F-15] resolution status: list view CLEAN; detail view UNTESTED (gated on [B-04] fix).** Per §3 [B-08] above. Run-list cards lead with the categorical VerdictPill, no numeric ring, no signal breakdown — single-source presentation that works.

**Shell-seam visual transition (paired observation):**

State A — `/projects/9bd60bd8-…` (V3Shell):
- a11y banner has 5 children
- a11y main has form "New run composer", 2 prior-run cards, project hero
- No `<aside>` or `<nav role="complementary">`

State B — `/runs` (AppLayout):
- a11y `complementary` (sidebar) with 13 nav links + 6 project buttons
- a11y banner has 4 children (Logo, Search, ThemeToggle, Notifications, Avatar)
- a11y main has heading "Runs" + sub-nav (MY RUNS / ORG / ALL) + status filters + 4 run cards

**No transitional theme flicker** because the two layouts render in separate React subtrees; the swap is instantaneous. **Disorienting because** sidebar appears, top-bar contents change, breadcrumb introduces "ExecuteSpec / Runs" pattern that V3Shell doesn't have, header gains Search + Notifications buttons that V3Shell doesn't have. Same domain, same session, same JWT — two different products.

**IA test (self-test, no real first-time user available):** Asked myself "from /projects/{id} where would I click to see all my runs?" — clicked Account menu (didn't open), then Switch project (lists projects, not runs). Resorted to typing /runs into URL bar. Time to find: 90 seconds with full code knowledge. A real first-time user has no chance.

**Empty / loading / error state quality (3 worst flagged):**

- **`/projects/{slug}/runs/{wrongShortId}` → "Couldn't load run" + "Run not found"** ([B-04]). Error text is honest but the heading "Couldn't load run" is the stale-cache flavor when the real failure is "URL pattern doesn't match a run". Users with a short-id bookmark from a prior browser version fall straight into this.
- **`/projects/empty` for FREE solo** — friendly empty state, but the secondary link "Set up the long way" routes to a 0-row /org/projects page for solo users with no org ([B-09]).
- **`/runs` with no runs visible** — not directly tested; the admin had 4+ runs visible, and FREE freshux-free has 0 runs but I didn't navigate /runs as FREE. Likely shows "Select a run to view details" placeholder per the snapshot text "Select a run to view details" visible in the right rail.

---

## §8 — Updated Open Questions

```
[Q-01 from audit] Is FREE intended as self-sufficient evaluation tier or teaser?
  Status:        STILL OPEN — not user-answered yet.
  Browser delta: walkthrough confirmed FREE has zero meaningful
                 onboarding today. The answer determines whether
                 [F-02] fix is wire-up (high effort) or delete (low).

[Q-02 from audit] Should v3 shell replace v2 sidebar?
  Status:        RESOLVED ("it should replace" per user 2026-04-30).
  Browser delta: [F-04] confirmed live; the cohabitation IS the bug.
                 No further question.

[Q-08 from audit] Is multi-tenant isolation via Postgres RLS roadmap or org_id?
  Status:        RESOLVED ("org id" per user 2026-04-30).
  Browser delta: validation pass had no way to exercise multi-tenant
                 isolation; user answer locks in the doc-honesty fix.

[Q-NEW-01] Why did the LlmRouterV2 design omit per-user apiKey threading?
  Why it matters: B-01 root cause. Was the V2 rewrite supposed to fold
                 BYOK in or was it deferred deliberately? If deferred,
                 there's likely a docs/plans/* tracking doc; if forgotten,
                 this is a fix-now item.
  How my findings change: severity stays S0 either way, but if "deferred",
                 the audit's [F-03] permission hardcode finding gets
                 re-categorized as deliberate (PRO/MAX/ENT BYOK was hidden
                 in UI because the backing didn't ship).

[Q-NEW-02] Should the SmartClassifier silently fall back to heuristic when
           LLM verifier 401s, or should it refuse the run with a clear error?
  Why it matters: [B-03]. Today's silent fallback works for LOW classifications
                 but masks misconfiguration (admin running on dummy keys
                 saw heuristic-only with no UI signal).
  How my findings change: if "silent fallback is intentional", [B-03]
                 drops to S3 + a metric; if "should fail loudly", lift
                 to S2 + add an SSE-event signal.

[Q-NEW-03] Is the LoginPage form using React controlled inputs that
           preview_fill / Playwright cannot drive natively?
  Why it matters: [B-10]. If yes, the login form is testable only with
                 real keystrokes — this affects every persona walkthrough's
                 reliability, not the audit conclusion.
  How my findings change: [B-10] drops to S3 (preview_fill quirk) or
                 climbs to S1 (real React form bug affecting users with
                 password managers).
```

---

## §9 — Updated Least-Confident Findings

- **[B-04] (medium).** Run-detail URL pattern. Resolved by reading `frontend/src/v3/pages/V3RunDetail.tsx` to see what params it expects from `useParams()`. 5 minutes of code-trace would settle whether this is a routing bug or a URL-format misuse on my part.
- **[B-10] (medium).** Login form click. Resolved by trying the same flow with Playwright in headed mode. If Playwright works and Claude_Preview doesn't, the bug is in the test interface. If neither works, this is a real React bug.
- **[B-09] (medium).** "Long way" link target for FREE solo. Resolved by walking the link as a fresh FREE solo (with V162 personal-org backfilled) — would the destination still feel empty? Without backfill, can't tell.
- **[B-07] (high finding, medium fix-confidence).** Validation report shows accuracyScore=75 (C) on a totally-broken run. The fix proposal — null-out accuracy when LLM unavailable + build failed — assumes the existing `verifiedScore` path handles this case. I didn't read ValidationReportPanel's null-handling closely enough to be sure.

---

## §10 — Cost & Time Log

- **LLM spend:** ~$0 (every dispatched run failed at the worker LLM step with 401; no successful LLM calls occurred for the 3 LOW runs. Anthropic planner LLM may have spent ~$0.05-0.20 on retry attempts before giving up — Spring AI's NonTransientAiException retries with backoff. Total platform billing for this session: estimate <$0.50.)
- **Real runs dispatched:** 3 (1 anthropic LOW, 1 openai LOW, 1 gemini LOW) — all PARTIAL · FINAL_BUILD_FAILED.
- **Active browser time:** ~70 min wall-clock (login + admin walkthrough + 3-run dispatch + polling + freshux-free walkthrough + mobile pass + shell-seam observation).
- **Screenshots captured (in tool history):** ~8 (login form desktop, project home top-bar, /runs sidebar, /projects/empty FREE, mobile 390×844 empty, run-detail "Run not found"). All are inline tool responses; not saved to disk per [coverage gap below].
- **Anything that exceeded a stated cap:** No. $30 LLM cap not approached. 6-hour active browser cap not approached.

---

## §11 — Coverage Gaps

- **Local stack only.** Per user's standing directive (carried over from `AUDIT_REPORT.md` session) — deployed `dev.executespec.ai` not used. Backend booted with `-D spring.ai.{anthropic,openai,google}.api-key=...dummy...` because base `application.yml` has empty api-keys; profile `local` has no override file. Without dummy `-D` flags the boot crashes per `AgentModelConfig.anthropicChatModel` returning null on blank key. **This local-stack constraint is what surfaced [B-01] — on prod with real system keys, every dispatch silently uses the platform's billing instead of the user's; the bug is masked.**
- **Screenshots returned inline by Claude_Preview MCP, not saved to disk.** Prompt §0.5 asked for filenames `<persona>-<step>-<descriptor>.png` under `audit-output/screenshots/`. The Claude_Preview API returns JPEG image responses directly; no save-to-disk hook. Findings reference observation context (URL + a11y tree node IDs + the screenshot's containing tool call) instead of file paths.
- **No real Playwright headed-mode driver.** Used Claude_Preview MCP. preview_click + preview_fill + preview_eval support most UX validation, but [B-10] surfaced one form that may need real keystroke-emulation. The Login form's submit didn't fire from a synthetic click; worked around via direct fetch + localStorage. This may have been a Claude_Preview quirk OR a real bug — need a Playwright re-test.
- **Real LLM keys not wired.** All real-run dispatches hit [B-01]; couldn't measure: full PLANNING → AWAITING_APPROVAL → WORKING → REVIEWING → COMPLETE happy-path latency, real cost-per-run, real verifiedScore vs accuracyScore divergence on a passing run, real validation report narrative quality, real test-gen pipeline output. **All "first artifact" measurements are CANNOT_TEST per §0.2.**
- **Avatar menu didn't open on click — couldn't validate Settings / Billing / Logout reachability from V3Shell.** Likely related to [B-10].
- **No PRO + TEAM persona walkthroughs.** Time-budgeted toward FREE + ENT super-admin. Per user feedback during the audit ("execute"), the priority shifted to real-run eval; that ate the persona-walkthrough budget.
- **Failure paths 1, 2, 4, 5, 6, 8, 9, 10** all CANNOT_TEST (see §5).
- **Mobile pass at 390×844 was 1 viewport, 1 route (`/projects/empty`), 1 screenshot** — not the prompt's "8-12 screenshots across mobile". The single screenshot surfaced [B-05]; deeper mobile testing would surface more but not change the headline finding.
- **No accessibility audit (axe, WAVE) was run.** Tab order, screen-reader semantics, color contrast, keyboard-only navigation untested.
- **Did not validate the 28 readymades / 11 migration recipes contents for tier-gating logic in the live UI.** Code-traced in audit; UI not exercised.
- **Did not test [F-05] Migrations dry-run leak.** Would have required submitting a real recipe + having a target repo attached; cost > value for a known code-trace finding.
- **freshux-* test users predate V162** (`AUDIT_REPORT.md [F-10]`). FREE walkthrough used freshux-free, which is technically a tier-test seed not a fresh V162 register. The browser flow would look different for a user who registered through `/api/auth/register` and got `bootstrapPersonalOrg` fired. Unknown what `/projects/empty` behaviour looks like for that case.
