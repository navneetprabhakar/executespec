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

export const BATCH_PIPELINE_STEPS = [
  'QUEUED', 'PLANNING', 'AWAITING_APPROVAL', 'QUEUED_BATCH',
  'IN_PROGRESS_BATCH', 'BATCH_COMPLETE', 'REVIEWING', 'COMPLETE',
];

export const NAV_ITEMS: {
  path: string;
  label: string;
  icon: string;
  adminOnly: boolean;
  superAdminOnly?: boolean;
  requiredPermission?: string;
}[] = [
  { path: '/',             label: 'Runs',         icon: 'LayoutDashboard', adminOnly: false },
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
