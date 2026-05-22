import {useEffect, useMemo, useState} from 'react';
import {Link, useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';

type Dash = Record<string, unknown>;
const numOf = (s: Dash, k: string): number | undefined =>
  typeof s[k] === 'number' ? (s[k] as number) : undefined;

// Real IDE Projects list — health cards (avg score / spend / runs / PRs / status),
// replacing the Signal ProjectsPage. Mirrors its dashboard-derived stats.
export default function IdeProjects() {
  const navigate = useNavigate();
  const [projects, setProjects] = useState<api.ProjectDto[] | null>(null);
  const [stats, setStats] = useState<Record<string, Dash>>({});
  const [filter, setFilter] = useState<'mine' | 'archived'>('mine');
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');

  useEffect(() => {
    api
      .getProjects()
      .then((list) => {
        setProjects(list);
        list.forEach((p) => {
          api
            .getProjectDashboard(p.id)
            .then((d) => setStats((s) => ({...s, [p.id]: d as unknown as Dash})))
            .catch(() => {});
        });
      })
      .catch(() => setProjects([]));
  }, []);

  const filtered = useMemo(
    () => (projects ?? []).filter((p) => (filter === 'archived' ? !!p.archivedAt : !p.archivedAt)),
    [projects, filter],
  );

  async function create() {
    const name = newName.trim();
    if (!name) return;
    try {
      const p = await api.createProject({name});
      navigate(`/projects/${(p as {id?: string}).id ?? ''}`);
    } catch {
      setCreating(false);
    }
  }

  function card(p: api.ProjectDto) {
    const s = stats[p.id] || {};
    const totalRuns =
      (numOf(s, 'activeRuns') ?? 0) +
      (numOf(s, 'completedRuns') ?? 0) +
      (numOf(s, 'failedRuns') ?? 0);
    const prs = ((s.recentPrs as unknown[]) || []).filter((pr) => {
      const st =
        (pr as {status?: string; state?: string}).status || (pr as {state?: string}).state || '';
      return st.toUpperCase() === 'OPEN';
    }).length;
    const repoCount = new Set(
      ((s.recentPrs as unknown[]) || [])
        .map((pr) => (pr as {repoFullName?: string}).repoFullName)
        .filter(Boolean),
    ).size;
    const avg = numOf(s, 'recencyAvgScore') ?? numOf(s, 'avgScore');
    const spent = numOf(s, 'totalCostUsd');
    const status = p.archivedAt
      ? {l: 'Archived', k: 'warn' as const}
      : prs > 0
        ? {l: 'Live', k: 'bad' as const}
        : totalRuns > 0
          ? {l: 'Healthy', k: 'ok' as const}
          : {l: 'New', k: 'warn' as const};
    return (
      <Link key={p.id} className='ide-run-card' to={`/projects/${p.id}`}>
        <div style={{display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6}}>
          <span style={{fontSize: 16}}>▤</span>
          <span className='ide-run-title' style={{flex: 1, margin: 0}}>
            {p.name}
          </span>
          <IdeBadge kind={status.k}>{status.l}</IdeBadge>
        </div>
        <div className='ide-run-meta ide-mono' style={{marginBottom: 10}}>
          {repoCount} repo{repoCount === 1 ? '' : 's'} · {totalRuns} run{totalRuns === 1 ? '' : 's'}{' '}
          · {prs} PR{prs === 1 ? '' : 's'} open
        </div>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(3,1fr)',
            gap: 8,
            paddingTop: 10,
            borderTop: '1px solid var(--ide-border)',
          }}
        >
          {[
            ['Avg score', avg != null ? `${Math.round(avg)}%` : '—'],
            ['Spent', spent != null ? `$${spent.toFixed(2)}` : '—'],
            ['Tech debt', '—'],
          ].map(([k, v]) => (
            <div key={k}>
              <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>{k}</div>
              <div
                style={{fontSize: 15, fontWeight: 600, marginTop: 2, color: 'var(--ide-fg-bright)'}}
              >
                {v}
              </div>
            </div>
          ))}
        </div>
      </Link>
    );
  }

  return (
    <div className='ide-pane'>
      <div style={{display: 'flex', alignItems: 'center', gap: 12, marginBottom: 4}}>
        <div className='ide-h1' style={{margin: 0}}>
          Projects
        </div>
        <span style={{flex: 1}} />
        {!creating ? (
          <button className='ide-btn' onClick={() => setCreating(true)}>
            ＋ New project
          </button>
        ) : (
          <span style={{display: 'flex', gap: 6}}>
            <input
              className='ide-input'
              autoFocus
              placeholder='Project name'
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && create()}
              style={{width: 200}}
            />
            <button className='ide-btn' onClick={create}>
              Create
            </button>
            <button className='ide-btn ghost' onClick={() => setCreating(false)}>
              ✕
            </button>
          </span>
        )}
      </div>
      <div className='ide-sub'>{filtered.length} projects</div>

      <div style={{display: 'flex', gap: 4, marginBottom: 14}}>
        {(['mine', 'archived'] as const).map((f) => (
          <button
            key={f}
            className='ide-badge'
            style={{
              cursor: 'pointer',
              textTransform: 'capitalize',
              borderColor: filter === f ? 'var(--ide-accent)' : undefined,
              color: filter === f ? 'var(--ide-accent)' : undefined,
            }}
            onClick={() => setFilter(f)}
          >
            {f}
          </button>
        ))}
      </div>

      {projects === null ? (
        <IdeLoader label='Loading projects…' />
      ) : filtered.length === 0 ? (
        <div className='ide-empty'>No {filter} projects.</div>
      ) : (
        <div className='ide-grid'>{filtered.map(card)}</div>
      )}
    </div>
  );
}
