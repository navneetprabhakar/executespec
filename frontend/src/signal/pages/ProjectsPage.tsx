import {useEffect, useMemo, useState} from 'react';
import {TopBar, TabBar, IconButton} from '@/signal/components/Chrome';
import {Feed, Card, StatusPill, Button, TabsStrip} from '@/signal/components/Primitives';
import {PromptModal} from '@/signal/components/Modal';
import {Plus, Search} from '@/signal/icons/icons';
import {useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';

type Tab = 'mine' | 'shared' | 'archived';

function initialsFor(name: string): string {
  const parts = (name || '').split(/[\s\-_]+/).filter(Boolean);
  if (parts.length === 0) return 'PR';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function gradientFor(seed: string): string {
  const palettes = [
    'linear-gradient(135deg, var(--signal-lavender), var(--signal-ocean))',
    'linear-gradient(135deg, var(--signal-ocean), var(--signal-mint))',
    'linear-gradient(135deg, var(--signal-y), var(--signal-coral))',
    'linear-gradient(135deg, var(--signal-coral), var(--signal-lavender))',
    'linear-gradient(135deg, var(--signal-mint), var(--signal-y))',
  ];
  let hash = 0;
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  return palettes[hash % palettes.length];
}

export default function ProjectsPage() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const [all, setAll] = useState<api.ProjectDto[] | null>(null);
  // `/api/projects` doesn't include aggregated stats (repoCount, totalRuns,
  // avgScore, etc.) — those live on /api/projects/{id}/dashboard. We fetch
  // them in a second pass keyed by project ID so the cards show real
  // numbers instead of "0 repos · 0 runs · 0 PRs open".
  const [stats, setStats] = useState<Record<string, Record<string, unknown>>>({});
  const [err, setErr] = useState('');
  const [tab, setTab] = useState<Tab>('mine');
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .getProjects()
      .then(async (projects) => {
        if (cancelled) return;
        setAll(projects);
        // Fire dashboard fetches in parallel; merge into stats map as each lands.
        // Also fetch recent runs per project so we can compute a recency-biased
        // average score instead of the simple `avgScore` from the dashboard.
        // The flat average treats a run from 6 months ago the same as one
        // from yesterday — which doesn't represent project health today.
        for (const p of projects.filter((x) => !x.archivedAt)) {
          Promise.all([
            api.getProjectDashboard(p.id).catch(() => null),
            api.getRuns({projectId: p.id, page: 0, size: 10}).catch(() => null),
            // Tech-debt count isn't on the dashboard payload — fetch it
            // separately and fold the severity-bucket total into `merged`.
            api.getTechDebt(p.id).catch(() => null),
          ]).then(([d, r, td]) => {
            if (cancelled) return;
            const merged: Record<string, unknown> = {...((d ?? {}) as Record<string, unknown>)};
            // Compute recency-biased avg: weights decay 1.0 → 0.4 over the
            // last 10 runs. Only scored runs count.
            const runs = (r?.content || []) as Array<{verifiedScore?: number; status?: string}>;
            const scored = runs.filter((rr) => typeof rr.verifiedScore === 'number').slice(0, 10);
            if (scored.length > 0) {
              let weightSum = 0;
              let scoreSum = 0;
              scored.forEach((rr, i) => {
                const w = 1 - 0.06 * i; // 1.0, 0.94, 0.88, … 0.46
                weightSum += w;
                scoreSum += (rr.verifiedScore ?? 0) * w;
              });
              merged.recencyAvgScore = scoreSum / weightSum;
            }
            if (td) {
              merged.techDebt = Object.values(td.issuesBySeverity || {}).reduce(
                (a, b) => a + (b || 0),
                0,
              );
            }
            setStats((prev) => ({...prev, [p.id]: merged}));
          });
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load projects.');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(() => {
    const list = all || [];
    const myOrg = user?.orgId;
    if (tab === 'archived') return list.filter((p) => p.archivedAt);
    if (tab === 'shared') {
      return list.filter(
        (p) =>
          !p.archivedAt &&
          myOrg &&
          (p as unknown as {ownerOrgId?: string}).ownerOrgId &&
          (p as unknown as {ownerOrgId?: string}).ownerOrgId !== myOrg,
      );
    }
    return list.filter((p) => !p.archivedAt);
  }, [all, tab, user?.orgId]);

  // Prompt-modal state for quick create — replaces window.prompt for a11y +
  // adds an optional description field so the user doesn't lose context.
  const [createOpen, setCreateOpen] = useState(false);

  async function performCreate(values: Record<string, string>) {
    const name = (values.name || '').trim();
    if (!name) return;
    setCreating(true);
    setErr('');
    try {
      const created = await api.createProject({
        name,
        description: (values.description || '').trim() || undefined,
      });
      setCreateOpen(false);
      navigate(`/projects/${created.id}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Could not create project.');
    } finally {
      setCreating(false);
    }
  }

  const mineCount = (all || []).filter((p) => !p.archivedAt).length;
  const archivedCount = (all || []).filter((p) => p.archivedAt).length;

  return (
    <>
      <TopBar
        title='Projects'
        sub={`${mineCount} active · ${user?.email ?? ''}`}
        back
        backTo='/home'
        actions={
          <>
            <IconButton to='/search' ariaLabel='Search'>
              <Search size={18} />
            </IconButton>
            <IconButton ariaLabel='New project' onClick={() => setCreateOpen(true)}>
              <Plus size={18} />
            </IconButton>
          </>
        }
      />
      <Feed>
        {err && (
          <div
            style={{
              background: 'var(--signal-coral-soft)',
              color: 'var(--signal-coral-text)',
              border: '1px solid var(--signal-coral)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {err}
          </div>
        )}

        <TabsStrip
          items={[
            {label: `Mine · ${mineCount}`, active: tab === 'mine', onClick: () => setTab('mine')},
            {label: 'Shared with me', active: tab === 'shared', onClick: () => setTab('shared')},
            {
              label: `Archived · ${archivedCount}`,
              active: tab === 'archived',
              onClick: () => setTab('archived'),
            },
          ]}
        />

        {all === null ? (
          <Card>
            <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
              Loading projects…
            </p>
          </Card>
        ) : filtered.length === 0 ? (
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
              {tab === 'archived'
                ? 'No archived projects.'
                : tab === 'shared'
                  ? 'No projects shared with you yet.'
                  : 'No projects yet. Create one to start running specs against real code.'}
            </p>
          </Card>
        ) : (
          <div className='signal-grid cols-2'>
            {filtered.map((p) => {
              // Merge in dashboard stats (loaded async). The dashboard
              // endpoint returns: activeRuns, completedRuns, failedRuns,
              // avgScore, totalCostUsd, totalTokens, recentPrs[],
              // recentTasks[], architectureDocStatus/Version. We derive
              // total-runs and open-PRs from these arrays + counters.
              const s = stats[p.id] || {};
              const num = (k: string): number | undefined => {
                const v = (s as Record<string, unknown>)[k];
                return typeof v === 'number' ? v : undefined;
              };
              const activeRuns = num('activeRuns') ?? 0;
              const completedRuns = num('completedRuns') ?? 0;
              const failedRuns = num('failedRuns') ?? 0;
              const totalRuns = activeRuns + completedRuns + failedRuns;
              const recentPrs = ((s as Record<string, unknown>).recentPrs as unknown[]) || [];
              const openPrs = recentPrs.filter((pr) => {
                const st =
                  (pr as {status?: string; state?: string}).status ||
                  (pr as {state?: string}).state ||
                  '';
                return st.toUpperCase() === 'OPEN';
              }).length;
              // Repo count not in dashboard payload — approximate from recentPrs
              // distinct repos. Falls back to 0 if no PRs.
              const repoCount = new Set(
                recentPrs.map((pr) => (pr as {repoFullName?: string}).repoFullName).filter(Boolean),
              ).size;
              // Prefer the recency-weighted average so the card reflects how
              // the project is performing now, not 6 months ago.
              const avg = num('recencyAvgScore') ?? num('avgScore');
              const spent = num('totalCostUsd');
              const debtCount = num('techDebt');
              const debtKind =
                debtCount == null
                  ? 'good'
                  : debtCount >= 3
                    ? 'fail'
                    : debtCount > 0
                      ? 'warn'
                      : 'good';
              const status = p.archivedAt
                ? undefined
                : openPrs > 0
                  ? 'live'
                  : totalRuns > 0
                    ? 'shipped'
                    : undefined;
              const statusLabel = p.archivedAt
                ? 'Archived'
                : openPrs > 0
                  ? 'Live'
                  : totalRuns > 0
                    ? 'Healthy'
                    : 'New';
              return (
                <Card key={p.id} to={`/projects/${p.id}`}>
                  <div style={{display: 'flex', gap: 14, marginBottom: 14}}>
                    <div
                      style={{
                        width: 44,
                        height: 44,
                        borderRadius: 12,
                        background: gradientFor(p.name || p.id),
                        display: 'grid',
                        placeItems: 'center',
                        color: 'var(--signal-ink)',
                        fontWeight: 700,
                        fontSize: 17,
                        flexShrink: 0,
                      }}
                    >
                      {initialsFor(p.name || 'PR')}
                    </div>
                    <div style={{flex: 1, minWidth: 0}}>
                      <div style={{fontSize: 16, fontWeight: 600, marginBottom: 2}}>{p.name}</div>
                      <div
                        style={{
                          fontSize: 12,
                          color: 'var(--signal-text-dim)',
                          fontFamily: "'JetBrains Mono', monospace",
                        }}
                      >
                        {repoCount} {repoCount === 1 ? 'repo' : 'repos'} · {totalRuns}{' '}
                        {totalRuns === 1 ? 'run' : 'runs'} · {openPrs}{' '}
                        {openPrs === 1 ? 'PR' : 'PRs'} open
                      </div>
                    </div>
                    <StatusPill
                      kind={status as 'live' | 'shipped' | 'needs' | 'failed' | undefined}
                    >
                      {statusLabel}
                    </StatusPill>
                  </div>
                  <div
                    style={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(3, 1fr)',
                      gap: 10,
                      paddingTop: 12,
                      borderTop: '1px solid var(--signal-hairline)',
                      fontSize: 12,
                    }}
                  >
                    <div>
                      <div style={{color: 'var(--signal-text-dim)', fontSize: 11}}>Avg score</div>
                      <div style={{fontWeight: 600, fontSize: 15, marginTop: 2}}>
                        {avg != null ? `${Math.round(avg)}%` : '—'}
                      </div>
                    </div>
                    <div>
                      <div style={{color: 'var(--signal-text-dim)', fontSize: 11}}>Spent</div>
                      <div style={{fontWeight: 600, fontSize: 15, marginTop: 2}}>
                        {spent != null ? `$${spent.toFixed(2)}` : '—'}
                      </div>
                    </div>
                    <div>
                      <div style={{color: 'var(--signal-text-dim)', fontSize: 11}}>Tech debt</div>
                      <div
                        style={{
                          fontWeight: 600,
                          fontSize: 15,
                          marginTop: 2,
                          color:
                            debtKind === 'fail'
                              ? 'var(--signal-coral)'
                              : debtKind === 'warn'
                                ? 'var(--signal-y-text)'
                                : undefined,
                        }}
                      >
                        {debtCount != null ? debtCount : '—'}
                      </div>
                    </div>
                  </div>
                </Card>
              );
            })}
          </div>
        )}

        <Button
          kind='secondary'
          block
          style={{marginTop: 12}}
          onClick={() => setCreateOpen(true)}
          disabled={creating}
        >
          <Plus size={16} />
          {creating ? 'Creating…' : 'New project'}
        </Button>
      </Feed>
      <TabBar />

      <PromptModal
        open={createOpen}
        title='New project'
        body='Projects bundle related repos + runs together so memory, codemaps, and chat persist across runs.'
        fields={[
          {name: 'name', label: 'Name', placeholder: 'e.g. Acme Billing', required: true},
          {
            name: 'description',
            label: 'Description (optional)',
            placeholder: 'What does this codebase do?',
            multiline: true,
          },
        ]}
        submitLabel='Create project'
        busy={creating}
        onClose={() => setCreateOpen(false)}
        onSubmit={performCreate}
      />
    </>
  );
}
