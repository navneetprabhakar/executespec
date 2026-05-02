import {useEffect, useMemo, useState} from 'react';
import {Link, useParams} from 'react-router-dom';
import {DiffEditor} from '@monaco-editor/react';
import {
  getRun,
  getValidationReport,
  getFilesWithStatus,
  getFileDiff,
  type FileWithStatus,
  type FileDiffPayload,
  type RunDetail,
  type ValidationReportData,
} from '@/lib/api';
import {GEIST_THEME_NAME} from '@/lib/monacoGeistTheme';
import '@/lib/monacoSetup';
import Pill from '../components/Pill';
import RunFailureSummary from '../components/RunFailureSummary';
import {
  STATUS_LABELS,
  VERDICT_LABELS,
  PROVIDER_DISPLAY,
  COST_PREFIX,
  type V3Verdict,
} from '../lib/terminology';

// Mirrors RunDetailTabs.MONACO_LANG_MAP — kept inline so V3RunDetail doesn't pull in the entire
// 2300-LOC tabs module just for one map. Add new extensions here when worker-generated stacks
// expand. Default: plaintext (Monaco still renders fine without highlighting).
const MONACO_LANG_MAP: Record<string, string> = {
  ts: 'typescript',
  tsx: 'typescript',
  js: 'javascript',
  jsx: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  json: 'json',
  java: 'java',
  py: 'python',
  rb: 'ruby',
  rs: 'rust',
  go: 'go',
  yml: 'yaml',
  yaml: 'yaml',
  xml: 'xml',
  html: 'html',
  css: 'css',
  scss: 'scss',
  md: 'markdown',
  sh: 'shell',
  sql: 'sql',
  toml: 'ini',
  dockerfile: 'dockerfile',
};

function getMonacoLanguage(filePath: string): string {
  const name = filePath.split('/').pop()?.toLowerCase() ?? '';
  if (name === 'dockerfile') return 'dockerfile';
  const ext = name.includes('.') ? name.split('.').pop()! : '';
  return MONACO_LANG_MAP[ext] ?? 'plaintext';
}

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
  const {slug, runShort} = useParams<{slug: string; runShort: string}>();
  const runId = runShort ?? '';

  const [detail, setDetail] = useState<RunDetail | null>(null);
  const [report, setReport] = useState<ValidationReportData | null>(null);
  // 2026-05-02 — switched from `getFiles` (every workspace file) to `getFilesWithStatus` so the
  // tree only carries ADDED/MODIFIED entries; UNCHANGED files are filtered out before render.
  // The user's ask: "make sure only diff is visible here." Vendored noise (node_modules/, dist/,
  // build/) is also filtered as a belt-and-braces guard so a misconfigured worker can't drown
  // the tree even if those paths slip through to ADDED.
  const [filesWithStatus, setFilesWithStatus] = useState<FileWithStatus[] | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const [diff, setDiff] = useState<FileDiffPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [diffLoading, setDiffLoading] = useState(false);
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
      getFilesWithStatus(runId).catch(() => [] as FileWithStatus[]),
    ]).then(([d, r, f]) => {
      if (cancelled) return;
      setDetail(d);
      setReport(r);
      setFilesWithStatus(f);
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [runId]);

  // Files actually changed by the run (ADDED + MODIFIED, with vendored paths filtered out).
  const VENDORED_PATTERN =
    /(^|\/)(node_modules|venv|\.venv|dist|build|target|\.next|\.gradle|__pycache__|\.idea|\.vscode|coverage)\//;
  const changedFiles = useMemo<FileWithStatus[]>(() => {
    if (!filesWithStatus) return [];
    return filesWithStatus.filter(
      (f) => (f.status === 'ADDED' || f.status === 'MODIFIED') && !VENDORED_PATTERN.test(f.path),
    );
  }, [filesWithStatus]);

  // Auto-pick the first changed file (skip lockfiles when we have a non-lockfile alternative).
  useEffect(() => {
    if (changedFiles.length === 0 || active) return;
    const nonLock = changedFiles.find((f) => !f.path.endsWith('.lock') && !f.path.endsWith('-lock.json'));
    setActive((nonLock ?? changedFiles[0]).path);
  }, [changedFiles, active]);

  // Load base + head for the active file
  useEffect(() => {
    if (!active) {
      setDiff(null);
      return;
    }
    let cancelled = false;
    setDiffLoading(true);
    getFileDiff(runId, active)
      .then((d) => {
        if (!cancelled) {
          setDiff(d);
          setDiffLoading(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setDiff({path: active, base: '', head: '// (could not load file diff)', status: 'ADDED'});
          setDiffLoading(false);
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

  const headerTone =
    verdict === 'READY'
      ? 'ok'
      : verdict === 'NEEDS_REVIEW'
        ? 'warn'
        : verdict === 'FAILED'
          ? 'err'
          : 'neutral';

  if (error) {
    return (
      <div
        className='v3-placeholder'
        style={{borderColor: 'var(--v3-error)', color: 'var(--v3-error)'}}
      >
        <h1>Couldn't load run</h1>
        <p>{error}</p>
        {slug && (
          <Link to={`/projects/${slug}`} style={{color: 'var(--v3-brand)', fontSize: 12}}>
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
              {verdict ? VERDICT_LABELS[verdict] : (STATUS_LABELS[detail.status] ?? detail.status)}
            </Pill>
            <span style={{fontSize: 14, fontWeight: 600, color: 'var(--v3-text)'}}>
              {detail.runTitle ?? 'Untitled run'}
            </span>
            <span style={{fontSize: 11, color: 'var(--v3-text-subtle)'}}>
              · {runId.slice(0, 8)} ·{' '}
              {detail.completedAt
                ? new Date(detail.completedAt).toLocaleString()
                : new Date(detail.startedAt).toLocaleString()}
            </span>
          </div>

          {/* Evidence chip row */}
          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10}}>
            {report?.finalBuildPassed === true && (
              <Pill tone='ok' size='sm'>
                Build ✓
              </Pill>
            )}
            {report?.finalBuildPassed === false && (
              <Pill tone='err' size='sm'>
                Build ✗
              </Pill>
            )}
            {report?.testsTotal != null && report.testsTotal > 0 && (
              <Pill tone={report.testsPassed === report.testsTotal ? 'ok' : 'warn'} size='sm'>
                {report.testsPassed}/{report.testsTotal} tests
              </Pill>
            )}
            {report?.reviewerVerdict === 'CLEAN' && (
              <Pill tone='ok' size='sm'>
                Reviewer 0 issues
              </Pill>
            )}
            {report?.reviewerVerdict === 'FIXED_ISSUES' && (
              <Pill tone='warn' size='sm'>
                Reviewer auto-fixed
              </Pill>
            )}
            {report?.reviewerVerdict === 'UNRESOLVED_ISSUES' && (
              <Pill tone='err' size='sm'>
                Reviewer unresolved
              </Pill>
            )}
            {detail.aiProvider && (
              <Pill tone='brand' size='sm'>
                {PROVIDER_DISPLAY[detail.aiProvider] ?? detail.aiProvider}
              </Pill>
            )}
            {detail.actualCostUsd != null && (
              <Pill tone='neutral' size='sm'>
                {COST_PREFIX} ${detail.actualCostUsd.toFixed(2)}
              </Pill>
            )}
          </div>

          {/* Action row */}
          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
            {detail.githubPrUrl && (
              <a
                href={detail.githubPrUrl}
                target='_blank'
                rel='noreferrer'
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
        className='v3-rundetail-grid'
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
            Changed files {filesWithStatus ? `(${changedFiles.length})` : ''}
          </div>
          {!filesWithStatus && (
            <div style={{padding: 8, fontSize: 12, color: 'var(--v3-text-subtle)'}}>Loading…</div>
          )}
          {filesWithStatus && changedFiles.length === 0 && (
            <div style={{padding: 8, fontSize: 12, color: 'var(--v3-text-subtle)'}}>
              No file changes detected for this run.
            </div>
          )}
          {filesWithStatus && (
            <ul style={{margin: 0, padding: 0, listStyle: 'none'}}>
              {changedFiles.map((f) => (
                <li key={f.path}>
                  <button
                    type='button'
                    onClick={() => setActive(f.path)}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 6,
                      width: '100%',
                      textAlign: 'left',
                      padding: '4px 6px',
                      background: active === f.path ? 'var(--v3-surface-elevated)' : 'transparent',
                      color: active === f.path ? 'var(--v3-text)' : 'var(--v3-text-muted)',
                      border: 'none',
                      borderRadius: 4,
                      cursor: 'pointer',
                      fontSize: 11.5,
                      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                      overflow: 'hidden',
                    }}
                    title={f.path}
                  >
                    <span
                      style={{
                        flexShrink: 0,
                        width: 16,
                        textAlign: 'center',
                        fontSize: 10,
                        fontWeight: 700,
                        color:
                          f.status === 'ADDED' ? 'var(--v3-success)' : 'var(--v3-warning)',
                      }}
                      aria-label={f.status === 'ADDED' ? 'Added' : 'Modified'}
                    >
                      {f.status === 'ADDED' ? 'A' : 'M'}
                    </span>
                    <span
                      style={{
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {f.path}
                    </span>
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
            display: 'flex',
            flexDirection: 'column',
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
                display: 'flex',
                alignItems: 'center',
                gap: 8,
              }}
            >
              <span style={{flex: 1, overflow: 'hidden', textOverflow: 'ellipsis'}}>{active}</span>
              {diff && (
                <Pill tone={diff.status === 'ADDED' ? 'ok' : 'warn'} size='sm'>
                  {diff.status === 'ADDED' ? 'New file' : 'Modified'}
                </Pill>
              )}
            </div>
          )}
          {diffLoading && (
            <div style={{padding: 16, fontSize: 12, color: 'var(--v3-text-subtle)'}}>
              Loading diff…
            </div>
          )}
          {!diffLoading && diff && (
            <div style={{flex: 1, minHeight: 0, height: 'calc(100vh - 360px)'}}>
              <DiffEditor
                key={active ?? ''}
                language={getMonacoLanguage(diff.path)}
                original={diff.base}
                modified={diff.head}
                theme={GEIST_THEME_NAME}
                options={{
                  readOnly: true,
                  // Only render lines that actually changed — this is the "only diff is
                  // visible" contract. Monaco hides identical regions behind expandable
                  // chevrons by default when this flag is on.
                  hideUnchangedRegions: {
                    enabled: true,
                    contextLineCount: 3,
                    minimumLineCount: 3,
                    revealLineCount: 20,
                  },
                  renderSideBySide: false, // inline diff fits the narrow 1fr column better
                  minimap: {enabled: false},
                  scrollBeyondLastLine: false,
                  fontSize: 12,
                  lineNumbers: 'on',
                  folding: true,
                  showFoldingControls: 'always',
                  wordWrap: 'off',
                  renderLineHighlight: 'line',
                  contextmenu: false,
                  domReadOnly: true,
                  cursorStyle: 'line-thin',
                  scrollbar: {
                    verticalScrollbarSize: 6,
                    horizontalScrollbarSize: 6,
                  },
                  overviewRulerLanes: 0,
                  hideCursorInOverviewRuler: true,
                  overviewRulerBorder: false,
                  guides: {
                    indentation: true,
                    bracketPairs: true,
                  },
                  padding: {top: 8},
                }}
              />
            </div>
          )}
          {!loading && !active && !diffLoading && (
            <div style={{padding: 16, fontSize: 12, color: 'var(--v3-text-subtle)'}}>
              Select a changed file from the left to view its diff.
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
