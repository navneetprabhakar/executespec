import {useEffect, useState} from 'react';
import {Link} from 'react-router-dom';
import {AlertTriangle, Check} from 'lucide-react';
import {getProjectSummary, getArchitectureStaleness, type StalenessReport} from '@/lib/api';

/**
 * Audit r5 (#4, 2026-05-06) — "what does ExecuteSpec see in your repo?"
 *
 * The product audit flagged that a new user submitting a spec for an
 * existing repo had no way to inspect what the planner thinks the repo
 * looks like. The codemap + architecture doc + project summary all
 * exist server-side (codeintel/ package) — they were just never
 * surfaced on the v3 project page. The "Knowledge" tab on the v2
 * dashboard had them, but that's two clicks away from the chat
 * surface, AND new users on v3 may never visit the v2 dashboard.
 *
 * This panel renders an at-a-glance "trust check" right above the
 * conversation thread when the project has at least one linked repo:
 *   - Project summary: 2-4-sentence read of what the repo does
 *   - Codemap staleness: per-repo SHA drift vs origin HEAD
 *   - "Open Architecture →" link to the v2 dashboard's Knowledge view
 *     for the deeper read.
 *
 * If no repos are linked yet (the most common new-user state), the
 * panel hides itself entirely so we don't pretend to know something
 * we don't.
 */
interface Props {
  projectId: string;
}

export default function RepoContextPanel({projectId}: Props) {
  const [summary, setSummary] = useState<string | null>(null);
  const [staleness, setStaleness] = useState<StalenessReport | null>(null);
  const [collapsed, setCollapsed] = useState(true);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      getProjectSummary(projectId).catch(() => null),
      getArchitectureStaleness(projectId).catch(() => null),
    ]).then(([s, st]) => {
      if (cancelled) return;
      setSummary(s?.content?.trim() || null);
      setStaleness(st);
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  // No repos = no signal worth showing.
  if (loading) return null;
  if (!staleness || staleness.totalCount === 0) return null;

  const stale = staleness.staleCount;
  const fresh = staleness.totalCount - stale;

  return (
    <div
      style={{
        marginBottom: 12,
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 8,
        padding: '8px 12px',
        fontSize: 12.5,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
        }}
      >
        <button
          type='button'
          onClick={() => setCollapsed((c) => !c)}
          style={{
            background: 'transparent',
            border: 'none',
            color: 'var(--v3-text-muted)',
            cursor: 'pointer',
            padding: 0,
            fontFamily: 'inherit',
            fontSize: 12.5,
            display: 'flex',
            alignItems: 'center',
            gap: 6,
          }}
          aria-expanded={!collapsed}
        >
          <span style={{color: 'var(--v3-text-subtle)'}}>{collapsed ? '▸' : '▾'}</span>
          <span>What I see in your repo</span>
          {/* F-21 fix (2026-05-09) — colour-only chip wasn't readable for
              red/green colour-blind users. Added a leading lucide glyph
              that encodes the same semantics (AlertTriangle for stale,
              Check for fresh) so the signal lands without colour. */}
          <span
            style={{
              fontSize: 11,
              padding: '1px 6px',
              borderRadius: 3,
              background: stale > 0 ? 'rgba(245,158,11,0.16)' : 'rgba(34,197,94,0.12)',
              color: stale > 0 ? 'var(--v3-warning)' : 'var(--v3-success, #22c55e)',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
            }}
            title={
              stale > 0
                ? `${stale} of ${staleness.totalCount} codemap(s) are stale relative to origin HEAD — run Analyse to refresh.`
                : 'All codemaps match the latest origin HEAD.'
            }
          >
            {stale > 0 ? (
              <AlertTriangle size={11} strokeWidth={2.5} aria-hidden='true' />
            ) : (
              <Check size={11} strokeWidth={2.5} aria-hidden='true' />
            )}
            {stale > 0
              ? `${fresh}/${staleness.totalCount} fresh · ${stale} stale`
              : `${staleness.totalCount} repo${staleness.totalCount === 1 ? '' : 's'} · fresh`}
          </span>
        </button>
        <Link
          to={`/projects/${projectId}?tab=code&codeSub=architecture`}
          style={{
            fontSize: 11.5,
            color: 'var(--v3-text-subtle)',
            textDecoration: 'none',
          }}
          title='Open the Code tab — architecture, repos, tech debt, standards'
        >
          Open Architecture →
        </Link>
      </div>
      {!collapsed && (
        <div style={{marginTop: 8, color: 'var(--v3-text-muted)', lineHeight: 1.55}}>
          {summary ? (
            <p style={{margin: 0, whiteSpace: 'pre-wrap'}}>{summary}</p>
          ) : (
            <p style={{margin: 0, color: 'var(--v3-text-subtle)'}}>
              Summary not yet generated — run an Analyse from the Code tab to read your codebase.
            </p>
          )}
        </div>
      )}
    </div>
  );
}
