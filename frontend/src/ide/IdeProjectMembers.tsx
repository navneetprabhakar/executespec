import {useEffect, useState} from 'react';
import {useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import {ProjectTabs} from '@/ide/IdeProjectTabs';
import {ConfirmModal} from '@/signal/components/Modal';
import {useAuthStore} from '@/stores/useAuthStore';
import {
  availableOrgMembers,
  isExternalProjectMember,
  memberDisplayName,
} from '@/ide/projectMoreUtils';
import {ProjectBanner as Banner} from '@/ide/projectUi';

const INVITE_ROLES: api.ProjectInviteRole[] = [
  'PROJECT_VIEWER',
  'PROJECT_DEVELOPER',
  'PROJECT_ADMIN',
];

export function IdeProjectMembers() {
  const {projectId = ''} = useParams();
  const user = useAuthStore((s) => s.user);
  const [members, setMembers] = useState<api.ProjectMemberDto[] | null>(null);
  const [orgMembers, setOrgMembers] = useState<api.OrgMemberDto[]>([]);
  const [invites, setInvites] = useState<api.ProjectInviteDto[] | null>(null);
  const [shareLinks, setShareLinks] = useState<api.ShareLinkDto[] | null>(null);
  const [externalSharingEnabled, setExternalSharingEnabled] = useState<boolean | null>(null);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<api.ProjectInviteRole>('PROJECT_DEVELOPER');
  const [orgMemberUserId, setOrgMemberUserId] = useState('');
  const [orgMemberRole, setOrgMemberRole] = useState<api.ProjectInviteRole>('PROJECT_DEVELOPER');
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [revokeFor, setRevokeFor] = useState<string | null>(null);
  const [revokeInviteFor, setRevokeInviteFor] = useState<string | null>(null);
  const [removeFor, setRemoveFor] = useState<string | null>(null);

  function load() {
    api
      .getProjectMembers(projectId)
      .then(setMembers)
      .catch(() => setMembers([]));
    api
      .listShareLinks(projectId)
      .then((r) => setShareLinks(r.links ?? []))
      .catch(() => setShareLinks([]));
    api
      .listProjectInvites(projectId)
      .then(setInvites)
      .catch(() => setInvites([]));
    api
      .getOrgMembers()
      .then(setOrgMembers)
      .catch(() => setOrgMembers([]));
  }

  useEffect(() => {
    if (!projectId) return;
    load();
    api
      .getMyOrg()
      .then((o) => setExternalSharingEnabled(!!o.externalSharingEnabled))
      .catch(() => setExternalSharingEnabled(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  const isSuperAdmin = user?.globalRole === 'SUPER_ADMIN';
  const canManage = isSuperAdmin || user?.orgRole === 'ORG_ADMIN';
  const canInviteExternals = isSuperAdmin || externalSharingEnabled === true;

  async function invite() {
    const email = inviteEmail.trim();
    if (!email) return;
    setBusy('invite');
    setErr('');
    setMsg('');
    try {
      await api.createProjectInvite(projectId, {email, role: inviteRole});
      setMsg(`Invite sent to ${email}.`);
      setInviteEmail('');
      setInvites(await api.listProjectInvites(projectId));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Invite failed.');
    } finally {
      setBusy('');
    }
  }

  async function addExistingOrgMember() {
    if (!orgMemberUserId) return;
    setBusy('add-member');
    setErr('');
    setMsg('');
    try {
      await api.addProjectMember(projectId, orgMemberUserId, orgMemberRole);
      setMsg('Project member added.');
      setOrgMemberUserId('');
      load();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Add member failed.');
    } finally {
      setBusy('');
    }
  }

  async function makeShareLink() {
    setBusy('share');
    setErr('');
    setMsg('');
    try {
      await api.createShareLink(projectId, {surface: 'PROJECT_OVERVIEW', expiresInDays: 30});
      setMsg('Share link created.');
      const r = await api.listShareLinks(projectId);
      setShareLinks(r.links ?? []);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Create failed.');
    } finally {
      setBusy('');
    }
  }

  async function revokeLink(linkId: string) {
    try {
      await api.revokeShareLink(projectId, linkId);
      setShareLinks((p) => (p ?? []).filter((l) => l.id !== linkId));
      setMsg('Share link revoked.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Revoke failed.');
    }
  }

  async function revokeInvite(inviteId: string) {
    try {
      await api.revokeProjectInvite(projectId, inviteId);
      setInvites((p) => (p ?? []).filter((i) => i.id !== inviteId));
      setMsg('Invite revoked.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Revoke failed.');
    }
  }

  async function resendInvite(invite: api.ProjectInviteDto) {
    setBusy(`invite-${invite.id}`);
    setErr('');
    setMsg('');
    try {
      await api.createProjectInvite(projectId, {email: invite.email, role: invite.projectRole});
      setInvites(await api.listProjectInvites(projectId));
      setMsg(`Invite re-sent to ${invite.email}.`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Resend failed.');
    } finally {
      setBusy('');
    }
  }

  async function remove(userId: string) {
    try {
      await api.removeProjectMember(projectId, userId);
      load();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Remove failed.');
    }
  }

  const selectableOrgMembers = availableOrgMembers(orgMembers, members);

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div className='ide-h1'>Members &amp; sharing</div>
      <Banner err={err} msg={msg} />

      <div className='ide-h2'>Project access · {members?.length ?? 0}</div>
      {members === null ? (
        <IdeLoader label='Loading members…' />
      ) : members.length === 0 ? (
        <div className='ide-empty'>No members.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {members.map((m, i) => {
            const isExternal = isExternalProjectMember(
              m as api.ProjectMemberDto & {external?: boolean},
            );
            const isMe = m.userId === user?.id;
            const role = m.projectRole || 'PROJECT_DEVELOPER';
            return (
              <div
                key={m.userId}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  padding: '11px 16px',
                  borderBottom: i < members.length - 1 ? '1px solid var(--ide-border)' : 'none',
                }}
              >
                <span style={{flex: 1, fontWeight: 500}}>
                  {memberDisplayName(m)}
                  {isMe && <span style={{color: 'var(--ide-fg-muted)'}}> · you</span>}
                  {isExternal && (
                    <span
                      style={{
                        display: 'inline-block',
                        marginLeft: 6,
                        padding: '1px 6px',
                        background: 'var(--ide-bg-input)',
                        color: 'var(--ide-blue)',
                        fontSize: 9,
                        fontWeight: 700,
                        letterSpacing: '0.06em',
                        borderRadius: 3,
                      }}
                    >
                      EXT
                    </span>
                  )}
                </span>
                <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>{m.email}</span>
                <IdeBadge kind='ok'>{role}</IdeBadge>
                {canManage && !isMe && (
                  <button
                    className='ide-btn ghost'
                    style={{padding: '3px 8px', color: 'var(--ide-red)'}}
                    onClick={() => setRemoveFor(m.userId)}
                  >
                    Remove
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}

      {canManage && selectableOrgMembers.length > 0 && (
        <>
          <div className='ide-h2'>Add org member</div>
          <div className='ide-card'>
            <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginBottom: 10}}>
              Grant this project to someone who is already in your organization. This does not send
              an invite or change their org role.
            </div>
            <select
              className='ide-select'
              value={orgMemberUserId}
              onChange={(e) => setOrgMemberUserId(e.target.value)}
              style={{marginBottom: 8}}
            >
              <option value=''>Select org member…</option>
              {selectableOrgMembers.map((member) => (
                <option key={member.userId} value={member.userId}>
                  {memberDisplayName(member)} · {member.email}
                </option>
              ))}
            </select>
            <div style={{display: 'flex', gap: 6, marginBottom: 10, flexWrap: 'wrap'}}>
              {INVITE_ROLES.map((r) => (
                <button
                  key={r}
                  type='button'
                  className='ide-btn ghost'
                  style={{
                    padding: '4px 10px',
                    borderColor: r === orgMemberRole ? 'var(--ide-accent)' : undefined,
                    color: r === orgMemberRole ? 'var(--ide-accent)' : undefined,
                  }}
                  onClick={() => setOrgMemberRole(r)}
                >
                  {r.replace('PROJECT_', '').toLowerCase()}
                </button>
              ))}
            </div>
            <button
              className='ide-btn'
              disabled={busy === 'add-member' || !orgMemberUserId}
              onClick={addExistingOrgMember}
            >
              {busy === 'add-member' ? 'Adding…' : 'Add to project'}
            </button>
          </div>
        </>
      )}

      {canManage &&
        (canInviteExternals ? (
          <>
            <div className='ide-h2'>Invite external collaborator</div>
            <div className='ide-card'>
              <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginBottom: 10}}>
                Invite by email to this project only. Gmail, Outlook, contractors, and partner
                domains are allowed here when project external collaborators are enabled.
              </div>
              <input
                className='ide-input'
                type='email'
                placeholder='teammate@company.com'
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
                style={{marginBottom: 8}}
              />
              <div style={{display: 'flex', gap: 6, marginBottom: 10, flexWrap: 'wrap'}}>
                {INVITE_ROLES.map((r) => (
                  <button
                    key={r}
                    type='button'
                    className='ide-btn ghost'
                    style={{
                      padding: '4px 10px',
                      borderColor: r === inviteRole ? 'var(--ide-accent)' : undefined,
                      color: r === inviteRole ? 'var(--ide-accent)' : undefined,
                    }}
                    onClick={() => setInviteRole(r)}
                  >
                    {r.replace('PROJECT_', '').toLowerCase()}
                  </button>
                ))}
              </div>
              <button
                className='ide-btn'
                disabled={busy === 'invite' || !inviteEmail.trim()}
                onClick={invite}
              >
                {busy === 'invite' ? 'Inviting…' : 'Send invite'}
              </button>
            </div>
          </>
        ) : (
          <>
            <div className='ide-h2'>Invite external collaborator</div>
            <div className='ide-card' style={{borderColor: 'var(--ide-blue)'}}>
              <div
                style={{
                  fontSize: 11,
                  fontWeight: 700,
                  color: 'var(--ide-blue)',
                  letterSpacing: '0.14em',
                  textTransform: 'uppercase',
                  marginBottom: 6,
                }}
              >
                Project external collaborators are off
              </div>
              <div style={{fontSize: 13, lineHeight: 1.55}}>
                Project-only external collaborators are disabled for your org, so people outside it
                can&apos;t be invited to this project. Enable it in{' '}
                <a href='/org/settings' style={{color: 'var(--ide-accent)'}}>
                  Org settings → Project external collaborators
                </a>
                .
              </div>
            </div>
          </>
        ))}

      {!canManage && (
        <div className='ide-card' style={{borderColor: 'var(--ide-blue)', marginTop: 12}}>
          <div
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: 'var(--ide-blue)',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              marginBottom: 6,
            }}
          >
            Invite access is admin-only
          </div>
          <div style={{fontSize: 13, lineHeight: 1.55}}>
            Ask an org admin to invite project collaborators or change your role. You can still view
            current members and use share links if your role permits them.
          </div>
        </div>
      )}

      {canManage && (
        <>
          <div className='ide-h2'>Pending invites · {invites?.length ?? 0}</div>
          {invites === null ? (
            <IdeLoader label='Loading invites…' />
          ) : invites.length === 0 ? (
            <div className='ide-empty'>No pending invites.</div>
          ) : (
            <div className='ide-card' style={{padding: 0}}>
              {invites.map((iv, i) => (
                <div
                  key={iv.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '11px 16px',
                    borderBottom: i < invites.length - 1 ? '1px solid var(--ide-border)' : 'none',
                  }}
                >
                  <div style={{minWidth: 0, flex: 1}}>
                    <div style={{fontWeight: 600}}>{iv.email}</div>
                    <div
                      className='ide-mono'
                      style={{
                        fontSize: 11,
                        color: 'var(--ide-fg-muted)',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {iv.projectRole.replace('PROJECT_', '').toLowerCase()} · {iv.status}
                      {iv.expiresAt && ` · expires ${new Date(iv.expiresAt).toLocaleDateString()}`}
                    </div>
                  </div>
                  <button
                    className='ide-btn ghost'
                    style={{padding: '3px 8px'}}
                    disabled={busy === `invite-${iv.id}`}
                    onClick={() => void resendInvite(iv)}
                  >
                    {busy === `invite-${iv.id}` ? 'Sending…' : 'Resend'}
                  </button>
                  <button
                    className='ide-btn ghost'
                    style={{padding: '3px 8px', color: 'var(--ide-red)'}}
                    onClick={() => setRevokeInviteFor(iv.id)}
                  >
                    Revoke
                  </button>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      <div className='ide-h2'>Share links · {shareLinks?.length ?? 0}</div>
      {shareLinks === null ? (
        <IdeLoader label='Loading share links…' />
      ) : shareLinks.length === 0 ? (
        <div className='ide-empty'>
          No share links yet. Create one to give read-only access without an account.
        </div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {shareLinks.map((s, i) => {
            const url = s.token ? `${window.location.origin}/shared/${s.token}` : '';
            return (
              <div
                key={s.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  padding: '11px 16px',
                  borderBottom: i < shareLinks.length - 1 ? '1px solid var(--ide-border)' : 'none',
                }}
              >
                <div style={{flex: 1, minWidth: 0}}>
                  <div style={{fontWeight: 600}}>{s.surface}</div>
                  <div
                    className='ide-mono'
                    style={{
                      fontSize: 11,
                      color: 'var(--ide-fg-muted)',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {url}
                    {s.expiresAt && ` · expires ${new Date(s.expiresAt).toLocaleDateString()}`}
                  </div>
                </div>
                <button
                  className='ide-btn ghost'
                  style={{padding: '3px 8px'}}
                  onClick={() =>
                    void navigator.clipboard.writeText(url).then(() => setMsg('Link copied.'))
                  }
                >
                  Copy
                </button>
                <button
                  className='ide-btn ghost'
                  style={{padding: '3px 8px', color: 'var(--ide-red)'}}
                  onClick={() => setRevokeFor(s.id)}
                >
                  Revoke
                </button>
              </div>
            );
          })}
        </div>
      )}
      <button
        className='ide-btn ghost'
        style={{marginTop: 10}}
        onClick={makeShareLink}
        disabled={busy === 'share'}
      >
        {busy === 'share' ? 'Creating…' : 'Create project snapshot link'}
      </button>

      <ConfirmModal
        open={!!revokeFor}
        title='Revoke share link'
        body='Revoke this share link?'
        confirmLabel='Revoke'
        danger
        onConfirm={async () => {
          const id = revokeFor;
          if (id) await revokeLink(id);
        }}
        onClose={() => setRevokeFor(null)}
      />
      <ConfirmModal
        open={!!revokeInviteFor}
        title='Revoke invite'
        body='Revoke this pending project invite? The existing magic link will stop working.'
        confirmLabel='Revoke'
        danger
        onConfirm={async () => {
          const id = revokeInviteFor;
          if (id) await revokeInvite(id);
        }}
        onClose={() => setRevokeInviteFor(null)}
      />
      <ConfirmModal
        open={!!removeFor}
        title='Remove member'
        body='Remove this member from the project?'
        confirmLabel='Remove'
        danger
        onConfirm={async () => {
          const id = removeFor;
          if (id) await remove(id);
        }}
        onClose={() => setRemoveFor(null)}
      />
    </div>
  );
}
