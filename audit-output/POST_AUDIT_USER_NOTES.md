# Post-audit notes — captured during audit, deferred per user instruction

**These are the user's own UX-mapping observations sent during the audit session.**
They are NOT audit findings; they are user directives to be acted on AFTER the audit
ships. Captured here verbatim so they are not lost between sessions.

## Note 1 — IA / navigation issues

> "the UI mapping is messed up, there is no way to go to Runs, add runs in the
>  landing page chat, and remove the side menu, move total number of run on top
>  for FREE/PRO users, no bar for TEAM/ENTERPRISE user"

Decomposed:
- **IA-Q1:** Where does "Runs" navigation live in the v3 shell? Code-trace
  confirms the v3 top-bar (V3TopBar.tsx:46-54) exposes only Logo · ProjectSwitcher ·
  Library · Migrations · Avatar. No Runs link. Existing runs only reachable via
  legacy v2 `/runs` (URL bar) or per-project `/projects/:slug/runs/:runShort`
  (deep-link). Reflected in §3 [F-04] of AUDIT_REPORT.md.
- **IA-Q2:** Should the v3 chat composer (Composer.tsx:104-167) surface a
  shortcut to "submit a run / view existing runs" alongside the textarea?
  Currently it's textarea-only; submission happens implicitly via Enter or
  hidden submit button. Reflected in §8 [Agentic UX gap #2].
- **IA-Q3:** Should the side menu (v2 AppLayout `Sidebar.tsx`) be removed
  entirely once the v3 shell stabilises? Today both ship and which one renders
  depends on the route — confusing because some routes (`/billing`, `/settings`,
  `/integrations`, `/pricing`) cohabit at the same URL with different shells.
- **IA-Q4:** Quota chip placement: should the run-quota chip move to the top
  nav for FREE/PRO and be hidden entirely for TEAM/ENTERPRISE (since they have
  unlimited runs)? Current state: the `UsageBadge` (Sidebar.tsx footer) shows
  for all tiers including TEAM/ENTERPRISE which is noise.

## How to fold these in to a follow-up working session

After AUDIT_REPORT.md ships and the human review pass is done, treat these as
"design tasks 1–4" to scope alongside the §3 top-15 findings. They likely
collapse into a single PR titled "v3 shell IA cleanup" but the four sub-tasks
above are the agreed scope.
