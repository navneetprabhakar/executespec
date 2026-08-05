# ExecuteSpec Studio — Master Implementation PRD

Version: 1.0 final implementation authority  
Date: 2026-07-23  
Status: **Final product and UX implementation authority**  
Product surface: `studio.executespec.ai`  
Canonical Figma: [ExecuteSpec Studio — Product & UX Closure](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L)  
Audience: product, UX, engineering, QA, safety, growth, analytics, and coding agents

## 0. Document authority

This document makes the Studio product coherent across casual builders and professional individuals. It does not delete or renumber the 81 existing `STU-*` requirements or 92 existing `PRO-*` requirements.

Reconciliation is closed. Source authority is:

1. Explicit user decisions recorded in `00-CLOSURE-REGISTER.md`.
2. Persona-specific behavior in the casual and professional implementation PRDs.
3. This master PRD for shared Studio behavior and conflict resolution.
4. Approved Studio UX decisions and exhaustive feature catalog.
5. Shared platform and object-model documents.
6. Enterprise contracts only where explicitly inherited.
7. Current code for implementation reality, never for silently weakening the intended product.

This document is the primary Studio authority. The persona PRDs remain stable requirement appendices and retain all 81 `STU-*` and 92 `PRO-*` identifiers.

The Figma file is the visual and interaction realization of the PRD, not a substitute for product authority. A surface is implementation-ready only when the stable requirement IDs, traceability rows, Figma nodes/states, action contracts, analytics, and acceptance tests agree.

## 1. Product definition

### 1.1 Product promise

> Turn an idea or requirement into a working, verified, publishable product—with simplicity by default and professional control when the work requires it.

### 1.2 Category

Studio is an agentic application-building and professional software-delivery workspace for individuals.

It combines:

- idea/requirement clarification;
- prompt and direct-manipulation creation;
- managed application resources;
- repository and local workflows where needed;
- automatic verification and repair;
- preview, release, evidence, and rollback;
- client-safe professional delivery;
- ownership, export, and graduation.

### 1.3 One product, two jobs

| Job mode | Starts from | Default language | Depth |
|---|---|---|---|
| Casual creation | an idea, visual reference, template, or small change | product and outcome language | preview-first, managed defaults |
| Professional delivery | a client requirement, existing repository, issue, document, or design | product language with optional engineering detail | scope, SCM, CI, evidence, client handoff |

These are modes of one Project, not separate products. Enabling professional depth must preserve every durable object and never require rebuilding.

### 1.4 Explicit boundary from Enterprise

Studio does not expose organisation-wide work graphs, cross-team coordination, enterprise service catalogues, central governance, broad incident operations, procurement controls, or complex delegated administration by default.

Studio can reuse Enterprise-grade safety, authority, evidence, verification, repository, CI, deployment, connector, and audit primitives when a professional task needs them.

## 2. Users and jobs

### 2.1 Casual builder

- founder validating an idea;
- product manager or designer building an interactive concept;
- student, hobbyist, creator, or small-business owner;
- user who thinks in outcomes and screens rather than infrastructure.

Primary jobs:

1. Go from vague idea to working preview.
2. Refine visually or conversationally.
3. Add data, sign-in, files, functions, and APIs safely.
4. Know whether it works in plain language.
5. Publish, share, update, and recover.
6. Retain source/data ownership and graduate without restarting.

### 2.2 Professional individual

- freelancer, consultant, independent developer, solo founder, or small-agency operator;
- user who works with client boundaries, repositories, CI, environments, evidence, and handoff.

Primary jobs:

1. Understand an unfamiliar project quickly.
2. Convert requirement/design/issue/context into scoped work.
3. Estimate effort, risk, cost, dependencies, and delivery boundary.
4. Implement locally or in managed execution.
5. Generate and validate integration-aware tests.
6. Create PRs, previews, releases, evidence, and client handoff.
7. Keep clients isolated and reusable personal knowledge sanitized.
8. Graduate to team/Enterprise without losing history.

## 3. Product principles

1. **Outcome before machinery.** Start with what the user wants and what is working now.
2. **Show before explaining.** Produce inspectable output early.
3. **One obvious default.** Safe managed defaults precede technical choice.
4. **Progressive disclosure.** Reveal engineering depth only when relevant or requested.
5. **One interaction grammar.** Tiers and modes do not fork the core journey.
6. **Reversible by default.** Material changes create durable checkpoints.
7. **Verified progress.** Progress, celebration, and readiness derive from durable evidence.
8. **No fake completeness.** Mocked, temporary, unverified, unavailable, and unpublished states are explicit.
9. **Authority at consequence.** Ask only for ambiguity, cost, risk, credentials, exposure, destructive change, or irreversible effect.
10. **Ownership without lock-in.** Source, data, documents, evidence, and history are exportable.
11. **Client isolation is absolute.** No cross-client code, secret, context, memory, or analytics leakage.
12. **Upgrade at intent.** Show an upgrade when a requested outcome is gated, not as permanent interface clutter.

## 4. Commercial experience

### 4.1 Customer-facing tiers

| Tier | Price intent | Promise | Primary job |
|---|---:|---|---|
| Free | $0 | Make something real | learn, explore, prototype, publish safely within limits |
| Pro | $20/month | Make it yours | operate a serious personal product with ownership and production depth |
| Max | $100/month | Deliver professionally | client isolation, repository/CI control, evidence, and professional handoff |

### 4.2 Tier interaction contract

- All tiers use the same Home, Project, Account, Project/Task/Output objects, Build/Data/Checks/Ship lenses, and agent state grammar.
- Limits may constrain capacity, frequency, destinations, retention, advanced controls, or professional workflows.
- Safety, export, deletion, honest failure, and recovery are not manufactured upsells.
- Locked navigation is avoided. Upgrade is shown at the moment a user asks for a gated outcome.
- A plan change never forks or migrates a Project.

### 4.3 Closed entitlement mapping

- Studio Free maps to backend `FREE`.
- Studio Pro maps to backend `PRO`.
- Studio Max at $100/month maps to backend `PRO_PLUS`; the internal identifier is not exposed in customer-facing UI.
- BYOK remains a separate key-source and billing route under Account → AI providers. It is not Max and must never be presented as a fourth Studio tier.
- Existing BYOK users retain their key-source route and the same Project/Task/Output grammar.
- Team and Enterprise are graduation destinations, not Studio tiers.
- Upgrade, downgrade, limit, top-up, read-only, export, deletion, and recovery behavior preserve Project identity, the current Task, Versions, Checks, Releases, and history.

## 5. Canonical objects

| Object | Meaning in Studio |
|---|---|
| Project | durable application/product including resources, environments, versions, and releases |
| Task | one user objective/conversation and its progress, decisions, and outcome |
| Output | the most relevant inspectable Preview, Changes, Checks, Data, or Release view |
| Brief | interpreted outcome, users, constraints, references, and acceptance intent |
| Version | immutable checkpoint relating intent, changes, resources, and verification |
| Change | bounded modification proposed or applied by direct manipulation or agent action |
| Check | durable verification result with evidence and freshness |
| Decision | authority request with consequence, scope, evidence, reversibility, and expiry |
| Release | immutable deployment package, destination, health evidence, and rollback link |
| Resource | managed data, app users, storage, function, secret, integration, domain, or runtime capability |
| ClientWorkspace | Max boundary for client identity, projects, sources, credentials, memory, and usage |
| ScopeBaseline | accepted professional delivery boundary |
| ChangeRequest | explicit divergence from the accepted professional scope |
| Handoff | client/reviewer-readable delivery package |
| Progress | verified learning/building achievement derived from evidence, not activity |

Internal Run, agent, provider, tool, and pipeline records remain subordinate evidence unless a professional user explicitly opens technical detail.

## 6. Information architecture

### 6.1 Durable surfaces

| Surface | URL | Purpose |
|---|---|---|
| Home | `/` | start, continue, import, duplicate, or open a Project/Task |
| Project | `/p/{project}` | build and operate one Project through contextual Task and Output |
| Account | `/account` | identity, plan/usage, providers/BYOK, preferences, privacy, and account safety |

Project settings are contextual. Versions/history are a Project overlay/timeline. Neither becomes a top-level application.

### 6.2 Project lenses

- **Build:** conversation, direct manipulation, preview, changes, and versions.
- **Data:** app users, data, files, functions, automation, integrations, and secrets.
- **Checks:** verification, failures, repair, evidence, CI, and review.
- **Ship:** preview/public release, domains, environments, health, sharing, rollback, and handoff.

The Codex-inspired shell and these product lenses are complementary:

- left pane — Projects and Tasks;
- center — current Task, progress, decisions, outcomes, and composer;
- right pane — relevant Output tabs such as Preview, Changes, Checks, Data, or Release.

Both side panes collapse, resize, and restore. Collapsed controls show meaningful icons. The center remains the primary working surface.

### 6.3 Settings groups

1. Account
2. Project
3. Resources
4. Source & quality
5. Publish & sharing
6. Client delivery — Max or contextual upgrade only

The internal capability catalog remains metadata for coverage, entitlements, analytics, and tests; it is not visible navigation.

## 7. Agent interaction model

### 7.1 Shared states

| State | User meaning | Required behavior |
|---|---|---|
| Ready | Studio understands enough to act or asks one focused clarification | show interpreted outcome and next action |
| Working | bounded action is executing | show stable semantic phase, current outcome, pause, and guidance |
| Needs input | real authority or ambiguity blocks progress | one decision card with consequence, evidence, expiry, and safe alternative |
| Complete | requested outcome and available verification are done | show output, checks, changed objects, and next action |
| Needs attention | failed, partial, stale, unavailable, or unsafe | distinguish cause and offer retry, modify, reconnect, restore, or support path |
| Paused | execution intentionally stopped | preserve state and state what resuming will do |

### 7.2 Semantic mission phases

For non-trivial work, Studio may show:

`Understand → Build → Verify → Publish`

It must not show invented percentages, internal crew/persona names, volatile tool counts, chain-of-thought, or low-level agent chatter.

### 7.3 Conversation durability

Messages guide work, but durable state lives in Project, Task, Version, Change, Check, Decision, Resource, Release, Scope, and Handoff objects. The transcript links to these objects; it is not their database.

## 8. Primary journeys

### Shared creation and operation

1. Zero-information idea → one useful clarification → working preview.
2. Existing Project → conversational or direct visual change → new Version.
3. Add data/sign-in/files/functions/integration → permission and secret boundary → verified resource.
4. Failed Check → explanation → bounded repair/retry/revert → fresh evidence.
5. Preview → publish decision → Release → health evidence → rollback if needed.
6. Return later → immediately understand working/blocked/complete state.
7. Reach a limit → preserve project and explain options without data loss.
8. Upgrade/downgrade → retain Project identity and history.
9. Recover unavailable source, expired authorization, failed publish, or UI/durable-state disagreement.
10. Export source/data/docs/evidence or delete Project/account.

### Professional depth

11. Create/switch isolated client workspace.
12. Import GitHub, Bitbucket, local, or existing Studio Project.
13. Understand repository/context and establish Brief.
14. Requirement → estimate → ScopeBaseline → plan/authority.
15. Local or cloud implementation with branch/provider/budget control.
16. Integration-test generation, suite validation, CI/on-demand execution, and evidence.
17. PR/preview → client review → change request or acceptance.
18. Release/deploy → handoff → offboarding/maintenance.
19. Promote sanitized reusable knowledge without client leakage.
20. Graduate to Team/Enterprise without losing evidence or history.

## 9. Functional capability domains

The detailed requirement text remains under stable `STU-*` and `PRO-*` IDs. Studio closure groups those requirements as follows:

| Domain | Casual baseline | Professional extension |
|---|---|---|
| Onboarding and intent | idea, template, reference, clarification, Brief | client/repo import, capability check, scope intake |
| Generation and editing | prompt generation, visual selection, iterative Changes | repository-aware implementation, refactor, migration, local hands |
| Data and app users | managed schema, records, auth, access rules | environment/client isolation, migration depth |
| Storage and media | files, limits, access, export/delete | client policy, external storage/provider depth |
| Functions and automation | functions, webhooks, schedules within safe patterns | CI jobs, queues, integrations, operational controls |
| Integrations and secrets | guided connections, permissions, write-only secrets | client-scoped connectors, repository/CI/provider credentials |
| Versioning and recovery | automatic checkpoints, compare, restore, rollback | branch/SHA-aware invalidation, dirty-worktree safety |
| Checks and evidence | generated tests, browser/accessibility/data checks, plain-language failures | unit/contract/integration/E2E, suite validation, CI, cited review |
| Publish and release | Studio preview/subdomain, domains by tier, health, rollback | staging/production targets, PR/release evidence, reconciliation |
| Sharing and ownership | scoped links, remix policy, source/data export | client review, approval/change request, delivery ownership |
| Progress and learning | verified quests, skill map, opt-in celebration | subtle professional milestones; never productivity surveillance |
| Cost and entitlements | clear usage, limits, top-ups, safe hard-limit behavior | project/client attribution, BYOK, provider/budget controls |
| Safety and privacy | deletion, retention, access, consent, public exposure gates | hard client isolation, memory promotion, credential boundaries |
| Documentation and handoff | generated setup/explanation and export | scope/evidence/release/client handoff package |
| Graduation | source/pro mode continuity | Team/Enterprise conversion with preserved history |

## 10. Responsive contract

### Desktop

- Three-pane composition is available.
- Left/right panes collapse independently and are resizable within bounded widths.
- Preview/Output remains visually dominant.

### Tablet

- One side pane may remain persistent only when content width stays usable.
- The other pane becomes an overlay/drawer.
- Decisions, checks, compare, publish, and rollback remain complete.
- Direct manipulation supports touch-sized targets and never depends on hover.

### Mobile

- One primary surface is visible at a time: Task or Output.
- Projects/Tasks and Output tabs open as drawers/sheets.
- Persistent decisions and working state survive surface switching.
- Supported: start/continue, guide small changes, inspect preview, resolve decisions, review Checks/Changes, publish, rollback, recover, and manage essential settings.
- Complex source edits or dense compare may hand off to desktop/CLI/IDE honestly.

## 11. Trust, safety, and failure contracts

Every consequential action must state:

- affected Project/client/environment/source;
- intended effect;
- relevant evidence and freshness;
- estimated cost/usage where material;
- reversibility and rollback;
- authority required and expiry;
- what remains unchanged.

P0 state coverage must include:

- empty/zero-information;
- loading/working;
- partial/stale/conflicting context;
- permission denied;
- source unavailable/reconnect required;
- plan rejected/expired/invalidated;
- paused/blocked/cancelled;
- partial failure/recovered/superseded;
- verification failed/inconclusive/unmeasured;
- publish failed/partial/rollback required;
- limit reached/read-only;
- offline/reconnect/resume;
- client boundary mismatch;
- stale branch/SHA/scope/provider/budget/environment approval;
- destructive action confirmation and post-action recovery.

## 12. Progress and gamification

Progress is optional, quiet, and evidence-backed.

Allowed:

- verified first preview, first passing Check, first safe publish, first rollback drill;
- skill progression tied to demonstrated outcomes;
- opt-in quests, celebrations, streaks, and showcase/remix paths;
- clear next useful capability.

Forbidden:

- rewards for token use, spend, prompts, generated lines, or unnecessary execution;
- shame, loss aversion, artificial urgency, or public ranking by default;
- celebration before evidence;
- professional productivity surveillance disguised as gamification.

## 13. Accessibility and quality

- WCAG 2.2 AA target for shipped web surfaces.
- Keyboard-complete navigation, pane control, composer, decision, tabs, dialogs, history, and settings.
- Visible focus and deterministic focus return.
- Minimum 44×44 CSS-pixel touch targets on touch layouts.
- Status never relies on color alone.
- Reduced-motion and live-region semantics for progress/result changes.
- No clipped text at supported localization expansion.
- Honest skeleton/loading, empty, error, and recovery states.

## 14. Analytics and success

Candidate north star:

> Percentage of activated Studio users who reach a retained, verified, user-valued outcome within 30 days without support intervention or unsafe recovery.

Required funnels:

- idea/import → Brief accepted → first Preview;
- first Preview → first meaningful Change → passing Check;
- passing Check → Preview/Release → retained usage;
- gated intent → upgrade decision → same-Task continuation;
- professional intake → accepted Scope → verified PR/Release → client acceptance;
- failure → recovery path → successful retry/restore/rollback;
- casual → Professional Mode → retained Project continuity.

Guardrails:

- publish rollback rate;
- unsafe/expired/stale approval prevention;
- failed recovery and data-loss reports;
- cross-client leakage incidents;
- user-visible progress/evidence disagreement;
- accessibility task completion;
- upgrade interruption and downgrade lockout rates.

## 15. Release gates

1. Product authority and tier semantics closed.
2. Current-state parity audit complete.
3. All 173 requirement IDs mapped.
4. P0 journey and state manifest accepted.
5. Responsive contracts represented in Figma.
6. Authority, entitlement, privacy, secret, client, publish, rollback, and failure matrices accepted.
7. Playable Figma prototype covers the accepted graph.
8. Consolidated visual, interaction, accessibility, redaction, and traceability audit reports zero unresolved P0 failures.
9. Coding-agent handoff defines repo ownership, component contracts, routes, APIs, analytics, tests, and rollout boundaries.

## 16. Product acceptance definition

Studio is product-ready for implementation when a user can:

- start with no technical information and reach an honest working Preview;
- understand, guide, pause, resume, and recover agent work;
- inspect and modify the application without needing Enterprise vocabulary;
- add and manage resources safely;
- understand what passed, failed, or remains unverified;
- publish or deliver with explicit consequence and rollback;
- move across Free, Pro, Max, casual, professional, and later Team/Enterprise boundaries without losing durable state;
- retain ownership and avoid client/context leakage;
- complete the core journey on desktop, tablet, and supported mobile tasks.
