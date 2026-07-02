/* IDE-native settings cluster. The old Signal settings pages were retired; this
 * file is the route owner for provider keys, org members, and org settings.
 *
 * Keys are write-only
 * (Connected / Not-set pills, blank input = leave unchanged); secrets are never
 * echoed back. Destructive confirms route through ConfirmModal (no window.confirm).
 */

import {useEffect, useState} from 'react';
import {Link} from 'react-router-dom';
import * as api from '@/lib/api';
import {useAuthStore, selectNormalizedTier, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {useDocumentTitle} from '@/hooks/useDocumentTitle';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import {hasUnlimitedAiCredits} from '@/ide/planUtils';
import {ConfirmModal} from '@/signal/components/Modal';
import {
  domainListFromEmail,
  isSamlConfigOnly,
  isTeamWorkspaceSetupPending,
  KEY_PLACEHOLDER,
  matrixModelLabel,
  matrixProviderRows,
  normalizedOrgTier,
  orgDisplayName,
  orgKeyRows as buildOrgKeyRows,
  orgMemberDisplayName,
  orgMemberId,
  orgMemberInitials,
  orgSeatDisplayCount,
  orgSeatValue,
  parseDomainList,
  type OrgKeyProvider,
  type UserKeyProvider,
} from '@/ide/orgUtils';

// Inline error / success banner — colours map to the IDE token set (mirrors
// IdeProjectMore's Banner). Renders nothing when both are empty.
function Banner({err, msg}: {err?: string; msg?: string}) {
  if (!err && !msg) return null;
  return (
    <div
      className='ide-card'
      style={{
        marginBottom: 12,
        fontSize: 13,
        color: err ? 'var(--ide-red)' : 'var(--ide-green)',
        borderColor: err ? 'var(--ide-red)' : 'var(--ide-green)',
      }}
    >
      {err || msg}
    </div>
  );
}

// Soft informational callout — the IDE equivalent of the Signal lavender-soft
// "heads up" cards. Blue-tinted so it reads as guidance, not an error.
function InfoCard({children}: {children: React.ReactNode}) {
  return (
    <div
      className='ide-card'
      style={{
        marginBottom: 12,
        fontSize: 12.5,
        lineHeight: 1.55,
        color: 'var(--ide-fg)',
        background: 'color-mix(in srgb, var(--ide-blue) 12%, transparent)',
        borderColor: 'color-mix(in srgb, var(--ide-blue) 35%, transparent)',
      }}
    >
      {children}
    </div>
  );
}

// Uppercase field label + control wrapper (mirrors the Signal `Field` helper).
function Field({label, children}: {label: string; children: React.ReactNode}) {
  return (
    <div style={{marginBottom: 12}}>
      <div
        style={{
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: '0.06em',
          textTransform: 'uppercase',
          color: 'var(--ide-fg-muted)',
          marginBottom: 6,
        }}
      >
        {label}
      </div>
      {children}
    </div>
  );
}

// API keys (BYOK) — /api-keys
export function IdeApiKeys() {
  useDocumentTitle('Provider keys');
  const tier = useAuthStore(selectNormalizedTier);
  const user = useAuthStore((s) => s.user);
  const permissions = useAuthStore((s) => s.permissions);
  const canManage = permissions?.canManageAiKeys ?? ['BYOK', 'TEAM', 'ENTERPRISE'].includes(tier);

  // Per-provider "key already saved" flags (raw keys are never returned) —
  // drives the "Leave blank to keep your current key" hint under each row.
  const connectedByProvider: Record<UserKeyProvider, boolean> = {
    ANTHROPIC: !!user?.hasAnthropicKey,
    OPENAI: !!user?.hasOpenAiKey,
    GEMINI: !!user?.hasGeminiKey,
    DEEPSEEK: !!user?.hasDeepSeekKey,
    FIREWORKS: !!user?.hasFireworksKey,
  };

  const [openaiKey, setOpenaiKey] = useState('');
  const [anthropicKey, setAnthropicKey] = useState('');
  const [deepSeekKey, setDeepSeekKey] = useState('');
  const [fireworksKey, setFireworksKey] = useState('');
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState<UserKeyProvider | null>(null);

  async function saveProviderKey(provider: UserKeyProvider, key: string) {
    if (!key.trim()) {
      setErr(`Enter a ${provider} key first.`);
      return;
    }
    setBusy(provider);
    setErr('');
    setMsg('');
    try {
      const v = await api.validateAiKey(provider, key.trim());
      if (v?.valid === false) throw new Error(v.message || `Invalid ${provider} key.`);
      await api.updateAiKeySettings(
        provider === 'OPENAI' ? key.trim() : '',
        provider === 'ANTHROPIC' ? key.trim() : '',
        provider === 'GEMINI' ? key.trim() : '',
        provider === 'DEEPSEEK' ? key.trim() : '',
        provider === 'FIREWORKS' ? key.trim() : '',
      );
      setMsg(`${provider} key saved. Work using ${provider} now bypasses the platform cap.`);
      if (provider === 'OPENAI') setOpenaiKey('');
      if (provider === 'ANTHROPIC') setAnthropicKey('');
      if (provider === 'DEEPSEEK') setDeepSeekKey('');
      if (provider === 'FIREWORKS') setFireworksKey('');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setBusy(null);
    }
  }

  function ProviderRow(
    label: string,
    provider: UserKeyProvider,
    value: string,
    set: (v: string) => void,
    last: boolean,
  ) {
    const connected = connectedByProvider[provider];
    return (
      <div
        key={provider}
        style={{
          padding: '14px 16px',
          borderBottom: last ? 'none' : '1px solid var(--ide-border)',
        }}
      >
        <div
          style={{fontSize: 13.5, fontWeight: 600, marginBottom: 8, color: 'var(--ide-fg-bright)'}}
        >
          {label}
        </div>
        {/* TIER-3 — when the tier gate is closed the whole row renders
            disabled; the upgrade banner above is the single CTA. */}
        <div style={{display: 'flex', gap: 8}}>
          <input
            className='ide-input ide-mono'
            type='password'
            aria-label={`${label} personal provider key`}
            autoComplete='off'
            placeholder={`Paste your ${provider.toLowerCase()} key (${KEY_PLACEHOLDER[provider]})`}
            value={value}
            onChange={(e) => set(e.target.value)}
            disabled={!canManage}
            style={{flex: 1}}
          />
          <button
            className='ide-btn'
            style={{flexShrink: 0}}
            disabled={!canManage || busy === provider}
            onClick={() => saveProviderKey(provider, value)}
          >
            {busy === provider ? 'Validating…' : 'Save & validate'}
          </button>
        </div>
        {canManage && connected && (
          <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 6}}>
            Leave blank to keep your current key
          </div>
        )}
      </div>
    );
  }

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <h1 className='ide-h1'>Provider keys</h1>
      <div className='ide-sub'>Bring your own model keys · bypass platform cost cap</div>

      <InfoCard>
        When you connect a provider key, work using that provider{' '}
        <strong>bypass our cost cap</strong> entirely. Keys are stored server-side under this
        deployment's configured crypto policy and are never shown again. Available on BYOK, TEAM,
        and ENTERPRISE tiers.
      </InfoCard>

      {!canManage && (
        <div
          className='ide-card'
          style={{
            marginBottom: 12,
            fontSize: 13,
            color: 'var(--ide-red)',
            borderColor: 'var(--ide-red)',
          }}
        >
          Provider keys require a BYOK, TEAM, or ENTERPRISE workspace.{' '}
          <Link to='/plans' style={{color: 'var(--ide-red)', fontWeight: 600}}>
            Upgrade →
          </Link>
        </div>
      )}

      <Banner err={err} msg={msg} />

      <div className='ide-h2'>Providers</div>
      <div className='ide-card' style={{padding: 0}}>
        {ProviderRow('Anthropic', 'ANTHROPIC', anthropicKey, setAnthropicKey, false)}
        {ProviderRow('OpenAI', 'OPENAI', openaiKey, setOpenaiKey, false)}
        {ProviderRow('DeepSeek', 'DEEPSEEK', deepSeekKey, setDeepSeekKey, false)}
        {ProviderRow('Fireworks', 'FIREWORKS', fireworksKey, setFireworksKey, true)}
      </div>
    </div>
  );
}

// Org members — /org-members
export function IdeOrgMembers() {
  useDocumentTitle('Org members');
  const user = useAuthStore((s) => s.user);
  const tier = useAuthStore(selectNormalizedTier);
  const [org, setOrg] = useState<api.OrgDto | null>(null);
  const [members, setMembers] = useState<api.OrgMemberDto[]>([]);
  const [invites, setInvites] = useState<api.OrgInvitation[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<'DEVELOPER' | 'BILLING_ADMIN' | 'ORG_ADMIN'>(
    'DEVELOPER',
  );
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [rowBusy, setRowBusy] = useState<string | null>(null);
  const [confirmRemove, setConfirmRemove] = useState<{userId: string; label: string} | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([api.getMyOrg(), api.getOrgMembers(), api.getOrgInvitations()]).then(
      ([o, m, i]) => {
        if (cancelled) return;
        if (o.status === 'fulfilled') setOrg(o.value);
        if (m.status === 'fulfilled') setMembers(m.value);
        if (i.status === 'fulfilled') setInvites(i.value);
        setLoaded(true);
      },
    );
    return () => {
      cancelled = true;
    };
  }, []);

  const isSuperAdmin = user?.globalRole === 'SUPER_ADMIN';
  const canInvite = user?.orgRole === 'ORG_ADMIN' || isSuperAdmin;
  const orgTier = normalizedOrgTier(org, tier);
  const workspaceSetupPending = isTeamWorkspaceSetupPending(
    org as api.OrgDto & {seats?: number},
    tier,
  );
  const seatValue = orgSeatValue(org as api.OrgDto & {seats?: number}, tier, members.length);

  async function invite() {
    const email = inviteEmail.trim();
    if (!email) return;
    setBusy(true);
    setErr('');
    setMsg('');
    try {
      await api.inviteMember({email, orgRole: inviteRole});
      setMsg(`Invite sent to ${email}.`);
      setInviteEmail('');
      const i = await api.getOrgInvitations();
      setInvites(i);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Invite failed.');
    } finally {
      setBusy(false);
    }
  }

  async function resend(id: string) {
    setRowBusy(`resend-${id}`);
    try {
      await api.resendInvitation(id);
      setMsg('Invite re-sent.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Resend failed.');
    } finally {
      setRowBusy(null);
    }
  }

  async function cancelInvite(id: string) {
    setRowBusy(`cancel-${id}`);
    setErr('');
    setMsg('');
    try {
      await api.cancelInvitation(id);
      setInvites(await api.getOrgInvitations());
      setMsg('Invite cancelled.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Cancel failed.');
    } finally {
      setRowBusy(null);
    }
  }

  async function removeOne(userId: string) {
    setRowBusy(`remove-${userId}`);
    try {
      await api.removeMember(userId);
      setMembers(await api.getOrgMembers());
      setMsg('Member removed.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Remove failed.');
    } finally {
      setRowBusy(null);
      setConfirmRemove(null);
    }
  }

  async function changeRole(userId: string, role: string) {
    setRowBusy(`role-${userId}`);
    try {
      await api.changeMemberRole(userId, role);
      setMembers(await api.getOrgMembers());
      setMsg('Role updated.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Role change failed.');
    } finally {
      setRowBusy(null);
    }
  }

  return (
    <div className='ide-pane' style={{maxWidth: 820}}>
      <h1 className='ide-h1'>Team access</h1>
      <div className='ide-sub'>
        Manage organization members, roles, and work-domain invites for {orgDisplayName(org)} ·{' '}
        {members.length} member
        {members.length === 1 ? '' : 's'} · {tier}
      </div>

      <Banner err={err} msg={msg} />

      {/* Seats-used + invite composer */}
      <div className='ide-card' style={{marginBottom: 12}}>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: canInvite ? 'flex-start' : 'center',
            gap: 16,
            flexWrap: 'wrap',
          }}
        >
          <div>
            <div
              style={{
                fontSize: 10,
                fontWeight: 700,
                color: 'var(--ide-fg-muted)',
                letterSpacing: '0.1em',
                textTransform: 'uppercase',
                marginBottom: 4,
              }}
            >
              Seats used
            </div>
            <div style={{fontSize: 22, fontWeight: 700, color: 'var(--ide-fg-bright)'}}>
              {seatValue}
            </div>
            {orgTier === 'ENTERPRISE' && (
              <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                {workspaceSetupPending
                  ? 'Finish workspace setup before treating this as a provisioned Enterprise org.'
                  : 'Enterprise membership is governed by contract and allowed domains.'}
              </div>
            )}
            {orgTier === 'TEAM' && (
              <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                {workspaceSetupPending
                  ? 'TEAM has a 2-seat minimum; invite flow is available while setup is completed.'
                  : 'Org members consume seats. External project collaborators do not.'}
              </div>
            )}
            {orgTier !== 'ENTERPRISE' && orgTier !== 'TEAM' && (
              <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                Org members consume seats. External project collaborators do not.
              </div>
            )}
          </div>
          {canInvite && (
            <div style={{display: 'flex', flexDirection: 'column', gap: 8, minWidth: 240}}>
              <div
                style={{
                  fontSize: 10,
                  fontWeight: 700,
                  color: 'var(--ide-fg-muted)',
                  letterSpacing: '0.1em',
                  textTransform: 'uppercase',
                }}
              >
                Invite org member
              </div>
              <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', lineHeight: 1.45}}>
                Use a company email matching your org domains. Gmail, Yahoo, Outlook, and similar
                consumer domains are project-collaborator invites, not org membership.
              </div>
              <input
                className='ide-input'
                type='email'
                aria-label='Organization invite email'
                placeholder='teammate@company.com'
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
              />
              <select
                className='ide-select'
                aria-label='Organization invite role'
                value={inviteRole}
                onChange={(e) => setInviteRole(e.target.value as typeof inviteRole)}
              >
                <option value='DEVELOPER'>DEVELOPER</option>
                <option value='BILLING_ADMIN'>BILLING_ADMIN</option>
                <option value='ORG_ADMIN'>ORG_ADMIN</option>
              </select>
              <button className='ide-btn' onClick={invite} disabled={busy || !inviteEmail.trim()}>
                {busy ? 'Inviting…' : `Send org ${inviteRole.toLowerCase()} invite`}
              </button>
              <Link to='/projects' style={{fontSize: 12, color: 'var(--ide-blue)'}}>
                Need to invite an external contractor? Open a project Members page.
              </Link>
            </div>
          )}
        </div>
      </div>

      <div className='ide-h2'>Members · {members.length}</div>
      {!loaded ? (
        <IdeLoader label='Loading members…' />
      ) : members.length === 0 ? (
        <div className='ide-empty'>No members found.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {members.map((m, idx) => {
            const mAny = m as unknown as {
              id?: string;
              userId?: string;
              email?: string;
              firstName?: string;
              lastName?: string;
              orgRole?: string;
              lastSeen?: string;
            };
            const uid = orgMemberId(mAny);
            const isMe = uid === user?.id;
            const av = orgMemberInitials(mAny);
            const roleBusy = rowBusy === `role-${uid}`;
            const removeBusy = rowBusy === `remove-${uid}`;
            const displayName = orgMemberDisplayName(mAny);
            return (
              <div
                key={mAny.id || mAny.userId}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 12,
                  padding: '11px 16px',
                  borderBottom: idx < members.length - 1 ? '1px solid var(--ide-border)' : 'none',
                }}
              >
                <div
                  style={{
                    width: 30,
                    height: 30,
                    borderRadius: '50%',
                    flexShrink: 0,
                    display: 'grid',
                    placeItems: 'center',
                    fontSize: 11,
                    fontWeight: 700,
                    color: 'var(--ide-accent-fg)',
                    background: 'var(--ide-accent)',
                  }}
                >
                  {av}
                </div>
                <div style={{minWidth: 0, flex: 1}}>
                  <div style={{fontSize: 13, fontWeight: 500, color: 'var(--ide-fg-bright)'}}>
                    {displayName}{' '}
                    {isMe && (
                      <span style={{color: 'var(--ide-fg-muted)', fontWeight: 400}}>· you</span>
                    )}
                  </div>
                  <div
                    style={{
                      fontSize: 11.5,
                      color: 'var(--ide-fg-muted)',
                      display: 'flex',
                      gap: 6,
                      flexWrap: 'wrap',
                    }}
                  >
                    <span>{mAny.email}</span>
                    {isMe && isSuperAdmin && (
                      <>
                        <span>·</span>
                        <span>SUPER_ADMIN</span>
                      </>
                    )}
                    {mAny.lastSeen && (
                      <>
                        <span>·</span>
                        <span>last seen {new Date(mAny.lastSeen).toLocaleDateString()}</span>
                      </>
                    )}
                  </div>
                </div>
                <div style={{display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0}}>
                  {isMe && isSuperAdmin && <IdeBadge kind='warn'>SUPER_ADMIN</IdeBadge>}
                  {canInvite && !isMe ? (
                    <>
                      <select
                        className='ide-select'
                        aria-label={`Organization role for ${mAny.email || displayName}`}
                        value={mAny.orgRole || 'DEVELOPER'}
                        disabled={roleBusy || removeBusy}
                        onChange={(e) => changeRole(uid, e.target.value)}
                        style={{width: 'auto', padding: '4px 8px', fontSize: 12}}
                      >
                        <option value='DEVELOPER'>DEVELOPER</option>
                        <option value='BILLING_ADMIN'>BILLING_ADMIN</option>
                        <option value='ORG_ADMIN'>ORG_ADMIN</option>
                      </select>
                      <button
                        className='ide-btn ghost'
                        style={{padding: '4px 10px', fontSize: 12}}
                        disabled={removeBusy || roleBusy}
                        onClick={() =>
                          setConfirmRemove({
                            userId: uid,
                            label: displayName.trim() || mAny.email || 'this member',
                          })
                        }
                      >
                        {removeBusy ? '…' : 'Remove'}
                      </button>
                    </>
                  ) : (
                    <IdeBadge kind={mAny.orgRole === 'ORG_ADMIN' ? 'ok' : 'warn'}>
                      {mAny.orgRole || 'DEVELOPER'}
                    </IdeBadge>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {invites.length > 0 && (
        <>
          <div className='ide-h2'>Pending invites · {invites.length}</div>
          <div className='ide-card' style={{padding: 0}}>
            {invites.map((iv, idx) => {
              const ivAny = iv as unknown as {
                id?: string;
                email?: string;
                invitedEmail?: string;
                orgRole?: string;
                expiresAt?: string;
                status?: string;
              };
              const email = ivAny.invitedEmail || ivAny.email || '—';
              return (
                <div
                  key={ivAny.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 12,
                    padding: '11px 16px',
                    borderBottom: idx < invites.length - 1 ? '1px solid var(--ide-border)' : 'none',
                  }}
                >
                  <div style={{minWidth: 0, flex: 1}}>
                    <div style={{fontSize: 13, fontWeight: 500, color: 'var(--ide-fg-bright)'}}>
                      {email}
                    </div>
                    <div
                      style={{
                        fontSize: 11.5,
                        color: 'var(--ide-fg-muted)',
                        display: 'flex',
                        gap: 6,
                        flexWrap: 'wrap',
                      }}
                    >
                      <span>{ivAny.orgRole || 'DEVELOPER'}</span>
                      {ivAny.expiresAt && (
                        <>
                          <span>·</span>
                          <span>expires {new Date(ivAny.expiresAt).toLocaleDateString()}</span>
                        </>
                      )}
                    </div>
                  </div>
                  {canInvite ? (
                    <div style={{display: 'flex', gap: 6, flexShrink: 0}}>
                      <button
                        className='ide-btn ghost'
                        style={{padding: '4px 10px', fontSize: 12}}
                        onClick={() => resend(ivAny.id || '')}
                        disabled={
                          rowBusy === `resend-${ivAny.id}` || rowBusy === `cancel-${ivAny.id}`
                        }
                      >
                        {rowBusy === `resend-${ivAny.id}` ? '…' : 'Resend'}
                      </button>
                      <button
                        className='ide-btn ghost'
                        style={{padding: '4px 10px', fontSize: 12, color: 'var(--ide-red)'}}
                        onClick={() => cancelInvite(ivAny.id || '')}
                        disabled={
                          rowBusy === `cancel-${ivAny.id}` || rowBusy === `resend-${ivAny.id}`
                        }
                      >
                        {rowBusy === `cancel-${ivAny.id}` ? '…' : 'Cancel'}
                      </button>
                    </div>
                  ) : (
                    <IdeBadge kind='warn'>{ivAny.status || 'pending'}</IdeBadge>
                  )}
                </div>
              );
            })}
          </div>
        </>
      )}

      <ConfirmModal
        open={!!confirmRemove}
        title='Remove member?'
        body={`${confirmRemove?.label || 'This member'} will lose access to all org projects, work, and billing. This action is irreversible.`}
        confirmLabel='Remove'
        danger
        busy={!!confirmRemove && rowBusy === `remove-${confirmRemove.userId}`}
        onClose={() => setConfirmRemove(null)}
        onConfirm={() => {
          if (confirmRemove) void removeOne(confirmRemove.userId);
        }}
      />
    </div>
  );
}

function CreateOrgPanel({
  org,
  onCreated,
}: {
  org: api.OrgDto | null;
  onCreated: (org: api.OrgDto) => void;
}) {
  const user = useAuthStore((s) => s.user);
  const tier = useAuthStore(selectNormalizedTier);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [domains, setDomains] = useState(domainListFromEmail(user?.email).join(', '));
  const [adminEmail, setAdminEmail] = useState(user?.email ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const canCreate = tier !== 'FREE' && (!org || org.isPersonal === true);
  const showCreateAction = canCreate || !org;
  const workspaceSetupPending = isTeamWorkspaceSetupPending(org, tier);

  async function submit() {
    const parsedDomains = parseDomainList(domains);
    if (!name.trim()) {
      setErr('Enter an organization name.');
      return;
    }
    if (parsedDomains.length === 0) {
      setErr('Add at least one company email domain.');
      return;
    }
    if (!adminEmail.trim()) {
      setErr('Add an admin contact email.');
      return;
    }
    setBusy(true);
    setErr('');
    try {
      const created = await api.createOrg({
        name: name.trim(),
        domains: parsedDomains,
        adminEmail: adminEmail.trim(),
      });
      onCreated(created);
      await useAuthStore.getState().loadMe();
      setOpen(false);
      setName('');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Organization creation failed.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className='ide-card' style={{marginBottom: 12}}>
      <div style={{display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap'}}>
        <div style={{minWidth: 0, flex: 1}}>
          <div style={{fontSize: 13.5, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
            {workspaceSetupPending ? 'Team workspace setup' : 'Organization workspace'}
          </div>
          <div style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', lineHeight: 1.5}}>
            {workspaceSetupPending
              ? 'Your paid workspace has team controls enabled. Add organization name and domains so buyers do not see a personal workspace shell.'
              : org?.isPersonal
                ? 'Convert your personal workspace into a team organization before inviting teammates.'
                : org
                  ? 'You are already in an organization. Multi-org switching is not available yet.'
                  : 'Create an organization before inviting teammates.'}
          </div>
        </div>
        {showCreateAction ? (
          <button
            className='ide-btn'
            disabled={!canCreate}
            onClick={() => setOpen((v) => !v)}
            title={
              tier === 'FREE' ? 'Organization creation is available after upgrading.' : undefined
            }
          >
            {open
              ? 'Cancel'
              : workspaceSetupPending
                ? 'Finish setup'
                : org?.isPersonal
                  ? 'Convert to org'
                  : 'Create org'}
          </button>
        ) : (
          <IdeBadge kind='ok'>Active org</IdeBadge>
        )}
      </div>

      {tier === 'FREE' && (
        <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 10}}>
          Organization creation is a paid-tier feature.{' '}
          <Link to='/plans' style={{color: 'var(--ide-accent)'}}>
            View plans
          </Link>
          .
        </div>
      )}

      {open && canCreate && (
        <div style={{marginTop: 12, borderTop: '1px solid var(--ide-border)', paddingTop: 12}}>
          <Field label='Organization name'>
            <input
              className='ide-input'
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder='Acme Engineering'
            />
          </Field>
          <Field label='Allowed email domains'>
            <input
              className='ide-input ide-mono'
              value={domains}
              onChange={(e) => setDomains(e.target.value)}
              placeholder='acme.com, acme.io'
            />
          </Field>
          <Field label='Admin contact'>
            <input
              className='ide-input'
              type='email'
              value={adminEmail}
              onChange={(e) => setAdminEmail(e.target.value)}
              placeholder='admin@acme.com'
            />
          </Field>
          {err && (
            <div style={{fontSize: 12.5, color: 'var(--ide-red)', marginBottom: 10}}>{err}</div>
          )}
          <button className='ide-btn' disabled={busy} onClick={submit}>
            {busy
              ? 'Creating…'
              : workspaceSetupPending
                ? 'Save team workspace'
                : org?.isPersonal
                  ? 'Convert workspace'
                  : 'Create organization'}
          </button>
        </div>
      )}
    </div>
  );
}

// Org settings — /org/settings
export function IdeOrgSettings() {
  useDocumentTitle('Org settings');
  const user = useAuthStore((s) => s.user);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const canEdit = isSuperAdmin || user?.orgRole === 'ORG_ADMIN';
  const [org, setOrg] = useState<api.OrgDto | null>(null);
  const [orgLoaded, setOrgLoaded] = useState(false);
  const [sso, setSso] = useState<api.SsoConfig | null>(null);
  const [name, setName] = useState('');
  const [domains, setDomains] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  // SSO form state.
  const [ssoProvider, setSsoProvider] = useState<api.SsoProvider>('GOOGLE_OIDC');
  const [ssoMetadataUrl, setSsoMetadataUrl] = useState('');
  const [ssoDomain, setSsoDomain] = useState('');
  const [ssoEnforced, setSsoEnforced] = useState(false);
  const [confirmClearSso, setConfirmClearSso] = useState(false);
  const [clearSsoBusy, setClearSsoBusy] = useState(false);
  // V198 — busy flag for the external-sharing toggle so consecutive clicks
  // don't race the PUT.
  const [extBusy, setExtBusy] = useState(false);
  // Org-wide BYOK provider keys. Inputs are write-only — raw keys are never
  // returned (only has*Key flags) — and a blank field leaves the stored key
  // unchanged, so each provider saves independently without wiping the others.
  const [keyInputs, setKeyInputs] = useState({
    anthropic: '',
    openai: '',
    gemini: '',
    openrouter: '',
    deepseek: '',
    fireworks: '',
  });
  const [keyBusy, setKeyBusy] = useState<string | null>(null);
  // F3 — per-org model-matrix overrides. `matrix` is the server view (defaults +
  // current overrides + picker options); `matrixOverrides` is the editable copy.
  const [matrix, setMatrix] = useState<api.ModelMatrixView | null>(null);
  const [matrixOverrides, setMatrixOverrides] = useState<Record<string, string>>({});
  const [matrixBusy, setMatrixBusy] = useState(false);
  const [managedAiPolicy, setManagedAiPolicy] = useState<api.ManagedAiPolicy | null>(null);
  // TIER-4 — matrix saves affect every run the org submits, so the Save button
  // routes through a ConfirmModal.
  const [confirmMatrixSave, setConfirmMatrixSave] = useState(false);
  // Seat adjustment (TEAM tier) — current value seeded from the org seat count.
  const [seats, setSeats] = useState(1);
  const [seatBusy, setSeatBusy] = useState(false);
  // Org members — drives the per-member suspend control.
  const [members, setMembers] = useState<api.OrgMemberDto[] | null>(null);
  const [suspendBusy, setSuspendBusy] = useState<string | null>(null);
  // Org audit log — loaded lazily on first expand to keep first paint cheap.
  const [audit, setAudit] = useState<api.AuditLogEntry[] | null>(null);
  const [auditOpen, setAuditOpen] = useState(false);
  const [auditBusy, setAuditBusy] = useState(false);

  useEffect(() => {
    Promise.allSettled([
      api.getMyOrg(),
      api.getSsoConfig(),
      api.getOrgModelMatrix(),
      api.getManagedAiPolicy().catch(() => null),
      api.getOrgMembers(),
    ]).then(([o, s, m, managed, mem]) => {
      if (o.status === 'fulfilled') {
        setOrg(o.value);
        setName(o.value.name || '');
        setDomains((o.value as unknown as {domains?: string[]}).domains?.join(', ') || '');
        setSeats(o.value.seatCount ?? 1);
      }
      if (s.status === 'fulfilled') {
        setSso(s.value);
        if (s.value.provider) setSsoProvider(s.value.provider);
        if (s.value.metadataUrl) setSsoMetadataUrl(s.value.metadataUrl);
        if (s.value.domain) setSsoDomain(s.value.domain);
        setSsoEnforced(!!s.value.enforced);
      }
      if (m.status === 'fulfilled') {
        setMatrix(m.value);
        setMatrixOverrides(m.value.overrides || {});
      }
      if (managed.status === 'fulfilled' && managed.value) setManagedAiPolicy(managed.value);
      if (mem.status === 'fulfilled') setMembers(mem.value);
      else setMembers([]);
      setOrgLoaded(true);
    });
  }, []);

  async function saveMatrix() {
    setMatrixBusy(true);
    setErr('');
    setMsg('');
    try {
      const next = await api.updateOrgModelMatrix(matrixOverrides);
      setMatrix(next);
      setMatrixOverrides(next.overrides || {});
      setMsg('Model matrix saved.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setMatrixBusy(false);
    }
  }

  async function saveOrg() {
    setBusy(true);
    setErr('');
    setMsg('');
    try {
      await api.updateOrg({
        name: name.trim() || undefined,
        domains: domains ? parseDomainList(domains) : undefined,
      });
      setMsg('Org saved.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setBusy(false);
    }
  }

  async function saveSso() {
    setBusy(true);
    setErr('');
    setMsg('');
    try {
      await api.updateSsoConfig({
        provider: ssoProvider,
        enforced: ssoEnforced,
        metadataUrl: ssoMetadataUrl.trim() || null,
        domain: ssoDomain.trim() || null,
      });
      setMsg('SSO config saved.');
      setSso(await api.getSsoConfig());
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'SSO save failed.');
    } finally {
      setBusy(false);
    }
  }

  async function performClearSso() {
    setClearSsoBusy(true);
    try {
      await api.clearSsoConfig();
      setSso(null);
      setMsg('SSO cleared.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Clear SSO failed.');
    } finally {
      setClearSsoBusy(false);
      setConfirmClearSso(false);
    }
  }

  async function saveSeats() {
    const requestedSeats = Math.max(2, seats);
    setSeatBusy(true);
    setErr('');
    setMsg('');
    try {
      const res = await api.adjustOrgSeats(requestedSeats);
      setMsg(res.message || `Seats updated to ${res.newSeats}.`);
      setSeats(res.newSeats);
      // Reflect the new count in the local org snapshot + subtitle.
      setOrg((prev) => (prev ? {...prev, seatCount: res.newSeats} : prev));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Seat update failed.');
    } finally {
      setSeatBusy(false);
    }
  }

  async function toggleSuspend(userId: string, suspended: boolean) {
    setSuspendBusy(userId);
    setErr('');
    setMsg('');
    try {
      await api.orgSuspendMember(userId, suspended);
      setMembers(await api.getOrgMembers());
      setMsg(suspended ? 'Member suspended.' : 'Member reinstated.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Suspend failed.');
    } finally {
      setSuspendBusy(null);
    }
  }

  async function loadAudit() {
    setAuditOpen(true);
    if (audit !== null) return;
    setAuditBusy(true);
    try {
      const page = await api.getAuditLogs({orgScoped: true, size: 50});
      setAudit(page.content);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Audit load failed.');
      setAudit([]);
    } finally {
      setAuditBusy(false);
    }
  }

  async function saveOrgKey(provider: OrgKeyProvider) {
    const value = keyInputs[provider].trim();
    if (!value) {
      setErr('Enter a key first.');
      return;
    }
    setKeyBusy(provider);
    setErr('');
    setMsg('');
    try {
      const next = await api.updateOrgAiKeySettings(
        provider === 'anthropic' ? value : '',
        provider === 'openai' ? value : '',
        provider === 'gemini' ? value : '',
        provider === 'openrouter' ? value : '',
        provider === 'deepseek' ? value : '',
        provider === 'fireworks' ? value : '',
      );
      setOrg(next);
      setKeyInputs((k) => ({...k, [provider]: ''}));
      setMsg('Provider key saved.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setKeyBusy(null);
    }
  }

  if (!orgLoaded) return <IdeLoader label='Loading org…' />;
  if (!org) {
    return (
      <div className='ide-pane' style={{maxWidth: 820}}>
        <h1 className='ide-h1'>Org settings</h1>
        <div className='ide-sub'>Create an organization to invite teammates</div>
        <Banner err={err} msg={msg} />
        <CreateOrgPanel
          org={null}
          onCreated={(next) => {
            setOrg(next);
            setName(next.name || '');
            setDomains(next.domainPatterns || '');
            setSeats(next.seatCount ?? 1);
          }}
        />
      </div>
    );
  }

  const orgKeyRows = buildOrgKeyRows(org);
  const orgTier = normalizedOrgTier(org);
  const isTeamTier = orgTier === 'TEAM';
  const workspaceSetupPending = isTeamWorkspaceSetupPending(
    org as api.OrgDto & {seats?: number},
    orgTier,
  );
  const seatDisplayCount = orgSeatDisplayCount(org as api.OrgDto & {seats?: number}, orgTier);
  const teamSeatInputValue = Math.max(2, seats);
  const ssoProviderConfigOnly = isSamlConfigOnly(ssoProvider);

  return (
    <div className='ide-pane' style={{maxWidth: 820}}>
      <h1 className='ide-h1'>Org settings</h1>
      <div className='ide-sub'>
        {workspaceSetupPending ? 'Workspace setup pending' : org.name || 'Personal org'} ·{' '}
        {seatDisplayCount} seats
      </div>

      <Banner err={err} msg={msg} />
      <CreateOrgPanel
        org={org}
        onCreated={(next) => {
          setOrg(next);
          setName(next.name || '');
          setDomains(next.domainPatterns || '');
          setSeats(next.seatCount ?? 1);
        }}
      />

      {/* ── Profile ── */}
      <div className='ide-h2'>Profile</div>
      <div className='ide-card'>
        <Field label='Org name'>
          <input
            className='ide-input'
            aria-label='Organization name'
            value={name}
            onChange={(e) => setName(e.target.value)}
            disabled={!canEdit}
          />
        </Field>
        <Field label='Authorised email domains (comma-separated)'>
          <input
            className='ide-input'
            aria-label='Organization authorized email domains'
            value={domains}
            onChange={(e) => setDomains(e.target.value)}
            disabled={!canEdit}
            placeholder='acme.co, acme.com'
          />
        </Field>
        {canEdit && (
          <button className='ide-btn' style={{width: '100%'}} disabled={busy} onClick={saveOrg}>
            {busy ? 'Saving…' : 'Save org profile'}
          </button>
        )}
      </div>

      {/* ── Seats ── */}
      <div className='ide-h2'>Seats</div>
      {isTeamTier ? (
        <div className='ide-card'>
          <div
            style={{
              fontSize: 12.5,
              color: 'var(--ide-fg-muted)',
              lineHeight: 1.5,
              marginBottom: 12,
            }}
          >
            {workspaceSetupPending
              ? 'TEAM has a 2-seat minimum. Complete workspace setup before buyer demos; the stored personal-org seat value can still be 1 until billing sync finishes.'
              : "TEAM-tier orgs share a seat pool. Changing the count syncs the prorated quantity to your billing gateway. You can't reduce below the current active member count."}
          </div>
          <Field label='Seats'>
            <div style={{display: 'flex', gap: 8, alignItems: 'center'}}>
              <input
                className='ide-input'
                type='number'
                aria-label='Team seat count'
                min={2}
                max={20}
                value={teamSeatInputValue}
                onChange={(e) => setSeats(Math.max(2, Math.min(20, Number(e.target.value) || 2)))}
                disabled={!canEdit}
                style={{width: 120}}
              />
              {canEdit && (
                <button
                  className='ide-btn'
                  style={{flexShrink: 0}}
                  disabled={seatBusy || teamSeatInputValue === (org.seatCount ?? 1)}
                  onClick={saveSeats}
                >
                  {seatBusy ? 'Updating…' : 'Update seats'}
                </button>
              )}
            </div>
          </Field>
        </div>
      ) : (
        <div className='ide-card'>
          <div style={{fontSize: 13.5, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
            {orgTier === 'ENTERPRISE'
              ? 'Enterprise seats are contract-managed'
              : 'Seat changes are unavailable'}
          </div>
          <div
            style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', lineHeight: 1.5, marginTop: 6}}
          >
            This org currently reports {seatDisplayCount} seat
            {seatDisplayCount === 1 ? '' : 's'}. Self-serve seat adjustment only applies to
            TEAM-tier billing. To give someone access to a project, open the project&apos;s{' '}
            <Link to='/projects'>Members</Link> tab; to invite them into the organization first, use{' '}
            <Link to='/org-members'>Team access</Link>.
          </div>
        </div>
      )}

      {/* ── Single sign-on ── */}
      <div className='ide-h2'>Single sign-on</div>
      {/* TIER-5 — SAML login enforcement is not implemented yet (config storage
          only, per docs/sso-saml-wiring.md), so the copy + the SAML chips say
          so instead of promising enforcement. */}
      <InfoCard>
        {orgTier === 'FREE' || orgTier === 'PRO' || orgTier === 'PRO_PLUS'
          ? 'Single sign-on is an Enterprise setup item. This page shows current status only for your tier.'
          : 'Pin login to your IdP — Google OIDC, Okta SAML, or Azure AD SAML. Once SAML enforcement is enabled for your org (contact us), members signing in with another method will be blocked.'}
      </InfoCard>
      <div className='ide-card'>
        <Field label='Provider'>
          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
            {(['GOOGLE_OIDC', 'OKTA_SAML', 'AZURE_AD_SAML'] as api.SsoProvider[]).map((p) => {
              const samlConfigOnly = isSamlConfigOnly(p);
              return (
                <button
                  key={p}
                  type='button'
                  onClick={
                    canEdit && orgTier === 'ENTERPRISE'
                      ? () => {
                          setSsoProvider(p);
                          if (isSamlConfigOnly(p)) setSsoEnforced(false);
                        }
                      : undefined
                  }
                  disabled={!canEdit || orgTier !== 'ENTERPRISE'}
                  className='ide-badge'
                  title={
                    samlConfigOnly
                      ? 'SAML sign-in is not yet enforced — configuration is stored; contact us to enable login enforcement.'
                      : undefined
                  }
                  style={{
                    padding: '5px 11px',
                    cursor: canEdit && orgTier === 'ENTERPRISE' ? 'pointer' : 'default',
                    borderColor: ssoProvider === p ? 'var(--ide-accent)' : undefined,
                    color: ssoProvider === p ? 'var(--ide-accent)' : undefined,
                  }}
                >
                  {p.replace(/_/g, ' ')}
                  {samlConfigOnly ? ' · config only' : ''}
                </button>
              );
            })}
          </div>
        </Field>
        <Field label='Metadata URL'>
          <input
            className='ide-input'
            aria-label='SSO metadata URL'
            value={ssoMetadataUrl}
            onChange={(e) => setSsoMetadataUrl(e.target.value)}
            disabled={!canEdit || orgTier !== 'ENTERPRISE'}
            placeholder='https://accounts.google.com/.well-known/openid-configuration'
          />
        </Field>
        <Field label='Login domain'>
          <input
            className='ide-input'
            aria-label='SSO login domain'
            value={ssoDomain}
            onChange={(e) => setSsoDomain(e.target.value)}
            disabled={!canEdit || orgTier !== 'ENTERPRISE'}
            placeholder='acme.com'
          />
        </Field>
        <Field label='Enforce SSO'>
          <label
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              fontSize: 13,
              color: 'var(--ide-fg-muted)',
            }}
          >
            <input
              type='checkbox'
              aria-label='Enforce SSO for organization'
              checked={ssoEnforced}
              disabled={!canEdit || orgTier !== 'ENTERPRISE' || ssoProviderConfigOnly}
              onChange={(e) => setSsoEnforced(e.target.checked)}
            />
            {ssoProviderConfigOnly
              ? 'SAML enforcement is not enabled yet; this provider is config-only.'
              : 'Require SSO for all members on the login domain.'}
          </label>
        </Field>
        {canEdit && orgTier === 'ENTERPRISE' && (
          <div style={{display: 'flex', gap: 8}}>
            <button className='ide-btn' style={{flex: 1}} disabled={busy} onClick={saveSso}>
              {busy ? 'Saving…' : 'Save SSO'}
            </button>
            {sso && (
              <button
                className='ide-btn ghost'
                style={{color: 'var(--ide-red)', flexShrink: 0}}
                onClick={() => setConfirmClearSso(true)}
              >
                Clear
              </button>
            )}
          </div>
        )}
      </div>

      {/* ── AI provider keys ── */}
      <div className='ide-h2'>AI provider keys</div>
      <InfoCard>
        {orgTier === 'FREE' || orgTier === 'PRO' || orgTier === 'PRO_PLUS'
          ? 'Org-wide provider keys are available on BYOK, TEAM, and ENTERPRISE workspaces. This page shows current status only for your tier.'
          : "Org-wide provider keys shared by every member. Work using a provider you supply a key for bypasses the platform cost cap. Keys are stored server-side under this deployment's configured crypto policy and are never shown again — paste a new value to replace one, or leave a field blank to keep the current key."}
      </InfoCard>
      <div className='ide-card' style={{padding: 0}}>
        {orgKeyRows.map((row, i) => (
          <div
            key={row.provider}
            style={{
              padding: '14px 16px',
              borderBottom: i < orgKeyRows.length - 1 ? '1px solid var(--ide-border)' : 'none',
            }}
          >
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: 8,
              }}
            >
              <div style={{fontSize: 13.5, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
                {row.label}
              </div>
              <IdeBadge kind={row.connected ? 'ok' : 'warn'}>
                {row.connected ? 'Connected' : 'Not set'}
              </IdeBadge>
            </div>
            <div style={{display: 'flex', gap: 8}}>
              <input
                className='ide-input ide-mono'
                type='password'
                aria-label={`${row.label} organization provider key`}
                autoComplete='off'
                placeholder={
                  row.connected ? 'Paste a new key to replace' : `Paste your ${row.label} key`
                }
                value={keyInputs[row.provider]}
                onChange={(e) => setKeyInputs((k) => ({...k, [row.provider]: e.target.value}))}
                disabled={!canEdit || ['FREE', 'PRO', 'PRO_PLUS'].includes(orgTier)}
                style={{flex: 1}}
              />
              <button
                className='ide-btn'
                style={{flexShrink: 0}}
                disabled={
                  !canEdit ||
                  ['FREE', 'PRO', 'PRO_PLUS'].includes(orgTier) ||
                  keyBusy === row.provider
                }
                onClick={() => saveOrgKey(row.provider)}
              >
                {keyBusy === row.provider ? 'Saving…' : 'Save'}
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* ── Model matrix (F3) ── */}
      <div className='ide-h2'>AI routing policy</div>
      {/* TIER-4 — legend directly above the first tier card: what the tiers
          mean, what "Default · <model>" resolves from, and who can edit. The
          non-ENTERPRISE upgrade note sits here too — above the grid. */}
      <InfoCard>
        Tiers: <strong>Planning</strong> (complex reasoning) · <strong>Workhorse</strong> (default)
        · <strong>Lightweight</strong> (cheap/fast). &lsquo;Default · &lt;model&gt;&rsquo; follows
        the platform matrix; pick a model to override it for this org&apos;s work. Editable on BYOK,
        TEAM, and ENTERPRISE.
        {matrix && !matrix.editable && (
          <>
            {' '}
            <strong>Your org&apos;s grid is read-only — upgrade to override models.</strong>
          </>
        )}
      </InfoCard>
      <div className='ide-card' style={{marginBottom: 12}}>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            gap: 12,
            alignItems: 'flex-start',
            flexWrap: 'wrap',
            marginBottom: 10,
          }}
        >
          <div>
            <div style={{fontSize: 13.5, fontWeight: 700, color: 'var(--ide-fg-bright)'}}>
              Managed AI policy
            </div>
            <div style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', marginTop: 2}}>
              Effective server policy for this account. Enforcement is backend-side and can step
              models up or down during execution.
            </div>
          </div>
          {managedAiPolicy && (
            <IdeBadge kind={managedAiPolicy.enabled ? 'ok' : 'warn'}>
              {managedAiPolicy.enabled ? 'Enabled' : 'Disabled'}
            </IdeBadge>
          )}
        </div>
        {managedAiPolicy ? (
          <>
            <div className='ide-kv'>
              <span>Tier</span>
              <span>{managedAiPolicy.tier}</span>
            </div>
            <div className='ide-kv'>
              <span>Included credits</span>
              <span>
                {hasUnlimitedAiCredits({
                  unlimitedAiCredits: managedAiPolicy.unlimitedAiCredits,
                  includedAiCredits: managedAiPolicy.includedCredits,
                })
                  ? 'Unlimited'
                  : `${managedAiPolicy.includedCredits} AI credits`}
              </span>
            </div>
            <div className='ide-kv'>
              <span>Step-down</span>
              <span>automatic near the managed-AI cap</span>
            </div>
            <div className='ide-kv' style={{borderBottom: 'none'}}>
              <span>Concurrency / rate limits</span>
              <span>
                {managedAiPolicy.concurrencyEnforced ? 'concurrency on' : 'concurrency off'} ·{' '}
                {managedAiPolicy.rateLimitsEnabled ? 'rate limits on' : 'rate limits off'}
              </span>
            </div>
          </>
        ) : (
          <div style={{fontSize: 13, color: 'var(--ide-fg-muted)'}}>
            Managed-AI policy is unavailable on this server profile.
          </div>
        )}
      </div>
      {matrix &&
        matrix.tiers.map((t) => (
          <div className='ide-card' key={t} style={{marginBottom: 10}}>
            <div
              style={{
                fontSize: 13,
                fontWeight: 700,
                textTransform: 'capitalize',
                marginBottom: 10,
                color: 'var(--ide-fg-bright)',
              }}
            >
              {t}
            </div>
            {matrixProviderRows(matrix, t, matrixOverrides).map((row) => (
              <div
                key={row.key}
                style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8}}
              >
                <div
                  style={{
                    width: 92,
                    flexShrink: 0,
                    fontSize: 12.5,
                    color: 'var(--ide-fg-muted)',
                    textTransform: 'capitalize',
                  }}
                >
                  {row.provider}
                </div>
                <select
                  className='ide-select'
                  aria-label={`${t} ${row.provider} model override`}
                  value={row.selectedOverride}
                  disabled={!canEdit || !matrix.editable}
                  onChange={(e) => {
                    const v = e.target.value;
                    setMatrixOverrides((prev) => {
                      const nextO = {...prev};
                      if (!v) delete nextO[row.key];
                      else nextO[row.key] = v;
                      return nextO;
                    });
                  }}
                  style={{
                    flex: 1,
                    minWidth: 0,
                    cursor: !canEdit || !matrix.editable ? 'not-allowed' : 'pointer',
                  }}
                >
                  <option value=''>Default · {row.defaultModel}</option>
                  {row.models.map((mdl) => (
                    <option key={mdl} value={mdl}>
                      {matrixModelLabel(matrix, mdl)}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
        ))}
      {matrix && matrix.editable && canEdit && (
        <button
          className='ide-btn'
          style={{width: '100%'}}
          disabled={matrixBusy}
          onClick={() => setConfirmMatrixSave(true)}
        >
          {matrixBusy ? 'Saving…' : 'Save model matrix'}
        </button>
      )}

      {/* ── External project collaborators (V198) ── */}
      <div className='ide-h2'>Project external collaborators</div>
      <div className='ide-card'>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'flex-start',
            gap: 12,
          }}
        >
          <div style={{minWidth: 0, flex: 1}}>
            <div
              style={{
                fontSize: 13.5,
                fontWeight: 600,
                marginBottom: 4,
                color: 'var(--ide-fg-bright)',
              }}
            >
              Allow project-only collaborators outside your org
            </div>
            <div style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', lineHeight: 1.5}}>
              When on, org admins and super admins can invite any email — including Gmail or Outlook
              — to a single project as a viewer, developer, or project admin. This does not make
              them organization members and does not bypass the work-domain requirement for org
              membership. When off, project access is limited to existing org members.
            </div>
          </div>
          <button
            type='button'
            role='switch'
            aria-label='Allow external project collaborators'
            aria-checked={!!org.externalSharingEnabled}
            disabled={!canEdit || extBusy}
            // eslint-disable-next-line no-restricted-syntax -- explicit pending state via `extBusy` already disables this toggle while in flight.
            onClick={async () => {
              setExtBusy(true);
              setErr('');
              setMsg('');
              try {
                const next = await api.updateExternalSharing(!org.externalSharingEnabled);
                setOrg(next);
                setMsg(
                  `Project external collaborators ${
                    next.externalSharingEnabled ? 'enabled' : 'disabled'
                  }.`,
                );
              } catch (e: unknown) {
                setErr(e instanceof Error ? e.message : 'Toggle failed.');
              } finally {
                setExtBusy(false);
              }
            }}
            style={{
              position: 'relative',
              width: 44,
              height: 32,
              flexShrink: 0,
              background: org.externalSharingEnabled
                ? 'var(--ide-accent)'
                : 'var(--ide-bg-tab-inactive)',
              border: '1px solid var(--ide-border)',
              borderRadius: 999,
              cursor: canEdit ? 'pointer' : 'not-allowed',
              opacity: canEdit ? 1 : 0.5,
              transition: 'background 0.2s',
            }}
          >
            <span
              style={{
                position: 'absolute',
                top: 1,
                left: org.externalSharingEnabled ? 13 : 1,
                width: 28,
                height: 28,
                background: '#fff',
                borderRadius: '50%',
                transition: 'left 0.2s',
              }}
            />
          </button>
        </div>
      </div>

      {/* ── Members (suspend/reinstate) ── */}
      <div className='ide-h2'>Members</div>
      <InfoCard>
        Suspend a member to block their org access without deleting them. Org-member invites require
        work-domain email. For Gmail or contractor access, use the Members page inside a specific
        project. Manage roles and org invites from{' '}
        <Link to='/org-members' style={{color: 'var(--ide-blue)'}}>
          Team access
        </Link>
        .
      </InfoCard>
      {members === null ? (
        <IdeLoader label='Loading members…' />
      ) : members.length === 0 ? (
        <div className='ide-empty'>No members found.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {members.map((m, i) => {
            const mName = orgMemberDisplayName(m);
            const isSelf = m.userId === user?.id;
            return (
              <div
                key={m.userId}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  gap: 12,
                  padding: '12px 16px',
                  borderBottom: i < members.length - 1 ? '1px solid var(--ide-border)' : 'none',
                }}
              >
                <div style={{minWidth: 0, flex: 1}}>
                  <div style={{fontSize: 13, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
                    {mName}
                    {isSelf && (
                      <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}> · you</span>
                    )}
                  </div>
                  <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}>
                    {m.email} · {m.orgRole}
                  </div>
                </div>
                {canEdit && !isSelf && (
                  <div style={{display: 'flex', gap: 6, flexShrink: 0}}>
                    <button
                      className='ide-btn ghost'
                      style={{padding: '4px 10px', fontSize: 12, color: 'var(--ide-red)'}}
                      disabled={suspendBusy === m.userId}
                      onClick={() => toggleSuspend(m.userId, true)}
                    >
                      {suspendBusy === m.userId ? '…' : 'Suspend'}
                    </button>
                    <button
                      className='ide-btn ghost'
                      style={{padding: '4px 10px', fontSize: 12}}
                      disabled={suspendBusy === m.userId}
                      onClick={() => toggleSuspend(m.userId, false)}
                    >
                      Reinstate
                    </button>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* ── Audit log ── */}
      <div className='ide-h2'>Audit log</div>
      <div className='ide-card' style={{padding: 0}}>
        {!auditOpen ? (
          <div style={{padding: 16}}>
            <div style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', marginBottom: 12}}>
              Org-scoped audit trail — role changes, SSO, project-collaborator toggles, member
              actions.
            </div>
            <button className='ide-btn ghost' onClick={loadAudit}>
              Load audit log
            </button>
          </div>
        ) : auditBusy || audit === null ? (
          <div style={{padding: 16}}>
            <IdeLoader label='Loading audit log…' />
          </div>
        ) : audit.length === 0 ? (
          <div style={{padding: 16, fontSize: 12.5, color: 'var(--ide-fg-muted)'}}>
            No audit entries yet.
          </div>
        ) : (
          audit.map((a, i) => (
            <div
              key={a.id}
              style={{
                padding: '11px 16px',
                borderBottom: i < audit.length - 1 ? '1px solid var(--ide-border)' : 'none',
              }}
            >
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  gap: 8,
                  alignItems: 'baseline',
                }}
              >
                <span style={{fontSize: 13, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
                  {a.eventType}
                </span>
                <span style={{fontSize: 11, color: 'var(--ide-fg-muted)', flexShrink: 0}}>
                  {a.createdAt ? new Date(a.createdAt).toLocaleString() : ''}
                </span>
              </div>
              <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)', marginTop: 2}}>
                {a.entityType}
                {a.entityId ? ` · ${a.entityId.slice(0, 12)}` : ''}
                {a.actorEmail ? ` · by ${a.actorEmail}` : ''}
              </div>
            </div>
          ))
        )}
      </div>

      {/* ── Compliance ── */}
      <div className='ide-h2'>Compliance</div>
      <div className='ide-card'>
        <div className='ide-kv'>
          <span>SOC 2</span>
          <IdeBadge kind='ok'>Type II ready</IdeBadge>
        </div>
        <div className='ide-kv'>
          <span>GDPR</span>
          <IdeBadge kind='ok'>Compliant</IdeBadge>
        </div>
        <div className='ide-kv'>
          <span>DPA</span>
          <a href='/dpa' style={{color: 'var(--ide-blue)'}}>
            View DPA
          </a>
        </div>
        <div className='ide-kv' style={{borderBottom: 'none'}}>
          <span>Audit log</span>
          <a href='/audit' style={{color: 'var(--ide-blue)'}}>
            Open audit log
          </a>
        </div>
      </div>

      <ConfirmModal
        open={confirmClearSso}
        title='Clear SSO config?'
        body='All members will fall back to password / OAuth on next login. Active sessions are preserved until they expire.'
        confirmLabel='Clear SSO'
        danger
        busy={clearSsoBusy}
        onClose={() => setConfirmClearSso(false)}
        onConfirm={performClearSso}
      />

      {/* TIER-4 — confirm before applying org-wide model overrides. */}
      <ConfirmModal
        open={confirmMatrixSave}
        title='Update model matrix'
        body='These overrides apply to every run submitted by members of this org.'
        confirmLabel='Save'
        busy={matrixBusy}
        onClose={() => setConfirmMatrixSave(false)}
        onConfirm={saveMatrix}
      />
    </div>
  );
}
