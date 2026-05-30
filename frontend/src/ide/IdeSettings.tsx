import {useEffect, useState} from 'react';
import * as api from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import {ConfirmModal, PromptModal} from '@/signal/components/Modal';

// Per-user credential providers that store a token via /auth/settings/{provider}.
// (GitHub also has the org-level App-install flow, surfaced as a second action.)
type ConnectKind = 'github' | 'jira' | 'confluence' | 'bitbucket';
const INT_BTN: React.CSSProperties = {padding: '4px 10px', flexShrink: 0};

export function IdeIntegrations() {
  const user = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);
  const [sources, setSources] = useState<string[] | null>(null);
  const [slack, setSlack] = useState<api.SlackStatus | null>(null);
  const [gh, setGh] = useState<api.GitHubAppStatus | null>(null);
  const [connect, setConnect] = useState<ConnectKind | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');

  const loadSources = () =>
    api
      .getIntegrationStatus()
      .then((s) => setSources(s.enabledSources ?? []))
      .catch(() => setSources([]));

  useEffect(() => {
    loadSources();
    api
      .getSlackStatus()
      .then(setSlack)
      .catch(() => {});
    api
      .getGitHubAppStatus()
      .then(setGh)
      .catch(() => {});
  }, []);

  function openInstall(getUrl: () => Promise<{installUrl?: string}>) {
    getUrl()
      .then((r) => r.installUrl && window.open(r.installUrl, '_blank'))
      .catch(() => {});
  }

  // Connected = the source is enabled server-side OR the per-user credential
  // flag is set (the flag flips immediately after a save, before the sources
  // list re-reads). `has()` is the canonical signal the retired Signal page used.
  const has = (k: string) => (sources ?? []).map((s) => s.toLowerCase()).includes(k);
  const ghConnected = !!gh?.installed || !!user?.hasGithubPat;
  const jiraConnected = has('jira') || !!user?.hasJiraToken;
  const confluenceConnected = has('confluence') || !!user?.hasConfluenceToken;
  const bitbucketConnected = has('bitbucket') || !!user?.hasBitbucketAppPassword;

  // PromptModal spec per provider — mirrors the connect modals from the retired
  // Signal IntegrationsPage. `run` posts to the typed /auth/settings/{provider}
  // helper and returns the refreshed AuthUser.
  function modalSpec(p: ConnectKind): {
    title: string;
    body: string;
    fields: Array<{
      name: string;
      label: string;
      placeholder?: string;
      defaultValue?: string;
      required?: boolean;
    }>;
    run: (v: Record<string, string>) => Promise<api.AuthUser>;
  } {
    switch (p) {
      case 'github':
        return {
          title: 'Connect GitHub (token)',
          body: 'Paste a GitHub personal access token (classic or fine-grained, scopes: repo + workflow). Used to clone and push your repositories. Stored encrypted at rest.',
          fields: [
            {
              name: 'githubPat',
              label: 'Personal access token',
              placeholder: 'ghp_… or github_pat_…',
              required: true,
            },
            {
              name: 'githubOwner',
              label: 'Owner (username or org)',
              placeholder: 'your-username',
              defaultValue: user?.githubOwner ?? '',
              required: true,
            },
          ],
          run: (v) => api.updateGithubSettings(v.githubPat, v.githubOwner),
        };
      case 'jira':
        return {
          title: 'Connect Jira',
          body: 'Paste your Jira Cloud credentials. The API token is stored encrypted at rest.',
          fields: [
            {
              name: 'baseUrl',
              label: 'Base URL',
              placeholder: 'https://yourco.atlassian.net',
              defaultValue: user?.jiraBaseUrl ?? '',
              required: true,
            },
            {
              name: 'email',
              label: 'Email',
              placeholder: 'you@yourco.com',
              defaultValue: user?.jiraEmail ?? '',
              required: true,
            },
            {name: 'apiToken', label: 'API token', placeholder: 'ATATT…', required: true},
          ],
          run: (v) => api.updateJiraSettings(v.baseUrl, v.email, v.apiToken),
        };
      case 'confluence':
        return {
          title: 'Connect Confluence',
          body: 'Paste your Confluence Cloud credentials. The API token is stored encrypted at rest.',
          fields: [
            {
              name: 'baseUrl',
              label: 'Base URL',
              placeholder: 'https://yourco.atlassian.net/wiki',
              defaultValue: user?.confluenceBaseUrl ?? '',
              required: true,
            },
            {
              name: 'email',
              label: 'Email',
              placeholder: 'you@yourco.com',
              defaultValue: user?.confluenceEmail ?? '',
              required: true,
            },
            {name: 'apiToken', label: 'API token', placeholder: 'ATATT…', required: true},
          ],
          run: (v) => api.updateConfluenceSettings(v.baseUrl, v.email, v.apiToken),
        };
      case 'bitbucket':
        return {
          title: 'Connect Bitbucket',
          body: 'Provide a Bitbucket workspace + repo + app password (scopes: repository, pullrequest).',
          fields: [
            {
              name: 'workspace',
              label: 'Workspace',
              placeholder: 'your-workspace',
              defaultValue: user?.bitbucketWorkspace ?? '',
              required: true,
            },
            {
              name: 'repoSlug',
              label: 'Default repo slug',
              placeholder: 'main-repo',
              defaultValue: user?.bitbucketRepoSlug ?? '',
              required: true,
            },
            {
              name: 'username',
              label: 'Username',
              placeholder: 'you',
              defaultValue: user?.bitbucketUsername ?? '',
              required: true,
            },
            {name: 'appPassword', label: 'App password', placeholder: 'ATBB…', required: true},
          ],
          run: (v) =>
            api.updateBitbucketSettings(v.username, v.appPassword, v.workspace, v.repoSlug),
        };
      default:
        throw new Error(`Unknown integration provider: ${String(p)}`);
    }
  }

  const spec = connect ? modalSpec(connect) : null;

  async function submitConnect(values: Record<string, string>) {
    if (!connect || !spec) return;
    setBusy(true);
    setErr('');
    setMsg('');
    try {
      const updated = await spec.run(values);
      setUser(api.normalizeAuthUser(updated));
      await loadSources();
      setMsg(`${spec.title.replace('Connect ', '').replace(' (token)', '')} connected.`);
      setConnect(null);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Connect failed.');
      setConnect(null); // close so the banner is visible; re-open to retry
    } finally {
      setBusy(false);
    }
  }

  const rows: Array<{
    name: string;
    desc: string;
    connected: boolean;
    sub?: string;
    actions?: React.ReactNode;
  }> = [
    {
      name: 'GitHub',
      desc: 'Clone repos + open PRs · GitHub App or a personal access token',
      connected: ghConnected,
      sub: gh?.installed ? 'app installed' : user?.hasGithubPat ? 'token set' : undefined,
      actions: (
        // RL-2026-05-044 — removed the dead "Install app" button (no system GitHub App is
        // configured; every GitHub op uses the caller's own PAT). Token entry is the only path.
        <button className='ide-btn ghost' style={INT_BTN} onClick={() => setConnect('github')}>
          {user?.hasGithubPat ? 'Update token' : 'Set token'}
        </button>
      ),
    },
    {
      name: 'Slack',
      desc: 'Run-completion alerts in your channel',
      connected: !!slack?.connected,
      sub: slack?.connected ? slack.teamName || undefined : undefined,
      actions: !slack?.connected ? (
        <button
          className='ide-btn ghost'
          style={INT_BTN}
          onClick={() => openInstall(api.getSlackInstallUrl)}
        >
          Connect
        </button>
      ) : undefined,
    },
    {
      name: 'Jira',
      desc: 'Trigger runs from issues',
      connected: jiraConnected,
      sub: user?.jiraBaseUrl || undefined,
      actions: (
        <button className='ide-btn ghost' style={INT_BTN} onClick={() => setConnect('jira')}>
          {jiraConnected ? 'Manage' : 'Connect'}
        </button>
      ),
    },
    {
      name: 'Confluence',
      desc: 'Trigger runs from pages',
      connected: confluenceConnected,
      sub: user?.confluenceBaseUrl || undefined,
      actions: (
        <button className='ide-btn ghost' style={INT_BTN} onClick={() => setConnect('confluence')}>
          {confluenceConnected ? 'Manage' : 'Connect'}
        </button>
      ),
    },
    {
      name: 'Bitbucket',
      desc: 'Clone repos + open PRs',
      connected: bitbucketConnected,
      sub: user?.bitbucketWorkspace || undefined,
      actions: (
        <button className='ide-btn ghost' style={INT_BTN} onClick={() => setConnect('bitbucket')}>
          {bitbucketConnected ? 'Manage' : 'Connect'}
        </button>
      ),
    },
  ];

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <div className='ide-h1'>Integrations</div>
      <div className='ide-sub'>Connect GitHub, Slack, and your issue trackers.</div>

      {(err || msg) && (
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
      )}

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
              <div style={{flex: 1, minWidth: 0}}>
                <div style={{fontWeight: 500}}>
                  {r.name}
                  {r.sub && (
                    <span style={{color: 'var(--ide-fg-muted)', fontWeight: 400}}> · {r.sub}</span>
                  )}
                </div>
                <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)', marginTop: 2}}>
                  {r.desc}
                </div>
              </div>
              <IdeBadge kind={r.connected ? 'ok' : 'warn'}>
                {r.connected ? 'Connected' : 'Not connected'}
              </IdeBadge>
              {r.actions}
            </div>
          ))}
        </div>
      )}

      {spec && (
        <PromptModal
          key={connect ?? 'closed'}
          open={!!connect}
          title={spec.title}
          body={spec.body}
          fields={spec.fields}
          submitLabel='Save'
          busy={busy}
          onClose={() => setConnect(null)}
          // eslint-disable-next-line no-restricted-syntax -- explicit pending state via `busy` (passed as the modal's `busy` prop) wraps this submit.
          onSubmit={submitConnect}
        />
      )}
    </div>
  );
}

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

// Shared row container — one bordered list inside an ide-card.
function AdminList({children}: {children: React.ReactNode}) {
  return (
    <div className='ide-card' style={{padding: 0}}>
      {children}
    </div>
  );
}

const ROW_BASE: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '10px 16px',
};
function rowStyle(last: boolean): React.CSSProperties {
  return {...ROW_BASE, borderBottom: last ? 'none' : '1px solid var(--ide-border)'};
}

// Small inline-edit <select> styled as a chip-sized control.
function MiniSelect({
  value,
  options,
  onChange,
  disabled,
}: {
  value: string;
  options: string[];
  onChange: (v: string) => void;
  disabled?: boolean;
}) {
  return (
    <select
      className='ide-select'
      value={value}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
      style={{width: 'auto', padding: '3px 8px', fontSize: 12}}
    >
      {options.map((o) => (
        <option key={o} value={o}>
          {o}
        </option>
      ))}
    </select>
  );
}

const USER_TIERS = ['FREE', 'PRO', 'TEAM', 'ENTERPRISE'];
const USER_ROLES = ['USER', 'FINANCE', 'SUPER_ADMIN'];
const USER_STATUSES = ['ACTIVE', 'SUSPENDED'];
const ORG_TIERS = ['FREE', 'PRO', 'TEAM', 'ENTERPRISE'];
const ORG_STATUSES = ['ACTIVE', 'SUSPENDED'];

type AdminSection = 'overview' | 'users' | 'orgs' | 'analytics' | 'ops' | 'topups';

export function IdeAdmin() {
  const user = useAuthStore((s) => s.user);
  const [section, setSection] = useState<AdminSection>('overview');
  const [toast, setToast] = useState<{kind: 'ok' | 'bad'; text: string} | null>(null);

  // shared notice helpers passed down to sections
  const ok = (text: string) => setToast({kind: 'ok', text});
  const bad = (e: unknown) =>
    setToast({kind: 'bad', text: e instanceof Error ? e.message : 'Action failed.'});

  const TABS: Array<[AdminSection, string]> = [
    ['overview', 'Overview'],
    ['users', 'Users'],
    ['orgs', 'Orgs'],
    ['analytics', 'Analytics'],
    ['ops', 'Ops'],
    ['topups', 'Top-ups'],
  ];

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Admin</div>
      <div className='ide-sub'>SUPER_ADMIN · every action here is logged.</div>

      <div className='ide-rtabs' style={{marginBottom: 16}}>
        {TABS.map(([key, label]) => (
          <button
            key={key}
            type='button'
            className={`ide-rtab${section === key ? ' active' : ''}`}
            onClick={() => setSection(key)}
          >
            {label}
          </button>
        ))}
      </div>

      {toast && (
        <div
          className='ide-card'
          style={{
            marginBottom: 12,
            color: toast.kind === 'ok' ? 'var(--ide-green)' : 'var(--ide-red)',
            borderColor: toast.kind === 'ok' ? 'var(--ide-green)' : 'var(--ide-red)',
            fontSize: 12.5,
          }}
        >
          {toast.text}
        </div>
      )}

      {section === 'overview' && <AdminOverview />}
      {section === 'users' && <AdminUsers ok={ok} bad={bad} />}
      {section === 'orgs' && <AdminOrgs ok={ok} bad={bad} />}
      {section === 'analytics' && <AdminAnalytics orgId={user?.orgId ?? null} />}
      {section === 'ops' && <AdminOps ok={ok} bad={bad} />}
      {section === 'topups' && <AdminTopups ok={ok} bad={bad} />}
    </div>
  );
}

// Overview (funnel + tiers)
function AdminOverview() {
  const [funnel, setFunnel] = useState<api.ActivationFunnelReport | null>(null);
  const [tiers, setTiers] = useState<api.TierLifecycleDto[]>([]);

  useEffect(() => {
    api
      .getActivationFunnel(30)
      .then(setFunnel)
      // keep the grid visible on error rather than spinning forever
      .catch(() => setFunnel({windowDays: 30, windowStart: '', stages: {}}));
    api
      .fetchTiersLifecycle()
      .then(setTiers)
      .catch(() => {});
  }, []);

  const stage = (k: string) => {
    const s = funnel?.stages?.[k];
    return s ? String(s.windowed) : '—';
  };

  if (!funnel) return <IdeLoader label='Loading admin…' />;
  return (
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
      <AdminList>
        {tiers.map((t, i) => (
          <div key={t.tier} style={rowStyle(i === tiers.length - 1)}>
            <span style={{flex: 1, fontWeight: 500}}>{t.displayName || t.tier}</span>
            <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
              {t.activeSubscribers} subs
            </span>
            <IdeBadge kind={t.state === 'LISTED' ? 'ok' : t.state === 'HIDDEN' ? 'warn' : 'bad'}>
              {t.state}
            </IdeBadge>
          </div>
        ))}
      </AdminList>
    </>
  );
}

function AdminUsers({ok, bad}: {ok: (s: string) => void; bad: (e: unknown) => void}) {
  const [users, setUsers] = useState<api.AuthUser[] | null>(null);
  const [suspendFor, setSuspendFor] = useState<api.AuthUser | null>(null);
  const [grantFor, setGrantFor] = useState<api.AuthUser | null>(null);

  const load = () =>
    api
      .adminListUsers()
      .then(setUsers)
      .catch(() => setUsers([]));
  useEffect(() => {
    load();
  }, []);

  // Optimistically merge a server-returned user back into the list.
  const patch = (u: api.AuthUser) =>
    setUsers((prev) => (prev ? prev.map((x) => (x.id === u.id ? u : x)) : prev));

  function setTier(u: api.AuthUser, tier: string) {
    api
      .adminUpdateUserTier(u.id, tier)
      .then((r) => {
        patch(r);
        ok(`${u.email} → ${tier}`);
      })
      .catch(bad);
  }
  function setRole(u: api.AuthUser, role: string) {
    api
      .adminUpdateUserRole(u.id, role)
      .then((r) => {
        patch(r);
        ok(`${u.email} role → ${role}`);
      })
      .catch(bad);
  }
  function applyStatus(u: api.AuthUser, status: string) {
    return api
      .adminUpdateUserStatus(u.id, status)
      .then((r) => {
        patch(r);
        ok(`${u.email} → ${status}`);
      })
      .catch(bad);
  }
  function setStatus(u: api.AuthUser, status: string) {
    if (status === 'SUSPENDED') setSuspendFor(u);
    else void applyStatus(u, status);
  }

  if (!users) return <IdeLoader label='Loading users…' />;
  return (
    <>
      <div className='ide-h2'>Users · {users.length}</div>
      <AdminList>
        {users.map((u, i) => (
          <div key={u.id} style={{...rowStyle(i === users.length - 1), flexWrap: 'wrap'}}>
            <div style={{flex: 1, minWidth: 180}}>
              <div style={{fontWeight: 500}}>
                {u.firstName} {u.lastName}
              </div>
              <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}} className='ide-mono'>
                {u.email}
              </div>
            </div>
            <MiniSelect value={u.tier} options={USER_TIERS} onChange={(v) => setTier(u, v)} />
            <MiniSelect value={u.globalRole} options={USER_ROLES} onChange={(v) => setRole(u, v)} />
            <MiniSelect
              value={USER_STATUSES.includes(u.status) ? u.status : 'ACTIVE'}
              options={USER_STATUSES}
              onChange={(v) => setStatus(u, v)}
            />
            <button
              className='ide-btn ghost'
              style={{padding: '3px 9px', fontSize: 12}}
              onClick={() => setGrantFor(u)}
            >
              + Runs
            </button>
          </div>
        ))}
      </AdminList>

      <ConfirmModal
        open={!!suspendFor}
        title='Suspend user'
        body={suspendFor ? `Suspend ${suspendFor.email}?` : ''}
        confirmLabel='Suspend'
        danger
        onConfirm={async () => {
          if (suspendFor) await applyStatus(suspendFor, 'SUSPENDED');
        }}
        onClose={() => setSuspendFor(null)}
      />
      <PromptModal
        open={!!grantFor}
        title='Grant runs'
        body={grantFor ? `Grant how many runs to ${grantFor.email}?` : ''}
        fields={[{name: 'quantity', label: 'Quantity', defaultValue: '10', required: true}]}
        submitLabel='Grant'
        // eslint-disable-next-line no-restricted-syntax -- PromptModal callback prop (modal owns submit lifecycle); result reported via ok()/bad() toast.
        onSubmit={async (v) => {
          const u = grantFor;
          setGrantFor(null);
          if (!u) return;
          const qty = Number(v.quantity);
          if (!Number.isFinite(qty) || qty <= 0) return;
          await api
            .adminGrantUserRuns(u.id, qty, null, 'admin grant')
            .then(() => ok(`Granted ${qty} runs to ${u.email}`))
            .catch(bad);
        }}
        onClose={() => setGrantFor(null)}
      />
    </>
  );
}

function AdminOrgs({ok, bad}: {ok: (s: string) => void; bad: (e: unknown) => void}) {
  const [orgs, setOrgs] = useState<api.OrgDto[] | null>(null);
  const [suspendFor, setSuspendFor] = useState<api.OrgDto | null>(null);
  const [limitFor, setLimitFor] = useState<api.OrgDto | null>(null);
  const [grantFor, setGrantFor] = useState<api.OrgDto | null>(null);

  const load = () =>
    api
      .adminListOrgs()
      .then(setOrgs)
      .catch(() => setOrgs([]));
  useEffect(() => {
    load();
  }, []);

  const patch = (o: api.OrgDto) =>
    setOrgs((prev) => (prev ? prev.map((x) => (x.id === o.id ? o : x)) : prev));

  function setTier(o: api.OrgDto, tier: string) {
    api
      .adminUpdateOrgTier(o.id, tier)
      .then((r) => {
        patch(r);
        ok(`${o.name} → ${tier}`);
      })
      .catch(bad);
  }
  function applyStatus(o: api.OrgDto, status: string) {
    return api
      .adminUpdateOrgStatus(o.id, status)
      .then((r) => {
        patch(r);
        ok(`${o.name} → ${status}`);
      })
      .catch(bad);
  }
  function setStatus(o: api.OrgDto, status: string) {
    if (status === 'SUSPENDED') setSuspendFor(o);
    else void applyStatus(o, status);
  }

  if (!orgs) return <IdeLoader label='Loading orgs…' />;
  return (
    <>
      <div className='ide-h2'>Orgs · {orgs.length}</div>
      <AdminList>
        {orgs.map((o, i) => (
          <div key={o.id} style={{...rowStyle(i === orgs.length - 1), flexWrap: 'wrap'}}>
            <div style={{flex: 1, minWidth: 160}}>
              <div style={{fontWeight: 500}}>{o.name}</div>
              <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}>
                {o.monthlyRunCount} runs ·{' '}
                {o.monthlyRunLimitOverride != null
                  ? `${o.monthlyRunLimitOverride} cap`
                  : 'tier cap'}
              </div>
            </div>
            <MiniSelect value={o.tier} options={ORG_TIERS} onChange={(v) => setTier(o, v)} />
            <MiniSelect
              value={ORG_STATUSES.includes(o.status) ? o.status : 'ACTIVE'}
              options={ORG_STATUSES}
              onChange={(v) => setStatus(o, v)}
            />
            <button
              className='ide-btn ghost'
              style={{padding: '3px 9px', fontSize: 12}}
              onClick={() => setLimitFor(o)}
            >
              Limit
            </button>
            <button
              className='ide-btn ghost'
              style={{padding: '3px 9px', fontSize: 12}}
              onClick={() => setGrantFor(o)}
            >
              + Runs
            </button>
          </div>
        ))}
      </AdminList>

      <ConfirmModal
        open={!!suspendFor}
        title='Suspend org'
        body={suspendFor ? `Suspend org ${suspendFor.name}?` : ''}
        confirmLabel='Suspend'
        danger
        onConfirm={async () => {
          if (suspendFor) await applyStatus(suspendFor, 'SUSPENDED');
        }}
        onClose={() => setSuspendFor(null)}
      />
      <PromptModal
        open={!!limitFor}
        title='Run-limit override'
        body={
          limitFor
            ? `Monthly run-limit override for ${limitFor.name} (blank = clear, use tier default):`
            : ''
        }
        fields={[
          {
            name: 'limit',
            label: 'Monthly limit',
            placeholder: 'blank = tier default',
            defaultValue:
              limitFor?.monthlyRunLimitOverride != null
                ? String(limitFor.monthlyRunLimitOverride)
                : '',
          },
        ]}
        submitLabel='Save'
        // eslint-disable-next-line no-restricted-syntax -- PromptModal callback prop (modal owns submit lifecycle); result reported via ok()/bad() toast.
        onSubmit={async (v) => {
          const o = limitFor;
          setLimitFor(null);
          if (!o) return;
          const limit = v.limit.trim() === '' ? null : Number(v.limit);
          if (limit !== null && (!Number.isFinite(limit) || limit < 0)) return;
          await api
            .adminUpdateOrgRunLimit(o.id, limit)
            .then((r) => {
              patch(r);
              ok(`${o.name} limit → ${limit ?? 'default'}`);
            })
            .catch(bad);
        }}
        onClose={() => setLimitFor(null)}
      />
      <PromptModal
        open={!!grantFor}
        title='Grant runs'
        body={grantFor ? `Grant how many runs to ${grantFor.name}?` : ''}
        fields={[{name: 'quantity', label: 'Quantity', defaultValue: '10', required: true}]}
        submitLabel='Grant'
        // eslint-disable-next-line no-restricted-syntax -- PromptModal callback prop (modal owns submit lifecycle); result reported via ok()/bad() toast.
        onSubmit={async (v) => {
          const o = grantFor;
          setGrantFor(null);
          if (!o) return;
          const qty = Number(v.quantity);
          if (!Number.isFinite(qty) || qty <= 0) return;
          await api
            .adminGrantOrgRuns(o.id, qty, null, 'admin grant')
            .then(() => ok(`Granted ${qty} runs to ${o.name}`))
            .catch(bad);
        }}
        onClose={() => setGrantFor(null)}
      />
    </>
  );
}

// The analytics endpoints are entity-scoped (entityType + entityId). For the
// admin's own org we surface usage + billing; member rollup is org-only.
function AdminAnalytics({orgId}: {orgId: string | null}) {
  const [usage, setUsage] = useState<api.UsageMetrics | null>(null);
  const [billing, setBilling] = useState<api.BillingOverviewData | null>(null);
  const [members, setMembers] = useState<api.MemberUsage[] | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!orgId) {
      setLoading(false);
      return;
    }
    Promise.allSettled([
      api.getAnalyticsUsage('org', orgId, 30),
      api.getAnalyticsBilling('org', orgId),
      api.getAnalyticsMembers(orgId, 30),
    ])
      .then(([u, b, m]) => {
        if (u.status === 'fulfilled') setUsage(u.value);
        if (b.status === 'fulfilled') setBilling(b.value);
        if (m.status === 'fulfilled') setMembers(m.value);
      })
      .finally(() => setLoading(false));
  }, [orgId]);

  if (!orgId) return <div className='ide-empty'>No org context — analytics are org-scoped.</div>;
  if (loading) return <IdeLoader label='Loading analytics…' />;

  const costEntries = usage ? Object.entries(usage.costByModel || {}) : [];
  return (
    <>
      <div className='ide-sub' style={{marginTop: -6}}>
        Scoped to your org · last 30d.
      </div>

      {usage && (
        <>
          <div className='ide-h2'>Usage</div>
          <div style={{display: 'flex', gap: 10, flexWrap: 'wrap'}}>
            {[
              ['Total runs', String(usage.totalRuns)],
              ['Successful', String(usage.successfulRuns)],
              ['Failed', String(usage.failedRuns)],
              ['Partial', String(usage.partialRuns)],
              ['Total cost', `$${(usage.totalCost ?? 0).toFixed(2)}`],
            ].map(([label, value]) => (
              <div key={label} className='ide-card' style={{flex: 1, minWidth: 110}}>
                <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>{label}</div>
                <div
                  style={{
                    fontSize: 20,
                    fontWeight: 700,
                    color: 'var(--ide-fg-bright)',
                    marginTop: 4,
                  }}
                >
                  {value}
                </div>
              </div>
            ))}
          </div>
        </>
      )}

      {costEntries.length > 0 && (
        <>
          <div className='ide-h2'>Cost by model</div>
          <AdminList>
            {costEntries.map(([model, cost], i) => (
              <div key={model} style={{...rowStyle(i === costEntries.length - 1), fontSize: 12.5}}>
                <span className='ide-mono' style={{flex: 1}}>
                  {model}
                </span>
                <span style={{color: 'var(--ide-fg-bright)'}}>${cost.toFixed(2)}</span>
              </div>
            ))}
          </AdminList>
        </>
      )}

      {billing && (
        <>
          <div className='ide-h2'>Billing</div>
          <div className='ide-card'>
            <div style={{display: 'flex', justifyContent: 'space-between', marginBottom: 6}}>
              <span style={{color: 'var(--ide-fg-muted)'}}>Plan</span>
              <span>{billing.currentPlan}</span>
            </div>
            <div style={{display: 'flex', justifyContent: 'space-between', marginBottom: 6}}>
              <span style={{color: 'var(--ide-fg-muted)'}}>Status</span>
              <span>{billing.subscriptionStatus}</span>
            </div>
            <div style={{display: 'flex', justifyContent: 'space-between', marginBottom: 6}}>
              <span style={{color: 'var(--ide-fg-muted)'}}>Period usage</span>
              <span>
                {billing.periodUsage} / {billing.runLimit}
              </span>
            </div>
            <div style={{display: 'flex', justifyContent: 'space-between'}}>
              <span style={{color: 'var(--ide-fg-muted)'}}>Top-up credits</span>
              <span>{billing.topupCredits}</span>
            </div>
          </div>
        </>
      )}

      {members && members.length > 0 && (
        <>
          <div className='ide-h2'>Members · {members.length}</div>
          <AdminList>
            {members.map((m, i) => (
              <div key={m.userId} style={{...rowStyle(i === members.length - 1), fontSize: 12.5}}>
                <span style={{flex: 1}}>
                  {m.firstName} {m.lastName}
                </span>
                <span style={{color: 'var(--ide-fg-muted)'}}>{m.runCount} runs</span>
                <span style={{color: 'var(--ide-fg-bright)'}}>
                  {Math.round((m.successRate ?? 0) * 100)}%
                </span>
              </div>
            ))}
          </AdminList>
        </>
      )}
    </>
  );
}

// Pill toggle — mirrors the notification-pref switch in IdeMore (38×20).
function IdeSwitch({
  on,
  disabled,
  onClick,
}: {
  on: boolean;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type='button'
      aria-pressed={on}
      disabled={disabled}
      onClick={onClick}
      style={{
        position: 'relative',
        width: 38,
        height: 20,
        flexShrink: 0,
        borderRadius: 999,
        border: '1px solid var(--ide-border)',
        background: on ? 'var(--ide-accent)' : 'var(--ide-bg-tab-inactive)',
        cursor: disabled ? 'default' : 'pointer',
        opacity: disabled ? 0.6 : 1,
        transition: 'background 0.15s',
      }}
    >
      <span
        style={{
          position: 'absolute',
          top: 1,
          left: on ? 18 : 1,
          width: 16,
          height: 16,
          borderRadius: '50%',
          background: '#fff',
          transition: 'left 0.15s',
        }}
      />
    </button>
  );
}

// Collapsible ide-h2 header — count + chevron, toggles the section body.
function CollapsibleHeader({
  label,
  count,
  open,
  onToggle,
}: {
  label: string;
  count: number;
  open: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type='button'
      className='ide-h2'
      onClick={onToggle}
      aria-expanded={open}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 6,
        width: '100%',
        background: 'none',
        border: 'none',
        padding: 0,
        cursor: 'pointer',
        textAlign: 'left',
      }}
    >
      <span className='ide-tree-chev'>{open ? '▾' : '▸'}</span>
      <span>
        {label} · {count}
      </span>
    </button>
  );
}

// Feature flags (Ops sub-panel)
// Collapsed by default. SUPER_ADMIN may toggle BOOLEAN flags inline — optimistic
// update with revert-on-error; non-boolean flags stay read-only value badges.
function FeatureFlagsPanel({ok, bad}: {ok: (s: string) => void; bad: (e: unknown) => void}) {
  const role = useAuthStore((s) => s.user?.globalRole);
  const isSuperAdmin = role === 'SUPER_ADMIN';
  const [flags, setFlags] = useState<api.FeatureFlagDto[] | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    api
      .adminListFeatureFlags()
      .then(setFlags)
      .catch(() => setFlags([]));
  }, []);

  function toggle(f: api.FeatureFlagDto) {
    if (!isSuperAdmin || busy) return;
    const next = f.flagValue === 'true' ? 'false' : 'true';
    // optimistic
    setFlags((prev) =>
      prev ? prev.map((x) => (x.id === f.id ? {...x, flagValue: next} : x)) : prev,
    );
    setBusy(f.flagKey);
    api
      .adminUpdateFeatureFlag(f.flagKey, next)
      .then((updated) => {
        setFlags((prev) => (prev ? prev.map((x) => (x.id === f.id ? updated : x)) : prev));
        ok(`${f.flagKey} → ${next}`);
      })
      .catch((e) => {
        // revert
        setFlags((prev) => (prev ? prev.map((x) => (x.id === f.id ? f : x)) : prev));
        bad(e);
      })
      .finally(() => setBusy(null));
  }

  if (flags === null) return <IdeLoader label='Loading feature flags…' />;
  return (
    <>
      <CollapsibleHeader
        label='Feature flags'
        count={flags.length}
        open={open}
        onToggle={() => setOpen((v) => !v)}
      />
      {open &&
        (flags.length === 0 ? (
          <div className='ide-empty'>No feature flags.</div>
        ) : (
          <AdminList>
            {flags.map((f, i) => {
              const isBool = f.valueType === 'BOOLEAN';
              return (
                <div key={f.id} style={{...rowStyle(i === flags.length - 1), fontSize: 12.5}}>
                  <span style={{flex: 1, minWidth: 0}}>
                    <span
                      className='ide-mono'
                      style={{display: 'block', color: 'var(--ide-fg-bright)'}}
                    >
                      {f.flagKey}
                    </span>
                    {f.description && (
                      <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                        {f.description}
                      </span>
                    )}
                  </span>
                  <span style={{color: 'var(--ide-fg-muted)', fontSize: 11}}>{f.scope}</span>
                  {isBool && isSuperAdmin ? (
                    <IdeSwitch
                      on={f.flagValue === 'true'}
                      disabled={busy === f.flagKey}
                      onClick={() => toggle(f)}
                    />
                  ) : (
                    <IdeBadge kind={f.flagValue === 'true' ? 'ok' : 'warn'}>{f.flagValue}</IdeBadge>
                  )}
                </div>
              );
            })}
          </AdminList>
        ))}
    </>
  );
}

function AdminOps({ok, bad}: {ok: (s: string) => void; bad: (e: unknown) => void}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [confirmKind, setConfirmKind] = useState<'halt' | 'reconcile' | null>(null);

  function doHalt() {
    setBusy('halt');
    return api
      .haltAllActiveRuns('admin emergency halt')
      .then((r) => ok(`Halted ${r.haltedCount} run(s).`))
      .catch(bad)
      .finally(() => setBusy(null));
  }
  function verifyChain() {
    setBusy('chain');
    api
      .verifyAuditChain()
      .then((r) =>
        r.clean
          ? ok(`Audit chain clean · ${r.verified}/${r.totalChained} verified.`)
          : bad(
              new Error(
                `Chain BREAK at seq ${r.firstBreak?.chainSeq}: ${r.firstBreak?.reason ?? 'unknown'}`,
              ),
            ),
      )
      .catch(bad)
      .finally(() => setBusy(null));
  }
  async function doReconcile() {
    setBusy('reconcile');
    try {
      const r = await fetch('/api/admin/executions/reconcile', {
        method: 'POST',
        headers: {Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`},
      });
      if (!r.ok) throw new Error(`Reconcile failed (${r.status}).`);
      ok('Reconcile dispatched.');
    } catch (e: unknown) {
      bad(e);
    } finally {
      setBusy(null);
    }
  }
  function usageReport(period: 'WEEKLY' | 'MONTHLY') {
    setBusy('report');
    api
      .adminTriggerUsageReport(period)
      .then((r) => ok(`${period} usage report sent (${r.emailsSent} email(s)).`))
      .catch(bad)
      .finally(() => setBusy(null));
  }

  function OpRow({
    title,
    desc,
    danger,
    busyKey,
    action,
    label,
  }: {
    title: string;
    desc: string;
    danger?: boolean;
    busyKey: string;
    action: () => void;
    label: string;
  }) {
    return (
      <div style={{...ROW_BASE, borderBottom: '1px solid var(--ide-border)'}}>
        <div style={{flex: 1}}>
          <div style={{fontWeight: 500, color: danger ? 'var(--ide-red)' : undefined}}>{title}</div>
          <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}>{desc}</div>
        </div>
        <button
          className='ide-btn ghost'
          style={danger ? {color: 'var(--ide-red)', borderColor: 'var(--ide-red)'} : undefined}
          disabled={busy === busyKey}
          onClick={action}
        >
          {busy === busyKey ? '…' : label}
        </button>
      </div>
    );
  }

  return (
    <>
      <div className='ide-h2'>Operations</div>
      <AdminList>
        <OpRow
          title='Halt all active runs'
          desc='Emergency stop — cancels every in-flight pipeline platform-wide.'
          danger
          busyKey='halt'
          action={() => setConfirmKind('halt')}
          label='Halt all'
        />
        <OpRow
          title='Verify audit chain'
          desc='Recompute the activity_audit hash-chain and report the first break.'
          busyKey='chain'
          action={verifyChain}
          label='Verify'
        />
        <OpRow
          title='Reconcile orphan runs'
          desc='Mark stale non-terminal ExecutionRuns as FAILED.'
          busyKey='reconcile'
          action={() => setConfirmKind('reconcile')}
          label='Reconcile'
        />
        <div style={{...ROW_BASE, borderBottom: 'none'}}>
          <div style={{flex: 1}}>
            <div style={{fontWeight: 500}}>Trigger usage report</div>
            <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}>
              Email the periodic usage digest to org admins.
            </div>
          </div>
          <button
            className='ide-btn ghost'
            disabled={busy === 'report'}
            onClick={() => usageReport('WEEKLY')}
          >
            Weekly
          </button>
          <button
            className='ide-btn ghost'
            disabled={busy === 'report'}
            onClick={() => usageReport('MONTHLY')}
          >
            Monthly
          </button>
        </div>
      </AdminList>

      <FeatureFlagsPanel ok={ok} bad={bad} />

      <ConfirmModal
        open={confirmKind === 'halt'}
        title='Halt all active runs'
        body='Halt ALL active runs platform-wide? This cancels in-flight pipelines for every org.'
        confirmLabel='Halt all'
        danger
        busy={busy === 'halt'}
        onConfirm={doHalt}
        onClose={() => setConfirmKind(null)}
      />
      <ConfirmModal
        open={confirmKind === 'reconcile'}
        title='Reconcile orphan runs'
        body='Reconcile orphan runs — mark stale non-terminal runs FAILED?'
        confirmLabel='Reconcile'
        busy={busy === 'reconcile'}
        onConfirm={doReconcile}
        onClose={() => setConfirmKind(null)}
      />
    </>
  );
}

function AdminTopups({ok, bad}: {ok: (s: string) => void; bad: (e: unknown) => void}) {
  const [pkgs, setPkgs] = useState<api.TopupPackageAdmin[] | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleteFor, setDeleteFor] = useState<api.TopupPackageAdmin | null>(null);

  const load = () =>
    api
      .adminListTopupPackages()
      .then(setPkgs)
      .catch(() => setPkgs([]));
  useEffect(() => {
    load();
  }, []);

  function toggleActive(p: api.TopupPackageAdmin) {
    api
      .adminUpdateTopupPackage(p.id, {active: !p.active})
      .then(() => {
        ok(`${p.name} ${!p.active ? 'activated' : 'deactivated'}`);
        load();
      })
      .catch(bad);
  }

  if (!pkgs) return <IdeLoader label='Loading top-up packages…' />;
  return (
    <>
      <div
        className='ide-h2'
        style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}
      >
        <span>Top-up packages · {pkgs.length}</span>
        <button
          className='ide-btn'
          style={{padding: '4px 12px', fontSize: 12}}
          onClick={() => setCreating(true)}
        >
          + New
        </button>
      </div>
      {pkgs.length === 0 ? (
        <div className='ide-empty'>No top-up packages.</div>
      ) : (
        <AdminList>
          {pkgs.map((p, i) => (
            <div key={p.id} style={{...rowStyle(i === pkgs.length - 1), flexWrap: 'wrap'}}>
              <div style={{flex: 1, minWidth: 150}}>
                <div style={{fontWeight: 500}}>{p.name}</div>
                <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}>
                  {p.runCount} runs · ₹{p.amountInr} / ${p.amountUsd}
                </div>
              </div>
              <IdeBadge kind={p.active ? 'ok' : 'warn'}>{p.active ? 'Active' : 'Off'}</IdeBadge>
              <button
                className='ide-btn ghost'
                style={{padding: '3px 9px', fontSize: 12}}
                onClick={() => toggleActive(p)}
              >
                {p.active ? 'Disable' : 'Enable'}
              </button>
              <button
                className='ide-btn ghost'
                style={{
                  padding: '3px 9px',
                  fontSize: 12,
                  color: 'var(--ide-red)',
                  borderColor: 'var(--ide-red)',
                }}
                onClick={() => setDeleteFor(p)}
              >
                Delete
              </button>
            </div>
          ))}
        </AdminList>
      )}

      <PromptModal
        open={creating}
        title='New top-up package'
        fields={[
          {name: 'name', label: 'Package name', placeholder: 'Starter pack', required: true},
          {name: 'runCount', label: 'Run count', defaultValue: '50', required: true},
          {name: 'amountInr', label: 'Amount (INR)', defaultValue: '500', required: true},
        ]}
        submitLabel='Create'
        // eslint-disable-next-line no-restricted-syntax -- PromptModal callback prop (modal owns submit lifecycle); result reported via ok()/bad() toast.
        onSubmit={async (v) => {
          setCreating(false);
          const name = v.name;
          if (!name) return;
          const runCount = Number(v.runCount);
          const amountInr = Number(v.amountInr);
          if (!Number.isFinite(runCount) || runCount <= 0 || !Number.isFinite(amountInr)) return;
          await api
            .adminCreateTopupPackage({name, runCount, amountInr})
            .then(() => {
              ok(`Created ${name}`);
              load();
            })
            .catch(bad);
        }}
        onClose={() => setCreating(false)}
      />
      <ConfirmModal
        open={!!deleteFor}
        title='Delete package'
        body={deleteFor ? `Delete top-up package "${deleteFor.name}"?` : ''}
        confirmLabel='Delete'
        danger
        onConfirm={async () => {
          const p = deleteFor;
          if (!p) return;
          await api
            .adminDeleteTopupPackage(p.id)
            .then(() => {
              ok(`Deleted ${p.name}`);
              load();
            })
            .catch(bad);
        }}
        onClose={() => setDeleteFor(null)}
      />
    </>
  );
}
