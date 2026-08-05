# ExecuteSpec Enterprise — Component and Variant Inventory

Status: **Canonical library contract implemented and validated in Figma at CP-076**

## Current Figma library

| Existing set | Current variants/properties | Decision |
|---|---|---|
| Button (`8:10`) | Primary, Secondary; text label | Replace with semantic action family while preserving visual token bindings |
| Objective Row (`8:17`) | Default, Selected; title/subtitle | Extend with lifecycle, attention, unread and collapsed-safe states |
| Source Row (`8:28`) | Connected, Conditional; source/meta | Replace with capability/permission/health/freshness-aware connector/source patterns |

The current three sets cannot encode the required state, authority, navigation, evidence, responsive, or accessibility contracts.

## Required component families

| Component set | Required variants | Required properties/slots | Key rules |
|---|---|---|---|
| `Action/Button` | normal, approval, high-impact, destructive, secondary, ghost, disabled; sm/md/lg; default/hover/focus/pressed/loading | label, leading/trailing icon, accessible label | one strongest action per screen; destructive never generic green |
| `Action/Card` | proposed, awaiting approval, authorised, blocked, running, failed, recovered; normal/high-impact/destructive | action type, actor, target, scope, effect, route, budget, preconditions, evidence, recovery, maturity, expiry | exact-action contract; mobile stack variant |
| `Decision/Option` | unselected, selected, disabled, conflict, recommended | title, rationale, risk, evidence, maturity | whole card selectable with visible focus and selected state |
| `Status/Compound` | all semantic classes; compact/full | process, outcome, permission, freshness, evidence completeness, icon | never collapse conflicting dimensions into one label |
| `Status/Maturity` | Current, Partial, Planned P0, P1, P2, Conditional | label, optional explanation | must not resemble health/status |
| `Navigation/Row` | expanded/collapsed; default/hover/focus/active/unread/warning/disabled | semantic SVG icon, label, badge/dot, tooltip | fixed 24px icon slot; ≥40 desktop, ≥44 touch |
| `Navigation/PaneToggle` | left/right; expand/collapse; default/hover/focus | icon, accessible label, tooltip | reversible reaction and focus preservation |
| `Shell/LeftPane` | expanded, collapsed, overlay/tablet | org/team, needs-you, Objective list, global tools, footer | no numbers as collapsed navigation |
| `Shell/Inspect` | docked, collapsed, tablet drawer, mobile route | title, close/toggle, scope, content, Search context | drawer scrim/focus trap; task-dependent default |
| `Shell/TopBar` | desktop, compact, tablet, mobile | breadcrumbs, status, Ask, notifications, account | strongest action belongs to current job, not global New Objective |
| `Objective/Row` | default, selected, needs-you, working, blocked, complete, unread, disabled | title, subtitle, lifecycle icon, source, attention | fixed anchors, no colour-only state |
| `Objective/Stage` | intent/context/decision/plan/working/verify/deliver/monitor; current/complete/blocked | stage label, status, evidence count | compact stepper on mobile; scrollable with accessible current-step semantics |
| `Composer/Objective` | empty, focused, draft, sending, error, disabled | text, attachment/source, scope summary, send | can propose work but never directly mutate |
| `Search/Context` | empty, focused, searching, partial, error, denied | query, explicit scope, modes, read-only indicator | visually distinct from Objective composer |
| `Scope/Builder` | compact, expanded, conflict, denied | org/team/project/service/repo/source/time chips | scope shown before query/action; changes can invalidate approval |
| `Source/Identity` | compact/full; available/denied/unavailable | official logo, canonical name, object label, source link | name + official logo; operational state separate |
| `Model/Identity` | compact/full; allowed/unavailable/limited/customer-hosted/logo-withheld | provider logo when approved, provider name, full model label, route/maturity | never model label without provider name; DeepSeek/OpenRouter/Fireworks omit logo until permission; neutral mark only for customer-hosted |
| `Source/Row` | healthy, stale, drift, denied, unavailable, disconnected, deletion | Source identity, capability, permission, freshness, owner | cannot display Healthy with drift/disconnect/deletion |
| `Citation/Row` | authoritative, observed, declared, generated, derived, inferred, unknown; denied | source, section/object, version, observed time, epistemic label | denied variant contains no hidden identifiers |
| `Conflict/Card` | unresolved, authority selected, resolved, superseded | sources, values, authority/freshness, rationale, action | similarity cannot resolve authority silently |
| `Answer/Panel` | complete, partial, denied, conflicting, stale, unable, timeout, greenfield | conclusion, scope, citations, conflicts, omissions, modes, actions | only complete/partial answer may say grounded |
| `Evidence/Card` | passed, failed, partial, inconclusive, unmeasured, stale | category, classification, subject version, environment, time, limitations | separate evidence categories; aggregate never hides omissions |
| `Evidence/LedgerRow` | compact/detail; same evidence states | source/artifact, attempts, classification, exact version | virtualised/table layout must remain keyboard accessible |
| `Verification/PlanRow` | required, recommended, excluded, missing, exception requested | test/boundary, reason, env/data, cost/time, owner, status | removing required item invokes exception flow |
| `Verification/RunRow` | queued, running, passed, failed, flaky, environment failed, cancelled, superseded | test/suite, SHA, env, attempts, duration, artifact | retry history visible; wrong SHA rejected |
| `GeneratedTest/Proof` | regression, new behavior, refactor, incident; valid/inconclusive/rejected | baseline/candidate, expected reason, dependency mode, assertions, mutation evidence, owner | passing-on-both is inconclusive |
| `Timeline/Event` | lifecycle, action, verification, delivery, external, learning | actor/source, time, state, evidence link | ordered, reloadable, no pipeline-noise default |
| `Service/Row` | healthy, warning, incident, unknown, unavailable | service, owner, env, observation time, active/planned work | live/history distinction explicit |
| `Connector/Card` | not connected, validating, healthy, stale, drift, failed, disconnected, purge | official identity, capabilities, permission, freshness, owner, validation | click opens connector detail; no logo-only rows |
| `Permission/Notice` | limited, denied, revoked, expired, wrong view | requested scope, safe reason, recovery | neutral/redacted shell; accessibility text also redacted |
| `Empty/State` | first use, no result, not searched, no ready work | title, explanation, allowed next action | state-specific composition; never inherited populated panes |
| `Failure/Panel` | product, environment, connector, permission, external write, unknown | class, preserved evidence, valid retry/handoff | no false success badge |
| `Drawer/Modal/Sheet` | desktop modal, tablet drawer, mobile sheet/full route | title, content, close, actions, scrim | focus trap, Escape/back, restored focus, labelled scroll region |
| `Settings/GroupNav` | expanded/compact/mobile | six groups, subsection, dirty/error badges | unsaved change guard and role-dependent visibility |
| `Settings/ChangeReview` | clean, dirty, validation failed, approval required, saving, saved, failed | changed fields, effect, audit preview, save/discard | server revalidation; one reviewed batch |
| `Memory/Row` | explicit, inferred, policy, disabled, expired | scope, evidence/confidence, last use, expiry, edit/disable/delete | private/team distinction and precedence |
| `Audit/Row` | read, action, approval, external result, deletion | actor, tenant, source, target, correlation, time, result | tamper-evident/exportable; content redacted per viewer |
| `Handoff/Card` | draft, sent, accepted, declined, redirected, expired | recipient, objective, current state, risks, evidence, requested responsibility | consent-based; no silent assignment |
| `Continuity/Card` | token created, opened, expired, revoked, source changed, completed | device, scoped objects, expiry, revoke/open | one-time user-bound token; re-authorise on destination |
| `Toast/Banner` | info, success, warning, danger; persistent/dismissible | message, action, accessible announcement | never the only record of consequential failure |
| `Loading/Skeleton` | list, panel, table, answer, timeline | known scope label, cancel where applicable | no shimmer left in completed Figma nodes |

## Tokens to add or revise

- Semantic action colours: normal, approval, high-impact, destructive, blocked, including soft/background/border/text pairs for dark and light modes.
- State colours independent of action colours: info, active, attention, blocked, failure, success, terminal-neutral.
- Typography ramp from `03-UX-FOUNDATION-CONTRACTS.md`; existing 11/12px styles become metadata-only.
- Size tokens: 32, 40 and 44 target heights; 24 icon slot; 56 collapsed rail; 240/288 expanded rail; 360/440 Inspect; tablet drawer widths.
- Focus ring tokens for normal, danger and high-contrast modes.
- Scrim, overlay, selected-row, denied/redacted, and stale-content tokens.

## Component construction gate

Before composing new screens in Figma:

1. create or extend these reusable component sets on the Components page;
2. bind local ExecuteSpec variables and text/effect styles rather than hard-coded visual values;
3. use imported official SVG assets for integration/provider identity;
4. expose label/slot properties instead of editing nested text in every instance;
5. validate dark/light, keyboard focus, 200% zoom/reflow, 30% string expansion and 44px touch variants;
6. create instances in screens—do not reproduce one-off component-shaped frames.
