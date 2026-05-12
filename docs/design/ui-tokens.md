# UI Design Tokens & Color Semantics

_Created: 2026-04-22 · Lives alongside the code in `frontend/src/tokens.css`_
_Last reviewed: 2026-05-12 (1.0.0-M1) — palette + 4-band score colours unchanged; JetBrainsMono variable font unified across login + dashboard + project surfaces (PR #108 2026-05-11)._

Single source of truth for why every color on the ExecuteSpec app looks the way it does. **If you're about to use a raw hex in a component, stop — add a token instead or reuse one below.**

## Core principle — color as signal, not decoration

Every color in the app carries meaning. A green chip means "this passed". An amber chip means "this needs attention but the run is usable". A red chip means "this failed — code may not run". Mixing these up causes the "everything is on fire" feeling even when the app is fine.

## Surface ladder (dark mode default, mirrored in light mode)

```
--geist-bg              page background
--geist-bg-secondary    elevated background (modals, fixed nav)
--gray-100              card background (primary container)
--gray-200              card background (hover / alt row)
--gray-300              input background, progress track
--gray-400              divider between cards
--gray-500 → -1000      ascending text contrast (-1000 = text-primary)

--border-default        1px border on cards and inputs
--border-hover          hover state border

--text-primary          titles, numbers, critical labels
--text-secondary        body copy, subtitles
--text-muted            metadata, timestamps, secondary annotations
```

## Semantic status palette

```
--status-success        #10B981    run passed · reviewer CLEAN · tests 100%
--status-executing      #5B8DEF    run working · task running · progressive
--status-thinking       #A78BFA    run planning · reviewing · reasoning
--status-waiting        #F59E0B    gate open · awaiting approval · needs review
--status-partial        #F5A524    run complete with warnings (NOT a failure)
--status-error          #F26D6D    run failed · build broken · tests fail
--status-blocked        #EC4899    blocked on external dependency
--status-queued         #66707D    queued · paused · cancelled · inactive
```

**Key distinctions:**

- `--status-waiting` vs `--status-partial`: `waiting` is a *future* state (we're paused expecting input). `partial` is a *terminal* state (we shipped but with caveats). Both are amber; don't collapse them.
- `--status-error` vs `--status-partial`: `error` means the code doesn't work. `partial` means the code works but something wasn't ideal (smoke-test flaked, a reviewer pass was interrupted, etc.). **Never use `--status-error` for PARTIAL runs** — that's how we get the fire-alarm look.

## Score-graduation palette (4-band)

For 0-100 scores — `ScoreRing`, `ScoreCard` dimension bars, validation-report hero:

```
>= 85     --status-success       excellent / clean
70 - 84   --accent-blue           solid / expected
50 - 69   --status-waiting        attention needed
 < 50     --status-error          poor / action required
```

Binary green/red on an 80-threshold was too harsh — borderline scores (70-79) read as failures when they're actually "good enough, ship it". The 4-band graduation is the rule **everywhere** we show a 0-100 number:
- `ScoreRing` default palette
- `ScoreCard.barColor()` / `.textColor()`
- `SignalBreakdown.scoreTone()` (slight variant: green ≥ 85, amber 70-84, red < 70)

## Accent palette

```
--accent-blue          primary CTAs, links, score mid-band
--accent-blue-hover    hover state for accent-blue
--accent-harness       planner/harness entities (purple)
--accent-planner       teal — contrast with harness
--brand-grad           logo + primary CTA gradient (blue → teal)
```

## Radius scale

```
--radius-sm   4px    chips, small buttons, tight input fields
--radius-md   6px    default cards, containers, inputs
--radius-lg   8px    feature cards, hero elements
--radius-xl  12px    browser-chrome frames, modal wrappers
--radius-2xl 16px    tour panels, marketing-grade surfaces
--radius-full        pills, chips that should be fully rounded
```

## Spacing scale (rem)

All spacing uses the `--space-N` tokens, never raw `px`. Tailwind `p-3` = `var(--space-3)` etc.

## Tailwind class → token mapping

These Tailwind utilities resolve to our tokens via the `tailwind.config.js` theme extension:

```
text-primary        var(--text-primary)
text-secondary      var(--text-secondary)
text-muted          var(--text-muted)

bg-status-success   var(--status-success)
bg-status-error     var(--status-error)
bg-status-waiting   var(--status-waiting)
bg-status-partial   var(--status-partial)
bg-status-queued    var(--status-queued)
bg-status-thinking  var(--status-thinking)
bg-status-executing var(--status-executing)

text-status-success … etc (same rule, text variant)
border-status-success … etc
```

For opacity variants, use Tailwind modifiers: `bg-status-success/10`, `border-status-success/30`.

## Common patterns (copy these, don't invent)

### Success chip
```
border-status-success/30 bg-status-success/10 text-status-success
```

### Warning / PARTIAL chip
```
border-status-partial/30 bg-status-partial/10 text-status-partial
```

### Error chip
```
border-status-error/30 bg-status-error/10 text-status-error
```

### Neutral / muted chip
```
border-[var(--border-default)] bg-[var(--gray-200)] text-muted
```

### 0-100 score display (use the ScoreRing component)
```tsx
import ScoreRing from '@/components/runs/ScoreRing';
<ScoreRing score={report.verifiedScore} label="verified / 100" size={132} />
```

### Dimension bar (use ScoreCard or copy its Row pattern)
```tsx
<div className="flex items-center gap-3 text-[11px]">
  <div className="w-28 text-secondary truncate">{label}</div>
  <div className="flex-1 h-2.5 rounded-full bg-[var(--gray-300)] overflow-hidden">
    <div className={`h-full rounded-full ${barColor(value)}`} style={{ width: `${value}%` }} />
  </div>
  <div className={`w-10 text-right font-mono ${textColor(value)}`}>{value}</div>
</div>
```

## Rules — MUST follow

1. **No raw hex in `.tsx` / `.css` files.** If you need a color, add a token to `tokens.css` or reuse one. Exception: recharts / canvas-rendered graphics that can't read CSS vars — use `resolveVar()` helper (see `TechDebtPanel.tsx`).
2. **PARTIAL is never red.** PARTIAL runs shipped; use `--status-partial` (amber) not `--status-error`. See the revert of tour-panel swap 2026-04-22 for why this matters.
3. **Borderline scores (70-84) are blue/amber, not red.** Use the 4-band graduation above for every 0-100 value.
4. **Muted text for secondary info, primary for the thing being measured.** The number is always `text-primary`; the "/100" suffix is `text-muted`. This gives every metric card the same visual rhythm.
5. **Chip pattern is always the trio: border, bg, text.** All three at matching semantic intensity. Never `border-status-success/30 bg-status-error/10` — that's chaos.

## Rules — MUST NOT

- ❌ Raw Tailwind colors in components (`bg-red-500`, `text-emerald-600`, etc.)
- ❌ Red for PARTIAL, FIXED_ISSUES, or any non-failure terminal state
- ❌ Hard binary thresholds (pass/fail) for graduated metrics — use the 4-band palette
- ❌ `style={{ color: '#...' }}` inline unless it's a recharts chart cell (then use `resolveVar`)

## Process for adding a new color

1. Is an existing token semantically right? **Use it.**
2. Is the need new (e.g., "analytics blue" that's distinct from accent-blue)? Add a token to `tokens.css` under the appropriate section.
3. Add its Tailwind class mapping in `tailwind.config.js`.
4. Document it in the relevant section above.

## Related files

- `frontend/src/tokens.css` — token definitions (dark + light theme)
- `frontend/tailwind.config.js` — Tailwind class mappings
- `frontend/src/components/runs/ScoreRing.tsx` — canonical usage of graduated palette
- `frontend/src/components/runs/ScoreCard.tsx` — canonical usage of dimension bars
- `frontend/src/components/runs/SignalBreakdown.tsx` — canonical usage of semantic chips
