import { useState, useEffect, useCallback } from 'react';
import { getTestReport, markTestInvalid } from '@/lib/api';
import type { TestReportData, TestResultDetail, FailureSuggestion } from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';
import { TestTube2, ChevronDown, ChevronRight, Check, X, AlertTriangle, SkipForward } from 'lucide-react';
import { cn } from '@/lib/utils';

interface Props {
  runId: string;
  status: string;
}

const STATUS_CONFIG: Record<string, { color: string; icon: typeof Check; label: string }> = {
  PASSED: { color: 'text-emerald-400', icon: Check, label: 'Passed' },
  FAILED: { color: 'text-red-400', icon: X, label: 'Failed' },
  SKIPPED: { color: 'text-zinc-500', icon: SkipForward, label: 'Skipped' },
  ERROR: { color: 'text-orange-400', icon: AlertTriangle, label: 'Error' },
};

export default function TestReportPanel({ runId, status }: Props) {
  const [report, setReport] = useState<TestReportData | null>(null);
  const [expanded, setExpanded] = useState(false);
  const addToast = useUiStore((s) => s.addToast);

  const fetchReport = useCallback(async () => {
    const data = await getTestReport(runId);
    if (data && data.totalTests > 0) setReport(data);
  }, [runId]);

  useEffect(() => {
    if (['AWAITING_MERGE', 'COMPLETE', 'PARTIAL', 'FAILED'].includes(status)) {
      fetchReport();
    }
  }, [status, fetchReport]);

  if (!report) return null;

  const passRate = report.totalTests > 0
    ? Math.round((report.passed / report.totalTests) * 100)
    : 0;

  const resultColor = report.failed === 0 ? 'text-emerald-400' : 'text-red-400';
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

  // Group suggestions by test method for quick lookup
  const suggestionMap = new Map<string, string>();
  report.suggestions?.forEach((s: FailureSuggestion) => {
    suggestionMap.set(s.testMethod, s.suggestion);
  });

  return (
    <div className={cn('mx-4 mt-3 rounded-lg border bg-white/[0.02]', borderColor)}>
      {/* Header */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center gap-3 p-3 text-left hover:bg-white/[0.02] transition-colors"
      >
        {expanded ? <ChevronDown className="h-4 w-4 text-zinc-500" /> : <ChevronRight className="h-4 w-4 text-zinc-500" />}
        <TestTube2 className="h-4 w-4 text-violet-400" />
        <span className="text-sm font-medium text-zinc-200">Integration Tests</span>
        <span className={cn('ml-2 text-sm font-medium', resultColor)}>
          {report.passed}/{report.totalTests} passed
        </span>
        {report.failed > 0 && (
          <span className="text-xs text-red-400">({report.failed} failed)</span>
        )}
        <span className="ml-auto text-xs text-zinc-500">
          {passRate}% pass rate
        </span>
      </button>

      {/* Expanded Content */}
      {expanded && (
        <div className="border-t border-white/[0.06] max-h-[500px] overflow-y-auto">
          {/* Summary Badges */}
          <div className="flex flex-wrap gap-2 p-3 border-b border-white/[0.06]">
            <Badge color="emerald" label={`${report.passed} passed`} />
            {report.failed > 0 && <Badge color="red" label={`${report.failed} failed`} />}
            {report.errors > 0 && <Badge color="orange" label={`${report.errors} errors`} />}
            {report.skipped > 0 && <Badge color="zinc" label={`${report.skipped} skipped`} />}
            {report.executionDurationMs > 0 && (
              <span className="text-xs text-zinc-500">
                {(report.executionDurationMs / 1000).toFixed(1)}s
              </span>
            )}
          </div>

          {/* Results */}
          <div className="divide-y divide-white/[0.04]">
            {report.results.map((r: TestResultDetail) => {
              const cfg = STATUS_CONFIG[r.status] || STATUS_CONFIG.ERROR;
              const Icon = cfg.icon;
              const suggestion = suggestionMap.get(r.testMethod);

              return (
                <div key={r.testResultId} className="px-3 py-2">
                  <div className="flex items-center gap-2">
                    <Icon className={cn('h-3.5 w-3.5', cfg.color)} />
                    <span className="text-xs text-zinc-400 font-mono">{r.testClass}</span>
                    <span className="text-xs text-zinc-300 font-mono">.{r.testMethod}</span>
                    <span className={cn('ml-auto text-xs', cfg.color)}>{cfg.label}</span>
                    {r.durationMs > 0 && (
                      <span className="text-xs text-zinc-600">{r.durationMs}ms</span>
                    )}
                  </div>

                  {r.failureMessage && (
                    <div className="mt-1 ml-5.5">
                      <p className="text-xs text-red-400/80 font-mono break-all">{r.failureMessage}</p>
                      {r.expected && r.actual && (
                        <div className="mt-0.5 text-xs text-zinc-500">
                          Expected: <span className="text-emerald-400/70">{r.expected}</span>
                          {' '}Actual: <span className="text-red-400/70">{r.actual}</span>
                        </div>
                      )}
                    </div>
                  )}

                  {suggestion && (
                    <div className="mt-1.5 ml-5.5 rounded bg-violet-500/10 border border-violet-500/20 px-2 py-1.5">
                      <p className="text-xs text-violet-300">{suggestion}</p>
                    </div>
                  )}

                  {r.status === 'FAILED' && (
                    <button
                      onClick={() => handleMarkInvalid(r.testResultId)}
                      className="mt-1 ml-5.5 text-xs text-zinc-500 hover:text-zinc-300 transition-colors"
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
      'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
      color === 'emerald' && 'bg-emerald-500/10 text-emerald-400',
      color === 'red' && 'bg-red-500/10 text-red-400',
      color === 'orange' && 'bg-orange-500/10 text-orange-400',
      color === 'zinc' && 'bg-zinc-500/10 text-zinc-400',
    )}>
      {label}
    </span>
  );
}
