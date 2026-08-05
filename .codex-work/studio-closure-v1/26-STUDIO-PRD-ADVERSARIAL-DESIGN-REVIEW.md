# ExecuteSpec Studio — Adversarial Design Review Against the PRD

Date: 2026-07-23  
Scope: Studio UX and Figma only  
Figma: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L>  
PRD authority: `01-STUDIO-MASTER-PRD.md`  
Traceability authority: `02-PRD-TRACEABILITY-MATRIX.md`  
Decision: **not ready to close or hand to implementation**

## 1. Executive verdict

The Studio design has a credible product grammar, unusually broad recovery coverage, and a substantially better mobile experience than the earlier desktop work. It does not yet satisfy the Master PRD.

The previous claim of zero unresolved P0/P1 findings is rejected by this fresh audit. That closure was too generous because it counted the presence of named screens and traceability rows as proof that the interaction was designed. It did not sufficiently test whether the screens expose the navigation, objects, evidence, and decision contracts promised by the PRD.

Current release assessment:

| Area | Verdict | Reason |
|---|---|---|
| Product model | Amber | Project, Task, Output, Version, Check, Resource, Decision, and Release are named, but several are represented only as summary rows. |
| Navigation and IA | Red | Desktop does not visibly provide persistent Build/Data/Checks/Ship lenses or a coherent Account hub. |
| Core agent loop | Amber | Prompt-to-decision flow is clear, but it dominates object work and produces repetitive screens. |
| Inspectable Output | Red | The right pane is mainly generic context metadata rather than the actual preview, diff, test output, data, or release evidence. |
| Trust and recovery | Green/Amber | Failure, restore, rollback, limit, client-boundary, and source-unavailable states are broad and explicit; authority detail is inconsistent. |
| Professional depth | Amber | The required journeys exist, but many professional workflows are copy-swaps rather than purpose-built tools. |
| Mobile | Green/Amber | Strong hierarchy, honest desktop handoff, and good action clarity; persistent composer and repetitive card grammar remain. |
| Tablet | Red/Amber | Screens exist, but the layout behaves like a stretched decision canvas rather than an adaptive tablet work model. |
| Accessibility evidence | Red/Amber | Static frames suggest legibility and contrast risks; runtime keyboard, focus, screen-reader, zoom, and motion behavior remain unverified. |
| PRD traceability | Red | 173 requirements collapse into 28 screen groups and one generic authority/effect/recovery phrase; this proves cataloguing, not interaction completeness. |

Blocking severity: **3 P0, 8 P1, 6 P2**.

## 2. Audit method and evidence

This review used fresh Figma screenshots from the current Studio Screens page (`1:4`) and checked them against:

- all 173 stable PRD requirements: 81 `STU-*` and 92 `PRO-*`;
- the 20 canonical journeys;
- the Home / Project / Account surface contract;
- the Build / Data / Checks / Ship lens contract;
- the left context / center Task / right inspectable Output shell contract;
- authority, effect, recovery, freshness, expiry, cost, and reversibility requirements;
- desktop, tablet, and mobile behavior rules;
- WCAG 2.2 AA intent and the documented input/focus/touch requirements.

Fresh screenshot evidence is stored under `evidence/adversarial-prd-2026-07-23/`.

## 3. Journey review

### 1. Free and Pro entry, brief, and first task — At risk

The entry screens are calm and understandable. The prompt, status, details, and decision model are consistent. However, returning Home, templates/imports, and first-run guidance are not materially different experiences. Desktop navigation exposes Projects and Settings but not the four Project lenses required by the PRD.

Evidence: `01-p3a-free-pro-start.png`.

### 2. Build, Data, and Checks — At risk

The sequence is named and the cards describe the expected outcomes. The user cannot visibly navigate between Build, Data, Checks, and Ship as persistent lenses. Data configuration and checks are rendered primarily as Details tables and decision cards rather than as inspectable object work.

Evidence: `02-p3b-data-checks.png`, `03-p3c-checks-published.png`.

### 3. Publish, health, sharing, limits, and recovery — Mostly healthy

This is the strongest desktop family. Publish review, degraded health, rollback, sharing, revocation, plan limit, source unavailable, and pane-collapse states are all represented. The weakness is evidence depth: release diff, deployment timeline, rollback target, and health signals are summarized instead of being directly inspectable.

Evidence: `03-p3c-checks-published.png`, `04-p3d-health-sharing.png`, `05-p3e-limits-recovery.png`.

### 4. Professional entry and client isolation — Mostly healthy

Professional Mode is progressive disclosure rather than a separate app, which matches the PRD. Client ownership and isolation are explicit. The design still needs a persistent, visible professional context model; changing the title, badge, and Details rows is not enough to carry multi-client operational depth.

Evidence: `06-p4a-professional-entry.png`.

### 5. Source, context, scope, and implementation plan — At risk

Repository connection, source availability, scope confirmation, and implementation review are present. They require dedicated object views: repository map, selected branch/commit, included/excluded file scope, source freshness, assumptions, and plan-to-file mapping. The current cards describe these objects but do not let the user inspect them adequately.

Evidence: `07-p4b-source-context.png`, `08-p4c-scope-implementation.png`.

### 6. Test generation, validation, CI, deployment, review, and handoff — At risk

The PRD's full professional sequence is represented. The same generic layout is used for test topology, validation evidence, CI triggers, deployment targets, client review, and handoff. These are materially different tasks and need different output structures: test tree/results, log viewer, deployment timeline, release diff, review packet, and handoff inventory.

Evidence: `09-p4d-tests-ci.png`, `10-p4e-deploy-handoff.png`.

### 7. Cost, collaboration, safety, deletion, and graduation — Mostly healthy

Cost boundaries, client mismatch, memory sanitisation, provider/BYOK configuration, deletion, and Enterprise graduation are unusually well covered for this design stage. High-consequence actions still use nearly the same visual grammar as low-risk decisions, and authority expiry, invalidators, and unchanged assets are often absent.

Evidence: `11-p4f-cost-collaboration.png`, `12-p4g-safety-graduation.png`.

### 8. Tablet — At risk

Tablet screens exist for the important journeys, but the composition preserves a large empty center with small cards and a bottom composer. The promised one-pane-plus-overlay/drawer interaction is not visible in the captured states. The result is a scaled desktop decision canvas rather than a tablet-native work model.

Evidence: `13-p5ta-tablet.png`, `14-p5tb-tablet.png`, `15-p5tc-tablet.png`.

### 9. Mobile — Healthy with caveats

Mobile has the clearest hierarchy of the three form factors. It uses full-width cards, explicit paired actions, readable sequence, bottom Build/Data/Checks/Ship navigation, and honest desktop handoff for source-heavy work. The generic composer is still shown on Account, destructive, and reviewer states where it competes with the primary decision and consumes scarce space.

Evidence: `16-p5ma-mobile.png`, `17-p5mb-mobile.png`, `18-p5mc-mobile.png`.

### 10. Cross-PRD traceability and accessibility — Not ready

The traceability matrix is exhaustive as an inventory but weak as release evidence. All 173 rows use the same generic authority/effect/recovery statement, while only 28 screen groups and 28 action labels carry the entire product. This obscures whether each consequential requirement has its own target, effect, unchanged scope, evidence freshness, cost, recovery, expiry, and valid verbs.

Static Figma frames cannot prove keyboard order, visible focus, focus return, screen-reader naming, live-region announcements, focus traps, reduced motion, 200% zoom, localization expansion, or runtime touch behavior. The audit therefore does not claim accessibility compliance.

## 4. P0 blockers

### P0-01 — The desktop information architecture does not expose the PRD's product model

The PRD defines three durable surfaces—Home, Project, Account—and four persistent Project lenses—Build, Data, Checks, Ship. Desktop visibly exposes a project list, New project, and Settings. It does not expose persistent lens navigation, and it lacks a coherent Account hub. Mobile carries the four lenses more clearly than desktop.

Impact:

- users must infer navigation from the current agent state or prompt;
- task history and product navigation are conflated;
- capabilities become difficult to rediscover after the guided journey;
- Account responsibilities such as plan, providers, privacy, and project settings fragment across isolated screens.

Required correction:

- add an explicit, compact Project lens switcher that survives Tasks and Outputs;
- add a desktop Account surface with the six approved job-based groups;
- preserve the current low-surface-count strategy—this is navigation inside the existing shell, not a new app.

PRD conflicts: navigation contract, durable surfaces, Project lens persistence, settings IA, rediscoverability.

### P0-02 — The Output pane is not an inspectable Output surface

The right pane repeatedly shows a domain label and generic live-context rows such as Source, Evidence, and Scope. Large areas are unused. The PRD requires the right pane to expose the actual object: preview, changed files/diff, data resources, check results/logs, deployment/release evidence, or review package.

Impact:

- “show before explain” is not satisfied;
- users cannot independently verify agent claims;
- professional journeys appear complete without exposing the artifact needed to approve them;
- desktop space is consumed without providing depth.

Required correction:

- define Output modes with purpose-built anatomy: Preview, Changes, Data, Checks, Release, Evidence;
- make the center decision reference selectable evidence in the Output pane;
- use the pane for artifacts, not a second summary of the center card;
- collapse it when no inspectable output exists.

PRD conflicts: inspectable Output, evidence model, source/plan/test/deploy/client review journeys, center dominance.

### P0-03 — The current traceability closure overstates interaction coverage

The matrix maps 173 requirement IDs to only 28 screen groups and reuses one authority/effect/recovery phrase for every requirement. Distinct workflows are frequently represented by the same Details table + Decision/Outcome card template with changed copy.

Impact:

- high-consequence actions can be marked covered without their full decision contract;
- implementation agents would receive ambiguous behavior for source selection, scope, tests, CI, deployment, billing, BYOK, client boundaries, deletion, and rollback;
- deterministic acceptance cannot distinguish a named screen from a usable workflow.

Required correction:

- replace grouped generic trace rows with requirement-specific interaction assertions;
- identify which requirements are non-visual, which share a component, and which require a distinct object view;
- require evidence node, trigger, authority, effect, recovery, invalidators, and terminal state for each consequential requirement;
- reopen S4, S9, S10, and S11 until the interaction-level mapping is complete.

PRD conflicts: all-requirement traceability, authority/effect/recovery, implementation handoff, deterministic acceptance.

## 5. P1 high-priority gaps

### P1-01 — Desktop workflows are over-templated

Ninety-three desktop screens exist, but many are copy variants of the same title, Details table, decision card, empty center, composer, and generic Output pane. Screen count is not workflow depth.

Correction: create purpose-built work areas for source, scope, plan, changes, tests, CI, deploy, review, handoff, billing, and deletion.

### P1-02 — DecisionCards do not consistently satisfy their own contract

Many consequential cards omit one or more of: what remains unchanged, evidence freshness, cost, expiry/invalidators, recovery path, or explicit secondary verb.

Correction: define low-, medium-, and high-consequence card variants; require all nine fields for publish, pay, connect, grant, delete, restore, rollback, provider, and client-boundary actions.

### P1-03 — Tablet is not a distinct adaptive interaction model

The frames demonstrate re-sizing, not the documented pane-to-overlay/drawer behavior. Empty space remains high while evidence depth remains low.

Correction: design explicit tablet states for left rail open, Output drawer open, full-screen preview, decision sheet, keyboard-visible composer, and orientation change.

### P1-04 — Professional Mode adds copy more often than operational structure

Professional screens cover the nouns but often not the tools. Client, repository, test, CI, deployment, review, and handoff work needs visible structure and inspectable evidence.

Correction: add progressive modules inside the same shell rather than a second product surface.

### P1-05 — Consequence hierarchy is visually weak

Low-risk continuation, publish, upgrade, BYOK, restore, revoke, and delete use closely related cards and button emphasis. Danger is sometimes conveyed mainly by border or small status color.

Correction: differentiate irreversible/destructive, billable, externally visible, and privilege-granting actions through layout, hierarchy, confirmation copy, and recovery visibility—not color alone.

### P1-06 — Typography and contrast present accessibility risk

Small monospaced labels, muted teal text, compact rails, and dense right-pane metadata are difficult to scan. Static screenshots cannot establish WCAG contrast, but the visible risk is material.

Correction: audit semantic color pairs numerically, establish minimum body/metadata sizes, strengthen information hierarchy, and verify at 100% and 200% zoom.

### P1-07 — Loading and latency states are underdesigned

“Working” states exist, but skeletons, progressive evidence, reconnecting, stale-data refresh, partial-output arrival, and delayed external providers are not visible as first-class layouts.

Correction: design loading, streaming, reconnecting, stale, and partial-result variants for center and Output independently.

### P1-08 — The universal composer competes with object work

The composer appears on nearly every screen, including Account, destructive confirmation, review, and resolution states. This makes conversation look primary even when the user should inspect an object or make a bounded decision.

Correction: make the composer contextual, minimized, or absent when the screen's primary job is review, settings, confirmation, or evidence inspection.

## 6. P2 refinements

1. Reduce the visibly unused center and Output space; use it for artifacts or collapse the pane.
2. Make returning Home, templates, imports, and duplicate-from-existing distinct rather than near-identical entry cards.
3. Increase rail/icon spacing and hit-area clarity without increasing the number of surfaces.
4. Improve preview fidelity; generic mock previews risk implying completion without showing the user's real artifact.
5. Keep verified progress optional, but move it closer to earned outcomes rather than treating it as another settings/decision card.
6. Remove the persistent mobile composer from Account, deletion, client review, and other bounded-action screens.

## 7. Strengths worth preserving

- Studio remains one product with one shell across Free, Pro, and Max.
- Failure and recovery coverage is broader than most products at this stage.
- Upgrade continuity correctly resumes the same Task rather than forcing a restart.
- BYOK and Max are not conflated.
- Client isolation, memory sanitisation, source failure, limits, rollback, and deletion are explicitly acknowledged.
- Mobile does not pretend to provide full desktop source editing.
- Mobile Build/Data/Checks/Ship navigation is clear and compact.
- Status is usually conveyed through text as well as color.
- Action labels are generally specific verbs rather than generic “Continue”.
- The side-pane collapse pattern uses icons, not numbers.
- The visual system is consistent and materially sharper than the earlier Studio direction.

## 8. Accessibility and evidence limits

This audit is based on static Figma screenshots and page metadata. It does not verify:

- keyboard reachability, tab order, skip behavior, or shortcuts;
- visible focus and focus return after sheets/dialogs;
- screen-reader names, roles, descriptions, or live regions;
- focus traps in preview, drawer, and confirmation surfaces;
- reduced-motion behavior;
- 200% zoom, text reflow, or localization expansion;
- actual contrast ratios from runtime tokens;
- responsive interpolation between the represented breakpoints;
- scrolling, retained selection, or pane restoration;
- current prototype transition behavior.

These are implementation and interactive-prototype acceptance gates. They cannot be closed from frame presence alone.

## 9. Closure changes required

The closure register should be corrected as follows:

- S4 — traceability: reopen;
- S9 — Figma surfaces: reopen for object-depth and IA;
- S10 — adversarial audit: open with 3 P0 and 8 P1;
- S11 — implementation handoff: not implementation-ready;
- S12 — visual sharpness: visually acceptable, but not equivalent to product completeness.

No repository implementation should begin from the present UX package. The next design phase should resolve the three P0 items first, then re-run the full PRD audit before any coding authorization.

