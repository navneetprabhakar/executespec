# IDE chrome — aesthetic UX review (2026-05-22)

> Scope: the IDE workspace is now the **only** chrome (Signal retired as a
> user-facing option). This review tours the core surfaces from an aesthetic /
> "does it read like a real IDE" lens. Verified live as `admin@executespec.ai`
> (SUPER_ADMIN, ENTERPRISE) on localhost at 1280px, light + dark.

## Verdict

The chrome now reads as an **authentic VS Code-style workspace**, not a reskinned
dashboard. The two founder complaints from the prior round are resolved:

- **File icons** — the run-result Code tab renders a real file tree (folder
  icons + chevrons, per-language file icons sharing one glyph per type like VS
  Code) feeding the diff viewer. No more generic tinted pages.
- **Login** — the auth brand panel is a VS Code editor mock (`spec.md` tab,
  line-numbered spec → `plan · code · test · PR ✓`, blue status bar) that
  previews the workspace the user is signing into.

Everything below is polish, not blockers.

## Surface-by-surface

| Surface | Verdict | Notes |
| --- | --- | --- |
| **Login / auth** | Strong | 2-col, IDE editor mock on the right; form clean; SSO + email/password; mobile collapses to the form alone. |
| **Workspace / Explorer** | Strong | Activity bar + Explorer (Open / Recent runs / Projects / System) with status dots + folder/file icons; ⌘K palette; consistent status bar (`IDE · Beta`, theme toggle — no Signal toggle). |
| **Run result** | Strong | Tabbed (Overview / Code / Tasks / Timeline / Validation / Cost / PR / Tests / Chat); Signals + Run panels; verdict pill + score; Code tab = real tree + diff viewer with Content/Diff/Wrap/Copy. |
| **Compose** | Good | Sample chips, project chips, and a Provider / **Model · Beta** / Mode / Spending-cap control row; the Enterprise model-pin selector renders with its "overrides org & default matrix" hint. |
| **Admin → Ops** | Good (build-verified) | Feature flags relocated under Ops, super-admin inline toggles, collapsible list. |
| **Account** | Good (build-verified) | Collapsible notification prefs + the new Contact/Feedback form. |

## Aesthetic nits / recommendations (prioritized, all minor)

1. **Compose control row** — Provider / Model / Mode / Cap is dense on one line;
   slightly more column spacing + consistent helper-text placement would breathe
   better on mid-width viewports.
2. **Java (and other monogram) file icons** — correct (one glyph per type, VS
   Code behaviour) but the saturated tile reads a touch flat at 15px; a more
   distinct per-language mark would be the last 5% (low priority).
3. **Mobile sweep** — the IDE has a distinct mobile layout (top bar + drawer +
   bottom tabs); spot-check 375×812 for the new surfaces (Contact form, model
   selector, Ops flags) before GA.

(Checked + dismissed: the run-result tab count badges — `.ide-rtab` already sets
`gap: 6px` and the count renders as a padded pill, so the `Code36` look in a
compressed screenshot is a `textContent` artifact, not a real spacing bug.)

## What was NOT re-reviewed here

Project workbench sub-tabs, Deployments, Integrations, and the billing/legal
Signal-fallback pages (still served by Signal pages until ported IDE-native).
These were covered in the prior `ux-assessment-*` docs and are unchanged this
round.
