import { useEffect, useState } from 'react';
import { Shield, Users, Building2, Settings2, GitMerge, ChevronDown, Check, X, RefreshCw, Infinity } from 'lucide-react';
import RoleGuard from '@/components/auth/RoleGuard';
import { cn } from '@/lib/utils';
import * as api from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';
import AdminInviteModal from './AdminInviteModal';

type Tab = 'users' | 'orgs' | 'tiers' | 'escalations';

const TIER_LABEL: Record<string, string> = { FREE: 'Free', PRO: 'Team', ENTERPRISE: 'Enterprise' };
const STATUS_COLOR: Record<string, string> = {
  ACTIVE: 'text-accent-emerald bg-accent-emerald/10',
  SUSPENDED: 'text-accent-amber bg-accent-amber/10',
  DELETED: 'text-zinc-500 bg-zinc-500/10',
  PENDING_VERIFICATION: 'text-accent-violet bg-accent-violet/10',
};

export default function AdminPage() {
  return (
    <RoleGuard requireSuperAdmin>
      <AdminPageInner />
    </RoleGuard>
  );
}

function AdminPageInner() {
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

  const loadUsers       = () => api.adminListUsers().then(setUsers).catch(() => {});
  const loadOrgs        = () => api.adminListOrgs().then(setOrgs).catch(() => {});
  const loadTierConfigs = () => api.adminGetTierConfig().then(setTierConfigs).catch(() => {});
  const loadEscalations = () => api.adminListEscalated().then(setEscalations).catch(() => {});

  useEffect(() => { loadUsers(); loadOrgs(); loadTierConfigs(); loadEscalations(); }, []);

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
      addToast({ type: 'success', message: `${TIER_LABEL[tier] ?? tier} limit updated` });
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
  ];

  return (
    <div className="h-full flex flex-col overflow-hidden">
      {/* Page header */}
      <div className="flex items-center gap-3 px-6 py-4 border-b border-white/[0.06] flex-shrink-0">
        <Shield className="h-5 w-5 text-accent-violet" />
        <h1 className="text-lg font-semibold text-white">Super Admin</h1>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 px-6 pt-3 flex-shrink-0 border-b border-white/[0.06]">
        {TABS.map(({ id, label, icon: Icon, count }) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={cn(
              'flex items-center gap-1.5 px-3 py-2 text-sm rounded-t-lg border-b-2 transition-colors',
              tab === id
                ? 'border-accent-violet text-accent-violet'
                : 'border-transparent text-zinc-500 hover:text-zinc-300',
            )}
          >
            <Icon className="h-3.5 w-3.5" />
            {label}
            {count !== undefined && (
              <span className={cn(
                'ml-1 px-1.5 py-0.5 text-[10px] rounded-full font-mono',
                tab === id ? 'bg-accent-violet/20 text-accent-violet' : 'bg-white/[0.06] text-zinc-500',
              )}>{count}</span>
            )}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-auto p-6">

        {/* ── USERS TAB ─────────────────────────────────────────────────── */}
        {tab === 'users' && (
          <div className="space-y-4">
            <div className="flex items-center gap-3">
              <input
                type="text"
                value={userSearch}
                onChange={(e) => setUserSearch(e.target.value)}
                placeholder="Search users…"
                className="flex-1 bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-zinc-300 placeholder:text-zinc-600 focus:outline-none focus:border-accent-violet/40"
              />
              <button
                onClick={() => setShowInviteModal(true)}
                className="flex items-center gap-2 px-4 py-2 bg-accent-violet hover:bg-accent-violet/80 text-white text-sm font-medium rounded-lg transition-colors"
              >
                + Invite User
              </button>
              <button onClick={loadUsers} className="p-2 text-zinc-500 hover:text-zinc-300">
                <RefreshCw className="h-4 w-4" />
              </button>
            </div>

            <div className="rounded-xl border border-white/[0.06] overflow-hidden">
              <table className="w-full text-sm">
                <thead className="bg-surface-1 text-zinc-500 text-xs">
                  <tr>
                    {['Name / Email', 'Status', 'Tier', 'Org Role', 'Actions'].map((h) => (
                      <th key={h} className="px-4 py-3 text-left font-medium">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/[0.04]">
                  {filteredUsers.map((u) => (
                    <tr key={u.id} className="hover:bg-white/[0.02] transition-colors">
                      <td className="px-4 py-3">
                        <p className="text-zinc-200 font-medium">{u.firstName} {u.lastName}</p>
                        <p className="text-zinc-500 text-xs">{u.email}</p>
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn('px-2 py-0.5 rounded-full text-xs font-medium', STATUS_COLOR[u.status] ?? 'text-zinc-400')}>
                          {u.status}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <select
                          value={u.tier}
                          onChange={(e) => changeUserTier(u.id, e.target.value)}
                          className="bg-surface-1 border border-white/[0.08] rounded px-2 py-1 text-xs text-zinc-300 focus:outline-none"
                        >
                          {Object.entries(TIER_LABEL).map(([v, l]) => (
                            <option key={v} value={v}>{l}</option>
                          ))}
                        </select>
                      </td>
                      <td className="px-4 py-3 text-zinc-400 text-xs">{u.orgRole ?? '—'}</td>
                      <td className="px-4 py-3">
                        <button
                          onClick={() => toggleUserStatus(u)}
                          className={cn(
                            'px-3 py-1 rounded text-xs font-medium transition-colors',
                            u.status === 'SUSPENDED'
                              ? 'bg-accent-emerald/10 text-accent-emerald hover:bg-accent-emerald/20'
                              : 'bg-accent-amber/10 text-accent-amber hover:bg-accent-amber/20',
                          )}
                        >
                          {u.status === 'SUSPENDED' ? 'Restore' : 'Suspend'}
                        </button>
                      </td>
                    </tr>
                  ))}
                  {filteredUsers.length === 0 && (
                    <tr><td colSpan={5} className="px-4 py-8 text-center text-zinc-600">No users found</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* ── ORGS TAB ──────────────────────────────────────────────────── */}
        {tab === 'orgs' && (
          <div className="space-y-4">
            <div className="flex justify-end">
              <button onClick={loadOrgs} className="p-2 text-zinc-500 hover:text-zinc-300">
                <RefreshCw className="h-4 w-4" />
              </button>
            </div>
            <div className="rounded-xl border border-white/[0.06] overflow-hidden">
              <table className="w-full text-sm">
                <thead className="bg-surface-1 text-zinc-500 text-xs">
                  <tr>
                    {['Org', 'Tier', 'Status', 'Run Limit', 'API Keys', 'Usage', 'Actions'].map((h) => (
                      <th key={h} className="px-4 py-3 text-left font-medium">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/[0.04]">
                  {orgs.map((o) => {
                    const limitEditing = editingLimits[o.id];
                    const displayLimit = o.monthlyRunLimitOverride ?? '(default)';
                    return (
                      <tr key={o.id} className="hover:bg-white/[0.02] transition-colors">
                        <td className="px-4 py-3">
                          <p className="text-zinc-200 font-medium">{o.name}</p>
                          <p className="text-zinc-500 text-xs truncate max-w-[160px]">{o.domainPatterns || '—'}</p>
                        </td>
                        <td className="px-4 py-3">
                          <select
                            value={o.tier}
                            onChange={(e) => changeOrgTier(o.id, e.target.value)}
                            className="bg-surface-1 border border-white/[0.08] rounded px-2 py-1 text-xs text-zinc-300 focus:outline-none"
                          >
                            <option value="PRO">Team</option>
                            <option value="ENTERPRISE">Enterprise</option>
                          </select>
                        </td>
                        <td className="px-4 py-3">
                          <span className={cn('px-2 py-0.5 rounded-full text-xs font-medium', STATUS_COLOR[o.status] ?? 'text-zinc-400')}>
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
                              className="w-20 bg-surface-1 border border-white/[0.08] rounded px-2 py-1 text-xs text-zinc-300 focus:outline-none focus:border-accent-violet/40"
                            />
                            {limitEditing !== undefined && (
                              <button onClick={() => saveOrgRunLimit(o.id)} className="p-1 text-accent-emerald hover:text-accent-emerald/80">
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
                              o.apiKeysProvided ? 'bg-accent-emerald' : 'bg-white/[0.10]',
                            )}
                            title={o.apiKeysProvided ? 'API keys provided (Enterprise = unlimited)' : 'No API keys'}
                          >
                            <span className={cn(
                              'absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform',
                              o.apiKeysProvided ? 'translate-x-4' : 'translate-x-0.5',
                            )} />
                          </button>
                        </td>
                        <td className="px-4 py-3 text-zinc-400 text-xs">
                          {o.monthlyRunCount} runs
                        </td>
                        <td className="px-4 py-3">
                          <button
                            onClick={() => toggleOrgStatus(o)}
                            className={cn(
                              'px-3 py-1 rounded text-xs font-medium transition-colors',
                              o.status === 'SUSPENDED'
                                ? 'bg-accent-emerald/10 text-accent-emerald hover:bg-accent-emerald/20'
                                : 'bg-accent-amber/10 text-accent-amber hover:bg-accent-amber/20',
                            )}
                          >
                            {o.status === 'SUSPENDED' ? 'Restore' : 'Suspend'}
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                  {orgs.length === 0 && (
                    <tr><td colSpan={7} className="px-4 py-8 text-center text-zinc-600">No organisations</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* ── TIER CONFIG TAB ───────────────────────────────────────────── */}
        {tab === 'tiers' && (
          <div className="space-y-4 max-w-lg">
            <p className="text-sm text-zinc-500">
              Set the default monthly run limits for each tier. Changes take effect immediately for new runs.
            </p>
            <div className="rounded-xl border border-white/[0.06] overflow-hidden">
              <table className="w-full text-sm">
                <thead className="bg-surface-1 text-zinc-500 text-xs">
                  <tr>
                    <th className="px-4 py-3 text-left font-medium">Tier</th>
                    <th className="px-4 py-3 text-left font-medium">Monthly Limit</th>
                    <th className="px-4 py-3 text-left font-medium">Note</th>
                    <th className="px-4 py-3"></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/[0.04]">
                  {tierConfigs.map((tc) => {
                    const editing = editingTiers[tc.tier];
                    return (
                      <tr key={tc.tier} className="hover:bg-white/[0.02]">
                        <td className="px-4 py-3">
                          <span className="font-medium text-zinc-200">{TIER_LABEL[tc.tier] ?? tc.tier}</span>
                          {tc.isFixed && <span className="ml-2 text-xs text-zinc-600">(default fixed)</span>}
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-2">
                            <input
                              type="number"
                              value={editing !== undefined ? editing : tc.monthlyRunLimit}
                              onChange={(e) => setEditingTiers((p) => ({ ...p, [tc.tier]: e.target.value }))}
                              className="w-24 bg-surface-1 border border-white/[0.08] rounded px-2 py-1.5 text-sm text-zinc-300 focus:outline-none focus:border-accent-violet/40"
                            />
                            {editing !== undefined && (
                              <button onClick={() => saveTierLimit(tc.tier)} className="p-1.5 bg-accent-emerald/15 text-accent-emerald rounded-lg hover:bg-accent-emerald/25">
                                <Check className="h-3.5 w-3.5" />
                              </button>
                            )}
                          </div>
                        </td>
                        <td className="px-4 py-3 text-zinc-500 text-xs">
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
            <p className="text-xs text-zinc-600">
              Enterprise orgs with <strong className="text-zinc-400">API Keys Provided</strong> toggled on always get unlimited runs regardless of this value.
              Per-org overrides (set in the Orgs tab) take precedence over these defaults.
            </p>
          </div>
        )}

        {/* ── ESCALATIONS TAB ───────────────────────────────────────────── */}
        {tab === 'escalations' && (
          <div className="space-y-4">
            <p className="text-sm text-zinc-500">
              Invitations escalated because the invitee's email domain doesn't match the org's registered domains.
            </p>
            {escalations.length === 0 ? (
              <div className="text-center py-12 text-zinc-600">No pending escalations</div>
            ) : (
              <div className="space-y-3">
                {escalations.map((inv) => (
                  <div key={inv.id} className="flex items-center justify-between p-4 rounded-xl bg-surface-1 border border-white/[0.06]">
                    <div>
                      <p className="text-zinc-200 text-sm font-medium">{inv.invitedEmail}</p>
                      <p className="text-zinc-500 text-xs mt-0.5">Org: {inv.orgId} · Role: {inv.orgRole}</p>
                    </div>
                    <div className="flex gap-2">
                      <button
                        onClick={() => approveEsc(inv.id)}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-accent-emerald/10 text-accent-emerald text-xs font-medium hover:bg-accent-emerald/20 transition-colors"
                      >
                        <Check className="h-3.5 w-3.5" /> Approve
                      </button>
                      <button
                        onClick={() => rejectEsc(inv.id)}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-accent-rose/10 text-accent-rose text-xs font-medium hover:bg-accent-rose/20 transition-colors"
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
      </div>

      {showInviteModal && (
        <AdminInviteModal
          onClose={() => setShowInviteModal(false)}
          onSuccess={loadUsers}
        />
      )}
    </div>
  );
}
