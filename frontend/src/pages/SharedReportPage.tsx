import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { getSharedReport, type SharedReportData } from '@/lib/api';
import { MarkdownContent } from '@/components/shared/MarkdownContent';
import { Shield, Check, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

// v2.5.14 — severity config aligned with semantic status tokens so shared
// report matches in-app ValidationReportPanel (both use status-error / status-
// partial / status-waiting). Previously hard-coded Tailwind colours drifted
// from the design system after the Phase 2 palette swap.
const SEVERITY_CONFIG: Record<string, { color: string }> = {
  WOULD_BREAK_BUILD:  { color: 'bg-status-error/15   text-status-error   border-status-error/30' },
  WOULD_CAUSE_BUG:    { color: 'bg-status-partial/15 text-status-partial border-status-partial/30' },
  WOULD_FAIL_REVIEW:  { color: 'bg-status-waiting/15 text-status-waiting border-status-waiting/30' },
  WOULD_FAIL_LINT:    { color: 'bg-[var(--gray-200)] text-secondary      border-[var(--border-default)]' },
};

export default function SharedReportPage() {
  const { shareToken } = useParams<{ shareToken: string }>();
  const [report, setReport] = useState<SharedReportData | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    if (!shareToken) return;
    getSharedReport(shareToken)
      .then(data => {
        if (!data) setNotFound(true);
        else setReport(data);
      })
      .finally(() => setLoading(false));
  }, [shareToken]);

  if (loading) {
    return (
      <div className="min-h-screen bg-[var(--geist-bg)] flex items-center justify-center">
        <Loader2 className="h-8 w-8 text-muted animate-spin" />
      </div>
    );
  }

  if (notFound || !report) {
    return (
      <div className="min-h-screen bg-[var(--geist-bg)] flex flex-col items-center justify-center text-secondary gap-4">
        <Shield className="h-12 w-12 text-muted" />
        <h1 className="text-xl font-semibold text-primary">Report Not Found</h1>
        <p className="text-sm">This validation report doesn't exist or has been removed.</p>
      </div>
    );
  }

  const issueCount = report.issues?.length ?? 0;

  return (
    <div className="min-h-screen bg-[var(--geist-bg)] text-primary relative">
      <div aria-hidden className="pointer-events-none absolute inset-0 -z-10 h-[340px]" style={{
        background: 'radial-gradient(ellipse 70% 50% at 50% 0%, color-mix(in srgb, var(--status-success) 12%, transparent), transparent 60%)'
      }} />
      {/* Header */}
      <header className="border-b border-[var(--border-default)]">
        <div className="max-w-3xl mx-auto px-4 sm:px-6 py-5 flex items-center gap-3">
          <span className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-[color-mix(in_srgb,var(--status-success)_30%,transparent)] bg-[color-mix(in_srgb,var(--status-success)_12%,transparent)] text-accent-green flex-shrink-0">
            <Shield className="h-5 w-5" />
          </span>
          <div className="min-w-0 flex-1">
            <div className="font-mono text-[10.5px] font-medium uppercase tracking-[0.08em] text-accent-green">Validation report · Shared</div>
            <p className="text-sm text-secondary font-mono">
              {report.filesGenerated} file{report.filesGenerated !== 1 ? 's' : ''} &middot; {report.languages.join(', ') || 'N/A'} &middot; {
                issueCount > 0
                  ? `${issueCount} issue${issueCount !== 1 ? 's' : ''} caught & fixed`
                  : 'Clean run'
              }
            </p>
          </div>
        </div>
      </header>

      {/* Content */}
      <main className="max-w-3xl mx-auto px-6 py-8 space-y-6">
        {/* Severity badges */}
        {issueCount > 0 && (
          <div className="flex flex-wrap gap-2">
            {report.issues.map((issue, i) => {
              const cfg = SEVERITY_CONFIG[issue.severity] ?? SEVERITY_CONFIG.WOULD_FAIL_REVIEW;
              return (
                <span key={i} className={cn('text-xs px-2 py-0.5 rounded-sm border', cfg.color)}>
                  {issue.severity.replace(/WOULD_/g, '').replace(/_/g, ' ').toLowerCase()}
                </span>
              );
            })}
          </div>
        )}

        {/* Markdown report */}
        {report.reportMarkdown && (
          <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-6">
            <MarkdownContent content={report.reportMarkdown} />
          </div>
        )}

        {/* Checks passed */}
        {report.checksPassed.length > 0 && (
          <div className="flex flex-wrap gap-3">
            {report.checksPassed.map(check => (
              <span key={check} className="flex items-center gap-1.5 text-sm text-status-success/80">
                <Check className="h-4 w-4" />
                {check.replace(/_/g, ' ')}
              </span>
            ))}
          </div>
        )}
      </main>

      {/* Footer */}
      <footer className="border-t border-[var(--border-default)] mt-16">
        <div className="max-w-3xl mx-auto px-6 py-6 text-center text-xs text-muted">
          Generated by <span className="text-secondary font-medium">ExecuteSpec</span> — Don't prompt. Specify.
        </div>
      </footer>
    </div>
  );
}
