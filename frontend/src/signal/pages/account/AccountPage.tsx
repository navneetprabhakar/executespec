import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Row, Card, Chip, Button, KvList} from '@/signal/components/Primitives';
import {LoadingState} from '@/signal/components/Loaders';
import {PromptModal} from '@/signal/components/Modal';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import {useUiStore} from '@/stores/useUiStore';
import * as api from '@/lib/api';

export default function AccountPage() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const tier = useAuthStore(selectNormalizedTier);
  const permissions = useAuthStore((s) => s.permissions);
  const theme = useUiStore((s) => s.theme);
  const setTheme = useUiStore((s) => s.setTheme);
  const uiMode = useUiStore((s) => s.uiMode);
  const setUiMode = useUiStore((s) => s.setUiMode);
  const [busy, setBusy] = useState(false);
  const [notifPrefs, setNotifPrefs] = useState<api.NotificationPrefRow[] | null>(null);
  const [notifBusy, setNotifBusy] = useState<string | null>(null);
  // Privacy & data — DSAR JSON export + right-to-erasure. `dsarBusy` covers the
  // JSON download; the erasure flow is gated behind a typed-confirm PromptModal.
  const [dsarBusy, setDsarBusy] = useState(false);
  const [eraseOpen, setEraseOpen] = useState(false);
  const [eraseBusy, setEraseBusy] = useState(false);
  const [eraseDone, setEraseDone] = useState<string | null>(null);
  const [privacyErr, setPrivacyErr] = useState('');

  // Load notification preferences for the dedicated panel below.
  useEffect(() => {
    api
      .getNotificationPreferences()
      .then(setNotifPrefs)
      .catch(() => setNotifPrefs([]));
  }, []);

  async function togglePref(eventType: string, channel: string, enabled: boolean) {
    const key = `${eventType}:${channel}`;
    // Narrow channel to the typed literal union — the row is dynamic but the
    // API only accepts IN_APP | EMAIL.
    const typedChannel: 'IN_APP' | 'EMAIL' = channel === 'EMAIL' ? 'EMAIL' : 'IN_APP';
    setNotifBusy(key);
    try {
      await api.updateNotificationPreference(eventType, typedChannel, enabled);
      const next = await api.getNotificationPreferences();
      setNotifPrefs(next);
    } catch {
      /* swallow */
    } finally {
      setNotifBusy(null);
    }
  }

  // Ensure permissions are loaded.
  useEffect(() => {
    if (!permissions) {
      api
        .getMyPermissions()
        .then((p) => useAuthStore.setState({permissions: p}))
        .catch(() => {});
    }
  }, [permissions]);

  const fullName =
    [user?.firstName, user?.lastName].filter(Boolean).join(' ').trim() ||
    (user?.email?.split('@')[0] ?? '—');
  const email = user?.email || '—';
  const initials =
    (user?.firstName?.[0] ?? '') + (user?.lastName?.[0] ?? '') ||
    (user?.email?.slice(0, 2) ?? 'EX').toUpperCase();
  const usage = permissions
    ? `${permissions.currentRunCount} / ${permissions.monthlyRunLimit === -1 ? '∞' : permissions.monthlyRunLimit}`
    : '— / —';

  async function onSignOut() {
    setBusy(true);
    try {
      await logout();
    } finally {
      navigate('/login', {replace: true});
    }
  }

  async function onExport() {
    try {
      const resp = await fetch('/api/auth/me/data-export', {
        headers: {Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`},
      });
      if (!resp.ok) throw new Error(`Export failed (${resp.status}).`);
      const blob = await resp.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `executespec-export-${new Date().toISOString().slice(0, 10)}.zip`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e: unknown) {
      window.alert(e instanceof Error ? e.message : 'Export failed.');
    }
  }

  // DSAR self-service export — GET /api/dsar/me returns a JSON snapshot of every
  // record we hold for the caller (distinct from the GDPR zip above). No api.ts
  // helper exists for it, so fetch with the bearer + trigger a JSON blob download.
  async function onDsarExport() {
    setDsarBusy(true);
    setPrivacyErr('');
    try {
      const token = localStorage.getItem('access_token') ?? '';
      const resp = await fetch('/api/dsar/me', {
        headers: token ? {Authorization: `Bearer ${token}`} : {},
      });
      if (!resp.ok) throw new Error(`Data request failed (${resp.status}).`);
      const blob = await resp.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `executespec-dsar-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e: unknown) {
      setPrivacyErr(e instanceof Error ? e.message : 'Data request failed.');
    } finally {
      setDsarBusy(false);
    }
  }

  // Right-to-erasure — DELETE /api/auth/me/data soft-deletes the caller's
  // completed/failed runs and schedules workspace teardown. Gated behind a typed
  // "DELETE" confirm; no api.ts helper, so direct fetch with the bearer.
  async function performErasure(values: Record<string, string>) {
    if ((values.confirm || '').trim().toUpperCase() !== 'DELETE') {
      setPrivacyErr('Type DELETE to confirm erasure.');
      return;
    }
    setEraseBusy(true);
    setPrivacyErr('');
    try {
      const token = localStorage.getItem('access_token') ?? '';
      const resp = await fetch('/api/auth/me/data', {
        method: 'DELETE',
        headers: token ? {Authorization: `Bearer ${token}`} : {},
      });
      if (!resp.ok) throw new Error(`Erasure failed (${resp.status}).`);
      const body = (await resp.json().catch(() => ({}))) as {runsScheduledForDeletion?: number};
      const n = body.runsScheduledForDeletion ?? 0;
      setEraseDone(
        `Erasure requested — ${n} run${n === 1 ? '' : 's'} scheduled for deletion. Active runs must be cancelled first.`,
      );
      setEraseOpen(false);
    } catch (e: unknown) {
      setPrivacyErr(e instanceof Error ? e.message : 'Erasure failed.');
    } finally {
      setEraseBusy(false);
    }
  }

  return (
    <>
      <TopBar title='Account' sub={email} back backTo='/home' />
      <Feed>
        <Card style={{display: 'flex', gap: 16, alignItems: 'center', padding: 18}}>
          <div className='signal-avatar' style={{width: 56, height: 56, fontSize: 20}}>
            {initials.toUpperCase()}
          </div>
          <div style={{flex: 1, minWidth: 0}}>
            <div style={{fontSize: 17, fontWeight: 600}}>{fullName}</div>
            <div style={{fontSize: 13, color: 'var(--signal-text-dim)'}}>{email}</div>
            <div style={{marginTop: 6, display: 'flex', gap: 4, flexWrap: 'wrap'}}>
              <Chip kind='signal'>{tier} tier</Chip>
              <Chip>{usage}</Chip>
              {user?.globalRole === 'SUPER_ADMIN' && <Chip kind='coral'>SUPER_ADMIN</Chip>}
              {user?.orgRole === 'ORG_ADMIN' && <Chip kind='active'>ORG_ADMIN</Chip>}
            </div>
          </div>
        </Card>

        <SectionHeader>Quick links</SectionHeader>
        <Row
          to='/billing'
          dot='mint'
          title='Billing & invoices'
          meta={<span>{tier} tier · manage subscription</span>}
          showChev
        />
        <Row
          to='/topup'
          dot='signal'
          title='Top up credits'
          meta={<span>{usage} this month</span>}
          showChev
        />
        <Row
          to='/plans'
          dot='lavender'
          title='Compare plans'
          meta={<span>{tier === 'ENTERPRISE' ? 'Custom pricing' : 'Upgrade for more runs'}</span>}
          showChev
        />
        <Row
          to='/api-keys'
          dot='ocean'
          title='API keys (BYOK)'
          meta={<span>Bypass cost cap with your own keys</span>}
          showChev
        />
        <Row
          to='/integrations'
          dot='lavender'
          title='Integrations'
          meta={<span>GitHub, Slack, Razorpay, Stripe</span>}
          showChev
        />
        {(user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN') && (
          <>
            <Row
              to='/org-members'
              title='Org members'
              meta={<span>Invite teammates · manage roles</span>}
              showChev
            />
            <Row
              to='/org/settings'
              title='Org settings'
              meta={<span>SSO · domains · audit · DPA</span>}
              showChev
            />
          </>
        )}

        <SectionHeader>Preferences</SectionHeader>
        <Card style={{padding: 0}}>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              padding: '14px 16px',
              borderBottom: '1px solid var(--signal-hairline)',
            }}
          >
            <div>
              <div style={{fontSize: 14.5, fontWeight: 500}}>Theme</div>
              <div style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>Currently: {theme}</div>
            </div>
            <div style={{display: 'flex', gap: 4}}>
              <Chip
                kind={theme === 'light' ? 'active' : 'default'}
                onClick={() => setTheme('light')}
              >
                Light
              </Chip>
              <Chip kind={theme === 'dark' ? 'active' : 'default'} onClick={() => setTheme('dark')}>
                Dark
              </Chip>
            </div>
          </div>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              padding: '14px 16px',
              borderBottom: '1px solid var(--signal-hairline)',
            }}
          >
            <div>
              <div style={{fontSize: 14.5, fontWeight: 500}}>
                Interface{' '}
                <span
                  style={{
                    fontSize: 10,
                    fontWeight: 700,
                    color: 'var(--signal-signal-text, #5B8DEF)',
                    border: '1px solid currentColor',
                    borderRadius: 4,
                    padding: '0 4px',
                    marginLeft: 4,
                    verticalAlign: 'middle',
                  }}
                >
                  BETA
                </span>
              </div>
              <div style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>
                {uiMode === 'ide' ? 'VS Code-style IDE' : 'Signal (classic)'}
              </div>
            </div>
            <div style={{display: 'flex', gap: 4}}>
              <Chip
                kind={uiMode === 'signal' ? 'active' : 'default'}
                onClick={() => setUiMode('signal')}
              >
                Signal
              </Chip>
              <Chip kind={uiMode === 'ide' ? 'active' : 'default'} onClick={() => setUiMode('ide')}>
                IDE
              </Chip>
            </div>
          </div>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              padding: '14px 16px',
            }}
          >
            <div>
              <div style={{fontSize: 14.5, fontWeight: 500}}>View mode</div>
              <div style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>
                {user?.viewMode === 'project' ? 'Project-centric' : 'Pro (run-centric)'}
              </div>
            </div>
          </div>
        </Card>

        <SectionHeader>Plan limits</SectionHeader>
        <Card>
          <KvList
            pairs={[
              {
                k: 'Monthly run cap',
                v: permissions
                  ? permissions.monthlyRunLimit === -1
                    ? 'Unlimited'
                    : String(permissions.monthlyRunLimit)
                  : '—',
              },
              {k: 'Runs used', v: permissions ? String(permissions.currentRunCount) : '—'},
              {
                k: 'Max org seats',
                v: permissions
                  ? permissions.maxOrgMembers === -1
                    ? 'Unlimited'
                    : String(permissions.maxOrgMembers)
                  : '—',
              },
              {k: 'Live log access', v: permissions?.canViewLiveLog ? 'Enabled' : 'PRO+'},
              {k: 'BYOK keys', v: permissions?.canManageAiKeys ? 'Enabled' : 'PRO+'},
              {k: 'Audit log', v: permissions?.canViewAudit ? 'Enabled' : 'TEAM+'},
              {
                k: 'Deploy engine',
                v: permissions?.canDeploy
                  ? `Up to ${permissions.maxDeployments === -1 ? '∞' : permissions.maxDeployments}`
                  : 'PRO+',
              },
            ]}
          />
        </Card>

        <SectionHeader>Notifications</SectionHeader>
        {notifPrefs == null ? (
          <LoadingState label='Loading notification preferences…' />
        ) : notifPrefs.length === 0 ? (
          <Card>
            <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
              No notification events to configure yet.
            </p>
          </Card>
        ) : (
          <Card style={{padding: 0}}>
            {notifPrefs.map((row, i) => {
              const rAny = row as unknown as {
                eventType?: string;
                channel?: string;
                enabled?: boolean;
                description?: string;
              };
              const eventType = rAny.eventType || `event-${i}`;
              const channel = rAny.channel || 'EMAIL';
              const key = `${eventType}:${channel}`;
              const enabled = rAny.enabled !== false;
              return (
                <div
                  key={key}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    padding: '12px 16px',
                    borderBottom:
                      i < notifPrefs.length - 1 ? '1px solid var(--signal-hairline)' : 0,
                  }}
                >
                  <div style={{minWidth: 0, flex: 1}}>
                    <div style={{fontSize: 13.5, fontWeight: 500, color: 'var(--signal-ink)'}}>
                      {eventType.replace(/_/g, ' ').toLowerCase()}
                    </div>
                    <div style={{fontSize: 11.5, color: 'var(--signal-text-dim)'}}>
                      {rAny.description || `${channel.toLowerCase()} channel`}
                    </div>
                  </div>
                  <button
                    type='button'
                    aria-pressed={enabled}
                    onClick={() => togglePref(eventType, channel, !enabled)}
                    disabled={notifBusy === key}
                    style={{
                      position: 'relative',
                      width: 40,
                      height: 22,
                      background: enabled ? 'var(--signal-mint)' : 'var(--signal-card-elev)',
                      border: '1px solid var(--signal-hairline)',
                      borderRadius: 999,
                      cursor: 'pointer',
                      transition: 'background 0.2s',
                      flexShrink: 0,
                    }}
                  >
                    <span
                      style={{
                        position: 'absolute',
                        top: 1,
                        left: enabled ? 18 : 1,
                        width: 18,
                        height: 18,
                        background: 'white',
                        borderRadius: '50%',
                        transition: 'left 0.2s',
                      }}
                    />
                  </button>
                </div>
              );
            })}
          </Card>
        )}

        <SectionHeader>Security &amp; data</SectionHeader>
        <Row
          to='/reset-password'
          title='Change password'
          meta={<span>Reset link sent to your email</span>}
          showChev
        />
        <Row title='Active sessions' meta={<span>Manage signed-in devices</span>} showChev />
        <Row
          title='Export my data (GDPR)'
          meta={<span>ZIP · runs, projects, billing</span>}
          trailing={
            <Button kind='secondary' sm onClick={onExport}>
              Export
            </Button>
          }
        />

        <SectionHeader>Privacy &amp; data</SectionHeader>
        {privacyErr && (
          <Card
            style={{
              background: 'var(--signal-coral-soft)',
              borderColor: 'var(--signal-coral)',
              marginBottom: 8,
            }}
          >
            <span style={{fontSize: 13, color: 'var(--signal-coral-text)'}}>{privacyErr}</span>
          </Card>
        )}
        {eraseDone && (
          <Card
            style={{
              background: 'var(--signal-mint-soft)',
              borderColor: 'var(--signal-mint)',
              marginBottom: 8,
            }}
          >
            <span style={{fontSize: 13, color: 'var(--signal-mint-text-on-light)'}}>
              {eraseDone}
            </span>
          </Card>
        )}
        <Row
          title='Request my data (DSAR)'
          meta={<span>JSON · everything we hold about you, GDPR Art. 15</span>}
          trailing={
            <Button kind='secondary' sm disabled={dsarBusy} onClick={onDsarExport}>
              {dsarBusy ? 'Preparing…' : 'Download JSON'}
            </Button>
          }
        />
        <Row
          title='Delete my data (right to erasure)'
          meta={<span>Schedules your runs for permanent deletion · GDPR Art. 17</span>}
          trailing={
            <Button
              kind='secondary'
              sm
              style={{color: 'var(--signal-coral-text)'}}
              onClick={() => {
                setPrivacyErr('');
                setEraseDone(null);
                setEraseOpen(true);
              }}
            >
              Delete
            </Button>
          }
        />

        <Button
          kind='secondary'
          block
          style={{marginTop: 16, color: 'var(--signal-coral-text)'}}
          disabled={busy}
          onClick={onSignOut}
        >
          {busy ? 'Signing out…' : 'Sign out'}
        </Button>
      </Feed>

      <PromptModal
        open={eraseOpen}
        title='Delete my data?'
        body={
          <>
            This soft-deletes every completed and failed run on your account and schedules the
            workspaces for permanent teardown. Active runs must be cancelled first. This cannot be
            undone. Type <strong>DELETE</strong> to confirm.
          </>
        }
        fields={[
          {name: 'confirm', label: 'Type DELETE to confirm', placeholder: 'DELETE', required: true},
        ]}
        submitLabel='Erase my data'
        busy={eraseBusy}
        onSubmit={performErasure}
        onClose={() => setEraseOpen(false)}
      />
    </>
  );
}
