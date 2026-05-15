import {useEffect, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar, SectionHeader, IconButton} from '@/signal/components/Chrome';
import {Feed, Button, Card, Chip, StatusPill, Row} from '@/signal/components/Primitives';
import {ProjectTabs} from './ProjectTabs';
import {Plus, Share} from '@/signal/icons/icons';
import {useAuthStore} from '@/stores/useAuthStore';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import * as api from '@/lib/api';

const ROLE_OPTIONS = ['PROJECT_VIEWER', 'PROJECT_DEVELOPER', 'PROJECT_ADMIN'];

export default function ProjectMembersPage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const user = useAuthStore((s) => s.user);
  const project = useProjectMeta(id);
  const [members, setMembers] = useState<api.ProjectMemberDto[] | null>(null);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState('PROJECT_DEVELOPER');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');

  async function refresh() {
    if (!id) return;
    const m = await api.getProjectMembers(id).catch(() => [] as api.ProjectMemberDto[]);
    setMembers(m);
  }

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  async function invite() {
    const email = inviteEmail.trim();
    if (!email) return;
    setBusy(true);
    setErr('');
    setMsg('');
    try {
      // Use the cross-org invite endpoint for project membership.
      const resp = await fetch(`/api/projects/${id}/invites`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`,
        },
        body: JSON.stringify({email, projectRole: inviteRole}),
      });
      if (!resp.ok) throw new Error(`Invite failed (${resp.status}).`);
      setMsg(`Invite sent to ${email}.`);
      setInviteEmail('');
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Invite failed.');
    } finally {
      setBusy(false);
    }
  }

  async function removeOne(userId: string) {
    if (!window.confirm('Remove this member from the project?')) return;
    try {
      await api.removeProjectMember(id, userId);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Remove failed.');
    }
  }

  const canManage = user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN';

  return (
    <>
      <TopBar
        title='Members & sharing'
        sub={`${project?.name || 'project'} · ${members?.length ?? 0} member${members?.length === 1 ? '' : 's'}`}
        back
        backTo={`/projects/${id}`}
        actions={
          <IconButton ariaLabel='Add'>
            <Plus size={18} />
          </IconButton>
        }
      />
      <Feed>
        <ProjectTabs active='members' />

        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{err}</div>}
        {msg && <div style={{background: 'var(--signal-mint-soft)', color: 'var(--signal-mint-text-on-light)', border: '1px solid var(--signal-mint)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{msg}</div>}

        <SectionHeader>Team · {members?.length ?? 0}</SectionHeader>
        {members === null ? (
          <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>Loading members…</p></Card>
        ) : members.length === 0 ? (
          <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>No members yet.</p></Card>
        ) : (
          members.map((m) => {
            const mAny = m as unknown as {id?: string; userId?: string; email?: string; firstName?: string; lastName?: string; role?: string; projectRole?: string; external?: boolean; lastSeen?: string};
            const isMe = mAny.userId === user?.id;
            const av = ((mAny.firstName?.[0] ?? '') + (mAny.lastName?.[0] ?? '') || (mAny.email?.slice(0, 2) ?? 'EX')).toUpperCase();
            const role = mAny.projectRole || mAny.role || 'PROJECT_DEVELOPER';
            return (
              <div key={mAny.id || mAny.userId} className='signal-row'>
                <div
                  style={{
                    width: 36,
                    height: 36,
                    borderRadius: '50%',
                    background: mAny.external ? 'var(--signal-card-elev)' : 'linear-gradient(135deg, var(--signal-y), var(--signal-coral))',
                    border: mAny.external ? '1px solid var(--signal-hairline)' : 'none',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontWeight: 600,
                    fontSize: 13,
                    color: 'var(--signal-ink)',
                  }}
                >
                  {av}
                </div>
                <div>
                  <div className='row-title'>
                    {(mAny.firstName || mAny.email?.split('@')[0]) ?? '—'} {mAny.lastName ?? ''}
                    {isMe && <span style={{color: 'var(--signal-text-dim)', fontWeight: 400}}> · you</span>}
                    {mAny.external && (
                      <span style={{display: 'inline-block', marginLeft: 6, padding: '1px 7px', background: 'var(--signal-lavender-soft)', color: '#5443a8', fontSize: 9, fontWeight: 700, letterSpacing: '0.06em', borderRadius: 3}}>EXT</span>
                    )}
                  </div>
                  <div className='row-meta'>
                    <span>{mAny.email}</span>
                    {mAny.lastSeen && (
                      <>
                        <span>·</span>
                        <span>last seen {new Date(mAny.lastSeen).toLocaleDateString()}</span>
                      </>
                    )}
                  </div>
                </div>
                {role === 'OWNER' || role === 'PROJECT_ADMIN' ? (
                  <span className='signal-status-pill' style={{background: 'var(--signal-ink)', color: 'var(--signal-bg)'}}>
                    {role}
                  </span>
                ) : (
                  <Chip>{role}</Chip>
                )}
                {canManage && !isMe && mAny.userId && (
                  <Button kind='ghost' sm style={{color: 'var(--signal-coral-text)', marginLeft: 4}} onClick={() => removeOne(mAny.userId!)}>×</Button>
                )}
              </div>
            );
          })
        )}

        {canManage && (
          <Card style={{marginTop: 14}}>
            <div style={{fontSize: 14.5, fontWeight: 600, marginBottom: 10}}>Invite teammate</div>
            <input
              type='email'
              placeholder='teammate@company.com'
              value={inviteEmail}
              onChange={(e) => setInviteEmail(e.target.value)}
              style={{
                width: '100%',
                background: 'var(--signal-card-elev)',
                border: '1px solid var(--signal-hairline)',
                borderRadius: 8,
                padding: '10px 12px',
                fontSize: 13,
                color: 'var(--signal-ink)',
                outline: 'none',
                marginBottom: 8,
              }}
            />
            <div style={{display: 'flex', gap: 6, marginBottom: 10, flexWrap: 'wrap'}}>
              {ROLE_OPTIONS.map((r) => (
                <Chip key={r} kind={r === inviteRole ? 'active' : 'default'} onClick={() => setInviteRole(r)}>
                  {r.replace('PROJECT_', '').toLowerCase()}
                </Chip>
              ))}
            </div>
            <Button kind='primary' block disabled={busy || !inviteEmail.trim()} onClick={invite}>
              {busy ? 'Inviting…' : 'Send invite'}
            </Button>
          </Card>
        )}

        <SectionHeader>Share links</SectionHeader>
        <Row dot='lavender' title='Run-report share' meta={<span>Read-only, expires 30d</span>} trailing={<Button kind='secondary' sm><Share size={14} /> New</Button>} />
        <Row dot='lavender' title='Architecture share' meta={<span>Read-only, expires 60d</span>} trailing={<Button kind='secondary' sm><Share size={14} /> New</Button>} />
        <p style={{fontSize: 12, color: 'var(--signal-text-mute)', textAlign: 'center', marginTop: 10}}>
          Share links create public, read-only URLs scoped to specific run reports or architecture snapshots.
        </p>
      </Feed>
    </>
  );
}
