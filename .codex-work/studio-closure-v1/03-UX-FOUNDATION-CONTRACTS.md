# ExecuteSpec Studio — UX Foundation Contracts

Version: 0.1  
Date: 2026-07-23  
Status: **Initial closure baseline**

## 1. Experience sentence

Studio should feel like one capable collaborator working on one real product with the user—not a dashboard of AI features, agents, infrastructure, or enterprise administration.

## 2. Shell contract

### Wide desktop

- Left pane: Studio identity, new Task, Projects, recent Tasks, account/plan.
- Center: current Task thread, semantic progress, Decisions, Outcome cards, composer.
- Right pane: Preview, Changes, Checks, Data, or Release for the current Task.
- Both side panes collapse independently, resize within bounded widths, and restore per device.
- Collapsed pane affordances use icons with labels/tooltips, never numeric placeholders.
- The center owns the largest share of usable width; opening a pane must not make the Task unreadable.

### Compact desktop/tablet

- Keep at most one persistent side pane when the center remains usable.
- Other pane opens as an anchored overlay/drawer.
- Preserve Task/Output selection and scroll positions when panes change state.

### Mobile

- One active primary surface at a time.
- Projects/Tasks and Output open through drawers/sheets/tabs.
- Working state and unresolved Decision remain persistent across switches.
- Back navigation follows user history, not internal object hierarchy.

## 3. Density contract

- Default Studio density is lower than Enterprise.
- One primary outcome, one active Task, and one next meaningful action dominate each state.
- Professional metadata appears in a contextual strip, expandable detail, or relevant Output—not as permanent dashboard chrome.
- Empty space is functional: it protects the Preview, composer, and decisions from competing panels.
- No page presents the internal feature catalog as navigation.

## 4. Object language

| Internal/platform term | Studio default | Professional disclosure |
|---|---|---|
| pipeline run | Task activity | execution record |
| agent/session | Studio | agent session only in advanced detail |
| workspace | Project | local/cloud workspace when relevant |
| artifact | Output or named object | build/deployment artifact in advanced detail |
| validation report | Checks | evidence report |
| deploy target | destination | environment/target |
| quota | usage/limit | exact entitlement/credit detail |
| model route | managed AI | provider/model/BYOK |
| rollback mechanism | Restore/Roll back | exact release/version/branch action |

Never expose internal agent personas/crew names, chain-of-thought, unstable tool counts, or invented progress percentages.

## 5. Navigation contract

Persistent global navigation:

- Home
- current Project/Task context
- Account

Persistent Project lenses:

- Build
- Data
- Checks
- Ship

Contextual, not global:

- Versions/history
- Settings
- Changes/diff
- Evidence detail
- Resource configuration
- Release history
- Client delivery
- provider/model/BYOK

Forbidden permanent top-level destinations:

- Runs
- Agents
- Models
- Benchmarks
- Integrations catalog
- Evidence
- Deployments
- Storage
- Auth
- Functions
- Secrets
- Client administration
- Gamification

## 6. Settings contract

| Group | User job | Visibility |
|---|---|---|
| Account | identity, plan/usage, privacy, learning preferences | global |
| Project | defaults, agent behavior, design/assets, context/docs, versions | Project |
| Resources | data/users, storage/media, functions/automation, integrations/secrets | Project/contextual |
| Source & quality | repositories/local, Checks/evidence, CI/review | advanced/Max/contextual |
| Publish & sharing | environments/domains, Release/rollback, access | Project/contextual |
| Client delivery | workspace identity, scope, review, handoff, offboarding | Max or upgrade intent |

Settings stores stable nouns, defaults, and policy. Current verbs and decisions stay in the work surface.

## 7. State grammar

### Status vocabulary

- Ready
- Working
- Needs input
- Complete
- Needs attention
- Paused

### Evidence vocabulary

- Verified
- Passed
- Failed
- Partial
- Inconclusive
- Unmeasured
- Stale
- Source unavailable
- Permission denied

### Authority vocabulary

- Review
- Approve
- Reject
- Change
- Cancel
- Retry
- Reconnect
- Restore
- Roll back
- Continue on desktop/CLI/IDE

Avoid generic `Continue`, `Confirm`, or `Done` when a consequential verb can be named.

## 8. Decision card contract

Every Decision card contains:

1. direct question;
2. affected Project/client/source/environment;
3. consequence and what remains unchanged;
4. evidence/freshness;
5. cost/usage when material;
6. reversibility and recovery;
7. authority expiry/invalidators;
8. recommended safe action plus alternative;
9. explicit primary and secondary verbs.

Routine implementation choices resolved by safe defaults do not interrupt the user.

## 9. Entitlement contract

- The shell never changes across Free, Pro, and Max.
- A locked control appears only after expressed intent or in a deliberate plan-comparison context.
- Upgrade resumes the same Task at the same decision point.
- Downgrade cannot destroy source, data, versions, evidence, or recovery access.
- Hard limits produce clear usage state and safe read-only/export/recovery behavior.
- BYOK is a provider/usage configuration, not a fourth visible Studio product tier.

## 10. Casual-to-professional contract

Enabling Professional Mode:

- retains Project, Tasks, Brief, Versions, Changes, Checks, Resources, Releases, data, progress, and history;
- adds source/client/scope/CI/evidence/handoff detail progressively;
- never duplicates or migrates the Project;
- requires explicit client isolation before client credentials/context are introduced;
- prevents automatic promotion of client context into personal memory/templates.

## 11. Progress and gamification contract

- No progress destination in global navigation.
- Progress appears contextually after verified outcomes.
- Celebration is opt-in, dismissible, reduced-motion aware, and never blocks work.
- Streaks/quests/showcase are user-controlled preferences.
- Professional Mode uses subtle milestones; it does not introduce leaderboards or productivity scoring.
- Spend, token consumption, generated code volume, and unnecessary execution never create rewards.

## 12. Visual foundation contract

- Reuse the accepted ExecuteSpec palette and semantic color roles from the Enterprise design system while changing composition and density for Studio.
- Do not copy Enterprise screen layouts.
- Extract and document color, typography, spacing, radii, elevation, icon size, focus, motion, and breakpoint tokens on the Studio `Foundations` page.
- Components must bind to semantic variables where supported.
- Third-party provider/model/source names remain available as text. Official-logo representation is deferred and is not a Phase 0 blocker; no substitute letter tiles or fake marks are introduced.

## 13. Component contract

Minimum canonical families:

- App shell / Pane / Pane toggle / Drawer / Sheet
- Project switcher / Task row / Task status
- Composer / Attachment / Selection context
- Agent status / Mission phase / Activity summary
- Decision card / Outcome card / Recovery card
- Output tabs / Preview toolbar / Device switcher
- Change summary / Diff disclosure / Version chip / History timeline
- Check summary / Check row / Evidence disclosure
- Resource row / Connection state / Secret field / Permission notice
- Release summary / Destination / Health / Rollback
- Usage meter / Intent gate / Upgrade sheet
- Client boundary / Scope / Change request / Handoff
- Settings group / Section / Row / Protected state
- Progress milestone / Quest / Celebration preference
- Empty / Loading / Partial / Error / Denied / Unavailable / Offline states

Variants must encode real state, tier, breakpoint, authority, and interaction differences. Detached lookalikes are not accepted.

## 14. Interaction and accessibility contract

- All visible controls have action semantics, hover/focus/pressed/disabled states, and touch targets.
- Keyboard order follows reading and consequence order.
- Modal/sheet focus traps and restores deterministically.
- Pane toggles announce expanded/collapsed state.
- Live working/progress updates use polite announcements; failures and required Decisions are assertive only when necessary.
- Reduced motion removes non-essential transitions/celebrations without hiding state change.
- Status includes icon/text, not color alone.
- External links, downloads, destructive actions, and environment changes are announced clearly.

## 15. Prototype contract

The playable prototype must include:

- named starts for Free, Pro, Max, tablet, mobile, professional onboarding, failure recovery, and upgrade continuity;
- no unwired actionable component instance unless explicitly inert/documented;
- no invalid destination, self-link, malformed URL, or P0 dead end;
- focus-order, focus-return, reduced-motion, and live-region metadata;
- critical destructive/client/secret/provider/billing data redaction states;
- exact traceability back to requirement and screen IDs.

