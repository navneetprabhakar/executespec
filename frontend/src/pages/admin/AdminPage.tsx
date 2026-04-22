import { useEffect, useState } from 'react';
import { Shield, Users, Building2, Settings2, GitMerge, ChevronDown, Check, X, RefreshCw, Infinity, Gift, Package, Plus, Pencil, Trash2, Mail, Flag, BarChart3, DollarSign, Receipt, Globe, Download } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import RoleGuard from '@/components/auth/RoleGuard';
import { cn } from '@/lib/utils';
import * as api from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';
import AdminInviteModal from './AdminInviteModal';
import GrantRunsModal from './GrantRunsModal';

type Tab = 'users' | 'orgs' | 'tiers' | 'escalations' | 'packages' | 'reports' | 'audit' | 'webhooks';

const TIER_LABEL_FALLBACK: Record<string, string> = { FREE: 'Free', PRO: 'Pro', ENTERPRISE: 'Enterprise' };
const ROLE_LABEL: Record<string, string> = { USER: 'User', SUPER_ADMIN: 'Super Admin', FINANCE: 'Finance' };
const STATUS_COLOR: Record<string, string> = {
  ACTIVE: 'text-accent-green bg-accent-green/10',
  SUSPENDED: 'text-accent-amber bg-accent-amber/10',
  DELETED: 'text-muted bg-[var(--gray-200)]',
  PENDING_VERIFICATION: 'text-accent-blue bg-accent-blue/10',
};

export default function AdminPage() {
  return (
    <RoleGuard requireSuperAdmin>
      <AdminPageInner />
    </RoleGuard>
  );
}

function AdminPageInner() {
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>('users');
  const { addToast } = useUiStore();

  // ── Users state ───────────────────────────────────────────────────────────
  const [users, setUsers] = useState<api.AuthUser[]>([]);
  const [userSearch, setUserSearch] = useState('');
  const [showInviteModal, setShowInviteModal] = useState(false);

  // ── Orgs state ────────────────────────────────────────────────────────────
  const [orgs, setOrgs] = useState<api.OrgDto[]>([]);
  const [editingLimits, setEditingLimits] = useState<Record<string, string>>({});

  // ── Tier config state ─────────────────────────────────────────────────────
  const [tierConfigs, setTierConfigs] = useState<api.TierConfig[]>([]);
  const [editingTiers, setEditingTiers] = useState<Record<string, string>>({});

  // ── Escalations state ─────────────────────────────────────────────────────
  const [escalations, setEscalations] = useState<api.OrgInvitation[]>([]);

  // ── Grant Runs state ────────────────────────────────────────────────────
  const [grantTarget, setGrantTarget] = useState<{ type: 'user' | 'org'; id: string; name: string } | null>(null);

  // ── Top-Up Packages state ───────────────────────────────────────────────
  const [packages, setPackages] = useState<api.TopupPackageAdmin[]>([]);
  const [editingPkg, setEditingPkg] = useState<api.TopupPackageAdmin | null>(null);
  const [newPkg, setNewPkg] = useState(false);
  const [pkgForm, setPkgForm] = useState({ name: '', runCount: '', amountInr: '', amountUsd: '', sortOrder: '' });

  // ── Usage Reports state ──────────────────────────────────────────────────
  const [reportSending, setReportSending] = useState<'WEEKLY' | 'MONTHLY' | null>(null);
  const [reportResult, setReportResult] = useState<{ emailsSent: number; period: string } | null>(null);

  // ── Payment Audit state ─────────────────────────────────────────────────
  const [auditEntries, setAuditEntries] = useState<api.PaymentAuditEntry[]>([]);
  const [auditPage, setAuditPage] = useState(0);
  const [auditTotal, setAuditTotal] = useState(0);
  const [auditFilter, setAuditFilter] = useState({ orgId: '', userId: '' });

  // ── Webhook Events state ────────────────────────────────────────────────
  const [webhookEvents, setWebhookEvents] = useState<api.WebhookEventEntry[]>([]);
  const [webhookPage, setWebhookPage] = useState(0);
  const [webhookTotal, setWebhookTotal] = useState(0);
  const [webhookGateway, setWebhookGateway] = useState('');

  const triggerReport = async (period: 'WEEKLY' | 'MONTHLY') => {
    setReportSending(period);
    setReportResult(null);
    try {
      const result = await api.adminTriggerUsageReport(period);
      setReportResult(result);
      addToast({ type: 'success', message: `Queued ${result.emailsSent} usage report emails` });
    } catch (e: any) {
      addToast({ type: 'error', message: e.message || 'Failed to trigger reports' });
    } finally {
      setReportSending(null);
    }
  };

  const loadUsers       = () => api.adminListUsers().then(setUsers).catch(() => {});
  const loadOrgs        = () => api.adminListOrgs().then(setOrgs).catch(() => {});
  const loadTierConfigs = () => api.adminGetTierConfig().then(setTierConfigs).catch(() => {});
  const loadEscalations = () => api.adminListEscalated().then(setEscalations).catch(() => {});
  const loadPackages    = () => api.adminListTopupPackages().then(setPackages).catch(() => {});
  const loadAudit       = (page = 0) => api.adminListPaymentAudit(page, 50, auditFilter.orgId || undefined, auditFilter.userId || undefined)
    .then((r) => { setAuditEntries(r.content); setAuditTotal(r.totalElements); setAuditPage(page); }).catch(() => {});
  const loadWebhooks    = (page = 0) => api.adminListWebhookEvents(page, 50, webhookGateway || undefined)
    .then((r) => { setWebhookEvents(r.content); setWebhookTotal(r.totalElements); setWebhookPage(page); }).catch(() => {});

  useEffect(() => { loadUsers(); loadOrgs(); loadTierConfigs(); loadEscalations(); loadPackages(); }, []);

  // Build tier label map from DB config, with fallback
  const tierLabel = (tier: string) => {
    const fromDb = tierConfigs.find((tc) => tc.tier === tier);
    return fromDb?.displayName ?? TIER_LABEL_FALLBACK[tier] ?? tier;
  };

  // ── User actions ──────────────────────────────────────────────────────────
  const toggleUserStatus = async (u: api.AuthUser) => {
    const next = u.status === 'SUSPENDED' ? 'ACTIVE' : 'SUSPENDED';
    try { await api.adminUpdateUserStatus(u.id, next); await loadUsers(); }
    catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };

  const changeUserTier = async (id: string, tier: string) => {
    try { await api.adminUpdateUserTier(id, tier); await loadUsers(); }
    catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };

  const changeUserRole = async (id: string, role: string) => {
    try { await api.adminUpdateUserRole(id, role); await loadUsers(); }
    catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };

  // ── Org actions ───────────────────────────────────────────────────────────
  const toggleOrgStatus = async (o: api.OrgDto) => {
    const next = o.status === 'SUSPENDED' ? 'ACTIVE' : 'SUSPENDED';
    try { await api.adminUpdateOrgStatus(o.id, next); await loadOrgs(); }
    catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };

  const changeOrgTier = async (id: string, tier: string) => {
    try { await api.adminUpdateOrgTier(id, tier); await loadOrgs(); }
    catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };

  const saveOrgRunLimit = async (orgId: string) => {
    const raw = editingLimits[orgId];
    const limit = raw === '' || raw === undefined ? null : parseInt(raw, 10);
    if (limit !== null && isNaN(limit)) return;
    try {
      await api.adminUpdateOrgRunLimit(orgId, limit);
      setEditingLimits((prev) => { const n = { ...prev }; delete n[orgId]; return n; });
      await loadOrgs();
      addToast({ type: 'success', message: 'Run limit updated' });
    } catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };

  const toggleApiKeys = async (o: api.OrgDto) => {
    try { await api.adminUpdateOrgApiKeys(o.id, !o.apiKeysProvided); await loadOrgs(); }
    catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };

  // ── Tier config actions ───────────────────────────────────────────────────
  const saveTierLimit = async (tier: string) => {
    const raw = editingTiers[tier];
    const limit = parseInt(raw, 10);
    if (isNaN(limit)) return;
    try {
      await api.adminUpdateTierConfig(tier, limit);
      setEditingTiers((prev) => { const n = { ...prev }; delete n[tier]; return n; });
      await loadTierConfigs();
      addToast({ type: 'success', message: `${tierLabel(tier)} limit updated` });
    } catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };

  // ── Escalation actions ────────────────────────────────────────────────────
  const approveEsc = async (id: string) => {
    try { await api.adminApproveInvite(id); await loadEscalations(); addToast({ type: 'success', message: 'Approved' }); }
    catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };
  const rejectEsc = async (id: string) => {
    try { await api.adminRejectInvite(id); await loadEscalations(); addToast({ type: 'success', message: 'Rejected' }); }
    catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };

  // ── Package actions ──────────────────────────────────────────────────────
  const resetPkgForm = () => { setPkgForm({ name: '', runCount: '', amountInr: '', amountUsd: '', sortOrder: '' }); setEditingPkg(null); setNewPkg(false); };

  const startEditPkg = (p: api.TopupPackageAdmin) => {
    setEditingPkg(p);
    setNewPkg(false);
    setPkgForm({
      name: p.name,
      runCount: String(p.runCount),
      amountInr: String(p.amountInr),
      amountUsd: String(p.amountUsd),
      sortOrder: String(p.sortOrder),
    });
  };

  const savePkg = async () => {
    const body = {
      name: pkgForm.name,
      runCount: parseInt(pkgForm.runCount, 10),
      amountInr: parseInt(pkgForm.amountInr, 10),
      amountUsd: parseInt(pkgForm.amountUsd, 10) || 0,
      sortOrder: parseInt(pkgForm.sortOrder, 10) || 0,
    };
    if (!body.name || isNaN(body.runCount) || isNaN(body.amountInr)) {
      addToast({ type: 'error', message: 'Name, run count, and INR amount are required' });
      return;
    }
    try {
      if (editingPkg) {
        await api.adminUpdateTopupPackage(editingPkg.id, body);
        addToast({ type: 'success', message: 'Package updated' });
      } else {
        await api.adminCreateTopupPackage(body);
        addToast({ type: 'success', message: 'Package created' });
      }
      resetPkgForm();
      await loadPackages();
    } catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };

  const deactivatePkg = async (id: string) => {
    try {
      await api.adminDeleteTopupPackage(id);
      addToast({ type: 'success', message: 'Package deactivated' });
      await loadPackages();
    } catch (e: any) { addToast({ type: 'error', message: e.message }); }
  };

  const filteredUsers = users.filter((u) => {
    const q = userSearch.toLowerCase();
    return !q || u.email.toLowerCase().includes(q) ||
      `${u.firstName} ${u.lastName}`.toLowerCase().includes(q);
  });

  const TABS: { id: Tab; label: string; icon: React.ElementType; count?: number }[] = [
    { id: 'users',       label: 'Users',        icon: Users,     count: users.length },
    { id: 'orgs',        label: 'Organisations', icon: Building2, count: orgs.length },
    { id: 'tiers',       label: 'Tier Config',  icon: Settings2 },
    { id: 'escalations', label: 'Escalations',  icon: GitMerge,  count: escalations.length || undefined },
    { id: 'packages',    label: 'Top-Up Pkgs',  icon: Package,   count: packages.filter((p) => p.active).length },
    { id: 'reports',     label: 'Usage Reports', icon: Mail },
    { id: 'audit',       label: 'Payment Audit', icon: Receipt },
    { id: 'webhooks',    label: 'Webhooks',      icon: Globe },
  ];

  return (
    <div className="h-full flex flex-col overflow-hidden relative">
      <div aria-hidden className="pointer-events-none absolute inset-0 -z-10" style={{
        background: 'radial-gradient(ellipse 60% 40% at 20% 0%, color-mix(in srgb, var(--accent-blue) 12%, transparent), transparent 60%)'
      }} />
      {/* Page header */}
      <div className="flex flex-col sm:flex-row sm:items-center gap-3 px-4 sm:px-6 py-5 border-b border-[var(--border-default)] flex-shrink-0">
        <div className="flex items-center gap-3 min-w-0 flex-1">
          <span className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-[color-mix(in_srgb,var(--accent-blue)_30%,transparent)] bg-[color-mix(in_srgb,var(--accent-blue)_12%,transparent)] text-accent-blue flex-shrink-0">
            <Shield className="h-4 w-4" />
          </span>
          <div className="min-w-0">
            <div className="inline-block font-mono text-[10.5px] font-medium uppercase tracking-[0.08em] text-accent-blue">Admin</div>
            <h1 className="text-lg sm:text-xl font-semibold text-primary tracking-[-0.015em] leading-tight">Super admin console</h1>
          </div>
        </div>
        <div className="flex items-center gap-2 sm:ml-auto flex-wrap">
          <button
            onClick={() => navigate('/admin/analytics')}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-secondary hover:text-primary bg-[var(--gray-200)] hover:bg-[var(--border-default)] rounded-md border border-[var(--border-default)] transition-colors"
          >
            <BarChart3 className="h-3.5 w-3.5" />
            Analytics
          </button>
          <button
            onClick={() => navigate('/admin/pricing')}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-secondary hover:text-primary bg-[var(--gray-200)] hover:bg-[var(--border-default)] rounded-md border border-[var(--border-default)] transition-colors"
          >
            <DollarSign className="h-3.5 w-3.5" />
            Pricing
          </button>
          <button
            onClick={() => navigate('/admin/feature-flags')}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-secondary hover:text-primary bg-[var(--gray-200)] hover:bg-[var(--border-default)] rounded-md border border-[var(--border-default)] transition-colors"
          >
            <Flag className="h-3.5 w-3.5" />
            Feature Flags
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 px-4 sm:px-6 pt-3 flex-shrink-0 border-b border-[var(--border-default)] overflow-x-auto">
        {TABS.map(({ id, label, icon: Icon, count }) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={cn(
              'flex items-center gap-1.5 px-3 py-2 text-sm rounded-t-lg border-b-2 transition-colors',
              tab === id
                ? 'border-accent-blue text-accent-blue'
                : 'border-transparent text-muted hover:text-secondary',
            )}
          >
            <Icon className="h-3.5 w-3.5" />
            {label}
            {count !== undefined && (
              <span className={cn(
                'ml-1 px-1.5 py-0.5 text-[10px] rounded-full font-mono',
                tab === id ? 'bg-accent-blue/20 text-accent-blue' : 'bg-[var(--gray-200)] text-muted',
              )}>{count}</span>
            )}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-auto p-4 sm:p-6">

        {/* ── USERS TAB ─────────────────────────────────────────────────── */}
        {tab === 'users' && (
          <div className="space-y-4">
            <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 sm:gap-3">
              <input
                type="text"
                value={userSearch}
                onChange={(e) => setUserSearch(e.target.value)}
                placeholder="Search users…"
                className="flex-1 bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-secondary placeholder:text-muted focus:outline-none focus:border-accent-blue/40"
              />
              <div className="flex items-center gap-2">
                <button
                  onClick={() => setShowInviteModal(true)}
                  className="w-full sm:w-auto flex items-center justify-center gap-2 px-4 py-2 bg-accent-blue hover:bg-accent-blue/80 text-primary text-sm font-medium rounded-sm transition-colors"
                >
                  + Invite User
                </button>
                <button onClick={loadUsers} className="p-2 text-muted hover:text-secondary">
                  <RefreshCw className="h-4 w-4" />
                </button>
              </div>
            </div>

            <div className="rounded-sm border border-[var(--border-default)] overflow-hidden">
              <div className="overflow-x-auto">
              <table className="w-full text-sm min-w-[600px] md:min-w-0">
                <thead className="bg-[var(--gray-100)] text-muted text-xs">
                  <tr>
                    <th className="px-4 py-3 text-left font-medium">Name / Email</th>
                    <th className="px-4 py-3 text-left font-medium">Status</th>
                    <th className="px-4 py-3 text-left font-medium hidden md:table-cell">Tier</th>
                    <th className="px-4 py-3 text-left font-medium hidden md:table-cell">Role</th>
                    <th className="px-4 py-3 text-left font-medium hidden lg:table-cell">Org Role</th>
                    <th className="px-4 py-3 text-left font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--border-default)]">
                  {filteredUsers.map((u) => (
                    <tr key={u.id} className="hover:bg-[var(--gray-100)] transition-colors">
                      <td className="px-4 py-3">
                        <p className="text-primary font-medium">{u.firstName} {u.lastName}</p>
                        <p className="text-muted text-xs">{u.email}</p>
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn('px-2 py-0.5 rounded-sm text-xs font-medium', STATUS_COLOR[u.status] ?? 'text-secondary')}>
                          {u.status}
                        </span>
                      </td>
                      <td className="px-4 py-3 hidden md:table-cell">
                        <select
                          value={u.tier}
                          onChange={(e) => changeUserTier(u.id, e.target.value)}
                          className="bg-[var(--gray-100)] border border-[var(--border-default)] rounded px-2 py-1 text-xs text-secondary focus:outline-none"
                        >
                          {(tierConfigs.length > 0 ? tierConfigs : [{ tier: 'FREE' }, { tier: 'PRO' }, { tier: 'ENTERPRISE' }]).map((tc) => (
                            <option key={tc.tier} value={tc.tier}>{tierLabel(tc.tier)}</option>
                          ))}
                        </select>
                      </td>
                      <td className="px-4 py-3 hidden md:table-cell">
                        <select
                          value={u.globalRole}
                          onChange={(e) => changeUserRole(u.id, e.target.value)}
                          className="bg-[var(--gray-100)] border border-[var(--border-default)] rounded px-2 py-1 text-xs text-secondary focus:outline-none"
                        >
                          {Object.entries(ROLE_LABEL).map(([v, l]) => (
                            <option key={v} value={v}>{l}</option>
                          ))}
                        </select>
                      </td>
                      <td className="px-4 py-3 text-secondary text-xs hidden lg:table-cell">{u.orgRole ?? '—'}</td>
                      <td className="px-4 py-3 flex items-center gap-1.5">
                        <button
                          onClick={() => setGrantTarget({ type: 'user', id: u.id, name: `${u.firstName} ${u.lastName}` })}
                          className="px-2.5 py-1 rounded text-xs font-medium bg-accent-green/10 text-accent-green hover:bg-accent-green/20 transition-colors"
                          title="Grant bonus runs"
                        >
                          <Gift className="h-3 w-3 inline mr-1" />Runs
                        </button>
                        <button
                          onClick={() => toggleUserStatus(u)}
                          className={cn(
                            'px-3 py-1 rounded text-xs font-medium transition-colors',
                            u.status === 'SUSPENDED'
                              ? 'bg-accent-green/10 text-accent-green hover:bg-accent-green/20'
                              : 'bg-accent-amber/10 text-accent-amber hover:bg-accent-amber/20',
                          )}
                        >
                          {u.status === 'SUSPENDED' ? 'Restore' : 'Suspend'}
                        </button>
                      </td>
                    </tr>
                  ))}
                  {filteredUsers.length === 0 && (
                    <tr><td colSpan={6} className="px-4 py-8 text-center text-muted">No users found</td></tr>
                  )}
                </tbody>
              </table>
              </div>
            </div>
          </div>
        )}

        {/* ── ORGS TAB ──────────────────────────────────────────────────── */}
        {tab === 'orgs' && (
          <div className="space-y-4">
            <div className="flex justify-end">
              <button onClick={loadOrgs} className="p-2 text-muted hover:text-secondary">
                <RefreshCw className="h-4 w-4" />
              </button>
            </div>
            <div className="rounded-sm border border-[var(--border-default)] overflow-hidden">
              <div className="overflow-x-auto">
              <table className="w-full text-sm min-w-[600px] md:min-w-0">
                <thead className="bg-[var(--gray-100)] text-muted text-xs">
                  <tr>
                    {['Org', 'Tier', 'Status', 'Run Limit', 'API Keys', 'Usage', 'Actions'].map((h) => (
                      <th key={h} className="px-4 py-3 text-left font-medium whitespace-nowrap">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--border-default)]">
                  {orgs.map((o) => {
                    const limitEditing = editingLimits[o.id];
                    const displayLimit = o.monthlyRunLimitOverride ?? '(default)';
                    return (
                      <tr key={o.id} className="hover:bg-[var(--gray-100)] transition-colors">
                        <td className="px-4 py-3">
                          <p className="text-primary font-medium">{o.name}</p>
                          <p className="text-muted text-xs truncate max-w-[160px]">{o.domainPatterns || '—'}</p>
                        </td>
                        <td className="px-4 py-3">
                          <select
                            value={o.tier}
                            onChange={(e) => changeOrgTier(o.id, e.target.value)}
                            className="bg-[var(--gray-100)] border border-[var(--border-default)] rounded px-2 py-1 text-xs text-secondary focus:outline-none"
                          >
                            <option value="PRO">Team</option>
                            <option value="ENTERPRISE">Enterprise</option>
                          </select>
                        </td>
                        <td className="px-4 py-3">
                          <span className={cn('px-2 py-0.5 rounded-sm text-xs font-medium', STATUS_COLOR[o.status] ?? 'text-secondary')}>
                            {o.status}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-1">
                            <input
                              type="number"
                              value={limitEditing !== undefined ? limitEditing : (o.monthlyRunLimitOverride ?? '')}
                              onChange={(e) => setEditingLimits((p) => ({ ...p, [o.id]: e.target.value }))}
                              placeholder="default"
                              className="w-20 bg-[var(--gray-100)] border border-[var(--border-default)] rounded px-2 py-1 text-xs text-secondary focus:outline-none focus:border-accent-blue/40"
                            />
                            {limitEditing !== undefined && (
                              <button onClick={() => saveOrgRunLimit(o.id)} className="p-1 text-accent-green hover:text-accent-green/80">
                                <Check className="h-3.5 w-3.5" />
                              </button>
                            )}
                          </div>
                        </td>
                        <td className="px-4 py-3">
                          <button
                            onClick={() => toggleApiKeys(o)}
                            className={cn(
                              'relative h-5 w-9 rounded-full transition-colors',
                              o.apiKeysProvided ? 'bg-accent-green' : 'bg-[var(--border-default)]',
                            )}
                            title={o.apiKeysProvided ? 'API keys provided (Enterprise = unlimited)' : 'No API keys'}
                          >
                            <span className={cn(
                              'absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform',
                              o.apiKeysProvided ? 'translate-x-4' : 'translate-x-0.5',
                            )} />
                          </button>
                        </td>
                        <td className="px-4 py-3 text-secondary text-xs">
                          {o.monthlyRunCount} runs
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-1.5">
                            <button
                              onClick={() => setGrantTarget({ type: 'org', id: o.id, name: o.name })}
                              className="px-2.5 py-1 rounded text-xs font-medium bg-accent-green/10 text-accent-green hover:bg-accent-green/20 transition-colors"
                              title="Grant bonus runs"
                            >
                              <Gift className="h-3 w-3 inline mr-1" />Runs
                            </button>
                            <button
                              onClick={() => toggleOrgStatus(o)}
                              className={cn(
                                'px-3 py-1 rounded text-xs font-medium transition-colors',
                                o.status === 'SUSPENDED'
                                  ? 'bg-accent-green/10 text-accent-green hover:bg-accent-green/20'
                                  : 'bg-accent-amber/10 text-accent-amber hover:bg-accent-amber/20',
                              )}
                            >
                              {o.status === 'SUSPENDED' ? 'Restore' : 'Suspend'}
                            </button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                  {orgs.length === 0 && (
                    <tr><td colSpan={7} className="px-4 py-8 text-center text-muted">No organisations</td></tr>
                  )}
                </tbody>
              </table>
              </div>
            </div>
          </div>
        )}

        {/* ── TIER CONFIG TAB ───────────────────────────────────────────── */}
        {tab === 'tiers' && (
          <div className="space-y-4 max-w-lg">
            <p className="text-sm text-muted">
              Set the default monthly run limits for each tier. Changes take effect immediately for new runs.
            </p>
            <div className="rounded-sm border border-[var(--border-default)] overflow-hidden">
              <table className="w-full text-sm">
                <thead className="bg-[var(--gray-100)] text-muted text-xs">
                  <tr>
                    <th className="px-4 py-3 text-left font-medium">Tier</th>
                    <th className="px-4 py-3 text-left font-medium">Monthly Limit</th>
                    <th className="px-4 py-3 text-left font-medium">Note</th>
                    <th className="px-4 py-3"></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--border-default)]">
                  {tierConfigs.map((tc) => {
                    const editing = editingTiers[tc.tier];
                    return (
                      <tr key={tc.tier} className="hover:bg-[var(--gray-100)]">
                        <td className="px-4 py-3">
                          <span className="font-medium text-primary">{tc.displayName ?? tierLabel(tc.tier)}</span>
                          {tc.isFixed && <span className="ml-2 text-xs text-muted">(default fixed)</span>}
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-2">
                            <input
                              type="number"
                              value={editing !== undefined ? editing : tc.monthlyRunLimit}
                              onChange={(e) => setEditingTiers((p) => ({ ...p, [tc.tier]: e.target.value }))}
                              className="w-24 bg-[var(--gray-100)] border border-[var(--border-default)] rounded px-2 py-1.5 text-sm text-secondary focus:outline-none focus:border-accent-blue/40"
                            />
                            {editing !== undefined && (
                              <button onClick={() => saveTierLimit(tc.tier)} className="p-1.5 bg-accent-green/15 text-accent-green rounded-sm hover:bg-accent-green/25">
                                <Check className="h-3.5 w-3.5" />
                              </button>
                            )}
                          </div>
                        </td>
                        <td className="px-4 py-3 text-muted text-xs">
                          {tc.tier === 'ENTERPRISE'
                            ? 'Override; ENTERPRISE orgs with API keys get unlimited'
                            : tc.description}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <p className="text-xs text-muted">
              Enterprise orgs with <strong className="text-secondary">API Keys Provided</strong> toggled on always get unlimited runs regardless of this value.
              Per-org overrides (set in the Orgs tab) take precedence over these defaults.
            </p>
          </div>
        )}

        {/* ── ESCALATIONS TAB ───────────────────────────────────────────── */}
        {tab === 'escalations' && (
          <div className="space-y-4">
            <p className="text-sm text-muted">
              Invitations escalated because the invitee's email domain doesn't match the org's registered domains.
            </p>
            {escalations.length === 0 ? (
              <div className="text-center py-12 text-muted">No pending escalations</div>
            ) : (
              <div className="space-y-3">
                {escalations.map((inv) => (
                  <div key={inv.id} className="flex items-center justify-between p-4 rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)]">
                    <div>
                      <p className="text-primary text-sm font-medium">{inv.invitedEmail}</p>
                      <p className="text-muted text-xs mt-0.5">Org: {inv.orgId} · Role: {inv.orgRole}</p>
                    </div>
                    <div className="flex gap-2">
                      <button
                        onClick={() => approveEsc(inv.id)}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-sm bg-accent-green/10 text-accent-green text-xs font-medium hover:bg-accent-green/20 transition-colors"
                      >
                        <Check className="h-3.5 w-3.5" /> Approve
                      </button>
                      <button
                        onClick={() => rejectEsc(inv.id)}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-sm bg-accent-red/10 text-accent-red text-xs font-medium hover:bg-accent-red/20 transition-colors"
                      >
                        <X className="h-3.5 w-3.5" /> Reject
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* ── TOP-UP PACKAGES TAB ─────────────────────────────────────────── */}
        {tab === 'packages' && (
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <p className="text-sm text-muted">
                Manage top-up run packages available for purchase. Deactivated packages are hidden from users.
              </p>
              <div className="flex gap-2">
                <button
                  onClick={() => { setNewPkg(true); setEditingPkg(null); setPkgForm({ name: '', runCount: '', amountInr: '', amountUsd: '', sortOrder: '' }); }}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-sm bg-accent-blue hover:bg-accent-blue/80 text-primary text-xs font-medium transition-colors"
                >
                  <Plus className="h-3.5 w-3.5" /> New Package
                </button>
                <button onClick={loadPackages} className="p-2 text-muted hover:text-secondary">
                  <RefreshCw className="h-4 w-4" />
                </button>
              </div>
            </div>

            {/* Create / Edit form */}
            {(newPkg || editingPkg) && (
              <div className="rounded-sm bg-[var(--gray-100)] border border-accent-blue/30 p-4 space-y-3">
                <h3 className="text-sm font-medium text-primary">
                  {editingPkg ? `Edit: ${editingPkg.name}` : 'Create New Package'}
                </h3>
                <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3">
                  <div>
                    <label className="block text-[10px] text-muted mb-1">Name</label>
                    <input
                      type="text"
                      value={pkgForm.name}
                      onChange={(e) => setPkgForm((p) => ({ ...p, name: e.target.value }))}
                      placeholder="e.g. 10 Runs"
                      className="w-full bg-[var(--gray-200)] border border-[var(--border-default)] rounded px-2 py-1.5 text-sm text-primary focus:outline-none focus:border-accent-blue/40"
                    />
                  </div>
                  <div>
                    <label className="block text-[10px] text-muted mb-1">Run Count</label>
                    <input
                      type="number"
                      value={pkgForm.runCount}
                      onChange={(e) => setPkgForm((p) => ({ ...p, runCount: e.target.value }))}
                      placeholder="10"
                      className="w-full bg-[var(--gray-200)] border border-[var(--border-default)] rounded px-2 py-1.5 text-sm text-primary focus:outline-none focus:border-accent-blue/40"
                    />
                  </div>
                  <div>
                    <label className="block text-[10px] text-muted mb-1">INR (paise)</label>
                    <input
                      type="number"
                      value={pkgForm.amountInr}
                      onChange={(e) => setPkgForm((p) => ({ ...p, amountInr: e.target.value }))}
                      placeholder="49900"
                      className="w-full bg-[var(--gray-200)] border border-[var(--border-default)] rounded px-2 py-1.5 text-sm text-primary focus:outline-none focus:border-accent-blue/40"
                    />
                  </div>
                  <div>
                    <label className="block text-[10px] text-muted mb-1">USD (cents)</label>
                    <input
                      type="number"
                      value={pkgForm.amountUsd}
                      onChange={(e) => setPkgForm((p) => ({ ...p, amountUsd: e.target.value }))}
                      placeholder="599"
                      className="w-full bg-[var(--gray-200)] border border-[var(--border-default)] rounded px-2 py-1.5 text-sm text-primary focus:outline-none focus:border-accent-blue/40"
                    />
                  </div>
                  <div>
                    <label className="block text-[10px] text-muted mb-1">Sort Order</label>
                    <input
                      type="number"
                      value={pkgForm.sortOrder}
                      onChange={(e) => setPkgForm((p) => ({ ...p, sortOrder: e.target.value }))}
                      placeholder="1"
                      className="w-full bg-[var(--gray-200)] border border-[var(--border-default)] rounded px-2 py-1.5 text-sm text-primary focus:outline-none focus:border-accent-blue/40"
                    />
                  </div>
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={savePkg}
                    className="px-4 py-1.5 rounded-sm bg-accent-green/15 text-accent-green text-xs font-medium hover:bg-accent-green/25 transition-colors"
                  >
                    {editingPkg ? 'Save Changes' : 'Create'}
                  </button>
                  <button
                    onClick={resetPkgForm}
                    className="px-4 py-1.5 rounded-sm bg-[var(--gray-200)] text-secondary text-xs font-medium hover:bg-[var(--border-default)] transition-colors"
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}

            {/* Packages table */}
            <div className="rounded-sm border border-[var(--border-default)] overflow-hidden">
              <div className="overflow-x-auto">
              <table className="w-full text-sm min-w-[600px] md:min-w-0">
                <thead className="bg-[var(--gray-100)] text-muted text-xs">
                  <tr>
                    {['Name', 'Runs', 'Price (INR)', 'Price (USD)', 'Order', 'Status', 'Actions'].map((h) => (
                      <th key={h} className="px-4 py-3 text-left font-medium whitespace-nowrap">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--border-default)]">
                  {packages.map((p) => (
                    <tr key={p.id} className={cn('hover:bg-[var(--gray-100)] transition-colors', !p.active && 'opacity-50')}>
                      <td className="px-4 py-3 text-primary font-medium">{p.name}</td>
                      <td className="px-4 py-3 text-secondary">{p.runCount}</td>
                      <td className="px-4 py-3 text-secondary font-mono text-xs">
                        {'₹'}{(p.amountInr / 100).toFixed(0)}
                        <span className="text-muted ml-1">({p.amountInr}p)</span>
                      </td>
                      <td className="px-4 py-3 text-secondary font-mono text-xs">
                        ${(p.amountUsd / 100).toFixed(2)}
                        <span className="text-muted ml-1">({p.amountUsd}c)</span>
                      </td>
                      <td className="px-4 py-3 text-secondary text-xs">{p.sortOrder}</td>
                      <td className="px-4 py-3">
                        <span className={cn(
                          'px-2 py-0.5 rounded-sm text-xs font-medium',
                          p.active ? 'text-accent-green bg-accent-green/10' : 'text-muted bg-[var(--gray-200)]',
                        )}>
                          {p.active ? 'Active' : 'Inactive'}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-1.5">
                          <button
                            onClick={() => startEditPkg(p)}
                            className="p-1.5 rounded text-secondary hover:text-primary hover:bg-[var(--gray-200)] transition-colors"
                            title="Edit"
                          >
                            <Pencil className="h-3.5 w-3.5" />
                          </button>
                          {p.active && (
                            <button
                              onClick={() => deactivatePkg(p.id)}
                              className="p-1.5 rounded text-accent-red/60 hover:text-accent-red hover:bg-accent-red/10 transition-colors"
                              title="Deactivate"
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                  {packages.length === 0 && (
                    <tr><td colSpan={7} className="px-4 py-8 text-center text-muted">No top-up packages</td></tr>
                  )}
                </tbody>
              </table>
              </div>
            </div>
          </div>
        )}
      </div>

        {/* ── USAGE REPORTS TAB ──────────────────────────────────────────── */}
        {tab === 'reports' && (
          <div className="space-y-6 max-w-xl">
            <div>
              <h2 className="text-base font-semibold text-primary mb-1">Send Usage Reports</h2>
              <p className="text-sm text-muted leading-relaxed">
                Sends usage report emails to <strong className="text-secondary">all active users</strong> and{' '}
                <strong className="text-secondary">org admins</strong>. Solo users get personal reports.
                Org admins get aggregated reports with top contributors and per-project breakdown.
              </p>
            </div>

            <div className="flex flex-col sm:flex-row gap-3 sm:gap-4">
              <button
                onClick={() => triggerReport('WEEKLY')}
                disabled={reportSending !== null}
                className={cn(
                  'flex items-center gap-2 px-5 py-2.5 rounded-sm text-sm font-semibold transition-all',
                  reportSending === 'WEEKLY'
                    ? 'bg-accent-blue/20 text-accent-blue cursor-wait'
                    : 'bg-accent-blue hover:bg-accent-blue/80 text-primary',
                  reportSending !== null && reportSending !== 'WEEKLY' && 'opacity-50 cursor-not-allowed',
                )}
              >
                <Mail className="h-4 w-4" />
                {reportSending === 'WEEKLY' ? 'Sending...' : 'Send Weekly Report'}
              </button>
              <button
                onClick={() => triggerReport('MONTHLY')}
                disabled={reportSending !== null}
                className={cn(
                  'flex items-center gap-2 px-5 py-2.5 rounded-sm text-sm font-semibold transition-all',
                  reportSending === 'MONTHLY'
                    ? 'bg-cyan-500/20 text-cyan-400 cursor-wait'
                    : 'bg-cyan-600 hover:bg-cyan-500 text-primary',
                  reportSending !== null && reportSending !== 'MONTHLY' && 'opacity-50 cursor-not-allowed',
                )}
              >
                <Mail className="h-4 w-4" />
                {reportSending === 'MONTHLY' ? 'Sending...' : 'Send Monthly Report'}
              </button>
            </div>

            {reportResult && (
              <div className="rounded-sm bg-accent-green/10 border border-accent-green/20 px-5 py-4 flex items-center gap-3">
                <Check className="h-5 w-5 text-accent-green flex-shrink-0" />
                <div>
                  <p className="text-sm font-medium text-accent-green">
                    Queued {reportResult.emailsSent} usage report emails
                  </p>
                  <p className="text-xs text-muted mt-0.5">
                    Period: {reportResult.period} &middot; Emails are being sent asynchronously
                  </p>
                </div>
              </div>
            )}

            <div className="rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-4">
              <h3 className="text-xs font-medium text-muted uppercase tracking-wider mb-3">What gets sent</h3>
              <div className="space-y-2 text-sm text-secondary">
                <div className="flex items-start gap-2">
                  <span className="text-accent-blue mt-0.5">&#9679;</span>
                  <span><strong className="text-secondary">Solo users</strong> — personal run count, token usage, cost estimate, per-project breakdown, comparison with previous period</span>
                </div>
                <div className="flex items-start gap-2">
                  <span className="text-cyan-400 mt-0.5">&#9679;</span>
                  <span><strong className="text-secondary">Org admins</strong> — aggregated org usage, top 5 contributors leaderboard, per-project breakdown, member count, period comparison</span>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ── PAYMENT AUDIT TAB ──────────────────────────────────────────── */}
        {tab === 'audit' && (
          <div className="space-y-4">
            <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 sm:gap-3">
              <input
                type="text"
                value={auditFilter.orgId}
                onChange={(e) => setAuditFilter((f) => ({ ...f, orgId: e.target.value }))}
                placeholder="Filter by Org ID…"
                className="flex-1 bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-secondary placeholder:text-muted focus:outline-none focus:border-accent-blue/40"
              />
              <input
                type="text"
                value={auditFilter.userId}
                onChange={(e) => setAuditFilter((f) => ({ ...f, userId: e.target.value }))}
                placeholder="Filter by User ID…"
                className="flex-1 bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-secondary placeholder:text-muted focus:outline-none focus:border-accent-blue/40"
              />
              <div className="flex items-center gap-2">
                <button onClick={() => loadAudit(0)} className="w-full sm:w-auto flex items-center justify-center gap-1.5 px-3 py-2 bg-accent-blue hover:bg-accent-blue/80 text-primary text-sm font-medium rounded-sm transition-colors">
                  Search
                </button>
                <button
                  onClick={() => api.adminExportPaymentAuditCsv().catch((e: any) => addToast({ type: 'error', message: e.message }))}
                  className="w-full sm:w-auto flex items-center justify-center gap-1.5 px-3 py-2 text-muted hover:text-secondary border border-[var(--border-default)] rounded-sm text-sm transition-colors"
                >
                  <Download className="h-3.5 w-3.5" />
                  CSV
                </button>
                <button onClick={() => loadAudit(auditPage)} className="p-2 text-muted hover:text-secondary">
                  <RefreshCw className="h-4 w-4" />
                </button>
              </div>
            </div>

            <div className="rounded-sm border border-[var(--border-default)] overflow-hidden">
              <div className="overflow-x-auto">
              <table className="w-full text-sm min-w-[600px] md:min-w-0">
                <thead className="bg-[var(--gray-100)] text-muted text-xs">
                  <tr>
                    {['Event', 'Entity', 'Tier Change', 'Gateway', 'Amount', 'By', 'Date'].map((h) => (
                      <th key={h} className="px-4 py-3 text-left font-medium whitespace-nowrap">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--border-default)]">
                  {auditEntries.length === 0 && (
                    <tr><td colSpan={7} className="px-4 py-8 text-center text-muted">
                      {auditTotal === 0 ? 'No audit entries. Click Search to load.' : 'No results.'}
                    </td></tr>
                  )}
                  {auditEntries.map((a) => (
                    <tr key={a.id} className="hover:bg-[var(--gray-100)] transition-colors">
                      <td className="px-4 py-3">
                        <span className="inline-block px-2 py-0.5 text-xs rounded-full bg-accent-blue/10 text-accent-blue whitespace-nowrap">{a.eventType}</span>
                      </td>
                      <td className="px-4 py-3 text-secondary">
                        <p className="text-xs">{a.entityType}</p>
                        <p className="text-muted text-[10px] font-mono truncate max-w-[120px]" title={a.entityId}>{a.entityId}</p>
                      </td>
                      <td className="px-4 py-3 text-xs text-secondary whitespace-nowrap">
                        {a.oldTier || a.newTier ? <>{a.oldTier ?? '–'} → {a.newTier ?? '–'}</> : '–'}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted">{a.gatewayProvider ?? '–'}</td>
                      <td className="px-4 py-3 text-xs text-secondary font-mono whitespace-nowrap">
                        {a.amountMinor != null ? `${(a.amountMinor / 100).toFixed(2)} ${a.currency ?? ''}` : '–'}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted truncate max-w-[100px]" title={a.performedBy ?? undefined}>{a.performedBy ?? '–'}</td>
                      <td className="px-4 py-3 text-xs text-muted whitespace-nowrap">{new Date(a.createdAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              </div>
            </div>

            {auditTotal > 50 && (
              <div className="flex items-center justify-between text-xs text-muted">
                <span>{auditTotal} total entries</span>
                <div className="flex gap-2">
                  <button disabled={auditPage === 0} onClick={() => loadAudit(auditPage - 1)} className="px-3 py-1 border border-[var(--border-default)] rounded-sm disabled:opacity-40">Prev</button>
                  <span className="px-2 py-1">Page {auditPage + 1}</span>
                  <button disabled={(auditPage + 1) * 50 >= auditTotal} onClick={() => loadAudit(auditPage + 1)} className="px-3 py-1 border border-[var(--border-default)] rounded-sm disabled:opacity-40">Next</button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* ── WEBHOOK EVENTS TAB ────────────────────────────────────────────── */}
        {tab === 'webhooks' && (
          <div className="space-y-4">
            <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 sm:gap-3">
              <select
                value={webhookGateway}
                onChange={(e) => setWebhookGateway(e.target.value)}
                className="w-full sm:w-auto bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-secondary focus:outline-none focus:border-accent-blue/40"
              >
                <option value="">All Gateways</option>
                <option value="RAZORPAY">Razorpay</option>
                <option value="STRIPE">Stripe</option>
              </select>
              <div className="flex items-center gap-2">
                <button onClick={() => loadWebhooks(0)} className="w-full sm:w-auto flex items-center justify-center gap-1.5 px-3 py-2 bg-accent-blue hover:bg-accent-blue/80 text-primary text-sm font-medium rounded-sm transition-colors">
                  Search
                </button>
                <button onClick={() => loadWebhooks(webhookPage)} className="p-2 text-muted hover:text-secondary">
                  <RefreshCw className="h-4 w-4" />
                </button>
              </div>
            </div>

            <div className="rounded-sm border border-[var(--border-default)] overflow-hidden">
              <div className="overflow-x-auto">
              <table className="w-full text-sm min-w-[600px] md:min-w-0">
                <thead className="bg-[var(--gray-100)] text-muted text-xs">
                  <tr>
                    {['Gateway', 'Event ID', 'Event Type', 'Status', 'Attempts', 'Received'].map((h) => (
                      <th key={h} className="px-4 py-3 text-left font-medium whitespace-nowrap">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--border-default)]">
                  {webhookEvents.length === 0 && (
                    <tr><td colSpan={6} className="px-4 py-8 text-center text-muted">
                      {webhookTotal === 0 ? 'No webhook events. Click Search to load.' : 'No results.'}
                    </td></tr>
                  )}
                  {webhookEvents.map((w) => (
                    <tr key={w.id} className="hover:bg-[var(--gray-100)] transition-colors">
                      <td className="px-4 py-3">
                        <span className={cn(
                          'inline-block px-2 py-0.5 text-xs rounded-full',
                          w.gateway === 'STRIPE' ? 'bg-purple-500/10 text-purple-400' : 'bg-accent-blue/10 text-accent-blue',
                        )}>{w.gateway}</span>
                      </td>
                      <td className="px-4 py-3 text-xs text-secondary font-mono truncate max-w-[180px]" title={w.eventId}>{w.eventId}</td>
                      <td className="px-4 py-3 text-xs text-secondary">{w.eventType}</td>
                      <td className="px-4 py-3">
                        <span className={cn(
                          'inline-block px-2 py-0.5 text-xs rounded-full',
                          w.processingStatus === 'PROCESSED' && 'bg-accent-green/10 text-accent-green',
                          w.processingStatus === 'IGNORED' && 'bg-accent-amber/10 text-accent-amber',
                          w.processingStatus === 'FAILED' && 'bg-status-error/10 text-status-error',
                        )}>{w.processingStatus}</span>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted text-center">{w.attempts}</td>
                      <td className="px-4 py-3 text-xs text-muted whitespace-nowrap">{new Date(w.createdAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              </div>
            </div>

            {webhookTotal > 50 && (
              <div className="flex items-center justify-between text-xs text-muted">
                <span>{webhookTotal} total events</span>
                <div className="flex gap-2">
                  <button disabled={webhookPage === 0} onClick={() => loadWebhooks(webhookPage - 1)} className="px-3 py-1 border border-[var(--border-default)] rounded-sm disabled:opacity-40">Prev</button>
                  <span className="px-2 py-1">Page {webhookPage + 1}</span>
                  <button disabled={(webhookPage + 1) * 50 >= webhookTotal} onClick={() => loadWebhooks(webhookPage + 1)} className="px-3 py-1 border border-[var(--border-default)] rounded-sm disabled:opacity-40">Next</button>
                </div>
              </div>
            )}
          </div>
        )}

      {showInviteModal && (
        <AdminInviteModal
          onClose={() => setShowInviteModal(false)}
          onSuccess={loadUsers}
        />
      )}

      {grantTarget && (
        <GrantRunsModal
          targetType={grantTarget.type}
          targetId={grantTarget.id}
          targetName={grantTarget.name}
          onClose={() => setGrantTarget(null)}
          onSuccess={() => { grantTarget.type === 'user' ? loadUsers() : loadOrgs(); }}
        />
      )}
    </div>
  );
}
