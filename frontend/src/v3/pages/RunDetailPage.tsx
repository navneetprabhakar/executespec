import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {Link, useParams} from 'react-router-dom';
import SafeDiffEditor from '@/components/runs/SafeDiffEditor';
import {MonacoSafeMount} from '@/components/runs/MonacoSafeMount';
import {Editor as MonacoEditor} from '@monaco-editor/react';
import {FileTree} from '@/components/run/FileTree';
import {
  cancelRun,
  getRun,
  getValidationReport,
  getFilesWithStatus,
  getFileDiff,
  getRunPrs,
  type FileWithStatus,
  type FileDiffPayload,
  type RunDetail,
  type ValidationReportData,
} from '@/lib/api';
import {useSse} from '@/hooks/useSse';
import {useSseStore} from '@/stores/useSseStore';
import type {PrSummary} from '@/types/project';
import {GEIST_THEME_NAME} from '@/lib/monacoGeistTheme';
import '@/lib/monacoSetup';
import Pill from '../components/Pill';
import RunFailureSummary from '../components/RunFailureSummary';
import RunSidePanel from '../components/RunSidePanel';
import {
  STATUS_LABELS,
  VERDICT_LABELS,
  PROVIDER_DISPLAY,
  COST_PREFIX,
  type V3Verdict,
} from '../lib/terminology';
import {deriveVerdict as canonicalDeriveVerdict, toV3Verdict} from '@/lib/verdict';

// Mirrors RunDetailTabs.MONACO_LANG_MAP — kept inline so RunDetailPage doesn't pull in the entire
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
  // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
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
export default function RunDetailPage() {
  // Route param is `:projectId` (renamed from `:slug` in 4a20683c); kept
  // the old `slug` alias only for back-compat readers — every actual use
  // now reads `projectId`. Optional because the flat `/runs/:runShort`
  // route mounts this page without a project context (FREE / unlinked
  // runs, audit finding 2026-05-06).
  const {projectId, runShort} = useParams<{projectId?: string; runShort: string}>();
  const runId = runShort ?? '';

  // 2026-05-07 — RunDetail's 3-pane grid + diff editor want both
  // `v3-main--wide` (max-width 1600px) and `v3-main--fill` (full
  // viewport height) modifiers. Previously these were applied via
  // a per-page `useEffect`; they're now driven by `Shell.classifyRoute`
  // so the layout policy lives in one place. No-op here.

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
  // 2026-05-02 — multi-repo PR list. The legacy `detail.githubPrUrl` is null on multi-repo runs
  // (it's the V1 single-repo column); per-repo PRs live on `pull_request_record` and surface via
  // `/api/runs/{id}/prs`. The action row falls back to detail.githubPrUrl only when this list is
  // empty (legacy runs without registry rows).
  const [prs, setPrs] = useState<PrSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [diffLoading, setDiffLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 2026-05-07 — viewer mode toggle (parity with v2 RunDetailTabs Code
  // tab). 'diff' shows the SafeDiffEditor with hideUnchangedRegions —
  // good for review at a glance; 'full' shows the modified file as a
  // normal Monaco editor — good for reading the file in context.
  // ADDED files show the same thing in both modes (no original to diff
  // against), but we allow the toggle anyway for consistency.
  const [viewerMode, setViewerMode] = useState<'diff' | 'full'>('diff');

  // 2026-05-07 — re-fetch is shared between the initial mount and the
  // SSE-triggered refresh below. Ref-stable so the SSE-event effect
  // doesn't re-arm the listener on every state change.
  const refetchAll = useCallback(
    async (initial: boolean) => {
      if (initial) {
        setLoading(true);
        setError(null);
      }
      const [d, r, f, p] = await Promise.all([
        getRun(runId).catch((e) => {
          if (initial) setError((e as Error)?.message ?? 'Run not found');
          return null;
        }),
        getValidationReport(runId).catch(() => null),
        getFilesWithStatus(runId).catch(() => [] as FileWithStatus[]),
        getRunPrs(runId).catch(() => [] as PrSummary[]),
      ]);
      // The detail fetch is the source of truth — only patch if it
      // succeeded (a 5xx during a transient gap shouldn't blank the
      // pane the user is looking at).
      if (d) setDetail(d);
      // Validation report can legitimately be null (run not done) — only
      // patch when present so a partial pre-COMPLETE refetch can't wipe
      // a stale-but-real report we already have. Same logic for files +
      // PRs: they can legitimately be empty so we always patch.
      if (r) setReport(r);
      setFilesWithStatus(f);
      setPrs(p);
      if (initial) setLoading(false);
    },
    [runId],
  );

  // Initial parallel load on mount / runId change.
  useEffect(() => {
    let cancelled = false;
    refetchAll(true).catch(() => {
      if (cancelled) return;
      // refetchAll already set the error message via getRun's catch.
    });
    return () => {
      cancelled = true;
    };
  }, [runId, refetchAll]);

  // 2026-05-07 — live updates via SSE. Pre-fix the page only fetched
  // once on mount, so a non-terminal run's status / verdict / score /
  // file tree / PR list would freeze on whatever shape it had at first
  // load. The user's report: "the page didn't auto refresh on
  // progress." useSse subscribes to /api/runs/{id}/events; the global
  // SSE store collects every event into `entries`. We watch the latest
  // entry for this runId and refetch on the events that change visible
  // state — RUN_STATUS / RUN_DETAIL / GATE_OPENED /
  // VALIDATION_REPORT_READY / RUN_RESTARTED / BATCH_*. Other event
  // types (LOG, RUN_PROVIDER_SWITCHED) are ignored since they don't
  // affect what this page renders.
  useSse(runId);
  const lastEntryId = useSseStore((s) => s.entries[s.entries.length - 1]?.id ?? null);
  const lastEntryType = useSseStore((s) => s.entries[s.entries.length - 1]?.type ?? null);
  const lastSeenIdRef = useRef<string | null>(null);
  useEffect(() => {
    if (!lastEntryId || lastEntryId === lastSeenIdRef.current) return;
    lastSeenIdRef.current = lastEntryId;
    if (
      lastEntryType === 'RUN_STATUS' ||
      lastEntryType === 'RUN_DETAIL' ||
      lastEntryType === 'GATE_OPENED' ||
      lastEntryType === 'VALIDATION_REPORT_READY' ||
      lastEntryType === 'RUN_RESTARTED' ||
      lastEntryType === 'BUDGET_EXCEEDED' ||
      lastEntryType === 'BATCH_PROGRESS' ||
      lastEntryType === 'BATCH_COMPLETE' ||
      lastEntryType === 'BATCH_FAILED'
    ) {
      void refetchAll(false);
    }
  }, [lastEntryId, lastEntryType, refetchAll]);

  // 2026-05-08 — fallback polling for non-terminal runs. SSE-driven refresh
  // catches the big lifecycle events (status change, validation-report ready)
  // but the worker writes files mid-run without emitting any SSE event the
  // frontend listens to, so the file tree / cost / token totals freeze on
  // last-fetched values until terminal. This 5s interval mirrors the
  // RunDetailTabs cost-poll pattern and stops itself the moment the run
  // reaches a terminal status. Keeps the page feeling alive while the agent
  // is editing without waiting on a structural SSE event.
  useEffect(() => {
    if (!detail) return;
    const TERMINAL = new Set([
      'COMPLETE',
      'PARTIAL',
      'FAILED',
      'CANCELLED',
      'ABORTED',
    ]);
    if (TERMINAL.has(detail.status)) return;
    const interval = setInterval(() => {
      void refetchAll(false);
    }, 5000);
    return () => clearInterval(interval);
  }, [detail, refetchAll]);

  // Files actually changed by the run (ADDED + MODIFIED, with vendored paths filtered out).
  const VENDORED_PATTERN =
    /(^|\/)(node_modules|venv|\.venv|dist|build|target|\.next|\.gradle|__pycache__|\.idea|\.vscode|coverage)\//;
  const changedFiles = useMemo<FileWithStatus[]>(() => {
    if (!filesWithStatus) return [];
    return filesWithStatus.filter(
      (f) => (f.status === 'ADDED' || f.status === 'MODIFIED') && !VENDORED_PATTERN.test(f.path),
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filesWithStatus]);

  // Auto-pick the first changed file (skip lockfiles when we have a non-lockfile alternative).
  useEffect(() => {
    if (changedFiles.length === 0 || active) return;
    const nonLock = changedFiles.find(
      (f) => !f.path.endsWith('.lock') && !f.path.endsWith('-lock.json'),
    );
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

  // Audit r5 (P0-3): canonical resolver from lib/verdict.ts. The previous
  // bespoke logic disagreed with the v2 RunsPage row + v2 dashboard for the
  // same run because each surface had its own copy.
  const verdict = useMemo<V3Verdict | null>(() => {
    if (!detail) return null;
    return toV3Verdict(
      canonicalDeriveVerdict({
        status: detail.status,
        finalBuildPassed: report?.finalBuildPassed,
        testsPassed: report?.testsPassed,
        testsTotal: report?.testsTotal,
        reviewerVerdict: report?.reviewerVerdict,
        verifiedScore: report?.verifiedScore,
        statusReason: detail.statusReason,
      }),
    );
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
        {projectId && (
          <Link to={`/projects/${projectId}`} style={{color: 'var(--v3-brand)', fontSize: 12}}>
            ← Back to project
          </Link>
        )}
      </div>
    );
  }

  return (
    <div>
      {/* Back-link breadcrumb (the only one in v3) */}
      {projectId && (
        <Link
          to={`/projects/${projectId}`}
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
            {/* Multi-repo aware PR action.
                 - 0 PRs + no legacy URL → no button.
                 - 1 PR (registry or legacy) → single primary "Open PR ↗" button.
                 - N>1 PRs → one tertiary button per repo, labeled "<short>#<num>". */}
            {(() => {
              const legacyUrl = detail.githubPrUrl;
              if (prs.length === 0 && !legacyUrl) return null;
              if (prs.length <= 1) {
                // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
                const single = prs[0]?.prUrl ?? legacyUrl!;
                return (
                  <a
                    href={single}
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
                );
              }
              return prs.map((pr) => {
                const shortRepo = pr.repoFullName?.includes('/')
                  ? pr.repoFullName.slice(pr.repoFullName.lastIndexOf('/') + 1)
                  : pr.repoFullName;
                return (
                  <a
                    key={`${pr.repoFullName}-${pr.prNumber}`}
                    href={pr.prUrl}
                    target='_blank'
                    rel='noreferrer'
                    title={`${pr.repoFullName} · ${pr.status}`}
                    style={{
                      padding: '5px 10px',
                      fontSize: 12,
                      fontWeight: 600,
                      background: pr.status === 'MERGED' ? 'var(--v3-brand)' : 'transparent',
                      color: pr.status === 'MERGED' ? 'var(--v3-text-on-brand)' : 'var(--v3-text)',
                      border: pr.status === 'MERGED' ? 'none' : '1px solid var(--v3-brand)',
                      borderRadius: 6,
                      cursor: 'pointer',
                      textDecoration: 'none',
                      fontFamily: 'inherit',
                    }}
                  >
                    {shortRepo}#{pr.prNumber} ↗
                  </a>
                );
              });
            })()}
            {projectId && (
              <Link
                to={`/projects/${projectId}`}
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
            {/* Audit r5 (#3, 2026-05-06): persistent Cancel on the
                detail page for in-flight runs. Mirrors the bubble-card
                affordance so users who navigated to /runs/{id} mid-run
                still have an escape hatch. */}
            {detail &&
              !['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED', 'ABORTED'].includes(
                detail.status,
              ) && (
                <button
                  type='button'
                  onClick={async () => {
                    if (
                      !confirm(
                        'Cancel this run? Any work the agent has already done will be discarded.',
                      )
                    ) {
                      return;
                    }
                    try {
                      await cancelRun(runId);
                      // Optimistic flip; the auto-refetch (useEffect on
                      // active+runId) catches up on the next tick.
                      setDetail((d) => (d ? {...d, status: 'CANCELLED'} : d));
                    } catch (e) {
                      alert((e as Error)?.message ?? 'Cancel failed.');
                    }
                  }}
                  style={{
                    padding: '5px 12px',
                    fontSize: 12,
                    fontWeight: 500,
                    background: 'transparent',
                    color: 'var(--v3-error)',
                    border: '1px solid var(--v3-border)',
                    borderRadius: 6,
                    cursor: 'pointer',
                    fontFamily: 'inherit',
                  }}
                  title='Stop this run. The agent will discard any in-progress work.'
                >
                  Cancel run
                </button>
              )}
          </div>
        </div>
      )}

      {/* Failure summary (R-131) — only when there's an actual failure signal.
          Audit r5 (P1-4, 2026-05-06): previously rendered for any non-READY
          verdict, which fired on CLEAN_COMPLETION runs whose verifiedScore
          happened to land below 85 ("Needs review" by score, but nothing
          actually went wrong). The "What went wrong" header is now gated on
          a real failure indicator: build failed, tests failed, reviewer
          unresolved/failed, or terminal status FAILED/CANCELLED. */}
      {detail &&
        (() => {
          const buildFailed = report?.finalBuildPassed === false;
          const testsFailed =
            report?.testsTotal != null &&
            report.testsTotal > 0 &&
            (report.testsPassed ?? 0) < report.testsTotal;
          const reviewerBad =
            report?.reviewerVerdict === 'UNRESOLVED_ISSUES' ||
            report?.reviewerVerdict === 'REVIEW_FAILED';
          const terminalBad =
            detail.status === 'FAILED' ||
            detail.status === 'CANCELLED' ||
            detail.status === 'ABORTED' ||
            detail.statusReason === 'FINAL_BUILD_FAILED' ||
            detail.statusReason === 'ALL_TASKS_FAILED' ||
            detail.statusReason === 'REVIEWER_FAILED';
          const hasFailureSignal = buildFailed || testsFailed || reviewerBad || terminalBad;
          return hasFailureSignal ? <RunFailureSummary detail={detail} report={report} /> : null;
        })()}

      {/* File tree + diff + side panel (three-pane on desktop, stacked
          on tablet/mobile). The right side panel surfaces the operator
          views (Plan / Log / Preview) that the v2 RunDetailTabs used to
          host — for in-flight runs it streams SSE; for AWAITING_APPROVAL
          it renders the inline plan-gate so users hitting the URL
          directly can approve without bouncing back to Compose; for
          terminal-OK runs it embeds the preview iframe. alignItems:
          stretch keeps the three columns the same height. */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(220px, 260px) minmax(0, 1fr) minmax(440px, 520px)',
          gap: 16,
          alignItems: 'stretch',
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
            // 2026-05-08 — was a flat <ul> of f.path strings; with 200+ changed
            // files (typical for swebench/programbench runs that pull a full
            // testbed) the list became unreadable. The standalone FileTree
            // component already builds a folder hierarchy + ADDED/MODIFIED
            // chips + bubbled-up dir status dots — reuse it instead of
            // re-implementing here. Mapping is trivial: every entry is a
            // file (no directories in changedFiles).
            <div style={{padding: '0 4px'}}>
              <FileTree
                files={changedFiles.map((f) => ({
                  path: f.path,
                  isDirectory: false,
                  status: f.status,
                }))}
                onSelectFile={(p) => setActive(p)}
                selectedFile={active ?? undefined}
              />
            </div>
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
                fontFamily: 'var(--v3-font-mono)',
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
              {/* 2026-05-07 — Diff / Full toggle restoring parity with v2
                  RunDetailTabs Code tab. */}
              {diff && (
                <div
                  role='radiogroup'
                  aria-label='File viewer mode'
                  style={{
                    display: 'inline-flex',
                    border: '1px solid var(--v3-border)',
                    borderRadius: 999,
                    overflow: 'hidden',
                    background: 'var(--v3-bg)',
                  }}
                >
                  {(['diff', 'full'] as const).map((m) => {
                    const active = viewerMode === m;
                    return (
                      <button
                        key={m}
                        role='radio'
                        aria-checked={active}
                        type='button'
                        onClick={() => setViewerMode(m)}
                        title={m === 'diff' ? 'Show only changed lines' : 'Show full file'}
                        style={{
                          padding: '2px 10px',
                          fontSize: 11,
                          fontWeight: 600,
                          fontFamily: 'inherit',
                          background: active
                            ? 'color-mix(in srgb, var(--v3-brand) 18%, transparent)'
                            : 'transparent',
                          color: active
                            ? 'var(--accent-blue-text, var(--v3-brand))'
                            : 'var(--v3-text-muted)',
                          border: 'none',
                          cursor: 'pointer',
                        }}
                      >
                        {m === 'diff' ? 'Diff' : 'Full'}
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          )}
          {diffLoading && (
            <div style={{padding: 16, fontSize: 12, color: 'var(--v3-text-subtle)'}}>
              Loading diff…
            </div>
          )}
          {!diffLoading && diff && (
            // 2026-05-05 — `position: relative` + child `position: absolute; inset: 0`
            // so the diff editor fills this box without relying on a height-100%
            // chain. flex: 1 here lets us take all remaining vertical space inside
            // the right-pane <section>, which (thanks to the grid-level
            // alignItems: stretch) is sized to match the sidebar.
            <div
              style={{
                flex: 1,
                minHeight: 0,
                position: 'relative',
              }}
            >
              <MonacoSafeMount
                fallback={
                  <div style={{padding: 16, fontSize: 12, color: 'var(--v3-text-subtle)'}}>
                    Diff viewer hit a transient render error — pick another file or refresh to
                    retry.
                  </div>
                }
              >
                {/*
                 * absolute-fill child of the relatively-positioned parent so
                 * SafeDiffEditor's `height: 100%` resolves against a known box.
                 *
                 * 2026-05-05 — Use SafeDiffEditor instead of bare @monaco-editor/react
                 * <DiffEditor>. SafeDiffEditor passes keepCurrent*Model and skips
                 * model.dispose() to eliminate the "TextModel got disposed before
                 * DiffEditorWidget model got reset" race at the source.
                 *
                 * Still NO `key` on the editor — prop changes (file switch) flow through
                 * setValue / setModelLanguage on the existing widget; we only let it
                 * unmount on full page unmount, not on prop change.
                 */}
                <div style={{position: 'absolute', inset: 0}}>
                  {viewerMode === 'diff' ? (
                    <SafeDiffEditor
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
                  ) : (
                    <MonacoEditor
                      language={getMonacoLanguage(diff.path)}
                      value={diff.head}
                      theme={GEIST_THEME_NAME}
                      options={{
                        readOnly: true,
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
                        guides: {indentation: true, bracketPairs: true},
                        padding: {top: 8},
                      }}
                    />
                  )}
                </div>
              </MonacoSafeMount>
            </div>
          )}
          {!loading && !active && !diffLoading && (
            <div style={{padding: 16, fontSize: 12, color: 'var(--v3-text-subtle)'}}>
              Select a changed file from the left to view its diff.
            </div>
          )}
        </section>

        <RunSidePanel
          runId={runId}
          detail={detail}
          onRefetch={() => {
            // Approve / cancel from PlanCard inside the panel — refetch the run
            // so the next render swaps the gate for the live task list.
            getRun(runId)
              .then((d) => setDetail(d))
              .catch(() => undefined);
          }}
        />
      </div>

      {/* Tablet/mobile collapse: stack files + diff, then full-width side panel. */}
      <style>{`
        @media (max-width: 1240px) {
          .v3-rundetail-grid {
            grid-template-columns: minmax(200px, 240px) 1fr !important;
          }
          .v3-rundetail-grid > aside:last-child {
            grid-column: 1 / -1;
          }
        }
        @media (max-width: 768px) {
          .v3-rundetail-grid {
            grid-template-columns: 1fr !important;
          }
        }
      `}</style>
    </div>
  );
}

