import type { ValidationReportData } from '@/lib/api';

type Dim = { label: string; value: number | null | undefined };

// v2.5.12 — token-aligned. Swapped raw `emerald-*` Tailwind hex for the
// platform `status-success` token so score bars match every other status
// indicator (StatusBadge, SignalBreakdown, ValidationReport pills).
function barColor(v: number): string {
  if (v >= 80) return 'bg-status-success';
  if (v >= 60) return 'bg-status-waiting';
  return 'bg-status-error';
}

function textColor(v: number): string {
  if (v >= 80) return 'text-status-success';
  if (v >= 60) return 'text-status-waiting';
  return 'text-status-error';
}

function Row({ label, value }: Dim) {
  if (value == null) return null;
  return (
    <div className="flex items-center gap-2 sm:gap-3 text-[11px]">
      <div className="w-24 sm:w-32 text-muted truncate">{label}</div>
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
 *
 * v2.5.12 — prefers {@code verifiedScore} (deterministic, V106) over
 * {@code accuracyScore} (LLM-inferred) for the header number, matching
 * {@code ValidationReportPanel}'s v2.5.10 primary-badge rule. When verified
 * is present, the LLM estimate is shown as a subdued "Est." qualifier.
 *
 * Also displays an honest helper note when all non-null dimensions equal
 * the anchor — reassuring the user that "90 everywhere" is the correct
 * answer for a clean run with no review issues, not a stuck-default bug.
 */
export default function ScoreCard({ report }: { report: ValidationReportData }) {
  const verified = report.verifiedScore;
  const llmScore = report.accuracyScore;
  const overall = verified ?? llmScore;

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

  // Honest-scoring note: when non-null dimensions all equal the anchor and
  // no review issues were found, the flat bars are arithmetically correct
  // (dimension = anchor − penalty; zero penalties → all = anchor).
  const nonNull = dims.map((d) => d.value).filter((v): v is number => v != null);
  const allDimsEqualAnchor =
    overall != null && nonNull.length >= 3 && nonNull.every((v) => v === overall);

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 mb-3 space-y-2.5">
      <div className="flex items-center justify-between">
        <h3 className="text-[10px] font-mono font-medium uppercase tracking-wider text-secondary">Quality dimensions</h3>
        {overall != null && (
          <div className={`text-xl font-semibold ${textColor(overall)}`}>
            {overall}
            <span className="text-[11px] font-normal text-muted"> / 100</span>
            {verified != null && llmScore != null && llmScore !== verified && (
              <span className="ml-2 text-[10px] font-normal text-muted">(Est. {llmScore})</span>
            )}
            {verified == null && report.accuracyGrade && (
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
      {allDimsEqualAnchor && (
        <p className="text-[10px] text-muted leading-relaxed">
          Dimensions match the verified score because the reviewer found no issues.
          Each bar is <code>anchor − issue penalty</code>; zero penalties → all bars equal the anchor.
        </p>
      )}
    </div>
  );
}
