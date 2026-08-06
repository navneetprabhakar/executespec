import {useEffect, useRef, useState} from 'react';
import {useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import {LoadError} from '@/ide/LoadError';
import {useAsyncData} from '@/lib/loadState';
import {ProjectTabs} from '@/ide/IdeProjectTabs';
import {ConfirmModal} from '@/signal/components/Modal';
import {useAuthStore} from '@/stores/useAuthStore';
import {
  availableOrgMembers,
  canManageMembership,
  canManageShareLinks,
  isExternalProjectMember,
  memberDisplayName,
  shareLinkStatus,
} from '@/ide/projectMoreUtils';
import {ProjectBanner as Banner} from '@/ide/projectUi';

const INVITE_ROLES: api.ProjectInviteRole[] = [
  'PROJECT_VIEWER',
  'PROJECT_DEVELOPER',
  'PROJECT_ADMIN',
];

/** `PROJECT_DEVELOPER` → `Developer`. The exact enum stays on the title attribute. */
function roleLabel(role: string): string {
  const bare = role.replace('PROJECT_', '').toLowerCase();
  return bare.charAt(0).toUpperCase() + bare.slice(1);
}

/**
 * Role choice as one segmented control.
 *
 * Was three ghost buttons whose selected state was an inline `borderColor` +
 * `color` override, so "chosen" was communicated by a control changing its own
 * metric. Every segment keeps `--ide-control-h`; these are the project member
 * invite targets the a11y script pins at 32px.
 */
function RolePicker({
  legend,
  value,
  onChange,
}: {
  legend: string;
  value: api.ProjectInviteRole;
  onChange: (role: api.ProjectInviteRole) => void;
}) {
  return (
    <div className='gf-seg' role='group' aria-label={legend}>
      {INVITE_ROLES.map((r) => (
        <button
          key={r}
          type='button'
          aria-pressed={r === value}
          style={{minHeight: 32}}
          onClick={() => onChange(r)}
        >
          {roleLabel(r)}
        </button>
      ))}
    </div>
  );
}

export function IdeProjectMembers() {
  const {projectId = ''} = useParams();
  const user = useAuthStore((s) => s.user);
  // D4: each of these renders an affirmative "you have no X" empty state, so a
  // failed load must stay distinguishable from a genuinely empty result.
  const membersState = useAsyncData(
    () => api.getProjectMembers(projectId),
    [projectId],
    !!projectId,
  );
  const shareLinksState = useAsyncData(
    () => api.listShareLinks(projectId).then((r) => r.links ?? []),
    [projectId],
    !!projectId,
  );
  const invitesState = useAsyncData(
    () => api.listProjectInvites(projectId),
    [projectId],
    !!projectId,
  );
  const orgMembersState = useAsyncData(() => api.getOrgMembers(), [projectId], !!projectId);
  const orgState = useAsyncData(() => api.getMyOrg(), [projectId], !!projectId);

  const members = membersState.data;
  const invites = invitesState.data;
  const setInvites = invitesState.setData;
  const shareLinks = shareLinksState.data;
  const setShareLinks = shareLinksState.setData;
  const orgMembers = orgMembersState.data ?? [];
  // Unknown (load failed) stays falsy, exactly as the previous `false` fallback
  // did, so a failure cannot silently widen invite authority.
  const externalSharingEnabled = orgState.data ? !!orgState.data.externalSharingEnabled : null;
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<api.ProjectInviteRole>('PROJECT_DEVELOPER');
  const [orgMemberUserId, setOrgMemberUserId] = useState('');
  const [orgMemberRole, setOrgMemberRole] = useState<api.ProjectInviteRole>('PROJECT_DEVELOPER');
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [newShareUrl, setNewShareUrl] = useState('');
  const [shareAuditLinkId, setShareAuditLinkId] = useState<string | null>(null);
  const [shareAudit, setShareAudit] = useState<api.ShareLinkAuditDto[] | null>(null);
  const [revokeFor, setRevokeFor] = useState<string | null>(null);
  const [revokeInviteFor, setRevokeInviteFor] = useState<string | null>(null);
  const [removeFor, setRemoveFor] = useState<string | null>(null);

  // RR-1332 — the authoritative "which project is mounted now" for guarding in-flight async writes.
  const projectIdRef = useRef(projectId);
  projectIdRef.current = projectId;

  // RR-1332 — React reuses this route component across `/projects/:projectId` navigations, so
  // project A's one-time share bearer (and its audit / revoke / status message) would remain
  // visible and copyable under project B, contradicting the shown-once semantics and misattributing
  // authority. Clear every scope-bound bit of state when the project changes.
  useEffect(() => {
    setNewShareUrl('');
    setShareAuditLinkId(null);
    setShareAudit(null);
    setRevokeFor(null);
    setRevokeInviteFor(null);
    setRemoveFor(null);
    setMsg('');
    setErr('');
  }, [projectId]);

  function load() {
    membersState.reload();
    shareLinksState.reload();
    invitesState.reload();
    orgMembersState.reload();
  }

  const isSuperAdmin = user?.globalRole === 'SUPER_ADMIN';
  const currentMember = members?.find((m) => m.userId === user?.id);
  // RR-1507 — membership and share-links have different server guards. A
  // PROJECT_DEVELOPER may manage share links (isProjectWriter) but NOT members
  // (requireProjectAdmin); a single boolean would show them a member-admin
  // surface that 403s after every confirm.
  const authorityInput = {
    globalRole: user?.globalRole,
    orgRole: user?.orgRole,
    projectRole: currentMember?.projectRole,
    hasProjectMembership: !!currentMember,
  };
  const canManageMembers = canManageMembership(authorityInput);
  const canManageShares = canManageShareLinks(authorityInput);
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
    // RR-1332 — pin the project this credential belongs to. If the user navigates to another
    // project while the create is in flight, discard the result rather than rendering project A's
    // one-time bearer under project B.
    const forProject = projectId;
    try {
      const created = await api.createShareLink(forProject, {
        surface: 'PROJECT_OVERVIEW',
        expiresInDays: 30,
      });
      if (forProject !== projectIdRef.current) return;
      if (!created.token) throw new Error('Server did not return the one-time share credential.');
      setNewShareUrl(api.shareLinkPublicUrl(created.token));
      setMsg('Share link created. Copy it now; the credential will not be shown again.');
      const r = await api.listShareLinks(forProject);
      if (forProject !== projectIdRef.current) return;
      setShareLinks(r.links ?? []);
    } catch (e: unknown) {
      if (forProject !== projectIdRef.current) return;
      setErr(e instanceof Error ? e.message : 'Create failed.');
    } finally {
      if (forProject === projectIdRef.current) setBusy('');
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

  async function showShareAudit(linkId: string) {
    if (shareAuditLinkId === linkId) {
      setShareAuditLinkId(null);
      setShareAudit(null);
      return;
    }
    setShareAuditLinkId(linkId);
    setShareAudit(null);
    setErr('');
    try {
      setShareAudit(await api.getShareLinkAudit(projectId, linkId));
    } catch (e: unknown) {
      setShareAuditLinkId(null);
      setErr(e instanceof Error ? e.message : 'Audit lookup failed.');
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
      <h1 className='ide-h1'>Members &amp; sharing</h1>
      <Banner err={err} msg={msg} />

      <div className='ide-h2'>Project access{members ? ` · ${members.length}` : ''}</div>
      {membersState.failure ? (
        <LoadError failure={membersState.failure} what='members' onRetry={membersState.reload} />
      ) : members === null ? (
        <IdeLoader label='Loading members…' />
      ) : members.length === 0 ? (
        <div className='ide-empty'>No members.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {members.map((m) => {
            const isExternal = isExternalProjectMember(
              m as api.ProjectMemberDto & {external?: boolean},
            );
            const isMe = m.userId === user?.id;
            const role = m.projectRole || 'PROJECT_DEVELOPER';
            return (
              <div key={m.userId} className='ide-project-row'>
                <div className='ide-project-row-body'>
                  {/* The chip sits OUTSIDE the truncating text, not inside it: at
                      375px a long name's ellipsis ate the whole `External` chip,
                      losing the one fact it exists to carry. */}
                  <span className='ide-project-row-title gf-titleline'>
                    <span className='gf-titletext'>
                      {memberDisplayName(m)}
                      {isMe && <span className='gf-hint'> · you</span>}
                    </span>
                    {/* Identity, not state. This was a tracked-out uppercase `EXT`
                        painted in the accent blue, which made "this person is
                        outside your org" — an always-true fact — shout as loudly
                        as a measured warning. */}
                    {isExternal && (
                      <span className='ide-badge gf-idchip' title='External collaborator'>
                        External
                      </span>
                    )}
                  </span>
                  {/* The email was a middle flex item, so at 375px it crushed the
                      name it belongs to. It is the row's sub-line and truncates. */}
                  <span className='ide-project-row-sub'>{m.email}</span>
                </div>
                {/* A role is an identity too: neutral, and in a fixed cell so the
                    Remove control does not dance left and right down the list. */}
                <span className='gf-rolecol'>
                  <span className='ide-badge' title={role}>
                    {roleLabel(role)}
                  </span>
                </span>
                {/* The cell is reserved even when there is no action, so a row
                    that cannot be removed ("you") does not shift the role column
                    88px right of every other row. */}
                <span className='gf-actioncol'>
                  {canManageMembers && !isMe && (
                    <button className='ide-btn danger' onClick={() => setRemoveFor(m.userId)}>
                      Remove
                    </button>
                  )}
                </span>
              </div>
            );
          })}
        </div>
      )}

      {canManageMembers && selectableOrgMembers.length > 0 && (
        <>
          <div className='ide-h2'>Add org member</div>
          <div className='ide-card gf-form'>
            <div className='gf-hint'>
              Grants project access to someone already in your org. No invite email; org role
              unchanged.
            </div>
            <select
              className='ide-select'
              aria-label='Organization member to add'
              value={orgMemberUserId}
              onChange={(e) => setOrgMemberUserId(e.target.value)}
            >
              <option value=''>Select org member…</option>
              {selectableOrgMembers.map((member) => (
                <option key={member.userId} value={member.userId}>
                  {memberDisplayName(member)} · {member.email}
                </option>
              ))}
            </select>
            <RolePicker
              legend='Project role for the org member'
              value={orgMemberRole}
              onChange={setOrgMemberRole}
            />
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

      {canManageMembers &&
        (canInviteExternals ? (
          <>
            <div className='ide-h2'>Invite external collaborator</div>
            <div className='ide-card gf-form'>
              <div className='gf-hint'>
                Email invite scoped to this project only. Any domain — Gmail, Outlook, contractor,
                partner — while external collaborators are enabled.
              </div>
              <input
                className='ide-input'
                type='email'
                aria-label='External collaborator email'
                placeholder='teammate@company.com'
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
              />
              <RolePicker
                legend='Project role for the invited collaborator'
                value={inviteRole}
                onChange={setInviteRole}
              />
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
            {/* Was a whole card repainted blue under a tracked-out uppercase
                micro-label. State is a 3px leading edge; emphasis is weight. */}
            <div className='ide-note info'>
              <span className='gf-note-title'>External collaborators are off for your org</span>
              People outside your org can&apos;t be invited to this project. Enable it in{' '}
              <a className='gf-note-link' href='/org/settings'>
                Org settings → Project external collaborators
              </a>
              .
            </div>
          </>
        ))}

      {!canManageMembers && (
        <div className='ide-note info' style={{marginTop: 'var(--ide-s3)'}}>
          <span className='gf-note-title'>Invites are admin-only</span>
          Ask an org admin to invite collaborators or change your role. You can still view current
          members. Share links are managed by project writers and org admins.
        </div>
      )}

      {canManageMembers && (
        <>
          <div className='ide-h2'>Pending invites{invites ? ` · ${invites.length}` : ''}</div>
          {invitesState.failure ? (
            <LoadError
              failure={invitesState.failure}
              what='pending invites'
              onRetry={invitesState.reload}
            />
          ) : invites === null ? (
            <IdeLoader label='Loading invites…' />
          ) : invites.length === 0 ? (
            <div className='ide-empty'>No pending invites.</div>
          ) : (
            <div className='ide-card' style={{padding: 0}}>
              {invites.map((iv) => (
                <div key={iv.id} className='ide-project-row'>
                  <div className='ide-project-row-body'>
                    <span className='ide-project-row-title'>{iv.email}</span>
                    <span className='ide-project-row-sub'>
                      {roleLabel(iv.projectRole)} · {iv.status}
                      {iv.expiresAt && ` · expires ${new Date(iv.expiresAt).toLocaleDateString()}`}
                    </span>
                  </div>
                  <div className='gf-actions'>
                    <button
                      className='ide-btn ghost'
                      disabled={busy === `invite-${iv.id}`}
                      onClick={() => void resendInvite(iv)}
                    >
                      {busy === `invite-${iv.id}` ? 'Sending…' : 'Resend'}
                    </button>
                    {/* Destruction is quiet until aimed at: `.danger` is a ghost
                        that reddens on hover, not a permanently red control. */}
                    <button className='ide-btn danger' onClick={() => setRevokeInviteFor(iv.id)}>
                      Revoke
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      <div className='ide-h2'>
        Share links
        {shareLinks
          ? ` · ${shareLinks.filter((s) => shareLinkStatus(s) === 'Active').length} active`
          : ''}
      </div>
      {newShareUrl && (
        <div className='ide-note warn' style={{marginBottom: 'var(--ide-s3)'}} role='status'>
          <span className='gf-note-title'>New link — shown once</span>
          <div className='ide-mono' style={{overflowWrap: 'anywhere'}}>
            {newShareUrl}
          </div>
          <div className='gf-actions' style={{marginTop: 'var(--ide-s3)'}}>
            <button
              className='ide-btn'
              onClick={() =>
                void navigator.clipboard.writeText(newShareUrl).then(() => setMsg('Link copied.'))
              }
            >
              Copy link
            </button>
            <button className='ide-btn ghost' onClick={() => setNewShareUrl('')}>
              I have saved it
            </button>
          </div>
        </div>
      )}
      {shareLinksState.failure ? (
        <LoadError
          failure={shareLinksState.failure}
          what='share links'
          onRetry={shareLinksState.reload}
        />
      ) : shareLinks === null ? (
        <IdeLoader label='Loading share links…' />
      ) : shareLinks.length === 0 ? (
        <div className='ide-empty'>No share links yet.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {shareLinks.map((s) => {
            // RR-1511 — an explicit per-row state so a revoked/expired link is not
            // shown as if it still grants access. This IS measured state, so it
            // keeps its colour and leads the row in a fixed cell.
            const st = shareLinkStatus(s);
            return (
              <div key={s.id} className='ide-project-row'>
                <span className='ide-project-statuscol'>
                  <IdeBadge kind={st === 'Active' ? 'ok' : st === 'Revoked' ? 'bad' : 'warn'}>
                    {st}
                  </IdeBadge>
                </span>
                <div className='ide-project-row-body'>
                  <span className='ide-project-row-title'>{s.surface}</span>
                  <span className='ide-project-row-sub ide-mono'>
                    {canManageShares
                      ? `Credential ${s.tokenPrefix}… · URL hidden after creation`
                      : 'Share link details are managed by project writers.'}
                    {s.expiresAt && ` · expires ${new Date(s.expiresAt).toLocaleDateString()}`}
                    {` · ${s.views} view${s.views === 1 ? '' : 's'}`}
                    {s.lastViewedAt &&
                      ` · last viewed ${new Date(s.lastViewedAt).toLocaleDateString()}`}
                  </span>
                </div>
                {canManageShares && (
                  <div className='gf-actions'>
                    <button className='ide-btn ghost' onClick={() => void showShareAudit(s.id)}>
                      {shareAuditLinkId === s.id ? 'Hide audit' : 'Audit'}
                    </button>
                    {/* RR-1511 — Revoke only makes sense on a still-active link. */}
                    {st === 'Active' && (
                      <button className='ide-btn danger' onClick={() => setRevokeFor(s.id)}>
                        Revoke
                      </button>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
      {shareAuditLinkId && (
        <div className='ide-card' style={{marginTop: 'var(--ide-s3)'}} aria-live='polite'>
          <span className='gf-note-title'>Latest share-link activity</span>
          {shareAudit === null ? (
            <IdeLoader label='Loading audit…' />
          ) : shareAudit.length === 0 ? (
            <div className='ide-empty'>No activity recorded.</div>
          ) : (
            shareAudit.map((event) => (
              <div key={event.id} className='gf-evidence-row'>
                <span className='gf-evidence-kind'>{event.eventType}</span>
                <span>{event.outcome}</span>
                <span className='gf-evidence-time'>
                  {new Date(event.occurredAt).toLocaleString()}
                </span>
              </div>
            ))
          )}
        </div>
      )}
      {canManageShares && (
        <button
          className='ide-btn ghost'
          style={{marginTop: 'var(--ide-s3)'}}
          onClick={makeShareLink}
          disabled={busy === 'share'}
        >
          {busy === 'share' ? 'Creating…' : 'Create project snapshot link'}
        </button>
      )}

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
