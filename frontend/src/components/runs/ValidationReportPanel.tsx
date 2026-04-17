import { useState, useEffect } from 'react';
import { ChevronDown, ChevronRight, Share2, Check, Shield, AlertTriangle, FileCode2, Download } from 'lucide-react';
import { getValidationReport, shareValidationReport, downloadValidationReportPdf, type ValidationReportData, type ReportIssue } from '@/lib/api';
import { MarkdownContent } from '@/components/shared/MarkdownContent';
import ScoreCard from './ScoreCard';
import SignalBreakdown from './SignalBreakdown';
import { useUiStore } from '@/stores/useUiStore';
import { useSseStore } from '@/stores/useSseStore';
import { cn } from '@/lib/utils';

interface ValidationReportPanelProps {
  runId: string;
  status: string;
  /** v2.5.10 — pipeline_run.status_reason. Surfaces the honest PARTIAL/COMPLETE
   *  narrative (e.g. "CLEAN_WITH_INTERMEDIATE_WARNINGS", "TESTS_FAILED:2_OF_7").
   *  Passed in from the parent which reads RunSummary. */
  statusReason?: string | null;
}

/**
 * Severity → design-system tokens. Build breakers block builds (error);
 * bug risks land in production (partial/orange); review issues are soft
 * (waiting/amber); lint is purely advisory (muted). Matches StatusBadge
 * colour semantics so Validation reads like every other status surface.
 */
const SEVERITY_CONFIG: Record<string, { cls: string; label: string; iconCls: string }> = {
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

const RESULT_CONFIG: Record<string, { cls: string; label: string; shieldCls: string }> = {
  PASSED:            { cls: 'bg-status-success/10 text-status-success border-status-success/30', shieldCls: 'text-status-success', label: 'Passed' },
  PASSED_WITH_FIXES: { cls: 'bg-status-success/10 text-status-success border-status-success/30', shieldCls: 'text-status-success', label: 'Passed with fixes' },
  NEEDS_ATTENTION:   { cls: 'bg-status-waiting/10 text-status-waiting border-status-waiting/30', shieldCls: 'text-status-waiting', label: 'Needs attention' },
  FAILED:            { cls: 'bg-status-error/10 text-status-error border-status-error/30',       shieldCls: 'text-status-error',   label: 'Failed' },
};

/**
 * v2.5.10 — human copy for `pipeline_run.status_reason`. Kept in sync with
 * StatusBadge's tooltip dictionary. Shown inline on the expanded report so
 * users don't have to hover the run-header badge to learn why a PARTIAL is
 * a PARTIAL.
 */
const STATUS_REASON_COPY: Record<string, string> = {
  CLEAN_COMPLETION:                 'Clean completion — every check passed.',
  CLEAN_WITH_INTERMEDIATE_WARNINGS: 'Clean result. An intermediate step warned but the final state is clean.',
  FINAL_BUILD_FAILED:               'The final build did not pass. Code may not run without changes.',
  REVIEWER_UNRESOLVED:              'The reviewer flagged issues that were not fully resolved.',
  REVIEWER_FAILED:                  'The reviewer agent errored out. Run status derived from the final build.',
  ALL_TASKS_FAILED:                 'No worker task completed successfully.',
  LEGACY_UNKNOWN:                   'Pre-v2.5.10 run — detailed signals are unavailable.',
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

export default function ValidationReportPanel({ runId, status, statusReason }: ValidationReportPanelProps) {
  const [report, setReport] = useState<ValidationReportData | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [copied, setCopied] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const addToast = useUiStore(s => s.addToast);

  const reportReady = useSseStore((s) => s.entries).some(e => e.type === 'VALIDATION_REPORT_READY');
  useEffect(() => {
    if (!FETCH_STATUSES.has(status)) return;
    // Fetch immediately if run is terminal (navigated to after stream closed),
    // or when the live VALIDATION_REPORT_READY event arrives during the session.
    getValidationReport(runId).then(r => { if (r) setReport(r); });
  }, [runId, status, reportReady]);

  if (!report) return null;

  const resultCfg = RESULT_CONFIG[report.pipelineResult] ?? RESULT_CONFIG.PASSED;

  // v2.5.10 — verifiedScore (deterministic build+tests+verdict) is primary.
  // accuracyScore (LLM self-estimate) demoted to secondary "Est." chip.
  const primaryScore = typeof report.verifiedScore === 'number'
      ? report.verifiedScore
      : (typeof report.accuracyScore === 'number' ? report.accuracyScore : null);
  const primaryLabel = typeof report.verifiedScore === 'number' ? 'Verified' : 'Accuracy';
  const accuracyScore = typeof report.accuracyScore === 'number' ? report.accuracyScore : null;
  const grade = report.accuracyGrade || null;

  const primaryScoreCls = primaryScore == null
    ? 'text-muted border-[var(--border-default)] bg-[var(--gray-200)]'
    : primaryScore >= 90 ? 'text-status-success border-status-success/30 bg-status-success/10'
    : primaryScore >= 75 ? 'text-status-waiting border-status-waiting/30 bg-status-waiting/10'
    : 'text-status-error border-status-error/30 bg-status-error/10';

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

  const reasonCopy = statusReasonCopy(statusReason);

  const handleShare = async () => {
    setSharing(true);
    try {
      const result = await shareValidationReport(runId);
      await navigator.clipboard.writeText(window.location.origin + result.shareUrl);
      setCopied(true);
      addToast({ type: 'success', message: 'Report link copied to clipboard' });
      setTimeout(() => setCopied(false), 2000);
    } catch (e: any) {
      addToast({ type: 'error', message: e.message || 'Failed to share report' });
    } finally {
      setSharing(false);
    }
  };

  const handleDownloadPdf = async () => {
    setDownloading(true);
    try {
      await downloadValidationReportPdf(runId);
      addToast({ type: 'success', message: 'PDF downloaded' });
    } catch (e: any) {
      addToast({ type: 'error', message: e.message || 'Failed to download PDF' });
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="mx-4 mb-3 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] overflow-hidden">
      {/* ── Header: icon + title + primary score + result chip ─────────── */}
      <div
        role="button"
        tabIndex={0}
        onClick={() => setExpanded(!expanded)}
        onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') setExpanded(!expanded); }}
        className="w-full flex items-center gap-3 px-4 py-3 text-left hover:bg-[var(--gray-200)] transition-colors cursor-pointer"
      >
        {expanded
          ? <ChevronDown className="h-4 w-4 text-secondary flex-shrink-0" />
          : <ChevronRight className="h-4 w-4 text-secondary flex-shrink-0" />
        }
        <Shield className={cn('h-3.5 w-3.5 flex-shrink-0', resultCfg.shieldCls)} />
        <span className="text-xs font-medium text-primary">Validation Report</span>
        <span className={cn('text-[10px] px-1.5 py-0.5 rounded-sm border font-medium', resultCfg.cls)}>
          {resultCfg.label}
        </span>
        {primaryScore != null && (
          <span
            className={cn('text-xs font-mono px-2 py-0.5 rounded-sm border tabular-nums font-medium', primaryScoreCls)}
            title={primaryLabel === 'Verified'
              ? 'Deterministic score: build + tests + reviewer verdict (v2.5.10)'
              : 'LLM-estimated spec coverage (legacy pre-V106)'}
          >
            <span className="opacity-70 mr-1">{primaryLabel === 'Verified' ? 'Ver' : 'Acc'}</span>
            {primaryScore}<span className="opacity-60">/100</span>
          </span>
        )}
        {report.generatedAfterImprovements && (
          <span className="text-[10px] px-1.5 py-0.5 rounded-sm border bg-accent-blue/10 text-accent-blue border-accent-blue/30">
            Re-validated
          </span>
        )}
        <span className="text-xs text-muted ml-1 truncate hidden md:inline">
          {summaryParts.join(' · ')}
        </span>
        <div className="ml-auto flex items-center gap-2" onClick={e => e.stopPropagation()}>
          <button
            onClick={handleDownloadPdf}
            disabled={downloading}
            className="flex items-center gap-1 text-[11px] text-secondary hover:text-primary transition-colors px-1.5 py-0.5 rounded-sm border border-[var(--border-default)] hover:border-[var(--border-hover)]"
          >
            <Download className="h-3 w-3" />
            {downloading ? '...' : 'PDF'}
          </button>
          <button
            onClick={handleShare}
            disabled={sharing}
            className="flex items-center gap-1 text-[11px] text-secondary hover:text-primary transition-colors px-1.5 py-0.5 rounded-sm border border-[var(--border-default)] hover:border-[var(--border-hover)]"
          >
            {copied ? <Check className="h-3 w-3" /> : <Share2 className="h-3 w-3" />}
            {copied ? 'Copied' : 'Share'}
          </button>
        </div>
      </div>

      {/* ── Narrative line for status_reason — v2.5.10 honest-PARTIAL copy ── */}
      {reasonCopy && (
        <div className="px-4 pb-2 -mt-1">
          <p className="text-xs text-secondary leading-relaxed">
            {reasonCopy}
          </p>
        </div>
      )}

      {/* ── Signal breakdown chip: always visible ─────────────────────── */}
      <div className="px-4 pb-2 -mt-1">
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
        <div className="border-t border-[var(--border-default)] max-h-[600px] overflow-y-auto bg-[var(--gray-100)]">
          <div className="px-4 py-3 space-y-3 text-xs leading-relaxed">

            {/* Issue counter grid — single-dimension semantic */}
            {(accuracyScore != null || report.issuesCaught > 0) && (
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                {accuracyScore != null && primaryLabel === 'Verified' && (
                  <div className={cn('rounded-sm border px-2 py-1.5', 'border-[var(--border-default)] bg-[var(--gray-200)] text-muted')}>
                    <div className="text-[10px] uppercase tracking-wide text-muted">LLM estimate</div>
                    <div className="text-sm font-mono font-semibold tabular-nums text-secondary">
                      {accuracyScore}<span className="text-[10px] text-muted">/100</span>
                      {grade && <span className="ml-1 text-[11px] text-muted">· {grade}</span>}
                    </div>
                  </div>
                )}
                {accuracyScore != null && primaryLabel !== 'Verified' && (
                  <div className={cn('rounded-sm border px-2 py-1.5', primaryScoreCls)}>
                    <div className="text-[10px] uppercase tracking-wide opacity-70">Accuracy</div>
                    <div className="text-sm font-mono font-semibold tabular-nums">
                      {accuracyScore}<span className="text-[10px] opacity-60">/100</span>
                      {grade && <span className="ml-1 text-[11px]">· {grade}</span>}
                    </div>
                  </div>
                )}
                {report.buildBreakers > 0 && (
                  <div className={cn('rounded-sm border px-2 py-1.5', SEVERITY_CONFIG.WOULD_BREAK_BUILD.cls)}>
                    <div className="text-[10px] uppercase tracking-wide opacity-70">Build breakers</div>
                    <div className="text-sm font-mono tabular-nums">{report.buildBreakers}</div>
                  </div>
                )}
                {report.productionBugs > 0 && (
                  <div className={cn('rounded-sm border px-2 py-1.5', SEVERITY_CONFIG.WOULD_CAUSE_BUG.cls)}>
                    <div className="text-[10px] uppercase tracking-wide opacity-70">Bug risks</div>
                    <div className="text-sm font-mono tabular-nums">{report.productionBugs}</div>
                  </div>
                )}
                {report.reviewFailures > 0 && (
                  <div className={cn('rounded-sm border px-2 py-1.5', SEVERITY_CONFIG.WOULD_FAIL_REVIEW.cls)}>
                    <div className="text-[10px] uppercase tracking-wide opacity-70">Review</div>
                    <div className="text-sm font-mono tabular-nums">{report.reviewFailures}</div>
                  </div>
                )}
                {report.lintFailures > 0 && (
                  <div className={cn('rounded-sm border px-2 py-1.5', SEVERITY_CONFIG.WOULD_FAIL_LINT.cls)}>
                    <div className="text-[10px] uppercase tracking-wide opacity-70">Lint</div>
                    <div className="text-sm font-mono tabular-nums">{report.lintFailures}</div>
                  </div>
                )}
              </div>
            )}

            {/* Six-dimension score card (Pilot R6) */}
            <ScoreCard report={report} />

            {/* Reviewer markdown narrative */}
            {report.reportMarkdown && (
              <MarkdownContent content={report.reportMarkdown} />
            )}

            {/* All individual issues */}
            {report.issues && report.issues.length > 0 && (
              <div className="space-y-1.5 pt-2 border-t border-[var(--border-default)]">
                <h4 className="text-[10px] font-mono font-medium uppercase tracking-wider text-secondary">
                  All issues ({report.issues.length})
                </h4>
                <div className="space-y-1">
                  {report.issues.map((issue: ReportIssue, i: number) => {
                    const cfg = SEVERITY_CONFIG[issue.severity] ?? SEVERITY_CONFIG.WOULD_FAIL_REVIEW;
                    return (
                      <div key={i} className="flex items-start gap-2 text-xs rounded-sm bg-[var(--gray-200)] border border-[var(--border-default)] px-2.5 py-1.5">
                        <AlertTriangle className={cn('h-3 w-3 mt-0.5 flex-shrink-0', cfg.iconCls)} />
                        <div className="min-w-0 flex-1 leading-snug">
                          <span className="text-primary">{issue.description}</span>
                          {issue.filePath && (
                            <span className="flex items-center gap-1 mt-0.5 text-muted font-mono text-[10px]">
                              <FileCode2 className="h-2.5 w-2.5" />
                              {issue.filePath}
                            </span>
                          )}
                          {issue.fix && (
                            <span className="block mt-0.5 text-status-success/80">Fix: {issue.fix}</span>
                          )}
                        </div>
                        <span className={cn('text-[10px] px-1.5 py-0.5 rounded-sm border whitespace-nowrap flex-shrink-0', cfg.cls)}>
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
              <div className="pt-2 border-t border-[var(--border-default)]">
                <h4 className="text-[10px] font-mono font-medium uppercase tracking-wider text-secondary mb-1.5">
                  Checks passed ({report.checksPassed.length})
                </h4>
                <div className="flex flex-wrap gap-x-3 gap-y-1">
                  {report.checksPassed.map(check => (
                    <span key={check} className="flex items-center gap-1 text-[11px] text-status-success/80">
                      <Check className="h-3 w-3" />
                      {prettyCheckName(check)}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
