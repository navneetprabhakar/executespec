# IDE UI rebuild — feature + API audit (2026-05-22)

> Driver: the new IDE chrome (`frontend/src/ide/`) shipped thin surfaces that miss
> features the production **Signal** UI already has. This is the screen-by-screen
> parity map + API-by-screen/access grouping that the rebuild must satisfy.
>
> **Key finding:** Signal does NOT use `components/runs/*` or `components/project/*`
> (those dirs are absent). Feature logic is **inlined into each page** under
> `signal/pages/`. Reusable primitives live in `signal/components/`: `Compose.tsx`
> (`ComposerCard`, `ScoreCard`, `PrCard`, `LiveHero`, `TaskLine`, `StreamCard`),
> `Primitives.tsx` (`Card`,`Row`,`KvList`,`TabsStrip`,`StatusPill`,`Chip`,`Button`),
> `SpecChat.tsx`, `FileTree.tsx`, `CodeView.tsx`, `CostProgressBar.tsx`, `Markdown.tsx`,
> `Modal.tsx`. The IDE rebuild needs equivalent IDE-native primitives + per-page logic.

## IDE status vs Signal (gap summary)

| Screen | IDE today | Missing (must add) |
|--------|-----------|--------------------|
| Home `/home` | recent-runs grid + projects | LiveHero (active run), quick-action cards w/ run-cap, score colour bands |
| Compose `/compose` | textarea+provider+mode+submit | **SpecChat "Build my spec"**, project picker, repo multi-select, cost+cap input, estimate p25/p75 chip, at-cap banner, templates |
| Plan `/plan/:id` | (none — routes to Signal) | whole screen: tasks, classifier band, credits+cap box, **Approve (charges credits)** / Cancel |
| Live `/live/:id` | status poll only | SSE live-log (StreamCard kinds), task lines w/ iter+$, **CostProgressBar**, phase label, cancel modal |
| Result `/result/:id` | files+code+basic info panel | **9 tabs**: Overview(verdict sentence + ScoreCard + measured-only signal breakdown + "what now" rows incl. Try-another-provider/Preview/PDF/Share), Tasks(+retry), Timeline(Gantt), Code(diff toggle ✓), **Cost**(per-phase), **PR**(PrCard + push), **Validation**(issues+checks+PDF), **Tests**(root-cause + apply-fix), **Chat**(iterate-as-new-run) |
| Projects `/projects` | (Signal) | gradient cards: avg-score(recency-weighted) / spend / tech-debt / repos·runs·PRs / **Live/Healthy/New/Archived** pills; Mine/Shared/Archived; create; search |
| Project `/projects/:id` | composer+repos+recent | **ProjectTabs** strip + the 9 sub-tabs (below) |
| Runs `/runs` | flat list | filter strip + split-pane inline detail |
| Account `/account` | profile+toggles+links ✓ | notification-pref switches, plan-limits KvList, GDPR export |
| Billing/Plans/Integrations/Admin/Org | (Signal) | full rebuild each |

### Project sub-tabs (each is its own surface)
- **Pulse** — 4 metrics (avg score / avg run time / spend·30d / PRs-merged·30d) + workspace health rows (SHA drift, webhook, open PRs) + open-PR cards + recent runs.
- **Repos** — per-repo cards (lang, LOC, HEAD/branch, drift, webhook, open PRs, arch status) + actions (view arch / install webhook / sync / unlink) + Link-repo panel.
- **Code** — **6 sub-tabs**: Browse (FileTree+CodeView of live source) · Tech debt (category cards + **Autofix N**) · Code review (severity pills + New scan + report) · Health · Standards (TEAM+) · Docs (gen/regen/download).
- **Runs** — Runs/PRs sub-tabs; filter strip; split-pane inline detail; PR cards w/ CI chip + **Autofix review**; refresh PRs.
- **Chat** — full-height project chat (memory banner, Markdown, compile-to-spec, compact, clear). **TEAM+ gated**.
- **Activity** — filterable feed + CSV export.
- **Members** — member rows (role, EXT badge, remove) + invite (V198-gated) + share-links.
- **Architecture** — **5 tabs**: Current(doc+stale pill+regen) · Versions(compare diff) · Invariants(PASS/FAIL) · ADRs · Contracts(drift).
- **Settings** — Settings / Documents(RAG) / Memory; danger zone archive/delete.

## API by domain + access (show/hide cheat-sheet)

- **Public (render logged-out):** auth entry, `GET /runs/{id}/events` (scoped token), `/reports/shared/*`, `/shared/**`, `/invites/preview`, pricing.
- **Auth + tenant-scoped (AccessGuard — own runs/projects only):** run lifecycle, validation/report, files/code, preview, project workspace, notifications, models, `GET /admin/feature-flags/public`.
- **Tier-gated → upgrade card for FREE:** PR Review + Code Review (PRO+, monthly quota), project chat (`/chat/info` 403), share-links + external invites.
- **ENTERPRISE-only editable:** org model-matrix grid (read-only otherwise); OpenRouter key.
- **ORG_ADMIN / SUPER_ADMIN only:** all `/org/me/settings/**` (ai-keys, key-source, model-matrix, external-sharing, jira, confluence), SSO, github-app, org standards, project create/archive/delete, deployment writes, V198 external-invite create.
- **BILLING_ADMIN / ORG_ADMIN:** org-scoped billing + payment audit.
- **SUPER_ADMIN only (hide entirely otherwise):** all `/api/admin/**` — users/orgs, tiers, feature flags, payment-audit/export, reconcile, swebench/programbench/eval, webhook-events, analytics.

Per-screen API lists captured in the gap table above; full endpoint inventory in `frontend/src/lib/api.ts` + `docs/frontend-api-usage.md` + root `CLAUDE.md` API tables.

## Rebuild plan (prioritized)

**Component kit additions** (IDE-native, in `ide/IdeKit.tsx` + new files): proper **file-type icons** (SVG/coloured glyph set, not text badges), **IdeTabs** (reusable tab strip), **IdeScoreCard** + verdict logic, **IdeKvList**, **IdeSpecChat** (Build-my-spec), **IdePrCard**, **IdeCostBar**, **IdeStream** (live log), **IdeModal/Confirm**, **IdeMarkdown** (reuse `signal/components/Markdown`).

**Phase order (each = real parity, behind the A/B toggle):**
1. **Result** `/result/:id` — 9 tabs (flagship; the screenshot showed it thinnest on features). Folder/file icons fix lands here.
2. **Compose** — SpecChat + repo/project picker + cost-cap + estimate. **Plan** + **Live** (SSE log, cost bar).
3. **Projects list** (health-metric cards) + **Project workbench** Pulse/Repos/Runs.
4. **Project Code** (6 sub-tabs) + **Chat** + **Architecture** (5 tabs) + Members/Activity/Settings.
5. **Billing / Plans / Integrations / Admin / Org** settings surfaces.

Non-rebuilt routes keep falling back to Signal pages inside the IDE shell until their phase lands.
