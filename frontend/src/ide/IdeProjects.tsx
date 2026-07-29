import {useEffect, useMemo, useState} from 'react';
import {Link, useNavigate, useSearchParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import {LoadError} from '@/ide/LoadError';
import {useAsyncData} from '@/lib/loadState';
import {
  providerIsProjectSelectable,
  providerDisplayName,
  providerOptionLabel,
  providerReadinessLabel,
} from '@/ide/providerReadiness';
import {useProviderReadiness} from '@/ide/useProviderReadiness';
import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {fmtCost} from '@/lib/utils';
import {readAgentProjectHandoff} from '@/ide/agentProjectHandoff';

type Dash = Record<string, unknown>;
const numOf = (s: Dash, k: string): number | undefined =>
  typeof s[k] === 'number' ? (s[k] as number) : undefined;

// Real IDE Projects list — health cards (avg score / spend / runs / PRs / status),
// replacing the Signal ProjectsPage. Mirrors its dashboard-derived stats.
// Tech-debt total = sum of every severity bucket. The dashboard payload
// carries no findings count, so it's a separate per-card fetch.
const debtTotal = (r: api.TechDebtReport): number =>
  Object.values(r.issuesBySeverity || {}).reduce((a, b) => a + (b || 0), 0);

export default function IdeProjects() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const agentHandoffId = searchParams.get('agentProjectHandoff');
  const agentHandoff = readAgentProjectHandoff(agentHandoffId);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const [stats, setStats] = useState<Record<string, Dash>>({});
  // Tech-debt count keyed by project id, fetched lazily alongside the dashboard.
  const [debt, setDebt] = useState<Record<string, number>>({});
  const [filter, setFilter] = useState<'mine' | 'archived'>('mine');
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [newProvider, setNewProvider] = useState('');
  const [createError, setCreateError] = useState('');
  const providerReadiness = useProviderReadiness();
  const selectableProviders = useMemo(
    () => providerReadiness.rows.filter(providerIsProjectSelectable),
    [providerReadiness.rows],
  );

  useEffect(() => {
    if (!newProvider && selectableProviders.length === 1) {
      setNewProvider(selectableProviders[0].provider.toUpperCase());
    }
  }, [newProvider, selectableProviders]);

  // D4 — the list itself renders "No {filter} projects.", a factual claim about
  // the user's own data, so a failed load must not be able to produce it.
  const projectsState = useAsyncData(
    () =>
      api.getProjects().then((list) => {
        list.forEach((p) => {
          // Per-card enrichment stays best-effort; see the note at `card()`.
          api
            .getProjectDashboard(p.id)
            .then((d) => setStats((s) => ({...s, [p.id]: d as unknown as Dash})))
            .catch(() => {});
          api
            .getTechDebt(p.id)
            .then((r) => setDebt((m) => ({...m, [p.id]: debtTotal(r)})))
            .catch(() => {});
        });
        return list;
      }),
    [],
  );
  const projects = projectsState.data;

  const filtered = useMemo(
    () => (projects ?? []).filter((p) => (filter === 'archived' ? !!p.archivedAt : !p.archivedAt)),
    [projects, filter],
  );

  async function create() {
    const name = newName.trim();
    if (!name) return;
    if (providerReadiness.supported && !newProvider) {
      setCreateError('Choose an available AI provider for this project.');
      return;
    }
    try {
      setCreateError('');
      const p = await api.createProject({name, aiProvider: newProvider || undefined});
      const createdId = (p as {id?: string}).id ?? '';
      navigate(
        agentHandoff && agentHandoffId
          ? `/projects/${createdId}/agent?agentProjectHandoff=${encodeURIComponent(agentHandoffId)}`
          : `/projects/${createdId}`,
      );
    } catch (error) {
      setCreateError(error instanceof Error ? error.message : 'Project creation failed.');
    }
  }

  function card(p: api.ProjectDto) {
    // D4: the per-project dashboard is a best-effort enrichment call, but its
    // absence used to render as hard zeros ("0 repos · 0 runs · 0 PRs open")
    // and a "New" status badge on a project that may be neither. Track whether
    // it actually arrived so the card can say "not loaded" instead of lying.
    const statsLoaded = stats[p.id] !== undefined;
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
    // RR-1475 — the card previously printed "N repos" derived from the distinct repos found in
    // `recentPrs`, which is capped at the 15 most recent PRs and is unrelated to how many
    // repositories are linked. A project with many linked repos and no recent PRs showed "0 repos".
    // The dashboard DTO carries no linked-repo count, so we drop the fabricated metric rather than
    // assert a wrong one; runs and open-PR counts below remain real.
    const avg = numOf(s, 'recencyAvgScore') ?? numOf(s, 'avgScore');
    const spent = isSuperAdmin ? numOf(s, 'totalCostUsd') : undefined;
    const techDebt = debt[p.id];
    const status = p.archivedAt
      ? {l: 'Archived', k: 'warn' as const}
      : !statsLoaded
        ? {l: 'Status unknown', k: 'warn' as const}
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
          {statsLoaded ? (
            <>
              {totalRuns} run{totalRuns === 1 ? '' : 's'} · {prs} PR{prs === 1 ? '' : 's'} open
            </>
          ) : (
            'Project activity not loaded'
          )}
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
            ...(isSuperAdmin
              ? ([['Model spend', spent != null ? fmtCost(spent) : '—']] as const)
              : []),
            ['Tech debt', techDebt != null ? String(techDebt) : '—'],
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
      {agentHandoff && (
        <div className='ide-card' role='status' style={{marginBottom: 12}}>
          <strong>Create a project for this conversation</strong>
          <p className='ide-sub' style={{margin: '6px 0 10px'}}>
            Your conversation and draft are preserved. After creation, the agent will open with the
            new project linked.
          </p>
          <button className='ide-btn ghost' onClick={() => navigate(agentHandoff.returnPath)}>
            Cancel and return to conversation
          </button>
        </div>
      )}
      <div style={{display: 'flex', alignItems: 'center', gap: 12, marginBottom: 4}}>
        <h1 className='ide-h1' style={{margin: 0}}>
          Projects
        </h1>
        <span style={{flex: 1}} />
        {!creating ? (
          <button
            className='ide-btn'
            onClick={() => {
              setCreating(true);
              setCreateError('');
            }}
          >
            ＋ New project
          </button>
        ) : (
          <span style={{display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap'}}>
            <input
              className='ide-input'
              autoFocus
              placeholder='Project name'
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && create()}
              style={{width: 200}}
            />
            <select
              className='ide-input'
              aria-label='AI provider'
              value={newProvider}
              disabled={providerReadiness.loading || providerReadiness.error}
              onChange={(event) => {
                setNewProvider(event.target.value);
                setCreateError('');
              }}
              style={{width: 190}}
            >
              <option value=''>Choose AI provider</option>
              {providerReadiness.rows.map((row) => (
                <option
                  key={row.provider}
                  value={row.provider.toUpperCase()}
                  disabled={!providerIsProjectSelectable(row)}
                >
                  {providerOptionLabel(providerDisplayName(row.provider), row)}
                </option>
              ))}
            </select>
            <button
              className='ide-btn'
              onClick={create}
              disabled={
                providerReadiness.supported &&
                (providerReadiness.loading || providerReadiness.error || !newProvider)
              }
            >
              Create
            </button>
            <button
              className='ide-btn ghost'
              aria-label='Cancel creating project'
              onClick={() => {
                setCreating(false);
                setCreateError('');
              }}
            >
              ✕
            </button>
            <span className='ide-sub' role={createError ? 'alert' : undefined}>
              {createError ||
                (providerReadiness.error
                  ? 'Provider readiness unavailable; creation is disabled.'
                  : providerReadiness.loading
                    ? 'Checking provider readiness…'
                    : newProvider
                      ? providerReadinessLabel(
                          providerReadiness.byProvider.get(newProvider.toUpperCase()),
                        )
                      : `${selectableProviders.length} provider${selectableProviders.length === 1 ? '' : 's'} available`)}
            </span>
          </span>
        )}
      </div>
      <div className='ide-sub'>{projects ? `${filtered.length} projects` : ' '}</div>

      <div style={{display: 'flex', gap: 4, marginBottom: 14}}>
        {(['mine', 'archived'] as const).map((f) => (
          <button
            key={f}
            className='ide-badge interactive'
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

      {projectsState.failure ? (
        <LoadError failure={projectsState.failure} what='projects' onRetry={projectsState.reload} />
      ) : projects === null ? (
        <IdeLoader label='Loading projects…' />
      ) : filtered.length === 0 ? (
        <div className='ide-empty'>No {filter} projects.</div>
      ) : (
        <div className='ide-grid'>{filtered.map(card)}</div>
      )}
    </div>
  );
}
