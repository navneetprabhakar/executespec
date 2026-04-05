export const STATUS_COLORS: Record<string, { dot: string; text: string }> = {
  QUEUED:                 { dot: 'bg-status-queued',   text: 'text-muted' },
  PLANNING:              { dot: 'bg-status-building',  text: 'text-status-building' },
  AWAITING_CLARIFICATION:{ dot: 'bg-status-building',  text: 'text-status-building' },
  AWAITING_APPROVAL:     { dot: 'bg-status-building',  text: 'text-status-building' },
  WORKING:               { dot: 'bg-status-building',  text: 'text-status-building' },
  REVIEWING:             { dot: 'bg-status-building',  text: 'text-status-building' },
  AWAITING_MERGE:        { dot: 'bg-status-building',  text: 'text-status-building' },
  COMPILING:             { dot: 'bg-status-building',  text: 'text-status-building' },
  MERGING:               { dot: 'bg-status-building',  text: 'text-status-building' },
  COMPLETE:              { dot: 'bg-status-success',   text: 'text-status-success' },
  FAILED:                { dot: 'bg-status-error',     text: 'text-status-error' },
  PAUSED:                { dot: 'bg-status-queued',    text: 'text-muted' },
  CANCELLED:             { dot: 'bg-status-queued',    text: 'text-muted' },
  PARTIAL:               { dot: 'bg-status-building',  text: 'text-status-building' },
  BLOCKED:               { dot: 'bg-status-building',  text: 'text-status-building' },
  STUCK:                 { dot: 'bg-status-error',     text: 'text-status-error' },
  // Task-level statuses
  PENDING:               { dot: 'bg-status-queued',    text: 'text-muted' },
  LOCKED:                { dot: 'bg-status-queued',    text: 'text-muted' },
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
