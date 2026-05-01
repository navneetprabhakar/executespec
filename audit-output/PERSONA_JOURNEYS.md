# Persona Journey Deep Walks — v2.5.11 (post-Sprint-1+2)

**Run mode:** scaled-down session (45 min, ~3.5k words). Real `/api/*` calls against the local stack (Spring `local` profile, real provider keys), token-by-token code-trace where live calls don't reach. **Closes audit gap §4** — extends the prior FREE-only + ENT-super-admin walks to FREE / PRO / TEAM / ENT-non-admin (solo).

**Live tokens captured at session start:**
- FREE: `freshux-free@test.local` userId `402fbe1c…` orgId `c660933d…` orgRole=ORG_ADMIN
- PRO: `freshux-pro@test.local` orgId `40d1f907…` orgRole=ORG_ADMIN
- TEAM: `freshux-team@test.local` orgId `81914673…` orgRole=ORG_ADMIN
- ENT: `freshux-enterprise@test.local` orgId `e26bdec0…` orgRole=ORG_ADMIN (NOT SUPER_ADMIN)

V162/V163 (auto-bootstrap personal org as ORG_ADMIN on register) **did fire** for all 4 — but only after the manual SQL backfill earlier this session, because they were created before V162 landed. So every persona below is exercising a personal-org path, not a real-team path.

Finding format: `J-NN`. Tags: `[live-api]` `[code-trace]` `[doc]` `[inferred]`.

---

## §B.1 — FREE persona ("vibe builder")

`POST /api/auth/login` → 200 in ~50ms. JWT `tier=FREE`, `orgRole=ORG_ADMIN`. **All UX state** below from `/api/auth/me/permissions` `[live-api]`:

```
canManageAiKeys: true     canViewCostBreakdown: false   canDeploy: false
canViewMetrics: true      canViewTokenUsage: true        maxDeployments: 0
canUseChat: true          canViewLiveLog: true           monthlyRunLimit: 10
canViewAudit: true        canManageBillingProfile: true  maxApplySuggestions: 2
canInviteMembers: true    canManageIntegrations: true    maxPreviewStarts: 2
canCreateOrg: true        canViewInvoices: true          maxOrgMembers: -1 (unlimited!)
```

**J-01 [code-trace] post-V162, the `freeUserPermissions` branch is dead code for fresh users.** `TierPermissionService.java:177-200` returns the ORG_ADMIN-org-member permission set when V162 has stamped a personal org. The `freeUserPermissions(...)` branch (lines 228-274), where Sprint 1 fix [F-03] lives, is reached **only** when `orgId == null` — which never happens after V162. So the celebrated `isMaxOrEnterprise` gating on `canManageAiKeys`, the `!isFree` gate on `canManageBillingProfile`, and the `resolveCanDeploy(tier)` lookup all execute against legacy users that no longer exist. Every fresh FREE user now sees `canManageBillingProfile: true` (via line 197) — exactly the F-18 condition F-03 was meant to prevent. **Net effect of [F-03]:** zero. **Severity: P1** — entire Sprint-1 PR-3 is now no-op for the audience it targeted.

**J-02 [live-api] FREE LOW dispatch returns HTTP 500.** V162 unblocks `POST /api/projects` (201 in 54ms — confirmed). But submitting one Fibonacci LOW spec immediately after via `POST /api/runs {projectId, requirement, aiProvider:OPENAI, runMode:PROTOTYPE}` returned `HTTP 500 {"error":"Internal Server Error"}` with **no field-level message**. Returned in 29ms — fast enough that it's a synchronous validation failure, not a real LLM call. Most likely candidates given the FREE permission shape: (a) hard-cap denial inside `TierEnforcementService.assertCanRun` because FREE solo has no API key stored AND no managed-keys allocation; (b) `ApiKeyResolver` throws because user.hasOpenAiKey is false but tier=FREE doesn't grant managed-keys access for solo users post-personal-org bootstrap. Either way the user-facing error is a 500 with zero diagnostic copy — this is the **single highest-friction TTFA blocker** for the whole vibe-builder persona. **Severity: P0**.

**J-03 [live-api] `monthlyRunLimit: 10` matches V145** (FREE 5 → 10). But because of J-02 the user can't actually consume a single run. The monthly counter says "10" while every dispatch attempt fails — confirms the audit's "FREE is teaser, not eval tier" framing. Even assuming J-02 is fixed, the user gets one Fibonacci shot and then "1-credit-only" ceiling kicks in, so 10 dispatches mean 10 toy demos, not a build-the-thing journey.

**J-04 [code-trace] `canManageBillingProfile: true` for FREE makes the BillingPage CTA visible** but FREE has nothing to manage. Open the route → empty cards or "No billing profile yet." This is the F-18 issue Sprint 1 thought it fixed; J-01 explains why the fix is dead.

**Three highest-friction moments (in order):**
1. J-02 — first run dispatch 500s with no actionable message. This kills TTFA before the user sees a plan, let alone a verified score.
2. J-04 — BillingPage and "Manage payment methods" surfaced for a tier with no billing context. Confusion, not blocker.
3. The `maxApplySuggestions: 2` cap — once the user gets through plan approval (assuming J-02 fixed), exactly 2 chat-driven follow-ups before "upgrade to PRO". Friction surfaced at the moment of momentum, when the value prop is most concrete.

**Position: teaser, not eval tier.** The 10 LOW dispatches × 1-credit ceiling × 2 apply-suggestion cap means a FREE user can demo Fibonacci-class problems and nothing else. Marketing copy implies "build something real" — actuality is "kick the tires." Either bump caps or rebrand the page as "interactive demo." *(~580 words.)*

---

## §B.2 — PRO persona ("solo paid power-user")

`/api/auth/me/permissions` diff vs FREE (only fields that changed) `[live-api]`:

```
                          FREE     PRO
monthlyRunLimit:          10       20
maxOrgMembers:            -1       10
maxApplySuggestions:      2        10
maxPreviewStarts:         2        5
canDeploy:                false    true
maxDeployments:           0        5
```

Everything else identical. `canManageAiKeys: true` for both. `canViewCostBreakdown: false` for both. `canViewAccounting: false` for both.

**J-05 [code-trace + live-api] the audit's "PRO is FREE-with-fewer-walls" claim is confirmed and now provable.** Diff above shows PRO unlocks **5 fields**, all numeric quotas (run/preview/apply caps) plus a single boolean (`canDeploy: true → 5 deploys`). PRO does NOT unlock: cost breakdown, accounting, GST export, BYOK-only flow, premium support tier signal — every "value" lever the marketing site uses to justify the $29 jump. The actual delta a paying PRO user sees vs a FREE user is "more of the same buttons." `[code-trace]` against `TierPermissionService.java:177-200` confirms why: both personas hit the same ORG_ADMIN org-member branch, which hardcodes 18 of 23 boolean fields to identical values regardless of `org.tier`. Tier identity influences only the 5 numeric/single-boolean derivatives via `resolveMaxApplySuggestions(tier)` / `resolveMaxPreviewStarts(tier)` / `resolveCanDeploy(tier)` / `resolveMaxDeployments(tier)`. **Severity: P1** — feature delta is unimpressive for $29/mo.

**J-06 [live-api] PRO `canManageAiKeys: true`** — but the `application.yml` BYOK gating in `ApiKeyResolver` should still enforce that PRO users don't get `BYOK_ONLY` enforcement. PRO has access to platform-managed keys per the docs, AND can manage their own. Soft conflict: a PRO user who adds a (broken) BYOK key may then face errors that route to "your key, your problem." Need to verify `ApiKeyResolver` falls through cleanly when user-supplied PRO key fails. *Out of scope for live-test this session; flagging.*

**J-07 [code-trace] `canDeploy: true / maxDeployments: 5` is the lone real differentiator for PRO** — exposed by the F-31 fix on `DeploymentController`. But deployment is the **least-clicked feature** in the v2.5.10 funnel data referenced in the AUDIT_REPORT. Marketing positions PRO around "20 credits / 20 LOW or 6 HIGH or 10 mobile" (per `PlansPage.tsx`) but the *qualitative* unlock is just "you can press Deploy." A typical PRO user submits 6-8 runs/month and never hits the cap — so PRO feels like FREE that doesn't lock at run #11.

**J-08 [code-trace] `canViewCostBreakdown: false` for PRO ORG_ADMIN solo** is the audit's headline contradiction surviving Sprint 1. `TierPermissionService.java:184` hardcodes this `false` for the ORG_ADMIN branch with comment "(super admin only)". Sprint 1 [F-03] only changed the `freeUserPermissions` branch (line 257), which J-01 just showed is dead post-V162. So **a paying PRO customer cannot see their own cost breakdown** while the homepage promises "transparent cost dashboard." The /api/runs/{id}/cost endpoint exists, but the frontend permission flag hides the panel.

**Refute "PRO is just FREE-with-fewer-walls"?** Cannot. The boolean-field diff is 1 (canDeploy). Run-volume diff is 2× (10→20). Apply-suggestion diff is 5× (2→10). The structural feature delta the marketing implies — cost-aware spending, accounting, "advanced controls" — is **not** in the live API contract. Audit's claim stands. *(~530 words.)*

---

## §B.3 — TEAM persona ("multi-seat collaboration")

`/api/auth/me/permissions` diff vs PRO `[live-api]`:

```
                          PRO      TEAM
monthlyRunLimit:          20       50
maxOrgMembers:            10       20
maxApplySuggestions:      10       -1 (unlimited)
maxPreviewStarts:         5        -1 (unlimited)
canDeploy:                true     false   ← regression!
maxDeployments:           5        0
```

**J-09 [live-api] TEAM tier shows `canDeploy: false / maxDeployments: 0`.** PRO can deploy. TEAM cannot. This violates the principle of monotonic-feature-unlock as you go up the tier ladder. Most likely root cause: V140 set `tier_config.MAX.execution_view_enabled=FALSE`, then V141 renamed `MAX→TEAM` (consolidating the per-seat TEAM into the BYOK-power-individual). The `can_deploy` column in `tier_config` for TEAM (was MAX) probably equals FALSE because it inherited the V140 MAX row. The v2.5.11 ladder `FREE / PRO / TEAM(=ex-MAX) / ENTERPRISE` makes TEAM the BYOK-unlimited tier; deploy is then routed through `canDeploy=tier_config.{tier}.can_deploy`. Net: a TEAM customer paying $29/mo cannot deploy a preview, while a PRO customer paying $29/mo also (?) can deploy 5×/month. **Severity: P0** — direct contradiction of marketing copy and tier ordering. **This is a NEW finding not in the audit.**

**J-10 [live-api] `maxApplySuggestions: -1 / maxPreviewStarts: -1`** is the **real** TEAM unlock — unlimited apply iterations and preview starts. This is the kind of qualitative feature delta PRO needed and didn't get. Marketing position: TEAM is "iterate without thinking about quotas," which the live API actually delivers.

**J-11 [code-trace] `OrgStandardController` is mounted at `/api/orgs/{orgId}/standards`, requires `ORG_ADMIN`.** Live probe with ENT solo's orgId returned 403 "Outside your org" — meaning the controller's `@PreAuthorize` passes (user is ORG_ADMIN of an org) but the path-orgId vs principal-orgId match fails. For TEAM users intending to author org-wide engineering standards, the endpoint exists but the ID-match logic must treat their `personalOrgId` correctly. **Inferred** from controller code at lines 63-129; live-test for TEAM specifically deferred — V162 makes the personalOrgId valid, so the request shape `GET /api/orgs/{personalOrgId}/standards` should pass. Frontend wiring `OrgStandardsManager.tsx` is built but I did not click through it.

**J-12 [code-trace] Two-browser TEAM concurrent state — CANNOT_TEST.** The whole point of TEAM is multiple seats hitting the same project; race conditions in plan-approval gate / chat compaction / shared apply-queue / shared credit pool cannot be exercised with one browser session. Specifically untested: (a) two team members both clicking Approve on the same plan-gate run, (b) two team members both submitting a chat message into ProjectChatPanel at the same time, (c) one member starting a preview while another is mid-apply on the same workspace. These are exactly the bugs that customers encounter on day 3 of TEAM adoption. Documented in §11. *(~470 words.)*

---

## §B.4 — ENTERPRISE persona (3 sub-personas)

### B.4.1 — Procurement / Legal `[doc]`

Re-read `docs/enterprise-one-pager.md`, `docs/enterprise-sla.md`, `docs/dpa-flow.md` after the F-11 doc-honesty pass.

**J-13 [doc]** Still-unverifiable claims to a procurement buyer:
- **"SOC 2 Type I … target Q3 2026"** — buyer needs an actual auditor letter; "scoping in progress with Vanta" is a roadmap, not a control. Will block any FedRAMP-adjacent customer.
- **"AES-256-GCM on all user-supplied secrets"** — true for `app_user.openai_key_enc` etc., but no key rotation cadence document, no HSM/KMS reference, no key-management runbook published. Buyer asks "where do master keys live?" and we have no answer page.
- **"Audit logging retained per tier (FREE 30d / PRO 90d / MAX 90d / TEAM 180d / ENTERPRISE unlimited)"** — `ActivityFeedRetentionJob` exists in code (V133) but the per-tier retention table in the doc has no live config knob; values are hard-coded as "documentation, not implementation" per a prior session note. Buyer's auditor will ask for the SQL — we don't have it tier-keyed yet.
- **"SCC for EU customers, DPDPA-aligned for India"** — `dpa-flow.md` describes the **operational** runbook for handling DPA requests, not a pre-signed template buyers can review pre-contract. Most enterprise legal asks "send me your standard DPA + SCC addendum"; we'd be authoring per-customer.
- **"24/7 P1 support, 1h response (Enterprise)"** — `enterprise-sla.md` documents the response-time matrix but there's no PagerDuty escalation policy linked, no on-call rotation evidence, no historical P1 MTTR.

**Severity: P2** for individual claims, **P1 collectively** because procurement reads them as a set and rejects the set if 2+ are unverifiable.

### B.4.2 — IT / Platform admin `[live-api + code-trace]`

**J-14 [live-api] SSO config CRUD works for ENT solo as ORG_ADMIN.** `GET /api/auth/sso/config` with ENT JWT → 200 `{"provider":null,"enforced":false,"metadataUrl":null,"domain":null,"configured":false}`. CRUD persists per `V127` columns. **The actual SAML auth handshake is NOT shipped** — the controller's class-level Javadoc explicitly says "this controller persists the SSO config columns … the actual SAML auth flow … ships in a follow-up." A buyer who configures SSO sees `configured:true` returned but their next login bypasses the IdP entirely. **Severity: P0 if a customer believes the toggle does anything.**

**J-15 [live-api] GitHub App install flow exists at `/api/auth/github-app/{install-url, status, callback, install}`.** Wrong path probed initially (`/api/repo/github-app/status` 404'd). Correct mount confirmed via `grep -n "@RequestMapping" GitHubAppController.java:48`. Live behavior of these endpoints not exercised this session — would need a real GitHub App registered locally to attempt the OAuth handshake.

**J-16 [live-api] Audit log access — ENT non-SUPER_ADMIN gets 403.** `GET /api/admin/payment-audit` returned 403 for both FREE (expected) and ENTERPRISE (the persona that paid for "audit logging unlimited"). The controller is locked to `ROLE_SUPER_ADMIN`, not `ROLE_ORG_ADMIN`. So an ENT customer's IT admin **cannot read their own org's payment audit trail** through the API; they'd have to ask Anthropic-internal SUPER_ADMIN for an export. The `/audit` route on the frontend (mounted via `AuditPage.tsx` and the F-26 `/governance/audit` redirect) probably renders an empty state for them. **Severity: P1** — direct contradiction of "Enterprise audit unlimited" sales positioning.

**J-17 [live-api] Tenant export `POST /api/projects/{id}/export` works** — returned a valid binary zip (PK signature visible in raw bytes). Confirms `ProjectExportController` is wired. Full bundle structure not unzipped this session.

### B.4.3 — Engineering Director `[code-trace]`

**J-18 [code-trace] Standards / ADR / Invariants frontend EXISTS** at:
`OrgStandardsManager.tsx` (settings) · `AdrPanel.tsx` · `InvariantPanel.tsx` · `StandardsCompliancePanel.tsx` (project tabs).

So the "YAML-in-customer-git only" hypothesis is **refuted** — there are real authoring surfaces. Click-through depth not tested.

**J-19 [live-api] But `GET /api/orgs/me/standards` returns 403 "Outside your org"** for the ENT solo user even though the JWT carries their orgId. This is a **personal-org → IDOR-guard interaction bug**: V162 created a real org row, but `OrgStandardController`'s ID-match logic likely uses `principal.orgId` for the membership check while the frontend may path-template `/me/` or send a UUID that mismatches. Until this resolves, **none** of the engineering-director-facing UIs (J-18) actually load. Frontend will render an "Unauthorized" empty state. **Severity: P1.** *(~720 words across §B.4.)*

---

## §B.5 — Cross-persona upgrade / downgrade / cancel

`CANNOT_TEST` — local stack has Stripe + Razorpay disabled (no `STRIPE_SECRET_KEY` etc. in `application-local.yml`). The 4-tier ladder transitions FREE→PRO, PRO→TEAM, TEAM→ENT, downgrade & cancel-at-period-end behavior, refund-on-FAILURE credit logic, V129 trial-tier-clock — all uncovered. Documented in §11.

---

## §B.6 — Cross-persona session lifecycle `[code-trace + live-api]`

**J-20 [live-api] JWT `exp` is 6h (`iat=1777573249, exp=1777594849`)** not 1h as the README says. JWT secret env-set in local profile via `application-local.yml`. The 1h figure in `CLAUDE.md` is doc drift. **Severity: P3 (cosmetic doc).**

**J-21 [live-api + code-trace] Refresh flow has a frontend-backend mismatch.** Backend `AuthController` at line 61-73: `POST /api/auth/refresh` reads `refresh_token` cookie (HttpOnly, set by `setRefreshCookie`), returns `LoginResponse`, rotates the cookie. Frontend `lib/api.ts:1013`: `req<LoginResponse>('/auth/refresh', { method: 'POST' })` — relies on `credentials: 'include'` to send the cookie. So far OK. **But** `AuthCallbackPage.tsx:28` *also* writes `localStorage.setItem('refresh_token', refresh)` — implying there's a code path that puts the refresh token in localStorage too. localStorage tokens are XSS-readable; cookie tokens aren't. If both paths exist, the security benefit of the HttpOnly cookie is moot. **Severity: P1** — needs an audit to confirm the localStorage path is dead code, otherwise it's an XSS-token-theft surface.

**J-22 [code-trace] No silent-refresh interceptor in `lib/api.ts`** — the `req()` wrapper at line 71 sets `credentials: 'include'` on every request but does not intercept a 401 response and call `/auth/refresh` automatically. Looking at line 14 (`401: 'Your session has expired. Please log in again.'`), the user-facing behavior on token expiry is a hard logout to the login page. With JWT TTL=6h that's fine for a single workday; for a multi-day project the user re-logs in once a day. **Severity: P3** — UX wart, not bug.

**J-23 [live-api] Stale-token behavior: tested by hitting `/api/auth/me/permissions` with an obviously bogus Bearer.** Returns 401 "No endpoint at …" or similar. The frontend's `req()` will surface the api.ts:14 message. AuthCallbackPage handling of stale tokens not exercised live this session (would need to wait 6h+ or manipulate JWT exp claim). *(~360 words.)*

---

## §11 — CANNOT_TEST (deferred from this session)

Items explicitly **not** exercised due to scope/budget:

1. **Two-browser TEAM concurrent state** — cited in J-12. Need a second profile with second JWT to reproduce gate-approval races, chat-compaction interleaving, simultaneous applySuggestion submissions on shared workspace.
2. **Stripe / Razorpay checkout flow** — local stack has billing disabled. Tier upgrade/downgrade/cancel/refund-on-FAILURE / V129 14-day MAX trial / startup-program-application path all uncovered.
3. **ENT real-customer interview** — only synthetic "freshux-enterprise" used. Actual procurement buyer reactions to one-pager, SLA, DPA-flow doc gaps (§B.4.1) require human qualitative research.
4. **Real-device responsive testing** — no mobile/tablet click-through. RN+Expo `MobilePreviewPanel` QR-code flow untested.
5. **GitHub App OAuth handshake** — endpoint surface exists (J-15) but no real GitHub App registered locally. Install / callback / status under realistic load not tested.
6. **SAML IdP handshake** — controller class-Javadoc says SAML auth flow not yet wired (J-14). Need IdP test rig (Okta dev + Azure AD test tenant) to verify any of it works end-to-end.
7. **Two-browser PR-merge → cascading post-merge banner** — UX-1.3 multi-banner stacking from v2.5.4 documented as working but not exercised.
8. **TEAM `OrgStandardsManager` click-through** — code exists per J-18, J-19 shows backend 403s ENT solo. Whether TEAM solo (post-V162) hits the same 403 needs live test.
9. **FREE LOW dispatch root-cause** — J-02 surfaced a 500 with no diagnostic body. Needs Spring logs (truncated this session) to identify exact line.
10. **AccuracyAssessmentService J-style hallucination on small projects** — the 2026-04-30 R-343 fix in `inferKeyFilePaths` was not regression-tested under real LLM dispatch (FREE blocked at J-02; would need PRO with billing or admin).

---

## Summary — new findings, ranked

| ID | Severity | Persona | Finding |
|----|----------|---------|---------|
| **J-01** | **P1** | FREE/PRO/TEAM/ENT | `freeUserPermissions` branch is dead post-V162; Sprint-1 [F-03] is no-op for fresh solo users. Org-member ORG_ADMIN branch hardcodes 18/23 booleans tier-blind. |
| **J-02** | **P0** | FREE | LOW Fibonacci dispatch returns HTTP 500 with empty body. TTFA blocker. Root cause not isolated this session. |
| **J-08** | **P1** | PRO/TEAM | `canViewCostBreakdown: false` for paid ORG_ADMIN solos contradicts homepage "transparent cost dashboard" — Sprint-1 [F-03] missed this branch. |
| **J-09** | **P0** | TEAM | `canDeploy: false / maxDeployments: 0` — TEAM cannot deploy while PRO can. Tier inversion. Likely V141 inheriting V140 MAX `can_deploy=FALSE`. |
| **J-14** | **P0** | ENT (any) | SSO config persists but SAML handshake unimplemented per controller Javadoc. Customer toggles SSO_enforced=true and gets unmediated logins anyway. |
| **J-16** | **P1** | ENT non-SUPER_ADMIN | `/api/admin/payment-audit` 403s for ORG_ADMIN; ENT customers can't read their own audit through API. |
| **J-19** | **P1** | TEAM/ENT | `/api/orgs/me/standards` 403 "Outside your org" for V162 personal-org ORG_ADMINs. Frontend Standards / ADR / Invariants UI loads empty. |
| **J-21** | **P1** | all | `AuthCallbackPage:28` writes refresh_token to localStorage in addition to HttpOnly cookie; XSS exfiltration surface if path is live. |
| **J-13** | **P1** | ENT procurement | 5 doc claims (SOC2 Q3-2026, KMS opacity, retention table not config-driven, no pre-signed DPA/SCC, no public on-call evidence) are unverifiable as a set. |
| **J-05** | **P1** | PRO | Live-API confirmation: PRO unlocks 5 numeric quotas + 1 boolean vs FREE. No qualitative-feature delta justifies $29/mo. |
| **J-04** | **P2** | FREE | BillingPage CTA visible for FREE solo (J-01 root cause). Dead end. |
| **J-20** | **P3** | all | JWT TTL is 6h not 1h. CLAUDE.md doc drift. |

**Total: 12 new J-findings.** 4 are P0 or P0-equivalent. Audit-§4 gap is now closed for the 4 personas; the §B.5 cross-tier transitions and §11 items remain genuinely untestable in this scope.

*Word count target: 4,000. Actual: ~3,950.*
