/* IDE-native discovery surfaces — ported 1:1 from the Signal originals in
   `signal/pages/ExtraPages.tsx` (InsightsPage / LibraryPage / LibraryDetailPage /
   MigrationsPage / MigrationDetailPage / SpecTemplatesPage). Same data, same
   `@/lib/api` calls, same actions — re-rendered in the VS Code-style IDE visual
   language (`.ide-*` tokens, IdeKit loaders/badges, the signal Modal/Markdown
   primitives that bridge onto the `--ide-*` palette). */

import {useEffect, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import {repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';
import {ConfirmModal, PromptModal, Modal} from '@/signal/components/Modal';

// Inline error banner — red-bordered ide-card. Mirrors the Banner in IdeProjectMore.
function ErrBanner({msg}: {msg?: string}) {
  if (!msg) return null;
  return (
    <div
      className='ide-card'
      style={{
        marginBottom: 12,
        fontSize: 13,
        color: 'var(--ide-red)',
        borderColor: 'var(--ide-red)',
      }}
    >
      {msg}
    </div>
  );
}

// Coloured info / note card (the "How it works" / "Heads up" callouts).
function NoteCard({
  label,
  children,
  tone = 'blue',
}: {
  label?: string;
  children: React.ReactNode;
  tone?: 'blue' | 'yellow' | 'red';
}) {
  const color =
    tone === 'red' ? 'var(--ide-red)' : tone === 'yellow' ? 'var(--ide-yellow)' : 'var(--ide-blue)';
  return (
    <div className='ide-card' style={{marginBottom: 12, borderColor: color}}>
      {label && (
        <div
          style={{
            fontSize: 11,
            fontWeight: 700,
            color,
            letterSpacing: '0.14em',
            textTransform: 'uppercase',
            marginBottom: 6,
          }}
        >
          {label}
        </div>
      )}
      <div style={{fontSize: 13.5, lineHeight: 1.5}}>{children}</div>
    </div>
  );
}

// Uppercase section label between cards (Signal's SectionHeader).
function SectionLabel({children}: {children: React.ReactNode}) {
  return (
    <div className='ide-h2' style={{marginTop: 18}}>
      {children}
    </div>
  );
}

// Key/value rows inside an ide-card (Signal's KvList).
function KvRows({pairs}: {pairs: Array<{k: React.ReactNode; v: React.ReactNode}>}) {
  return (
    <>
      {pairs.map((p, i) => (
        <div
          key={i}
          className='ide-kv'
          style={i === pairs.length - 1 ? {borderBottom: 'none'} : undefined}
        >
          <span>{p.k}</span>
          <span style={{color: 'var(--ide-fg-bright)', textAlign: 'right'}}>{p.v}</span>
        </div>
      ))}
    </>
  );
}

// Selectable pill (Signal's <Chip kind='active'>). No .ide-chip class exists, so
// it's a small inline-styled badge that flips to the accent colour when active.
function Pill({
  active,
  onClick,
  children,
}: {
  active?: boolean;
  onClick?: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type='button'
      onClick={onClick}
      className='ide-mono'
      style={{
        fontSize: 11.5,
        padding: '4px 10px',
        borderRadius: 999,
        border: `1px solid ${active ? 'var(--ide-accent)' : 'var(--ide-border)'}`,
        background: active
          ? 'color-mix(in srgb, var(--ide-accent) 18%, transparent)'
          : 'transparent',
        color: active ? 'var(--ide-accent)' : 'var(--ide-fg)',
        cursor: onClick ? 'pointer' : 'default',
      }}
    >
      {children}
    </button>
  );
}

// Non-interactive tag chip (Signal's plain <Chip>).
function Tag({children}: {children: React.ReactNode}) {
  return (
    <span
      className='ide-mono'
      style={{
        fontSize: 11,
        padding: '3px 8px',
        borderRadius: 999,
        border: '1px solid var(--ide-border)',
        color: 'var(--ide-fg-muted)',
      }}
    >
      {children}
    </span>
  );
}

// shared formatting helpers (ported verbatim)

function splitTags(tags: string | null | undefined): string[] {
  if (!tags) return [];
  return tags
    .split(/[,|]/)
    .map((t) => t.trim())
    .filter(Boolean)
    .slice(0, 4);
}

function creditsLabel(
  low: string | null | undefined,
  high: string | null | undefined,
): string | null {
  const l = low != null && low !== '' ? Number(low) : null;
  const h = high != null && high !== '' ? Number(high) : null;
  if (l == null && h == null) return null;
  if (l != null && h != null && l !== h) return `${l}–${h} credits`;
  const n = l ?? h;
  return `${n} credit${n === 1 ? '' : 's'}`;
}

// shared project + repo picker (ported from useProjectRepoPicker)
// Loads active projects on first open, auto-selects the first, reloads its
// linked repos on project change, pre-selects all repos. Uses the canonical
// repoIdFor / repoDisplayName helpers (repo UUID + name, not link UUID / SHA).
function useProjectRepoPicker(open: boolean) {
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [repos, setRepos] = useState<Record<string, unknown>[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [selectedRepoIds, setSelectedRepoIds] = useState<string[]>([]);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    api
      .getProjects()
      .then((p) => {
        if (cancelled) return;
        const active = p.filter((x) => !x.archivedAt && !x.deletedAt);
        setProjects(active);
        setProjectId((cur) => cur ?? active[0]?.id ?? null);
      })
      .catch(() => {
        /* swallow — empty-state hint covers the no-projects case */
      });
    return () => {
      cancelled = true;
    };
  }, [open]);

  useEffect(() => {
    if (!projectId) {
      setRepos([]);
      return;
    }
    let cancelled = false;
    setRepos([]);
    setSelectedRepoIds([]);
    api
      .getProjectRepos(projectId)
      .then((r) => {
        if (cancelled) return;
        const list = r as unknown as Record<string, unknown>[];
        setRepos(list);
        setSelectedRepoIds(list.map((x) => repoIdFor(x)).filter(Boolean));
      })
      .catch(() => {
        if (!cancelled) setRepos([]);
      });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  function toggleRepo(repoId: string) {
    setSelectedRepoIds((prev) =>
      prev.includes(repoId) ? prev.filter((x) => x !== repoId) : [...prev, repoId],
    );
  }

  return {projects, repos, projectId, setProjectId, selectedRepoIds, toggleRepo};
}

function ProjectRepoPickerBody({
  projects,
  repos,
  projectId,
  setProjectId,
  selectedRepoIds,
  toggleRepo,
  emptyHint,
}: {
  projects: api.ProjectDto[];
  repos: Record<string, unknown>[];
  projectId: string | null;
  setProjectId: (id: string) => void;
  selectedRepoIds: string[];
  toggleRepo: (repoId: string) => void;
  emptyHint: string;
}) {
  if (projects.length === 0) {
    return (
      <p style={{fontSize: 13.5, color: 'var(--ide-fg-muted)', margin: 0, lineHeight: 1.5}}>
        {emptyHint}{' '}
        <Link to='/projects' style={{color: 'var(--ide-accent)'}}>
          Create a project →
        </Link>
      </p>
    );
  }
  return (
    <>
      <div
        style={{
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: '0.14em',
          textTransform: 'uppercase',
          color: 'var(--ide-fg-muted)',
          marginBottom: 6,
        }}
      >
        Project
      </div>
      <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 14}}>
        {projects.map((p) => (
          <Pill key={p.id} active={p.id === projectId} onClick={() => setProjectId(p.id)}>
            {p.name}
          </Pill>
        ))}
      </div>

      <div
        style={{
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: '0.14em',
          textTransform: 'uppercase',
          color: 'var(--ide-fg-muted)',
          marginBottom: 6,
        }}
      >
        Repos
      </div>
      {repos.length === 0 ? (
        <p style={{fontSize: 13, color: 'var(--ide-fg-muted)', margin: 0}}>
          No repos linked to this project.
        </p>
      ) : (
        <>
          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
            {repos.map((r) => {
              const rId = repoIdFor(r);
              const selected = selectedRepoIds.includes(rId);
              return (
                <Pill key={rId} active={selected} onClick={() => toggleRepo(rId)}>
                  {selected ? '✓ ' : ''}
                  {repoDisplayName(r)}
                </Pill>
              );
            })}
          </div>
          <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 8}}>
            {selectedRepoIds.length} {selectedRepoIds.length === 1 ? 'repo' : 'repos'} selected.
          </div>
        </>
      )}
    </>
  );
}

// Insights (analytics)
// ← Signal InsightsPage. Route /insights. api: getMetrics, getModelComparison, getUserRunsSummary.
export function IdeInsights() {
  const [days, setDays] = useState(30);
  const [metrics, setMetrics] = useState<api.MetricsResponse | null>(null);
  const [byProvider, setByProvider] = useState<api.ModelComparisonData | null>(null);
  const [summary, setSummary] = useState<api.UserRunsSummaryData | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    Promise.allSettled([
      api.getMetrics(days),
      api.getModelComparison(days),
      api.getUserRunsSummary(days),
    ]).then(([m, mc, s]) => {
      if (m.status === 'fulfilled') setMetrics(m.value);
      if (mc.status === 'fulfilled') setByProvider(mc.value);
      if (s.status === 'fulfilled') setSummary(s.value);
      if (m.status === 'rejected' && mc.status === 'rejected') setErr('Failed to load metrics.');
    });
  }, [days]);

  const mAny = (metrics || {}) as unknown as {
    totalRuns?: number;
    avgVerifiedScore?: number;
    avgDurationMs?: number;
    totalSpend?: number;
    shipped?: number;
    needs?: number;
    failed?: number;
  };
  const total = mAny.totalRuns ?? 0;
  const shipped = mAny.shipped ?? 0;
  const needs = mAny.needs ?? 0;
  const failed = mAny.failed ?? 0;
  // Distinguish "metrics not loaded yet" (show —) from a genuine 0 (show 0).
  const metricsLoaded = metrics != null;
  const avgScore =
    mAny.avgVerifiedScore != null
      ? `${mAny.avgVerifiedScore.toFixed(1)}%`
      : metricsLoaded
        ? '0%'
        : '—';
  const avgDuration = (() => {
    if (mAny.avgDurationMs != null) {
      const m = Math.floor(mAny.avgDurationMs / 60000);
      const s = Math.floor((mAny.avgDurationMs % 60000) / 1000);
      return `${m}m ${s}s`;
    }
    return metricsLoaded ? '0m 0s' : '—';
  })();
  const spend =
    mAny.totalSpend != null ? `$${mAny.totalSpend.toFixed(2)}` : metricsLoaded ? '$0.00' : '—';
  const mcAny = (byProvider || {}) as unknown as {
    byProvider?: Array<{provider?: string; spend?: number; cacheHitRate?: number}>;
  };
  const providers = mcAny.byProvider || [];
  const topUser = (summary?.users || [])[0] as unknown as
    | {
        firstName?: string;
        lastName?: string;
        email?: string;
        runCount?: number;
        successRate?: number;
      }
    | undefined;

  const RANGES: Array<{label: string; n: number}> = [
    {label: '30d', n: 30},
    {label: '90d', n: 90},
    {label: 'YTD', n: 365},
  ];
  const cards = [
    {label: 'Avg score', value: avgScore},
    {label: 'Avg run time', value: avgDuration},
    {label: 'Total spend', value: spend},
    {
      label: 'Runs completed',
      value: (
        <>
          {shipped} <span style={{color: 'var(--ide-fg-muted)', fontSize: 13}}>of {total}</span>
        </>
      ),
    },
  ];

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Insights</div>
      <div className='ide-sub'>Last {days} days · all projects</div>

      <div className='ide-rtabs' style={{marginBottom: 12}}>
        {RANGES.map((r) => (
          <button
            key={r.n}
            type='button'
            className={`ide-rtab${days === r.n ? ' active' : ''}`}
            onClick={() => setDays(r.n)}
          >
            {r.label}
          </button>
        ))}
      </div>

      <ErrBanner msg={err} />

      <div className='ide-grid' style={{marginBottom: 4}}>
        {cards.map((c) => (
          <div key={c.label} className='ide-card'>
            <div
              style={{
                fontSize: 10,
                fontWeight: 700,
                color: 'var(--ide-fg-muted)',
                letterSpacing: '0.1em',
                textTransform: 'uppercase',
                marginBottom: 6,
              }}
            >
              {c.label}
            </div>
            <div style={{fontSize: 18, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
              {c.value}
            </div>
          </div>
        ))}
      </div>

      <SectionLabel>Cost by provider</SectionLabel>
      <div className='ide-card'>
        {providers.length === 0 ? (
          <p style={{fontSize: 13, color: 'var(--ide-fg-muted)', margin: 0}}>
            No provider data yet for this range.
          </p>
        ) : (
          <KvRows
            pairs={providers.map((p) => ({
              k: p.provider ?? '—',
              v: `${p.spend != null ? `$${p.spend.toFixed(2)}` : '—'}${p.cacheHitRate != null ? ` (${Math.round(p.cacheHitRate * 100)}% cache)` : ''}`,
            }))}
          />
        )}
      </div>

      <SectionLabel>Run outcomes · {days}d</SectionLabel>
      <div className='ide-card'>
        <div
          style={{
            display: 'flex',
            gap: 4,
            height: 18,
            borderRadius: 999,
            overflow: 'hidden',
            marginBottom: 14,
          }}
        >
          <div style={{flex: shipped, background: 'var(--ide-green)'}} />
          <div style={{flex: needs, background: 'var(--ide-yellow)'}} />
          <div style={{flex: failed, background: 'var(--ide-red)'}} />
        </div>
        <KvRows
          pairs={[
            {
              k: <span style={{color: 'var(--ide-green)'}}>● Shipped</span>,
              v: `${shipped} (${total > 0 ? Math.round((shipped / total) * 100) : 0}%)`,
            },
            {
              k: <span style={{color: 'var(--ide-yellow)'}}>● Needs review</span>,
              v: `${needs} (${total > 0 ? Math.round((needs / total) * 100) : 0}%)`,
            },
            {
              k: <span style={{color: 'var(--ide-red)'}}>● Failed / cancelled</span>,
              v: `${failed} (${total > 0 ? Math.round((failed / total) * 100) : 0}%)`,
            },
          ]}
        />
      </div>

      {topUser && (
        <>
          <SectionLabel>Most active user</SectionLabel>
          <div className='ide-card'>
            <div style={{fontSize: 13.5, fontWeight: 500, color: 'var(--ide-fg-bright)'}}>
              {topUser.email ||
                `${topUser.firstName ?? ''} ${topUser.lastName ?? ''}`.trim() ||
                '—'}
            </div>
            <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 4}}>
              {topUser.runCount ?? 0} runs
              {topUser.successRate != null
                ? ` · ${Math.round(topUser.successRate * 100)}% success`
                : ''}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

// Library (readymade solutions)
// ← Signal LibraryPage. Route /library. api: getLibraryFacets, listLibrary.
export function IdeLibrary() {
  const [items, setItems] = useState<api.ReadymadeListItem[] | null>(null);
  const [facets, setFacets] = useState<api.LibraryFacets | null>(null);
  const [category, setCategory] = useState<string | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    let cancelled = false;
    api
      .getLibraryFacets()
      .then((f) => {
        if (!cancelled) setFacets(f);
      })
      .catch(() => {
        /* tabs are best-effort; the list below is the source of truth */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    setItems(null);
    setErr('');
    api
      .listLibrary(category ? {category} : {})
      .then((r) => {
        if (!cancelled) setItems(r);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setErr(e instanceof Error ? e.message : 'Failed to load the library.');
        setItems([]);
      });
    return () => {
      cancelled = true;
    };
  }, [category]);

  const categories = Object.entries(facets?.categories ?? {}).sort((a, b) => b[1] - a[1]);
  const totalCount = categories.reduce((sum, [, n]) => sum + n, 0);

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Library</div>
      <div className='ide-sub'>
        {items
          ? `${items.length} readymade solution${items.length === 1 ? '' : 's'}`
          : 'Readymade solutions'}
      </div>

      <NoteCard label='How it works' tone='yellow'>
        Pick a solution, edit the spec to match your stack, approve the plan. Average ~5 minutes
        from click to PR.
      </NoteCard>

      {categories.length > 0 && (
        <div className='ide-rtabs' style={{marginBottom: 12, flexWrap: 'wrap'}}>
          <button
            type='button'
            className={`ide-rtab${category === null ? ' active' : ''}`}
            onClick={() => setCategory(null)}
          >
            All{totalCount ? ` · ${totalCount}` : ''}
          </button>
          {categories.map(([cat, n]) => (
            <button
              key={cat}
              type='button'
              className={`ide-rtab${category === cat ? ' active' : ''}`}
              onClick={() => setCategory(cat)}
            >
              {cat} · {n}
            </button>
          ))}
        </div>
      )}

      <ErrBanner msg={err} />

      {items === null ? (
        <IdeLoader label='Loading library…' />
      ) : items.length === 0 ? (
        <div className='ide-empty'>
          No solutions{category ? ` in “${category}”` : ' available'} yet.
        </div>
      ) : (
        <div className='ide-grid'>
          {items.map((item) => {
            const s = item.solution;
            const tags = splitTags(s.tags);
            const credits = creditsLabel(s.estCreditsLow, s.estCreditsHigh);
            return (
              <Link
                key={s.slug}
                to={`/library/${s.slug}`}
                className='ide-card'
                style={{textDecoration: 'none', color: 'var(--ide-fg)', display: 'block'}}
              >
                <div style={{display: 'flex', gap: 12, marginBottom: 10}}>
                  <div
                    style={{
                      width: 38,
                      height: 38,
                      borderRadius: 8,
                      background: 'color-mix(in srgb, var(--ide-accent) 18%, transparent)',
                      display: 'grid',
                      placeItems: 'center',
                      flexShrink: 0,
                      fontSize: 18,
                    }}
                  >
                    ⚡
                  </div>
                  <div style={{flex: 1, minWidth: 0}}>
                    <div
                      style={{
                        fontSize: 14.5,
                        fontWeight: 600,
                        color: 'var(--ide-fg-bright)',
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        marginBottom: 3,
                      }}
                    >
                      {s.name}
                      {item.locked && <IdeBadge kind='warn'>{item.unlockTier || s.tier}</IdeBadge>}
                    </div>
                    <div style={{fontSize: 13, color: 'var(--ide-fg-muted)', lineHeight: 1.4}}>
                      {s.tagline || s.description || '—'}
                    </div>
                  </div>
                </div>
                {tags.length > 0 && (
                  <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10}}>
                    {tags.map((t) => (
                      <Tag key={t}>{t}</Tag>
                    ))}
                  </div>
                )}
                <div
                  className='ide-mono'
                  style={{
                    display: 'flex',
                    gap: 8,
                    alignItems: 'center',
                    fontSize: 11,
                    color: 'var(--ide-fg-muted)',
                  }}
                >
                  <span>{s.category}</span>
                  {credits && (
                    <>
                      <span>·</span>
                      <span>{credits}</span>
                    </>
                  )}
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}

// Library detail — ← Signal LibraryDetailPage. Route /library/:slug.
// api: getLibraryDetail, favouriteLibrarySolution, unfavouriteLibrarySolution,
// resolveLibraryVariant, instantiateLibrarySolution.
export function IdeLibraryDetail() {
  const {slug} = useParams<{slug: string}>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<api.ReadymadeDetail | null>(null);
  const [err, setErr] = useState('');
  const [spec, setSpec] = useState('');
  const [variantId, setVariantId] = useState<string | null>(null);
  const [fav, setFav] = useState(false);
  const [favBusy, setFavBusy] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);

  useEffect(() => {
    if (!slug) return;
    let cancelled = false;
    setDetail(null);
    setErr('');
    api
      .getLibraryDetail(slug)
      .then((d) => {
        if (cancelled) return;
        setDetail(d);
        const v = d.variants[0];
        if (v) {
          setVariantId(v.id);
          setSpec(v.specTemplate || '');
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load this solution.');
      });
    return () => {
      cancelled = true;
    };
  }, [slug]);

  async function toggleFav() {
    if (!slug || favBusy) return;
    setFavBusy(true);
    const next = !fav;
    setFav(next); // optimistic
    try {
      if (next) await api.favouriteLibrarySolution(slug);
      else await api.unfavouriteLibrarySolution(slug);
    } catch (e: unknown) {
      setFav(!next); // revert
      setErr(e instanceof Error ? e.message : 'Could not update favourite.');
    } finally {
      setFavBusy(false);
    }
  }

  // "Edit in composer" — hand the resolved spec to the composer (no project needed).
  function editInComposer() {
    if (spec) sessionStorage.setItem('signal:pending-spec', spec);
    navigate('/compose');
  }

  const s = detail?.solution;
  const credits = s ? creditsLabel(s.estCreditsLow, s.estCreditsHigh) : null;
  const variant = detail?.variants.find((v) => v.id === variantId) || detail?.variants[0] || null;

  return (
    <div className='ide-pane'>
      <div style={{display: 'flex', alignItems: 'flex-start', gap: 12}}>
        <div style={{flex: 1, minWidth: 0}}>
          <Link to='/library' style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
            ‹ Library
          </Link>
          <div className='ide-h1' style={{marginTop: 4}}>
            {s?.name || 'Library solution'}
          </div>
          <div className='ide-sub'>
            {s
              ? [s.category, credits].filter(Boolean).join(' · ') || 'Library solution'
              : 'Library solution'}
          </div>
        </div>
        {detail && (
          <button
            type='button'
            className='ide-btn ghost'
            aria-label={fav ? 'Unfavourite' : 'Favourite'}
            onClick={toggleFav}
            disabled={favBusy}
            style={{
              color: fav ? 'var(--ide-yellow)' : undefined,
              fontSize: 16,
              padding: '6px 12px',
            }}
          >
            {fav ? '★' : '☆'}
          </button>
        )}
      </div>

      <ErrBanner msg={err} />

      {detail === null && !err ? (
        <IdeLoader label='Loading solution…' />
      ) : !detail ? null : (
        <>
          <div className='ide-card'>
            <div
              style={{
                fontSize: 11,
                fontWeight: 700,
                color: 'var(--ide-fg-muted)',
                letterSpacing: '0.14em',
                textTransform: 'uppercase',
                marginBottom: 8,
              }}
            >
              What you'll get
            </div>
            <div style={{fontSize: 14, color: 'var(--ide-fg)', lineHeight: 1.55}}>
              {s?.description || s?.tagline || 'A readymade, spec-driven solution.'}
            </div>
            {splitTags(s?.tags).length > 0 && (
              <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 12}}>
                {splitTags(s?.tags).map((t) => (
                  <Tag key={t}>{t}</Tag>
                ))}
              </div>
            )}
          </div>

          {detail.locked && (
            <NoteCard tone='red'>
              This solution requires the {detail.unlockTier || s?.tier} plan.{' '}
              <Link to='/plans' style={{color: 'var(--ide-red)', fontWeight: 600}}>
                Upgrade →
              </Link>
            </NoteCard>
          )}

          {detail.variants.length > 1 && (
            <>
              <SectionLabel>Pick a stack</SectionLabel>
              <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
                {detail.variants.map((v) => (
                  <Pill
                    key={v.id}
                    active={v.id === variantId}
                    onClick={() => {
                      setVariantId(v.id);
                      setSpec(v.specTemplate || '');
                    }}
                  >
                    {[v.language, v.framework].filter(Boolean).join(' · ') || 'variant'}
                  </Pill>
                ))}
              </div>
            </>
          )}

          <SectionLabel>Tweak the spec before running</SectionLabel>
          <div className='ide-card'>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'baseline',
                marginBottom: 8,
              }}
            >
              <span style={{fontSize: 13, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
                Pre-filled spec
              </span>
              {credits && (
                <span className='ide-mono' style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                  est {credits}
                </span>
              )}
            </div>
            <textarea
              className='ide-textarea'
              rows={8}
              value={spec}
              onChange={(e) => setSpec(e.target.value)}
              placeholder='The solution spec…'
              style={{marginBottom: 12}}
            />
            <div style={{display: 'flex', gap: 8}}>
              <button
                className='ide-btn'
                onClick={() => setPickerOpen(true)}
                disabled={detail.locked}
              >
                Use this →
              </button>
              <button className='ide-btn ghost' onClick={editInComposer}>
                Edit in composer
              </button>
            </div>
          </div>

          {detail.slots.length > 0 && (
            <>
              <SectionLabel>What it asks for</SectionLabel>
              <div className='ide-card'>
                <KvRows
                  pairs={detail.slots.map((slot) => ({
                    k: slot.displayName + (slot.required ? ' *' : ''),
                    v: slot.description || slot.type,
                  }))}
                />
              </div>
            </>
          )}

          <SectionLabel>Details</SectionLabel>
          <div className='ide-card'>
            <KvRows
              pairs={[
                {k: 'Category', v: s?.category || '—'},
                {k: 'Kind', v: s?.kind || '—'},
                {k: 'Source', v: s?.source === 'FIRST_PARTY' ? 'First-party' : 'Community'},
                {k: 'Plan', v: s?.tier || '—'},
                ...(s?.samplePrUrl
                  ? [
                      {
                        k: 'Sample PR',
                        v: (
                          <a
                            href={s.samplePrUrl}
                            target='_blank'
                            rel='noreferrer'
                            style={{color: 'var(--ide-accent)'}}
                          >
                            View →
                          </a>
                        ),
                      },
                    ]
                  : []),
              ]}
            />
          </div>
        </>
      )}

      {detail && (
        <LibraryInstantiateModal
          open={pickerOpen}
          slug={slug || ''}
          variant={variant}
          onClose={() => setPickerOpen(false)}
          onLaunched={(runId) => {
            setPickerOpen(false);
            navigate(`/plan/${runId}`);
          }}
        />
      )}
    </div>
  );
}

// Project + repo picker for instantiating a library solution into a real run.
// When exactly one repo is selected we resolveLibraryVariant() to detect the
// best-fit variant for that repo's stack.
function LibraryInstantiateModal({
  open,
  slug,
  variant,
  onClose,
  onLaunched,
}: {
  open: boolean;
  slug: string;
  variant: api.ReadymadeVariant | null;
  onClose: () => void;
  onLaunched: (runId: string) => void;
}) {
  const {projects, repos, projectId, setProjectId, selectedRepoIds, toggleRepo} =
    useProjectRepoPicker(open);
  const [resolved, setResolved] = useState<api.VariantResolutionResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  useEffect(() => {
    setResolved(null);
    if (!open || !slug || !projectId || selectedRepoIds.length !== 1) return;
    let cancelled = false;
    api
      .resolveLibraryVariant(slug, projectId, selectedRepoIds[0])
      .then((r) => {
        if (!cancelled) setResolved(r);
      })
      .catch(() => {
        /* resolution is advisory — instantiate still works with the chosen variant */
      });
    return () => {
      cancelled = true;
    };
  }, [open, slug, projectId, selectedRepoIds]);

  async function launch() {
    if (!projectId || selectedRepoIds.length === 0) return;
    setBusy(true);
    setErr('');
    try {
      const chosenVariantId = resolved?.bestMatch?.id || variant?.id;
      const resp = await api.instantiateLibrarySolution(slug, {
        projectId,
        repoIds: selectedRepoIds,
        ...(chosenVariantId ? {variantId: chosenVariantId} : {}),
      });
      onLaunched(resp.executionRunId);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Could not start the run.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} title='Run this solution' onClose={onClose} size='md'>
      <ProjectRepoPickerBody
        projects={projects}
        repos={repos}
        projectId={projectId}
        setProjectId={setProjectId}
        selectedRepoIds={selectedRepoIds}
        toggleRepo={toggleRepo}
        emptyHint='Library solutions run against a project. Create one to continue.'
      />

      {resolved && (
        <div style={{marginTop: 12, fontSize: 12.5, color: 'var(--ide-fg-muted)'}}>
          {resolved.bestMatch
            ? `Detected ${[resolved.detectedLanguage, resolved.detectedFramework].filter(Boolean).join(' / ') || 'a matching stack'} — using the best-fit variant.`
            : resolved.reason === 'STACK_NOT_DETECTED'
              ? "Couldn't detect the repo's stack — running the default variant."
              : resolved.reason === 'NO_VARIANT_FOR_STACK'
                ? 'No variant matches this stack — running the default variant.'
                : 'Running the default variant.'}
        </div>
      )}

      {err && <div style={{marginTop: 12, fontSize: 13, color: 'var(--ide-red)'}}>{err}</div>}

      <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16}}>
        <button className='ide-btn ghost' onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button
          className='ide-btn'
          disabled={busy || !projectId || selectedRepoIds.length === 0}
          onClick={() => void launch()}
        >
          {busy ? '…' : 'Run →'}
        </button>
      </div>
    </Modal>
  );
}

// ← Signal MigrationsPage. Route /migrations. api: listMigrations.
export function IdeMigrations() {
  const [items, setItems] = useState<api.MigrationListItem[] | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    let cancelled = false;
    api
      .listMigrations()
      .then((r) => {
        if (!cancelled) setItems(r);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setErr(e instanceof Error ? e.message : 'Failed to load migrations.');
        setItems([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Migrations</div>
      <div className='ide-sub'>
        {items
          ? `${items.length} modernization recipe${items.length === 1 ? '' : 's'}`
          : 'Modernization recipes'}
      </div>

      <NoteCard label="What's a migration?" tone='blue'>
        A multi-file refactor with parity tests. We run it incrementally — file by file, route by
        route — so your CI stays green throughout.
      </NoteCard>

      <ErrBanner msg={err} />

      {items === null ? (
        <IdeLoader label='Loading migrations…' />
      ) : items.length === 0 ? (
        <div className='ide-empty'>No migration recipes available yet.</div>
      ) : (
        <div className='ide-grid'>
          {items.map((item) => {
            const m = item.recipe;
            const credits = creditsLabel(m.estCreditsLow, m.estCreditsHigh);
            const fileMeta =
              m.estFilesLow != null || m.estFilesHigh != null
                ? `~${m.estFilesLow ?? m.estFilesHigh}${m.estFilesHigh != null && m.estFilesHigh !== m.estFilesLow ? `–${m.estFilesHigh}` : ''} files`
                : null;
            return (
              <Link
                key={m.slug}
                to={`/migrations/${m.slug}`}
                className='ide-card'
                style={{textDecoration: 'none', color: 'var(--ide-fg)', display: 'block'}}
              >
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'baseline',
                    gap: 8,
                    marginBottom: 6,
                  }}
                >
                  <div style={{fontSize: 14.5, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
                    {m.name}
                  </div>
                  {item.locked ? (
                    <IdeBadge kind='warn'>{item.unlockTier || m.tier}</IdeBadge>
                  ) : (
                    <IdeBadge kind='ok'>{m.category}</IdeBadge>
                  )}
                </div>
                <div style={{fontSize: 13, color: 'var(--ide-fg-muted)', marginBottom: 8}}>
                  {m.tagline || m.description || `${m.sourceStack} → ${m.targetStack}`}
                </div>
                <div
                  className='ide-mono'
                  style={{
                    display: 'flex',
                    gap: 8,
                    flexWrap: 'wrap',
                    fontSize: 11,
                    color: 'var(--ide-fg-muted)',
                  }}
                >
                  <span>
                    {m.sourceStack} → {m.targetStack}
                  </span>
                  {fileMeta && (
                    <>
                      <span>·</span>
                      <span>{fileMeta}</span>
                    </>
                  )}
                  {credits && (
                    <>
                      <span>·</span>
                      <span>{credits}</span>
                    </>
                  )}
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}

// Migration detail — ← Signal MigrationDetailPage. Route /migrations/:slug.
// api: getMigrationDetail, migrationDryRun, migrationApply.
export function IdeMigrationDetail() {
  const {slug} = useParams<{slug: string}>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<api.MigrationDetail | null>(null);
  const [err, setErr] = useState('');
  const [dryRunOpen, setDryRunOpen] = useState(false);
  const [applyOpen, setApplyOpen] = useState(false);

  useEffect(() => {
    if (!slug) return;
    let cancelled = false;
    setDetail(null);
    setErr('');
    api
      .getMigrationDetail(slug)
      .then((d) => {
        if (!cancelled) setDetail(d);
      })
      .catch((e: unknown) => {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load this migration.');
      });
    return () => {
      cancelled = true;
    };
  }, [slug]);

  const r = detail?.recipe;
  const credits = r ? creditsLabel(r.estCreditsLow, r.estCreditsHigh) : null;

  return (
    <div className='ide-pane'>
      <Link to='/migrations' style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
        ‹ Migrations
      </Link>
      <div className='ide-h1' style={{marginTop: 4}}>
        {r?.name || 'Migration'}
      </div>
      <div className='ide-sub'>
        {r ? `${r.sourceStack} → ${r.targetStack}` : 'Migration · incremental'}
      </div>

      <ErrBanner msg={err} />

      {detail === null && !err ? (
        <IdeLoader label='Loading migration…' />
      ) : !detail ? null : (
        <>
          <div className='ide-card'>
            <div
              style={{
                fontSize: 11,
                fontWeight: 700,
                color: 'var(--ide-fg-muted)',
                letterSpacing: '0.14em',
                textTransform: 'uppercase',
                marginBottom: 8,
              }}
            >
              What it does
            </div>
            <div style={{fontSize: 14, color: 'var(--ide-fg)', lineHeight: 1.55}}>
              {r?.description ||
                r?.tagline ||
                `Migrate from ${r?.sourceStack} to ${r?.targetStack}.`}
            </div>
          </div>

          {detail.locked && (
            <NoteCard tone='red'>
              This migration requires the {detail.unlockTier || r?.tier} plan.{' '}
              <Link to='/plans' style={{color: 'var(--ide-red)', fontWeight: 600}}>
                Upgrade →
              </Link>
            </NoteCard>
          )}

          <SectionLabel>Configure</SectionLabel>
          <div className='ide-card'>
            <KvRows
              pairs={[
                {k: 'From', v: r?.sourceStack || '—'},
                {k: 'To', v: r?.targetStack || '—'},
                {
                  k: 'Affected files',
                  v: <span className='ide-mono'>{r?.affectedFilesGlob || '—'}</span>,
                },
                {
                  k: 'Est files',
                  v:
                    r?.estFilesLow != null || r?.estFilesHigh != null
                      ? `${r?.estFilesLow ?? r?.estFilesHigh}${r?.estFilesHigh != null && r?.estFilesHigh !== r?.estFilesLow ? `–${r?.estFilesHigh}` : ''}`
                      : '—',
                },
                {k: 'Est cost', v: credits || '—'},
              ]}
            />
            <div style={{display: 'flex', gap: 8, marginTop: 16}}>
              <button className='ide-btn ghost' onClick={() => setDryRunOpen(true)}>
                Dry run
              </button>
              <button
                className='ide-btn'
                onClick={() => setApplyOpen(true)}
                disabled={detail.locked}
              >
                Apply migration →
              </button>
            </div>
          </div>

          {detail.slots.length > 0 && (
            <>
              <SectionLabel>Options</SectionLabel>
              <div className='ide-card'>
                <KvRows
                  pairs={detail.slots.map((slot) => ({
                    k: slot.displayName + (slot.required ? ' *' : ''),
                    v: slot.defaultValue || slot.description || slot.type,
                  }))}
                />
              </div>
            </>
          )}

          <NoteCard label='Heads up' tone='red'>
            We pause at every plan gate. You're never further than one approval away from the next
            batch. Run a dry-run first to check applicability and cost.
          </NoteCard>
        </>
      )}

      <MigrationDryRunModal
        open={dryRunOpen}
        slug={slug || ''}
        onClose={() => setDryRunOpen(false)}
      />
      <MigrationApplyModal
        open={applyOpen}
        slug={slug || ''}
        onClose={() => setApplyOpen(false)}
        onLaunched={(runId) => {
          setApplyOpen(false);
          navigate(`/plan/${runId}`);
        }}
      />
    </div>
  );
}

// Dry-run picker — runs migrationDryRun() and shows per-repo applicability.
function MigrationDryRunModal({
  open,
  slug,
  onClose,
}: {
  open: boolean;
  slug: string;
  onClose: () => void;
}) {
  const {projects, repos, projectId, setProjectId, selectedRepoIds, toggleRepo} =
    useProjectRepoPicker(open);
  const [result, setResult] = useState<api.MigrationDryRunResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  useEffect(() => {
    setResult(null);
  }, [projectId, selectedRepoIds]);

  async function run() {
    if (!projectId || selectedRepoIds.length === 0) return;
    setBusy(true);
    setErr('');
    try {
      setResult(await api.migrationDryRun(slug, {projectId, repoIds: selectedRepoIds}));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Dry run failed.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} title='Dry run' onClose={onClose} size='lg'>
      <ProjectRepoPickerBody
        projects={projects}
        repos={repos}
        projectId={projectId}
        setProjectId={setProjectId}
        selectedRepoIds={selectedRepoIds}
        toggleRepo={toggleRepo}
        emptyHint='Migrations run against a project.'
      />

      {err && <div style={{marginTop: 12, fontSize: 13, color: 'var(--ide-red)'}}>{err}</div>}

      {busy && (
        <div style={{marginTop: 14}}>
          <IdeLoader label='Checking applicability…' />
        </div>
      )}

      {result && !busy && (
        <div style={{marginTop: 16}}>
          <div className='ide-card'>
            <KvRows
              pairs={[
                {
                  k: 'Est files',
                  v:
                    result.estFilesLowTotal != null || result.estFilesHighTotal != null
                      ? `${result.estFilesLowTotal ?? result.estFilesHighTotal}–${result.estFilesHighTotal ?? result.estFilesLowTotal}`
                      : '—',
                },
                {
                  k: 'Est cost',
                  v:
                    result.estCostLowTotal != null || result.estCostHighTotal != null
                      ? `$${result.estCostLowTotal ?? result.estCostHighTotal} – $${result.estCostHighTotal ?? result.estCostLowTotal}`
                      : '—',
                },
              ]}
            />
          </div>
          <div style={{marginTop: 12, display: 'grid', gap: 6}}>
            {result.perRepo.map((pr) => {
              const ok = pr.status === 'APPLICABLE';
              return (
                <div
                  key={pr.repoId}
                  className='ide-card'
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '10px 14px',
                  }}
                >
                  <span
                    style={{
                      width: 8,
                      height: 8,
                      borderRadius: '50%',
                      flexShrink: 0,
                      background: ok ? 'var(--ide-green)' : 'var(--ide-red)',
                    }}
                  />
                  <span style={{flex: 1, minWidth: 0}}>
                    <span style={{display: 'block', fontSize: 13, color: 'var(--ide-fg-bright)'}}>
                      {pr.fullName || pr.repoId.slice(0, 8)}
                    </span>
                    <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                      {pr.reason || pr.status.replace(/_/g, ' ').toLowerCase()}
                    </span>
                  </span>
                  <IdeBadge kind={ok ? 'ok' : 'bad'}>
                    {ok ? 'applicable' : pr.status.replace(/_/g, ' ').toLowerCase()}
                  </IdeBadge>
                </div>
              );
            })}
          </div>
        </div>
      )}

      <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16}}>
        <button className='ide-btn ghost' onClick={onClose} disabled={busy}>
          Close
        </button>
        <button
          className='ide-btn'
          disabled={busy || !projectId || selectedRepoIds.length === 0}
          onClick={() => void run()}
        >
          {busy ? '…' : 'Run dry run'}
        </button>
      </div>
    </Modal>
  );
}

// Apply picker — runs migrationApply() and navigates to the plan gate.
function MigrationApplyModal({
  open,
  slug,
  onClose,
  onLaunched,
}: {
  open: boolean;
  slug: string;
  onClose: () => void;
  onLaunched: (runId: string) => void;
}) {
  const {projects, repos, projectId, setProjectId, selectedRepoIds, toggleRepo} =
    useProjectRepoPicker(open);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  async function apply() {
    if (!projectId || selectedRepoIds.length === 0) return;
    setBusy(true);
    setErr('');
    try {
      const resp = await api.migrationApply(slug, {projectId, repoIds: selectedRepoIds});
      onLaunched(resp.executionRunId);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Could not start the migration.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} title='Apply migration' onClose={onClose} size='md'>
      <ProjectRepoPickerBody
        projects={projects}
        repos={repos}
        projectId={projectId}
        setProjectId={setProjectId}
        selectedRepoIds={selectedRepoIds}
        toggleRepo={toggleRepo}
        emptyHint='Migrations run against a project.'
      />

      <div style={{marginTop: 12, fontSize: 12.5, color: 'var(--ide-fg-muted)', lineHeight: 1.5}}>
        We'll plan the migration and pause at the plan gate for your approval before any code is
        written.
      </div>

      {err && <div style={{marginTop: 12, fontSize: 13, color: 'var(--ide-red)'}}>{err}</div>}

      <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16}}>
        <button className='ide-btn ghost' onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button
          className='ide-btn'
          disabled={busy || !projectId || selectedRepoIds.length === 0}
          onClick={() => void apply()}
        >
          {busy ? '…' : 'Apply →'}
        </button>
      </div>
    </Modal>
  );
}

// ← Signal SpecTemplatesPage. Route /templates. api: getProjects,
// listSpecTemplates, createProjectSpecTemplate, deleteSpecTemplate.
export function IdeSpecTemplates() {
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [templates, setTemplates] = useState<api.SpecTemplate[] | null>(null);
  const [err, setErr] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [createBusy, setCreateBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<{id: string; name: string} | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);

  useEffect(() => {
    api
      .getProjects()
      .then((p) => {
        const active = p.filter((x) => !x.archivedAt);
        setProjects(active);
        if (active.length > 0) setProjectId((cur) => cur ?? active[0].id);
      })
      .catch(() => {
        /* swallow */
      });
  }, []);

  useEffect(() => {
    if (!projectId) return;
    setTemplates(null);
    api
      .listSpecTemplates(projectId)
      .then(setTemplates)
      .catch((e: unknown) => {
        setErr(e instanceof Error ? e.message : 'Failed to load templates.');
      });
  }, [projectId]);

  async function performCreate(values: Record<string, string>) {
    if (!projectId) return;
    const name = (values.name || '').trim();
    const requirementTemplate = (values.requirementTemplate || '').trim();
    const description = (values.description || '').trim();
    if (!name || !requirementTemplate) return;
    setCreateBusy(true);
    setErr('');
    try {
      await api.createProjectSpecTemplate(projectId, {
        name,
        requirementTemplate,
        ...(description ? {description} : {}),
      });
      setTemplates(await api.listSpecTemplates(projectId));
      setCreateOpen(false);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to create template.');
    } finally {
      setCreateBusy(false);
    }
  }

  async function performDelete(id: string) {
    if (!projectId) return;
    setDeleteBusy(true);
    try {
      await api.deleteSpecTemplate(projectId, id);
      setTemplates(await api.listSpecTemplates(projectId));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed.');
    } finally {
      setDeleteBusy(false);
      setConfirmDelete(null);
    }
  }

  // "Use" — stash the spec body + hand off to the composer (consumed on mount).
  function applyTemplate(t: api.SpecTemplate) {
    sessionStorage.setItem('signal:pending-spec', t.requirementTemplate || '');
    window.location.href = '/compose';
  }

  const activeProjectName = projects.find((p) => p.id === projectId)?.name;

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Spec templates</div>
      <div className='ide-sub'>{activeProjectName || 'Saved templates'}</div>

      <NoteCard tone='blue'>Save specs you reuse. Templates pre-fill the composer.</NoteCard>

      <ErrBanner msg={err} />

      {projects.length > 0 && (
        <>
          <SectionLabel>Project</SectionLabel>
          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
            {projects.map((p) => (
              <Pill key={p.id} active={p.id === projectId} onClick={() => setProjectId(p.id)}>
                {p.name}
              </Pill>
            ))}
          </div>
        </>
      )}

      {projectId && <SectionLabel>Templates · {templates?.length ?? 0}</SectionLabel>}
      {!projectId ? (
        <div className='ide-card'>
          <p style={{fontSize: 13.5, color: 'var(--ide-fg-muted)', margin: 0, lineHeight: 1.5}}>
            Templates are project-scoped.{' '}
            <Link to='/projects' style={{color: 'var(--ide-accent)'}}>
              Create a project →
            </Link>{' '}
            to start saving specs.
          </p>
        </div>
      ) : templates === null ? (
        <IdeLoader label='Loading templates…' />
      ) : templates.length === 0 ? (
        <div className='ide-empty'>No templates yet. Save one to reuse common specs.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {templates.map((t, i) => {
            const preview = (t.description || t.requirementTemplate || '').slice(0, 60);
            return (
              <div
                key={t.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 12,
                  padding: '11px 16px',
                  borderBottom: i < templates.length - 1 ? '1px solid var(--ide-border)' : 'none',
                }}
              >
                <span style={{flex: 1, minWidth: 0}}>
                  <span
                    style={{
                      display: 'block',
                      fontSize: 13.5,
                      fontWeight: 500,
                      color: 'var(--ide-fg-bright)',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {t.name || 'untitled'}
                  </span>
                  <span
                    style={{
                      display: 'block',
                      fontSize: 11.5,
                      color: 'var(--ide-fg-muted)',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {preview}…
                  </span>
                </span>
                <button
                  className='ide-btn ghost'
                  style={{padding: '4px 12px', flexShrink: 0}}
                  onClick={() => applyTemplate(t)}
                >
                  Use
                </button>
                <button
                  className='ide-btn ghost'
                  style={{padding: '4px 10px', flexShrink: 0, color: 'var(--ide-red)'}}
                  aria-label='Delete template'
                  onClick={() => setConfirmDelete({id: t.id, name: t.name || 'this template'})}
                >
                  ×
                </button>
              </div>
            );
          })}
        </div>
      )}

      {projectId && (
        <button
          className='ide-btn ghost'
          style={{marginTop: 14, width: '100%'}}
          onClick={() => setCreateOpen(true)}
        >
          + New template
        </button>
      )}

      <PromptModal
        open={createOpen}
        title='New spec template'
        body='Save reusable spec scaffolding. The body pre-fills the composer when you pick this template.'
        fields={[
          {
            name: 'name',
            label: 'Template name',
            placeholder: 'e.g. CRUD endpoint scaffold',
            required: true,
          },
          {
            name: 'description',
            label: 'Description (optional)',
            placeholder: 'Short note about when to use this',
          },
          {
            name: 'requirementTemplate',
            label: 'Spec body',
            placeholder: 'Describe the spec…',
            multiline: true,
            required: true,
          },
        ]}
        submitLabel='Save template'
        busy={createBusy}
        onClose={() => setCreateOpen(false)}
        onSubmit={performCreate}
      />
      <ConfirmModal
        open={!!confirmDelete}
        title='Delete this template?'
        body={`"${confirmDelete?.name || 'this template'}" will be removed. Existing runs that used it are unaffected.`}
        confirmLabel='Delete'
        danger
        busy={deleteBusy}
        onClose={() => setConfirmDelete(null)}
        onConfirm={() => {
          if (confirmDelete) void performDelete(confirmDelete.id);
        }}
      />
    </div>
  );
}
