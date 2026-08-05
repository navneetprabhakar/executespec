# ExecuteSpec Enterprise — Figma Phase 1 Mutation Specification

Status: **Applied and validated in Figma on 2026-07-20**  
Run: `enterprise-closure-v2-2026-07-20`  
Target file: `aq1I0rCCUpfRKe7gyyO41S`

## Purpose

This is the exact idempotent mutation contract for Phase 1. It extends the existing collections; it does not create parallel token systems or alter screens. All new variables are tagged with shared plugin data namespace `dsb`, run ID `enterprise-closure-v2-2026-07-20`, phase `phase1`, and a stable logical key.

## P1.a–P1.e — Variables

### Primitive additions

| Name | Dark/light value purpose | Value | Scope | WEB syntax | Status |
|---|---|---:|---|---|---|
| `approval-deep` | approval action surface | `#08724F` | none | `var(--es-approval)` | planned code token |
| `destructive-deep` | accessible light destructive surface | `#B91C1C` | none | `var(--es-destructive)` | planned code token |
| `stone-18` | disabled/terminal soft fill | `rgba(111,125,125,.18)` | none | `var(--ide-disabled-soft)` | planned code token |
| `yellow-16` | high-impact/attention soft fill | `rgba(245,196,81,.16)` | none | `var(--ide-warning-soft)` | planned code token |
| `red-16` | failure/destructive soft fill | `rgba(255,124,124,.16)` | none | `var(--ide-danger-soft)` | planned code token |
| `light-yellow-14` | light high-impact soft fill | `rgba(184,135,0,.14)` | none | `var(--ide-warning-soft)` | planned code token |
| `light-red-12` | light failure/destructive soft fill | `rgba(185,28,28,.12)` | none | `var(--ide-danger-soft)` | planned code token |
| `black-60` | modal/drawer scrim | `rgba(3,15,15,.60)` | `EFFECT_COLOR` | `var(--ide-scrim)` | planned code token |

Existing palette primitives remain authoritative for normal actions, approval dark text/surface pairing, active/info, success, warning and dark destructive actions.

### Semantic colour additions

All semantic values alias primitives—no raw semantic values.

| Variable | Dark alias | Light alias | Scope | WEB syntax |
|---|---|---|---|---|
| `color/action/normal` | `action-green` | `brand-green` | frame, shape, stroke | `var(--ide-accent)` |
| `color/action/approval` | `approval-deep` | `approval-deep` | frame, shape, stroke | `var(--es-approval)` |
| `color/action/high-impact` | `yellow` | `light-yellow` | frame, shape, stroke | `var(--ide-yellow)` |
| `color/action/destructive` | `red` | `destructive-deep` | frame, shape, stroke | `var(--es-destructive)` |
| `color/action/blocked` | `stone` | `light-muted` | frame, shape, stroke | `var(--ide-fg-muted)` |
| `color/action/disabled-soft` | `stone-18` | `light-inactive` | frame, shape | `var(--ide-disabled-soft)` |
| `color/action/high-impact-soft` | `yellow-16` | `light-yellow-14` | frame, shape | `var(--ide-warning-soft)` |
| `color/action/destructive-soft` | `red-16` | `light-red-12` | frame, shape | `var(--ide-danger-soft)` |
| `color/text/on-approval` | `white` | `white` | text | `var(--es-white)` |
| `color/text/on-high-impact` | `black` | `black` | text | `var(--es-black)` |
| `color/text/on-destructive` | `black` | `pure-white` | text | `var(--es-destructive-fg)` |
| `color/state/active` | `mountain-meadow` | `frog` | shape, text, stroke | `var(--ide-blue)` |
| `color/state/attention` | `yellow` | `light-yellow` | shape, text, stroke | `var(--ide-yellow)` |
| `color/state/blocked` | `red` | `destructive-deep` | shape, text, stroke | `var(--es-destructive)` |
| `color/state/terminal-neutral` | `stone` | `light-muted` | shape, text, stroke | `var(--ide-fg-muted)` |
| `color/bg/denied-redacted` | `input` | `light-inactive` | frame, shape | `var(--ide-denied-redacted)` |
| `color/bg/stale` | `yellow-16` | `light-yellow-14` | frame, shape | `var(--ide-warning-soft)` |
| `color/bg/scrim` | `black-60` | `black-60` | frame, shape | `var(--ide-scrim)` |

`color/action/default` remains as a compatibility alias during migration. New components bind to `color/action/normal`; old instances are migrated before the compatibility variable is considered for retirement.

### Size additions

| Variable | Value | Scope | WEB syntax |
|---|---:|---|---|
| `size/hit-preferred` | 40 | width/height | `var(--hit-preferred)` |
| `size/hit-mobile` | 44 | width/height | `var(--hit-min-mobile)` |
| `size/icon-slot` | 24 | width/height | `var(--icon-slot)` |
| `size/rail-collapsed` | 56 | width/height | `var(--rail-collapsed)` |
| `size/rail-expanded-compact` | 240 | width/height | `var(--rail-expanded-compact)` |
| `size/rail-expanded` | 288 | width/height | `var(--rail-expanded)` |
| `size/inspect-compact` | 360 | width/height | `var(--inspect-compact)` |
| `size/inspect` | 440 | width/height | `var(--inspect-width)` |

Existing 4/8/12/16/20/24/32 spacing and radius tokens remain unchanged.

## P1.f — Styles

### New readable text styles

Existing styles remain intact until screens are migrated, avoiding an uncontrolled global reflow. The old 18px page heading and 15px section heading are renamed with a `Legacy/` prefix only after their instance count reaches zero.

| Style | Font | Size / line height | Use |
|---|---|---:|---|
| `Heading/Page/Large` | JetBrains Mono Bold | 24 / 30 | page title |
| `Heading/Section/Large` | JetBrains Mono Medium | 18 / 24 | section title |
| `Body/Instruction` | JetBrains Mono Regular | 15 / 23 | decisions, warnings, explanations |
| `Body/Navigation` | JetBrains Mono Regular | 13 / 19 | navigation and supporting content |
| `Label/Action` | JetBrains Mono Medium | 13 / 18 | buttons and interactive labels |

### New focus effect styles

| Style | Treatment | Purpose |
|---|---|---|
| `Focus/Normal` | 2px canvas separation + 2px normal action ring | keyboard focus |
| `Focus/HighImpact` | 2px canvas separation + 2px amber ring | consequential focus |
| `Focus/Danger` | 2px canvas separation + 2px destructive ring | destructive focus |

Figma effect styles cannot bind effect colours to local variables reliably in this workflow; the effects use the exact referenced primitive values and are documented as a code-token mapping exception. Component fills, strokes, padding, gaps and radii remain variable-bound.

## Contrast evidence

Ratios were calculated using WCAG relative luminance against the actual Console foregrounds.

| Pair | Ratio | Result |
|---|---:|---|
| dark normal `#00DF82` / product black | 11.01 | AA/AAA body |
| light normal `#03624C` / product white | 6.79 | AA body |
| approval `#08724F` / product white | 5.49 | AA body |
| dark high-impact `#F5C451` / product black | 11.95 | AA/AAA body |
| light high-impact `#B88700` / product black | 6.03 | AA body |
| dark destructive `#FF7C7C` / product black | 7.81 | AA/AAA body |
| current light red `#D14B4B` / product white | 4.02 | **fails AA body** |
| planned light destructive `#B91C1C` / white | 6.47 | AA body |

The deeper light destructive primitive is therefore mandatory for destructive controls; the existing light red may remain for non-body status decoration/text where its use is separately validated.

## Idempotency and rollback

1. Resolve collections by exact ID/name and variables/styles by exact stable name plus shared `dsb` key.
2. Create missing entities only; update only entities tagged by this run.
3. Return every created or mutated ID from each Figma call.
4. Validate counts, scopes, WEB syntax, alias targets and broken aliases immediately after each subsection.
5. On partial failure, remove only entities tagged with this run and phase; never clean by prefix.
6. Persist returned IDs in `dsb-state-enterprise-closure-v2-2026-07-20.json` after every successful call.

## Expected Phase 1 delta

- Collections: remain 3.
- Variables: 71 → 105 (8 primitives + 18 semantic colours + 8 sizes).
- Text styles: 7 → 12.
- Effect styles: 2 → 5.
- Component sets and screens: unchanged in Phase 1.

Applied result: all expected deltas passed. Exact entity IDs and validation evidence are recorded in `15-FIGMA-PHASE-1-FOUNDATION-RESULT.md` and `dsb-state-enterprise-closure-v2-2026-07-20.json`.

## Exit checks

- 105 total local variables, with 42 primitives, 39 semantic colours and 24 size variables.
- Dark/Light aliases resolve with zero broken targets.
- Every semantic/size variable has exact scopes and WEB syntax.
- Primitive scopes are empty except `black-60` as `EFFECT_COLOR`.
- All five new text styles use an available JetBrains Mono font style.
- All three focus styles exist and are visually distinguishable.
- No screen or prototype reaction count changes during Phase 1.
