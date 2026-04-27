import type { ValidationReportData } from '@/lib/api';
import VerdictPill from './VerdictPill';
import { Glossary } from '@/components/shared/Glossary';

type Dim = { label: string; value: number | null | undefined };

// v2.6.0 (UI polish v1) — graduated palette matching the landing-site tour.
// 4-band scale prevents the binary "green OR red" flip that made borderline
// scores (70-79) read as failures. Same thresholds as ScoreRing.defaultColor.
function barColor(v: number): string {
  if (v >= 85) return 'bg-status-success';
  if (v >= 70) return 'bg-accent-blue';
  if (v >= 50) return 'bg-status-waiting';
  return 'bg-status-error';
}

function textColor(v: number): string {
  if (v >= 85) return 'text-status-success';
  if (v >= 70) return 'text-accent-blue';
  if (v >= 50) return 'text-status-waiting';
  return 'text-status-error';
}

function Row({ label, value }: Dim) {
  if (value == null) return null;
  return (
    <div className="flex items-center gap-3 text-[11px]">
      <div className="w-28 sm:w-36 text-secondary truncate">{label}</div>
      <div className="flex-1 h-2.5 rounded-full bg-[var(--gray-300)] overflow-hidden">
        <div
          className={`h-full rounded-full transition-[width] duration-500 ease-out ${barColor(value)}`}
          style={{ width: `${value}%` }}
        />
      </div>
      <div className={`w-10 text-right font-mono tabular-nums font-medium ${textColor(value)}`}>{value}</div>
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
    <div className="rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] p-4 space-y-3">
      {/* Plan 1 (this-week #3): verdict leads, numeric score sits beside it
          as supporting detail rather than as the primary signal. */}
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-2">
          <h3 className="text-[10px] font-mono font-medium uppercase tracking-wider text-muted">Quality dimensions</h3>
          <VerdictPill
            size="sm"
            finalBuildPassed={report.finalBuildPassed}
            testsPassed={report.testsPassed}
            testsTotal={report.testsTotal}
            reviewerVerdict={report.reviewerVerdict}
            verifiedScore={report.verifiedScore}
          />
        </div>
        {overall != null && (
          <div className="flex items-baseline gap-2">
            <span className={`text-base font-medium tabular-nums opacity-80 ${textColor(overall)}`}>{overall}</span>
            <span className="text-[10px] font-normal text-muted">/ 100</span>
            {verified != null && llmScore != null && llmScore !== verified && (
              <span className="text-[10px] font-mono text-muted">· Est. {llmScore}</span>
            )}
            {verified == null && report.accuracyGrade && (
              <span className="text-[10px] text-muted">· {report.accuracyGrade}</span>
            )}
          </div>
        )}
      </div>
      <div className="space-y-2">
        {dims.map((d) => (
          <Row key={d.label} label={d.label} value={d.value} />
        ))}
      </div>
      {allDimsEqualAnchor && (
        <p className="text-[10px] text-muted leading-relaxed pt-1 border-t border-[var(--border-default)]">
          Dimensions match the <Glossary term="verified score">verified score</Glossary> because the <Glossary term="wiring pass">reviewer</Glossary> found no issues.
          Each bar is <code>anchor − issue penalty</code>; zero penalties means every bar equals the anchor.
        </p>
      )}
    </div>
  );
}
