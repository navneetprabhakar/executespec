# ExecuteSpec Enterprise — Coding-Agent Implementation Handoff

Status: **UX/product contract complete at CP-076; production implementation and runtime verification remain**  
Date: **2026-07-22**  
Figma: `https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S`  
Enterprise Screens page: `5:4`  
Prototype graph: `22-FIGMA-PROTOTYPE-GRAPH.json`

## 1. Start here

A coding agent must read these authorities in order:

1. `ENTERPRISE-IMPLEMENTATION-PRD.md` — 109 stable product requirement IDs.
2. `01-PRD-9.1-NAVIGATION-AMENDMENT.md` — approved Objective-centred navigation interpretation.
3. `02-PRD-TRACEABILITY-MATRIX.md` — requirement priority, state bundle, breakpoint, implementation evidence, analytics and deterministic proof.
4. `03-UX-FOUNDATION-CONTRACTS.md`, `04-PERMISSION-AND-REDACTION-MATRIX.md` and `05-RESPONSIVE-RULES.md`.
5. `06-SCREEN-STATE-MANIFEST.md`, `08-ACTION-AUTHORITY-EFFECT-RECOVERY-CONTRACT.md` and `09-PROTOTYPE-TRANSITION-MAP.md`.
6. `10-ANALYTICS-AND-ACCEPTANCE-TESTS.md`.
7. This handoff, `19-CROSS-SURFACE-CLOSURE-AUDIT.md`, `20-FIGMA-PROTOTYPE-ACCESSIBILITY-AUDIT.md` and the exported graph.

Do not infer product truth from a screenshot alone. A Figma frame expresses visual hierarchy and intended transitions; the PRD, state contracts, RBAC/redaction matrix and acceptance tests are normative.

## 2. Non-negotiable product decisions

- Enterprise and Studio are different product views and separate URLs. Studio receives no Enterprise context automatically.
- Enterprise uses an Objective-centred shell. Permanent destinations are Home, Objectives, Intelligence, Services, Evidence and Settings.
- Both desktop side panes are collapsible. Collapsed panes show named semantic icons, not numbers.
- Search context is read-only retrieval. Objective Ask/composition may propose work but cannot silently mutate a source system.
- A source system remains authoritative. Semantic similarity never chooses governing truth.
- Every consequential action exposes actor, target, scope, effect, route, cost/budget, preconditions, evidence, recovery, maturity and expiry as applicable.
- High-impact, destructive and approval actions must not use generic normal-primary treatment.
- Continuity uses a one-time, user-bound token. Opening a token is not restoration; destination authorization and source-version validation precede context restoration.
- Denied, expired and wrong-view states resolve no protected target and leak no tenant, object, member, source, graph or accessibility-tree details.
- DeepSeek, OpenRouter and Fireworks remain supported and named but currently have no logo or placeholder glyph. Do not remove provider support because the identity asset is deferred.
- Production mutation is outside the P0/P1 incident-assistance contract. Those states support investigation, recommendation, escalation and bounded Objective proposal.

## 3. Repository ownership

The planned split has five repositories with hard boundaries:

| Repository | Owns | Must not own |
|---|---|---|
| Core | Stable domain IDs and schemas; state machines; authority/evidence/redaction vocabulary; provider-neutral contracts; shared generated clients | Connector credentials, UI rendering, analytics storage |
| Service | APIs; persistence; Objective orchestration; context/knowledge graph; policy/RBAC enforcement; integration adapters; verification/test lifecycle; continuity; reconciliation | Product-specific rendering; extension-local filesystem execution |
| Extension | VS Code/CLI local-hands client; local approvals; filesystem/shell execution; durable server session protocol | Enterprise policy decisions or source-of-truth state |
| UI | Enterprise and Studio entrypoints; responsive shell; canonical components; state rendering; accessibility; safe API client | Server authorization, source truth selection, hidden provider fallback |
| Analytics | Versioned event schemas; ingestion; privacy-safe transformations; product/SLO dashboards; acceptance telemetry | Business mutation or entitlement decisions |

Cross-repository contracts are versioned in Core. Service is authoritative for authorization and state transitions. UI never reconstructs permission or completion truth from local state.

## 4. UI entrypoints and responsibilities

Recommended URL boundary:

- Enterprise: dedicated Enterprise host or `/enterprise/*` application entry.
- Studio: `https://studio.executespec.ai`.
- Switching views uses a neutral choice screen and a normal HTTPS navigation. It does not serialize active Enterprise context into the Studio URL, browser storage or analytics payload.
- Permitted cross-view references require an explicit user action and target-side reauthorization.

Enterprise navigation responsibilities:

| Destination | Primary responsibility |
|---|---|
| Home | Needs-you work, current Objectives and honest zero-information/bootstrap state |
| Objectives | Context, decisions, plans, execution, verification, delivery and collaboration around one canonical Objective |
| Intelligence | Permission-scoped query, cross-team context, relationship inspection and proposal-only follow-up |
| Services | Service/environment health, historical observations, incidents and bounded operational proposals |
| Evidence | Deterministic proof, measured/unmeasured outcomes, delivery/write-back and reconciliation |
| Settings | Integrations, models, governance, memory/data lifecycle, access/security and audit |

Inspect is contextual, not a seventh destination. Ask is an entry mechanism, not a separate permanent surface.

## 5. Canonical state model

Every rendered Objective state is derived from a server-issued tuple, not independent UI booleans:

`objective + context + decision + action + verification + delivery + permissions + source versions`

The UI must reject contradictory tuples in development/test builds. Examples:

- `Complete` with required verification unmeasured or failed.
- `Healthy` when the live source is unavailable and only historical evidence exists.
- `Restored` before destination authorization or after expiry/revocation/source drift.
- `Connected` after OAuth failure or partial connector setup.
- `Write succeeded` while external outcome is unknown.
- `Grounded answer` when the query was denied or unavailable.

Use the S0–S9 bundles in the traceability matrix. Do not collapse partial, stale, conflicting, denied, unavailable, no-result and not-searched into a generic empty/error state.

## 6. Figma implementation map

Accepted screen roots:

- 48 desktop frames at 1440×960.
- 30 tablet frames at 834×960.
- 32 mobile frames at 390×844.
- 20 named prototype starts.
- 327 canonical `Action/Button` instances: 316 wired and 11 explicitly inert.
- 131 additional prototype hotspots.
- Full root-level destinations and URLs are in `22-FIGMA-PROTOTYPE-GRAPH.json`.

Use Figma node IDs as visual references, not runtime identifiers. Stable runtime identifiers come from the PRD/state manifest.

Critical reference nodes:

| Contract | Desktop | Tablet | Mobile |
|---|---:|---:|---:|
| Neutral Enterprise/Studio choice | `166:345` | `302:19673` | `302:19907` |
| Zero-information brief | `292:16241` | `301:16123` | `301:19043` |
| Home / Needs you | `227:3203` | `251:9440` | `263:11688` |
| Context authority/conflict | `227:3470` | `301:19230` | `302:16424` |
| Plan decision | `227:3737` | `251:10003` | `263:11937` |
| Working / blocked / recovered | `227:4004`, `235:6027`, `235:6335` | `251:10185`, `290:19925` | `263:12063`, `263:12180` |
| Evidence / partial write-back | `227:4271`, `235:6639` | `251:10379`, `290:21110` | `263:12297`, `263:12433` |
| Intelligence partial/denied | `181:806`, `183:973` | `270:12803`, `273:13993` | `271:13510`, `271:13640` |
| Service/incident | `209:2309`, `239:1843`, `239:2257`, `239:2671` | `270:13019`, `270:13183` | `271:13749` |
| Connector lifecycle | `187:1120`, `189:1278`, `239:3226` | `270:13339`, `273:14146` | `271:13878`, `271:13990` |
| Model route review/block | `220:2861`, `200:1698` | desktop handoff/summary | `302:19297`, `302:19483` |
| External-link matrix | `292:17349`, `175:611`, `176:3425`, `177:663` | `282:14299`, `282:15102`, `282:15899`, `282:16704` | `282:17515`, `282:18354`, `282:19185`, `282:20025` |
| Continuity matrix | `245:1722`, `296:15631`, `176:3425`, `297:15875`, `245:2113`, `245:2417` | `283:14489`, `283:15533`, `283:16587`, `283:17647`, `283:18717`, `283:19794` | `264:12400`, `283:20866`, `264:12613`, `283:21844`, `264:12504`, `264:12715` |

## 7. API and authorization contracts

Do not invent a broad client-side mutation API from Figma button labels. For each action:

1. Resolve the PRD requirement and state transition.
2. Authorize tenant, actor, role, project/objective, source scope and exact target on the server.
3. Bind an idempotency key and expected source/object version.
4. Return a typed authority/effect/recovery preview before consequential execution.
5. Persist decision, action, evidence and external-write receipts separately.
6. Reconcile unknown external outcomes before retry.
7. Emit privacy-safe analytics only after the server accepts the transition.

Required failure classes include permission denied, policy denied, source unavailable, source changed, expired authority, route unavailable, environment blocked, verification failed/inconclusive/unmeasured, external outcome unknown and partial write-back. A generic 500/error banner is not an acceptable product state.

## 8. Knowledge and cross-team context

The queryable enterprise information layer must represent explicit, permission-filtered nodes and edges for:

- repositories, branches, commits, blame/ownership and releases;
- documents, versions, pages, decisions and citations;
- Jira work, comments, plans and status history;
- Slack/Teams discussions only under conditional authorized search;
- services, environments, deployments, incidents and operational evidence;
- teams, people, subject-matter expertise, current work and planned work;
- Objectives, decisions, actions, verification, delivery and supersession.

Startups with zero connected information are a first-class state. The system may store user-reviewed declared context and bootstrap proposals, but must not fabricate retrieved history or cross-team knowledge.

Use lexical lookup for exact identifiers, vector retrieval for semantic candidates and graph traversal only for explicit relationship questions. The final answer must carry provenance, freshness, authority and omissions regardless of retrieval technique.

## 9. Accessibility implementation contract

Figma’s final structural audit passes, but browser behavior must be implemented and tested:

- Logical focus order follows visual/document order after reflow.
- A skip link precedes shell navigation.
- Desktop order: top bar → left navigation → primary workspace/actions → Inspect toggle/content → Search context.
- Tablet Inspect and mobile navigation overlay are modal: background inert and `aria-hidden`, focus trapped, Escape/close/back supported, focus restored to the invoker.
- Mobile pushed routes use normal document order and restore the originating control when popped.
- Icon-only controls use the semantic names in Figma; tooltip text is not the only accessible name.
- Loading/disabled controls expose state and reason; they do not appear as unexplained dead buttons.
- Progress is a polite live region. Immediate blocking failures may be assertive. Do not repeatedly announce streaming logs.
- Reduced motion replaces movement with an instant state change without losing state, focus or evidence.
- Minimum target: 44×44 touch; desktop may use 40px canonical buttons but never below the WCAG 24px minimum.
- Text contrast must remain WCAG 2.2 AA in every state and theme.

## 10. Responsive, zoom and localisation tests

Figma provides fixed 1440, 834 and 390 references. It cannot prove browser reflow. Required automated tests:

- At 200% zoom, a 1440px browser behaves as a 720 CSS-pixel viewport and follows the compact/mobile responsibility, with no two-dimensional page scroll.
- At 320, 390, 720, 834, 1024 and 1440 CSS pixels, primary content and every action remain reachable.
- Expand every user-facing string by 30%, including repository, provider, model, person, project and incident names.
- Long unbroken identifiers wrap or truncate only with a programmatically available full value.
- No horizontal scrolling inside the page shell; data tables may use a labelled, keyboard-operable local scroll region when unavoidable.
- Touch safe areas and virtual-keyboard overlap are tested on mobile.
- Locale changes do not change action authority or hide state qualifiers.

Use Playwright screenshots plus DOM assertions; pixel comparison alone is insufficient.

## 11. Deterministic test plan

Minimum layers:

- Domain/state-machine tests for every allowed and forbidden S0–S9 transition.
- Tenant/RBAC/source-ACL tests proving zero protected facts in UI DTOs, caches, exports, notifications, analytics and accessibility names.
- Contract tests for every connector capability, identity, pagination, rate limit, version, retention and reconciliation behavior.
- Idempotency and unknown-outcome tests for external writes.
- Generated-test lifecycle tests: impact discovery, test generation, suite validation, CI trigger, on-demand trigger, flake/inconclusive handling and evidence persistence.
- Component tests for all canonical variants, accessible name/role/value/state, focus visibility, target size and contrast.
- Playwright execution of SC-01 through SC-14 on required breakpoints.
- Graph/query tests for permission filtering, provenance, freshness, omissions, authority conflict and zero-information behavior.
- Analytics tests proving one safe event per accepted business transition and no content leakage.

The scenario pass criteria are defined in `10-ANALYTICS-AND-ACCEPTANCE-TESTS.md`. Use `AT-<PRD-ID>` names so failures map back to the 109-ID matrix.

## 12. Delivery sequence

1. Freeze Core schemas, state enums, authority/evidence envelopes and analytics event versions.
2. Build server-side tenant/RBAC/redaction and transition tests before UI mutation paths.
3. Implement Enterprise/Studio URL boundary and neutral entry.
4. Implement shell, responsive navigation, Inspect and accessibility primitives.
5. Implement zero-information/bootstrap and Objective/context/decision flow.
6. Implement action/verification/delivery/reconciliation.
7. Implement Intelligence and the permission-filtered information layer.
8. Implement Services/incidents with proposal-only P0/P1 authority.
9. Certify connectors and model-route governance.
10. Implement external links and continuity last, after the underlying authorization/version contracts are proven.
11. Run SC-01–SC-14, 200% reflow, 30% expansion, redaction, contrast, keyboard and integration suites.
12. Release only when production evidence replaces every `NET_NEW`, `PARTIAL` or `UNVERIFIED_LEGACY` assumption relevant to the launch slice.

## 13. Definition of done

A feature is not done because its happy-path frame matches Figma. It is done only when:

- the PRD ID and analytics event are linked;
- allowed, denied, stale, partial, unavailable, expired, changed and recovery states required by its bundle exist;
- server authorization and idempotency are deterministic;
- UI copy and accessibility output obey redaction;
- responsive and keyboard behavior pass;
- integration behavior is certified against a real sandbox;
- external writes reconcile;
- verification evidence is persisted and queryable;
- browser tests cover the accepted Figma transition;
- the traceability matrix records the test command and result.

The current Figma pack is implementation-ready design evidence. It is not evidence that the existing code already implements the Enterprise product.

