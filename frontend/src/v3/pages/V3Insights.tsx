import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  getMetrics,
  getProjects,
  getRuns,
  type MetricsResponse,
  type ProjectDto,
  type RunSummary,
} from '@/lib/api';
import { useAuthStore, selectIsOrgAdmin } from '@/stores/useAuthStore';
import Pill from '../components/Pill';

/**
 * R-152 — /insights.
 *
 * Greenfield §4.11: collapses today's /dashboard + /exec +
 * /admin/analytics into one page. ORG_ADMIN+ gated; non-admins are
 * redirected away.
 *
 * Sections:
 *   1. Top tile row: total runs · spend (best-effort) · success rate
 *      · active projects · needs-attention count
 *   2. Spend by project bar chart (best-effort — uses run.actualCostUsd
 *      summed per projectId)
 *   3. Top wins this week (5 most recent COMPLETE runs with PR link)
 *   4. Needs attention (3 most recent FAILED/PARTIAL runs)
 *
 * Reuses existing v2 endpoints: getMetrics, getProjects, getRuns. No
 * new backend.
 */
export default function V3Insights() {
  const navigate = useNavigate();
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);
  const user = useAuthStore((s) => s.user);
  const [metrics, setMetrics] = useState<MetricsResponse | null>(null);
  const [projects, setProjects] = useState<ProjectDto[] | null>(null);
  const [runs, setRuns] = useState<RunSummary[] | null>(null);
  const [loading, setLoading] = useState(true);

  // R-343 (P2 from UX persona review) — date-range picker for Pat
  // (CFO).  30 / 90 / 365 days.  Persisted to localStorage so a
  // returning user sees the same window.  Persisted choice drives
  // both the metrics fetch + the runs filter (runs older than the
  // window are excluded from spend / success-rate / wins).
  const [windowDays, setWindowDays] = useState<30 | 90 | 365>(() => {
    if (typeof window === 'undefined') return 30;
    try {
      const raw = localStorage.getItem('v3-insights-window-days');
      const v = raw ? parseInt(raw, 10) : 30;
      if (v === 90 || v === 365) return v;
      return 30;
    } catch { return 30; }
  });
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try { localStorage.setItem('v3-insights-window-days', String(windowDays)); } catch {}
  }, [windowDays]);
  // R-343 (P1 from UX persona review) — multi-project filter.  Riley
  // (team-lead) wants to see metrics across N specific projects, not
  // org-wide and not single-project.  Empty Set = "all projects".
  // Persisted to localStorage so the filter survives page reloads.
  const [projectFilter, setProjectFilter] = useState<Set<string>>(() => {
    if (typeof window === 'undefined') return new Set();
    try {
      const raw = localStorage.getItem('v3-insights-project-filter');
      if (!raw) return new Set();
      return new Set(JSON.parse(raw));
    } catch {
      return new Set();
    }
  });
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      localStorage.setItem('v3-insights-project-filter', JSON.stringify(Array.from(projectFilter)));
    } catch (_) { /* ignore */ }
  }, [projectFilter]);
  function toggleProject(id: string) {
    setProjectFilter((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  useEffect(() => {
    // Wait for the auth store to hydrate before deciding role-gate.
    // On first render `user` is null even for an authed session
    // because `loadMe` hasn't returned yet.
    if (user === null) return;
    if (!isOrgAdmin) {
      navigate('/home');
      return;
    }
    let cancelled = false;
    setLoading(true);
    // R-343 — refetch when the date-window changes.  90/365-day windows
    // need a bigger run page-size to capture the full set; 100 is
    // enough for 30 days but undersized for 365 on a busy org.  Crude
    // scaling (3× per window-step) avoids a backend pagination loop.
    const pageSize = windowDays >= 365 ? 500 : windowDays >= 90 ? 200 : 100;
    Promise.all([
      getMetrics(windowDays).catch(() => null),
      getProjects().catch(() => [] as ProjectDto[]),
      getRuns({ page: 0, size: pageSize }).catch(() => ({ content: [] as RunSummary[] })),
    ]).then(([m, p, r]) => {
      if (cancelled) return;
      setMetrics(m);
      setProjects(p);
      // Filter runs to the chosen window.  `r.content` may include
      // older rows when the backend page-size is large; we trim them
      // here so the derived stats stay window-bound.
      const cutoff = Date.now() - windowDays * 24 * 60 * 60 * 1000;
      const filtered = (r.content ?? []).filter((row) => {
        const ts = row.startedAt ? +new Date(row.startedAt) : 0;
        return ts >= cutoff;
      });
      setRuns(filtered);
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [isOrgAdmin, navigate, user, windowDays]);

  // R-343 — apply the multi-project filter to the run set BEFORE all
  // derived stats so totals + tiles + spend-by-project + wins +
  // attention all reflect Riley's selection.  Empty filter Set =
  // "all projects" (no filtering).
  const filteredRuns = useMemo(() => {
    if (!runs) return null;
    if (projectFilter.size === 0) return runs;
    return runs.filter((r) => r.projectId && projectFilter.has(r.projectId));
  }, [runs, projectFilter]);

  // Derived stats
  const stats = useMemo(() => {
    if (!metrics || !filteredRuns) return null;
    // When filtering, recompute totals from the filtered run list
    // since `metrics` is org-wide.  Empty filter falls back to the
    // backend metrics object.
    if (projectFilter.size > 0) {
      const total = filteredRuns.length;
      const completed = filteredRuns.filter((r) => r.status === 'COMPLETE').length;
      const successRate = total > 0 ? Math.round((completed / total) * 100) : null;
      const totalSpend = filteredRuns.reduce((sum, r) => sum + (r.actualCostUsd ?? 0), 0);
      const needsAttention = filteredRuns.filter((r) => r.status === 'PARTIAL' || r.status === 'FAILED').length;
      return { successRate, totalSpend, needsAttention, totalRuns: total, completedRuns: completed };
    }
    const successRate =
      metrics.totalRuns > 0
        ? Math.round((metrics.completedRuns / metrics.totalRuns) * 100)
        : null;
    const totalSpend = filteredRuns.reduce((sum, r) => sum + (r.actualCostUsd ?? 0), 0);
    const needsAttention = filteredRuns.filter((r) => r.status === 'PARTIAL' || r.status === 'FAILED').length;
    return { successRate, totalSpend, needsAttention, totalRuns: metrics.totalRuns, completedRuns: metrics.completedRuns };
  }, [metrics, filteredRuns, projectFilter]);

  // Spend per project
  const spendByProject = useMemo(() => {
    if (!projects || !filteredRuns) return [];
    const map = new Map<string, { project: ProjectDto; runCount: number; spend: number }>();
    for (const p of projects) map.set(p.id, { project: p, runCount: 0, spend: 0 });
    for (const r of filteredRuns) {
      if (!r.projectId) continue;
      const e = map.get(r.projectId);
      if (!e) continue;
      e.runCount += 1;
      e.spend += r.actualCostUsd ?? 0;
    }
    return Array.from(map.values())
      .filter((e) => e.runCount > 0)
      .sort((a, b) => b.spend - a.spend)
      .slice(0, 6);
  }, [projects, filteredRuns]);

  const topWins = useMemo(() => {
    if (!filteredRuns) return [];
    return filteredRuns
      .filter((r) => r.status === 'COMPLETE')
      .sort((a, b) => +new Date(b.completedAt ?? b.startedAt) - +new Date(a.completedAt ?? a.startedAt))
      .slice(0, 5);
  }, [filteredRuns]);

  const attention = useMemo(() => {
    if (!filteredRuns) return [];
    return filteredRuns
      .filter((r) => r.status === 'PARTIAL' || r.status === 'FAILED')
      .sort((a, b) => +new Date(b.completedAt ?? b.startedAt) - +new Date(a.completedAt ?? a.startedAt))
      .slice(0, 3);
  }, [filteredRuns]);

  const maxSpend = spendByProject.length ? spendByProject[0].spend : 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, paddingBottom: 32 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 16, justifyContent: 'space-between', flexWrap: 'wrap' }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 600, color: 'var(--v3-text)', margin: 0 }}>
            Insights
          </h1>
          <p style={{ fontSize: 12, color: 'var(--v3-text-muted)', margin: '4px 0 0' }}>
            Last {windowDays} days · runs · spend · success rate · what's shipping
            {projectFilter.size > 0 && (
              <span style={{ color: 'var(--v3-brand)' }}>
                {' '}· filtered to {projectFilter.size} project{projectFilter.size === 1 ? '' : 's'}
              </span>
            )}
          </p>
        </div>
        {/* R-343 — date-range picker (P2).  30 / 90 / 365 days.  The
            picker is a flat 3-up segmented control; selecting refetches
            the metrics + runs.  Choice persists to localStorage so
            Pat's preferred window survives page reloads. */}
        <div style={{ display: 'flex', gap: 0, border: '1px solid var(--v3-border)', borderRadius: 6, overflow: 'hidden', flexShrink: 0 }}>
          {([30, 90, 365] as const).map((d, i) => (
            <button
              key={d}
              type="button"
              onClick={() => setWindowDays(d)}
              aria-pressed={windowDays === d}
              style={{
                padding: '5px 12px',
                fontSize: 11.5,
                fontFamily: 'inherit',
                fontWeight: windowDays === d ? 600 : 500,
                background: windowDays === d ? 'var(--v3-brand)' : 'var(--v3-bg)',
                color: windowDays === d ? 'var(--v3-text-on-brand)' : 'var(--v3-text-muted)',
                border: 'none',
                borderRight: i < 2 ? '1px solid var(--v3-border)' : 'none',
                cursor: 'pointer',
              }}
            >
              {d}d
            </button>
          ))}
        </div>
      </div>

      {/* R-343 — multi-project filter chip rail.  Only renders when the
          user has ≥2 projects (single-project orgs have nothing to
          filter).  Clicking a chip toggles inclusion; the "All
          projects" chip clears the selection.  Filter state is
          persisted to localStorage so reloads keep the picked set. */}
      {projects && projects.length >= 2 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 11, color: 'var(--v3-text-subtle)', fontWeight: 600, letterSpacing: 0.4, textTransform: 'uppercase', marginRight: 4 }}>
            Filter
          </span>
          <button
            type="button"
            onClick={() => setProjectFilter(new Set())}
            aria-pressed={projectFilter.size === 0}
            style={{
              padding: '4px 10px',
              borderRadius: 999,
              fontSize: 12,
              fontFamily: 'inherit',
              cursor: 'pointer',
              fontWeight: projectFilter.size === 0 ? 600 : 500,
              background: projectFilter.size === 0 ? 'var(--v3-brand)' : 'var(--v3-bg)',
              color: projectFilter.size === 0 ? 'var(--v3-text-on-brand)' : 'var(--v3-text-muted)',
              border: `1px solid ${projectFilter.size === 0 ? 'var(--v3-brand)' : 'var(--v3-border)'}`,
            }}
          >
            All projects
          </button>
          {projects.map((p) => {
            const active = projectFilter.has(p.id);
            return (
              <button
                key={p.id}
                type="button"
                onClick={() => toggleProject(p.id)}
                aria-pressed={active}
                style={{
                  padding: '4px 10px',
                  borderRadius: 999,
                  fontSize: 12,
                  fontFamily: 'inherit',
                  cursor: 'pointer',
                  fontWeight: active ? 600 : 500,
                  background: active ? 'var(--v3-brand)' : 'var(--v3-bg)',
                  color: active ? 'var(--v3-text-on-brand)' : 'var(--v3-text)',
                  border: `1px solid ${active ? 'var(--v3-brand)' : 'var(--v3-border)'}`,
                }}
                title={p.description ?? undefined}
              >
                {p.name}
              </button>
            );
          })}
        </div>
      )}

      {loading && (
        <div className="v3-placeholder">
          <p>Loading insights…</p>
        </div>
      )}

      {!loading && metrics && stats && (
        <>
          {/* ── Tiles ─────────────────────────────────────────────────── */}
          <div
            className="v3-insights-tiles"
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
              gap: 10,
            }}
          >
            {/* R-343 — `stats.totalRuns` falls back to `metrics.totalRuns`
                when no project filter is set; when filtered it reflects
                the filtered run count.  Same for active-projects. */}
            <Tile label="Runs" value={String(stats.totalRuns)} />
            <Tile
              label="Spend"
              value={stats.totalSpend > 0 ? `$${stats.totalSpend.toFixed(2)}` : '—'}
            />
            <Tile
              label="Success rate"
              value={stats.successRate != null ? `${stats.successRate}%` : '—'}
              tone={stats.successRate != null && stats.successRate >= 80 ? 'ok' : 'warn'}
            />
            <Tile label="Active projects" value={String(projectFilter.size > 0 ? projectFilter.size : (projects?.length ?? 0))} />
            <Tile
              label="Needs attention"
              value={String(stats.needsAttention)}
              tone={stats.needsAttention > 0 ? 'warn' : 'ok'}
            />
          </div>

          {/* ── Spend by project ──────────────────────────────────────── */}
          {spendByProject.length > 0 && (
            <Section title="Spend by project">
              {spendByProject.map((e) => (
                <div
                  key={e.project.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 12,
                    padding: '6px 0',
                    fontSize: 12,
                  }}
                >
                  <div style={{ flex: '0 0 160px', color: 'var(--v3-text)' }}>
                    {e.project.name}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div
                      style={{
                        height: 8,
                        background: 'var(--v3-bg)',
                        border: '1px solid var(--v3-border)',
                        borderRadius: 4,
                        overflow: 'hidden',
                      }}
                    >
                      <div
                        style={{
                          height: '100%',
                          width: `${maxSpend > 0 ? Math.min(100, (e.spend / maxSpend) * 100) : 0}%`,
                          background: 'var(--v3-brand)',
                        }}
                      />
                    </div>
                  </div>
                  <div style={{ flex: '0 0 100px', textAlign: 'right', color: 'var(--v3-text-muted)' }}>
                    {e.runCount} runs · ${e.spend.toFixed(2)}
                  </div>
                </div>
              ))}
            </Section>
          )}

          {/* ── Top wins ──────────────────────────────────────────────── */}
          {topWins.length > 0 && (
            <Section title="Top wins this period">
              {topWins.map((r) => (
                <RunRow key={r.runId} run={r} tone="ok" />
              ))}
            </Section>
          )}

          {/* ── Needs attention ───────────────────────────────────────── */}
          {attention.length > 0 ? (
            <Section title="Needs attention">
              {attention.map((r) => (
                <RunRow key={r.runId} run={r} tone="warn" />
              ))}
            </Section>
          ) : (
            <Section title="Needs attention">
              <p style={{ margin: 0, fontSize: 12, color: 'var(--v3-success)' }}>
                ✓ All clear in the last 30 days.
              </p>
            </Section>
          )}
        </>
      )}
    </div>
  );
}

// ─── primitives ─────────────────────────────────────────────────────────────

function Tile({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: 'ok' | 'warn';
}) {
  const accent =
    tone === 'ok'
      ? 'var(--v3-success)'
      : tone === 'warn'
      ? 'var(--v3-warning)'
      : 'var(--v3-text)';
  return (
    <div
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 8,
        padding: 12,
      }}
    >
      <div style={{ fontSize: 11, color: 'var(--v3-text-muted)' }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 600, color: accent, marginTop: 2 }}>{value}</div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <h2 style={{ margin: '0 0 8px', fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}>
        {title}
      </h2>
      <div>{children}</div>
    </section>
  );
}

function RunRow({ run, tone }: { run: RunSummary; tone: 'ok' | 'warn' }) {
  const navigate = useNavigate();
  const target = run.projectId ? `/projects/${run.projectId}/runs/${run.runId}` : '#';
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => target !== '#' && navigate(target)}
      onKeyDown={(e) => {
        if ((e.key === 'Enter' || e.key === ' ') && target !== '#') navigate(target);
      }}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '6px 0',
        borderBottom: '1px dashed var(--v3-border)',
        fontSize: 12,
        cursor: target !== '#' ? 'pointer' : 'default',
      }}
    >
      <Pill tone={tone} size="sm">
        {tone === 'ok' ? 'Ready' : 'Needs review'}
      </Pill>
      <span
        style={{
          flex: 1,
          color: 'var(--v3-text)',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {run.runTitle ?? run.userRequest?.slice(0, 60) ?? run.runId.slice(0, 8)}
      </span>
      {run.actualCostUsd != null && (
        <span style={{ color: 'var(--v3-text-muted)', fontSize: 11 }}>
          ${run.actualCostUsd.toFixed(2)}
        </span>
      )}
      <span style={{ color: 'var(--v3-text-subtle)', fontSize: 11 }}>
        {new Date(run.completedAt ?? run.startedAt).toLocaleDateString()}
      </span>
    </div>
  );
}
