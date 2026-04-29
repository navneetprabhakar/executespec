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
    Promise.all([
      getMetrics(30).catch(() => null),
      getProjects().catch(() => [] as ProjectDto[]),
      getRuns({ page: 0, size: 100 }).catch(() => ({ content: [] as RunSummary[] })),
    ]).then(([m, p, r]) => {
      if (cancelled) return;
      setMetrics(m);
      setProjects(p);
      setRuns(r.content ?? []);
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [isOrgAdmin, navigate, user]);

  // Derived stats
  const stats = useMemo(() => {
    if (!metrics || !runs) return null;
    const successRate =
      metrics.totalRuns > 0
        ? Math.round((metrics.completedRuns / metrics.totalRuns) * 100)
        : null;
    const totalSpend = runs.reduce((sum, r) => sum + (r.actualCostUsd ?? 0), 0);
    const needsAttention = runs.filter((r) => r.status === 'PARTIAL' || r.status === 'FAILED').length;
    return { successRate, totalSpend, needsAttention };
  }, [metrics, runs]);

  // Spend per project
  const spendByProject = useMemo(() => {
    if (!projects || !runs) return [];
    const map = new Map<string, { project: ProjectDto; runCount: number; spend: number }>();
    for (const p of projects) map.set(p.id, { project: p, runCount: 0, spend: 0 });
    for (const r of runs) {
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
  }, [projects, runs]);

  const topWins = useMemo(() => {
    if (!runs) return [];
    return runs
      .filter((r) => r.status === 'COMPLETE')
      .sort((a, b) => +new Date(b.completedAt ?? b.startedAt) - +new Date(a.completedAt ?? a.startedAt))
      .slice(0, 5);
  }, [runs]);

  const attention = useMemo(() => {
    if (!runs) return [];
    return runs
      .filter((r) => r.status === 'PARTIAL' || r.status === 'FAILED')
      .sort((a, b) => +new Date(b.completedAt ?? b.startedAt) - +new Date(a.completedAt ?? a.startedAt))
      .slice(0, 3);
  }, [runs]);

  const maxSpend = spendByProject.length ? spendByProject[0].spend : 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, paddingBottom: 32 }}>
      <div>
        <h1 style={{ fontSize: 22, fontWeight: 600, color: 'var(--v3-text)', margin: 0 }}>
          Insights
        </h1>
        <p style={{ fontSize: 12, color: 'var(--v3-text-muted)', margin: '4px 0 0' }}>
          Last 30 days · runs · spend · success rate · what's shipping
        </p>
      </div>

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
            <Tile label="Runs" value={String(metrics.totalRuns)} />
            <Tile
              label="Spend"
              value={stats.totalSpend > 0 ? `$${stats.totalSpend.toFixed(2)}` : '—'}
            />
            <Tile
              label="Success rate"
              value={stats.successRate != null ? `${stats.successRate}%` : '—'}
              tone={stats.successRate != null && stats.successRate >= 80 ? 'ok' : 'warn'}
            />
            <Tile label="Active projects" value={String(projects?.length ?? 0)} />
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
