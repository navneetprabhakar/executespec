# Q-04 — Unified Composer Picker (Library + Migrations + Templates)

> **Audit branch:** `audit/v2-5-11-ux-product`
> **Author:** Claude (audit Sprint 5 spec, follow-up to FINAL_REPORT.md)
> **Date:** 2026-05-01
> **Status:** Spec — not yet implemented. Sized as a single Sprint 5 slice.
> **User decision:** Q-04 confirmed — "ok i agree" with the recommendation to unify discovery while keeping the three backend flows distinct.

---

## TL;DR

Three "starting-point" features ship today (Readymades / Library, Migrations, Spec Templates), each with its own discovery surface. Discoverability is one job; the backends and action verbs differ. The unified composer picker brings discovery under one inline affordance — a `+ Start from…` chip next to the textarea — and branches by source type into the existing per-feature flows. **No new backend endpoints.** Just a single new component (`ComposerSourcePicker.tsx`) and three thin adapters.

| Source | Backend (today) | Action verb | Existing UI |
|--------|----------------|-------------|-------------|
| **Library** (Readymades) | `GET /api/readymades` → `ReadymadeListItem[]` | "Use readymade" → variant resolution → spec template fills the textarea + slots open in a slot panel | [V3Library.tsx](frontend/src/v3/pages/V3Library.tsx) |
| **Migrations** | `GET /api/migrations` → `MigrationListItem[]` | "Run migration" → dry-run preview → apply (separate from a regular run) | [V3Migrations.tsx](frontend/src/v3/pages/V3Migrations.tsx) |
| **Templates** | `GET /api/projects/{id}/templates` → `SpecTemplate[]` | "Use template" → fills the textarea verbatim | [TemplatePicker.tsx](frontend/src/components/execution/TemplatePicker.tsx) |

The unified picker is a **discovery affordance**, not a backend rewrite. Each source still routes through its own action handler — readymades open the slot panel, migrations open the dry-run modal, templates fill the textarea. The picker just makes "where do I start from?" answerable in one click instead of three pages.

---

## 1. The problem (audit context)

From [BROWSER_VALIDATION_REPORT.md](audit-output/BROWSER_VALIDATION_REPORT.md) Q-04:

> The "Library", "Migrations", and "Templates" features each let me start a new run from a pre-built source, but each is buried in a separate place. I had to know all three existed and navigate to them individually.

Three observations:
1. **Readymades** are a paid-tier upsell (per `ReadymadeSolutionSummary.tier`) — the library page exists but isn't surfaced from the composer where new-run intent lives.
2. **Migrations** are a separate flow (dry-run → apply) that doesn't produce a regular run; it's actually closer to "run this checkpoint" than "start a new project". Easy to miss for users not on a migration path today.
3. **Templates** are project-scoped (created from inside a project) and org-scoped (admin-curated). The TemplatePicker is wired into the v2 ExecutionSetup but **not** into the v3 Composer where most new runs happen now.

The audit's user said "this should all just be there when I'm typing a spec." That's the right read.

---

## 2. UX shape

### 2.1 Composer affordance

A new chip/button to the **left** of the existing `Plan & review` mode chip in [Composer.tsx:299](frontend/src/v3/components/Composer.tsx:299):

```
┌──────────────────────────────────────────────────────────────┐
│  What do you want to build?                                   │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  [textarea — 2 rows when populated, 4 when empty]      │  │
│  │                                                         │  │
│  │                                                         │  │
│  └────────────────────────────────────────────────────────┘  │
│  [+ Start from…]  [Plan & review ▾]  [▾ Advanced]   [Submit] │
└──────────────────────────────────────────────────────────────┘
```

The chip uses the same visual language as the existing `Plan & review` / `Advanced` chips — minimal, doesn't scream for attention. On click, opens an **inline overlay panel** below the composer (not a modal — modals fragment the chat-style flow on this surface).

### 2.2 Picker panel anatomy

```
┌──────────────────────────────────────────────────────────────┐
│  Start from…                                            [×]  │
│  ┌────────────────────┐ ┌────────────────────┐ ┌──────────┐  │
│  │ 📚 Library         │ │ 🔄 Migrations     │ │ 📝 Templ. │  │
│  │ N readymades       │ │ M recipes         │ │ K saved   │  │
│  └────────────────────┘ └────────────────────┘ └──────────┘  │
│  Search: [____________________]                               │
│                                                                │
│  [Selected tab content — 5-row list with names + tags]        │
│                                                                │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ Stripe checkout endpoint                                 │  │
│  │ Library · Integration · 8-12 credits · Free              │  │
│  │ [Use readymade →]                                        │  │
│  └─────────────────────────────────────────────────────────┘  │
│  ...                                                           │
└──────────────────────────────────────────────────────────────┘
```

**Three top-level tabs** correspond exactly to the three source types. Counts come from the existing list endpoints. Search is a cross-tab fuzzy match (case-insensitive `contains` on `name + tagline + tags` for readymades, `title + summary` for migrations, `name` for templates).

When `templates` count is 0 (typical for a fresh project), that tab still shows but renders an inline "Save your first template from a successful run" CTA instead of an empty list. Same pattern as [BROWSER_VALIDATION_REPORT.md] empty-state hero recommendations.

### 2.3 Per-source action verbs

| Source | Click action |
|--------|-------------|
| **Readymade** | Resolves the variant (existing `POST /api/readymades/{slug}/resolve-variant`) → if resolution succeeds, fills the textarea with `variant.specTemplate` + opens the slot panel inline below the composer (slot inputs render as fields; user fills, hits Submit) → composer dispatches as a normal run with the slot values appended. If resolution returns `NO_VARIANT_FOR_STACK` / `STACK_NOT_DETECTED` / `REPO_NOT_LINKED`, surface a 1-line inline note (`Pick a repo first` / `Stack not detected — link a repo`) instead of erroring. |
| **Migration** | **Does NOT fill the textarea.** Migrations have their own dispatch path — opens the existing dry-run modal at `V3MigrationDetail.tsx`. Picker closes; the modal takes over. After dry-run + apply succeed, picker doesn't reopen — the user is now on the migration's result page. |
| **Template** | Fills the textarea with `template.specText` verbatim. Picker closes. User can edit before Submit. |

The action verbs are intentionally distinct: `Use readymade →`, `Run migration →`, `Use template →`. Same color (subtle), same size, but different verbs telegraph what's about to happen. No surprises.

### 2.4 Empty states

| Source | When zero items | Render |
|--------|-----------------|--------|
| Library | Free tier with no readymades unlocked | "Upgrade to Pro to unlock 30+ readymades" with `View library →` CTA |
| Migrations | No migrations available for the project's current schema | "No applicable migrations" (silent — most users will never see one) |
| Templates | Project has no saved templates | "Save your first template from a completed run" + how-to link |

---

## 3. Component structure

```
v3/components/
├── Composer.tsx                         (existing — adds 1 chip + state)
└── ComposerSourcePicker/                (NEW)
    ├── ComposerSourcePicker.tsx         (NEW — orchestrator + tab layout)
    ├── LibraryTab.tsx                   (NEW — readymade list + Use action)
    ├── MigrationsTab.tsx                (NEW — migration list + Run action)
    ├── TemplatesTab.tsx                 (NEW — template list + Use action)
    └── useSourcePickerData.ts           (NEW — parallel-fetches the 3 list APIs, dedupes search)
```

`useSourcePickerData` is the only shared piece — fetches all three list endpoints in parallel on first open (cached for the session via the existing `useV3ProjectStore` pattern), exposes a single `search(query)` that filters across sources.

The `LibraryTab` / `MigrationsTab` / `TemplatesTab` components are intentionally separate — each owns the specific click action for its source. No abstract "SourceTab" base class. Three similar files is better than one premature abstraction; each has its own action verb and its own confirmation flow (slot-filling for readymades is structurally different from textarea-filling for templates).

---

## 4. Backend changes

**None for the picker itself.** All three list APIs already exist:

- `GET /api/readymades?surface=BUILD` → `ReadymadeListItem[]` (already paginated; first page is enough for picker — load more on scroll if needed later)
- `GET /api/migrations?status=AVAILABLE` → `MigrationListItem[]`
- `GET /api/projects/{id}/templates` → `SpecTemplate[]` (project-scoped; for global, also call `/api/orgs/{orgId}/templates`)

**One small enhancement** that the picker enables: surface the **template author's name** in the picker row. Today `SpecTemplate` has `createdByUserId` but no display name; the picker would benefit from showing "Saved by Alice" alongside "Saved by you". Sized as XS — add a join in `SpecTemplateController.list` or expose a helper endpoint. Not blocking; can ship the picker without it and add the author display in a follow-up.

---

## 5. Telemetry

New events (extend `V3_EVENTS`):

- `composer_source_picker_opened` — `{projectId}`
- `composer_source_picker_searched` — `{query, queryLen}`
- `composer_source_chosen` — `{source: 'library' | 'migrations' | 'templates', itemId, itemName}`
- `composer_source_picker_closed_without_choice` — `{durationMs}`

Goal of telemetry: prove that the picker increases first-run-from-pre-built-source rate. If 3 months in we see it's <10% of run submissions, the surface area isn't pulling its weight and we should consider collapsing it back into Advanced or moving it to the empty-state CTAs only.

---

## 6. Tier gating

| Tier | Library | Migrations | Templates |
|------|---------|------------|-----------|
| **FREE** | Free readymades only (`tier='FREE'` filter on list) | All available | View own only |
| **PRO** (HIDDEN) | Free + Pro readymades | All available | Project + org |
| **TEAM** | All readymades | All available | Project + org |
| **ENTERPRISE** | All readymades | All available + custom org migrations | Project + org |

Existing `ReadymadeSolutionSummary.tier` + `ReadymadeListItem.locked` already encode this. The picker just renders the lock chip (`🔒 Pro`) on locked rows; clicking opens the existing upgrade modal instead of dispatching.

No new backend gating — the existing per-tier filtering on each list endpoint applies.

---

## 7. Rollout plan

| Phase | Days | What | Gate |
|-------|------|------|------|
| **0 — pickerless launch validation** | 0 | Confirm via telemetry that current `composer_sample_chip_clicked` rate (3 hardcoded sample chips at [Composer.tsx:431-457](frontend/src/v3/components/Composer.tsx:431)) is non-trivial. If users click those at all, they'll click a richer source picker. | Read existing PostHog data |
| **1 — single source first** | 1 | Ship `ComposerSourcePicker` with **only the Templates tab** active, the other two tabs disabled with "Coming soon" copy. Templates are project-scoped (low risk, no tier-gating drama) and the audience that has saved templates is exactly the audience that will use the picker. | Manual review + telemetry stub |
| **2 — Library tab** | 1 | Enable the Library tab. Free-tier upgrade modal already exists; just wire `locked` rows. | Eat-our-own-dogfood internal session before enabling for everyone |
| **3 — Migrations tab** | 0.5 | Enable the Migrations tab. The dry-run modal exists; just route from picker click to `/v3/migrations/<slug>` instead of opening the inline action. | Manual + telemetry |
| **4 — telemetry review** | (3 month delay) | Read `composer_source_chosen` rate per source. If any source is <2% of submissions, consider hiding that tab for non-eligible users (e.g. hide Migrations for projects with no applicable recipes). | Quarterly review |

Total: **~2.5 days** of frontend work.

### Risk

- **Library tab too heavy** — readymades have variants + slots + dependencies. The picker shouldn't try to render that complexity inline. Resolution: when user picks a readymade, the picker closes and the **slot panel** opens inline under the composer (separate component, not part of the picker). Keeps picker simple.
- **Migrations are confusing** — they're not "starts" exactly, they're "transitions". If users pick a migration expecting a normal run, they'll be confused by the dry-run flow. Resolution: verb says `Run migration →` (not `Start from migration`); the modal is unmistakable.
- **Templates without an author** — anonymous "Saved 3 weeks ago" rows feel low-trust. Mitigation: see §4 — surface author name as a follow-up enhancement; ship without it on day 1 (label as "Saved by you" / "Saved by org").

---

## 8. Out of scope

- **Cross-source recommendation** ("based on your spec, you might want this readymade") — needs an LLM-call surface. Worth pursuing in a future audit but not Sprint 5.
- **Pin / favorite within the picker** — once we see the data on which sources users return to, we can add a "Recently used" tab. Premature today.
- **Template authoring from picker** — "Save current draft as template" is already in the v2 ExecutionSetup flow; piping it to the v3 Composer is a separate slice.
- **Sharing readymades + templates across orgs** (community marketplace) — backend has the `source: 'COMMUNITY'` flag on readymades but no marketplace surface today. Different product.

---

## 9. Recommendation

Ship Phase 1 (Templates-only) within the next 2-3 weeks as a Sprint 5 slice. Validate the picker shape with a single source before broadening. The Library + Migrations tabs are 1 day each on top — quick follow-ups once the picker shape is proven.

Do **not** wait for §4's author-display enhancement before Phase 1 — "Saved by you" is fine as a v1 label.

Do **not** ship a modal version. The chat-style v3 surface treats modals as friction; an inline overlay is the right pattern.

---

*End of spec.*
