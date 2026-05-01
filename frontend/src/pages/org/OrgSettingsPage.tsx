import {useEffect, useState} from 'react';
import {useAuthStore} from '@/stores/useAuthStore';
import RoleGuard from '@/components/auth/RoleGuard';
import * as api from '@/lib/api';
import SsoConfigCard from '@/components/settings/SsoConfigCard';
import OrgStandardsManager from '@/components/settings/OrgStandardsManager';

// ── Create Organization Form ───────────────────────────────────────────────

function CreateOrgForm({onCreated}: {onCreated: () => void}) {
  const {user, loadMe} = useAuthStore();
  const [name, setName] = useState('');
  const [domains, setDomains] = useState('');
  const [adminEmail, setAdminEmail] = useState(user?.email ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  // Tier-coherence: PRO + TEAM + ENTERPRISE can create orgs; FREE cannot.
  const canCreate =
    ['ENTERPRISE', 'TEAM', 'PRO'].includes(user?.tier ?? '') || user?.globalRole === 'SUPER_ADMIN';

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !domains.trim() || !adminEmail.trim()) {
      setError('All fields are required');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const domainList = domains
        .split(',')
        .map((d) => d.trim().toLowerCase())
        .filter(Boolean);
      await api.createOrg({name: name.trim(), domains: domainList, adminEmail: adminEmail.trim()});
      // Refresh user state so orgId is populated
      await loadMe();
      onCreated();
    } catch (err: any) {
      setError(err.message ?? 'Failed to create organization');
    } finally {
      setSaving(false);
    }
  };

  if (!canCreate) {
    return (
      <div className='p-8 text-center'>
        <h2 className='text-xl font-bold text-primary mb-2'>Enterprise Feature</h2>
        <p className='text-muted mb-4'>
          Organization management is available on the{' '}
          <span className='text-[var(--text-secondary)] font-semibold'>Enterprise</span> plan.
          Contact us to learn more.
        </p>
        <p className='text-muted text-sm'>
          Your current plan:{' '}
          <span className='text-primary font-semibold'>{user?.tier ?? 'FREE'}</span>
        </p>
      </div>
    );
  }

  return (
    <div className='p-8 max-w-lg mx-auto'>
      <h2 className='text-xl font-bold text-primary mb-2'>Create Organization</h2>
      <p className='text-muted text-sm mb-6'>
        Set up your organization to manage team members, integrations, and AI provider keys.
      </p>
      <form onSubmit={handleCreate} className='space-y-4'>
        <div>
          <label className='text-sm text-secondary mb-1 block'>Organization Name</label>
          <input
            type='text'
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            placeholder='Acme Corp'
            className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none'
          />
        </div>
        <div>
          <label className='text-sm text-secondary mb-1 block'>Email Domains</label>
          <input
            type='text'
            value={domains}
            onChange={(e) => setDomains(e.target.value)}
            required
            placeholder='acme.com, acmecorp.com'
            className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none'
          />
          <p className='text-xs text-muted mt-1'>
            Comma-separated domains. Users with matching emails can be invited.
          </p>
        </div>
        <div>
          <label className='text-sm text-secondary mb-1 block'>Admin Email</label>
          <input
            type='email'
            value={adminEmail}
            onChange={(e) => setAdminEmail(e.target.value)}
            required
            placeholder='admin@acme.com'
            className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none'
          />
          <p className='text-xs text-muted mt-1'>
            Primary contact email for the organization admin.
          </p>
        </div>

        {error && <p className='text-sm text-accent-red'>{error}</p>}

        <button
          type='submit'
          disabled={saving}
          className='w-full rounded-sm bg-accent-blue px-4 py-2.5 font-semibold text-primary hover:bg-accent-blue disabled:opacity-50 transition-colors'
        >
          {saving ? 'Creating...' : 'Create Organization'}
        </button>
      </form>
    </div>
  );
}

// ── Main OrgSettingsPage ───────────────────────────────────────────────────

export default function OrgSettingsPage() {
  const {user} = useAuthStore();
  const [org, setOrg] = useState<api.OrgDto | null>(null);
  const [members, setMembers] = useState<api.OrgMemberDto[]>([]);
  const [invitations, setInvitations] = useState<api.OrgInvitation[]>([]);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState('DEVELOPER');
  const [inviteMsg, setInviteMsg] = useState('');
  const [loading, setLoading] = useState(true);

  const loadOrgData = () => {
    setLoading(true);
    Promise.all([
      api.getMyOrg().catch(() => null),
      api.getOrgMembers().catch(() => []),
      api.getOrgInvitations().catch(() => []),
    ])
      .then(([o, m, i]) => {
        setOrg(o);
        setMembers(m);
        setInvitations(i);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadOrgData();
  }, []);

  const handleInvite = async (e: React.FormEvent) => {
    e.preventDefault();
    setInviteMsg('');
    try {
      const resp = await api.inviteMember({email: inviteEmail, orgRole: inviteRole});
      setInviteMsg(resp.message);
      setInviteEmail('');
      const updated = await api.getOrgInvitations().catch(() => []);
      setInvitations(updated);
    } catch (err: any) {
      setInviteMsg('Error: ' + (err.message ?? 'Failed to invite'));
    }
  };

  const handleRemoveMember = async (userId: string) => {
    if (!confirm('Remove this member from the org?')) return;
    await api.removeMember(userId);
    setMembers((m) => m.filter((x) => x.userId !== userId));
  };

  const handleSuspendMember = async (userId: string, currentlySuspended: boolean) => {
    const action = currentlySuspended ? 'restore' : 'suspend';
    if (!confirm(`${action.charAt(0).toUpperCase() + action.slice(1)} this member?`)) return;
    try {
      await api.orgSuspendMember(userId, !currentlySuspended);
      const updated = await api.getOrgMembers().catch(() => members);
      setMembers(updated);
    } catch (err: any) {
      alert('Error: ' + err.message);
    }
  };

  const handleResendInvitation = async (id: string) => {
    try {
      const resp = await api.resendInvitation(id);
      setInviteMsg(resp.message);
      const updated = await api.getOrgInvitations().catch(() => invitations);
      setInvitations(updated);
    } catch (err: any) {
      setInviteMsg('Error: ' + (err.message ?? 'Failed to resend'));
    }
  };

  if (loading) return <div className='p-8 text-muted'>Loading…</div>;

  // Show Create Org form if user has no org
  if (!org) return <CreateOrgForm onCreated={loadOrgData} />;

  return (
    <RoleGuard requireOrgAdmin>
      <div className='h-full overflow-y-auto p-6 space-y-8'>
        {/* Org overview */}
        <div>
          <h1 className='text-2xl font-bold text-primary'>{org.name}</h1>
          <p className='text-muted text-sm mt-1'>
            Tier: <span className='text-[var(--text-secondary)] font-semibold'>{org.tier}</span> ·
            Runs this month: <span className='text-primary'>{org.monthlyRunCount}</span> · Domains:{' '}
            <span className='text-secondary'>{org.domainPatterns}</span>
            {org.adminEmail && (
              <>
                {' '}
                · Admin: <span className='text-secondary'>{org.adminEmail}</span>
              </>
            )}
          </p>
        </div>

        {/* Members */}
        <div>
          <h2 className='text-lg font-semibold text-primary mb-3'>Members ({members.length})</h2>
          <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] overflow-hidden'>
            <table className='w-full text-sm'>
              <thead className='bg-[var(--gray-300)] text-muted'>
                <tr>
                  <th className='px-4 py-3 text-left'>Name</th>
                  <th className='px-4 py-3 text-left'>Email</th>
                  <th className='px-4 py-3 text-left'>Role</th>
                  <th className='px-4 py-3 text-left'>Joined</th>
                  <th className='px-4 py-3' />
                </tr>
              </thead>
              <tbody className='divide-y divide-[var(--border-default)]'>
                {members.map((m) => (
                  <tr key={m.userId} className='hover:bg-[var(--gray-300)]/40'>
                    <td className='px-4 py-3 text-primary'>
                      {m.firstName} {m.lastName}
                    </td>
                    <td className='px-4 py-3 text-muted'>{m.email}</td>
                    <td className='px-4 py-3'>
                      <span className='rounded-sm bg-accent-blue/20 px-2 py-0.5 text-xs text-accent-blue'>
                        {m.orgRole}
                      </span>
                    </td>
                    <td className='px-4 py-3 text-muted'>
                      {new Date(m.joinedAt).toLocaleDateString()}
                    </td>
                    <td className='px-4 py-3 flex gap-2'>
                      {m.userId !== user?.id && (
                        <>
                          <button
                            onClick={() => handleRemoveMember(m.userId)}
                            className='text-xs text-accent-red hover:text-accent-red'
                          >
                            Remove
                          </button>
                          <button
                            onClick={() => handleSuspendMember(m.userId, false)}
                            className='text-xs text-accent-amber hover:text-accent-amber'
                          >
                            Suspend
                          </button>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* Invite */}
        <div>
          <h2 className='text-lg font-semibold text-primary mb-3'>Invite a member</h2>
          <form onSubmit={handleInvite} className='flex gap-3 flex-wrap'>
            <input
              type='email'
              value={inviteEmail}
              onChange={(e) => setInviteEmail(e.target.value)}
              required
              placeholder='colleague@company.com'
              className='flex-1 min-w-[200px] rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none'
            />
            <select
              value={inviteRole}
              onChange={(e) => setInviteRole(e.target.value)}
              className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none'
            >
              <option value='DEVELOPER'>Developer</option>
              <option value='ORG_ADMIN'>Org Admin</option>
              <option value='BILLING_ADMIN'>Billing Admin</option>
            </select>
            <button
              type='submit'
              className='rounded-sm bg-accent-blue px-4 py-2 font-semibold text-primary hover:bg-accent-blue'
            >
              Send invite
            </button>
          </form>
          {inviteMsg && <p className='mt-2 text-sm text-muted'>{inviteMsg}</p>}
        </div>

        {/* SSO config (M-004 Wave 2) */}
        <div>
          <h2 className='text-lg font-semibold text-primary mb-3'>Single sign-on</h2>
          <SsoConfigCard />
        </div>

        {/* Engineering standards CRUD (M-024 Wave 2) */}
        <div>
          <h2 className='text-lg font-semibold text-primary mb-3'>Engineering standards</h2>
          <OrgStandardsManager orgId={org.id} />
        </div>

        {/* Pending invitations */}
        {invitations.length > 0 && (
          <div>
            <h2 className='text-lg font-semibold text-primary mb-3'>Pending invitations</h2>
            <div className='space-y-2'>
              {invitations.map((inv) => (
                <div
                  key={inv.id}
                  className='flex items-center justify-between rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] px-4 py-3'
                >
                  <div>
                    <span className='text-primary text-sm'>{inv.invitedEmail}</span>
                    <span className='ml-2 text-xs text-muted'>{inv.orgRole}</span>
                  </div>
                  <div className='flex items-center gap-2'>
                    <span
                      className={`rounded-sm px-2 py-0.5 text-xs font-semibold ${
                        inv.status === 'ESCALATED'
                          ? 'bg-accent-amber/20 text-accent-amber'
                          : inv.status === 'ACCEPTED'
                            ? 'bg-accent-blue/20 text-[var(--status-success)]'
                            : inv.status === 'EXPIRED'
                              ? 'bg-accent-red/20 text-accent-red'
                              : 'bg-[var(--gray-400)] text-muted'
                      }`}
                    >
                      {inv.status}
                    </span>
                    {(inv.status === 'PENDING' || inv.status === 'EXPIRED') && (
                      <button
                        onClick={() => handleResendInvitation(inv.id)}
                        className='text-xs text-[var(--text-secondary)] hover:text-accent-blue font-medium'
                      >
                        Resend
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </RoleGuard>
  );
}
