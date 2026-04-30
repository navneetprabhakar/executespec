import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  getRun,
  getValidationReport,
  getFiles,
  getFile,
  type RunDetail,
  type ValidationReportData,
} from '@/lib/api';
import Pill from '../components/Pill';
import RunFailureSummary from '../components/RunFailureSummary';
import {
  STATUS_LABELS,
  VERDICT_LABELS,
  PROVIDER_DISPLAY,
  COST_PREFIX,
  type V3Verdict,
} from '../lib/terminology';

/**
 * R-130 — PR-shaped run detail.
 *
 * Three zones:
 *   1. Status header — single verdict (Ready / Needs review / Failed) +
 *      4 evidence chips + run metadata (provider, cost, duration). No
 *      "Verified N/100" headline.
 *   2. File tree (left) + file diff/content (right). Two-pane on desktop,
 *      stacked on mobile. Reuses /api/runs/{id}/files for the tree and
 *      /api/runs/{id}/file?path=… for content.
 *   3. Failure summary (R-131) renders ABOVE the diff when verdict is
 *      Needs review or Failed — points at the next action.
 *
 * "Open PR" link surfaces when run.githubPrUrl is set. "Re-run" routes
 * back to /projects/{slug} composer pre-filled with the spec.
 */
export default function V3RunDetail() {
  const { slug, runShort } = useParams<{ slug: string; runShort: string }>();
  const runId = runShort ?? '';

  const [detail, setDetail] = useState<RunDetail | null>(null);
  const [report, setReport] = useState<ValidationReportData | null>(null);
  const [files, setFiles] = useState<string[] | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const [content, setContent] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [contentLoading, setContentLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Lazy-load run + report + files in parallel
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.all([
      getRun(runId).catch((e) => {
        if (!cancelled) setError((e as Error)?.message ?? 'Run not found');
        return null;
      }),
      getValidationReport(runId).catch(() => null),
      getFiles(runId).catch(() => [] as string[]),
    ]).then(([d, r, f]) => {
      if (cancelled) return;
      setDetail(d);
      setReport(r);
      setFiles(f);
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [runId]);

  // Auto-pick first non-trivial file
  useEffect(() => {
    if (!files || files.length === 0 || active) return;
    const first =
      files.find(
        (p) =>
          !p.endsWith('/') &&
          !p.includes('/node_modules/') &&
          !p.endsWith('.lock')
      ) ?? files[0];
    setActive(first);
  }, [files, active]);

  // Load content for the active file
  useEffect(() => {
    if (!active) {
      setContent(null);
      return;
    }
    let cancelled = false;
    setContentLoading(true);
    getFile(runId, active)
      .then((c) => {
        if (!cancelled) {
          setContent(c);
          setContentLoading(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setContent('// (could not load file content)');
          setContentLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [active, runId]);

  const verdict = useMemo<V3Verdict | null>(() => {
    if (!detail) return null;
    const status = detail.status;
    if (status === 'FAILED' || status === 'CANCELLED' || status === 'ABORTED') return 'FAILED';
    if (status !== 'COMPLETE' && status !== 'PARTIAL') return null;
    const buildOk = report?.finalBuildPassed === true;
    const reviewerClean = report?.reviewerVerdict === 'CLEAN';
    const testsPass =
      report?.testsTotal != null && report.testsTotal > 0
        ? report.testsPassed === report.testsTotal
        : null;
    if (buildOk && reviewerClean && testsPass !== false) return 'READY';
    if (status === 'PARTIAL' || !buildOk) return 'FAILED';
    return 'NEEDS_REVIEW';
  }, [detail, report]);

  const headerTone = verdict === 'READY' ? 'ok' : verdict === 'NEEDS_REVIEW' ? 'warn' : verdict === 'FAILED' ? 'err' : 'neutral';

  if (error) {
    return (
      <div className="v3-placeholder" style={{ borderColor: 'var(--v3-error)', color: 'var(--v3-error)' }}>
        <h1>Couldn't load run</h1>
        <p>{error}</p>
        {slug && (
          <Link to={`/projects/${slug}`} style={{ color: 'var(--v3-brand)', fontSize: 12 }}>
            ← Back to project
          </Link>
        )}
      </div>
    );
  }

  return (
    <div>
      {/* Back-link breadcrumb (the only one in v3) */}
      {slug && (
        <Link
          to={`/projects/${slug}`}
          style={{
            display: 'inline-block',
            marginBottom: 12,
            color: 'var(--v3-text-muted)',
            fontSize: 11.5,
            textDecoration: 'none',
          }}
        >
          ← Back to project
        </Link>
      )}

      {/* Status header */}
      {detail && (
        <div
          style={{
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border)',
            borderRadius: 8,
            padding: 14,
            marginBottom: 16,
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              flexWrap: 'wrap',
              marginBottom: 8,
            }}
          >
            <Pill tone={headerTone}>
              {verdict ? VERDICT_LABELS[verdict] : STATUS_LABELS[detail.status] ?? detail.status}
            </Pill>
            <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}>
              {detail.runTitle ?? 'Untitled run'}
            </span>
            <span style={{ fontSize: 11, color: 'var(--v3-text-subtle)' }}>
              · {runId.slice(0, 8)} ·{' '}
              {detail.completedAt
                ? new Date(detail.completedAt).toLocaleString()
                : new Date(detail.startedAt).toLocaleString()}
            </span>
          </div>

          {/* Evidence chip row */}
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10 }}>
            {report?.finalBuildPassed === true && <Pill tone="ok" size="sm">Build ✓</Pill>}
            {report?.finalBuildPassed === false && <Pill tone="err" size="sm">Build ✗</Pill>}
            {report?.testsTotal != null && report.testsTotal > 0 && (
              <Pill
                tone={report.testsPassed === report.testsTotal ? 'ok' : 'warn'}
                size="sm"
              >
                {report.testsPassed}/{report.testsTotal} tests
              </Pill>
            )}
            {report?.reviewerVerdict === 'CLEAN' && (
              <Pill tone="ok" size="sm">Reviewer 0 issues</Pill>
            )}
            {report?.reviewerVerdict === 'FIXED_ISSUES' && (
              <Pill tone="warn" size="sm">Reviewer auto-fixed</Pill>
            )}
            {report?.reviewerVerdict === 'UNRESOLVED_ISSUES' && (
              <Pill tone="err" size="sm">Reviewer unresolved</Pill>
            )}
            {detail.aiProvider && (
              <Pill tone="brand" size="sm">{PROVIDER_DISPLAY[detail.aiProvider] ?? detail.aiProvider}</Pill>
            )}
            {detail.actualCostUsd != null && (
              <Pill tone="neutral" size="sm">
                {COST_PREFIX} ${detail.actualCostUsd.toFixed(2)}
              </Pill>
            )}
          </div>

          {/* Action row */}
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            {detail.githubPrUrl && (
              <a
                href={detail.githubPrUrl}
                target="_blank"
                rel="noreferrer"
                style={{
                  padding: '5px 12px',
                  fontSize: 12,
                  fontWeight: 600,
                  background: 'var(--v3-brand)',
                  color: 'var(--v3-text-on-brand)',
                  border: 'none',
                  borderRadius: 6,
                  cursor: 'pointer',
                  textDecoration: 'none',
                  fontFamily: 'inherit',
                }}
              >
                Open PR ↗
              </a>
            )}
            {slug && (
              <Link
                to={`/projects/${slug}`}
                style={{
                  padding: '5px 12px',
                  fontSize: 12,
                  fontWeight: 500,
                  background: 'transparent',
                  color: 'var(--v3-text-muted)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 6,
                  cursor: 'pointer',
                  textDecoration: 'none',
                  fontFamily: 'inherit',
                }}
              >
                Re-prompt
              </Link>
            )}
          </div>
        </div>
      )}

      {/* Failure summary (R-131) — only when warranted */}
      {detail && (verdict === 'FAILED' || verdict === 'NEEDS_REVIEW') && (
        <RunFailureSummary detail={detail} report={report} />
      )}

      {/* File tree + content (two-pane on desktop, stacked on mobile) */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(220px, 280px) 1fr',
          gap: 16,
          alignItems: 'start',
        }}
        className="v3-rundetail-grid"
      >
        <aside
          style={{
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border)',
            borderRadius: 8,
            padding: 8,
            maxHeight: 'calc(100vh - 320px)',
            overflowY: 'auto',
          }}
        >
          <div
            style={{
              padding: '4px 6px 8px',
              fontSize: 11,
              fontWeight: 600,
              color: 'var(--v3-text-subtle)',
              letterSpacing: 0.5,
              textTransform: 'uppercase',
            }}
          >
            Files {files ? `(${files.length})` : ''}
          </div>
          {!files && <div style={{ padding: 8, fontSize: 12, color: 'var(--v3-text-subtle)' }}>Loading…</div>}
          {files && files.length === 0 && (
            <div style={{ padding: 8, fontSize: 12, color: 'var(--v3-text-subtle)' }}>
              No workspace files for this run.
            </div>
          )}
          {files && (
            <ul style={{ margin: 0, padding: 0, listStyle: 'none' }}>
              {files.map((f) => (
                <li key={f}>
                  <button
                    type="button"
                    onClick={() => setActive(f)}
                    style={{
                      display: 'block',
                      width: '100%',
                      textAlign: 'left',
                      padding: '4px 6px',
                      background: active === f ? 'var(--v3-surface-elevated)' : 'transparent',
                      color: active === f ? 'var(--v3-text)' : 'var(--v3-text-muted)',
                      border: 'none',
                      borderRadius: 4,
                      cursor: 'pointer',
                      fontSize: 11.5,
                      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                    title={f}
                  >
                    {f}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </aside>

        <section
          style={{
            background: 'var(--v3-bg)',
            border: '1px solid var(--v3-border)',
            borderRadius: 8,
            overflow: 'hidden',
            minHeight: 280,
          }}
        >
          {active && (
            <div
              style={{
                padding: '6px 12px',
                background: 'var(--v3-surface)',
                borderBottom: '1px solid var(--v3-border)',
                fontSize: 11.5,
                color: 'var(--v3-text-muted)',
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
              }}
            >
              {active}
            </div>
          )}
          {contentLoading && (
            <div style={{ padding: 16, fontSize: 12, color: 'var(--v3-text-subtle)' }}>Loading file…</div>
          )}
          {!contentLoading && content && (
            <pre
              style={{
                margin: 0,
                padding: 14,
                fontSize: 11.5,
                lineHeight: 1.55,
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                color: 'var(--v3-text)',
                overflow: 'auto',
                maxHeight: 'calc(100vh - 360px)',
                whiteSpace: 'pre',
              }}
            >
              {content}
            </pre>
          )}
          {!loading && !active && !contentLoading && (
            <div style={{ padding: 16, fontSize: 12, color: 'var(--v3-text-subtle)' }}>
              Select a file from the left to view its contents.
            </div>
          )}
        </section>
      </div>

      {/* Mobile collapse: stack the two panes */}
      <style>{`
        @media (max-width: 768px) {
          .v3-rundetail-grid {
            grid-template-columns: 1fr !important;
          }
        }
      `}</style>
    </div>
  );
}
