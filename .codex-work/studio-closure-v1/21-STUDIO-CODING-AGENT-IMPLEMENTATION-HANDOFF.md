# ExecuteSpec Studio — Coding-Agent Implementation Handoff

Version: 1.0  
Date: 2026-07-23  
Status: **Implementation-ready product and UX authority**  
Figma: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L>

## 1. Mission

Implement ExecuteSpec Studio as the individual-user product for casual builders, freelancers, consultants, and solo founders. Studio turns an idea or requirement into a working, verified, publishable or deliverable software product while remaining simple by default and progressively exposing professional control.

This handoff does not authorize a redesign. Match the accepted Figma, product language, component grammar, responsive behavior, and durable state contracts.

## 2. Authority and required read order

Coding agents must read these documents before changing code:

1. `01-STUDIO-MASTER-PRD.md` — final product authority.
2. `02-PRD-TRACEABILITY-MATRIX.md` — all 173 stable requirements and exact evidence.
3. `03-UX-FOUNDATION-CONTRACTS.md` — shell, object language, state, entitlement, and accessibility grammar.
4. `04-SCREEN-STATE-MANIFEST.md` — complete state inventory.
5. `07-CURRENT-STATE-PARITY-AUDIT.md` — existing, partial, planned, and missing backend/product behavior.
6. `08-STUDIO-DECISIONS-AND-CONFLICTS.md` — resolved product conflicts.
7. `09-AUTHORITY-EFFECT-RECOVERY-MATRIX.md` — consequence and recovery rules.
8. `10-RESPONSIVE-RULES.md` — desktop/tablet/mobile behavior.
9. `11-COMPONENT-AND-VARIANT-INVENTORY.md` — canonical UI components.
10. `13-ANALYTICS-AND-ACCEPTANCE-TESTS.md` — event and acceptance contract.
11. `19-FIGMA-PHASE-6-PROTOTYPE-GRAPH.md` — exact playable routes.
12. `20-STUDIO-ADVERSARIAL-UX-AUDIT.md` — audit result and remaining implementation gates.

When code and product documents disagree, do not silently preserve current code behavior. Record the mismatch, preserve stable requirement IDs, and implement the final PRD unless an architecture/security constraint requires explicit product escalation.

## 3. Non-negotiable product invariants

1. Studio and Enterprise are separate views and route trees. Shared services do not justify a shared UX shell.
2. Studio has three durable surfaces: Home, Project, and Account.
3. Project has four lenses: Build, Data, Checks, and Ship.
4. Casual and professional modes share Project, Task, Output, Brief, Version, Change, Check, Decision, Release, Resource, and history.
5. Professional Mode adds depth inside the same Studio shell; it does not migrate or duplicate a Project.
6. Free, Pro, and Max use one information architecture and interaction grammar.
7. Free maps to `FREE`, Pro to `PRO`, and Max to `PRO_PLUS` internally. Never expose `PRO_PLUS` or `Pro+` to Studio users.
8. BYOK is a separate key-source and billing route under Account → AI providers. It is not Max.
9. Team and Enterprise are graduation destinations, not Studio tiers.
10. Upgrade, downgrade, failure, reconnect, and dismissal preserve the exact Task, checkpoint, evidence, and intended action.
11. Both desktop side panes collapse independently to official icon rails. Collapsed state never displays numbers.
12. The centre Task remains dominant.
13. Mobile is a decision, review, verification, publish, rollback, and recovery surface. It is not a full desktop source editor.
14. Generated tests cannot become evidence until the suite itself is validated.
15. Client identity, source identity, scope, authority, environment, and cost boundary remain visible before professional actions.
16. Client data, code, secrets, memory, analytics, and reviewer directories never cross client boundaries.
17. Progress and gamification derive from verified outcomes, are optional, and never reward spend, token usage, prompt count, or generated lines.
18. Third-party and model-provider names remain visible. Their logos are deliberately deferred.

## 4. Repository boundaries

The planned repository topology is five explicit repositories. A monorepo may be used temporarily, but these dependency boundaries must be enforceable at folder, package, ownership, and CI levels.

| Repository | Owns | Must not own |
|---|---|---|
| Core repo | canonical domain objects, shared schemas, policy types, authority/effect contracts, event envelopes, generated clients, compatibility rules | HTTP controllers, provider implementations, React views, analytics warehouse logic |
| Service repo | identity, billing, entitlements, agent execution, repositories, resources, tests, CI, preview/deploy, release, evidence, integrations, persistence, API implementation | Studio component rendering, Enterprise route composition, CLI/VS Code presentation |
| Extension repo | CLI, VS Code extension, local-hands runtime, device/PAT authentication, terminal permissions, remote-brain/local-hands protocol | managed cloud execution internals, Studio web UI, analytics warehouse |
| UI repo | Studio and Enterprise applications as separate route/shell packages, shared design tokens/components where semantically valid, browser state, accessibility, responsive behavior | execution orchestration, provider credentials, authoritative billing calculations |
| Analytics repo | redacted product-event ingestion, semantic models, quality funnels, experiments, dashboards, retention policy | raw prompts, code, secrets, client names, repository contents, synchronous product decisions |

Allowed dependency direction:

`UI → Core contracts → Service APIs`  
`Extension → Core contracts → Service APIs`  
`Service → Core contracts`  
`Analytics ← redacted events from UI/Service/Extension`

Core must not depend on Service, UI, Extension, or Analytics. Analytics must never sit on the synchronous execution path.

If the work remains in a monorepo initially, use top-level boundaries equivalent to:

```text
/core
/services
/extensions
/ui/studio
/ui/enterprise
/ui/shared
/analytics
```

Add architecture tests that reject reverse dependencies and prevent Studio from importing Enterprise route/shell modules.

## 5. Route and surface contract

Minimum route tree:

```text
studio.executespec.ai/
├── /                         Home
├── /p/:projectId             Project shell
│   ├── build                 Task, Preview, Changes, Versions
│   ├── data                  Resources, app users, storage, functions, APIs, secrets
│   ├── checks                Checks, generated suite, evidence, repair, CI
│   └── ship                  Preview/release, environments, health, sharing, rollback, handoff
└── /account
    ├── appearance-accessibility
    ├── ai-providers
    ├── connections-permissions
    ├── billing-usage
    ├── privacy-ownership
    └── progress-learning
```

Versions/history are a Project overlay or timeline, not global navigation. Project settings remain contextual. Client workspaces are a Professional Mode boundary and switcher, not another application shell.

The browser URL may encode lens, selected Output, Version, Check, Release, or review package, but URL state must resolve to durable server state. Do not make the transcript or local storage authoritative.

## 6. Canonical domain and state model

| Object | Required implementation behavior |
|---|---|
| Project | stable identity across tier/mode changes; owns resources, environments, Versions, Releases, and client boundary |
| Task | one user objective with messages, semantic phase, checkpoint, decisions, changes, evidence, and outcome |
| Output | typed selection of Preview, Changes, Checks, Data, Release, evidence, or handoff; restorable on reload |
| Brief | interpreted outcome, users, constraints, references, assumptions, and acceptance intent |
| Version | immutable checkpoint of intent, code/resources, evidence freshness, and release relation |
| Change | bounded proposed/applied modification with source effect and verification impact |
| Check | durable deterministic or assessed verification result with evidence type, freshness, and failure class |
| Decision | authority request with target, effect, evidence, expiry, reversibility, safe alternative, and unchanged state |
| Release | immutable artifact/destination/health record with rollback predecessor |
| Resource | data, app users/auth, files, function, API, secret reference, integration, domain, or runtime capability |
| ClientWorkspace | hard tenant-like boundary for client identity, Projects, sources, credentials, memory, reviewers, and usage |
| ScopeBaseline | accepted professional delivery boundary and assumptions |
| ChangeRequest | preserved divergence from ScopeBaseline with impact and explicit accept/reject result |
| Handoff | client-safe package linking release, evidence, acceptance, documents, ownership, and maintenance state |
| UsageLedger | attributable estimate/actual/cap by client, Project, Task, phase, provider, model, and key source |
| Progress | verified skill/quest/achievement state with opt-out and no spend/activity scoring |

Required Task states:

`READY | WORKING | NEEDS_INPUT | COMPLETE | NEEDS_ATTENTION | PAUSED`

Required reason detail includes empty, loading, partial, stale, conflicting, unavailable, offline, denied, expired, rejected, invalidated, cancelled, verification-failed, deployment-degraded, limit-reached, cost-cap, client-mismatch, and recovered/superseded states.

Internal pipeline states may be richer. Map them deterministically to this product vocabulary; never expose volatile tool counts, chain-of-thought, internal crew names, or invented percentages.

## 7. UI shell and component contract

### Desktop

- Default: left 248 px, centre 792 px, right 400 px at 1440×1024 reference viewport.
- Both panes collapse independently.
- Both collapsed: 48 px left rail, 1344 px centre, 48 px right rail.
- Pane state is user preference; selected Task/Output is durable route state.

### Tablet

- 1024×1366 reference composition.
- 64 px official navigation rail.
- One dominant Task with contextual Output; drawers/sheets replace the second persistent pane.
- No hover-only action.

### Mobile

- 390×844 reference composition.
- 64 px top bar, 603 px Task region, 109 px composer region, 68 px bottom navigation.
- One primary surface at a time.
- Minimum 44×44 CSS-pixel touch targets.
- Dense diff/source editing hands off honestly to desktop/CLI/VS Code.

Canonical Figma component sets:

| Component | Node |
|---|---:|
| Button | `6:21` |
| IconButton | `6:47` |
| StatusBadge | `6:75` |
| TierBadge | `6:84` |
| NavRow | `6:103` |
| TaskRow | `6:134` |
| Composer | `8:42` |
| DecisionCard | `8:93` |
| OutcomeCard | `8:129` |
| RecoveryCard | `8:165` |
| SettingsGroup | `8:190` |
| ProfessionalBoundary | `8:206` |
| OutputPanel | `10:140` |
| PaneToggle | `11:42` |
| CollapsedPaneRail | `11:69` |
| MobileNavigation | `11:138` |
| TabletNavigationRail | `11:191` |
| SurfaceRow | `11:232` |

Do not implement screen-local lookalikes when a canonical component and variant exist. Component variants must cover state, emphasis, effect, tier, mode, and responsive differences without changing the underlying meaning.

## 8. Screen implementation inventory

Accepted Figma sections:

- Free/Pro desktop: `13:2`, `15:206`, `17:522`, `20:711`, `23:962` — D01–D40.
- Max/professional desktop: `32:1238`, `34:1518`, `35:1798`, `36:2078`, `37:2358`, `38:2638`, `39:2918` — D41–D93.
- Tablet: `44:3093`, `47:3427`, `48:3763` — T01–T20.
- Mobile: `49:3931`, `52:4306`, `57:4661` — M01–M30.

Use `02-PRD-TRACEABILITY-MATRIX.md` for the exact requirement-to-node mapping. Do not implement screen numbers as route names or business states; they are design evidence identifiers.

## 9. API and effect contracts

Do not invent REST paths from the mockups. Reconcile these product operations against the existing service API, then add versioned contracts only where parity is missing.

Every mutation command must support:

- actor and authentication source;
- Project and optional ClientWorkspace scope;
- Task and expected checkpoint/version;
- source/repository/branch/SHA where relevant;
- target environment or external destination;
- requested effect and bounded authority;
- idempotency key;
- expected current state/precondition;
- cost/usage cap where material;
- structured success, partial, denied, stale, conflict, unavailable, and retryable outcomes;
- durable audit/evidence reference;
- recovery or compensating action.

High-consequence operations require a propose/confirm/result protocol:

1. Propose exact target, effect, evidence, cost, authority, expiry, reversibility, and unchanged state.
2. Confirm an expiring, actor/project/action/target-bound authority token.
3. Execute idempotently.
4. Persist result and evidence before the UI represents completion.
5. Expose retry, repair, reconnect, restore, rollback, or support recovery.

This protocol applies to purchase/top-up, external connection, secret use, repository mutation, commit/push/PR, test generation that writes files, CI configuration/trigger, publish/deploy, public sharing, collaborator invite/revoke, personal-memory promotion, export, deletion, offboarding, and graduation.

## 10. Verification and CI contract

The Checks lens must implement the complete P1 testing capability:

1. Build a test topology from changed components, contracts, data/auth paths, user journeys, environments, and accepted scope.
2. Select impact/risk coverage; do not regenerate an unrelated whole suite by default.
3. Generate happy-path, edge/error, regression, contract/integration, and journey tests as appropriate.
4. Validate generated tests for deterministic assertions, meaningful failure, fixture isolation, mutation sensitivity, source consistency, and non-duplication.
5. Mark invalid or weak generated suites as non-evidence.
6. Run locally, in managed isolation, or through CI according to source and authority.
7. Trigger automatically for configured branch/PR events.
8. Trigger on demand from the same durable Check definition.
9. Classify failures as product regression, environment, flaky signal, infrastructure/tooling, authorization, or unknown.
10. Preserve logs, command, source SHA, environment, suite version, pass/fail counts, and evidence freshness.
11. Create a bounded repair Task; do not silently mutate after a failed CI run unless pre-authorized.
12. Present a client-readable verification summary without hiding the technical evidence.

Minimum test categories:

- unit/component;
- API/contract;
- integration/data/auth;
- browser end-to-end;
- accessibility automation plus manual keyboard/screen-reader checks;
- responsive/visual regression;
- deployment smoke and health verification;
- rollback verification;
- generated-suite quality/mutation checks.

## 11. Safety, privacy, and redaction

- Store secrets only in approved secret stores; UI and events retain references, never values.
- Local hands defaults to no source upload. Commands, files, and authority are explicitly approved and revocable.
- All queries, memory, analytics, review packages, exports, and logs carry Project and ClientWorkspace scope.
- Personal-template or personal-memory promotion uses reviewable sanitisation and excludes client names, source links, artifacts, code, credentials, documents, design assets, metrics, decisions, and confidential evidence.
- Public/share/client-review views are explicit allowlists, not redacted internal pages.
- Deletion lists every affected object, retention/legal-hold rule, schedule, irreversibility point, and recovery window.
- Export/offboarding stops on any client-boundary mismatch.
- Analytics exclude prompts, code, secrets, client names, repository contents, document bodies, and unredacted error text.

## 12. Analytics contract

Every event includes:

`studioSurface, tier, personaMode, projectId, taskId?, source, stateBefore, stateAfter, outcome, recoveryUsed, latencyBucket, redactedErrorClass`

Primary funnels:

- idea/import → Brief accepted → first Preview;
- first Preview → meaningful Change → passing Check;
- passing Check → Release → retained use;
- gated intent → plan decision → same-Task continuation;
- professional intake → ScopeBaseline → verified PR/Release → client acceptance;
- failure → recovery → successful retry/restore/rollback;
- casual → Professional Mode → preserved Project continuity.

Guardrails:

- cross-client leakage: zero;
- false working/published/verified claims: zero;
- unsafe/stale authority execution: zero;
- data-loss and failed-recovery rate;
- upgrade interruption and downgrade lockout rate;
- accessibility task completion;
- client review content-boundary violations.

## 13. Implementation phases

### I0 — Contract and repository foundations

- Freeze stable domain/event schemas in Core.
- Establish the five repository/package boundaries and dependency tests.
- Generate service/UI/extension clients from one versioned API schema.
- Add feature flags and compatibility translation for current `PRO_PLUS` and BYOK data.
- Add `AT-<requirement-id>` test tagging convention.

Exit: schemas compile in all consumers; reverse-dependency tests pass; no customer-visible `PRO_PLUS`.

### I1 — Studio shell, identity, entitlements, and durable Task state

- Create the separate Studio route tree and shell.
- Implement pane collapse/restore, lenses, Output selection, reload continuity, Account groups, and responsive navigation.
- Resolve Free/Pro/Max entitlements and same-Task upgrade/downgrade behavior.
- Map service states to the canonical Studio vocabulary.

Exit: D01/D32–D40, T01, M01/M10/M11/M30 acceptance paths pass.

### I2 — Casual creation, Preview, change, and Versions

- Idea/template/import intake, clarification, Brief, plan decision, working state, Preview, conversational and visual change, Version, compare, restore.
- Preserve source consistency and evidence freshness after every change.

Exit: core and visual prototype paths pass end-to-end with refresh/reload and interruption.

### I3 — Managed resources and integrations

- Data, app users/auth, storage, functions, APIs, secrets, permissions, environment separation, unavailable-source recovery.
- Use explicit secret and external-connection authority.

Exit: J03 paths pass with success, denied, stale, unavailable, reconnect, and rollback evidence.

### I4 — Checks, generated tests, CI, and repair

- Implement the full verification contract in section 10.
- Automatic configured CI and on-demand execution must use one durable Check definition.

Exit: D15–D18 and D67–D72 paths pass; invalid generated suites cannot produce verified evidence.

### I5 — Publish, release, health, sharing, and rollback

- Managed Preview, publish review, environment/destination selection, immutable Release, post-deploy checks, degraded state, rollback, scoped share, revoke, export/remix policy.

Exit: core publish and deployment-failure paths pass; no UI declares published before durable evidence.

### I6 — Professional Mode, client/source/scope/authority

- Project-preserving Professional Mode entry.
- ClientWorkspace, GitHub, Bitbucket Cloud, local hands, dirty source, repository map, cited context, requirements, estimate, ScopeBaseline, ChangeRequest, plan, route, model/provider/BYOK, budget/time, local/cloud execution.

Exit: D41–D64, T08–T14, M12–M19 and permission/authority recovery paths pass with hard client isolation.

### I7 — Professional review, delivery, knowledge, cost, and offboarding

- Diff/review/autofix, test topology, deployment, client review, feedback mapping, acceptance, handoff, documents, sanitised reuse, cost attribution, collaborators, retention, deletion, offboarding, Team/Enterprise graduation.

Exit: D65–D93, T15–T20, M20–M29 pass; review exports are allowlisted; boundary mismatch stops all effects.

### I8 — Accessibility, responsive parity, analytics, and rollout

- Browser keyboard/focus, screen reader, live regions, reduced motion, 200% zoom/reflow, localisation expansion, responsive route/state parity, redacted analytics, dashboards, rollout and rollback switches.

Exit: no open P0/P1; AUD-P2-01 through AUD-P2-05 pass; every stable requirement has deterministic test evidence.

## 14. Test and acceptance strategy

### Deterministic traceability

- Every `STU-*` and `PRO-*` row gets one or more automated/manual assertions tagged `AT-<requirement-id>`.
- CI emits a machine-readable result keyed by all 173 IDs.
- The release gate fails for missing, duplicate, skipped-without-rationale, or stale requirement evidence.

### Required layers

1. Core schema and policy unit tests.
2. Service domain/integration tests with tenancy, idempotency, stale-write, authorization, and compensation cases.
3. Contract tests for UI and Extension clients.
4. Component tests for every canonical variant and semantic state.
5. Route/reducer tests proving reload and same-Task continuity.
6. End-to-end desktop tests for all 19 prototype assertions.
7. Tablet/mobile end-to-end tests for the responsive equivalents.
8. Visual regression at canonical viewports and major density/localisation cases.
9. Accessibility automation plus manual keyboard, VoiceOver/NVDA, reduced-motion, and zoom checks.
10. Security tests for secret redaction, tenant/client isolation, expired authority, malicious export, and analytics payloads.
11. Billing tests for Free/Pro/Max, BYOK separation, top-up, limit, upgrade, downgrade, cancellation, and read-only recovery.
12. Integration-test generation, suite-quality validation, automatic CI trigger, and on-demand trigger tests.
13. Deployment, post-deploy health, rollback, and failure-evidence tests.

### Mandatory journey assertions

Implement every transition in `19-FIGMA-PHASE-6-PROTOTYPE-GRAPH.md`. Browser tests must click the real control and assert the durable state/effect, not only the next URL or rendered title.

## 15. Rollout and observability

- Ship behind separate Studio surface and capability flags.
- Use cohort rollout by tier/mode; never silently move Enterprise users into Studio or vice versa.
- Shadow-read old/new entitlement mappings before enforcing Max.
- Observe state disagreement, failed recovery, authorization denial, stale action, publish/rollback, source reconnect, cross-client guard, analytics rejection, and accessibility errors.
- Keep a kill switch for each high-consequence mutation family.
- Rollback UI and service contracts compatibly; never orphan Project/Task/Version/Release data.

## 16. Deferred, not forgotten

- Official provider/integration logos are deferred; use names only.
- Figma comments are not retrievable through the current connector and require a separately authenticated review workflow.
- Static Figma cannot prove runtime keyboard, screen reader, authorization, redaction, tenancy, analytics, or backend effects; these are implementation gates, not accepted assumptions.

## 17. Definition of implementation complete

Implementation is complete only when:

- all 173 tagged assertions pass;
- all 19 prototype journey assertions pass on desktop and responsive equivalents pass where supported;
- Free/Pro/Max/BYOK semantics and same-Task continuity pass;
- generated-test validation, automatic CI, and on-demand execution pass;
- client/source/scope/authority/cost boundaries pass adversarial isolation tests;
- publish, health, rollback, sharing, deletion, export, offboarding, and graduation effects are durable and recoverable;
- WCAG 2.2 AA runtime gates and responsive/localisation gates pass;
- analytics schemas are redacted and complete;
- no P0/P1 defect remains;
- documentation and API schemas match shipped behavior.

