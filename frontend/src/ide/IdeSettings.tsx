import {useEffect, useState} from 'react';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';

// ───────────────────────── Integrations ─────────────────────────
export function IdeIntegrations() {
  const [sources, setSources] = useState<string[] | null>(null);
  const [slack, setSlack] = useState<api.SlackStatus | null>(null);
  const [gh, setGh] = useState<api.GitHubAppStatus | null>(null);

  useEffect(() => {
    api
      .getIntegrationStatus()
      .then((s) => setSources(s.enabledSources ?? []))
      .catch(() => setSources([]));
    api
      .getSlackStatus()
      .then(setSlack)
      .catch(() => {});
    api
      .getGitHubAppStatus()
      .then(setGh)
      .catch(() => {});
  }, []);

  function open(getUrl: () => Promise<{installUrl?: string}>) {
    getUrl()
      .then((r) => r.installUrl && window.open(r.installUrl, '_blank'))
      .catch(() => {});
  }

  const has = (k: string) => (sources ?? []).map((s) => s.toLowerCase()).includes(k);
  const rows = [
    {
      name: 'GitHub',
      connected: !!gh?.installed,
      action: () => open(api.getGitHubAppInstallUrl as () => Promise<{installUrl?: string}>),
      label: 'Install app',
    },
    {
      name: 'Slack',
      connected: !!slack?.connected,
      sub: slack?.connected ? slack.teamName || '' : '',
      action: () => open(api.getSlackInstallUrl),
      label: 'Connect',
    },
    {name: 'Jira', connected: has('jira')},
    {name: 'Confluence', connected: has('confluence')},
    {name: 'Bitbucket', connected: has('bitbucket')},
  ];

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <div className='ide-h1'>Integrations</div>
      <div className='ide-sub'>Connect GitHub, Slack, and your issue trackers.</div>
      {sources === null ? (
        <IdeLoader label='Loading integrations…' />
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {rows.map((r, i) => (
            <div
              key={r.name}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '13px 16px',
                borderBottom: i < rows.length - 1 ? '1px solid var(--ide-border)' : 'none',
              }}
            >
              <span style={{flex: 1, fontWeight: 500}}>
                {r.name}
                {r.sub && (
                  <span style={{color: 'var(--ide-fg-muted)', fontWeight: 400}}> · {r.sub}</span>
                )}
              </span>
              <IdeBadge kind={r.connected ? 'ok' : 'warn'}>
                {r.connected ? 'Connected' : 'Not connected'}
              </IdeBadge>
              {r.action && !r.connected && (
                <button className='ide-btn ghost' style={{padding: '4px 10px'}} onClick={r.action}>
                  {r.label}
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ───────────────────────── Admin ─────────────────────────
// Canonical activation funnel — label + the stage key returned by
// /admin/activation-funnel (mirrors analytics.ts's 6-event funnel).
const FUNNEL_STAGES: Array<[string, string]> = [
  ['Signups', 'SIGNUP'],
  ['Projects', 'PROJECT_CREATED'],
  ['Specs', 'SPEC_SUBMITTED'],
  ['Plans approved', 'PLAN_APPROVED'],
  ['Runs complete', 'RUN_COMPLETE'],
  ['PRs merged', 'PR_MERGED'],
];

export function IdeAdmin() {
  const [funnel, setFunnel] = useState<api.ActivationFunnelReport | null>(null);
  const [flags, setFlags] = useState<api.FeatureFlagDto[]>([]);
  const [tiers, setTiers] = useState<api.TierLifecycleDto[]>([]);

  useEffect(() => {
    api
      .getActivationFunnel(30)
      .then(setFunnel)
      // keep the grid visible on error rather than spinning forever
      .catch(() => setFunnel({windowDays: 30, windowStart: '', stages: {}}));
    api
      .adminListFeatureFlags()
      .then(setFlags)
      .catch(() => {});
    api
      .fetchTiersLifecycle()
      .then(setTiers)
      .catch(() => {});
  }, []);

  const stage = (k: string) => {
    const s = funnel?.stages?.[k];
    return s ? String(s.windowed) : '—';
  };

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Admin</div>
      {!funnel ? (
        <IdeLoader label='Loading admin…' />
      ) : (
        <>
          <div className='ide-h2'>Platform · last {funnel.windowDays}d</div>
          <div style={{display: 'flex', gap: 10, flexWrap: 'wrap'}}>
            {FUNNEL_STAGES.map(([label, key]) => (
              <div key={key} className='ide-card' style={{flex: 1, minWidth: 120}}>
                <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>{label}</div>
                <div
                  style={{
                    fontSize: 22,
                    fontWeight: 700,
                    color: 'var(--ide-fg-bright)',
                    marginTop: 4,
                  }}
                >
                  {stage(key)}
                </div>
              </div>
            ))}
          </div>

          <div className='ide-h2'>Tiers</div>
          <div className='ide-card' style={{padding: 0}}>
            {tiers.map((t, i) => (
              <div
                key={t.tier}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  padding: '10px 16px',
                  borderBottom: i < tiers.length - 1 ? '1px solid var(--ide-border)' : 'none',
                }}
              >
                <span style={{flex: 1, fontWeight: 500}}>{t.displayName || t.tier}</span>
                <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
                  {t.activeSubscribers} subs
                </span>
                <IdeBadge
                  kind={t.state === 'LISTED' ? 'ok' : t.state === 'HIDDEN' ? 'warn' : 'bad'}
                >
                  {t.state}
                </IdeBadge>
              </div>
            ))}
          </div>

          <div className='ide-h2'>Feature flags · {flags.length}</div>
          <div className='ide-card' style={{padding: 0}}>
            {flags.map((f, i) => (
              <div
                key={f.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  padding: '8px 16px',
                  borderBottom: i < flags.length - 1 ? '1px solid var(--ide-border)' : 'none',
                  fontSize: 12.5,
                }}
              >
                <span className='ide-mono' style={{flex: 1}}>
                  {f.flagKey}
                </span>
                <span style={{color: 'var(--ide-fg-muted)'}}>{f.scope}</span>
                <IdeBadge kind={f.flagValue === 'true' ? 'ok' : 'warn'}>{f.flagValue}</IdeBadge>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
