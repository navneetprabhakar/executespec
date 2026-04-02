# frontend/ — React Dashboard

React 18 + Vite 5 + Tailwind CSS 3.4 + TypeScript 5.3

## Structure
- `src/pages/` — Route pages (Dashboard, RunDetail, SharedReport, Admin, Billing, etc.)
- `src/components/runs/` — Run-related components (RunDetail, RunCard, TaskCard, NewRunModal, etc.)
- `src/components/pricing/` — PricingTable with model role display
- `src/hooks/useSse.ts` — SSE event stream hook (generic handler, auto-parses all event types)
- `src/lib/api.ts` — API client (axios-based, JWT auth)
- `src/lib/store.ts` — Zustand state store

## Key Components
- `RunDetail.tsx` — Main run view with Vibe View toggle. Shows gate banners, task list, review, report, cost estimate.
- `RunDetailTabs.tsx` — Tabbed detail view. Overview tab shows GitHub PR link (`githubPrUrl`), per-copilot-run token breakdown. Graph tab uses `TaskGraphGroups` to split copilot/review nodes into separate collapsible sections.
- `RunTimeline.tsx` — Pipeline status bar. `mapStatusToPhase()` maps all statuses including COMPILING, MERGING, AWAITING_MERGE.
- `TaskGraph.tsx` — Task dependency graph. Accepts optional `nodes` prop for subgraph rendering. Strips source prefixes via `displayTaskId()`. Recomputes levels via Kahn's algorithm for subgraphs.
- `NewRunModal.tsx` — Run submission form with AI provider selection, language validation hint.
- `ValidationReportPanel.tsx` — Collapsible validation report display.
- `RunConversation.tsx` — Chat copilot interface.
- `PreviewTab.tsx` — Preview with 5-minute countdown timer (auto-starts after COMPLETE).
- `VibeView.tsx` — Simplified progress view (mission control UX) for non-technical users.

## Patterns
- SSE events flow through generic `onmessage` handler — no per-type wiring needed
- Event type checks use SCREAMING_SNAKE_CASE (matches backend)
- Review display uses `recommendationCompat ?? recommendation` for V2 compat
- Toast notifications for SSE events (compaction, model escalation)
- Pagination: run list uses `PagedResponse<RunSummary>` with page/size/totalElements
- Task graph groups: nodes grouped by `_cop{N}_`/`_rev{N}_` prefix into separate collapsible sections
- SSE refetch triggers include COMPILING and MERGING statuses (useSse.ts)
- RunDetail API includes `githubPrUrl` field — displayed as clickable link in overview tab

## Build
```bash
npm --prefix frontend run build
npm --prefix frontend run dev  # dev server on :5173
```
