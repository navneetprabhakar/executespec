import {useState, useEffect} from 'react';
import {
  ChevronDown,
  ChevronRight,
  Share2,
  Check,
  Shield,
  AlertTriangle,
  FileCode2,
  Download,
  RefreshCw,
  Play,
} from 'lucide-react';
import {
  getValidationReport,
  shareValidationReport,
  downloadValidationReportPdf,
  retryValidationReport,
  type ValidationReportData,
  type ReportIssue,
} from '@/lib/api';
import {MarkdownContent} from '@/components/shared/MarkdownContent';
import ScoreCard from './ScoreCard';
import ScoreRing from './ScoreRing';
import SignalBreakdown from './SignalBreakdown';
import VerdictPill from './VerdictPill';
import RunStamp from '@/components/project/RunStamp';
import {Glossary} from '@/components/shared/Glossary';
import {useUiStore} from '@/stores/useUiStore';
import {useSseStore} from '@/stores/useSseStore';
import {cn, errMsg} from '@/lib/utils';

interface ValidationReportPanelProps {
  runId: string;
  status: string;
  /** v2.5.10 — pipeline_run.status_reason. Surfaces the honest PARTIAL/COMPLETE
   *  narrative (e.g. "CLEAN_WITH_INTERMEDIATE_WARNINGS", "TESTS_FAILED:2_OF_7").
   *  Passed in from the parent which reads RunSummary. */
  statusReason?: string | null;
  /** v2.6.4 — RunStamp metadata. When provided, the expanded panel renders a
   *  shareable 1-line stamp + share-link / embed button at the bottom (per
   *  UX & Wireframe Report §6.3 #3). All fields optional — the stamp omits
   *  anything missing rather than rendering em-dashes. */
  stampMeta?: {
    runStartedAt?: string | null;
    runCompletedAt?: string | null;
    tasksTotal?: number | null;
    tasksComplete?: number | null;
    provider?: string | null;
  };
  /** R-11 (UX audit, 2026-05-03) — retry-failed-tasks affordance promoted
   *  into the report header. Pre-fix this button lived on the run-level
   *  toolbar, far from the failure signal. Now the parent passes a callback
   *  + count and the report header renders the button next to Regenerate
   *  when there's something to retry. Renders nothing when count <= 0. */
  retryFailed?: () => void | Promise<void>;
  retryFailedCount?: number;
  retryFailedBusy?: boolean;
}

/**
 * Severity → design-system tokens. Build breakers block builds (error);
 * bug risks land in production (partial/orange); review issues are soft
 * (waiting/amber); lint is purely advisory (muted). Matches StatusBadge
 * colour semantics so Validation reads like every other status surface.
 */
const SEVERITY_CONFIG: Record<string, {cls: string; label: string; iconCls: string}> = {
  WOULD_BREAK_BUILD: {
    cls: 'bg-status-error/10 text-status-error border-status-error/30',
    iconCls: 'text-status-error',
    label: 'Build breaker',
  },
  WOULD_CAUSE_BUG: {
    cls: 'bg-status-partial/10 text-status-partial border-status-partial/30',
    iconCls: 'text-status-partial',
    label: 'Bug risk',
  },
  WOULD_FAIL_REVIEW: {
    cls: 'bg-status-waiting/10 text-status-waiting border-status-waiting/30',
    iconCls: 'text-status-waiting',
    label: 'Review issue',
  },
  WOULD_FAIL_LINT: {
    cls: 'bg-[var(--gray-200)] text-muted border-[var(--border-default)]',
    iconCls: 'text-muted',
    label: 'Lint issue',
  },
};

const RESULT_CONFIG: Record<string, {cls: string; label: string; shieldCls: string}> = {
  PASSED: {
    cls: 'bg-status-success/10 text-status-success border-status-success/30',
    shieldCls: 'text-status-success',
    label: 'Passed',
  },
  PASSED_WITH_FIXES: {
    cls: 'bg-status-success/10 text-status-success border-status-success/30',
    shieldCls: 'text-status-success',
    label: 'Passed with fixes',
  },
  NEEDS_ATTENTION: {
    cls: 'bg-status-waiting/10 text-status-waiting border-status-waiting/30',
    shieldCls: 'text-status-waiting',
    label: 'Needs attention',
  },
  FAILED: {
    cls: 'bg-status-error/10 text-status-error border-status-error/30',
    shieldCls: 'text-status-error',
    label: 'Has issues',
  },
};

/**
 * v2.5.10 — human copy for `pipeline_run.status_reason`. Kept in sync with
 * StatusBadge's tooltip dictionary. Shown inline on the expanded report so
 * users don't have to hover the run-header badge to learn why a PARTIAL is
 * a PARTIAL.
 */
const STATUS_REASON_COPY: Record<string, string> = {
  CLEAN_COMPLETION: 'Clean completion — every check passed.',
  CLEAN_WITH_INTERMEDIATE_WARNINGS:
    'Clean result. An intermediate step warned but the final state is clean.',
  FINAL_BUILD_FAILED: 'The final build did not pass. Code may not run without changes.',
  REVIEWER_UNRESOLVED: 'The reviewer flagged issues that were not fully resolved.',
  REVIEWER_FAILED: 'The reviewer agent errored out. Run status derived from the final build.',
  REVIEWER_INTERRUPTED_CODE_PRESERVED:
    'All tasks finished, but the reviewer pass was interrupted by a server restart. Code and workspace are intact — click Regenerate to re-run the reviewer + scoring.',
  ALL_TASKS_FAILED: 'No worker task completed successfully.',
  LEGACY_UNKNOWN: 'Pre-v2.5.10 run — detailed signals are unavailable.',
};

function statusReasonCopy(raw?: string | null): string | null {
  if (!raw) return null;
  if (raw.startsWith('TESTS_FAILED:')) {
    const m = raw.match(/TESTS_FAILED:(\d+)_OF_(\d+)/);
    if (m) return `${m[1]} of ${m[2]} test cases failed.`;
    return 'Some test cases failed.';
  }
  return STATUS_REASON_COPY[raw] ?? null;
}

const FETCH_STATUSES = new Set(['COMPLETE', 'PARTIAL', 'FAILED']);

/**
 * Human label for a checksPassed enum. Backend emits SCREAMING_SNAKE; the UI
 * wants "Prompt defense", not "PROMPT DEFENSE".
 */
function prettyCheckName(raw: string): string {
  const spaced = raw.replace(/_/g, ' ').toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export default function ValidationReportPanel({
  runId,
  status,
  statusReason,
  stampMeta,
  retryFailed,
  retryFailedCount = 0,
  retryFailedBusy = false,
}: ValidationReportPanelProps) {
  const [report, setReport] = useState<ValidationReportData | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [copied, setCopied] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const addToast = useUiStore((s) => s.addToast);

  const reportReady = useSseStore((s) => s.entries).some(
    (e) => e.type === 'VALIDATION_REPORT_READY',
  );
  useEffect(() => {
    if (!FETCH_STATUSES.has(status)) return;
    // Fetch immediately if run is terminal (navigated to after stream closed),
    // or when the live VALIDATION_REPORT_READY event arrives during the session.
    getValidationReport(runId).then((r) => {
      if (r) setReport(r);
    });
  }, [runId, status, reportReady]);

  // Regenerate the validation report. Backend runs async (202) — poll a few
  // times because there's no dedicated SSE event for retry completion.
  const handleRetry = async () => {
    setRetrying(true);
    try {
      await retryValidationReport(runId);
      addToast({type: 'info', message: 'Regenerating validation report…'});
      // Poll for up to 30s (six 5-s ticks). First successful non-null result wins.
      for (let i = 0; i < 6; i++) {
        await new Promise((r) => setTimeout(r, 5000));
        const fresh = await getValidationReport(runId);
        if (fresh) {
          setReport(fresh);
          addToast({type: 'success', message: 'Validation report ready'});
          break;
        }
      }
    } catch (e) {
      addToast({type: 'error', message: errMsg(e) || 'Retry failed'});
    } finally {
      setRetrying(false);
    }
  };

  // Terminal run but no report yet → surface a recovery affordance instead of
  // rendering nothing. Old behaviour silently hid the panel, so users had no
  // idea the report had crashed server-side.
  if (!report) {
    if (!FETCH_STATUSES.has(status)) return null;
    return (
      <div className='mx-4 mb-3 rounded-sm border border-status-waiting/30 bg-status-waiting/10 px-4 py-3 flex items-center gap-3'>
        <AlertTriangle className='h-4 w-4 text-status-waiting flex-shrink-0' />
        <div className='flex-1 min-w-0'>
          <div className='text-xs font-medium text-primary'>Validation report not generated</div>
          <div className='text-[11px] text-muted mt-0.5'>
            The background report generator crashed or hasn't finished. Click Retry to regenerate
            from persisted task state.
          </div>
        </div>
        <button
          type='button'
          onClick={handleRetry}
          disabled={retrying}
          className='flex items-center gap-1.5 text-[11px] font-medium px-2.5 py-1 rounded-sm border border-status-waiting/40 bg-status-waiting/10 text-status-waiting hover:bg-status-waiting/20 disabled:opacity-60 disabled:cursor-not-allowed'
        >
          <RefreshCw className={cn('h-3 w-3', retrying && 'animate-spin')} />
          {retrying ? 'Retrying…' : 'Retry'}
        </button>
      </div>
    );
  }

  const resultCfg = RESULT_CONFIG[report.pipelineResult] ?? RESULT_CONFIG.PASSED;

  // 2026-05-10 — single-score model. The dual "Verified" + "LLM estimate"
  // display was confusing: one score is deterministic (V106 build+tests+
  // verdict math), the other is an LLM self-rating that can disagree by
  // 30+ points on the same run. We now show ONE number — verifiedScore
  // when available, else accuracyScore as a legacy fallback for pre-V106
  // rows. The secondary "Est. N" chip + "LLM estimate" tile are dropped
  // (see further down in the panel body).
  const primaryScore =
    typeof report.verifiedScore === 'number'
      ? report.verifiedScore
      : typeof report.accuracyScore === 'number'
        ? report.accuracyScore
        : null;
  const grade = report.accuracyGrade || null;

  // primaryScoreCls retired with the header score-chip drop in P-19/R-03.
  // The expanded ScoreCard hero still uses its own colouring (ScoreRing),
  // so no fallback class needed here.

  const summaryParts: string[] = [];
  summaryParts.push(`${report.filesGenerated} file${report.filesGenerated !== 1 ? 's' : ''}`);
  if (report.languages.length > 0) summaryParts.push(report.languages.join(', '));
  if (report.pipelineResult === 'NEEDS_ATTENTION' || report.pipelineResult === 'FAILED') {
    summaryParts.push(`${report.issuesCaught} unresolved`);
  } else if (report.issuesCaught > 0) {
    summaryParts.push(`${report.issuesCaught} fixed`);
  } else {
    summaryParts.push('Clean run');
  }

  // R-09 (UX audit, 2026-05-04) — the v2.5.14 `suppressReason` special case
  // is gone now that ValidationReportService.reconcileWithRunStatus
  // (backend) ensures pipelineResult agrees with status_reason at write
  // time, and V171 backfilled historical drift. The two signals are now
  // consistent so the narrative line can render unconditionally.
  const reasonCopy = statusReasonCopy(statusReason);

  const handleShare = async () => {
    setSharing(true);
    try {
      const result = await shareValidationReport(runId);
      await navigator.clipboard.writeText(window.location.origin + result.shareUrl);
      setCopied(true);
      addToast({type: 'success', message: 'Report link copied to clipboard'});
      setTimeout(() => setCopied(false), 2000);
    } catch (e) {
      addToast({type: 'error', message: errMsg(e) || 'Failed to share report'});
    } finally {
      setSharing(false);
    }
  };

  const handleDownloadPdf = async () => {
    setDownloading(true);
    try {
      await downloadValidationReportPdf(runId);
      addToast({type: 'success', message: 'PDF downloaded'});
    } catch (e) {
      addToast({type: 'error', message: errMsg(e) || 'Failed to download PDF'});
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className='mx-4 mb-3 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] overflow-hidden'>
      {/* ── Header: icon + title + primary score + result chip ─────────── */}
      <div
        role='button'
        tabIndex={0}
        onClick={() => setExpanded(!expanded)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') setExpanded(!expanded);
        }}
        className='w-full flex items-center gap-3 px-4 py-3 text-left hover:bg-[var(--gray-200)] transition-colors cursor-pointer'
      >
        {expanded ? (
          <ChevronDown className='h-4 w-4 text-secondary flex-shrink-0' />
        ) : (
          <ChevronRight className='h-4 w-4 text-secondary flex-shrink-0' />
        )}
        <Shield className={cn('h-3.5 w-3.5 flex-shrink-0', resultCfg.shieldCls)} />
        <span className='text-xs font-medium text-primary'>Validation Report</span>
        {/* Plan 1 (this-week #3): verdict leads, numeric score is secondary
            disclosure. The {resultCfg.label} chip kept for back-compat
            (CLEAN/FIXED/UNRESOLVED palette is familiar) but visually demoted. */}
        <VerdictPill
          size='lg'
          finalBuildPassed={report.finalBuildPassed}
          testsPassed={report.testsPassed}
          testsTotal={report.testsTotal}
          reviewerVerdict={report.reviewerVerdict}
          verifiedScore={report.verifiedScore}
          statusReason={statusReason}
        />
        {/* P-19 / R-03 (UX audit, 2026-05-03): pre-fix this header carried
            FOUR overlapping status signals — VerdictPill, the {resultCfg.label}
            chip ("Has issues" / "Passed with fixes" / etc.), the
            primary-score chip (Ver 92/100), and the textual summaryParts.
            VerdictPill already encodes the verdict lineage (Shippable /
            Needs review / Blocked) with the score it's derived from in its
            tooltip; the additional chips were each saying the same thing
            with different vocabulary. Dropped both the resultCfg.label chip
            and the primary-score chip. The numeric score still lives in the
            expanded ScoreCard hero (ScoreRing) for users who want it. */}
        {report.generatedAfterImprovements && (
          <span className='text-[10px] px-1.5 py-0.5 rounded-sm border bg-accent-blue/10 text-accent-blue border-accent-blue/30'>
            Re-validated
          </span>
        )}
        <span className='text-xs text-muted ml-1 truncate hidden md:inline'>
          {summaryParts.join(' · ')}
        </span>
        <div className='ml-auto flex items-center gap-2' onClick={(e) => e.stopPropagation()}>
          {/* R-11 — retry-failed-tasks button. Shows up only when the parent
              has both a callback and a non-zero count (i.e. RunDetail derived
              the failedTasks list). Sits left of Regenerate so the failure
              signal (verdict pill / signal breakdown chip) and the retry
              action are in the same horizontal scan. */}
          {retryFailed && retryFailedCount > 0 && (
            <button
              onClick={() => void retryFailed()}
              disabled={retryFailedBusy}
              title={`Retry ${retryFailedCount} failed task${retryFailedCount === 1 ? '' : 's'} in this run`}
              className='flex items-center gap-1 text-[11px] font-medium text-status-partial hover:text-status-partial transition-colors px-2 py-0.5 rounded-sm border border-status-partial/40 bg-status-partial/10 hover:bg-status-partial/20 disabled:opacity-60 disabled:cursor-not-allowed'
            >
              <Play className={cn('h-3 w-3', retryFailedBusy && 'animate-pulse')} />
              {retryFailedBusy ? 'Retrying…' : `Retry failed (${retryFailedCount})`}
            </button>
          )}
          <button
            onClick={handleRetry}
            disabled={retrying}
            title='Regenerate the validation report from persisted run state. Useful after scoring fixes land.'
            className='flex items-center gap-1 text-[11px] text-secondary hover:text-primary transition-colors px-1.5 py-0.5 rounded-sm border border-[var(--border-default)] hover:border-[var(--border-hover)] disabled:opacity-60 disabled:cursor-not-allowed'
          >
            <RefreshCw className={cn('h-3 w-3', retrying && 'animate-spin')} />
            {retrying ? '...' : 'Regenerate'}
          </button>
          <button
            onClick={handleDownloadPdf}
            disabled={downloading}
            className='flex items-center gap-1 text-[11px] text-secondary hover:text-primary transition-colors px-1.5 py-0.5 rounded-sm border border-[var(--border-default)] hover:border-[var(--border-hover)]'
          >
            <Download className='h-3 w-3' />
            {downloading ? '...' : 'PDF'}
          </button>
          <button
            onClick={handleShare}
            disabled={sharing}
            className='flex items-center gap-1 text-[11px] text-secondary hover:text-primary transition-colors px-1.5 py-0.5 rounded-sm border border-[var(--border-default)] hover:border-[var(--border-hover)]'
          >
            {copied ? <Check className='h-3 w-3' /> : <Share2 className='h-3 w-3' />}
            {copied ? 'Copied' : 'Share'}
          </button>
        </div>
      </div>

      {/* ── Narrative line for status_reason — v2.5.10 honest-PARTIAL copy ── */}
      {reasonCopy && (
        <div className='px-4 pb-2 -mt-1'>
          <p className='text-xs text-secondary leading-relaxed'>{reasonCopy}</p>
        </div>
      )}

      {/* ── Signal breakdown chip: always visible ─────────────────────── */}
      <div className='px-4 pb-2 -mt-1'>
        <SignalBreakdown
          finalBuildPassed={report.finalBuildPassed}
          testsPassed={report.testsPassed}
          testsTotal={report.testsTotal}
          reviewerVerdict={report.reviewerVerdict}
          verifiedScore={report.verifiedScore}
          accuracyScore={report.accuracyScore}
        />
      </div>

      {/* ── Expanded content ──────────────────────────────────────────── */}
      {expanded && (
        <div className='border-t border-[var(--border-default)] max-h-[680px] overflow-y-auto bg-[var(--gray-100)]'>
          <div className='px-4 py-4 space-y-4 text-xs leading-relaxed'>
            {/* Hero: ring + signal tiles in a 2-column layout matching the
                executespec.ai tour panel. Ring appears only when we have a
                verified score — LLM-only scoring keeps the legacy chip view. */}
            {primaryScore != null && (
              <div className='flex flex-col sm:flex-row items-start sm:items-center gap-5 pb-3 border-b border-[var(--border-default)]'>
                <ScoreRing score={primaryScore} label='score / 100' size={132} />
                <div className='grid grid-cols-2 gap-2 flex-1 w-full'>
                  {report.finalBuildPassed != null && (
                    <div
                      className={cn(
                        'rounded-sm border px-3 py-2',
                        report.finalBuildPassed
                          ? 'border-status-success/30 bg-status-success/10'
                          : 'border-status-error/30 bg-status-error/10',
                      )}
                    >
                      <div
                        className={cn(
                          'text-[10px] uppercase tracking-wide font-mono',
                          report.finalBuildPassed ? 'text-status-success' : 'text-status-error',
                        )}
                      >
                        Build
                      </div>
                      <div className='text-sm font-medium mt-0.5 text-primary'>
                        {report.finalBuildPassed ? 'Passed' : 'Failed'}
                      </div>
                    </div>
                  )}
                  {report.testsTotal != null && (
                    <div
                      className={cn(
                        'rounded-sm border px-3 py-2',
                        report.testsTotal === 0
                          ? 'border-[var(--border-default)] bg-[var(--gray-200)]'
                          : (report.testsPassed ?? 0) === report.testsTotal
                            ? 'border-status-success/30 bg-status-success/10'
                            : (report.testsPassed ?? 0) >= report.testsTotal * 0.8
                              ? 'border-status-waiting/30 bg-status-waiting/10'
                              : 'border-status-error/30 bg-status-error/10',
                      )}
                    >
                      <div className='text-[10px] uppercase tracking-wide font-mono text-muted'>
                        Tests
                      </div>
                      <div className='text-sm font-medium mt-0.5 text-primary tabular-nums'>
                        {report.testsTotal === 0
                          ? 'Not run'
                          : `${report.testsPassed ?? 0} / ${report.testsTotal}`}
                      </div>
                    </div>
                  )}
                  {report.reviewerVerdict && (
                    <div
                      className={cn(
                        'rounded-sm border px-3 py-2',
                        report.reviewerVerdict === 'CLEAN' ||
                          report.reviewerVerdict === 'FIXED_ISSUES'
                          ? 'border-status-success/30 bg-status-success/10'
                          : report.reviewerVerdict === 'UNRESOLVED_ISSUES'
                            ? 'border-status-waiting/30 bg-status-waiting/10'
                            : 'border-status-error/30 bg-status-error/10',
                      )}
                    >
                      {/* M-006 (Wave 1) — Glossary on "Reviewer" → wiring-pass tooltip. */}
                      <div className='text-[10px] uppercase tracking-wide font-mono text-muted'>
                        <Glossary term='wiring pass'>Reviewer</Glossary>
                      </div>
                      <div className='text-sm font-medium mt-0.5 text-primary'>
                        {report.reviewerVerdict.replace(/_/g, ' ')}
                      </div>
                    </div>
                  )}
                  {/* 2026-05-10 — secondary "LLM estimate" tile dropped per
                       single-score policy. The deterministic score (in the
                       hero ScoreRing above) is the only number shown now. */}
                </div>
              </div>
            )}

            {/* Issue counter grid — only shown when there are non-zero issues.
                Hero tiles above already cover build/tests/reviewer, so this is
                purely for severity-bucketed issue counts. */}
            {report.issuesCaught > 0 && (
              <div className='grid grid-cols-2 md:grid-cols-4 gap-2'>
                {report.buildBreakers > 0 && (
                  <div
                    className={cn(
                      'rounded-sm border px-3 py-2',
                      SEVERITY_CONFIG.WOULD_BREAK_BUILD.cls,
                    )}
                  >
                    <div className='text-[10px] uppercase tracking-wide font-mono opacity-70'>
                      Build breakers
                    </div>
                    <div className='text-sm font-medium mt-0.5 tabular-nums'>
                      {report.buildBreakers}
                    </div>
                  </div>
                )}
                {report.productionBugs > 0 && (
                  <div
                    className={cn(
                      'rounded-sm border px-3 py-2',
                      SEVERITY_CONFIG.WOULD_CAUSE_BUG.cls,
                    )}
                  >
                    <div className='text-[10px] uppercase tracking-wide font-mono opacity-70'>
                      Bug risks
                    </div>
                    <div className='text-sm font-medium mt-0.5 tabular-nums'>
                      {report.productionBugs}
                    </div>
                  </div>
                )}
                {report.reviewFailures > 0 && (
                  <div
                    className={cn(
                      'rounded-sm border px-3 py-2',
                      SEVERITY_CONFIG.WOULD_FAIL_REVIEW.cls,
                    )}
                  >
                    <div className='text-[10px] uppercase tracking-wide font-mono opacity-70'>
                      Review issues
                    </div>
                    <div className='text-sm font-medium mt-0.5 tabular-nums'>
                      {report.reviewFailures}
                    </div>
                  </div>
                )}
                {report.lintFailures > 0 && (
                  <div
                    className={cn(
                      'rounded-sm border px-3 py-2',
                      SEVERITY_CONFIG.WOULD_FAIL_LINT.cls,
                    )}
                  >
                    <div className='text-[10px] uppercase tracking-wide font-mono opacity-70'>
                      Lint
                    </div>
                    <div className='text-sm font-medium mt-0.5 tabular-nums'>
                      {report.lintFailures}
                    </div>
                  </div>
                )}
              </div>
            )}

            {/* Six-dimension score card (Pilot R6) */}
            <ScoreCard report={report} />

            {/* Reviewer markdown narrative */}
            {report.reportMarkdown && <MarkdownContent content={report.reportMarkdown} />}

            {/* All individual issues */}
            {report.issues && report.issues.length > 0 && (
              <div className='space-y-1.5 pt-2 border-t border-[var(--border-default)]'>
                <h4 className='text-[10px] font-mono font-medium uppercase tracking-wider text-secondary'>
                  All issues ({report.issues.length})
                </h4>
                <div className='space-y-1'>
                  {report.issues.map((issue: ReportIssue, i: number) => {
                    const cfg =
                      SEVERITY_CONFIG[issue.severity] ?? SEVERITY_CONFIG.WOULD_FAIL_REVIEW;
                    return (
                      <div
                        key={i}
                        className='flex items-start gap-2 text-xs rounded-sm bg-[var(--gray-200)] border border-[var(--border-default)] px-2.5 py-1.5'
                      >
                        <AlertTriangle
                          className={cn('h-3 w-3 mt-0.5 flex-shrink-0', cfg.iconCls)}
                        />
                        <div className='min-w-0 flex-1 leading-snug'>
                          <span className='text-primary'>{issue.description}</span>
                          {issue.filePath && (
                            <span className='flex items-center gap-1 mt-0.5 text-muted font-mono text-[10px]'>
                              <FileCode2 className='h-2.5 w-2.5' />
                              {issue.filePath}
                            </span>
                          )}
                          {issue.fix && (
                            <span className='block mt-0.5 text-status-success/80'>
                              Fix: {issue.fix}
                            </span>
                          )}
                        </div>
                        <span
                          className={cn(
                            'text-[10px] px-1.5 py-0.5 rounded-sm border whitespace-nowrap flex-shrink-0',
                            cfg.cls,
                          )}
                        >
                          {cfg.label}
                        </span>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Checks passed */}
            {report.checksPassed.length > 0 && (
              <div className='pt-2 border-t border-[var(--border-default)]'>
                <h4 className='text-[10px] font-mono font-medium uppercase tracking-wider text-secondary mb-1.5'>
                  Checks passed ({report.checksPassed.length})
                </h4>
                <div className='flex flex-wrap gap-x-3 gap-y-1'>
                  {report.checksPassed.map((check) => (
                    <span
                      key={check}
                      className='flex items-center gap-1 text-[11px] text-status-success/80'
                    >
                      <Check className='h-3 w-3' />
                      {prettyCheckName(check)}
                    </span>
                  ))}
                </div>
              </div>
            )}

            {/* v2.6.4 — RunStamp: shareable 1-line stamp + copy/share/embed.
                Per UX & Wireframe Report §6.3 #3 — every COMPLETE run produces
                a free distribution loop. Only render when we have stamp meta
                AND the run is in a terminal state worth bragging about. */}
            {(status === 'COMPLETE' || status === 'PARTIAL') && stampMeta && (
              <div className='pt-2 border-t border-[var(--border-default)]'>
                <h4 className='text-[10px] font-mono font-medium uppercase tracking-wider text-secondary mb-1.5'>
                  Share this run
                </h4>
                <RunStamp
                  runId={runId}
                  durationMs={
                    stampMeta.runStartedAt && stampMeta.runCompletedAt
                      ? new Date(stampMeta.runCompletedAt).getTime() -
                        new Date(stampMeta.runStartedAt).getTime()
                      : null
                  }
                  verifiedScore={report.verifiedScore ?? report.accuracyScore ?? null}
                  tasksTotal={stampMeta.tasksTotal ?? null}
                  tasksComplete={stampMeta.tasksComplete ?? null}
                  provider={stampMeta.provider ?? null}
                />
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
