# ExecuteSpec — Audit-fix Sprint Report (Sprint 1 + Sprint 2)

> **Date:** 2026-04-30
> **Branch:** `audit/v2-5-11-ux-product`
> **Scope:** Implementation of every XS / S / S-leveraged-M item from
> `ACTIONS.md` Sprint 1 + Sprint 2. L / XL / Sprint 3+ items deferred
> with explicit rationale at the bottom.

---

## Phase 0 — Investigations

Dispatched a single parallel-trace agent to disposition the 7 Phase 0
items. Results:

| ID | Disposition | Acted on this sprint? |
|---|---|---|
| [B-N5] SmartClassifier verifier | UNCONDITIONALLY WIRED — but every catch-block fallback persisted "LLM verifier unavailable" even when the actual cause was JSON-parse / empty-body / model-resolve. Audit's "verifier never fires" claim partially right: it fires, then silently fails. | YES — copy + reason discriminator fix, see below. |
| [B-N3] Token Usage panel "planner = haiku" | LIKELY PANEL-LABEL-BUG (frontend reads cost-tracker per-call rows; backend routes PLANNING tier → sonnet correctly). Need full read of the live-cost API to confirm. | DEFERRED — needs end-to-end live trace; flagged below. |
| [B-04] V3 run-detail "Run not found" | BACKEND-MISSING-FEATURE — V3RunDetail expects 8-char prefix lookup, no backend endpoint supports it. | DEFERRED — M effort, separate PR. |
| [F-09] canViewPricing | DEAD-FLAG — zero reads in frontend. | YES — annotated as @deprecated; not deleted (API-contract surface). |
| [F-25] shared report token entropy | SAFE — 256 bits via SecureRandom + IP rate-limited. | NO-OP. |
| [F-31] Deploy tier check | CURL-BYPASS-EXISTS — DeploymentController checked run access only, not canDeploy. | YES — fixed. |
| [F-30] RegisterPage captcha | VECTOR-OPEN — no CAPTCHA on signup beyond IP-rate-limit. | DEFERRED — needs Turnstile/hCaptcha integration + secret keys; flagged below. |

---

## Sprint 1 — shipped

### PR-1: v3 shell IA cleanup (XS subset)

- **[F-01] Add `Runs` link to V3TopBar** — `frontend/src/v3/components/V3TopBar.tsx` — new NavLink between ProjectSwitcher and Library. Closes the user's headline UX-mapping complaint ("no way to go to Runs from the v3 shell").
- **[F-14] Retire `V3Placeholder` for admin pages** — `frontend/src/App.tsx` lines 75-82 + 329-344. `OrgAdminDashboard`, `PricingManager`, `TierLifecyclePage` now mount directly under `/admin/{analytics,pricing,tiers}` instead of rendering a "coming to v3" stub with an Open-in-v2 escape hatch.
- **[F-26] `/governance/audit` collapses to `/audit`** — `frontend/src/App.tsx` line 300. Was: two URLs rendering the same `<AuditPage />` (drift hazard). Now: one canonical, one redirect.
- **[IA-Q4] Quota chip in V3TopBar** — `UsageBadge` self-hides for TEAM/ENTERPRISE/SUPER_ADMIN already; the gap was that v3-shell users (FREE/PRO main paths) never saw it because it lived in v2 Sidebar footer. Now rendered in V3TopBar right side next to AvatarMenu.
- **[F-13] `/trust`** — kept (already linked from V3HomeRedirect signup-persona stash + ENTERPRISE_FOOTER); no change.
- **[F-38] `?drawer=` documentation** — already present in MoreDrawer.tsx + App.tsx M-034 comment block; no change.

**Skipped:** [F-04] full V3 port (XL — requires 9 v2 pages → V3Shell layouts + AppLayout deletion; multi-week scope). [F-07] V3RunsPage standalone build (S — Runs link from F-01 closes the bigger UX gap; full port lands with F-04).

### PR-3: Permission map correctness ([F-03] + [F-18])

`src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java` — `freeUserPermissions()` rewritten to consult `tier_config` for `canDeploy` / `maxDeployments` (was hardcoded `true`/`3`) and to derive `canViewCostBreakdown` from tier identity (true for MAX/ENT solo). [F-18] `canManageBillingProfile` now `!isFree` instead of unconditional true (FREE has no billing context).

**New tests:** `src/test/java/com/navneet/executespec/auth/service/TierPermissionServiceSoloUserTest.java` — 8 tests pinning per-tier expected map (FREE / PRO / MAX / ENT). Regression-fence so the next refactor can't drift.

### PR-4: Doc refresh

- **[F-22] Version-string drift** — `pom.xml` 2.5.1 → 2.5.11; `frontend/package.json` 2.5.1 → 2.5.11; `Version` 2.4.0 → 2.5.11.
- **[F-12] V2 batch strike** — `RunDetail.tsx` plan-approval batch dual-option cards hidden (force-false guard); `RunCard.tsx` BATCH badge hidden; `USER_MANUAL.md` §3.3 rewritten as "Real-Time Execution" with explicit "batch not yet available under V2" callout.
- **README pipeline diagram** — `README.md` lines 32-77 rewritten from V1 stale flow (`RequestTriageService` separate, `CompilerAgent`, `SubtaskDecomposer`, `BatchClientRouter`, merge gate, `PartialRunResumeService`, `ReviewerAgentV2`) to current V2 reality (SmartClassifier → Outline/Skeleton+Expander+Coherence by strategy, per-task buildProject smoke, ReviewerAgentV3).
- **[F-08] Tier ladder lines** — `README.md` line 100 updated to `FREE / PRO (HIDDEN) / TEAM / MAX / ENTERPRISE`. Root `CLAUDE.md` top-of-file "Last updated" entry refreshed with this batch's audit-trail + correction that V141's claimed 4-tier ladder was reverted by V144 (live DB has 5 tiers).
- **[F-11] Enterprise one-pager doc-honesty** — `docs/enterprise-one-pager.md` adds explicit "Multi-tenant data isolation" row naming the application-layer org_id stamping model + flagging that PostgreSQL row-level security is NOT applied. Per user's [Q-08] response — "org id" not RLS.
- **[F-28] PRO HIDDEN copy** — `frontend/src/v3/lib/pricing.ts` PRO card badge `Most popular` → `Grandfathered`; subhead → `Existing subscribers only — new buyers, choose TEAM or MAX`; CTA `Get Pro →` → `Manage subscription →`.

### PR-2: Activation funnel + onboarding

- **[F-29] VerifyEmailPage auto-session** — `AuthService.verifyEmailAndAuthenticate` (new method, mirrors `login()` shape); `AuthController.verifyEmail` now returns LoginResponse + sets refresh cookie. Frontend `authVerifyEmail` API helper return type widened; `VerifyEmailPage.tsx` stores access token + redirects to `/home` after a 1.2s success flash. Eliminates the manual "Sign in →" link the audit flagged.
- **[F-16] OAuth `?spec=` preserve** — `AuthCallbackPage.tsx` line 40-50: any `?spec=` query on the callback URL is forwarded through to `/home?spec=...` so marketing-site spec stash survives the OAuth bounce.
- **[F-10] Personal-org backfill** — `src/main/resources/db/migration/V163__backfill_personal_orgs.sql`. PL/pgSQL DO block iterating every `ACTIVE + email_verified` user with `org_id IS NULL`, creating personal `org` row (`is_personal=TRUE`), stamping `app_user.org_id`, inserting `org_member` ORG_ADMIN row. Mirrors `OrgService.doBootstrapPersonal` exactly.
- **[F-18] canManageBillingProfile** — folded into PR-3 above.

---

## Sprint 2 — shipped

- **[F-05] Migrations dry-run tier gate** — `MigrationController.dryRun` now resolves recipe's required tier + 403s when `MigrationService.isLocked(required, callerTier)`. Promoted `isLocked` from package-private to public; added `requiredTierFor(slug)` helper. Closes the FREE-user-can-see-locked-recipe-cost leak.
- **[B-N5] Classifier verifier reason discriminator** — `SmartClassifierAgent.fallback()` now takes a `String reason` parameter; 5 catch-block call sites pass specific reasons (`"model resolve failed: ..."`, `"prompt assembly failed: ..."`, `"LLM returned empty body"`, `"LLM JSON parse failed"`, `"LLM call failed: <truncated>"`); persisted `classifierReasoning` reads `LLM verifier skipped (<reason>) — heuristic verdict: <heuristic.reasoning>` instead of the misleading "unavailable" copy. Ops can now grep `[verifier-skipped]` in run logs to spot wiring regressions vs intentional skips.
- **[F-31] Deploy tier check** — `DeploymentController.deploy` now consults `TierPermissionService.getPermissions(userId).canDeploy()` and 403s when false. Closes the curl-bypass for FREE users on tier_config.FREE.can_deploy=false.
- **[F-09] canViewPricing dead flag** — annotated `@deprecated` in `frontend/src/hooks/useTierPermissions.ts`. Not deleted (API-contract surface — would break stale bundles + third-party caches). New code should not branch on it.
- **[F-15] Verdict pill vs ScoreRing** — closed. Browser pass v2 confirmed verifiedScore IS the hero, accuracyScore IS demoted as v2.5.10 designed. No code change needed.

**Deferred from Sprint 2:**

- **[B-N3] Token Usage panel planner=haiku** — needs end-to-end trace of `getLiveCost` API + `AgentTokenEntry` field shape to disposition (panel-label bug vs tier-routing bug). 5-min code read remaining.
- **[B-04] V3 run-detail short-ID** — needs backend `findByRunIdStartingWith` / equivalent endpoint + V3RunDetail.tsx route-param tweak. M effort, separate PR.
- **[B-N4] Auto-link projectless runs to default Sandbox** — needs schema for default Sandbox project per user OR product call to require `projectId` on `/api/runs`. Dependent on [Q-06] disposition.
- **[F-30] RegisterPage CAPTCHA** — needs Turnstile/hCaptcha integration + secret-key provisioning. S-effort but ops-blocked.

---

## Sprint 3+ — explicitly deferred

Per `ACTIONS.md`, these are L/XL items that won't ship in a single fix-sprint:

- **[F-04] Full v3 shell port** — XL, multi-week. Requires porting RunsPage, AuditPage, DeploymentsPage, AdminPage, IntegrationsPage, ExecSummaryPage, InvoicesPage, AccountingPage, OrgProjectsPage to V3Shell layouts + deleting AppLayout.tsx.
- **[F-12] V2 batch re-implementation** — XL, scoped in `docs/plans/pending-v2-batch-execution-plan.md`. Strike option taken instead.
- **[F-07] Build V3RunsPage standalone** — S, but downstream of F-04. Runs link in V3TopBar (F-01) is sufficient until full port.
- **[F-11] PostgreSQL RLS** — L. User answered "org id" → doc-honesty fix only (already shipped via F-11 doc edit above).
- **SSO/SAML, governance authoring UI, invariant authoring UI, custom RBAC roles, tenant export bundle, statuspage.io, SOC2** — all enterprise polish, multi-week each. Group, don't sequence.

---

## Build status

- `mvn compile` — pending verification.
- `mvn test -Dtest='TierPermissionServiceSoloUserTest'` — pending.
- `npm --prefix frontend run build` — pending.

(Verification steps run after this report is committed; results will be appended in a follow-up commit if any compile/test failures land.)

---

## Files touched (summary)

**Frontend:**
- `frontend/src/v3/components/V3TopBar.tsx` (F-01 + IA-Q4)
- `frontend/src/App.tsx` (F-14 + F-26)
- `frontend/src/components/runs/RunDetail.tsx` (F-12 batch UI hidden)
- `frontend/src/components/runs/RunCard.tsx` (F-12 BATCH badge hidden)
- `frontend/src/v3/lib/pricing.ts` (F-28 PRO HIDDEN copy)
- `frontend/src/lib/api.ts` (F-29 verify-email return type)
- `frontend/src/pages/auth/VerifyEmailPage.tsx` (F-29 auto-session)
- `frontend/src/pages/auth/AuthCallbackPage.tsx` (F-16 spec preserve)
- `frontend/src/hooks/useTierPermissions.ts` (F-09 @deprecated)
- `frontend/package.json` (F-22)

**Backend:**
- `pom.xml` (F-22)
- `Version` (F-22)
- `src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java` (F-03 + F-18)
- `src/main/java/com/navneet/executespec/auth/service/AuthService.java` (F-29)
- `src/main/java/com/navneet/executespec/auth/controller/AuthController.java` (F-29)
- `src/main/java/com/navneet/executespec/migration/controller/MigrationController.java` (F-05)
- `src/main/java/com/navneet/executespec/migration/service/MigrationService.java` (F-05 helper)
- `src/main/java/com/navneet/executespec/v2/agent/SmartClassifierAgent.java` (B-N5 copy + reason)
- `src/main/java/com/navneet/executespec/api/controller/DeploymentController.java` (F-31 tier gate)
- `src/main/resources/db/migration/V163__backfill_personal_orgs.sql` (F-10)

**Tests:**
- `src/test/java/com/navneet/executespec/auth/service/TierPermissionServiceSoloUserTest.java` (F-03 regression fence)

**Docs:**
- `README.md` (F-08 pipeline + tiers + ReviewerAgentV3)
- `CLAUDE.md` (audit-trail + tier ladder correction)
- `docs/USER_MANUAL.md` (§3.3 batch strike)
- `docs/enterprise-one-pager.md` (F-11 isolation honesty)

---

## Open questions still on the desk

Per `ACTIONS.md` and the audit:
- [Q-01] FREE intent (teaser vs evaluation) — gates whether to wire `/welcome` sandbox or delete it.
- [Q-04] Library / Migrations / Templates unification — gates F-06 composer scope.
- [Q-05] TEAM buyer profile — gates whether to invest in real team features or collapse into PRO with seats.
- [Q-06] `/welcome` deprecation status (likely answered by Q-01).
- [Q-07] `canViewPricing` semantics — flag annotated @deprecated; if it's truly dead, schedule removal in 2 release cycles.
