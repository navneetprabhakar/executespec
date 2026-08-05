# ExecuteSpec Enterprise - Screen-by-Screen Designer Handoff

Date: 2026-07-31  
Checkpoint: `CP-077`  
Audience: Enterprise UX/UI, interaction, content, accessibility, and product designers  
Canonical Figma: <https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise>

## Authority and inventory

This catalogue covers the 110 accepted `EN-D-*`, `EN-T-*`, and `EN-M-*` roots exported at CP-076: 48 desktop, 30 tablet, and 32 mobile. Every screen is implementation design authority. Older `E*`, `X*`, and other replaced frames are historical references only and are deliberately excluded.

## Global shell and state contract

- Organisation / Team -> Objective -> Inspect is the primary hierarchy.
- Left rail contains organisation/team, Needs you, Objectives, Intelligence, Services, Evidence links, and Settings; both panes collapse to semantic icons.
- Centre owns the current Objective/tool/settings job; Inspect is contextual and read-only where it searches context.
- Process, outcome, permission, availability, freshness, evidence completeness, and epistemic status render independently.
- Production remediation is P2/Conditional. P0/P1 may investigate, recommend, request approval, escalate, or create an Objective.
- Provider names remain visible. DeepSeek, OpenRouter, and Fireworks currently display no logo or placeholder glyph.

## Screen count

- Desktop: 48
- Tablet: 30
- Mobile: 32
- Total: 110

## Desktop authority

### EN-D-00A - Neutral view choice

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=166-345) (`166:345`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 1 canonical actions; 1 wired; 0 explicitly inert.
- **User job:** Choose the correct product boundary.
- **Required content:** Neutral Studio/Enterprise choices; no tenant/project/Objective/source/member/memory disclosure; target authorisation and separate URL.
- **Primary interaction:** Choose a product or return safely.
- **Failure/recovery:** Wrong account/view or denied target clears protected context and returns to neutral choice.
- **Direct destinations:** [EN-D-01Z](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=168-3261)
- **Journeys:** [EJ11 - Neutral Studio/Enterprise view choice and secure switching](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej11-neutral-studio-enterprise-view-choice-and-secure-switching)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-01Z - Zero information

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=168-3261) (`168:3261`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Bootstrap a truthful empty tenant.
- **Required content:** Empty-state explanation; organisation/team/setup intent; source options; proposal-only Brief; permissions/effect review; cancel/resume.
- **Primary interaction:** Open scoped Ask, draft setup, review proposal, or connect a source.
- **Failure/recovery:** No sample data may look real; failed setup preserves a safe empty tenant and resumable draft.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-16241)
- **Journeys:** [EJ01 - Zero-information Enterprise setup](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej01-zero-information-enterprise-setup)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-02 - Zero-info setup brief

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-16241) (`292:16241`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Bootstrap a truthful empty tenant.
- **Required content:** Empty-state explanation; organisation/team/setup intent; source options; proposal-only Brief; permissions/effect review; cancel/resume.
- **Primary interaction:** Open scoped Ask, draft setup, review proposal, or connect a source.
- **Failure/recovery:** No sample data may look real; failed setup preserves a safe empty tenant and resumable draft.
- **Direct destinations:** [EN-D-10H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3203)
- **Journeys:** [EJ01 - Zero-information Enterprise setup](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej01-zero-information-enterprise-setup)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-05A - Scoped Ask

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485) (`289:15485`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Ask a permission-scoped read-only question.
- **Required content:** Scope; permitted sources; retrieval state; cited answer; omissions/conflicts/freshness; denied/unavailable/no-result distinction; proposed follow-up.
- **Primary interaction:** Submit/refine query, inspect citations, compare conflict, request access, refresh, or propose a bounded Objective/action.
- **Failure/recovery:** Denied reveals no protected claims; organisation-wide Intelligence is P1 and explicitly labelled.
- **Direct destinations:** [EN-D-30Q](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=207-1960), [EN-D-20C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3470)
- **Journeys:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-10H - Home needs you

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3203) (`227:3203`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Find the next Objective decision.
- **Required content:** Organisation/team; Needs you; ready/running/blocked Objective rows; source/approval/access causes; filters; maturity; proposal-only create/link.
- **Primary interaction:** Open/filter work, create/link Objective, reconnect source, or resolve required authority.
- **Failure/recovery:** Empty, source unavailable, permission-limited, loading, and no-ready-work remain distinct.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-20C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3470)
- **Journeys:** [EJ02 - Jira-ready work to scoped Objective](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej02-jira-ready-work-to-scoped-objective)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-11U - Home source unavailable

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-5159) (`235:5159`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Find the next Objective decision.
- **Required content:** Organisation/team; Needs you; ready/running/blocked Objective rows; source/approval/access causes; filters; maturity; proposal-only create/link.
- **Primary interaction:** Open/filter work, create/link Objective, reconnect source, or resolve required authority.
- **Failure/recovery:** Empty, source unavailable, permission-limited, loading, and no-ready-work remain distinct.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-21P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-5449), [EN-D-39H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=187-1120), [EN-D-53C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-3226)
- **Journeys:** [EJ02 - Jira-ready work to scoped Objective](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej02-jira-ready-work-to-scoped-objective)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-14D - Context permission denied

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=172-487) (`172:487`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-50H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=211-2595)
- **Journeys:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-14E - Context searched no result

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=174-562) (`174:562`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485)
- **Journeys:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-20C - Objective context conflict

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3470) (`227:3470`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-20R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-18472)
- **Journeys:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-20R - Context authority resolved

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-18472) (`292:18472`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-D-23A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3737), [EN-D-20C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3470)
- **Journeys:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-21P - Context partial stale

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-5449) (`235:5449`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 5 canonical actions; 5 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-26P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=181-806), [EN-D-11U](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-5159), [EN-D-23A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3737)
- **Journeys:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-23A - Plan approval

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3737) (`227:3737`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Review bounded Plan authority.
- **Required content:** Plan/version; tasks/tests/dependencies; target/SHA/environment; actor/approver; cost/time/resource caps; expiry; evidence; effect; recovery.
- **Primary interaction:** Approve, reject, change scope, inspect proof, revoke, or re-review.
- **Failure/recovery:** Rejected/expired/invalidated states cannot execute; material changes require fresh approval.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-23B](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-5745), [EN-D-23X](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=192-1440), [EN-D-20C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3470)
- **Journeys:** [EJ04 - Plan review, approval, rejection, expiry, and invalidation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej04-plan-review-approval-rejection-expiry-and-invalidation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-23B - Plan approved bounded authority

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-5745) (`235:5745`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 5 canonical actions; 5 wired; 0 explicitly inert.
- **User job:** Review bounded Plan authority.
- **Required content:** Plan/version; tasks/tests/dependencies; target/SHA/environment; actor/approver; cost/time/resource caps; expiry; evidence; effect; recovery.
- **Primary interaction:** Approve, reject, change scope, inspect proof, revoke, or re-review.
- **Failure/recovery:** Rejected/expired/invalidated states cannot execute; material changes require fresh approval.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-24W](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4004), [EN-D-23X](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=192-1440), [EN-D-23A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3737)
- **Journeys:** [EJ04 - Plan review, approval, rejection, expiry, and invalidation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej04-plan-review-approval-rejection-expiry-and-invalidation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-23X - Plan rejected expired invalidated

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=192-1440) (`192:1440`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 1 wired; 1 explicitly inert.
- **User job:** Review bounded Plan authority.
- **Required content:** Plan/version; tasks/tests/dependencies; target/SHA/environment; actor/approver; cost/time/resource caps; expiry; evidence; effect; recovery.
- **Primary interaction:** Approve, reject, change scope, inspect proof, revoke, or re-review.
- **Failure/recovery:** Rejected/expired/invalidated states cannot execute; material changes require fresh approval.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485)
- **Journeys:** [EJ04 - Plan review, approval, rejection, expiry, and invalidation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej04-plan-review-approval-rejection-expiry-and-invalidation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-24B - Working blocked

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-6027) (`235:6027`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 5 canonical actions; 4 wired; 1 explicitly inert.
- **User job:** Track current Objective execution.
- **Required content:** Semantic phase; route/provider; source SHA; budget; durable checkpoint; live verification; blockers; partial/superseded attempts; pause/cancel/recovery.
- **Primary interaction:** Inspect, pause, cancel-preserve, request access, retry, re-plan, or open successor.
- **Failure/recovery:** Process/outcome stay separate; recovery resumes exact checkpoint and superseded work remains evidence.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-25F](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=179-674), [EN-D-10H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3203), [EN-D-24R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-6335)
- **Journeys:** [EJ05 - Objective work, pause, block, recovery, and supersession](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej05-objective-work-pause-block-recovery-and-supersession)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-24R - Working recovered superseded

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-6335) (`235:6335`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 5 canonical actions; 5 wired; 0 explicitly inert.
- **User job:** Track current Objective execution.
- **Required content:** Semantic phase; route/provider; source SHA; budget; durable checkpoint; live verification; blockers; partial/superseded attempts; pause/cancel/recovery.
- **Primary interaction:** Inspect, pause, cancel-preserve, request access, retry, re-plan, or open successor.
- **Failure/recovery:** Process/outcome stay separate; recovery resumes exact checkpoint and superseded work remains evidence.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-25E](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4271), [EN-D-25F](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=179-674), [EN-D-24B](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-6027), [EN-D-24W](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4004)
- **Journeys:** [EJ05 - Objective work, pause, block, recovery, and supersession](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej05-objective-work-pause-block-recovery-and-supersession)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-24W - Working objective

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4004) (`227:4004`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 3 wired; 1 explicitly inert.
- **User job:** Track current Objective execution.
- **Required content:** Semantic phase; route/provider; source SHA; budget; durable checkpoint; live verification; blockers; partial/superseded attempts; pause/cancel/recovery.
- **Primary interaction:** Inspect, pause, cancel-preserve, request access, retry, re-plan, or open successor.
- **Failure/recovery:** Process/outcome stay separate; recovery resumes exact checkpoint and superseded work remains evidence.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-10H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3203), [EN-D-24B](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-6027)
- **Journeys:** [EJ05 - Objective work, pause, block, recovery, and supersession](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej05-objective-work-pause-block-recovery-and-supersession)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-25E - Evidence and delivery

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4271) (`227:4271`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Inspect proof and external delivery.
- **Required content:** Verification plan/run/outcome; deterministic versus inferred/unmeasured evidence; source/environment; delivery target; external effect; correlation/idempotency; rollback/reconciliation.
- **Primary interaction:** Open proof, run missing verification, review delivery, retry valid subset, reconcile, or hand off.
- **Failure/recovery:** Failed/inconclusive/partial/unmeasured and unknown remote effects remain explicit; never blind-retry external writes.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-25W](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-6639)
- **Journeys:** [EJ06 - Verification, evidence, delivery, and external-write reconciliation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej06-verification-evidence-delivery-and-external-write-reconciliation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-25F - Verification failed incomplete

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=179-674) (`179:674`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 1 canonical actions; 1 wired; 0 explicitly inert.
- **User job:** Inspect proof and external delivery.
- **Required content:** Verification plan/run/outcome; deterministic versus inferred/unmeasured evidence; source/environment; delivery target; external effect; correlation/idempotency; rollback/reconciliation.
- **Primary interaction:** Open proof, run missing verification, review delivery, retry valid subset, reconcile, or hand off.
- **Failure/recovery:** Failed/inconclusive/partial/unmeasured and unknown remote effects remain explicit; never blind-retry external writes.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485)
- **Journeys:** [EJ06 - Verification, evidence, delivery, and external-write reconciliation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej06-verification-evidence-delivery-and-external-write-reconciliation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-25W - Delivery partial write-back

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-6639) (`235:6639`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 5 canonical actions; 5 wired; 0 explicitly inert.
- **User job:** Inspect proof and external delivery.
- **Required content:** Verification plan/run/outcome; deterministic versus inferred/unmeasured evidence; source/environment; delivery target; external effect; correlation/idempotency; rollback/reconciliation.
- **Primary interaction:** Open proof, run missing verification, review delivery, retry valid subset, reconcile, or hand off.
- **Failure/recovery:** Failed/inconclusive/partial/unmeasured and unknown remote effects remain explicit; never blind-retry external writes.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-62R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=202-1834), [EN-D-25E](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4271)
- **Journeys:** [EJ06 - Verification, evidence, delivery, and external-write reconciliation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej06-verification-evidence-delivery-and-external-write-reconciliation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-26D - Answer denied

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=183-973) (`183:973`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Ask a permission-scoped read-only question.
- **Required content:** Scope; permitted sources; retrieval state; cited answer; omissions/conflicts/freshness; denied/unavailable/no-result distinction; proposed follow-up.
- **Primary interaction:** Submit/refine query, inspect citations, compare conflict, request access, refresh, or propose a bounded Objective/action.
- **Failure/recovery:** Denied reveals no protected claims; organisation-wide Intelligence is P1 and explicitly labelled.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-50H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=211-2595)
- **Journeys:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-26P - Answer partial stale conflicting

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=181-806) (`181:806`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Ask a permission-scoped read-only question.
- **Required content:** Scope; permitted sources; retrieval state; cited answer; omissions/conflicts/freshness; denied/unavailable/no-result distinction; proposed follow-up.
- **Primary interaction:** Submit/refine query, inspect citations, compare conflict, request access, refresh, or propose a bounded Objective/action.
- **Failure/recovery:** Denied reveals no protected claims; organisation-wide Intelligence is P1 and explicitly labelled.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-21P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-5449), [EN-D-20R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-18472)
- **Journeys:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-26U - Answer unavailable

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=184-1026) (`184:1026`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Ask a permission-scoped read-only question.
- **Required content:** Scope; permitted sources; retrieval state; cited answer; omissions/conflicts/freshness; denied/unavailable/no-result distinction; proposed follow-up.
- **Primary interaction:** Submit/refine query, inspect citations, compare conflict, request access, refresh, or propose a bounded Objective/action.
- **Failure/recovery:** Denied reveals no protected claims; organisation-wide Intelligence is P1 and explicitly labelled.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485)
- **Journeys:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-30Q - Intelligence scoped query

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=207-1960) (`207:1960`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Ask a permission-scoped read-only question.
- **Required content:** Scope; permitted sources; retrieval state; cited answer; omissions/conflicts/freshness; denied/unavailable/no-result distinction; proposed follow-up.
- **Primary interaction:** Submit/refine query, inspect citations, compare conflict, request access, refresh, or propose a bounded Objective/action.
- **Failure/recovery:** Denied reveals no protected claims; organisation-wide Intelligence is P1 and explicitly labelled.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-26P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=181-806)
- **Journeys:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-38I - Incident conditional remediation

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=194-1593) (`194:1593`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Understand services and investigate incidents.
- **Required content:** Service/owner/environment/readiness/incident/change; observation time; live/history distinction; omissions; remediation maturity; verification/write-back.
- **Primary interaction:** Open service, inspect evidence/runbook, investigate, create remediation Objective, escalate, or reconcile incident write-back.
- **Failure/recovery:** Live unavailable means current unknown; P0/P1 does not expose executable production rollback.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-23A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3737)
- **Journeys:** [EJ08 - Service state and incident investigation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej08-service-state-and-incident-investigation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-39H - Connector drift

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=187-1120) (`187:1120`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Review connector capability and lifecycle.
- **Required content:** Provider name; account; scopes/capabilities; connection/permission/availability/freshness/capability dimensions; retention/legal hold; export/purge effect.
- **Primary interaction:** Authorise/validate, narrow/rotate, reconnect, export, disconnect, queue/verify purge, or cancel.
- **Failure/recovery:** OAuth denial, drift, rate limit, failed write, partial purge, and legal hold have separate recovery; secrets remain write-only.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-53C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-3226)
- **Journeys:** [EJ09 - Connector permission, health, disconnect, export, and purge lifecycle](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej09-connector-permission-health-disconnect-export-and-purge-lifecycle)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-39P - Disconnect purge export

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=189-1278) (`189:1278`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Review connector capability and lifecycle.
- **Required content:** Provider name; account; scopes/capabilities; connection/permission/availability/freshness/capability dimensions; retention/legal hold; export/purge effect.
- **Primary interaction:** Authorise/validate, narrow/rotate, reconnect, export, disconnect, queue/verify purge, or cancel.
- **Failure/recovery:** OAuth denial, drift, rate limit, failed write, partial purge, and legal hold have separate recovery; secrets remain write-only.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-59Q](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-19605), [EN-D-25E](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4271)
- **Journeys:** [EJ09 - Connector permission, health, disconnect, export, and purge lifecycle](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej09-connector-permission-health-disconnect-export-and-purge-lifecycle)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-40P - Services portfolio

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=209-2309) (`209:2309`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Understand services and investigate incidents.
- **Required content:** Service/owner/environment/readiness/incident/change; observation time; live/history distinction; omissions; remediation maturity; verification/write-back.
- **Primary interaction:** Open service, inspect evidence/runbook, investigate, create remediation Objective, escalate, or reconcile incident write-back.
- **Failure/recovery:** Live unavailable means current unknown; P0/P1 does not expose executable production rollback.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-42I](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-1843)
- **Journeys:** [EJ08 - Service state and incident investigation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej08-service-state-and-incident-investigation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-41U - Service live unavailable

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-1356) (`239:1356`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Understand services and investigate incidents.
- **Required content:** Service/owner/environment/readiness/incident/change; observation time; live/history distinction; omissions; remediation maturity; verification/write-back.
- **Primary interaction:** Open service, inspect evidence/runbook, investigate, create remediation Objective, escalate, or reconcile incident write-back.
- **Failure/recovery:** Live unavailable means current unknown; P0/P1 does not expose executable production rollback.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-21P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-5449), [EN-D-40P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=209-2309), [EN-D-61C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=245-1722)
- **Journeys:** [EJ08 - Service state and incident investigation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej08-service-state-and-incident-investigation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-42B - Incident action blocked

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-2257) (`239:2257`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 3 wired; 1 explicitly inert.
- **User job:** Understand services and investigate incidents.
- **Required content:** Service/owner/environment/readiness/incident/change; observation time; live/history distinction; omissions; remediation maturity; verification/write-back.
- **Primary interaction:** Open service, inspect evidence/runbook, investigate, create remediation Objective, escalate, or reconcile incident write-back.
- **Failure/recovery:** Live unavailable means current unknown; P0/P1 does not expose executable production rollback.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-20C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3470)
- **Journeys:** [EJ08 - Service state and incident investigation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej08-service-state-and-incident-investigation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-42I - Active incident investigation

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-1843) (`239:1843`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 6 canonical actions; 6 wired; 0 explicitly inert.
- **User job:** Understand services and investigate incidents.
- **Required content:** Service/owner/environment/readiness/incident/change; observation time; live/history distinction; omissions; remediation maturity; verification/write-back.
- **Primary interaction:** Open service, inspect evidence/runbook, investigate, create remediation Objective, escalate, or reconcile incident write-back.
- **Failure/recovery:** Live unavailable means current unknown; P0/P1 does not expose executable production rollback.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-26P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=181-806), [EN-D-23A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3737), [EN-D-25E](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4271), [EN-D-20C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3470)
- **Journeys:** [EJ08 - Service state and incident investigation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej08-service-state-and-incident-investigation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-42V - Incident verified write-back failed

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-2671) (`239:2671`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 3 wired; 1 explicitly inert.
- **User job:** Inspect proof and external delivery.
- **Required content:** Verification plan/run/outcome; deterministic versus inferred/unmeasured evidence; source/environment; delivery target; external effect; correlation/idempotency; rollback/reconciliation.
- **Primary interaction:** Open proof, run missing verification, review delivery, retry valid subset, reconcile, or hand off.
- **Failure/recovery:** Failed/inconclusive/partial/unmeasured and unknown remote effects remain explicit; never blind-retry external writes.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-62R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=202-1834), [EN-D-50H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=211-2595)
- **Journeys:** [EJ08 - Service state and incident investigation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej08-service-state-and-incident-investigation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-50H - Enterprise Settings home

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=211-2595) (`211:2595`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Find the next Objective decision.
- **Required content:** Organisation/team; Needs you; ready/running/blocked Objective rows; source/approval/access causes; filters; maturity; proposal-only create/link.
- **Primary interaction:** Open/filter work, create/link Objective, reconnect source, or resolve required authority.
- **Failure/recovery:** Empty, source unavailable, permission-limited, loading, and no-ready-work remain distinct.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-55P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=220-2861)
- **Journeys:** [EJ02 - Jira-ready work to scoped Objective](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej02-jira-ready-work-to-scoped-objective)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-53C - First connector permission impact

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-3226) (`239:3226`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Review connector capability and lifecycle.
- **Required content:** Provider name; account; scopes/capabilities; connection/permission/availability/freshness/capability dimensions; retention/legal hold; export/purge effect.
- **Primary interaction:** Authorise/validate, narrow/rotate, reconnect, export, disconnect, queue/verify purge, or cancel.
- **Failure/recovery:** OAuth denial, drift, rate limit, failed write, partial purge, and legal hold have separate recovery; secrets remain write-only.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-39H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=187-1120)
- **Journeys:** [EJ09 - Connector permission, health, disconnect, export, and purge lifecycle](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej09-connector-permission-health-disconnect-export-and-purge-lifecycle)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-55M - Route unavailable usage limit

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=200-1698) (`200:1698`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 3 canonical actions; 2 wired; 1 explicitly inert.
- **User job:** Govern provider/model routing.
- **Required content:** Provider/model names; key source; health; policy/maturity; data/budget boundary; usage/limit; eligible alternatives; approval and route-validation receipt.
- **Primary interaction:** Select/review allowed route, request policy change, validate, resume, or remain blocked.
- **Failure/recovery:** No silent failover/substitution/spend; DeepSeek/OpenRouter/Fireworks remain named without logos.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-55P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=220-2861)
- **Journeys:** [EJ10 - AI model policy and route recovery](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej10-ai-model-policy-and-route-recovery)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-55P - AI model policy matrix

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=220-2861) (`220:2861`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Govern provider/model routing.
- **Required content:** Provider/model names; key source; health; policy/maturity; data/budget boundary; usage/limit; eligible alternatives; approval and route-validation receipt.
- **Primary interaction:** Select/review allowed route, request policy change, validate, resume, or remain blocked.
- **Failure/recovery:** No silent failover/substitution/spend; DeepSeek/OpenRouter/Fireworks remain named without logos.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-55M](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=200-1698)
- **Journeys:** [EJ10 - AI model policy and route recovery](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej10-ai-model-policy-and-route-recovery)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-59Q - Purge queued

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-19605) (`292:19605`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Review connector capability and lifecycle.
- **Required content:** Provider name; account; scopes/capabilities; connection/permission/availability/freshness/capability dimensions; retention/legal hold; export/purge effect.
- **Primary interaction:** Authorise/validate, narrow/rotate, reconnect, export, disconnect, queue/verify purge, or cancel.
- **Failure/recovery:** OAuth denial, drift, rate limit, failed write, partial purge, and legal hold have separate recovery; secrets remain write-only.
- **Direct destinations:** [EN-D-50H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=211-2595), [EN-D-39P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=189-1278)
- **Journeys:** [EJ09 - Connector permission, health, disconnect, export, and purge lifecycle](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej09-connector-permission-health-disconnect-export-and-purge-lifecycle)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-60A - External link authorized

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-17349) (`292:17349`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Resolve an external Enterprise link.
- **Required content:** Origin and intended object/action class; account/view; permission; expiry; source version; authorised/redacted result; request-access/reissue/return-safe path.
- **Primary interaction:** Authenticate, switch view/account, open exact authorised target, request access, reissue, or return.
- **Failure/recovery:** Only authorised states reveal protected identifiers; wrong/expired/denied/revoked/source-changed remain distinct.
- **Direct destinations:** [EN-D-23A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3737), [EN-D-60D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=177-663), [EN-D-10H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3203)
- **Journeys:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-60D - Link authorization denied

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=177-663) (`177:663`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Resolve an external Enterprise link.
- **Required content:** Origin and intended object/action class; account/view; permission; expiry; source version; authorised/redacted result; request-access/reissue/return-safe path.
- **Primary interaction:** Authenticate, switch view/account, open exact authorised target, request access, reissue, or return.
- **Failure/recovery:** Only authorised states reveal protected identifiers; wrong/expired/denied/revoked/source-changed remain distinct.
- **Direct destinations:** [EN-D-50H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=211-2595), [EN-D-00A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=166-345)
- **Journeys:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-60E - Continuity expired

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=176-3425) (`176:3425`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 1 canonical actions; 1 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-D-00A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=166-345)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-60W - Wrong view recovery

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=175-611) (`175:611`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 1 canonical actions; 1 wired; 0 explicitly inert.
- **User job:** Resolve an external Enterprise link.
- **Required content:** Origin and intended object/action class; account/view; permission; expiry; source version; authorised/redacted result; request-access/reissue/return-safe path.
- **Primary interaction:** Authenticate, switch view/account, open exact authorised target, request access, reissue, or return.
- **Failure/recovery:** Only authorised states reveal protected identifiers; wrong/expired/denied/revoked/source-changed remain distinct.
- **Direct destinations:** [EN-D-60A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-17349)
- **Journeys:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-61C - Secure handoff created

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=245-1722) (`245:1722`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-61O](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=296-15631), [EN-D-61R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=297-15875)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-61D - Continuity completed

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=245-2417) (`245:2417`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-50H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=211-2595), [EN-D-24W](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4004)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-61O - Continuity opened

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=296-15631) (`296:15631`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-61D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=245-2417), [EN-D-61R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=297-15875), [EN-D-61C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=245-1722)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-61R - Continuity revoked

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=297-15875) (`297:15875`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-61C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=245-1722), [EN-D-50H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=211-2595)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-61S - Continuity source changed

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=245-2113) (`245:2113`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 5 canonical actions; 4 wired; 1 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-61R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=297-15875), [EN-D-61C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=245-1722), [EN-D-20C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3470)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-62C - External write authoritative read

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-20749) (`292:20749`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Inspect proof and external delivery.
- **Required content:** Verification plan/run/outcome; deterministic versus inferred/unmeasured evidence; source/environment; delivery target; external effect; correlation/idempotency; rollback/reconciliation.
- **Primary interaction:** Open proof, run missing verification, review delivery, retry valid subset, reconcile, or hand off.
- **Failure/recovery:** Failed/inconclusive/partial/unmeasured and unknown remote effects remain explicit; never blind-retry external writes.
- **Direct destinations:** [EN-D-25E](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4271), [EN-D-25W](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-6639)
- **Journeys:** [EJ06 - Verification, evidence, delivery, and external-write reconciliation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej06-verification-evidence-delivery-and-external-write-reconciliation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-D-62R - External write reconciliation

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=202-1834) (`202:1834`)
- **Viewport:** 1440x960 (desktop)
- **Prototype contract:** 3 canonical actions; 2 wired; 1 explicitly inert.
- **User job:** Inspect proof and external delivery.
- **Required content:** Verification plan/run/outcome; deterministic versus inferred/unmeasured evidence; source/environment; delivery target; external effect; correlation/idempotency; rollback/reconciliation.
- **Primary interaction:** Open proof, run missing verification, review delivery, retry valid subset, reconcile, or hand off.
- **Failure/recovery:** Failed/inconclusive/partial/unmeasured and unknown remote effects remain explicit; never blind-retry external writes.
- **Direct destinations:** [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485), [EN-D-62C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-20749)
- **Journeys:** [EJ06 - Verification, evidence, delivery, and external-write reconciliation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej06-verification-evidence-delivery-and-external-write-reconciliation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

## Tablet authority

### EN-T-00A - Neutral view choice

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-19673) (`302:19673`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Choose the correct product boundary.
- **Required content:** Neutral Studio/Enterprise choices; no tenant/project/Objective/source/member/memory disclosure; target authorisation and separate URL.
- **Primary interaction:** Choose a product or return safely.
- **Failure/recovery:** Wrong account/view or denied target clears protected context and returns to neutral choice.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440)
- **Journeys:** [EJ11 - Neutral Studio/Enterprise view choice and secure switching](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej11-neutral-studio-enterprise-view-choice-and-secure-switching)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-01 - Home needs you

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440) (`251:9440`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Find the next Objective decision.
- **Required content:** Organisation/team; Needs you; ready/running/blocked Objective rows; source/approval/access causes; filters; maturity; proposal-only create/link.
- **Primary interaction:** Open/filter work, create/link Objective, reconnect source, or resolve required authority.
- **Failure/recovery:** Empty, source unavailable, permission-limited, loading, and no-ready-work remain distinct.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-01F](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-17733), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706)
- **Journeys:** [EJ02 - Jira-ready work to scoped Objective](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej02-jira-ready-work-to-scoped-objective)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-01F - Objective filters

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-17733) (`290:17733`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Find the next Objective decision.
- **Required content:** Organisation/team; Needs you; ready/running/blocked Objective rows; source/approval/access causes; filters; maturity; proposal-only create/link.
- **Primary interaction:** Open/filter work, create/link Objective, reconnect source, or resolve required authority.
- **Failure/recovery:** Empty, source unavailable, permission-limited, loading, and no-ready-work remain distinct.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440)
- **Journeys:** [EJ02 - Jira-ready work to scoped Objective](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej02-jira-ready-work-to-scoped-objective)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-02 - Objective context

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706) (`251:9706`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-11](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10683), [EN-T-12](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-15559), [EN-T-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10003)
- **Journeys:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-02Z - Zero-info setup brief

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=301-16123) (`301:16123`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Bootstrap a truthful empty tenant.
- **Required content:** Empty-state explanation; organisation/team/setup intent; source options; proposal-only Brief; permissions/effect review; cancel/resume.
- **Primary interaction:** Open scoped Ask, draft setup, review proposal, or connect a source.
- **Failure/recovery:** No sample data may look real; failed setup preserves a safe empty tenant and resumable draft.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706), [EN-T-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10003)
- **Journeys:** [EJ01 - Zero-information Enterprise setup](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej01-zero-information-enterprise-setup)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-03 - Plan decision

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10003) (`251:10003`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 5 canonical actions; 5 wired; 0 explicitly inert.
- **User job:** Review bounded Plan authority.
- **Required content:** Plan/version; tasks/tests/dependencies; target/SHA/environment; actor/approver; cost/time/resource caps; expiry; evidence; effect; recovery.
- **Primary interaction:** Approve, reject, change scope, inspect proof, revoke, or re-review.
- **Failure/recovery:** Rejected/expired/invalidated states cannot execute; material changes require fresh approval.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-11](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10683), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706), [EN-T-12](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-15559), [EN-T-04](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10185)
- **Journeys:** [EJ04 - Plan review, approval, rejection, expiry, and invalidation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej04-plan-review-approval-rejection-expiry-and-invalidation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-04 - Working objective

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10185) (`251:10185`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Track current Objective execution.
- **Required content:** Semantic phase; route/provider; source SHA; budget; durable checkpoint; live verification; blockers; partial/superseded attempts; pause/cancel/recovery.
- **Primary interaction:** Inspect, pause, cancel-preserve, request access, retry, re-plan, or open successor.
- **Failure/recovery:** Process/outcome stay separate; recovery resumes exact checkpoint and superseded work remains evidence.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-11](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10683), [EN-T-04B](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-19925), [EN-T-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440)
- **Journeys:** [EJ05 - Objective work, pause, block, recovery, and supersession](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej05-objective-work-pause-block-recovery-and-supersession)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-04B - Working blocked recovery

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-19925) (`290:19925`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Track current Objective execution.
- **Required content:** Semantic phase; route/provider; source SHA; budget; durable checkpoint; live verification; blockers; partial/superseded attempts; pause/cancel/recovery.
- **Primary interaction:** Inspect, pause, cancel-preserve, request access, retry, re-plan, or open successor.
- **Failure/recovery:** Process/outcome stay separate; recovery resumes exact checkpoint and superseded work remains evidence.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-11](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10683), [EN-T-04](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10185), [EN-T-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440)
- **Journeys:** [EJ05 - Objective work, pause, block, recovery, and supersession](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej05-objective-work-pause-block-recovery-and-supersession)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-05 - Evidence and delivery

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10379) (`251:10379`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Inspect proof and external delivery.
- **Required content:** Verification plan/run/outcome; deterministic versus inferred/unmeasured evidence; source/environment; delivery target; external effect; correlation/idempotency; rollback/reconciliation.
- **Primary interaction:** Open proof, run missing verification, review delivery, retry valid subset, reconcile, or hand off.
- **Failure/recovery:** Failed/inconclusive/partial/unmeasured and unknown remote effects remain explicit; never blind-retry external writes.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-11](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10683), [EN-T-05P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-21110)
- **Journeys:** [EJ06 - Verification, evidence, delivery, and external-write reconciliation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej06-verification-evidence-delivery-and-external-write-reconciliation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-05A - Scoped Ask

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413) (`289:16413`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Ask a permission-scoped read-only question.
- **Required content:** Scope; permitted sources; retrieval state; cited answer; omissions/conflicts/freshness; denied/unavailable/no-result distinction; proposed follow-up.
- **Primary interaction:** Submit/refine query, inspect citations, compare conflict, request access, refresh, or propose a bounded Objective/action.
- **Failure/recovery:** Denied reveals no protected claims; organisation-wide Intelligence is P1 and explicitly labelled.
- **Direct destinations:** [EN-T-06](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-12803), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706)
- **Journeys:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-05P - Partial write-back recovery

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-21110) (`290:21110`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Inspect proof and external delivery.
- **Required content:** Verification plan/run/outcome; deterministic versus inferred/unmeasured evidence; source/environment; delivery target; external effect; correlation/idempotency; rollback/reconciliation.
- **Primary interaction:** Open proof, run missing verification, review delivery, retry valid subset, reconcile, or hand off.
- **Failure/recovery:** Failed/inconclusive/partial/unmeasured and unknown remote effects remain explicit; never blind-retry external writes.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-11](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10683), [EN-T-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10379), [EN-T-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440)
- **Journeys:** [EJ13 - Tablet Inspect and read-only context retrieval](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej13-tablet-inspect-and-read-only-context-retrieval)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-06 - Intelligence scoped partial

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-12803) (`270:12803`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Ask a permission-scoped read-only question.
- **Required content:** Scope; permitted sources; retrieval state; cited answer; omissions/conflicts/freshness; denied/unavailable/no-result distinction; proposed follow-up.
- **Primary interaction:** Submit/refine query, inspect citations, compare conflict, request access, refresh, or propose a bounded Objective/action.
- **Failure/recovery:** Denied reveals no protected claims; organisation-wide Intelligence is P1 and explicitly labelled.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-11](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10683), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706)
- **Journeys:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-06D - Intelligence permission denied

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=273-13993) (`273:13993`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Ask a permission-scoped read-only question.
- **Required content:** Scope; permitted sources; retrieval state; cited answer; omissions/conflicts/freshness; denied/unavailable/no-result distinction; proposed follow-up.
- **Primary interaction:** Submit/refine query, inspect citations, compare conflict, request access, refresh, or propose a bounded Objective/action.
- **Failure/recovery:** Denied reveals no protected claims; organisation-wide Intelligence is P1 and explicitly labelled.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-12](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-15559), [EN-T-06](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-12803)
- **Journeys:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-07 - Services portfolio

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-13019) (`270:13019`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Understand services and investigate incidents.
- **Required content:** Service/owner/environment/readiness/incident/change; observation time; live/history distinction; omissions; remediation maturity; verification/write-back.
- **Primary interaction:** Open service, inspect evidence/runbook, investigate, create remediation Objective, escalate, or reconcile incident write-back.
- **Failure/recovery:** Live unavailable means current unknown; P0/P1 does not expose executable production rollback.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-13183), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706)
- **Journeys:** [EJ08 - Service state and incident investigation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej08-service-state-and-incident-investigation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-08 - Incident conditional remediation

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-13183) (`270:13183`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Understand services and investigate incidents.
- **Required content:** Service/owner/environment/readiness/incident/change; observation time; live/history distinction; omissions; remediation maturity; verification/write-back.
- **Primary interaction:** Open service, inspect evidence/runbook, investigate, create remediation Objective, escalate, or reconcile incident write-back.
- **Failure/recovery:** Live unavailable means current unknown; P0/P1 does not expose executable production rollback.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-11](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10683), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706)
- **Journeys:** [EJ08 - Service state and incident investigation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej08-service-state-and-incident-investigation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-09 - Critical Settings connector review

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-13339) (`270:13339`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Review connector capability and lifecycle.
- **Required content:** Provider name; account; scopes/capabilities; connection/permission/availability/freshness/capability dimensions; retention/legal hold; export/purge effect.
- **Primary interaction:** Authorise/validate, narrow/rotate, reconnect, export, disconnect, queue/verify purge, or cancel.
- **Failure/recovery:** OAuth denial, drift, rate limit, failed write, partial purge, and legal hold have separate recovery; secrets remain write-only.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-12](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-15559), [EN-T-10](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=273-14146)
- **Journeys:** [EJ09 - Connector permission, health, disconnect, export, and purge lifecycle](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej09-connector-permission-health-disconnect-export-and-purge-lifecycle)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-09A - Deep link authorized

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-14299) (`282:14299`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Resolve an external Enterprise link.
- **Required content:** Origin and intended object/action class; account/view; permission; expiry; source version; authorised/redacted result; request-access/reissue/return-safe path.
- **Primary interaction:** Authenticate, switch view/account, open exact authorised target, request access, reissue, or return.
- **Failure/recovery:** Only authorised states reveal protected identifiers; wrong/expired/denied/revoked/source-changed remain distinct.
- **Direct destinations:** [EN-T-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10003), [EN-T-09D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-16704), [EN-T-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440)
- **Journeys:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-09D - Deep link denied

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-16704) (`282:16704`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Resolve an external Enterprise link.
- **Required content:** Origin and intended object/action class; account/view; permission; expiry; source version; authorised/redacted result; request-access/reissue/return-safe path.
- **Primary interaction:** Authenticate, switch view/account, open exact authorised target, request access, reissue, or return.
- **Failure/recovery:** Only authorised states reveal protected identifiers; wrong/expired/denied/revoked/source-changed remain distinct.
- **Direct destinations:** [EN-T-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440)
- **Journeys:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-09E - Deep link expired

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-15899) (`282:15899`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Resolve an external Enterprise link.
- **Required content:** Origin and intended object/action class; account/view; permission; expiry; source version; authorised/redacted result; request-access/reissue/return-safe path.
- **Primary interaction:** Authenticate, switch view/account, open exact authorised target, request access, reissue, or return.
- **Failure/recovery:** Only authorised states reveal protected identifiers; wrong/expired/denied/revoked/source-changed remain distinct.
- **Direct destinations:** [EN-T-10C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-14489), [EN-T-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440)
- **Journeys:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-09W - Deep link wrong

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-15102) (`282:15102`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Resolve an external Enterprise link.
- **Required content:** Origin and intended object/action class; account/view; permission; expiry; source version; authorised/redacted result; request-access/reissue/return-safe path.
- **Primary interaction:** Authenticate, switch view/account, open exact authorised target, request access, reissue, or return.
- **Failure/recovery:** Only authorised states reveal protected identifiers; wrong/expired/denied/revoked/source-changed remain distinct.
- **Direct destinations:** [EN-T-09A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-14299), [EN-T-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440)
- **Journeys:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-10 - Connector authorization failed recovery

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=273-14146) (`273:14146`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Review connector capability and lifecycle.
- **Required content:** Provider name; account; scopes/capabilities; connection/permission/availability/freshness/capability dimensions; retention/legal hold; export/purge effect.
- **Primary interaction:** Authorise/validate, narrow/rotate, reconnect, export, disconnect, queue/verify purge, or cancel.
- **Failure/recovery:** OAuth denial, drift, rate limit, failed write, partial purge, and legal hold have separate recovery; secrets remain write-only.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-09](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-13339)
- **Journeys:** [EJ09 - Connector permission, health, disconnect, export, and purge lifecycle](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej09-connector-permission-health-disconnect-export-and-purge-lifecycle)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-10C - Continuity token created

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-14489) (`283:14489`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-10O](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-15533), [EN-T-10R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-17647)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-10D - Continuity completed

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-19794) (`283:19794`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-10O - Continuity opened

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-15533) (`283:15533`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-10D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-19794), [EN-T-10R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-17647)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-10R - Continuity revoked

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-17647) (`283:17647`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-10C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-14489), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-10S - Continuity source changed

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-18717) (`283:18717`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706), [EN-T-10C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-14489)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-10X - Continuity expired

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-16587) (`283:16587`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-10C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-14489), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-11 - Inspect drawer open

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10683) (`251:10683`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 4 canonical actions; 1 wired; 3 explicitly inert.
- **User job:** Complete the named Enterprise job.
- **Required content:** Organisation/team/Objective identity; state tuple; scope; authority; evidence/freshness; maturity; effect; recovery; audit linkage.
- **Primary interaction:** Take the strongest valid next action or return without losing Objective state.
- **Failure/recovery:** Loading, empty, partial, denied, unavailable, failed, recovered, cancelled, and superseded states remain honest and recoverable.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413)
- **Journeys:** [EJ13 - Tablet Inspect and read-only context retrieval](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej13-tablet-inspect-and-read-only-context-retrieval)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-12 - Scope review

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-15559) (`290:15559`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706)
- **Journeys:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-T-20R - Context authority resolved

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=301-19230) (`301:19230`)
- **Viewport:** 834x960 (tablet)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413), [EN-T-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10003), [EN-T-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440)
- **Journeys:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

## Mobile authority

### EN-M-00 - Navigation overlay

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434) (`263:11434`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 0 canonical actions; 0 wired; 0 explicitly inert.
- **User job:** Complete the named Enterprise job.
- **Required content:** Organisation/team/Objective identity; state tuple; scope; authority; evidence/freshness; maturity; effect; recovery; audit linkage.
- **Primary interaction:** Take the strongest valid next action or return without losing Objective state.
- **Failure/recovery:** Loading, empty, partial, denied, unavailable, failed, recovered, cancelled, and superseded states remain honest and recoverable.
- **Direct destinations:** [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-00A - Neutral view choice

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-19907) (`302:19907`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Choose the correct product boundary.
- **Required content:** Neutral Studio/Enterprise choices; no tenant/project/Objective/source/member/memory disclosure; target authorisation and separate URL.
- **Primary interaction:** Choose a product or return safely.
- **Failure/recovery:** Wrong account/view or denied target clears protected context and returns to neutral choice.
- **Direct destinations:** [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297), [EN-M-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878)
- **Journeys:** [EJ11 - Neutral Studio/Enterprise view choice and secure switching](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej11-neutral-studio-enterprise-view-choice-and-secure-switching)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-01 - Home needs you

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688) (`263:11688`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Find the next Objective decision.
- **Required content:** Organisation/team; Needs you; ready/running/blocked Objective rows; source/approval/access causes; filters; maturity; proposal-only create/link.
- **Primary interaction:** Open/filter work, create/link Objective, reconnect source, or resolve required authority.
- **Failure/recovery:** Empty, source unavailable, permission-limited, loading, and no-ready-work remain distinct.
- **Direct destinations:** [EN-M-01F](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-18822), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ02 - Jira-ready work to scoped Objective](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej02-jira-ready-work-to-scoped-objective)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-01F - Objective filters

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-18822) (`290:18822`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Find the next Objective decision.
- **Required content:** Organisation/team; Needs you; ready/running/blocked Objective rows; source/approval/access causes; filters; maturity; proposal-only create/link.
- **Primary interaction:** Open/filter work, create/link Objective, reconnect source, or resolve required authority.
- **Failure/recovery:** Empty, source unavailable, permission-limited, loading, and no-ready-work remain distinct.
- **Direct destinations:** [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297), [EN-M-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878)
- **Journeys:** [EJ02 - Jira-ready work to scoped Objective](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej02-jira-ready-work-to-scoped-objective)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-02 - Objective context

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804) (`263:11804`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-M-02I](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=266-15874), [EN-M-12](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-16695), [EN-M-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11937), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-02I - Inspect source evidence

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=266-15874) (`266:15874`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 0 canonical actions; 0 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804)
- **Journeys:** [EJ13 - Tablet Inspect and read-only context retrieval](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej13-tablet-inspect-and-read-only-context-retrieval)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-02Z - Zero-info setup brief

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=301-19043) (`301:19043`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Bootstrap a truthful empty tenant.
- **Required content:** Empty-state explanation; organisation/team/setup intent; source options; proposal-only Brief; permissions/effect review; cancel/resume.
- **Primary interaction:** Open scoped Ask, draft setup, review proposal, or connect a source.
- **Failure/recovery:** No sample data may look real; failed setup preserves a safe empty tenant and resumable draft.
- **Direct destinations:** [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11937), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297), [EN-M-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878)
- **Journeys:** [EJ01 - Zero-information Enterprise setup](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej01-zero-information-enterprise-setup)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-03 - Plan approval

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11937) (`263:11937`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 4 canonical actions; 4 wired; 0 explicitly inert.
- **User job:** Review bounded Plan authority.
- **Required content:** Plan/version; tasks/tests/dependencies; target/SHA/environment; actor/approver; cost/time/resource caps; expiry; evidence; effect; recovery.
- **Primary interaction:** Approve, reject, change scope, inspect proof, revoke, or re-review.
- **Failure/recovery:** Rejected/expired/invalidated states cannot execute; material changes require fresh approval.
- **Direct destinations:** [EN-M-02I](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=266-15874), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-04](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12063), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ04 - Plan review, approval, rejection, expiry, and invalidation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej04-plan-review-approval-rejection-expiry-and-invalidation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-04 - Working Objective

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12063) (`263:12063`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Track current Objective execution.
- **Required content:** Semantic phase; route/provider; source SHA; budget; durable checkpoint; live verification; blockers; partial/superseded attempts; pause/cancel/recovery.
- **Primary interaction:** Inspect, pause, cancel-preserve, request access, retry, re-plan, or open successor.
- **Failure/recovery:** Process/outcome stay separate; recovery resumes exact checkpoint and superseded work remains evidence.
- **Direct destinations:** [EN-M-02I](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=266-15874), [EN-M-04B](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12180), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ05 - Objective work, pause, block, recovery, and supersession](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej05-objective-work-pause-block-recovery-and-supersession)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-04B - Working blocked recovery

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12180) (`263:12180`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Track current Objective execution.
- **Required content:** Semantic phase; route/provider; source SHA; budget; durable checkpoint; live verification; blockers; partial/superseded attempts; pause/cancel/recovery.
- **Primary interaction:** Inspect, pause, cancel-preserve, request access, retry, re-plan, or open successor.
- **Failure/recovery:** Process/outcome stay separate; recovery resumes exact checkpoint and superseded work remains evidence.
- **Direct destinations:** [EN-M-02I](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=266-15874), [EN-M-04](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12063), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ05 - Objective work, pause, block, recovery, and supersession](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej05-objective-work-pause-block-recovery-and-supersession)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-05 - Evidence and delivery

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297) (`263:12297`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Inspect proof and external delivery.
- **Required content:** Verification plan/run/outcome; deterministic versus inferred/unmeasured evidence; source/environment; delivery target; external effect; correlation/idempotency; rollback/reconciliation.
- **Primary interaction:** Open proof, run missing verification, review delivery, retry valid subset, reconcile, or hand off.
- **Failure/recovery:** Failed/inconclusive/partial/unmeasured and unknown remote effects remain explicit; never blind-retry external writes.
- **Direct destinations:** [EN-M-02I](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=266-15874), [EN-M-05P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12433), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804)
- **Journeys:** [EJ06 - Verification, evidence, delivery, and external-write reconciliation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej06-verification-evidence-delivery-and-external-write-reconciliation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-05A - Scoped Ask

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-17353) (`289:17353`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Ask a permission-scoped read-only question.
- **Required content:** Scope; permitted sources; retrieval state; cited answer; omissions/conflicts/freshness; denied/unavailable/no-result distinction; proposed follow-up.
- **Primary interaction:** Submit/refine query, inspect citations, compare conflict, request access, refresh, or propose a bounded Objective/action.
- **Failure/recovery:** Denied reveals no protected claims; organisation-wide Intelligence is P1 and explicitly labelled.
- **Direct destinations:** [EN-M-06](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13510), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804)
- **Journeys:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-05P - Partial write-back recovery

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12433) (`263:12433`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Inspect proof and external delivery.
- **Required content:** Verification plan/run/outcome; deterministic versus inferred/unmeasured evidence; source/environment; delivery target; external effect; correlation/idempotency; rollback/reconciliation.
- **Primary interaction:** Open proof, run missing verification, review delivery, retry valid subset, reconcile, or hand off.
- **Failure/recovery:** Failed/inconclusive/partial/unmeasured and unknown remote effects remain explicit; never blind-retry external writes.
- **Direct destinations:** [EN-M-02I](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=266-15874), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-06 - Intelligence scoped partial

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13510) (`271:13510`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Ask a permission-scoped read-only question.
- **Required content:** Scope; permitted sources; retrieval state; cited answer; omissions/conflicts/freshness; denied/unavailable/no-result distinction; proposed follow-up.
- **Primary interaction:** Submit/refine query, inspect citations, compare conflict, request access, refresh, or propose a bounded Objective/action.
- **Failure/recovery:** Denied reveals no protected claims; organisation-wide Intelligence is P1 and explicitly labelled.
- **Direct destinations:** [EN-M-02I](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=266-15874), [EN-M-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-17353), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-06D - Intelligence permission denied

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13640) (`271:13640`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Ask a permission-scoped read-only question.
- **Required content:** Scope; permitted sources; retrieval state; cited answer; omissions/conflicts/freshness; denied/unavailable/no-result distinction; proposed follow-up.
- **Primary interaction:** Submit/refine query, inspect citations, compare conflict, request access, refresh, or propose a bounded Objective/action.
- **Failure/recovery:** Denied reveals no protected claims; organisation-wide Intelligence is P1 and explicitly labelled.
- **Direct destinations:** [EN-M-12](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-16695), [EN-M-06](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13510), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-07 - Incident conditional remediation

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13749) (`271:13749`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Understand services and investigate incidents.
- **Required content:** Service/owner/environment/readiness/incident/change; observation time; live/history distinction; omissions; remediation maturity; verification/write-back.
- **Primary interaction:** Open service, inspect evidence/runbook, investigate, create remediation Objective, escalate, or reconcile incident write-back.
- **Failure/recovery:** Live unavailable means current unknown; P0/P1 does not expose executable production rollback.
- **Direct destinations:** [EN-M-02I](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=266-15874), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-06](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13510), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ08 - Service state and incident investigation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej08-service-state-and-incident-investigation)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-08 - Critical Settings connector review

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878) (`271:13878`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Review connector capability and lifecycle.
- **Required content:** Provider name; account; scopes/capabilities; connection/permission/availability/freshness/capability dimensions; retention/legal hold; export/purge effect.
- **Primary interaction:** Authorise/validate, narrow/rotate, reconnect, export, disconnect, queue/verify purge, or cancel.
- **Failure/recovery:** OAuth denial, drift, rate limit, failed write, partial purge, and legal hold have separate recovery; secrets remain write-only.
- **Direct destinations:** [EN-M-12](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-16695), [EN-M-08F](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13990), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-06](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13510), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ09 - Connector permission, health, disconnect, export, and purge lifecycle](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej09-connector-permission-health-disconnect-export-and-purge-lifecycle)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-08F - Connector authorization failed

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13990) (`271:13990`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Review connector capability and lifecycle.
- **Required content:** Provider name; account; scopes/capabilities; connection/permission/availability/freshness/capability dimensions; retention/legal hold; export/purge effect.
- **Primary interaction:** Authorise/validate, narrow/rotate, reconnect, export, disconnect, queue/verify purge, or cancel.
- **Failure/recovery:** OAuth denial, drift, rate limit, failed write, partial purge, and legal hold have separate recovery; secrets remain write-only.
- **Direct destinations:** [EN-M-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-06](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13510), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ09 - Connector permission, health, disconnect, export, and purge lifecycle](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej09-connector-permission-health-disconnect-export-and-purge-lifecycle)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-09A - Deep link authorized

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-17515) (`282:17515`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Resolve an external Enterprise link.
- **Required content:** Origin and intended object/action class; account/view; permission; expiry; source version; authorised/redacted result; request-access/reissue/return-safe path.
- **Primary interaction:** Authenticate, switch view/account, open exact authorised target, request access, reissue, or return.
- **Failure/recovery:** Only authorised states reveal protected identifiers; wrong/expired/denied/revoked/source-changed remain distinct.
- **Direct destinations:** [EN-M-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11937), [EN-M-09D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-20025), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688)
- **Journeys:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-09D - Deep link denied

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-20025) (`282:20025`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Resolve an external Enterprise link.
- **Required content:** Origin and intended object/action class; account/view; permission; expiry; source version; authorised/redacted result; request-access/reissue/return-safe path.
- **Primary interaction:** Authenticate, switch view/account, open exact authorised target, request access, reissue, or return.
- **Failure/recovery:** Only authorised states reveal protected identifiers; wrong/expired/denied/revoked/source-changed remain distinct.
- **Direct destinations:** [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688)
- **Journeys:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-09E - Deep link expired

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-19185) (`282:19185`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Resolve an external Enterprise link.
- **Required content:** Origin and intended object/action class; account/view; permission; expiry; source version; authorised/redacted result; request-access/reissue/return-safe path.
- **Primary interaction:** Authenticate, switch view/account, open exact authorised target, request access, reissue, or return.
- **Failure/recovery:** Only authorised states reveal protected identifiers; wrong/expired/denied/revoked/source-changed remain distinct.
- **Direct destinations:** [EN-M-10C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12400), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688)
- **Journeys:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-09W - Deep link wrong

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-18354) (`282:18354`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Resolve an external Enterprise link.
- **Required content:** Origin and intended object/action class; account/view; permission; expiry; source version; authorised/redacted result; request-access/reissue/return-safe path.
- **Primary interaction:** Authenticate, switch view/account, open exact authorised target, request access, reissue, or return.
- **Failure/recovery:** Only authorised states reveal protected identifiers; wrong/expired/denied/revoked/source-changed remain distinct.
- **Direct destinations:** [EN-M-09A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-17515), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688)
- **Journeys:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-10C - Secure handoff created

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12400) (`264:12400`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-M-10O](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-20866), [EN-M-10R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-21844), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-10D - Continuity completed

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12715) (`264:12715`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 1 canonical actions; 1 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-10O - Handoff opened

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-20866) (`283:20866`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-M-10D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12715), [EN-M-10R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-21844), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297), [EN-M-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-10R - Handoff revoked

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-21844) (`283:21844`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-M-10C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12400), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297), [EN-M-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-10S - Handoff source changed

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12504) (`264:12504`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 3 canonical actions; 3 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-M-02I](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=266-15874), [EN-M-10D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12715), [EN-M-10C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12400), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-10X - Handoff expired

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12613) (`264:12613`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Continue securely across devices.
- **Required content:** One-time same-user token; scoped Objective/evidence; expiry/revocation; destination permission; source-version check; open/complete receipt; origin return path.
- **Primary interaction:** Create/copy/open/revoke/reissue/complete handoff.
- **Failure/recovery:** Opening is not restoration; expired/revoked/source-changed/denied states never expose protected context.
- **Direct destinations:** [EN-M-10C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12400), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297)
- **Journeys:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-12 - Scope review

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-16695) (`290:16695`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297), [EN-M-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878)
- **Journeys:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-20R - Context authority resolved

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-16424) (`302:16424`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Review scoped source context.
- **Required content:** Exact Objective/source scope; citations; permission/availability/freshness/epistemic state; omissions/conflicts; governing authority; affected plan/decision.
- **Primary interaction:** Inspect/compare source, refresh, change scope, request access, choose governing source, or continue.
- **Failure/recovery:** Denied redacts protected data; unavailable differs from no result; source change invalidates dependent authority.
- **Direct destinations:** [EN-M-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11937), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297), [EN-M-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878)
- **Journeys:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-55M - Route unavailable usage limit

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-19483) (`302:19483`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Govern provider/model routing.
- **Required content:** Provider/model names; key source; health; policy/maturity; data/budget boundary; usage/limit; eligible alternatives; approval and route-validation receipt.
- **Primary interaction:** Select/review allowed route, request policy change, validate, resume, or remain blocked.
- **Failure/recovery:** No silent failover/substitution/spend; DeepSeek/OpenRouter/Fireworks remain named without logos.
- **Direct destinations:** [EN-M-55R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-19297), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297), [EN-M-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878)
- **Journeys:** [EJ10 - AI model policy and route recovery](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej10-ai-model-policy-and-route-recovery)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

### EN-M-55R - Model route review

- **Figma:** [open exact screen](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-19297) (`302:19297`)
- **Viewport:** 390x844 (mobile)
- **Prototype contract:** 2 canonical actions; 2 wired; 0 explicitly inert.
- **User job:** Govern provider/model routing.
- **Required content:** Provider/model names; key source; health; policy/maturity; data/budget boundary; usage/limit; eligible alternatives; approval and route-validation receipt.
- **Primary interaction:** Select/review allowed route, request policy change, validate, resume, or remain blocked.
- **Failure/recovery:** No silent failover/substitution/spend; DeepSeek/OpenRouter/Fireworks remain named without logos.
- **Direct destinations:** [EN-M-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11937), [EN-M-55M](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-19483), [EN-M-00](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11434), [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688), [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804), [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297), [EN-M-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878)
- **Journeys:** [EJ10 - AI model policy and route recovery](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej10-ai-model-policy-and-route-recovery)
- **Acceptance:** organisation/team/Objective identity, state tuple, scope, maturity, authority/effect, evidence/freshness, action availability, and recovery are understandable without internal agent logs or colour-only inference.

## Designer completion checklist

1. Use only the 110 accepted EN-D/T/M roots as screen authority.
2. Preserve all 14 scenario journeys and all 20 named flow starts.
3. Retain the 109 stable PRD-ID traceability contract and maturity labels.
4. Verify keyboard order/focus visibility, traps/dismissal/focus return, screen-reader semantics/live regions, reduced motion, 200% zoom/reflow, and 30% localization expansion.
5. Retain server-authoritative RBAC/redaction and connector/reconciliation states in every affected composition.
6. Keep Studio/Enterprise and Objective/Search-context boundaries explicit.
7. Do not treat this UX package as proof that production APIs, permissions, connectors, or persistence are implemented.
