export const STATUS_COLORS: Record<string, { dot: string; text: string; glow: string; bg: string }> = {
  QUEUED:            { dot: 'bg-zinc-500',         text: 'text-zinc-400',      glow: '',                        bg: 'bg-zinc-500/10' },
  PLANNING:          { dot: 'bg-accent-violet',    text: 'text-accent-violet', glow: 'shadow-glow-violet',      bg: 'bg-accent-violet/10' },
  AWAITING_APPROVAL: { dot: 'bg-accent-amber',     text: 'text-accent-amber',  glow: 'shadow-glow-amber',       bg: 'bg-accent-amber/10' },
  WORKING:           { dot: 'bg-accent-cyan',      text: 'text-accent-cyan',   glow: 'shadow-glow-cyan',        bg: 'bg-accent-cyan/10' },
  REVIEWING:         { dot: 'bg-accent-violet',    text: 'text-accent-violet', glow: 'shadow-glow-violet',      bg: 'bg-accent-violet/10' },
  AWAITING_MERGE:    { dot: 'bg-accent-amber',     text: 'text-accent-amber',  glow: 'shadow-glow-amber',       bg: 'bg-accent-amber/10' },
  MERGING:           { dot: 'bg-accent-emerald',   text: 'text-accent-emerald',glow: 'shadow-glow-emerald',     bg: 'bg-accent-emerald/10' },
  COMPLETE:          { dot: 'bg-accent-emerald',   text: 'text-accent-emerald',glow: 'shadow-glow-emerald',     bg: 'bg-accent-emerald/10' },
  FAILED:            { dot: 'bg-accent-rose',      text: 'text-accent-rose',   glow: 'shadow-glow-rose',        bg: 'bg-accent-rose/10' },
  PAUSED:            { dot: 'bg-orange-400',        text: 'text-orange-400',    glow: '',                        bg: 'bg-orange-400/10' },
  CANCELLED:         { dot: 'bg-zinc-600',         text: 'text-zinc-500',      glow: '',                        bg: 'bg-zinc-600/10' },
  PARTIAL:           { dot: 'bg-accent-amber',     text: 'text-accent-amber',  glow: 'shadow-glow-amber',       bg: 'bg-accent-amber/10' },
  BLOCKED:           { dot: 'bg-accent-amber',     text: 'text-accent-amber',  glow: 'shadow-glow-amber',       bg: 'bg-accent-amber/10' },
  STUCK:             { dot: 'bg-accent-rose',      text: 'text-accent-rose',   glow: 'shadow-glow-rose',        bg: 'bg-accent-rose/10'  },
  // Task-level statuses (not run-level) — shown in TaskCard
  PENDING:           { dot: 'bg-zinc-500',         text: 'text-zinc-400',      glow: '',                        bg: 'bg-zinc-500/10' },
  LOCKED:            { dot: 'bg-zinc-400',         text: 'text-zinc-400',      glow: '',                        bg: 'bg-zinc-400/10' },
};

export const ACTIVE_STATUSES = new Set(['PLANNING', 'WORKING', 'STUCK', 'REVIEWING', 'MERGING']);
export const GATE_STATUSES   = new Set(['AWAITING_APPROVAL', 'AWAITING_MERGE']);

export const PIPELINE_STEPS = [
  'QUEUED', 'PLANNING', 'AWAITING_APPROVAL', 'WORKING',
  'REVIEWING', 'AWAITING_MERGE', 'MERGING', 'COMPLETE',
];

export const NAV_ITEMS = [
  { path: '/',             label: 'Runs',         icon: 'LayoutDashboard' },
  { path: '/dashboard',    label: 'Dashboard',    icon: 'BarChart3' },
  { path: '/pricing',      label: 'Pricing',      icon: 'DollarSign' },
  { path: '/deployments',  label: 'Deployments',  icon: 'Rocket' },
  { path: '/integrations', label: 'Integrations', icon: 'Plug' },
  { path: '/settings',     label: 'Settings',     icon: 'Settings' },
];

export const ROUTE_PATHS = {
  runs:         '/',
  dashboard:    '/dashboard',
  pricing:      '/pricing',
  deployments:  '/deployments',
  integrations: '/integrations',
  settings:     '/settings',
};
