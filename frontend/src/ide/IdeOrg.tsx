/* IDE-native port of the Signal SETTINGS cluster:
 *   - IdeApiKeys     ← signal/pages/account/MiscAccountPages.tsx ApiKeysPage    (/api-keys)
 *   - IdeOrgMembers  ← signal/pages/account/MiscAccountPages.tsx OrgMembersPage (/org-members)
 *   - IdeOrgSettings ← signal/pages/LegalPages.tsx OrgSettingsPage              (/org/settings)
 *
 * Data/API/actions/validation mirror the Signal originals EXACTLY — only the
 * presentation is re-rendered in the IDE visual language. Keys are write-only
 * (Connected / Not-set pills, blank input = leave unchanged); secrets are never
 * echoed back. Destructive confirms route through ConfirmModal (no window.confirm).
 */

import {useEffect, useState} from 'react';
import {Link} from 'react-router-dom';
import * as api from '@/lib/api';
import {useAuthStore, selectNormalizedTier, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import {ConfirmModal} from '@/signal/components/Modal';

// ───────────────────────── shared bits ─────────────────────────

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

// ───────────────────────── API keys (BYOK) — /api-keys ─────────────────────────
export function IdeApiKeys() {
  const tier = useAuthStore(selectNormalizedTier);
  // Gate strictly by tier (FREE only is blocked). Some running JVMs return
  // `canManageAiKeys=false` on PRO/TEAM (RJ-01 quirk) which would show the wrong
  // CTA to paying users. Tier is the contractual surface, so we ignore the
  // advisory `permissions.canManageAiKeys` hint here — same as the Signal page.
  const canManage = tier !== 'FREE';

  const [openaiKey, setOpenaiKey] = useState('');
  const [anthropicKey, setAnthropicKey] = useState('');
  const [geminiKey, setGeminiKey] = useState('');
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState<'OPENAI' | 'ANTHROPIC' | 'GEMINI' | null>(null);

  async function saveProviderKey(provider: 'OPENAI' | 'ANTHROPIC' | 'GEMINI', key: string) {
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
      );
      setMsg(`${provider} key saved. Runs against ${provider} now bypass the platform cap.`);
      if (provider === 'OPENAI') setOpenaiKey('');
      if (provider === 'ANTHROPIC') setAnthropicKey('');
      if (provider === 'GEMINI') setGeminiKey('');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setBusy(null);
    }
  }

  function ProviderRow(
    label: string,
    provider: 'OPENAI' | 'ANTHROPIC' | 'GEMINI',
    value: string,
    set: (v: string) => void,
    last: boolean,
  ) {
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
        <div style={{display: 'flex', gap: 8}}>
          <input
            className='ide-input ide-mono'
            type='password'
            autoComplete='off'
            placeholder={`Paste your ${provider.toLowerCase()} key (sk-…)`}
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
      </div>
    );
  }

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <div className='ide-h1'>API keys</div>
      <div className='ide-sub'>BYOK · bypass platform cost cap</div>

      <InfoCard>
        When you connect a provider key, runs against that provider{' '}
        <strong>bypass our cost cap</strong> entirely. Keys are encrypted at rest. Available on
        PRO/TEAM/ENTERPRISE tiers.
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
          BYOK is a PRO+ feature.{' '}
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
        {ProviderRow('Gemini (Google AI)', 'GEMINI', geminiKey, setGeminiKey, true)}
      </div>
    </div>
  );
}

// ───────────────────────── Org members — /org-members ─────────────────────────
export function IdeOrgMembers() {
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

  const canInvite = user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN';
  const seats = (org as unknown as {seats?: number; usedSeats?: number})?.seats ?? members.length;
  const orgAny = (org || {}) as unknown as {name?: string};

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
      <div className='ide-h1'>Org members</div>
      <div className='ide-sub'>
        {orgAny.name || 'Personal org'} · {members.length} member{members.length === 1 ? '' : 's'} ·{' '}
        {tier}
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
              {members.length} / {seats}
            </div>
          </div>
          {canInvite && (
            <div style={{display: 'flex', flexDirection: 'column', gap: 8, minWidth: 240}}>
              <input
                className='ide-input'
                type='email'
                placeholder='teammate@company.com'
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
              />
              <select
                className='ide-select'
                value={inviteRole}
                onChange={(e) => setInviteRole(e.target.value as typeof inviteRole)}
              >
                <option value='DEVELOPER'>DEVELOPER</option>
                <option value='BILLING_ADMIN'>BILLING_ADMIN</option>
                <option value='ORG_ADMIN'>ORG_ADMIN</option>
              </select>
              <button className='ide-btn' onClick={invite} disabled={busy || !inviteEmail.trim()}>
                {busy ? 'Inviting…' : `Send ${inviteRole.toLowerCase()} invite`}
              </button>
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
            const isMe = mAny.userId === user?.id;
            const av = (
              (mAny.firstName?.[0] ?? '') + (mAny.lastName?.[0] ?? '') ||
              (mAny.email?.slice(0, 2) ?? 'EX')
            ).toUpperCase();
            const uid = mAny.userId || mAny.id || '';
            const roleBusy = rowBusy === `role-${uid}`;
            const removeBusy = rowBusy === `remove-${uid}`;
            const displayName =
              ((mAny.firstName || mAny.email?.split('@')[0]) ?? '—') +
              (mAny.lastName ? ` ${mAny.lastName}` : '');
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
                    {(mAny.firstName || mAny.email?.split('@')[0]) ?? '—'} {mAny.lastName ?? ''}{' '}
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
                    {mAny.lastSeen && (
                      <>
                        <span>·</span>
                        <span>last seen {new Date(mAny.lastSeen).toLocaleDateString()}</span>
                      </>
                    )}
                  </div>
                </div>
                <div style={{display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0}}>
                  {canInvite && !isMe ? (
                    <>
                      <select
                        className='ide-select'
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
                orgRole?: string;
                expiresAt?: string;
                status?: string;
              };
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
                      {ivAny.email}
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
                    <button
                      className='ide-btn ghost'
                      style={{padding: '4px 10px', fontSize: 12, flexShrink: 0}}
                      onClick={() => resend(ivAny.id || '')}
                      disabled={rowBusy === `resend-${ivAny.id}`}
                    >
                      {rowBusy === `resend-${ivAny.id}` ? '…' : 'Resend'}
                    </button>
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
        body={`${confirmRemove?.label || 'This member'} will lose access to all org projects, runs, and billing. This action is irreversible.`}
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

// ───────────────────────── Org settings — /org/settings ─────────────────────────
export function IdeOrgSettings() {
  const user = useAuthStore((s) => s.user);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const canEdit = isSuperAdmin || user?.orgRole === 'ORG_ADMIN';
  const [org, setOrg] = useState<api.OrgDto | null>(null);
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
  });
  const [keyBusy, setKeyBusy] = useState<string | null>(null);
  // F3 — per-org model-matrix overrides. `matrix` is the server view (defaults +
  // current overrides + picker options); `matrixOverrides` is the editable copy.
  const [matrix, setMatrix] = useState<api.ModelMatrixView | null>(null);
  const [matrixOverrides, setMatrixOverrides] = useState<Record<string, string>>({});
  const [matrixBusy, setMatrixBusy] = useState(false);
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
      api.getOrgMembers(),
    ]).then(([o, s, m, mem]) => {
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
      if (mem.status === 'fulfilled') setMembers(mem.value);
      else setMembers([]);
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
        domains: domains
          ? domains
              .split(',')
              .map((d) => d.trim())
              .filter(Boolean)
          : undefined,
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
    setSeatBusy(true);
    setErr('');
    setMsg('');
    try {
      const res = await api.adjustOrgSeats(seats);
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

  async function saveOrgKey(provider: 'anthropic' | 'openai' | 'gemini' | 'openrouter') {
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

  if (!org) return <IdeLoader label='Loading org…' />;

  // OpenRouter is an Enterprise-only gateway — only offer it when the org
  // qualifies (the backend rejects the key with 403 otherwise).
  const isEnterprise = (org.tier || '').toUpperCase() === 'ENTERPRISE';
  const orgKeyRows: {
    provider: 'anthropic' | 'openai' | 'gemini' | 'openrouter';
    label: string;
    connected: boolean;
  }[] = [
    {provider: 'anthropic', label: 'Anthropic', connected: !!org.hasAnthropicKey},
    {provider: 'openai', label: 'OpenAI', connected: !!org.hasOpenAiKey},
    {provider: 'gemini', label: 'Gemini', connected: !!org.hasGeminiKey},
    ...(isEnterprise
      ? [{provider: 'openrouter' as const, label: 'OpenRouter', connected: !!org.hasOpenRouterKey}]
      : []),
  ];

  const seatCount = (org as unknown as {seats?: number}).seats ?? 1;

  return (
    <div className='ide-pane' style={{maxWidth: 820}}>
      <div className='ide-h1'>Org settings</div>
      <div className='ide-sub'>
        {org.name || 'Personal org'} · {seatCount} seats
      </div>

      <Banner err={err} msg={msg} />

      {/* ── Profile ── */}
      <div className='ide-h2'>Profile</div>
      <div className='ide-card'>
        <Field label='Org name'>
          <input
            className='ide-input'
            value={name}
            onChange={(e) => setName(e.target.value)}
            disabled={!canEdit}
          />
        </Field>
        <Field label='Authorised email domains (comma-separated)'>
          <input
            className='ide-input'
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
      <div className='ide-card'>
        <div
          style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', lineHeight: 1.5, marginBottom: 12}}
        >
          TEAM-tier orgs share a seat pool (min 2). Changing the count syncs the prorated quantity
          to your billing gateway. You can&apos;t reduce below the current active member count.
        </div>
        <Field label='Seats'>
          <div style={{display: 'flex', gap: 8, alignItems: 'center'}}>
            <input
              className='ide-input'
              type='number'
              min={1}
              max={20}
              value={seats}
              onChange={(e) => setSeats(Math.max(1, Math.min(20, Number(e.target.value) || 1)))}
              disabled={!canEdit}
              style={{width: 120}}
            />
            {canEdit && (
              <button
                className='ide-btn'
                style={{flexShrink: 0}}
                disabled={seatBusy || seats === (org.seatCount ?? 1)}
                onClick={saveSeats}
              >
                {seatBusy ? 'Updating…' : 'Update seats'}
              </button>
            )}
          </div>
        </Field>
      </div>

      {/* ── Single sign-on ── */}
      <div className='ide-h2'>Single sign-on (SSO)</div>
      <InfoCard>
        Pin login to your IdP — Google OIDC, Okta SAML, or Azure AD SAML. Members signing in with
        another method are blocked once SSO is enforced.
      </InfoCard>
      <div className='ide-card'>
        <Field label='Provider'>
          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
            {(['GOOGLE_OIDC', 'OKTA_SAML', 'AZURE_AD_SAML'] as api.SsoProvider[]).map((p) => (
              <button
                key={p}
                type='button'
                onClick={canEdit ? () => setSsoProvider(p) : undefined}
                disabled={!canEdit}
                className='ide-badge'
                style={{
                  padding: '5px 11px',
                  cursor: canEdit ? 'pointer' : 'default',
                  borderColor: ssoProvider === p ? 'var(--ide-accent)' : undefined,
                  color: ssoProvider === p ? 'var(--ide-accent)' : undefined,
                }}
              >
                {p.replace(/_/g, ' ')}
              </button>
            ))}
          </div>
        </Field>
        <Field label='Metadata URL'>
          <input
            className='ide-input'
            value={ssoMetadataUrl}
            onChange={(e) => setSsoMetadataUrl(e.target.value)}
            disabled={!canEdit}
            placeholder='https://accounts.google.com/.well-known/openid-configuration'
          />
        </Field>
        <Field label='Login domain'>
          <input
            className='ide-input'
            value={ssoDomain}
            onChange={(e) => setSsoDomain(e.target.value)}
            disabled={!canEdit}
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
              checked={ssoEnforced}
              disabled={!canEdit}
              onChange={(e) => setSsoEnforced(e.target.checked)}
            />
            Require SSO for all members on the login domain.
          </label>
        </Field>
        {canEdit && (
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
        Org-wide BYOK keys shared by every member. Runs against a provider you supply a key for
        bypass the platform cost cap. Keys are encrypted at rest and never shown again — paste a new
        value to replace one, or leave a field blank to keep the current key.
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
                autoComplete='off'
                placeholder={
                  row.connected ? 'Paste a new key to replace' : `Paste your ${row.label} key`
                }
                value={keyInputs[row.provider]}
                onChange={(e) => setKeyInputs((k) => ({...k, [row.provider]: e.target.value}))}
                disabled={!canEdit}
                style={{flex: 1}}
              />
              <button
                className='ide-btn'
                style={{flexShrink: 0}}
                disabled={!canEdit || keyBusy === row.provider}
                onClick={() => saveOrgKey(row.provider)}
              >
                {keyBusy === row.provider ? 'Saving…' : 'Save'}
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* ── Model matrix (F3) ── */}
      <div className='ide-h2'>Model matrix</div>
      <InfoCard>
        Pin which model each agent tier uses, per provider. Cells left on <em>Default</em> follow
        the platform matrix; an override applies to every run your org submits on that provider.{' '}
        {matrix && !matrix.editable && <strong>Overrides are an Enterprise-tier feature.</strong>}
      </InfoCard>
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
            {matrix.providers
              .filter((p) => matrix.defaults[`${p}:${t}`])
              .map((p) => {
                const key = `${p}:${t}`;
                const def = matrix.defaults[key];
                const val = matrixOverrides[key] ?? '';
                return (
                  <div
                    key={key}
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
                      {p}
                    </div>
                    <select
                      className='ide-select'
                      value={val}
                      disabled={!canEdit || !matrix.editable}
                      onChange={(e) => {
                        const v = e.target.value;
                        setMatrixOverrides((prev) => {
                          const nextO = {...prev};
                          if (!v) delete nextO[key];
                          else nextO[key] = v;
                          return nextO;
                        });
                      }}
                      style={{
                        flex: 1,
                        minWidth: 0,
                        cursor: !canEdit || !matrix.editable ? 'not-allowed' : 'pointer',
                      }}
                    >
                      <option value=''>Default · {def}</option>
                      {(matrix.modelsByProvider[p] ?? []).map((mdl) => (
                        <option key={mdl} value={mdl}>
                          {mdl}
                        </option>
                      ))}
                    </select>
                  </div>
                );
              })}
          </div>
        ))}
      {matrix && matrix.editable && canEdit && (
        <button
          className='ide-btn'
          style={{width: '100%'}}
          disabled={matrixBusy}
          onClick={saveMatrix}
        >
          {matrixBusy ? 'Saving…' : 'Save model matrix'}
        </button>
      )}

      {/* ── External sharing (V198) ── */}
      <div className='ide-h2'>External sharing</div>
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
              Allow inviting people outside your org
            </div>
            <div style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', lineHeight: 1.5}}>
              When on, you (org admin) or a super admin can invite any email — including gmail /
              outlook — as a read-only or developer collaborator on a single project. When off,
              project invites are restricted to existing org members.
            </div>
          </div>
          <button
            type='button'
            role='switch'
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
                setMsg(`External sharing ${next.externalSharingEnabled ? 'enabled' : 'disabled'}.`);
              } catch (e: unknown) {
                setErr(e instanceof Error ? e.message : 'Toggle failed.');
              } finally {
                setExtBusy(false);
              }
            }}
            style={{
              position: 'relative',
              width: 44,
              height: 24,
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
                left: org.externalSharingEnabled ? 21 : 1,
                width: 20,
                height: 20,
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
        Suspend a member to block their access without removing them from the org. Reinstate to
        restore it. Manage roles + invites from{' '}
        <Link to='/org-members' style={{color: 'var(--ide-blue)'}}>
          Org members
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
            const mName = [m.firstName, m.lastName].filter(Boolean).join(' ').trim() || m.email;
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
              Org-scoped audit trail — role changes, SSO + sharing toggles, member actions.
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
    </div>
  );
}
