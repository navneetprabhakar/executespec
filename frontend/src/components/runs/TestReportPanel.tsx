import { useState, useEffect, useCallback } from 'react';
import { getTestReport, markTestInvalid, applyTestFixes } from '@/lib/api';
import { V2_LEGACY_UI } from '@/lib/constants';
import type { TestReportData, TestResultDetail, FailureSuggestion, RootCauseGroup } from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';
import { useSseStore } from '@/stores/useSseStore';
import { TestTube2, ChevronDown, ChevronRight, Check, X, AlertTriangle, SkipForward, Wrench, Target } from 'lucide-react';
import { cn } from '@/lib/utils';

interface Props {
  runId: string;
  status: string;
}

const STATUS_CONFIG: Record<string, { color: string; icon: typeof Check; label: string }> = {
  PASSED: { color: 'text-emerald-400', icon: Check, label: 'Passed' },
  FAILED: { color: 'text-accent-red', icon: X, label: 'Failed' },
  SKIPPED: { color: 'text-muted', icon: SkipForward, label: 'Skipped' },
  ERROR: { color: 'text-accent-amber', icon: AlertTriangle, label: 'Error' },
};

export default function TestReportPanel({ runId, status }: Props) {
  const [report, setReport] = useState<TestReportData | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [applyingFixes, setApplyingFixes] = useState<string | true | false>(false); // false=idle, true=all, string=signature
  const addToast = useUiStore((s) => s.addToast);

  const fetchReport = useCallback(async () => {
    const data = await getTestReport(runId);
    if (data && data.totalTests > 0) setReport(data);
  }, [runId]);

  const testReportReady = useSseStore((s) => s.entries).some(e => e.type === 'TEST_REPORT_READY');
  useEffect(() => {
    if (!['COMPLETE', 'PARTIAL', 'FAILED'].includes(status)) return;
    // Fetch immediately if run is terminal (navigated to after stream closed),
    // or when the live TEST_REPORT_READY event arrives during the session.
    fetchReport();
  }, [status, fetchReport, testReportReady]);

  if (!report) return null;

  const passRate = report.totalTests > 0
    ? Math.round((report.passed / report.totalTests) * 100)
    : 0;

  const resultColor = report.failed === 0 ? 'text-emerald-400' : 'text-accent-red';
  const borderColor = report.failed === 0 ? 'border-emerald-500/30' : 'border-red-500/30';

  const handleMarkInvalid = async (resultId: number) => {
    try {
      await markTestInvalid(resultId);
      addToast({ type: 'success', message: 'Test marked as invalid' });
      fetchReport();
    } catch {
      addToast({ type: 'error', message: 'Failed to mark test invalid' });
    }
  };

  const hasRootCauses = report.rootCauseGroups && report.rootCauseGroups.length > 0;
  const hasFixableCauses = hasRootCauses && report.rootCauseGroups.some(g => g.hasSuggestion);

  const handleApplyFixes = async (signature?: string) => {
    setApplyingFixes(signature ?? true);
    try {
      await applyTestFixes(runId, signature);
      addToast({ type: 'success', message: signature
        ? `Fix applied for "${signature}" — re-running tests...`
        : 'All fixes applied — re-running tests...' });
    } catch (e: any) {
      addToast({ type: 'error', message: e.message || 'Failed to apply test fixes' });
    } finally {
      setApplyingFixes(false);
    }
  };

  // Group suggestions by test method for quick lookup
  const suggestionMap = new Map<string, string>();
  report.suggestions?.forEach((s: FailureSuggestion) => {
    suggestionMap.set(s.testMethod, s.suggestion);
  });

  return (
    <div className={cn('mx-4 mt-3 rounded-sm border bg-[var(--gray-100)]', borderColor)}>
      {/* Header */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center gap-3 p-3 text-left hover:bg-[var(--gray-100)] transition-colors"
      >
        {expanded ? <ChevronDown className="h-4 w-4 text-muted" /> : <ChevronRight className="h-4 w-4 text-muted" />}
        <TestTube2 className="h-4 w-4 text-accent-blue" />
        <span className="text-sm font-medium text-primary">Integration Tests</span>
        <span className="px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider rounded bg-accent-blue/15 text-accent-blue border border-accent-blue/25">Beta</span>
        <span className={cn('ml-2 text-sm font-medium', resultColor)}>
          {report.passed}/{report.totalTests} passed
        </span>
        {report.failed > 0 && (
          <span className="text-xs text-accent-red">({report.failed} failed)</span>
        )}
        <div className="ml-auto flex items-center gap-2" onClick={e => e.stopPropagation()}>
          {V2_LEGACY_UI.applyTestFixes && report.failed > 0 && hasFixableCauses && (
            <button
              onClick={() => handleApplyFixes()}
              disabled={!!applyingFixes}
              className="flex items-center gap-1.5 text-xs text-accent-blue hover:text-violet-300 transition-colors px-2 py-1 rounded border border-violet-500/30 hover:border-violet-500/50 bg-accent-blue/10"
            >
              <Wrench className="h-3 w-3" />
              {applyingFixes === true ? 'Applying fixes...' : 'Apply All Fixes'}
            </button>
          )}
          <span className="text-xs text-muted">
            {passRate}% pass rate
          </span>
        </div>
      </button>

      {/* Expanded Content */}
      {expanded && (
        <div className="border-t border-[var(--border-default)] max-h-[500px] overflow-y-auto">
          {/* Summary Badges */}
          <div className="flex flex-wrap gap-2 p-3 border-b border-[var(--border-default)]">
            <Badge color="emerald" label={`${report.passed} passed`} />
            {report.failed > 0 && <Badge color="red" label={`${report.failed} failed`} />}
            {report.errors > 0 && <Badge color="orange" label={`${report.errors} errors`} />}
            {report.skipped > 0 && <Badge color="zinc" label={`${report.skipped} skipped`} />}
            {report.executionDurationMs > 0 && (
              <span className="text-xs text-muted">
                {(report.executionDurationMs / 1000).toFixed(1)}s
              </span>
            )}
          </div>

          {/* Root Cause Groups */}
          {hasRootCauses && (
            <div className="p-3 space-y-2 border-b border-[var(--border-default)]">
              <h4 className="text-xs font-semibold uppercase tracking-wide text-secondary">
                Root Causes ({report.rootCauseGroups.length})
              </h4>
              {report.rootCauseGroups.map((group: RootCauseGroup) => (
                <div key={group.signature} className="rounded-md bg-[var(--gray-100)] border border-[var(--border-default)] px-3 py-2">
                  <div className="flex items-start gap-2">
                    <Target className="h-3.5 w-3.5 mt-0.5 text-accent-red flex-shrink-0" />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-medium text-primary">{group.signature}</span>
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-red-500/15 text-accent-red border border-red-500/20">
                          {group.count} test{group.count !== 1 ? 's' : ''}
                        </span>
                      </div>
                      <p className="text-xs text-muted mt-0.5 truncate">
                        {group.testMethods.slice(0, 3).join(', ')}{group.count > 3 ? `, +${group.count - 3} more` : ''}
                      </p>
                      {group.hasSuggestion && (
                        <p className="text-xs text-violet-300/80 mt-1">{group.suggestion}</p>
                      )}
                    </div>
                    {V2_LEGACY_UI.applyTestFixes && group.hasSuggestion && (
                      <button
                        onClick={() => handleApplyFixes(group.signature)}
                        disabled={!!applyingFixes}
                        className="flex items-center gap-1 text-xs text-accent-blue hover:text-violet-300 transition-colors px-2 py-1 rounded border border-violet-500/30 hover:border-violet-500/50 bg-accent-blue/10 flex-shrink-0"
                      >
                        <Wrench className="h-3 w-3" />
                        {applyingFixes === group.signature ? 'Fixing...' : 'Fix'}
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Results */}
          <div className="divide-y divide-[var(--border-default)]">
            {report.results.map((r: TestResultDetail) => {
              const cfg = STATUS_CONFIG[r.status] || STATUS_CONFIG.ERROR;
              const Icon = cfg.icon;
              const suggestion = suggestionMap.get(r.testMethod);

              return (
                <div key={r.testResultId} className="px-3 py-2">
                  <div className="flex items-center gap-2">
                    <Icon className={cn('h-3.5 w-3.5', cfg.color)} />
                    <span className="text-xs text-secondary font-mono">{r.testClass}</span>
                    <span className="text-xs text-secondary font-mono">.{r.testMethod}</span>
                    <span className={cn('ml-auto text-xs', cfg.color)}>{cfg.label}</span>
                    {r.durationMs > 0 && (
                      <span className="text-xs text-muted">{r.durationMs}ms</span>
                    )}
                  </div>

                  {r.failureMessage && (
                    <div className="mt-1 ml-5.5">
                      <p className="text-xs text-accent-red/80 font-mono break-all">{r.failureMessage}</p>
                      {r.expected && r.actual && (
                        <div className="mt-0.5 text-xs text-muted">
                          Expected: <span className="text-emerald-400/70">{r.expected}</span>
                          {' '}Actual: <span className="text-accent-red/70">{r.actual}</span>
                        </div>
                      )}
                    </div>
                  )}

                  {suggestion && (
                    <div className="mt-1.5 ml-5.5 rounded bg-accent-blue/10 border border-violet-500/20 px-2 py-1.5">
                      <p className="text-xs text-violet-300">{suggestion}</p>
                    </div>
                  )}

                  {r.status === 'FAILED' && (
                    <button
                      onClick={() => handleMarkInvalid(r.testResultId)}
                      className="mt-1 ml-5.5 text-xs text-muted hover:text-secondary transition-colors"
                    >
                      Mark as incorrect test
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

function Badge({ color, label }: { color: string; label: string }) {
  return (
    <span className={cn(
      'inline-flex items-center rounded-sm px-2 py-0.5 text-xs font-medium',
      color === 'emerald' && 'bg-emerald-500/10 text-emerald-400',
      color === 'red' && 'bg-red-500/10 text-accent-red',
      color === 'orange' && 'bg-orange-500/10 text-accent-amber',
      color === 'zinc' && 'bg-[var(--gray-200)] text-secondary',
    )}>
      {label}
    </span>
  );
}
