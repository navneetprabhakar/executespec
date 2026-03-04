import { useEffect, useState } from 'react';
import { useAuthStore } from '@/stores/useAuthStore';
import RoleGuard from '@/components/auth/RoleGuard';
import * as api from '@/lib/api';

export default function OrgSettingsPage() {
  const { user } = useAuthStore();
  const [org, setOrg] = useState<api.OrgDto | null>(null);
  const [members, setMembers] = useState<api.OrgMemberDto[]>([]);
  const [invitations, setInvitations] = useState<api.OrgInvitation[]>([]);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState('DEVELOPER');
  const [inviteMsg, setInviteMsg] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.getMyOrg().catch(() => null),
      api.getOrgMembers().catch(() => []),
      api.getOrgInvitations().catch(() => []),
    ]).then(([o, m, i]) => {
      setOrg(o);
      setMembers(m);
      setInvitations(i);
    }).finally(() => setLoading(false));
  }, []);

  const handleInvite = async (e: React.FormEvent) => {
    e.preventDefault();
    setInviteMsg('');
    try {
      const resp = await api.inviteMember({ email: inviteEmail, orgRole: inviteRole });
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
    setMembers(m => m.filter(x => x.userId !== userId));
  };

  const handleSuspendMember = async (userId: string, currentlySuspended: boolean) => {
    const action = currentlySuspended ? 'restore' : 'suspend';
    if (!confirm(`${action.charAt(0).toUpperCase() + action.slice(1)} this member?`)) return;
    try {
      await api.orgSuspendMember(userId, !currentlySuspended);
      // Reload members to reflect new status (OrgMemberDto doesn't carry status directly)
      const updated = await api.getOrgMembers().catch(() => members);
      setMembers(updated);
    } catch (err: any) {
      alert('Error: ' + err.message);
    }
  };

  if (loading) return <div className="p-8 text-slate-400">Loading…</div>;
  if (!org) return (
    <div className="p-8 text-center">
      <h2 className="text-xl font-bold text-white mb-2">No org yet</h2>
      <p className="text-slate-400 mb-4">You don't belong to an org or haven't created one.</p>
    </div>
  );

  return (
    <RoleGuard requireOrgAdmin>
      <div className="h-full overflow-y-auto p-6 space-y-8">
        {/* Org overview */}
        <div>
          <h1 className="text-2xl font-bold text-white">{org.name}</h1>
          <p className="text-slate-400 text-sm mt-1">
            Tier: <span className="text-purple-400 font-semibold">{org.tier}</span> ·
            Runs this month: <span className="text-white">{org.monthlyRunCount}</span> ·
            Domains: <span className="text-slate-300">{org.domainPatterns}</span>
          </p>
        </div>

        {/* Members */}
        <div>
          <h2 className="text-lg font-semibold text-white mb-3">Members ({members.length})</h2>
          <div className="rounded-xl border border-slate-700 bg-[#1a1f2e] overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-slate-800 text-slate-400">
                <tr>
                  <th className="px-4 py-3 text-left">Name</th>
                  <th className="px-4 py-3 text-left">Email</th>
                  <th className="px-4 py-3 text-left">Role</th>
                  <th className="px-4 py-3 text-left">Joined</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-700">
                {members.map(m => (
                  <tr key={m.userId} className="hover:bg-slate-800/40">
                    <td className="px-4 py-3 text-white">{m.firstName} {m.lastName}</td>
                    <td className="px-4 py-3 text-slate-400">{m.email}</td>
                    <td className="px-4 py-3">
                      <span className="rounded-full bg-purple-900/40 px-2 py-0.5 text-xs text-purple-300">
                        {m.orgRole}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-slate-500">{new Date(m.joinedAt).toLocaleDateString()}</td>
                    <td className="px-4 py-3 flex gap-2">
                      {m.userId !== user?.id && (
                        <>
                          <button onClick={() => handleRemoveMember(m.userId)}
                            className="text-xs text-red-400 hover:text-red-300">Remove</button>
                          <button onClick={() => handleSuspendMember(m.userId, false)}
                            className="text-xs text-amber-400 hover:text-amber-300">Suspend</button>
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
          <h2 className="text-lg font-semibold text-white mb-3">Invite a member</h2>
          <form onSubmit={handleInvite} className="flex gap-3 flex-wrap">
            <input type="email" value={inviteEmail} onChange={e => setInviteEmail(e.target.value)} required
              placeholder="colleague@company.com"
              className="flex-1 min-w-[200px] rounded-lg border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none" />
            <select value={inviteRole} onChange={e => setInviteRole(e.target.value)}
              className="rounded-lg border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none">
              <option value="DEVELOPER">Developer</option>
              <option value="ORG_ADMIN">Org Admin</option>
              <option value="BILLING_ADMIN">Billing Admin</option>
            </select>
            <button type="submit"
              className="rounded-lg bg-purple-600 px-4 py-2 font-semibold text-white hover:bg-purple-500">
              Send invite
            </button>
          </form>
          {inviteMsg && <p className="mt-2 text-sm text-slate-400">{inviteMsg}</p>}
        </div>

        {/* Pending invitations */}
        {invitations.length > 0 && (
          <div>
            <h2 className="text-lg font-semibold text-white mb-3">Pending invitations</h2>
            <div className="space-y-2">
              {invitations.map(inv => (
                <div key={inv.id} className="flex items-center justify-between rounded-lg border border-slate-700 bg-[#1a1f2e] px-4 py-3">
                  <div>
                    <span className="text-white text-sm">{inv.invitedEmail}</span>
                    <span className="ml-2 text-xs text-slate-500">{inv.orgRole}</span>
                  </div>
                  <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${
                    inv.status === 'ESCALATED' ? 'bg-amber-900/40 text-amber-400' :
                    inv.status === 'ACCEPTED' ? 'bg-green-900/40 text-green-400' :
                    'bg-slate-700 text-slate-400'}`}>
                    {inv.status}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </RoleGuard>
  );
}
