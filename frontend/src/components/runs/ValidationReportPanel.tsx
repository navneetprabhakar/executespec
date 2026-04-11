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
        <Shield className="h-4 w-4 text-emerald-400 flex-shrink-0" />
        <span className="text-sm font-medium text-primary">Validation Report</span>
        {report.generatedAfterImprovements && (
          <span className="text-[10px] px-1.5 py-0.5 rounded border bg-accent-blue/20 text-accent-blue border-accent-blue/30">
            Re-validated after edits
          </span>
        )}
        <span className="text-xs text-muted ml-1">{summaryParts.join(' · ')}</span>
        <div className="ml-auto flex items-center gap-2" onClick={e => e.stopPropagation()}>
          <button
            onClick={handleDownloadPdf}
            disabled={downloading}
            className="flex items-center gap-1.5 text-xs text-muted hover:text-accent-green transition-colors px-2 py-1 rounded border border-[var(--border-default)] hover:border-[var(--border-default)]"
          >
            <Download className="h-3 w-3" />
            {downloading ? 'Downloading...' : 'PDF'}
          </button>
          <button
            onClick={handleShare}
            disabled={sharing}
            className="flex items-center gap-1.5 text-xs text-muted hover:text-accent-green transition-colors px-2 py-1 rounded border border-[var(--border-default)] hover:border-[var(--border-default)]"
          >
            {copied ? <Check className="h-3 w-3" /> : <Share2 className="h-3 w-3" />}
            {copied ? 'Copied' : 'Share'}
          </button>
        </div>
      </div>

      {/* Expanded content */}
      {expanded && (
        <div className="border-t border-[var(--border-default)] max-h-[600px] overflow-y-auto">
          <div className="px-4 py-4 space-y-4">
            {/* Issue badges summary */}
            {report.issuesCaught > 0 && (
              <div className="flex flex-wrap gap-2">
                {report.buildBreakers > 0 && (
                  <span className={cn('text-xs px-2 py-0.5 rounded-sm border', SEVERITY_CONFIG.WOULD_BREAK_BUILD.color)}>
                    {report.buildBreakers} build breaker{report.buildBreakers !== 1 ? 's' : ''}
                  </span>
                )}
                {report.productionBugs > 0 && (
                  <span className={cn('text-xs px-2 py-0.5 rounded-sm border', SEVERITY_CONFIG.WOULD_CAUSE_BUG.color)}>
                    {report.productionBugs} bug risk{report.productionBugs !== 1 ? 's' : ''}
                  </span>
                )}
                {report.reviewFailures > 0 && (
                  <span className={cn('text-xs px-2 py-0.5 rounded-sm border', SEVERITY_CONFIG.WOULD_FAIL_REVIEW.color)}>
                    {report.reviewFailures} review issue{report.reviewFailures !== 1 ? 's' : ''}
                  </span>
                )}
                {report.lintFailures > 0 && (
                  <span className={cn('text-xs px-2 py-0.5 rounded-sm border', SEVERITY_CONFIG.WOULD_FAIL_LINT.color)}>
                    {report.lintFailures} lint issue{report.lintFailures !== 1 ? 's' : ''}
                  </span>
                )}
              </div>
            )}

            {/* Markdown report */}
            {report.reportMarkdown && (
              <MarkdownContent content={report.reportMarkdown} />
            )}

            {/* All individual issues */}
            {report.issues && report.issues.length > 0 && (
              <div className="space-y-2 pt-2 border-t border-[var(--border-default)]">
                <h4 className="text-xs font-semibold uppercase tracking-wide text-secondary">
                  All Issues ({report.issues.length})
                </h4>
                <div className="space-y-1.5">
                  {report.issues.map((issue: ReportIssue, i: number) => {
                    const cfg = SEVERITY_CONFIG[issue.severity] ?? SEVERITY_CONFIG.WOULD_FAIL_REVIEW;
                    return (
                      <div key={i} className="flex items-start gap-2 text-xs rounded-md bg-[var(--gray-100)] border border-[var(--border-default)] px-3 py-2">
                        <AlertTriangle className={cn('h-3.5 w-3.5 mt-0.5 flex-shrink-0', cfg.color.split(' ')[1])} />
                        <div className="min-w-0 flex-1">
                          <span className="text-secondary">{issue.description}</span>
                          {issue.filePath && (
                            <span className="flex items-center gap-1 mt-0.5 text-muted">
                              <FileCode2 className="h-3 w-3" />
                              {issue.filePath}
                            </span>
                          )}
                          {issue.fix && (
                            <span className="block mt-0.5 text-emerald-400/70">Fix: {issue.fix}</span>
                          )}
                        </div>
                        <span className={cn('text-[10px] px-1.5 py-0.5 rounded border whitespace-nowrap flex-shrink-0', cfg.color)}>
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
              <div className="flex flex-wrap gap-2 pt-2 border-t border-[var(--border-default)]">
                {report.checksPassed.map(check => (
                  <span key={check} className="flex items-center gap-1 text-xs text-emerald-400/80">
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
