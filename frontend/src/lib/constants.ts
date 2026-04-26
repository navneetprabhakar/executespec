export const STATUS_COLORS: Record<string, { dot: string; text: string }> = {
  // Gray — inactive
  QUEUED:                 { dot: 'bg-status-queued',    text: 'text-muted' },
  PAUSED:                { dot: 'bg-status-queued',    text: 'text-muted' },
  CANCELLED:             { dot: 'bg-status-queued',    text: 'text-muted' },
  PENDING:               { dot: 'bg-status-queued',    text: 'text-muted' },
  LOCKED:                { dot: 'bg-status-queued',    text: 'text-muted' },
  // Blue — executing
  WORKING:               { dot: 'bg-status-executing', text: 'text-status-executing' },
  EXECUTING:             { dot: 'bg-status-executing', text: 'text-status-executing' },
  // Purple — thinking/review (PLANNING family)
  PLANNING:              { dot: 'bg-status-thinking',  text: 'text-status-thinking' },
  REVIEWING:             { dot: 'bg-status-thinking',  text: 'text-status-thinking' },
  // ExecutionRun-only statuses (project Launches) — same family as Planning
  SETUP:                 { dot: 'bg-status-thinking',  text: 'text-status-thinking' },
  ANALYSING:             { dot: 'bg-status-thinking',  text: 'text-status-thinking' },
  ARCH_REVIEW:           { dot: 'bg-status-waiting',   text: 'text-status-waiting' },
  // Amber — waiting (PipelineRun family)
  AWAITING_APPROVAL:     { dot: 'bg-status-waiting',   text: 'text-status-waiting' },
  AWAITING_CLARIFICATION:{ dot: 'bg-status-waiting',   text: 'text-status-waiting' },
  // Green — success
  COMPLETE:              { dot: 'bg-status-success',   text: 'text-status-success' },
  // Orange — partial
  PARTIAL:               { dot: 'bg-status-partial',   text: 'text-status-partial' },
  // Red — failure
  FAILED:                { dot: 'bg-status-error',     text: 'text-status-error' },
  STUCK:                 { dot: 'bg-status-error',     text: 'text-status-error' },
  // Pink — blocked
  BLOCKED:               { dot: 'bg-status-blocked',   text: 'text-status-blocked' },
  // Batch
  QUEUED_BATCH:          { dot: 'bg-status-waiting',   text: 'text-status-waiting' },
  IN_PROGRESS_BATCH:     { dot: 'bg-status-executing', text: 'text-status-executing' },
  BATCH_COMPLETE:        { dot: 'bg-status-success',   text: 'text-status-success' },
};

// V1 dead enums removed (COMPILING / MERGING / AWAITING_MERGE — V2 backend never emits them)
// *_BATCH retained — V2 batch path is on the roadmap (see docs/plans/v2-batch-execution-plan.md)
export const ACTIVE_STATUSES = new Set([
  'PLANNING', 'WORKING', 'EXECUTING', 'STUCK',
  'SETUP', 'ANALYSING', 'REVIEWING',
  'QUEUED_BATCH', 'IN_PROGRESS_BATCH',
]);
export const GATE_STATUSES   = new Set([
  'AWAITING_APPROVAL', 'AWAITING_CLARIFICATION', 'ARCH_REVIEW',
]);

export const PIPELINE_STEPS = [
  'QUEUED', 'PLANNING', 'AWAITING_APPROVAL', 'WORKING',
  'REVIEWING', 'COMPLETE',
];

export const NAV_ITEMS: {
  path: string;
  label: string;
  icon: string;
  adminOnly: boolean;
  superAdminOnly?: boolean;
  requiredPermission?: string;
}[] = [
  // Phase 1.1 #16 — "Mission" as primary noun in user-visible copy.
  // URL paths + DTO keys keep "run" — copy-only rename per the plan.
  { path: '/',             label: 'Missions',     icon: 'LayoutDashboard', adminOnly: false },
  // v2.5.14 Phase 3.1 — exec summary for Business Owner persona.
  // Gated behind canViewCostBreakdown (PRO/MAX/ENTERPRISE) — FREE can't see
  // the spend aggregate because that's a paid-tier analytics feature.
  { path: '/exec',         label: 'Exec summary', icon: 'TrendingUp',      adminOnly: false, requiredPermission: 'canViewCostBreakdown' },
  { path: '/dashboard',    label: 'Dashboard',    icon: 'BarChart3',       adminOnly: true  },
  { path: '/pricing',      label: 'Pricing',      icon: 'DollarSign',     adminOnly: false, requiredPermission: 'canViewPricing' },
  { path: '/integrations', label: 'Integrations', icon: 'Plug',           adminOnly: false, requiredPermission: 'canManageIntegrations' },
  { path: '/invoices',     label: 'Invoices',     icon: 'Receipt',        adminOnly: false, requiredPermission: 'canViewInvoices' },
  { path: '/accounting',   label: 'Accounting',   icon: 'Calculator',     adminOnly: false, requiredPermission: 'canViewAccounting' },
  { path: '/audit',        label: 'Audit',        icon: 'ClipboardList',  adminOnly: false, requiredPermission: 'canViewAudit' },
  { path: '/deployments', label: 'Deploy',       icon: 'Rocket',         adminOnly: false, superAdminOnly: true },
  { path: '/admin/analytics', label: 'Analytics',  icon: 'BarChart3',      adminOnly: true  },
  { path: '/settings',     label: 'Settings',     icon: 'Settings',       adminOnly: false },
];

/**
 * v2.5.13 — harness-v2-remaining-work Gap 3 frontend sweep.
 *
 * Endpoints that the V2 backend explicitly returns {@code 410 Gone} for. The
 * underlying UI surfaces live on for the moment (so a future V2 redesign can
 * re-enable them incrementally), but they are hidden today so users don't
 * click buttons that fail silently.
 *
 * Flip individual flags to {@code true} as V2 re-implements each path.
 */
export const V2_LEGACY_UI = {
  // feasibility dropped 2026-04-18 — component + API client deleted; endpoint stays permanent-410.
  runConversation:      true,   // POST/GET /api/runs/{id}/chat wired to V2ChatService (2026-04-19). /iterate + /apply still 410.
  editRunPlan:          false,  // POST /api/runs/{id}/edit-plan — deferred
  applyTestFixes:       false,  // POST /api/runs/{id}/apply-test-fixes — deferred (wire with V2 test-gen)
  implementSuggestions: false,  // POST /api/runs/{id}/implement-suggestions — deferred
  rework:               false,  // POST /api/runs/{id}/rework — deferred
  mergeApproval:        false,  // POST /api/runs/{id}/merge + /reopen-merge — permanent 410 (V2 auto-merges)
} as const;

export const ROUTE_PATHS = {
  runs:         '/',
  dashboard:    '/dashboard',
  pricing:      '/pricing',
  integrations: '/integrations',
  invoices:     '/invoices',
  accounting:   '/accounting',
  audit:        '/audit',
  deployments:  '/deployments',
  settings:     '/settings',
};
