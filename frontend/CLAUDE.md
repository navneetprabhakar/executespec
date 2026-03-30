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
- `NewRunModal.tsx` — Run submission form with AI provider selection, language validation hint.
- `ValidationReportPanel.tsx` — Collapsible validation report display.
- `RunConversation.tsx` — Chat copilot interface.
- `PreviewTab.tsx` — Preview with 10-minute countdown timer (auto-starts after COMPLETE).
- `VibeView.tsx` — Simplified progress view (mission control UX) for non-technical users.

## Patterns
- SSE events flow through generic `onmessage` handler — no per-type wiring needed
- Event type checks use SCREAMING_SNAKE_CASE (matches backend)
- Review display uses `recommendationCompat ?? recommendation` for V2 compat
- Toast notifications for SSE events (compaction, model escalation)
- Pagination: run list uses `PagedResponse<RunSummary>` with page/size/totalElements

## Build
```bash
npm --prefix frontend run build
npm --prefix frontend run dev  # dev server on :5173
```
