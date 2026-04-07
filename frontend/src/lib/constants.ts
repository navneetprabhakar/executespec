export const STATUS_COLORS: Record<string, { dot: string; text: string }> = {
  // Gray — inactive
  QUEUED:                 { dot: 'bg-status-queued',    text: 'text-muted' },
  PAUSED:                { dot: 'bg-status-queued',    text: 'text-muted' },
  CANCELLED:             { dot: 'bg-status-queued',    text: 'text-muted' },
  PENDING:               { dot: 'bg-status-queued',    text: 'text-muted' },
  LOCKED:                { dot: 'bg-status-queued',    text: 'text-muted' },
  // Blue — executing
  WORKING:               { dot: 'bg-status-executing', text: 'text-status-executing' },
  COMPILING:             { dot: 'bg-status-executing', text: 'text-status-executing' },
  MERGING:               { dot: 'bg-status-executing', text: 'text-status-executing' },
  // Purple — thinking/review
  PLANNING:              { dot: 'bg-status-thinking',  text: 'text-status-thinking' },
  REVIEWING:             { dot: 'bg-status-thinking',  text: 'text-status-thinking' },
  // Amber — waiting
  AWAITING_APPROVAL:     { dot: 'bg-status-waiting',   text: 'text-status-waiting' },
  AWAITING_CLARIFICATION:{ dot: 'bg-status-waiting',   text: 'text-status-waiting' },
  AWAITING_MERGE:        { dot: 'bg-status-waiting',   text: 'text-status-waiting' },
  // Green — success
  COMPLETE:              { dot: 'bg-status-success',   text: 'text-status-success' },
  // Orange — partial
  PARTIAL:               { dot: 'bg-status-partial',   text: 'text-status-partial' },
  // Red — failure
  FAILED:                { dot: 'bg-status-error',     text: 'text-status-error' },
  STUCK:                 { dot: 'bg-status-error',     text: 'text-status-error' },
  // Pink — blocked
  BLOCKED:               { dot: 'bg-status-blocked',   text: 'text-status-blocked' },
};

export const ACTIVE_STATUSES = new Set(['PLANNING', 'WORKING', 'STUCK', 'COMPILING']);
export const GATE_STATUSES   = new Set(['AWAITING_APPROVAL', 'AWAITING_MERGE', 'AWAITING_CLARIFICATION']);

export const PIPELINE_STEPS = [
  'QUEUED', 'PLANNING', 'AWAITING_APPROVAL', 'WORKING',
  'COMPILING', 'COMPLETE',
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
