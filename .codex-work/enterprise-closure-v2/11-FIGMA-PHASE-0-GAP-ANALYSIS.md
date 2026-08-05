# ExecuteSpec Enterprise — Figma Phase 0 Gap Analysis

Status: **Scope locked; Figma mutation requires Phase 0 approval**  
File: `aq1I0rCCUpfRKe7gyyO41S`  
Run: `enterprise-closure-v2-2026-07-20`

## P0.a — Code and asset truth

The protected Console uses JetBrains Mono and the black/green ExecuteSpec palette from `frontend/src/console/console.css`. Its dark-mode roles map to `--ide-*` variables, backed by `--es-*` primitives. The production token floor includes 32px desktop and 44px mobile hit targets, responsive breakpoints at 480/768/1024/1280/1536, and explicit success, warning, danger, active, focus and surface roles.

The repository does not contain official third-party/provider SVG assets. Current provider marks in `ModelPicker.tsx` are letter tiles, not official logos. The existing Figma file already contains official marks for the currently demonstrated integrations/models; those must be reused and subjected to the documented brand/licensing review. No substitute letter tile may be introduced for a provider that has an official mark.

## P0.b — Existing Figma truth

| Area | Existing |
|---|---|
| Pages | Cover, Foundations, Components, Enterprise Screens |
| Variables | 71 across ExecuteSpec Primitives (34), ExecuteSpec Color (21, Dark/Light), ExecuteSpec Size (16) |
| Styles | 7 text, 2 effect, 0 paint |
| Component sets | Button (2), Objective Row (2), Source Row (2) |
| Screens | P00 plus 45 destinations; desktop, compact, tablet and mobile examples |
| Prototype | 224 reactions at Phase 0 inspection |

All variables have appropriate scopes and WEB code syntax. Primitive variables intentionally have no scopes. Existing palette values and code syntax align with the live Console, so the Figma collection architecture wins and is extended rather than replaced.

## P0.c — Library reuse result

The file is subscribed to Material 3, Simple Design System, iOS/iPadOS and macOS libraries. Searches for action buttons, navigation rails, semantic status badges and tooltips returned no compatible assets in the available search index. Even a close visual match would not encode ExecuteSpec's authority, maturity, permission, evidence and recovery contract. Decision: keep official icon assets where valid; build ExecuteSpec semantic components locally.

## P0.d — Locked v1 build scope

### Foundation delta

- Extend `ExecuteSpec Color` with semantic action roles: approval, high-impact, destructive, blocked, disabled and their required soft/border/text roles in Dark/Light.
- Extend state roles independently: active, attention, blocked, terminal-neutral, denied/redacted, stale and overlay/scrim.
- Extend `ExecuteSpec Size` with 40px action/navigation, 44px touch, 24px icon slot, 56px collapsed rail, 240/288px expanded rail and 360/440px Inspect widths.
- Add focus-danger and focus-high-impact effect styles while retaining existing elevation styles.
- Add readable text styles for 24px page heading, 18px section heading, 15px primary body and 13px navigation/supporting text. Existing 11/12px styles become metadata-only.

### Component dependency order

1. Action/Button, Action/CriticalButton, Navigation/Row, Navigation/PaneToggle, Status/Maturity and Status/Compound.
2. Permission/Notice, Empty/State, Failure/Panel, Toast/Banner and Loading/Skeleton.
3. Source/Identity, Model/Identity, Source/Row, Citation/Row and Evidence/Card/LedgerRow.
4. Objective/Row, Objective/Stage, Composer/Objective, Search/Context and Scope/Builder.
5. Action/Card, Decision/Option, Conflict/Card and Answer/Panel.
6. Verification/PlanRow, Verification/RunRow and GeneratedTest/Proof.
7. Shell/LeftPane, Shell/Inspect, Shell/TopBar and Drawer/Modal/Sheet.
8. Service/Row, Connector/Card, Settings/GroupNav, Settings/ChangeReview, Memory/Row, Audit/Row, Timeline/Event, Handoff/Card and Continuity/Card.

The detailed variant/property contract remains normative in `07-COMPONENT-AND-VARIANT-INVENTORY.md`. Large matrices must be split below 30 variants; icon identity uses instance-swap or local official vector assets rather than an icon variant explosion.

## P0.e — Conflict register and resolution

| Conflict | Evidence | Resolution |
|---|---|---|
| Live code uses a broad Graphite/Signal token layer while current Enterprise Figma uses Console `--es-*`/`--ide-*` roles | `tokens.css`, `console.css`, Figma variables | Enterprise file follows the protected Console palette; code syntax preserves the exact live CSS mapping. Do not import unrelated public/legacy surface tokens. |
| Current Button exposes only Primary/Secondary | Figma `8:10` | Supersede with semantic action families; preserve old set until screen migration proves no live instance is orphaned. |
| Current Objective Row conflates selection with lifecycle/attention | Figma `8:17` | Replace with independent selected, lifecycle, needs-you, unread and disabled semantics. |
| Current Source Row conflates connection and maturity | Figma `8:28` | Replace with identity + capability + permission + availability + freshness. |
| Most screen copy uses 11–13px text | 2,230 text-layer audit | Apply the approved 15–16px decision/instruction body floor and 13–14px navigation floor; 11–12px remains metadata only. |
| Official logo requirement is stronger than current repository assets | product contract vs code letter tiles | Figma uses/reuses official vectors plus canonical names. Code implementation receives an explicit asset gap; no fake logo is approved. |
| Existing Figma page structure lacks Getting Started/Utilities and per-component pages | library best practice vs an established product file | Preserve the four-page product file to avoid multiplying surfaces; document component families in clearly named sections on Components. This is a deliberate local-file exception, not a silent omission. |

No unresolved product decision fork remains. The source hierarchy already determines each resolution.

## P0.f — Exit assessment

- P0.a complete: code and asset sources inspected.
- P0.b complete: pages, variables, styles, components and prototype baseline inspected.
- P0.c complete: subscribed libraries and targeted searches inspected.
- P0.d complete: token/style/component scope locked.
- P0.e complete: all discovered conflicts resolved with provenance.
- P0.f complete: this gap analysis and the state ledger are persisted.

Phase 0 exits read-only. Phase 1 may begin only after approval to mutate the existing Figma file.
