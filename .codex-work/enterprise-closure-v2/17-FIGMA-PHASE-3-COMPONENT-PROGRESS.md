# ExecuteSpec Enterprise — Figma Phase 3 Component Progress

Status: **In progress; icon family and Action/Button structurally and visually closed**  
Date: 2026-07-20

## Semantic UI icons

The Components page now contains 17 ExecuteSpec-owned 24px outline components:

`Home`, `Objective`, `Intelligence`, `Services`, `Evidence`, `Settings`, `Search`, `Plus`, `ChevronLeft`, `ChevronRight`, `PanelLeft`, `PanelRight`, `Close`, `Ask`, `Notifications`, `Account`, and `More`.

All vector strokes bind to `color/icon/muted`. Metadata validation returned 17/17 components, 24×24 dimensions, complete vector bindings, stable `dsb` keys and zero missing fonts. Screenshot review passed. These glyphs are product UI icons and are not third-party provider identities.

## Action/Button

The button family is split into five sets to keep every Cartesian matrix at or below 30 variants:

| Component set | Node | Variants |
|---|---|---:|
| `Action/Button/Core Small` | `83:194` | 20 |
| `Action/Button/Core Medium` | `83:196` | 20 |
| `Action/Button/Core Large` | `83:198` | 20 |
| `Action/Button/Supporting` | `83:200` | 30 |
| `Action/Button/Disabled` | `83:202` | 3 |

Total: 93 variants. The core sets cover Normal, Approval, High-impact and Destructive across Default, Hover, Focused, Pressed and Loading. Supporting covers Secondary and Ghost across 32/40/44px target sizes and the same five states. Disabled covers all three target sizes.

Each set exposes `Label` (TEXT), `Show Icon` (BOOLEAN), and `Icon` (INSTANCE_SWAP). Loading variants suppress the icon and own the fixed `Working…` label; disabled variants own `Unavailable`. Focus effects follow action severity.

## Defects found and repaired during visual QA

1. Newly bound semantic aliases rendered the seed `#808080` after variant rebinding. The final binding helper now resolves each Dark alias to its primitive and stores that value as the paint fallback while preserving the semantic variable binding.
2. Opaque hover/pressed overlays erased amber and red severity. Consequential variants now preserve their intent surface for default/hover/focus/loading and use severity-specific pressed treatments; the obsolete state layer remains hidden in all 93 variants.
3. Component TEXT properties replaced `Working…` and `Unavailable` with `Continue`. Loading and disabled variants now remove that text reference and own their state label.

Final screenshot review of the medium core set confirms the four action severities, hover outline, severity-specific focus/pressed behavior and visible loading indicator. An isolated Focused specimen renders at 120×48, proving the full outer focus treatment is not clipped.

## Code Connect decision

No mapping was published. The intended implementation boundary is `frontend/src/design-system/ActionButton.tsx`, but that boundary is not yet real. Publishing a mapping now would misrepresent implementation evidence. Map it only after the React component exists and the design/code prop contract is verified.

## Semantic status icons

Seven additional 24px status atoms—Neutral, Active, Attention, Blocked, Failure, Success and Terminal—pair semantic shape with a bound semantic colour. They are never used without text. Structural validation confirmed 7/7 components, 24×24 geometry, bound vector paint and zero missing fonts.

## Status/Maturity

`Status/Maturity` (`93:566`) has six variants: Current, Partial, Planned P0, P1, P2 and Conditional. The labels and semantic icons are fixed; only the `Maturity` VARIANT axis is exposed. This prevents an instance from visually claiming a maturity different from its implementation state. All fills and strokes are variable-bound, and screenshot plus fresh structural validation passed.

## Status/Compound

`Status/Compound` (`94:674`) has 14 variants: seven semantic tones across Inline and Stacked layouts. Inline is the wide-pane form; Stacked preserves the same compound truth for narrow tablet/mobile surfaces. Instances expose `Label`, `Detail` and `Show Detail`; the semantic icon is deliberately not swappable. This keeps process/outcome meaning aligned with Neutral, Active, Attention, Blocked, Failure, Success or Terminal. All 14 variants passed visual and structural validation.

## Navigation/Row

`Navigation/Row` (`97:342`) contains 28 variants across Expanded/Collapsed, Desktop/Touch and seven states. Expanded rows use a fixed 24px semantic icon slot, 12px icon-label gap, 240px row width and 40/44px height. Collapsed rows are icon-only at 44×44 desktop and 48×48 touch; the Label property remains the implementation’s accessible name and tooltip. Unread and warning dots are state-driven and sit outside the glyph. Label and Icon properties are exposed; dot visibility is not freely editable. Screenshot and fresh structural validation passed.

## Navigation/PaneToggle

`Navigation/PaneToggle` (`98:808`) contains 20 variants across left/right pane, expanded/collapsed pane state and Default/Hover/Focused/Pressed/Disabled. Every control is 44×44. Pane plus PaneState fixes the icon and hidden accessible label: left-expanded collapses left, left-collapsed expands left, and equivalently for the right pane. No editable property can break that pairing. Screenshot and fresh structural validation passed.

## Permission/Notice

`Permission/Notice` (`100:818`) contains 10 variants across Allowed/Limited/Denied/Revoked/Unknown and Inline/Stacked. Permission titles and semantic icons are fixed; only the safe Detail copy is editable. Recovery controls are intentionally composed outside the notice using the typed Action/Button family, because a notice must not imply authority. Visual QA caught an initially clipped default Detail string; the component-property default was shortened to `Protected content remains hidden.` and the fresh structural check confirmed text fit in all 10 variants. All fills/strokes are bound and no fonts are missing.

## Identity marks and provider identity

Six 24px identity marks were promoted without recolouring from the existing design source: Bitbucket Cloud, Jira Cloud, Slack, OpenAI, Anthropic and Google (`103:427`, `103:432`, `103:437`, `103:445`, `103:456`, `103:461`). They remain provisional until the official asset/terms review is complete.

`Identity/Provider` (`105:470`) contains ten fixed provider variants. Approved/provisional marks are inseparable from their canonical provider names. DeepSeek, OpenRouter and Fireworks are name-only by explicit product decision; they have zero mark instances and no substitute glyph. Customer-hosted models use the neutral ExecuteSpec Services glyph, not a third-party mark. Visual and fresh structural validation passed.

## Source/Identity

`Source/Identity` (`106:980`) contains 18 variants across Bitbucket Cloud/Jira Cloud/Slack, Compact/Full, and Available/Denied/Unavailable. Provider identity, object metadata and operational state are separate. `Object label` and `Source link` are editable text properties; provider/state semantics are fixed by variants. Screenshot QA found and repaired two composition defects: the initial compound-status dependency overflowed the cards, and the compact Jira sample wrapped over its link. The final family uses compact semantic status chips, a provider-neutral sample object label and child-bounds validation across all variants.

## Model/Identity

`Model/Identity` (`109:1681`) contains 42 variants across seven provider routes, Compact/Full layouts and Allowed/Limited/Unavailable availability. Every variant contains serving provider name, an editable full registry model label, fixed route, fixed maturity and separate availability. Anthropic, OpenAI and Google retain their marks; DeepSeek, OpenRouter and Fireworks structurally contain no logo; customer-hosted uses the neutral Services glyph. Screenshot and composition-only child-bounds validation passed after increasing compact metadata height and width. No placeholder or invented provider mark exists.

## Objective/Row

`Objective/Row` (`111:1363`) contains 24 variants across eight lifecycle states and three fixed source identities. Each row uses a lifecycle shape, state label and colour together; Needs-you and Unread also include an attention dot. Jira Cloud, Slack and Bitbucket Cloud sources always pair their 14px mark with the canonical name. Title, subtitle, lifecycle and state-summary anchors remain fixed. Fresh validation caught and repaired a 4px content-stack overflow and a mistyped radius token that had left the masters square.

## Search/Context

`Search/Context` (`112:1225`) contains Empty, Focused, Searching, Partial, Error and Denied variants. `SEARCH CONTEXT` and `READ-ONLY` remain fixed; scope and Code/Docs/Discussions retrieval modes are visible in every state. The only editable property is Query. An initially unused Scope property was removed rather than publishing a misleading control. There is no send or mutation affordance. Containment and screenshot review passed.

## Composer/Objective

`Composer/Objective` (`113:1321`) contains Empty, Focused, Draft, Sending, Error and Disabled states. Every variant displays `PROPOSAL ONLY`, explicit scope and the Add-context affordance. Empty/Focused/Disabled use the disabled Action/Button, Draft uses the normal proposal action, Sending uses Loading, and Error uses Try again. The action creates a reviewable proposal and does not claim direct repository or system authority. Nested action-state and containment validation passed.

## Scope/Builder

`Scope/Builder` (`115:1324`) contains Compact, Expanded, Conflict and Denied variants with separate Org, Team, Project, Service, Repo, Source and Time fields. Expanded explicitly warns that changing scope invalidates existing approval; Conflict identifies the Service/Repo mismatch. Denied structurally removes the editable property references for Service, Repo, Source and Time and replaces them with `Protected`, so hidden identifiers cannot leak through an instance override. Screenshot and fresh bounds validation passed.

## Citation/Row

`Citation/Row` (`116:1633`) contains 24 variants across eight epistemic classifications and Jira Cloud/Slack/Bitbucket Cloud source identities. Object, version and observed time are editable only in permitted variants; Denied removes those references and renders `Protected`. The canonical source logo/name pair remains visible so the omission is explainable without leaking hidden object identifiers. Source pairing, epistemic labels, denied redaction and child bounds passed fresh validation.

## Evidence/Card

`Evidence/Card` (`117:1471`) contains Passed, Failed, Partial, Inconclusive, Unmeasured and Stale outcomes. Category, classification, exact subject version, environment, observed time and limitations are visible in every card. Limitations remain present even for Passed evidence. Unmeasured is a blocked/danger semantic state and cannot visually inherit Passed. All six variants passed screenshot and containment review.

## Evidence/LedgerRow

`Evidence/LedgerRow` (`118:1645`) contains 12 State × Layout variants. Compact and Detail share exact column anchors for Source/artifact, Attempts, Classification, Exact version and Outcome; Detail adds retained retry history without moving the columns. This is the implementation contract for a keyboard-accessible table or virtual list. Anchor order, detail visibility, outcome semantics and containment passed.

## Action/Card

`Action/Card` (`119:3751`) contains 42 Lifecycle × Impact × Layout variants. All cards expose action type, actor, target, scope, effect, route, budget, preconditions, evidence, recovery, maturity and expiry. Awaiting approval uses Approval intent, Running uses Loading and Blocked uses the disabled button family. Wide (638×340) and Stacked (420×464) retain the same authority-bound fields. Fresh bounds validation found and repaired undersized stacked contract cells before both focused specimens passed screenshot review.

## Decision/Option

`Decision/Option` (`121:1866`) contains Unselected, Selected, Disabled, Conflict and Recommended whole-card controls. Title, rationale, risk, evidence and maturity remain visible. Selected and Recommended carry the normal focus effect; the implementation contract is a radio-style whole-card target, not a nested checkbox. All five variants passed containment and screenshot review.

## Conflict/Card

`Conflict/Card` (`122:1953`) contains Unresolved, Authority selected, Resolved and Superseded. Jira Cloud and Slack values retain marks, names, authority/freshness and rationale. Unresolved explicitly says `NO AUTOMATIC MERGE`; Authority selected requires an Approval action; Superseded is disabled. Similarity never chooses authority. All variants passed attribution, action-state and bounds validation.

## Answer/Panel

`Answer/Panel` (`123:2137`) contains Complete, Partial, Denied, Conflicting, Stale, Unable, Timeout and Greenfield. Only Complete and Partial use grounding language and retain an editable conclusion. Other states use fixed non-editable state-specific conclusions. Visual QA found two shared-property truthfulness defects: non-success states initially inherited Complete’s counts and conclusion. Both were removed from those variants and replaced with state-safe fixed values. Scope, modes, counts, omissions and next actions remain visible throughout.

## Verification/PlanRow, Verification/RunRow and GeneratedTest/Proof

`Verification/PlanRow` (`126:2015`) keeps Required, Recommended, Excluded, Missing and Exception requested distinct; required removal must use the exception flow. `Verification/RunRow` (`127:2123`) separates queued/running/test outcomes from environment failure, cancellation and SHA supersession while retaining attempts and artifacts. `GeneratedTest/Proof` (`129:2327`) contains 12 Context × Validity cards; passing baseline and candidate is fixed as Inconclusive. Fresh bounds validation caught and repaired a 2px proof-metadata overflow before final screenshot review.

## Validation closure

A fresh consolidated structural read passed across all five component sets. It confirmed 93/93 variants, exact set counts of 20/20/20/30/3, one TEXT + one BOOLEAN + one INSTANCE_SWAP property per set, semantic bindings on every primary fill, zero visible obsolete state layers, focus effects on all Focused variants, visible spinners plus `Working…` on all Loading variants, `Unavailable` on all Disabled variants, and zero missing fonts. Action/Button is now approved for downstream component and screen use.

## Shell/TopBar

`Shell/TopBar` (`132:2085`) contains Desktop, Compact, Tablet and Mobile variants at 1320×64, 1040×64, 834×64 and 390×56. Breadcrumbs, page title and current-job status are editable properties. Ask, notifications and account/more remain reachable without adding a global create CTA that would compete with the current Objective job. The first visual pass found component-set corner clipping and mobile overflow from shared long defaults; both were repaired. All four variants now have zero direct-child overflow, use JetBrains Mono, and bind shell fills/strokes to ExecuteSpec variables.

## Shell/LeftPane

`Shell/LeftPane` (`136:2403`) contains Expanded (288px), Collapsed (56px) and tablet Overlay (320px) variants. Expanded/Overlay show organisation/team, needs-you Objectives with explicit lifecycle/source identity, global tools and account/environment footer. Collapsed mode contains named semantic icons and status dots only—no numeric placeholders. Desktop rows retain 40px targets, collapsed rows are 44px and overlay rows use touch targets. Composition-level containment and screenshot review passed; the only intentional non-token paints are the preserved official/provisional Bitbucket, Jira and Slack brand vectors.

## Shell/Inspect

`Shell/Inspect` (`137:2448`) contains Docked (440px), Collapsed (56px), Tablet drawer (360px) and Mobile route (390px). The pane preserves selected scope/evidence and exposes a visibly separate read-only Search context region. The first structural audit found seven header/helper containment failures; fill sizing and text wrapping were repaired and the second audit returned zero failures. Collapsed Sources and Search context icons were separated after visual review so the read-only query cannot be mistaken for Objective Ask/composition. Drawer scrim, focus trap, Escape/back close and restored focus are explicit composition requirements for the later Drawer/Modal/Sheet family and responsive screens.

## Service/Row

`Service/Row` (`140:2389`) contains Healthy, Warning, Incident, Unknown and Unavailable states. State and LIVE/HISTORY observation mode are fixed semantics; Service, Owner, Environment and Work remain editable content. Eight initial column-boundary failures were repaired by rebalancing the exact 1320px grid. The final family passed containment and screenshot review.

## Source/Row

`Source/Row` (`141:2518`) contains Healthy, Stale, Drift, Denied, Unavailable, Disconnected and Deletion queued. Provider identity, lifecycle state, capability, permission, freshness and owner are separate fields. The deletion lifecycle displays `PURGE QUEUED`; a long sample object label that collided with the nested source link was replaced with a stable short object identifier. All variants passed final containment and visual review.

## Connector/Card

`Connector/Card` (`143:3455`) contains 24 provider × state variants across Bitbucket Cloud, Jira Cloud and Slack, and Not connected, Validating, Healthy, Stale, Drift, Failed, Disconnected and Purge. The entire card is a named accessible control. Visual QA explicitly covered Jira Healthy, Bitbucket Validating and Slack Purge. Purge revokes permission, shows retained-until freshness, and preserves the scheduled deletion validation; Validating communicates requested discovery without implying readiness.

## Provider logo exception

DeepSeek, OpenRouter and Fireworks remain supported provider and model routes. Their canonical names remain visible, but their provider/model identity components contain no logo instance and no placeholder glyph. This is an explicit temporary product decision pending separate asset/usage resolution.

## Settings/GroupNav and Settings/ChangeReview

`Settings/GroupNav` (`147:2663`) preserves six stable groups across Expanded, Compact and Mobile. Compact uses semantic icons plus status dots; expanded/mobile preserve explicit `DIRTY` and `ERROR` pills. Visual QA repaired compact overflow and soft-fill contrast. `Settings/ChangeReview` (`147:2836`) carries Clean, Dirty, Validation failed, Approval required, Saving, Saved and Failed through one reviewed batch with changed fields, effect and audit preview. Ambiguous disabled `Unavailable` copy was replaced with state-specific non-action guidance.

## Memory/Row and Audit/Row

`Memory/Row` (`151:2741`) separates Explicit, Inferred, Policy, Disabled and Expired while preserving provenance/confidence, private/team scope, precedence, last use, expiry and reversible controls. `Audit/Row` (`151:2880`) covers Read, Action, Approval, External result and Deletion with actor, tenant, source, target, correlation, time and result. Protected content is redacted without erasing event evidence. Visual QA repaired default white fills in both status columns.

## Handoff/Card and Continuity/Card

`Handoff/Card` (`152:2791`) covers Draft, Sent, Accepted, Declined, Redirected and Expired. Responsibility moves only after explicit consent; decline and redirect history remains visible. `Continuity/Card` (`152:2909`) covers Token created, Opened, Expired, Revoked, Source changed and Completed. The token is one-time and user-bound; the destination must reauthorize before protected context restores. Initial containment audits found 24 handoff and six continuity overflows; master heights and documentation reflow were repaired without removing any authority or recovery field.

## P3.n lifecycle, overlay and feedback families — closed

Created and individually validated:

- `Objective/Stage/Wide` (`153:3025`) and `Objective/Stage/Compact` (`153:3026`), 24 variants each.
- `Timeline/Event` (`153:3156`), six user-level event types.
- `Empty/State` (`154:2817`), four state-specific absence compositions.
- `Failure/Panel` (`154:2917`), six classified failures with preserved evidence and valid recovery.
- `Drawer/Modal/Sheet` (`155:2857`), desktop/modal, tablet/drawer, mobile/sheet and mobile/route.
- `Toast/Banner` (`157:2877`), eight tone × persistence variants with accessible urgency.
- `Loading/Skeleton` (`157:2937`), five known-scope static loading specimens.

Inventory reconciliation found and repaired one remaining gap. Permission/Notice now includes Expired and Wrong view in both layouts; masters `158:22`, `158:29`, `158:38`, `158:47` are children of set `100:818`, Cover is clean, and Expired uses terminal-neutral rather than revoked-danger styling.

The final one-to-one audit found all 41 required family contracts, zero missing fonts and zero direct-child overflows. DeepSeek, OpenRouter and Fireworks remain name-only across provider and model identity. The original minimal sets are explicitly deprecated as `Legacy/Button`, `Legacy/Objective Row` and `Legacy/Source Row`. Phase 3 is closed; screen repair must use canonical component instances.

## CP-060 — Desktop neutral/redaction repair batch 1

Two implementation-replacement frames were added to Enterprise Screens without deleting the originals:

- `EN-D-00A · Neutral view choice · R1` (`166:345`) replaces populated `50:291`. It exposes only the Studio/Enterprise choice and generic account assurance before selection. It contains no tenant, project, objective, source, member or memory identifiers.
- `EN-D-01Z · Zero information · R1` (`168:3261`) replaces populated `40:103`. It proves no organization, repository, source, person, objective, graph, freshness record or memory exists or has been queried, and offers only organization creation or a greenfield brief.

Both screens use local color variables and linked canonical component instances. The first visual pass repaired default-white structural fills and undersized Decision/Option instances. The zero-information notice was shortened and returned to the canonical inline layout after stacked layout created excessive dead space. Final structural validation reports zero overflow; focused screenshots were reviewed; forbidden inherited identifiers are absent.

Next: split `40:438` into permission-denied and searched-no-result states, then split `50:626` into wrong-view, expired and denied recovery states.

## CP-061 — Desktop state split batch 2

The two overloaded legacy frames were decomposed into five truthful replacement screens:

- `EN-D-14D · Context permission denied · R1` (`172:487`)
- `EN-D-14E · Context searched no result · R1` (`174:562`)
- `EN-D-60W · Wrong view recovery · R1` (`175:611`)
- `EN-D-60E · Continuity expired · R1` (`176:3425`)
- `EN-D-60D · Link authorization denied · R1` (`177:663`)

The denied screen evaluates permission before retrieval and redacts the sample Checkout scope inherited by the canonical denied Answer/Panel. The no-result screen contains a search receipt, asserts zero permitted matches and explicitly excludes denied/unavailable content from the zero. The three link-recovery screens contain only their own state and never mix wrong-view, expiry and denial semantics.

Visual QA found that Expired/Wrong-view Permission/Notice masters were structurally inside `100:818` but their detail layers had empty `componentPropertyReferences`. Layers `158:26`, `158:33`, `158:42`, `158:51` are now bound to `Detail#100:0`; a throwaway instance property test passed. All five screens have zero forbidden inherited identifiers, zero overflow and focused screenshot review.

Next: repair the failed/inconclusive/unmeasured evidence state and partial/stale/conflicting plus denied/unable answer states.

## CP-062 — Evidence and answer truth batch 3

Four replacement screens were added and validated:

- `EN-D-25F · Verification failed incomplete · R1` (`179:674`)
- `EN-D-26P · Answer partial stale conflicting · R1` (`181:806`)
- `EN-D-26D · Answer denied · R1` (`183:973`)
- `EN-D-26U · Answer unavailable · R1` (`184:1026`)

Verification now separates terminal process completion from outcome and evidence completeness: one failed, one inconclusive and one unmeasured card are summarized by a Failure compound status, with preserved proof and valid recovery. Partial answer exposes two omissions, one unresolved Jira/Slack authority conflict and one stale deterministic source; similarity is explicitly prohibited from choosing authority. Denied and unavailable are separate screens with permission versus environment/availability causes.

Visual QA found Partial Answer/Panel accepted shared property calls but left `CONFLICTS 0` because four text layers had no property references. Master `123:1878` now binds `123:1895`, `123:1898`, `123:1901`, `123:1904` to Citations, Conflicts, Omissions and Omissions-detail. The screen now renders `CONFLICTS 1` and the exact omission explanation. Two nested official Jira/Slack vector frames intentionally bleed 2px beyond their 16px wrapper; this is preserved brand artwork, not a layout overflow, and does not overlap adjacent content.

Next: consequential operational states—connector drift, disconnect/purge/export, rejected/expired plan, incident, route unavailable/limit and reconciliation.

## CP-063 — Operational consequence batch 4; paused

Four additional R1 replacements are validated:

- `EN-D-39H · Connector drift · R1` (`187:1120`)
- `EN-D-39P · Disconnect purge export · R1` (`189:1278`)
- `EN-D-23X · Plan rejected expired invalidated · R1` (`192:1440`)
- `EN-D-38I · Incident conditional remediation · R1` (`194:1593`)

Connector drift expresses connection, permission, availability, freshness and capability as an aligned tuple and contains no Healthy claim. Purge review exposes the exact Jira connection target, export-first path, retained/deleted inventory, legal-hold result, typed confirmation, fresh admin authentication and the point after which recovery is impossible. Plan invalidation keeps v3 rejection and v4 invalidation decisions queryable while the execution action remains blocked. Incident remediation is P2 + Conditional, recommendation-only until the Incident Commander approves the exact production release; P0/P1 are explicitly prevented from exposing a production rollback control.

All four screens have zero layout overflow and focused screenshot review. The original screens remain retained pending prototype validation.

**Paused by user after CP-063.** Resume with route unavailable/limit (`44:583`) and external-write reconciliation (`51:327`), checkpoint CP-064, then continue shared shell and purpose-built global-tool work.
