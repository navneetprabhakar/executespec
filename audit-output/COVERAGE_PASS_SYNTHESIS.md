# ExecuteSpec — Coverage Pass Synthesis

> **Date:** 2026-05-01
> **Branch:** `audit/v2-5-11-ux-product`
> **Scope:** Synthesizes the 6 coverage-pass deliverables produced
> from `COVERAGE_PROMPTS.md` against the prior `AUDIT_REPORT.md` and
> `BROWSER_VALIDATION_REPORT.md`. Does NOT replace those — points at
> them for detail. Companion to `SPRINT_FIXES_REPORT.md` which covers
> the implementation work that shipped.

---

## §1 — TL;DR (≤300 words)

- **The coverage pass found 49 new issues across 6 reports** (12 J-NN
  personas, 15 S-NN surface, 6 D-NN failure-path, 10 A-NN accessibility,
  ~3 M-NN mobile, plus competitor positioning facts). Three are **P0**,
  ten are **P1**.
- **The most consequential single finding is `[J-01]`: my own Sprint 1
  PR-3 fix for `[F-03]` was dead code.** V162's personal-org bootstrap
  (which fires on every fresh signup) routes solo users through
  `orgMemberPermissions`, not `freeUserPermissions`. The F-03 fix lived
  only in the latter. **Fixed in this same coverage-pass session** —
  added a personal-org guard at `TierPermissionService.getPermissions:84`
  that routes `is_personal=TRUE` orgs through the tier-driven solo path
  + 3 new unit tests (11/11 green).
- **Tier configuration regressions live in DB:** `[J-09]` TEAM tier has
  `canDeploy: false / maxDeployments: 0` while PRO has `true / 5` —
  inverted ladder. Likely V141's MAX→TEAM rename inheriting V140's
  defaults. NEEDS USER DECISION before fix (which value is correct).
- **Two security holes from the coverage pass:** `[J-14]` SSO
  `enforced=true` toggle works but the SAML handshake itself is
  unimplemented per `SsoConfigController` Javadoc — customer can lock
  out their own org with no recovery path. `[J-21]` AuthCallbackPage
  writes `refresh_token` to `localStorage` in addition to the HttpOnly
  cookie — XSS exfiltration surface.
- **Production blockers from the failure-path pass:** `[D-02]`
  OutlinePlannerAgent has no wall-clock timeout (stalled provider →
  PLANNING forever). `[D-06]` GitHub PAT update accepts bogus tokens
  silently (`PUT /api/auth/settings/github` with malformed PAT returns
  200 + `hasGithubPat:true`).
- **Competitive positioning:** PRO at $20 was the floor not the
  bargain — every Tier 1 has a $20-$30 tier. **MAX BYOK at $29 is
  genuinely unique.** Devin's $500→$20 price drop (per fetched pricing)
  signals consumer-ization of the autonomous-agent category.
- **Mobile is actively broken** at narrow widths (V3TopBar clipping
  confirmed across 3 viewports per Prompt E). WCAG AA is **partial
  pass** — automated baseline is clean, but no real screen-reader
  validation = can't sign a VPAT.

**Net position:** the engine works (browser validation v2 confirmed
4-for-4 real runs across providers); the surface around it is full
of high-leverage small fixes. Sprint 3 should pick the 3 P0s and ship.

---

## §2 — Reports produced this pass

| # | Prompt | Deliverable | Words | Key headline |
|---|---|---|---|---|
| A | Competitor landscape | [audit-output/COMPETITIVE_LANDSCAPE.md](audit-output/COMPETITIVE_LANDSCAPE.md) | 2,500 | "Mergeable PRs with proof of correctness" is the defensible 12-month position |
| B | Persona journeys (4 personas) | [audit-output/PERSONA_JOURNEYS.md](audit-output/PERSONA_JOURNEYS.md) | 3,199 | F-03 fix is dead code post-V162 [J-01] — fixed this session |
| C | UX surface coverage (14 surfaces) | [audit-output/UX_SURFACE_COVERAGE.md](audit-output/UX_SURFACE_COVERAGE.md) | 2,939 | CmdK admin actions are stubs; PRO bullet contradicts F-28 [S-03] — fixed this session |
| D | Failure paths (10 paths) | [audit-output/FAILURE_PATHS.md](audit-output/FAILURE_PATHS.md) | 3,195 | 2 production blockers: D-02 (no plan timeout), D-06 (PAT no-validate) |
| E | Mobile multi-viewport | [audit-output/MOBILE_AUDIT.md](audit-output/MOBILE_AUDIT.md) | 2,902 | V3TopBar broken on every V3 route × every viewport — log out + quota chip off-screen for FREE/PRO mobile [M-01/02/03 = P0]; mobile not shippable |
| F | Accessibility | [audit-output/ACCESSIBILITY_AUDIT.md](audit-output/ACCESSIBILITY_AUDIT.md) | 3,022 | WCAG AA partial; SSE state changes not announced |

Total cap discipline: ≤21k words across 6 reports vs the prompt's
~5-10k each (cumulative ~30-60k full-depth). Right-sized for one session.

---

## §3 — Coverage-pass findings — severity-ranked top 13

```
[J-01] (P1) F-03 fix dead code post-V162 — FIXED THIS SESSION
  Severity: P1 → resolved
  See: PERSONA_JOURNEYS.md §B.1, SPRINT_FIXES_REPORT.md addendum
  Fix shipped: TierPermissionService.getPermissions personal-org guard
               + 3 new unit tests in TierPermissionServiceSoloUserTest
               (11/11 total green).

[S-03] (P1) PRO card bullet contradicted F-28 + V145 — FIXED THIS SESSION
  Severity: P1 → resolved
  See: UX_SURFACE_COVERAGE.md §C.12
  Fix shipped: pricing.ts PRO bullet "Unlimited runs / month" →
               "20 credits / month (grandfathered cap)".

[J-02] (P0) FREE LOW Fibonacci dispatch 500s synchronously in 29ms
  Severity: P0 (DEFERRED — needs Spring log root-cause)
  See: PERSONA_JOURNEYS.md §B.1
  Pre-V162 + this session's V163 backfill should have unblocked this;
  hasn't. Empty error body suggests async handler crashes before
  ResponseStatusException advice catches it. Reproducible via curl.
  Fix path: 1-2h investigation; trace request-thread stack via
  /actuator/loggers + targeted DEBUG.

[J-09] (P0) TEAM canDeploy=false/maxDeployments=0; PRO canDeploy=true/maxDeployments=5
  Severity: P0 (DEFERRED — needs product decision before fix)
  See: PERSONA_JOURNEYS.md §B.3
  Inverted-ladder regression. Almost certainly V141 (MAX→TEAM rename)
  inheriting V140's MAX defaults. tier_config.TEAM.can_deploy needs
  to be set to TRUE + reasonable maxDeployments — but the right value
  is a tier-marketing decision (does TEAM unlock unlimited deploys
  matching its other "-1" caps, or is the cap intentional?).

[J-14] (P0) SSO config CRUD + enforced=true works; SAML handshake unimplemented
  Severity: P0 (DEFERRED — security)
  See: PERSONA_JOURNEYS.md §B.4.2
  ORG_ADMIN can flip sso_enforced=TRUE which the auth filter respects,
  but the SAML2 handshake doesn't exist (per SsoConfigController class
  Javadoc). Result: ORG_ADMIN can lock themselves out.
  Fix: either (a) reject sso_enforced=TRUE in the controller until
  the handshake ships, or (b) ship the SAML handshake (per
  docs/sso-saml-wiring.md ~3-5 days of unscoped work).

[D-02] (P1) OutlinePlannerAgent has no wall-clock timeout
  Severity: P1 (DEFERRED — production blocker before next prod cut)
  See: FAILURE_PATHS.md §3 / D-02
  Phase 2 caps at 20 min via CompletableFuture.get; Phase 1 calls
  the LLM synchronously. Stalled provider → PLANNING forever.
  Fix: ~15 LOC wrap in CompletableFuture.supplyAsync + .get(timeout).

[D-06] (P1) GitHub PAT write-without-validation
  Severity: P1 (DEFERRED — production blocker before next prod cut)
  See: FAILURE_PATHS.md §3 / D-06
  PUT /api/auth/settings/github with bogus token returns 200 +
  hasGithubPat:true. Pattern exists for AI keys
  (POST /api/auth/settings/ai-keys/validate per CLAUDE.md Phase 1.2);
  same shape missing for GitHub. Downstream silently no-ops.
  Fix: ~15 LOC mirroring AI-keys validate pattern.

[J-08] (P1) canViewCostBreakdown still false for PRO/TEAM personal-org
  Severity: P1 (RESOLVED via J-01 fix above)
  See: PERSONA_JOURNEYS.md §B.2
  Post-J-01 personal-org users hit freeUserPermissions which has the
  isMaxOrEnterprise gate — PRO still false (correct), MAX/ENT now true.
  Same fix as J-01.

[J-16] (P1) ENT non-SUPER_ADMIN gets 403 on /api/admin/payment-audit
  Severity: P1 (DEFERRED — contradicts ENT-SLA "Audit unlimited")
  See: PERSONA_JOURNEYS.md §B.4.2
  Fix: `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")` on
  PaymentAuditController OR move to a dedicated org-scoped audit endpoint.

[J-19] (P1) /api/orgs/me/standards returns 403 for personal-org ORG_ADMINs
  Severity: P1 (DEFERRED — frontend already shows OrgStandardsManager)
  See: PERSONA_JOURNEYS.md §B.4.3
  Frontend `OrgStandardsManager` / `AdrPanel` / `InvariantPanel` /
  `StandardsCompliancePanel` all exist but render empty/unauthorized
  states for personal-org users. Either (a) route personal orgs
  through a different "standards-not-available-for-personal-orgs"
  empty-state, or (b) allow standards on personal orgs.

[J-21] (P1) refresh_token written to localStorage in addition to HttpOnly cookie
  Severity: P1 (DEFERRED — XSS exfiltration surface)
  See: PERSONA_JOURNEYS.md §B.6 + AuthCallbackPage:28
  Fix: `localStorage.removeItem('refresh_token')` on legacy callback
  path; rely on HttpOnly cookie only. ~3 LOC.

[S-01] (P1) CmdK admin actions navigate to /home (false advertising)
  Severity: P1 (DEFERRED — quick fix)
  See: UX_SURFACE_COVERAGE.md §C.14 + frontend/src/v3/components/CmdK.tsx:70-73
  All 4 SUPER_ADMIN entries (admin-tiers, admin-flags, admin-audit,
  admin-halt) navigate to /home. Either wire them to real routes or
  remove from the palette.

[S-02] (P1) Library facets endpoint 404s in local
  Severity: P1 (LIKELY config gap, not bug)
  See: UX_SURFACE_COVERAGE.md §C.1
  /api/library/facets + /api/library/_facets both 404. Macro-group
  tabs silently never render (call wrapped in .catch(() => null)).
  Fix: trace the controller; either ship the endpoint or update the
  caller to not call it on local profile.

[M-01/02/03] (P0) V3TopBar broken on every V3 route × every mobile viewport
  Severity: P0 (DEFERRED — extends prior [B-05]; mobile-not-shippable)
  See: MOBILE_AUDIT.md §3
  Migrations link + UsageBadge + AvatarMenu fall off-screen at 360,
  390, 428. **A FREE/PRO mobile user cannot log out and cannot see
  their quota.** V3Shell has no hamburger pattern; AppLayout does
  (the v2 sidebar). Half the app shape-shifts on mobile.
  Fix: implement a hamburger-drawer pattern in V3TopBar.tsx (~140
  LOC component + ~80 LOC v3.css responsive rules per the audit).
  Validation criteria documented in MOBILE_AUDIT.md §5.
```

---

## §4 — Coverage-pass findings — moderate severity (P2-P3, deferred)

Not enumerated in detail here — see individual reports. Categories:

**Doc drift (P3):**
- `[D-04]` V1 endpoints documented as 410 Gone, actual is 400/404
  (FAILURE_PATHS.md). Either fix the contract or fix the doc.

**UX polish (P2-P3):**
- `[S-05]` AG-3 inline retry-per-task confirmed missing
  (UX_SURFACE_COVERAGE.md). retryTask API helper exists, no caller
  in RunDetailTabs.Tasks.
- `[D-01]` In-provider retry (429/529) is silent on the wire (no
  RUN_RETRY SSE event).

**Accessibility (P1-P2):**
- `[A-01]` No "Skip to main content" link anywhere (HIGH per WCAG
  2.4.1 Level A) — 30-min fix.
- `[A-04]` SSE run-state changes not announced to assistive tech
  (MED-HIGH per WCAG 4.1.3 Level AA). No `aria-live` regions.
- `[A-02]` RunList pagination chevrons missing aria-label (MED per
  WCAG 4.1.2 Level A) — 5-min fix.

**Security (P1):**
- `[J-21]` refresh_token in localStorage (above).
- `[D-03]` JWT errors swallowed at DEBUG — token-tamper attempts
  produce no production log.
- `[F-30]` from prior audit: RegisterPage CAPTCHA absent (verified
  in Phase 0 — vector still open).

---

## §5 — What this pass DID NOT cover

Honest enumeration of what's still missing — to inform the next audit
cycle, not to hand-wave:

- **Two-browser TEAM concurrent state** — needs distinct browser
  profiles, not faked with two tabs. PERSONA_JOURNEYS.md flags every
  concurrency claim as `[code-trace]`.
- **Stripe / Razorpay live test card flows** — local stack has billing
  disabled. CANNOT_TEST per PERSONA_JOURNEYS.md §B.5.
- **Real-device mobile** — no BrowserStack, no real iOS/Android. Mobile
  audit is Claude_Preview MCP viewport-resize only.
- **Real screen reader (NVDA / VoiceOver / JAWS)** — explicitly out of
  scope per ACCESSIBILITY_AUDIT.md §4. Cannot honestly sign a VPAT or
  WCAG conformance until run.
- **ENT real-customer interview** — no validated procurement
  requirements; ENTERPRISE redesign briefs in both audits are inferred.
- **Hands-on competitor sign-ups** — COMPETITIVE_LANDSCAPE.md is
  WebFetch + training-data only. No real TTFA measurements against
  the field.
- **Performance / Core Web Vitals / Lighthouse Performance** — no
  perf audit run.
- **Browser compatibility matrix** — Edge / Firefox / Safari desktop /
  in-app webviews (LinkedIn, Slack, Twitter) untested.
- **Email deliverability + content review** — verify, invite, password
  reset, run-complete notifications. Largely manual.
- **Localization (Hindi / Tamil / etc.)** — ExecuteSpec ships INR but
  no localized UI. Out of scope.

---

## §6 — How to use this report

For the next session:

1. **Read this synthesis first** — top 13 findings + §5 caveats.
2. **Drill into the 6 reports** as needed — each is self-contained and
   evidence-cited.
3. **Pick a priority slice** — recommended: P0/P1s in §3 plus the 3
   accessibility blockers, ~2-3 days of focused work.
4. **Don't redo what's already done** — `SPRINT_FIXES_REPORT.md`
   covers everything from `ACTIONS.md` Sprint 1 + Sprint 2 that
   shipped this session. Two of the coverage-pass findings (J-01,
   S-03) were follow-up fixes to my own Sprint 1+2 work and shipped
   in the same session.

---

## §7 — Final tally for this session

**Code shipped:**
- 5 commits (Sprint 1+2): tier-config-driven solo permissions [F-03+F-18],
  v3 IA cleanup [F-01/F-13/F-14/F-26/IA-Q4], batch UI strike [F-12],
  PRO HIDDEN copy [F-28], verify-email auto-session [F-29],
  OAuth ?spec= preserve [F-16], V163 personal-org backfill [F-10],
  migrations dry-run tier gate [F-05], deploy server-side tier check
  [F-31], classifier reason discriminator [B-N5], canViewPricing
  @deprecated [F-09], doc refresh sweep [F-08/F-11/F-22/F-28]
- 1 follow-up commit (Sprint 3): J-01 personal-org guard + S-03 PRO
  bullet copy fix + 3 new unit tests (11/11 total green)
- TOTAL: 6 commits on `audit/v2-5-11-ux-product` ahead of prior tip

**Reports shipped:**
- 6 coverage reports (this synthesis + COMPETITIVE_LANDSCAPE +
  PERSONA_JOURNEYS + UX_SURFACE_COVERAGE + FAILURE_PATHS + MOBILE_AUDIT +
  ACCESSIBILITY_AUDIT) ≈ 21k words combined
- 1 sprint synthesis (SPRINT_FIXES_REPORT.md from earlier in the day)

**LLM spend across all sessions today:** &lt; $1.50 total (4 LOW dispatches
during browser validation v2 + 0 during coverage pass — agents kept
costs low).

**Production-readiness position:** The engine ships — browser-verified
A-grade output across 3 providers. The wrapping (IA, security gates,
permission map post-V162, mobile, a11y) needs the Sprint-3 P0 batch
before next prod cut. **Stay disciplined: ship the 3 P0s + the 3 a11y
blockers + close the Sprint-1-shipped fixes via post-deploy smoke,
then re-audit.**
