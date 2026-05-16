import {useEffect, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar, SectionHeader, IconButton} from '@/signal/components/Chrome';
import {Feed, Button, Card, Chip, StatusPill, Row} from '@/signal/components/Primitives';
import {LoadingState, SkeletonRow} from '@/signal/components/Loaders';
import {ConfirmModal} from '@/signal/components/Modal';
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
  // V175 share-links — public read-only project snapshots.
  const [shareLinks, setShareLinks] = useState<Array<Record<string, unknown>>>([]);
  const [shareBusy, setShareBusy] = useState(false);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState('PROJECT_DEVELOPER');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [confirmRemove, setConfirmRemove] = useState<{userId: string; label: string} | null>(null);
  const [removeBusy, setRemoveBusy] = useState(false);
  // V198 — org-level external sharing flag. When false the "Invite teammate"
  // card is hidden + replaced with a one-line explainer. SUPER_ADMIN bypasses
  // the flag at the API level so we still show the form to them.
  const [externalSharingEnabled, setExternalSharingEnabled] = useState<boolean | null>(null);

  async function refresh() {
    if (!id) return;
    const m = await api.getProjectMembers(id).catch(() => [] as api.ProjectMemberDto[]);
    setMembers(m);
    void refreshShareLinks();
  }

  async function refreshShareLinks() {
    if (!id) return;
    try {
      const r = await fetch(`/api/projects/${id}/share-links`, {
        headers: {Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`},
      });
      if (!r.ok) {
        setShareLinks([]);
        return;
      }
      const data = await r.json();
      setShareLinks(Array.isArray(data) ? data : (data.content || data.links || []));
    } catch {
      setShareLinks([]);
    }
  }

  async function createShareLink(kind: string) {
    if (!id) return;
    setShareBusy(true);
    setErr('');
    try {
      const r = await fetch(`/api/projects/${id}/share-links`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`,
        },
        body: JSON.stringify({kind, expiresInDays: 30}),
      });
      if (!r.ok) throw new Error(`Create failed (${r.status}).`);
      setMsg('Share link created.');
      await refreshShareLinks();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Create failed.');
    } finally {
      setShareBusy(false);
    }
  }

  async function deleteShareLink(linkId: string) {
    if (!id) return;
    try {
      await fetch(`/api/projects/${id}/share-links/${linkId}`, {
        method: 'DELETE',
        headers: {Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`},
      });
      await refreshShareLinks();
      setMsg('Share link revoked.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Revoke failed.');
    }
  }

  useEffect(() => {
    void refresh();
    // V198 — fetch the org-level external sharing flag so we can hide the
    // invite UI when the org hasn't opted in. SUPER_ADMIN bypasses at the
    // API level, so we always render for them too.
    api.getMyOrg()
      .then((o) => setExternalSharingEnabled(!!o.externalSharingEnabled))
      .catch(() => setExternalSharingEnabled(false));
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

  async function performRemove(userId: string) {
    setRemoveBusy(true);
    try {
      await api.removeProjectMember(id, userId);
      await refresh();
      setMsg('Member removed.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Remove failed.');
    } finally {
      setRemoveBusy(false);
      setConfirmRemove(null);
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
          <LoadingState label="Loading members…" />
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
                  <Button
                    kind='ghost'
                    sm
                    style={{color: 'var(--signal-coral-text)', marginLeft: 4}}
                    onClick={() => setConfirmRemove({
                      userId: mAny.userId!,
                      label: ((mAny.firstName || mAny.email?.split('@')[0]) ?? '') + (mAny.lastName ? ` ${mAny.lastName}` : '') || mAny.email || 'this member',
                    })}
                  >
                    ×
                  </Button>
                )}
              </div>
            );
          })
        )}

        {canManage && (() => {
          // V198 — gate the invite UI on the org's external_sharing flag.
          // SUPER_ADMIN bypasses (their role bit is checked server-side too).
          const isSuperAdmin = user?.globalRole === 'SUPER_ADMIN';
          const canInviteExternals = isSuperAdmin || externalSharingEnabled === true;
          if (!canInviteExternals) {
            return (
              <Card style={{marginTop: 14, background: 'var(--signal-lavender-soft)', borderColor: 'transparent'}}>
                <div style={{fontSize: 11, fontWeight: 700, color: '#5443a8', letterSpacing: '0.14em', textTransform: 'uppercase', marginBottom: 6}}>
                  External sharing is off
                </div>
                <div style={{fontSize: 13, color: '#5443a8', lineHeight: 1.55}}>
                  Your org admin has not enabled inviting people from outside the org. Enable it in
                  {' '}
                  <a href='/org/settings' style={{color: '#5443a8', textDecoration: 'underline'}}>Org settings → External sharing</a>
                  {' '}
                  to invite collaborators using any email address.
                </div>
              </Card>
            );
          }
          return (
            <Card style={{marginTop: 14}}>
              <div style={{fontSize: 14.5, fontWeight: 600, marginBottom: 4}}>Invite teammate</div>
              <div style={{fontSize: 12, color: 'var(--signal-text-dim)', marginBottom: 10}}>
                Any email works — gmail, outlook, or a company address. The recipient gets a magic link.
              </div>
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
          );
        })()}

        <SectionHeader>Share links · {shareLinks.length}</SectionHeader>
        {shareLinks.length === 0 ? (
          <Card><p style={{fontSize: 13, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
            No share links yet. Create one to give people read-only access without an account.
          </p></Card>
        ) : (
          shareLinks.map((s) => {
            const sAny = s as unknown as {id?: string; kind?: string; token?: string; expiresAt?: string; createdAt?: string};
            const url = sAny.token ? `${window.location.origin}/shared/${sAny.token}` : '';
            return (
              <Row
                key={sAny.id}
                dot='lavender'
                title={sAny.kind || 'Share link'}
                meta={
                  <>
                    <span className='mono' style={{maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}>{url}</span>
                    {sAny.expiresAt && (<><span>·</span><span>expires {new Date(sAny.expiresAt).toLocaleDateString()}</span></>)}
                  </>
                }
                trailing={
                  <div style={{display: 'flex', gap: 4}}>
                    <Button kind='secondary' sm onClick={() => void navigator.clipboard.writeText(url).then(() => setMsg('Link copied.'))}>Copy</Button>
                    <Button kind='ghost' sm style={{color: 'var(--signal-coral-text)'}} onClick={() => sAny.id && deleteShareLink(sAny.id)}>Revoke</Button>
                  </div>
                }
              />
            );
          })
        )}
        <div style={{display: 'flex', gap: 8, marginTop: 10}}>
          <Button kind='secondary' block sm onClick={() => createShareLink('PROJECT_SNAPSHOT')} disabled={shareBusy}>
            <Share size={14} /> Create project snapshot link
          </Button>
        </div>
        <p style={{fontSize: 12, color: 'var(--signal-text-mute)', textAlign: 'center', marginTop: 10}}>
          Share links create public, read-only URLs to your project or specific reports.
        </p>
      </Feed>

      <ConfirmModal
        open={!!confirmRemove}
        title='Remove from project?'
        body={`${confirmRemove?.label || 'This member'} will lose access to all project runs, code, chat, and documents. They'll need a fresh invite to return.`}
        confirmLabel='Remove'
        danger
        busy={removeBusy}
        onClose={() => setConfirmRemove(null)}
        onConfirm={() => { if (confirmRemove) void performRemove(confirmRemove.userId); }}
      />
    </>
  );
}
