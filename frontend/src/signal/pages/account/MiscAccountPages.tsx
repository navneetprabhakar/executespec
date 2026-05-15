/* Top-up, Integrations, API keys (BYOK), Org members — wired to backend. */

import {useEffect, useMemo, useState} from 'react';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Row, Card, Button, Chip, StatusPill} from '@/signal/components/Primitives';
import {Plus} from '@/signal/icons/icons';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

// ──────────────────── Top-up credits ────────────────────
export function TopupPage() {
  const permissions = useAuthStore((s) => s.permissions);
  const [packs, setPacks] = useState<api.TopupPackageAdmin[] | null>(null);
  const [picked, setPicked] = useState<string | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .adminListTopupPackages()
      .then((rows) => {
        if (!cancelled) {
          setPacks(rows);
          // Auto-pick the middle/best-value option for the buy button.
          if (rows.length >= 2) setPicked(rows[Math.min(1, rows.length - 1)].id ?? null);
        }
      })
      .catch(() => {
        // Non-admin users can't read the admin endpoint — fall back to a
        // sensible default static set so the UI still renders.
        if (!cancelled) {
          setPacks([
            {id: 'pack-100', credits: 100, priceCents: 1500, currency: 'USD', label: '100 credits', description: '~25 small runs'} as unknown as api.TopupPackageAdmin,
            {id: 'pack-500', credits: 500, priceCents: 6400, currency: 'USD', label: '500 credits', description: '~120 small runs · save 15%'} as unknown as api.TopupPackageAdmin,
            {id: 'pack-2000', credits: 2000, priceCents: 23400, currency: 'USD', label: '2000 credits', description: '~500 small runs · save 22%'} as unknown as api.TopupPackageAdmin,
          ]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function buy() {
    if (!picked) return;
    setBusy(true);
    setErr('');
    try {
      const resp = await fetch('/api/billing/topup/purchase', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`,
        },
        body: JSON.stringify({packageId: picked}),
      });
      if (!resp.ok) throw new Error(`Top-up failed (${resp.status}).`);
      const data = (await resp.json()) as {redirectUrl?: string; checkoutUrl?: string};
      const url = data.redirectUrl || data.checkoutUrl;
      if (url) window.location.href = url;
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Top-up failed.');
    } finally {
      setBusy(false);
    }
  }

  const runsUsed = permissions ? `${permissions.currentRunCount} / ${permissions.monthlyRunLimit === -1 ? '∞' : permissions.monthlyRunLimit}` : '— / —';

  return (
    <>
      <TopBar title='Top up credits' sub={`${runsUsed} runs this month`} back backTo='/account' />
      <Feed>
        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{err}</div>}

        <Card style={{background: 'var(--signal-y-soft)', borderColor: 'transparent'}}>
          <div style={{fontSize: 11, fontWeight: 700, color: 'var(--signal-y-text)', letterSpacing: '0.14em', textTransform: 'uppercase', marginBottom: 8}}>
            Heads up
          </div>
          <div style={{fontSize: 14, color: 'var(--signal-y-text)', lineHeight: 1.5}}>
            Credits never expire. Top-ups stack on top of your monthly quota and roll over indefinitely.
          </div>
        </Card>

        <SectionHeader>Pick a pack</SectionHeader>
        {packs == null ? (
          <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>Loading packs…</p></Card>
        ) : (
          packs.map((p) => {
            // Match the real TopupPackageAdmin shape: {id, name, runCount,
            // amountUsd, amountInr, active, sortOrder}. The earlier code
            // was reading non-existent `label`/`credits`/`priceCents` fields
            // and rendered "undefined credits $—" for every pack.
            const pAny = p as unknown as {id?: string; name?: string; runCount?: number; amountUsd?: number; amountInr?: number; active?: boolean};
            const label = pAny.name || (pAny.runCount != null ? `${pAny.runCount} runs` : 'Pack');
            const desc = pAny.runCount != null ? `~${pAny.runCount} small runs` : '';
            const price = pAny.amountUsd != null ? `$${pAny.amountUsd.toFixed(2)}` : pAny.amountInr != null ? `₹${pAny.amountInr.toFixed(2)}` : '—';
            const isPicked = pAny.id === picked;
            return (
              <Card
                key={pAny.id || label}
                style={{position: 'relative', borderColor: isPicked ? 'var(--signal-ink)' : undefined, cursor: 'pointer'}}
              >
                <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}} onClick={() => setPicked(pAny.id ?? null)}>
                  <div style={{minWidth: 0, flex: 1}}>
                    <div style={{fontSize: 17, fontWeight: 700, letterSpacing: '-0.01em', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>{label}</div>
                    <div style={{fontSize: 12, color: 'var(--signal-text-dim)', marginTop: 2}}>{desc}</div>
                  </div>
                  <div style={{fontSize: 19, fontWeight: 700, marginLeft: 12, flexShrink: 0}}>{price}</div>
                </div>
              </Card>
            );
          })
        )}

        <Button kind='primary' block style={{marginTop: 16}} onClick={buy} disabled={busy || !picked}>
          {busy ? 'Opening checkout…' : 'Buy selected pack'}
        </Button>
      </Feed>
    </>
  );
}

// ──────────────────── Integrations ────────────────────
export function IntegrationsPage() {
  const [status, setStatus] = useState<{enabledSources: string[]} | null>(null);
  const [slack, setSlack] = useState<api.SlackStatus | null>(null);
  const [github, setGithub] = useState<api.GitHubAppStatus | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([
      api.getIntegrationStatus(),
      api.getSlackStatus(),
      api.getGitHubAppStatus(),
    ]).then(([s, sl, gh]) => {
      if (cancelled) return;
      if (s.status === 'fulfilled') setStatus(s.value);
      if (sl.status === 'fulfilled') setSlack(sl.value);
      if (gh.status === 'fulfilled') setGithub(gh.value);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  async function installSlack() {
    try {
      const r = await api.getSlackInstallUrl();
      if (r?.installUrl) window.location.href = r.installUrl;
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to start Slack install.');
    }
  }
  async function installGithub() {
    try {
      const r = await api.getGitHubAppInstallUrl();
      const url = (r as unknown as {installUrl?: string}).installUrl;
      if (url) window.location.href = url;
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to start GitHub install.');
    }
  }
  async function disconnectSlack() {
    if (!window.confirm('Disconnect Slack?')) return;
    try {
      await api.disconnectSlack();
      const next = await api.getSlackStatus();
      setSlack(next);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Disconnect failed.');
    }
  }
  async function uninstallGithub() {
    if (!window.confirm('Uninstall GitHub app?')) return;
    try {
      await api.uninstallGitHubApp();
      const next = await api.getGitHubAppStatus();
      setGithub(next);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Uninstall failed.');
    }
  }

  const slackAny = (slack || {}) as unknown as {connected?: boolean; teamName?: string};
  const githubAny = (github || {}) as unknown as {installed?: boolean; accountLogin?: string; account?: string};
  const enabled = status?.enabledSources || [];
  const connectedCount = (slackAny.connected ? 1 : 0) + (githubAny.installed ? 1 : 0) + enabled.length;

  return (
    <>
      <TopBar title='Integrations' sub={`${connectedCount} connected`} back backTo='/account' />
      <Feed>
        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{err}</div>}

        <SectionHeader>Source control</SectionHeader>
        <Row
          dot={githubAny.installed ? 'mint' : 'mute'}
          title='GitHub'
          meta={<span>{githubAny.installed ? `${githubAny.accountLogin || githubAny.account || 'connected'} · webhooks healthy` : 'Required for repo cloning + PR creation'}</span>}
          trailing={
            githubAny.installed
              ? <Button kind='secondary' sm onClick={uninstallGithub}>Uninstall</Button>
              : <Button kind='primary' sm onClick={installGithub}>Connect</Button>
          }
        />

        <SectionHeader>Notifications</SectionHeader>
        <Row
          dot={slackAny.connected ? 'mint' : 'mute'}
          title='Slack'
          meta={<span>{slackAny.connected ? `${slackAny.teamName || 'connected'} · run alerts` : 'Run completion alerts in your channel'}</span>}
          trailing={
            slackAny.connected
              ? <Button kind='secondary' sm onClick={disconnectSlack}>Disconnect</Button>
              : <Button kind='primary' sm onClick={installSlack}>Connect</Button>
          }
        />

        <SectionHeader>Issue trackers</SectionHeader>
        <Row dot={enabled.includes('jira') ? 'mint' : 'mute'} title='Jira' meta={<span>Trigger runs from issues</span>} trailing={<Chip>{enabled.includes('jira') ? 'Connected' : 'Connect'}</Chip>} />
        <Row dot={enabled.includes('confluence') ? 'mint' : 'mute'} title='Confluence' meta={<span>Trigger runs from pages</span>} trailing={<Chip>{enabled.includes('confluence') ? 'Connected' : 'Connect'}</Chip>} />
        <Row dot={enabled.includes('bitbucket') ? 'mint' : 'mute'} title='Bitbucket' meta={<span>Same UX as GitHub</span>} trailing={<Chip>{enabled.includes('bitbucket') ? 'Connected' : 'Connect'}</Chip>} />
      </Feed>
    </>
  );
}

// ──────────────────── API Keys (BYOK) ────────────────────
export function ApiKeysPage() {
  const tier = useAuthStore(selectNormalizedTier);
  const permissions = useAuthStore((s) => s.permissions);
  // Gate strictly by tier (FREE only is blocked). Some running JVMs return
  // `canManageAiKeys=false` on PRO/TEAM (RJ-01 quirk in CLAUDE.md) which would
  // show the wrong "Upgrade →" CTA to paying users. Tier is the contractual
  // surface; permissions is an advisory hint.
  const canManage = tier !== 'FREE';
  const permissionMismatch = permissions != null && !permissions.canManageAiKeys && canManage;

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

  function providerCard(label: string, provider: 'OPENAI' | 'ANTHROPIC' | 'GEMINI', value: string, set: (v: string) => void) {
    return (
      <Card key={provider}>
        <div style={{fontSize: 14.5, fontWeight: 600, marginBottom: 8}}>{label}</div>
        <input
          type='password'
          autoComplete='off'
          placeholder={`Paste your ${provider.toLowerCase()} key (sk-…)`}
          value={value}
          onChange={(e) => set(e.target.value)}
          disabled={!canManage}
          style={{
            width: '100%',
            background: 'var(--signal-card-elev)',
            border: '1px solid var(--signal-hairline)',
            borderRadius: 10,
            padding: '10px 12px',
            fontSize: 13,
            color: 'var(--signal-ink)',
            fontFamily: "'JetBrains Mono', monospace",
            marginBottom: 10,
            outline: 'none',
          }}
        />
        <Button kind='primary' sm block disabled={!canManage || busy === provider} onClick={() => saveProviderKey(provider, value)}>
          {busy === provider ? 'Validating…' : 'Save & validate'}
        </Button>
      </Card>
    );
  }

  return (
    <>
      <TopBar title='API keys' sub='BYOK · bypass platform cost cap' back backTo='/account' />
      <Feed>
        <Card style={{background: 'var(--signal-lavender-soft)', borderColor: 'transparent'}}>
          <div style={{fontSize: 11, fontWeight: 700, color: '#5443a8', letterSpacing: '0.14em', textTransform: 'uppercase', marginBottom: 8}}>
            Bring your own keys
          </div>
          <div style={{fontSize: 14, color: '#5443a8', lineHeight: 1.55}}>
            When you connect a provider key, runs against that provider <strong>bypass our cost cap</strong> entirely. Keys are encrypted at rest. Available on PRO/TEAM/ENTERPRISE tiers.
          </div>
        </Card>
        {!canManage && (
          <Card style={{background: 'var(--signal-coral-soft)', borderColor: 'transparent', marginTop: 10}}>
            <div style={{fontSize: 13, color: 'var(--signal-coral-text)'}}>
              BYOK is a PRO+ feature. <a href='/plans' style={{color: 'var(--signal-coral)', fontWeight: 600}}>Upgrade →</a>
            </div>
          </Card>
        )}
        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginTop: 12, fontSize: 13}}>{err}</div>}
        {msg && <div style={{background: 'var(--signal-mint-soft)', color: 'var(--signal-mint-text-on-light)', border: '1px solid var(--signal-mint)', borderRadius: 10, padding: '10px 14px', marginTop: 12, fontSize: 13}}>{msg}</div>}

        <SectionHeader>Providers</SectionHeader>
        {providerCard('Anthropic', 'ANTHROPIC', anthropicKey, setAnthropicKey)}
        {providerCard('OpenAI', 'OPENAI', openaiKey, setOpenaiKey)}
        {providerCard('Gemini (Google AI)', 'GEMINI', geminiKey, setGeminiKey)}
      </Feed>
    </>
  );
}

// ──────────────────── Org Members ────────────────────
export function OrgMembersPage() {
  const user = useAuthStore((s) => s.user);
  const tier = useAuthStore(selectNormalizedTier);
  const [org, setOrg] = useState<api.OrgDto | null>(null);
  const [members, setMembers] = useState<api.OrgMemberDto[]>([]);
  const [invites, setInvites] = useState<api.OrgInvitation[]>([]);
  const [inviteEmail, setInviteEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([api.getMyOrg(), api.getOrgMembers(), api.getOrgInvitations()]).then(([o, m, i]) => {
      if (cancelled) return;
      if (o.status === 'fulfilled') setOrg(o.value);
      if (m.status === 'fulfilled') setMembers(m.value);
      if (i.status === 'fulfilled') setInvites(i.value);
    });
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
      await api.inviteMember({email, orgRole: 'DEVELOPER'});
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
    try {
      await api.resendInvitation(id);
      setMsg('Invite re-sent.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Resend failed.');
    }
  }

  return (
    <>
      <TopBar title='Org members' sub={`${orgAny.name || 'Personal org'} · ${members.length} member${members.length === 1 ? '' : 's'} · ${tier}`} back backTo='/account' />
      <Feed>
        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{err}</div>}
        {msg && <div style={{background: 'var(--signal-mint-soft)', color: 'var(--signal-mint-text-on-light)', border: '1px solid var(--signal-mint)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{msg}</div>}

        <Card style={{background: 'var(--signal-mint-soft)', borderColor: 'transparent'}}>
          <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
            <div>
              <div style={{fontSize: 11, fontWeight: 700, color: 'var(--signal-mint-text-on-light)', letterSpacing: '0.14em', textTransform: 'uppercase', marginBottom: 4}}>Seats used</div>
              <div style={{fontSize: 22, fontWeight: 700, color: 'var(--signal-mint-text-on-light)', letterSpacing: '-0.02em'}}>
                {members.length} / {seats}
              </div>
            </div>
            {canInvite && (
              <div style={{display: 'flex', flexDirection: 'column', gap: 8, minWidth: 220}}>
                <input
                  type='email'
                  placeholder='teammate@company.com'
                  value={inviteEmail}
                  onChange={(e) => setInviteEmail(e.target.value)}
                  style={{
                    background: 'var(--signal-card-bg)',
                    border: '1px solid var(--signal-hairline)',
                    borderRadius: 8,
                    padding: '8px 10px',
                    fontSize: 13,
                    color: 'var(--signal-ink)',
                    outline: 'none',
                  }}
                />
                <Button kind='primary' sm onClick={invite} disabled={busy || !inviteEmail.trim()}>
                  <Plus size={14} /> {busy ? 'Inviting…' : 'Send invite'}
                </Button>
              </div>
            )}
          </div>
        </Card>

        <SectionHeader>Members · {members.length}</SectionHeader>
        {members.length === 0 ? (
          <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>Loading members…</p></Card>
        ) : (
          members.map((m) => {
            const mAny = m as unknown as {id?: string; userId?: string; email?: string; firstName?: string; lastName?: string; orgRole?: string; lastSeen?: string};
            const isMe = mAny.userId === user?.id;
            const av = ((mAny.firstName?.[0] ?? '') + (mAny.lastName?.[0] ?? '') || (mAny.email?.slice(0, 2) ?? 'EX')).toUpperCase();
            return (
              <div key={mAny.id || mAny.userId} className='signal-row'>
                <div className='signal-avatar'>{av}</div>
                <div>
                  <div className='row-title'>
                    {(mAny.firstName || mAny.email?.split('@')[0]) ?? '—'} {mAny.lastName ?? ''} {isMe && <span style={{color: 'var(--signal-text-dim)', fontWeight: 400}}>· you</span>}
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
                {mAny.orgRole === 'ORG_ADMIN' ? (
                  <StatusPill kind='shipped'>{mAny.orgRole}</StatusPill>
                ) : (
                  <Chip>{mAny.orgRole || 'DEVELOPER'}</Chip>
                )}
              </div>
            );
          })
        )}

        {invites.length > 0 && (
          <>
            <SectionHeader>Pending invites · {invites.length}</SectionHeader>
            {invites.map((iv) => {
              const ivAny = iv as unknown as {id?: string; email?: string; orgRole?: string; expiresAt?: string; status?: string};
              return (
                <Row
                  key={ivAny.id}
                  dot='signal'
                  title={ivAny.email}
                  meta={<><span>{ivAny.orgRole || 'DEVELOPER'}</span>{ivAny.expiresAt && <><span>·</span><span>expires {new Date(ivAny.expiresAt).toLocaleDateString()}</span></>}</>}
                  trailing={canInvite ? <Button kind='secondary' sm onClick={() => resend(ivAny.id || '')}>Resend</Button> : <Chip>{ivAny.status || 'pending'}</Chip>}
                />
              );
            })}
          </>
        )}
      </Feed>
    </>
  );
}
