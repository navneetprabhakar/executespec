/* Top-up, Integrations, API keys (BYOK), Org members — wired to backend. */

import {useEffect, useMemo, useState} from 'react';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Row, Card, Button, Chip, StatusPill} from '@/signal/components/Primitives';
import {LoadingState, SkeletonRow} from '@/signal/components/Loaders';
import {Plus} from '@/signal/icons/icons';
import {GitHubLogo, SlackLogo, JiraLogo, ConfluenceLogo, BitbucketLogo} from '@/signal/components/BrandLogos';
import {PromptModal, ConfirmModal} from '@/signal/components/Modal';
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
          <LoadingState label="Loading packs…" />
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
  // Per-provider credential modal (Jira / Confluence / Bitbucket).
  const [connectOpen, setConnectOpen] = useState<null | 'jira' | 'confluence' | 'bitbucket'>(null);
  const [connectBusy, setConnectBusy] = useState(false);
  // Disconnect confirmation state — replaces window.confirm for proper a11y.
  const [disconnectOpen, setDisconnectOpen] = useState<null | 'slack' | 'github'>(null);
  const [disconnectBusy, setDisconnectBusy] = useState(false);

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
  async function performSlackDisconnect() {
    setDisconnectBusy(true);
    try {
      await api.disconnectSlack();
      const next = await api.getSlackStatus();
      setSlack(next);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Disconnect failed.');
    } finally {
      setDisconnectBusy(false);
      setDisconnectOpen(null);
    }
  }
  async function performGithubUninstall() {
    setDisconnectBusy(true);
    try {
      await api.uninstallGitHubApp();
      const next = await api.getGitHubAppStatus();
      setGithub(next);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Uninstall failed.');
    } finally {
      setDisconnectBusy(false);
      setDisconnectOpen(null);
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
        <IntegrationRow
          logo={<GitHubLogo size={20} />}
          connected={!!githubAny.installed}
          title='GitHub'
          desc={githubAny.installed ? `${githubAny.accountLogin || githubAny.account || 'connected'} · webhooks healthy` : 'Required for repo cloning + PR creation'}
          action={
            githubAny.installed
              ? <Button kind='secondary' sm onClick={() => setDisconnectOpen('github')}>Uninstall</Button>
              : <Button kind='primary' sm onClick={installGithub}>Connect</Button>
          }
        />
        <IntegrationRow
          logo={<BitbucketLogo size={20} />}
          connected={enabled.includes('bitbucket')}
          title='Bitbucket'
          desc='Same UX as GitHub'
          action={
            enabled.includes('bitbucket')
              ? <Chip kind='mint'>Connected</Chip>
              : <Button kind='primary' sm onClick={() => setConnectOpen('bitbucket')}>Connect</Button>
          }
        />

        <SectionHeader>Notifications</SectionHeader>
        <IntegrationRow
          logo={<SlackLogo size={20} />}
          connected={!!slackAny.connected}
          title='Slack'
          desc={slackAny.connected ? `${slackAny.teamName || 'connected'} · run alerts` : 'Run completion alerts in your channel'}
          action={
            slackAny.connected
              ? <Button kind='secondary' sm onClick={() => setDisconnectOpen('slack')}>Disconnect</Button>
              : <Button kind='primary' sm onClick={installSlack}>Connect</Button>
          }
        />

        <SectionHeader>Issue trackers</SectionHeader>
        <IntegrationRow
          logo={<JiraLogo size={20} />}
          connected={enabled.includes('jira')}
          title='Jira'
          desc='Trigger runs from issues'
          action={
            enabled.includes('jira')
              ? <Chip kind='mint'>Connected</Chip>
              : <Button kind='primary' sm onClick={() => setConnectOpen('jira')}>Connect</Button>
          }
        />
        <IntegrationRow
          logo={<ConfluenceLogo size={20} />}
          connected={enabled.includes('confluence')}
          title='Confluence'
          desc='Trigger runs from pages'
          action={
            enabled.includes('confluence')
              ? <Chip kind='mint'>Connected</Chip>
              : <Button kind='primary' sm onClick={() => setConnectOpen('confluence')}>Connect</Button>
          }
        />
      </Feed>

      {/* Per-provider credential prompts. Each posts to its dedicated
          /api/auth/settings/{provider} endpoint via the typed api helpers. */}
      <PromptModal
        open={connectOpen === 'jira'}
        title='Connect Jira'
        body='Paste your Jira Cloud credentials. The API token is stored encrypted at rest.'
        fields={[
          {name: 'baseUrl', label: 'Base URL', placeholder: 'https://yourco.atlassian.net', required: true},
          {name: 'email', label: 'Email', placeholder: 'you@yourco.com', required: true},
          {name: 'apiToken', label: 'API token', placeholder: 'ATATT…', required: true},
        ]}
        busy={connectBusy}
        onClose={() => setConnectOpen(null)}
        onSubmit={async (v) => {
          setConnectBusy(true);
          try {
            await api.updateJiraSettings(v.baseUrl, v.email, v.apiToken);
            setConnectOpen(null);
            const next = await api.getIntegrationStatus();
            setStatus(next);
          } catch (e: unknown) {
            setErr(e instanceof Error ? e.message : 'Jira connect failed.');
          } finally {
            setConnectBusy(false);
          }
        }}
      />
      <PromptModal
        open={connectOpen === 'confluence'}
        title='Connect Confluence'
        body='Paste your Confluence Cloud credentials.'
        fields={[
          {name: 'baseUrl', label: 'Base URL', placeholder: 'https://yourco.atlassian.net/wiki', required: true},
          {name: 'email', label: 'Email', placeholder: 'you@yourco.com', required: true},
          {name: 'apiToken', label: 'API token', placeholder: 'ATATT…', required: true},
        ]}
        busy={connectBusy}
        onClose={() => setConnectOpen(null)}
        onSubmit={async (v) => {
          setConnectBusy(true);
          try {
            await api.updateConfluenceSettings(v.baseUrl, v.email, v.apiToken);
            setConnectOpen(null);
            const next = await api.getIntegrationStatus();
            setStatus(next);
          } catch (e: unknown) {
            setErr(e instanceof Error ? e.message : 'Confluence connect failed.');
          } finally {
            setConnectBusy(false);
          }
        }}
      />
      <PromptModal
        open={connectOpen === 'bitbucket'}
        title='Connect Bitbucket'
        body='Provide a Bitbucket workspace + repo + app password (scopes: repository, pullrequest).'
        fields={[
          {name: 'workspace', label: 'Workspace', placeholder: 'your-workspace', required: true},
          {name: 'repoSlug', label: 'Default repo slug', placeholder: 'main-repo', required: true},
          {name: 'username', label: 'Username', placeholder: 'you', required: true},
          {name: 'appPassword', label: 'App password', placeholder: 'ATBB…', required: true},
        ]}
        busy={connectBusy}
        onClose={() => setConnectOpen(null)}
        onSubmit={async (v) => {
          setConnectBusy(true);
          try {
            await api.updateBitbucketSettings(v.username, v.appPassword, v.workspace, v.repoSlug);
            setConnectOpen(null);
            const next = await api.getIntegrationStatus();
            setStatus(next);
          } catch (e: unknown) {
            setErr(e instanceof Error ? e.message : 'Bitbucket connect failed.');
          } finally {
            setConnectBusy(false);
          }
        }}
      />

      <ConfirmModal
        open={disconnectOpen === 'slack'}
        title='Disconnect Slack?'
        body='Run-completion alerts will stop firing in your Slack workspace. You can reconnect at any time.'
        confirmLabel='Disconnect'
        danger
        busy={disconnectBusy}
        onClose={() => setDisconnectOpen(null)}
        onConfirm={performSlackDisconnect}
      />
      <ConfirmModal
        open={disconnectOpen === 'github'}
        title='Uninstall GitHub app?'
        body='The agent will lose access to your repos — runs that need GitHub for cloning, PRs, or webhooks will fail until you reinstall.'
        confirmLabel='Uninstall'
        danger
        busy={disconnectBusy}
        onClose={() => setDisconnectOpen(null)}
        onConfirm={performGithubUninstall}
      />
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
  const [inviteRole, setInviteRole] = useState<'DEVELOPER' | 'BILLING_ADMIN' | 'ORG_ADMIN'>('DEVELOPER');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [rowBusy, setRowBusy] = useState<string | null>(null);
  const [confirmRemove, setConfirmRemove] = useState<{userId: string; label: string} | null>(null);

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
              <div style={{display: 'flex', flexDirection: 'column', gap: 8, minWidth: 240}}>
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
                <select
                  value={inviteRole}
                  onChange={(e) => setInviteRole(e.target.value as typeof inviteRole)}
                  style={{
                    background: 'var(--signal-card-bg)',
                    border: '1px solid var(--signal-hairline)',
                    borderRadius: 8,
                    padding: '8px 10px',
                    fontSize: 13,
                    color: 'var(--signal-ink)',
                    outline: 'none',
                  }}
                >
                  <option value='DEVELOPER'>DEVELOPER</option>
                  <option value='BILLING_ADMIN'>BILLING_ADMIN</option>
                  <option value='ORG_ADMIN'>ORG_ADMIN</option>
                </select>
                <Button kind='primary' sm onClick={invite} disabled={busy || !inviteEmail.trim()}>
                  <Plus size={14} /> {busy ? 'Inviting…' : `Send ${inviteRole.toLowerCase()} invite`}
                </Button>
              </div>
            )}
          </div>
        </Card>

        <SectionHeader>Members · {members.length}</SectionHeader>
        {members.length === 0 ? (
          <LoadingState label="Loading members…" />
        ) : (
          members.map((m) => {
            const mAny = m as unknown as {id?: string; userId?: string; email?: string; firstName?: string; lastName?: string; orgRole?: string; lastSeen?: string};
            const isMe = mAny.userId === user?.id;
            const av = ((mAny.firstName?.[0] ?? '') + (mAny.lastName?.[0] ?? '') || (mAny.email?.slice(0, 2) ?? 'EX')).toUpperCase();
            const uid = mAny.userId || mAny.id || '';
            const roleBusy = rowBusy === `role-${uid}`;
            const removeBusy = rowBusy === `remove-${uid}`;
            const displayName = ((mAny.firstName || mAny.email?.split('@')[0]) ?? '—') + (mAny.lastName ? ` ${mAny.lastName}` : '');
            return (
              <div key={mAny.id || mAny.userId} className='signal-row' style={{gridTemplateColumns: 'auto 1fr auto'}}>
                <div className='signal-avatar'>{av}</div>
                <div style={{minWidth: 0}}>
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
                <div style={{display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0}}>
                  {canInvite && !isMe ? (
                    <>
                      <select
                        value={mAny.orgRole || 'DEVELOPER'}
                        disabled={roleBusy || removeBusy}
                        onChange={(e) => changeRole(uid, e.target.value)}
                        style={{
                          background: 'var(--signal-card-elev)',
                          border: '1px solid var(--signal-hairline)',
                          borderRadius: 8,
                          padding: '5px 8px',
                          fontSize: 12,
                          color: 'var(--signal-ink)',
                          outline: 'none',
                          cursor: roleBusy ? 'wait' : 'pointer',
                        }}
                      >
                        <option value='DEVELOPER'>DEVELOPER</option>
                        <option value='BILLING_ADMIN'>BILLING_ADMIN</option>
                        <option value='ORG_ADMIN'>ORG_ADMIN</option>
                      </select>
                      <Button
                        kind='secondary'
                        sm
                        disabled={removeBusy || roleBusy}
                        onClick={() => setConfirmRemove({userId: uid, label: displayName.trim() || mAny.email || 'this member'})}
                      >
                        {removeBusy ? '…' : 'Remove'}
                      </Button>
                    </>
                  ) : mAny.orgRole === 'ORG_ADMIN' ? (
                    <StatusPill kind='shipped'>{mAny.orgRole}</StatusPill>
                  ) : (
                    <Chip>{mAny.orgRole || 'DEVELOPER'}</Chip>
                  )}
                </div>
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

      <ConfirmModal
        open={!!confirmRemove}
        title='Remove member?'
        body={`${confirmRemove?.label || 'This member'} will lose access to all org projects, runs, and billing. This action is irreversible.`}
        confirmLabel='Remove'
        danger
        busy={!!confirmRemove && rowBusy === `remove-${confirmRemove.userId}`}
        onClose={() => setConfirmRemove(null)}
        onConfirm={() => { if (confirmRemove) void removeOne(confirmRemove.userId); }}
      />
    </>
  );
}

// Tight row variant for integrations — leads with the brand logo so the
// service is recognisable at a glance, drops the generic dot in favour of a
// real mark.
function IntegrationRow({logo, connected, title, desc, action}: {logo: React.ReactNode; connected: boolean; title: string; desc: string; action: React.ReactNode}) {
  return (
    <div className='signal-row' style={{gridTemplateColumns: 'auto 1fr auto'}}>
      <div style={{width: 32, height: 32, borderRadius: 8, background: 'var(--signal-card-elev)', display: 'grid', placeItems: 'center', flexShrink: 0, opacity: connected ? 1 : 0.65}}>
        {logo}
      </div>
      <div style={{minWidth: 0}}>
        <div className='row-title' style={{display: 'flex', alignItems: 'center', gap: 6}}>
          {title}
          {connected && (
            <span style={{display: 'inline-block', width: 6, height: 6, borderRadius: '50%', background: 'var(--signal-mint)', boxShadow: '0 0 6px rgba(124,227,180,0.6)'}} />
          )}
        </div>
        <div className='row-meta'><span>{desc}</span></div>
      </div>
      <div style={{display: 'flex', alignItems: 'center'}}>{action}</div>
    </div>
  );
}
