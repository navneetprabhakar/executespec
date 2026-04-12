import { useState, useEffect } from 'react';
import { ChevronDown, ChevronRight, Share2, Check, Shield, AlertTriangle, FileCode2, Download } from 'lucide-react';
import { getValidationReport, shareValidationReport, downloadValidationReportPdf, type ValidationReportData, type ReportIssue } from '@/lib/api';
import { MarkdownContent } from '@/components/shared/MarkdownContent';
import { useUiStore } from '@/stores/useUiStore';
import { useSseStore } from '@/stores/useSseStore';
import { cn } from '@/lib/utils';

interface ValidationReportPanelProps {
  runId: string;
  status: string;
}

const SEVERITY_CONFIG: Record<string, { color: string; label: string }> = {
  WOULD_BREAK_BUILD: { color: 'bg-red-500/20 text-accent-red border-red-500/30', label: 'Build breaker' },
  WOULD_CAUSE_BUG: { color: 'bg-orange-500/20 text-accent-amber border-orange-500/30', label: 'Bug risk' },
  WOULD_FAIL_REVIEW: { color: 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30', label: 'Review issue' },
  WOULD_FAIL_LINT: { color: 'bg-[var(--gray-200)] text-secondary border-[var(--border-default)]', label: 'Lint issue' },
};

const FETCH_STATUSES = new Set(['COMPLETE', 'PARTIAL', 'FAILED']);

export default function ValidationReportPanel({ runId, status }: ValidationReportPanelProps) {
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

  const RESULT_CONFIG: Record<string, { color: string; label: string }> = {
    PASSED: { color: 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30', label: 'Passed' },
    PASSED_WITH_FIXES: { color: 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30', label: 'Passed with fixes' },
    NEEDS_ATTENTION: { color: 'bg-amber-500/20 text-accent-amber border-amber-500/30', label: 'Needs attention' },
    FAILED: { color: 'bg-red-500/20 text-accent-red border-red-500/30', label: 'Failed' },
  };

  const resultCfg = RESULT_CONFIG[report.pipelineResult] ?? RESULT_CONFIG.PASSED;

  const summaryParts: string[] = [];
  summaryParts.push(`${report.filesGenerated} file${report.filesGenerated !== 1 ? 's' : ''}`);
  if (report.languages.length > 0) summaryParts.push(report.languages.join(', '));
  if (report.pipelineResult === 'NEEDS_ATTENTION') {
    summaryParts.push(`${report.issuesCaught} unresolved issue${report.issuesCaught !== 1 ? 's' : ''}`);
  } else if (report.issuesCaught > 0) {
    summaryParts.push(`${report.issuesCaught} issue${report.issuesCaught !== 1 ? 's' : ''} caught & fixed`);
  } else {
    summaryParts.push('Clean run, all checks passed');
  }

  const score = typeof report.accuracyScore === 'number' ? report.accuracyScore : null;
  const grade = report.accuracyGrade || null;
  const scoreColor = score == null
    ? 'text-muted border-[var(--border-default)] bg-[var(--gray-200)]'
    : score >= 90 ? 'text-emerald-400 border-emerald-500/30 bg-emerald-500/10'
    : score >= 75 ? 'text-accent-amber border-amber-500/30 bg-amber-500/10'
    : 'text-accent-red border-red-500/30 bg-red-500/10';

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
      {/* Collapsed header */}
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
        <Shield className="h-3.5 w-3.5 text-emerald-400 flex-shrink-0" />
        <span className="text-xs font-medium text-primary">Validation Report</span>
        <span className={cn('text-[10px] px-1.5 py-0.5 rounded border', resultCfg.color)}>
          {resultCfg.label}
        </span>
        {score != null && (
          <span className={cn('text-[10px] font-mono px-1.5 py-0.5 rounded border tabular-nums', scoreColor)}>
            {score}/100{grade ? ` · ${grade}` : ''}
          </span>
        )}
        {report.generatedAfterImprovements && (
          <span className="text-[10px] px-1.5 py-0.5 rounded border bg-accent-blue/20 text-accent-blue border-accent-blue/30">
            Re-validated
          </span>
        )}
        <span className="text-[11px] text-muted ml-1 truncate">{summaryParts.join(' · ')}</span>
        <div className="ml-auto flex items-center gap-2" onClick={e => e.stopPropagation()}>
          <button
            onClick={handleDownloadPdf}
            disabled={downloading}
            className="flex items-center gap-1 text-[11px] text-muted hover:text-accent-green transition-colors px-1.5 py-0.5 rounded border border-[var(--border-default)]"
          >
            <Download className="h-3 w-3" />
            {downloading ? '...' : 'PDF'}
          </button>
          <button
            onClick={handleShare}
            disabled={sharing}
            className="flex items-center gap-1 text-[11px] text-muted hover:text-accent-green transition-colors px-1.5 py-0.5 rounded border border-[var(--border-default)]"
          >
            {copied ? <Check className="h-3 w-3" /> : <Share2 className="h-3 w-3" />}
            {copied ? 'Copied' : 'Share'}
          </button>
        </div>
      </div>

      {/* Expanded content */}
      {expanded && (
        <div className="border-t border-[var(--border-default)] max-h-[600px] overflow-y-auto">
          <div className="px-4 py-3 space-y-3 text-[12px] leading-relaxed">
            {/* Accuracy + counters grid */}
            {(score != null || report.issuesCaught > 0) && (
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                {score != null && (
                  <div className={cn('rounded-sm border px-2 py-1.5', scoreColor)}>
                    <div className="text-[10px] uppercase tracking-wide opacity-70">Accuracy</div>
                    <div className="text-sm font-mono font-semibold tabular-nums">
                      {score}<span className="text-[10px] opacity-60">/100</span>
                      {grade && <span className="ml-1 text-[11px]">· {grade}</span>}
                    </div>
                  </div>
                )}
                {report.buildBreakers > 0 && (
                  <div className={cn('rounded-sm border px-2 py-1.5', SEVERITY_CONFIG.WOULD_BREAK_BUILD.color)}>
                    <div className="text-[10px] uppercase tracking-wide opacity-70">Build breakers</div>
                    <div className="text-sm font-mono tabular-nums">{report.buildBreakers}</div>
                  </div>
                )}
                {report.productionBugs > 0 && (
                  <div className={cn('rounded-sm border px-2 py-1.5', SEVERITY_CONFIG.WOULD_CAUSE_BUG.color)}>
                    <div className="text-[10px] uppercase tracking-wide opacity-70">Bug risks</div>
                    <div className="text-sm font-mono tabular-nums">{report.productionBugs}</div>
                  </div>
                )}
                {report.reviewFailures > 0 && (
                  <div className={cn('rounded-sm border px-2 py-1.5', SEVERITY_CONFIG.WOULD_FAIL_REVIEW.color)}>
                    <div className="text-[10px] uppercase tracking-wide opacity-70">Review</div>
                    <div className="text-sm font-mono tabular-nums">{report.reviewFailures}</div>
                  </div>
                )}
                {report.lintFailures > 0 && (
                  <div className={cn('rounded-sm border px-2 py-1.5', SEVERITY_CONFIG.WOULD_FAIL_LINT.color)}>
                    <div className="text-[10px] uppercase tracking-wide opacity-70">Lint</div>
                    <div className="text-sm font-mono tabular-nums">{report.lintFailures}</div>
                  </div>
                )}
              </div>
            )}

            {/* Markdown report */}
            {report.reportMarkdown && (
              <MarkdownContent content={report.reportMarkdown} />
            )}

            {/* All individual issues */}
            {report.issues && report.issues.length > 0 && (
              <div className="space-y-1.5 pt-2 border-t border-[var(--border-default)]">
                <h4 className="text-[10px] font-semibold uppercase tracking-wide text-secondary">
                  All issues ({report.issues.length})
                </h4>
                <div className="space-y-1">
                  {report.issues.map((issue: ReportIssue, i: number) => {
                    const cfg = SEVERITY_CONFIG[issue.severity] ?? SEVERITY_CONFIG.WOULD_FAIL_REVIEW;
                    return (
                      <div key={i} className="flex items-start gap-2 text-[11px] rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] px-2.5 py-1.5">
                        <AlertTriangle className={cn('h-3 w-3 mt-0.5 flex-shrink-0', cfg.color.split(' ')[1])} />
                        <div className="min-w-0 flex-1 leading-snug">
                          <span className="text-secondary">{issue.description}</span>
                          {issue.filePath && (
                            <span className="flex items-center gap-1 mt-0.5 text-muted font-mono text-[10px]">
                              <FileCode2 className="h-2.5 w-2.5" />
                              {issue.filePath}
                            </span>
                          )}
                          {issue.fix && (
                            <span className="block mt-0.5 text-emerald-400/70">Fix: {issue.fix}</span>
                          )}
                        </div>
                        <span className={cn('text-[9px] px-1.5 py-0.5 rounded border whitespace-nowrap flex-shrink-0', cfg.color)}>
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
              <div className="flex flex-wrap gap-x-3 gap-y-1 pt-2 border-t border-[var(--border-default)]">
                {report.checksPassed.map(check => (
                  <span key={check} className="flex items-center gap-1 text-[11px] text-emerald-400/80">
                    <Check className="h-3 w-3" />
                    {check.replace(/_/g, ' ')}
                  </span>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
