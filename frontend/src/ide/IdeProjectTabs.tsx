import {useEffect, useState} from 'react';
import {Link, useLocation, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {runTitleFor, repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';

type Dash = Record<string, unknown>;
const numOf = (s: Dash, k: string): number | undefined =>
  typeof s[k] === 'number' ? (s[k] as number) : undefined;
function fmtWhen(iso?: string | null) {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

// Workbench tab strip — shown on every project sub-route.
const T: Array<{label: string; seg: string}> = [
  {label: 'Compose', seg: ''},
  {label: 'Pulse', seg: '/pulse'},
  {label: 'Repos', seg: '/repos'},
  {label: 'Runs', seg: '/runs'},
  {label: 'Code', seg: '/code'},
  {label: 'Chat', seg: '/chat'},
  {label: 'Architecture', seg: '/architecture'},
  {label: 'Members', seg: '/members'},
  {label: 'Activity', seg: '/activity'},
  {label: 'Settings', seg: '/settings'},
];
export function ProjectTabs({projectId}: {projectId: string}) {
  const {pathname} = useLocation();
  return (
    <div className='ide-rtabs' style={{marginBottom: 16}}>
      {T.map((t) => {
        const to = `/projects/${projectId}${t.seg}`;
        const active = t.seg === '' ? pathname === to : pathname.startsWith(to);
        return (
          <Link
            key={t.label}
            to={to}
            className={`ide-rtab${active ? ' active' : ''}`}
            style={{textDecoration: 'none'}}
          >
            {t.label}
          </Link>
        );
      })}
    </div>
  );
}

function Metric({label, value}: {label: string; value: string}) {
  return (
    <div className='ide-card' style={{flex: 1, minWidth: 130}}>
      <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>{label}</div>
      <div style={{fontSize: 22, fontWeight: 700, color: 'var(--ide-fg-bright)', marginTop: 4}}>
        {value}
      </div>
    </div>
  );
}

// ───────────────────────── Pulse ─────────────────────────
export function IdeProjectPulse() {
  const {projectId = ''} = useParams();
  const [dash, setDash] = useState<Dash | null>(null);
  const [health, setHealth] = useState<Array<Record<string, unknown>>>([]);

  useEffect(() => {
    if (!projectId) return;
    api
      .getProjectDashboard(projectId)
      .then((d) => setDash(d as unknown as Dash))
      .catch(() => setDash({}));
    api
      .getWorkspaceHealth(projectId)
      .then((h) => {
        const arr = (h as unknown as {repos?: unknown[]}).repos || (Array.isArray(h) ? h : []);
        setHealth(arr as Array<Record<string, unknown>>);
      })
      .catch(() => setHealth([]));
  }, [projectId]);

  const avgScore = dash ? (numOf(dash, 'recencyAvgScore') ?? numOf(dash, 'avgScore')) : undefined;
  const dur = dash ? numOf(dash, 'avgDurationMs') : undefined;
  const runTime = dur ? `${Math.floor(dur / 60000)}m ${Math.floor((dur % 60000) / 1000)}s` : '—';
  const spent = dash ? (numOf(dash, 'totalSpent30d') ?? numOf(dash, 'totalCostUsd')) : undefined;
  const merged = dash
    ? (numOf(dash, 'prsMerged30d') ??
      ((dash.recentPrs as unknown[]) || []).filter(
        (p) => ((p as {status?: string}).status || '').toUpperCase() === 'MERGED',
      ).length)
    : undefined;

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div className='ide-h1'>Pulse</div>
      {!dash ? (
        <IdeLoader label='Loading pulse…' />
      ) : (
        <>
          <div style={{display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 8}}>
            <Metric label='Avg score' value={avgScore != null ? `${Math.round(avgScore)}%` : '—'} />
            <Metric label='Avg run time' value={runTime} />
            <Metric label='Spent · 30d' value={spent != null ? `$${spent.toFixed(2)}` : '—'} />
            <Metric label='PRs merged · 30d' value={merged != null ? String(merged) : '—'} />
          </div>
          <div className='ide-h2'>Workspace health</div>
          {health.length === 0 ? (
            <div className='ide-empty'>No linked repos.</div>
          ) : (
            <div className='ide-card' style={{padding: 0}}>
              {health.map((r, i) => {
                const drift = numOf(r, 'commitsBehind') ?? numOf(r, 'behindBy') ?? 0;
                const wh = (r as {webhookInstalled?: boolean}).webhookInstalled;
                const open = numOf(r, 'openPrs') ?? 0;
                return (
                  <div
                    key={i}
                    style={{
                      display: 'flex',
                      gap: 10,
                      alignItems: 'center',
                      padding: '10px 16px',
                      borderBottom: i < health.length - 1 ? '1px solid var(--ide-border)' : 'none',
                    }}
                  >
                    <span style={{flex: 1, fontWeight: 500}}>{repoDisplayName(r)}</span>
                    <IdeBadge kind={drift > 0 ? 'warn' : 'ok'}>
                      {drift > 0 ? `drift +${drift}` : 'in sync'}
                    </IdeBadge>
                    <IdeBadge kind={wh ? 'ok' : 'warn'}>{wh ? 'webhook' : 'no webhook'}</IdeBadge>
                    <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>{open} open PR</span>
                  </div>
                );
              })}
            </div>
          )}
        </>
      )}
    </div>
  );
}

// ───────────────────────── Runs ─────────────────────────
export function IdeProjectRuns() {
  const {projectId = ''} = useParams();
  const [runs, setRuns] = useState<api.RunSummary[] | null>(null);

  useEffect(() => {
    if (!projectId) return;
    api
      .getRuns({projectId, size: 40})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => setRuns([]));
  }, [projectId]);

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div className='ide-h1'>Runs</div>
      {runs === null ? (
        <IdeLoader label='Loading runs…' />
      ) : runs.length === 0 ? (
        <div className='ide-empty'>No runs in this project yet.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {runs.map((r, i) => (
            <Link
              key={r.runId}
              to={`/result/${r.runId}`}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                padding: '11px 16px',
                textDecoration: 'none',
                color: 'var(--ide-fg)',
                borderBottom: i < runs.length - 1 ? '1px solid var(--ide-border)' : 'none',
              }}
            >
              <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
              <span
                style={{
                  flex: 1,
                  minWidth: 0,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  fontWeight: 500,
                }}
              >
                {runTitleFor(r)}
              </span>
              <span style={{fontSize: 12, color: 'var(--ide-fg-muted)', flexShrink: 0}}>
                {fmtWhen(r.startedAt)}
              </span>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

// ───────────────────────── Repos ─────────────────────────
export function IdeProjectRepos() {
  const {projectId = ''} = useParams();
  const [repos, setRepos] = useState<Array<Record<string, unknown>> | null>(null);
  const [health, setHealth] = useState<Record<string, Record<string, unknown>>>({});
  const [busy, setBusy] = useState('');
  const [msg, setMsg] = useState('');

  function load() {
    api
      .getProjectRepos(projectId)
      .then((l) => setRepos(l as unknown as Array<Record<string, unknown>>))
      .catch(() => setRepos([]));
    api
      .getWorkspaceHealth(projectId)
      .then((h) => {
        const arr = (h as unknown as {repos?: unknown[]}).repos || (Array.isArray(h) ? h : []);
        const map: Record<string, Record<string, unknown>> = {};
        (arr as Array<Record<string, unknown>>).forEach((r) => {
          map[repoDisplayName(r)] = r;
        });
        setHealth(map);
      })
      .catch(() => {});
  }
  useEffect(() => {
    if (projectId) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  async function act(key: string, fn: () => Promise<unknown>) {
    setBusy(key);
    setMsg('');
    try {
      await fn();
      setMsg('Done.');
      load();
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Failed.');
    } finally {
      setBusy('');
    }
  }

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div className='ide-h1'>Repos</div>
      {msg && (
        <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-blue)'}}>
          {msg}
        </div>
      )}
      {repos === null ? (
        <IdeLoader label='Loading repos…' />
      ) : repos.length === 0 ? (
        <div className='ide-empty'>No repos linked to this project.</div>
      ) : (
        <div className='ide-grid'>
          {repos.map((r) => {
            const id = repoIdFor(r);
            const name = repoDisplayName(r);
            const h = health[name] || {};
            const drift = numOf(h, 'commitsBehind') ?? numOf(h, 'behindBy') ?? 0;
            const wh = (h as {webhookInstalled?: boolean}).webhookInstalled;
            const open = numOf(h, 'openPrs') ?? 0;
            return (
              <div key={id} className='ide-card'>
                <div style={{fontWeight: 600, color: 'var(--ide-fg-bright)', marginBottom: 8}}>
                  ▤ {name}
                </div>
                <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10}}>
                  <IdeBadge kind={drift > 0 ? 'warn' : 'ok'}>
                    {drift > 0 ? `drift +${drift}` : 'in sync'}
                  </IdeBadge>
                  <IdeBadge kind={wh ? 'ok' : 'warn'}>{wh ? 'webhook ✓' : 'no webhook'}</IdeBadge>
                  <IdeBadge kind='ok'>{open} open PR</IdeBadge>
                </div>
                <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
                  <button
                    className='ide-btn ghost'
                    style={{padding: '4px 10px'}}
                    disabled={busy === `sync${id}`}
                    onClick={() => act(`sync${id}`, () => api.syncRepo(id))}
                  >
                    Sync
                  </button>
                  {!wh && (
                    <button
                      className='ide-btn ghost'
                      style={{padding: '4px 10px'}}
                      disabled={busy === `wh${id}`}
                      onClick={() => act(`wh${id}`, () => api.installRepoWebhook(id))}
                    >
                      Install webhook
                    </button>
                  )}
                  <button
                    className='ide-btn ghost'
                    style={{padding: '4px 10px', color: 'var(--ide-red)'}}
                    disabled={busy === `unlink${id}`}
                    onClick={() => act(`unlink${id}`, () => api.unlinkProjectRepo(projectId, id))}
                  >
                    Unlink
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
