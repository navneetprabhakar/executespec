import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Row, Card, Chip, Button, KvList} from '@/signal/components/Primitives';
import {LoadingState} from '@/signal/components/Loaders';
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
  const [busy, setBusy] = useState(false);
  const [notifPrefs, setNotifPrefs] = useState<api.NotificationPrefRow[] | null>(null);
  const [notifBusy, setNotifBusy] = useState<string | null>(null);

  // Load notification preferences for the dedicated panel below.
  useEffect(() => {
    api.getNotificationPreferences().then(setNotifPrefs).catch(() => setNotifPrefs([]));
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
      api.getMyPermissions().then((p) => useAuthStore.setState({permissions: p})).catch(() => {});
    }
  }, [permissions]);

  const fullName = [user?.firstName, user?.lastName].filter(Boolean).join(' ').trim() || (user?.email?.split('@')[0] ?? '—');
  const email = user?.email || '—';
  const initials = (user?.firstName?.[0] ?? '') + (user?.lastName?.[0] ?? '') || (user?.email?.slice(0, 2) ?? 'EX').toUpperCase();
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

  return (
    <>
      <TopBar title='Account' sub={email} back backTo='/home' />
      <Feed>
        <Card style={{display: 'flex', gap: 16, alignItems: 'center', padding: 18}}>
          <div className='signal-avatar' style={{width: 56, height: 56, fontSize: 20}}>{initials.toUpperCase()}</div>
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
        <Row to='/billing' dot='mint' title='Billing & invoices' meta={<span>{tier} tier · manage subscription</span>} showChev />
        <Row to='/topup' dot='signal' title='Top up credits' meta={<span>{usage} this month</span>} showChev />
        <Row to='/plans' dot='lavender' title='Compare plans' meta={<span>{tier === 'ENTERPRISE' ? 'Custom pricing' : 'Upgrade for more runs'}</span>} showChev />
        <Row to='/api-keys' dot='ocean' title='API keys (BYOK)' meta={<span>Bypass cost cap with your own keys</span>} showChev />
        <Row to='/integrations' dot='lavender' title='Integrations' meta={<span>GitHub, Slack, Razorpay, Stripe</span>} showChev />
        {(user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN') && (
          <>
            <Row to='/org-members' title='Org members' meta={<span>Invite teammates · manage roles</span>} showChev />
            <Row to='/org/settings' title='Org settings' meta={<span>SSO · domains · audit · DPA</span>} showChev />
          </>
        )}

        <SectionHeader>Preferences</SectionHeader>
        <Card style={{padding: 0}}>
          <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '14px 16px', borderBottom: '1px solid var(--signal-hairline)'}}>
            <div>
              <div style={{fontSize: 14.5, fontWeight: 500}}>Theme</div>
              <div style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>Currently: {theme}</div>
            </div>
            <div style={{display: 'flex', gap: 4}}>
              <Chip kind={theme === 'light' ? 'active' : 'default'} onClick={() => setTheme('light')}>Light</Chip>
              <Chip kind={theme === 'dark' ? 'active' : 'default'} onClick={() => setTheme('dark')}>Dark</Chip>
            </div>
          </div>
          <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '14px 16px'}}>
            <div>
              <div style={{fontSize: 14.5, fontWeight: 500}}>View mode</div>
              <div style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>{user?.viewMode === 'project' ? 'Project-centric' : 'Pro (run-centric)'}</div>
            </div>
          </div>
        </Card>

        <SectionHeader>Plan limits</SectionHeader>
        <Card>
          <KvList
            pairs={[
              {k: 'Monthly run cap', v: permissions ? (permissions.monthlyRunLimit === -1 ? 'Unlimited' : String(permissions.monthlyRunLimit)) : '—'},
              {k: 'Runs used', v: permissions ? String(permissions.currentRunCount) : '—'},
              {k: 'Max org seats', v: permissions ? (permissions.maxOrgMembers === -1 ? 'Unlimited' : String(permissions.maxOrgMembers)) : '—'},
              {k: 'Live log access', v: permissions?.canViewLiveLog ? 'Enabled' : 'PRO+'},
              {k: 'BYOK keys', v: permissions?.canManageAiKeys ? 'Enabled' : 'PRO+'},
              {k: 'Audit log', v: permissions?.canViewAudit ? 'Enabled' : 'TEAM+'},
              {k: 'Deploy engine', v: permissions?.canDeploy ? `Up to ${permissions.maxDeployments === -1 ? '∞' : permissions.maxDeployments}` : 'PRO+'},
            ]}
          />
        </Card>

        <SectionHeader>Notifications</SectionHeader>
        {notifPrefs == null ? (
          <LoadingState label='Loading notification preferences…' />
        ) : notifPrefs.length === 0 ? (
          <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>No notification events to configure yet.</p></Card>
        ) : (
          <Card style={{padding: 0}}>
            {notifPrefs.map((row, i) => {
              const rAny = row as unknown as {eventType?: string; channel?: string; enabled?: boolean; description?: string};
              const eventType = rAny.eventType || `event-${i}`;
              const channel = rAny.channel || 'EMAIL';
              const key = `${eventType}:${channel}`;
              const enabled = rAny.enabled !== false;
              return (
                <div
                  key={key}
                  style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 16px', borderBottom: i < notifPrefs.length - 1 ? '1px solid var(--signal-hairline)' : 0}}
                >
                  <div style={{minWidth: 0, flex: 1}}>
                    <div style={{fontSize: 13.5, fontWeight: 500, color: 'var(--signal-ink)'}}>{eventType.replace(/_/g, ' ').toLowerCase()}</div>
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
        <Row to='/reset-password' title='Change password' meta={<span>Reset link sent to your email</span>} showChev />
        <Row title='Active sessions' meta={<span>Manage signed-in devices</span>} showChev />
        <Row title='Export my data (GDPR)' meta={<span>ZIP · runs, projects, billing</span>} trailing={<Button kind='secondary' sm onClick={onExport}>Export</Button>} />

        <Button kind='secondary' block style={{marginTop: 16, color: 'var(--signal-coral-text)'}} disabled={busy} onClick={onSignOut}>
          {busy ? 'Signing out…' : 'Sign out'}
        </Button>
      </Feed>
    </>
  );
}
