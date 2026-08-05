# ExecuteSpec Studio — Strict PRD Audit, Second Pass

Date: 2026-07-23  
Scope: current Studio Figma against the Master PRD  
Figma: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L>  
Mutation: none; audit only  
Verdict: **conceptually broad, visually consistent, but not yet a usable Studio product definition**

## 1. Why this pass is stricter

The first adversarial pass asked whether the design visibly represented the PRD's capabilities and journeys. This pass applies a harder standard:

> A requirement is covered only when the current screen lets the intended user understand and complete the job, inspect the relevant object, make the consequential decision with sufficient authority context, and reach a credible success or recovery state.

Naming a preview, diff, repository, test suite, release, client package, or resource is not coverage. A summary count is not inspectable evidence. A repeated decision-card shell with different copy is not a distinct workflow.

Fresh evidence was captured from all 18 current Figma screen sections and inspected again. It is stored under `evidence/strict-pass-2026-07-23/`.

## 2. Revised verdict

The design should not be treated as implementation-ready. The stricter pass records:

- **6 P0 blockers**;
- **10 P1 product/interaction gaps**;
- **7 P2 visual/content refinements**.

The previous pass correctly identified incomplete navigation, generic Output, and inflated traceability. This pass goes further: the current design is primarily an approval dossier around an agent. It is not yet the application-building workspace promised by the PRD.

## 3. PRD promise versus current design

| PRD promise | Current evidence | Verdict |
|---|---|---|
| vague idea to working Preview | sequence and status cards exist; the Preview itself is a small generic text card | blocked |
| conversational or direct visual change | prompts and a selected-element label exist; there is no credible visual canvas or selection model | blocked |
| inspectable Preview, Changes, Checks, Data, Release | right pane primarily shows counts and context labels | blocked |
| Projects and Tasks in the left pane | Projects appear; Task/conversation history does not | blocked |
| persistent Build/Data/Checks/Ship lenses | present on mobile only; absent from desktop; tablet uses unlabeled icons | blocked |
| one primary Task or Output surface on mobile | mobile combines summaries in one column; no Task/Output switcher or drawers are demonstrated | blocked |
| tablet overlay/drawer behavior | fixed rail plus small adjacent cards; overlay/drawer states are not demonstrated | blocked |
| authority at consequence | some target/effect/recovery copy exists; expiry, invalidators, unchanged scope, evidence freshness, and authority are inconsistent | blocked |
| professional repository/test/CI/deploy/handoff depth | broad screen coverage, but mostly summary tables and decision cards | partial |
| source, client, cost, deletion, recovery safety | several explicit and thoughtful states exist | strongest area |

## 4. Journey-by-journey assessment

### 1. Home and zero-information entry — At risk

Home is quiet and understandable, but it behaves like a project list plus a prompt. The PRD requires start, continue, import, duplicate, and open Project/Task. The current desktop entry does not visibly expose templates, visual reference import, duplication, Task history, or a meaningful returning-work state.

Evidence: `01-p3a.png`.

### 2. Brief clarification and plan authority — At risk

The Brief and plan cards are clear summaries. They do not visibly support inspecting assumptions, editing individual acceptance intents, attaching references to a requirement, or understanding which planned task changes which object. The plan can be approved, but not meaningfully reviewed.

Evidence: `01-p3a.png`, `08-p4c.png`.

### 3. Build and first working Preview — Blocked

The Running state communicates phase, checkpoint, pause, and resume. The Preview shown beside it is a generic dark card containing the application name, slogan, and one button. It is not evidence of a working application, responsive behavior, real content, interaction, or runtime state.

Evidence: `01-p3a.png`, `13-p5ta.png`.

### 4. Direct visual manipulation — Blocked

The PRD explicitly promises direct-manipulation creation. Desktop says “Change the selected section,” and mobile shows a card labelled as the selected element, but there is no credible application canvas, selection outline, hierarchy, property inspector, screen chooser, breakpoint control, or before/after visual comparison.

Evidence: `01-p3a.png`, `16-p5ma.png`.

### 5. Data and managed resources — At risk

Resources, permissions, environments, and secrets are named, and sensitive values are not exposed. The user cannot inspect schema, records, access rules, function inputs/outputs, integration permissions, or resource relationships. The workflow is closer to approving resource creation than managing application data.

Evidence: `02-p3b.png`, `13-p5ta.png`.

### 6. Checks, repair, and evidence — At risk

The failure/recovery language is good, but “Checks,” “Open logs,” “Open evidence,” and suite counts remain unexpanded. There is no actual assertion list, failing step, browser capture, stack trace, coverage map, flaky-test evidence, or repair diff in the accepted evidence set.

Evidence: `03-p3c.png`, `09-p4d.png`, `17-p5mb.png`.

### 7. Versions, compare, publish, health, and rollback — At risk

The release state model is one of the strongest parts of the design. However, Compare presents file counts instead of a usable comparison; Release presents checks and a target instead of a release manifest; health presents a signal summary instead of a timeline or evidence; rollback presents a target without a sufficiently inspectable impact view.

Evidence: `03-p3c.png`, `04-p3d.png`, `16-p5ma.png`.

### 8. Professional client, source, context, and scope — At risk

Client isolation, source choice, dirty-state blocking, context conflict, scope divergence, route, and budget are thoughtfully named. These screens still rely on summary rows. There is no client switcher model with clear global boundary, repository tree/map, source comparison, citation viewer, editable scope baseline, dependency map, or plan-to-repository inspection.

Evidence: `06-p4a.png`, `07-p4b.png`, `08-p4c.png`, `14-p5tb.png`.

### 9. Professional implementation, tests, CI, deploy, review, and handoff — At risk

The complete delivery sequence exists on paper. Each job requires a purpose-built object view, but the screens reuse the same title + summary + decision + generic right pane. CI needs logs and runs; deploy needs environments and a timeline; review needs a real change package; handoff needs an export inventory and client-readable artifact.

Evidence: `09-p4d.png`, `10-p4e.png`, `15-p5tc.png`.

### 10. Cost, collaboration, privacy, deletion, and graduation — Mostly healthy

Cost attribution, continuation blocking, reviewer scoping, retention, irreversible deletion, boundary mismatch, sanitised memory, and graduation are explicit. This is the design's most mature family. The remaining weakness is incomplete authority detail and the absence of inspectable inventories behind actions such as deletion, export, memory promotion, and graduation mapping.

Evidence: `11-p4f.png`, `12-p4g.png`, `15-p5tc.png`, `18-p5mc.png`.

### 11. Tablet — Blocked against the responsive contract

Tablet uses an icon rail, task content, a small adjacent output card, and a bottom composer. The accepted frames do not demonstrate the required persistent-pane/overlay combination, output drawer, full-screen preview, decision sheet, keyboard-visible state, orientation change, or touch-first direct manipulation.

Evidence: `13-p5ta.png`, `14-p5tb.png`, `15-p5tc.png`.

### 12. Mobile — At risk despite being the strongest visual surface

Mobile has the clearest hierarchy, strongest action treatment, and useful Build/Data/Checks/Ship navigation. It does not demonstrate Project/Task drawers, Output tabs/sheets, Task-versus-Output switching, actual preview inspection, or a route into Account. The persistent composer appears even on bounded review, deletion, and Account states.

Evidence: `16-p5ma.png`, `17-p5mb.png`, `18-p5mc.png`.

## 5. P0 blockers

### P0-01 — The primary casual-builder job is not actually designed

The user can approve an agent-generated plan, but cannot visibly build or manipulate the application. The missing core is a credible working Preview/canvas and the interaction between selection, prompt, change, checkpoint, and evidence.

Required closure evidence:

- an actual application Preview with realistic UI and runtime states;
- visible selection and direct-manipulation behavior;
- screen/page navigation and responsive breakpoint inspection;
- before/after Change review;
- a clear transition from visual action to Version and Check evidence.

### P0-02 — Output objects are summaries, not inspectable artifacts

Preview, Changes, Data, Checks, Release, evidence, logs, repository map, and client package are mostly counts or labels. The user cannot independently verify the agent's claims.

Required closure evidence:

- six purpose-built Output modes: Preview, Changes, Data, Checks, Release, Evidence;
- real content anatomy for each mode;
- tabs/history/freshness and links from center claims to exact evidence;
- full/open states and empty/loading/error/stale variants.

### P0-03 — The Project navigation and durable Task model are missing

Desktop lacks persistent Build/Data/Checks/Ship lenses and Task history. Tablet uses unlabeled icons. Mobile has lenses but does not demonstrate Project/Task drawers or Output switching. Account has no credible desktop/tablet hub and no obvious mobile entry path.

Required closure evidence:

- desktop Projects + Tasks pane;
- persistent Project lens switcher;
- Task resume, branch, completed, blocked, and archived states;
- Account entry and six-group Account IA at every supported form factor;
- Output tab/sheet navigation.

### P0-04 — Consequential actions do not consistently satisfy the PRD's trust contract

Several publish, restore, upgrade, connector, provider, client, invite, retention, deletion, and graduation cards omit one or more mandatory fields: affected source/environment/client, evidence freshness, cost, authority, expiry/invalidators, what remains unchanged, or recovery.

Required closure evidence:

- a field-complete audit for every consequential action;
- distinct variants for billable, externally visible, privilege-granting, destructive, and irreversible actions;
- explicit expired/invalidated and post-action confirmation states.

### P0-05 — Tablet and mobile do not demonstrate the accepted responsive interaction contract

Resized compositions exist, but the required state transitions do not. Tablet does not show the overlay/drawer model. Mobile does not show Task/Output switching or Projects/Tasks and Output drawers/sheets.

Required closure evidence:

- explicit responsive surface maps;
- open/closed drawer and sheet states;
- focus/selection preservation across switching;
- full-screen preview and compare;
- touch/keyboard/orientation variants.

### P0-06 — Traceability and closure evidence remain invalid for implementation handoff

All 173 requirement rows are present, but 28 grouped screen families and one generic authority/effect/recovery phrase cannot prove interaction coverage. Current prototype behavior was not revalidated in this audit, and static frames cannot establish accessibility acceptance.

Required closure evidence:

- requirement-specific triggers, nodes, objects, decisions, outcomes, failures, and recovery;
- explicit designation of shared component coverage versus distinct workflow coverage;
- fresh prototype route validation after P0 redesign;
- interactive accessibility verification before closing the UX release gate.

## 6. P1 gaps

1. The desktop center is underused while the design claims the center is the primary work surface.
2. The same template is used for jobs with fundamentally different information needs.
3. The right pane frequently duplicates the center summary rather than extending it.
4. Brief and plan review lack editable structure, citations, assumptions, and object-level impact.
5. Repository, scope, test, CI, deployment, and client-review views lack professional working depth.
6. Loading, streaming, reconnecting, stale, partial-result, and long-running external-operation layouts are incomplete.
7. The universal composer competes with direct actions and remains visible where conversation is not the job.
8. Secondary actions are often small text inside cards and do not read as equivalent decision options.
9. Tier and Professional Mode mostly alter copy, badges, and density instead of unlocking progressively deeper object views.
10. Settings are represented as a summary list rather than a complete, navigable six-group system.

## 7. P2 refinements

1. Increase default text size and contrast for metadata, right-pane content, rails, and muted instructions.
2. Reduce the almost-black uniformity; important surface changes need stronger non-color hierarchy.
3. Replace generic Kitchen Planner placeholder content with realistic, varied project artifacts.
4. Remove stale or contradictory actions such as repeated “Create plan” treatment after planning/build progression.
5. Replace developer-facing composer microcopy such as “Attach / Run” for casual users.
6. Reduce empty center/right-pane acreage when no artifact is available; collapse or explain the empty state.
7. Give progress/gamification a contextual outcome treatment instead of another decision-card presentation.

## 8. Strengths to preserve

- A coherent visual language across tiers and form factors.
- Strong status vocabulary with text accompanying color.
- Explicit pause, checkpoint, resume, restore, and rollback thinking.
- Thoughtful source-unavailable, offline, dirty-state, client mismatch, and limit recovery.
- Max/BYOK separation and same-Task upgrade continuity.
- Clear client isolation, reviewer scoping, retention, deletion, and sanitized-memory intent.
- Mobile action hierarchy is materially better than desktop.
- The product avoids exposing internal multi-agent mechanics.

## 9. Accessibility risks and evidence limits

Visible risks:

- very small monospaced metadata;
- low-contrast muted teal text on dark teal surfaces;
- small icon rails with limited labels;
- dense decision copy and weak secondary-action affordance;
- information hierarchy that depends heavily on green/amber/red borders;
- large unused space surrounding small interaction targets.

Not established by these screenshots:

- actual contrast ratios;
- keyboard order, shortcuts, or pane control;
- visible focus and deterministic focus return;
- accessible names, roles, descriptions, and live regions;
- focus trapping for modal/drawer/sheet states;
- 44×44 CSS-pixel targets in the implemented product;
- reduced motion;
- 200% zoom, reflow, or localization expansion;
- state preservation during responsive surface switching.

The design cannot claim WCAG 2.2 AA readiness from static frames.

## 10. Closure decision

Studio UX remains open. The current file is a useful scenario catalogue and a consistent decision-system exploration, but it is not yet an implementation-ready application-building workspace.

The correct repair order is:

1. design the actual builder/Preview and Output object system;
2. restore Projects/Tasks/lenses/Account navigation;
3. design real responsive switching and drawers;
4. repair consequential-action contracts;
5. replace grouped traceability with interaction evidence;
6. re-run prototype and accessibility audits.

