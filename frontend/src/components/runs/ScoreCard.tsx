import type { ValidationReportData } from '@/lib/api';

type Dim = { label: string; value: number | null | undefined };

function barColor(v: number): string {
  if (v >= 80) return 'bg-emerald-500';
  if (v >= 60) return 'bg-accent-amber';
  return 'bg-accent-red';
}

function textColor(v: number): string {
  if (v >= 80) return 'text-emerald-400';
  if (v >= 60) return 'text-accent-amber';
  return 'text-accent-red';
}

function Row({ label, value }: Dim) {
  if (value == null) return null;
  return (
    <div className="flex items-center gap-3 text-[11px]">
      <div className="w-32 text-muted">{label}</div>
      <div className="flex-1 h-2 rounded bg-[var(--border-default)]">
        <div
          className={`h-2 rounded ${barColor(value)}`}
          style={{ width: `${value}%` }}
        />
      </div>
      <div className={`w-10 text-right font-mono ${textColor(value)}`}>{value}</div>
    </div>
  );
}

/**
 * Pilot R6 — shows overall accuracy + 6 dimension bars at the top of the
 * validation report panel. Colour-coded: ≥80 green, 60–79 yellow, <60 red.
 */
export default function ScoreCard({ report }: { report: ValidationReportData }) {
  const overall = report.accuracyScore;
  const dims: Dim[] = [
    { label: 'Code quality', value: report.codeQualityScore },
    { label: 'Test coverage', value: report.testCoverageScore },
    { label: 'Architecture', value: report.architectureScore },
    { label: 'Security', value: report.securityScore },
    { label: 'Performance', value: report.performanceScore },
    { label: 'Design (Figma)', value: report.designComplianceScore },
  ];
  const anyDims = dims.some((d) => d.value != null);
  if (overall == null && !anyDims) return null;

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 mb-3 space-y-2.5">
      <div className="flex items-center justify-between">
        <h3 className="text-[12px] font-medium text-primary">Review score</h3>
        {overall != null && (
          <div className={`text-xl font-semibold ${textColor(overall)}`}>
            {overall}
            <span className="text-[11px] font-normal text-muted"> / 100</span>
            {report.accuracyGrade && (
              <span className="ml-2 text-xs text-muted">({report.accuracyGrade})</span>
            )}
          </div>
        )}
      </div>
      <div className="space-y-1.5">
        {dims.map((d) => (
          <Row key={d.label} label={d.label} value={d.value} />
        ))}
      </div>
    </div>
  );
}
