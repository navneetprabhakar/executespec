import { useMemo } from 'react';
import type { AssessmentResult } from '@/types/project';

interface Props {
  /** Raw JSON string from {@code RunDetail.assessmentResult}. */
  raw: string | null | undefined;
}

/**
 * Pilot R4 — renders the FeatureAssessmentService output above the plan-approval
 * gate so the user can see complexity, risks, and cross-repo deps before they
 * commit credits.
 */
export default function AssessmentPanel({ raw }: Props) {
  const assessment = useMemo<AssessmentResult | null>(() => {
    if (!raw) return null;
    try {
      return JSON.parse(raw) as AssessmentResult;
    } catch {
      return null;
    }
  }, [raw]);

  if (!assessment) return null;

  return (
    <div className="border rounded-lg bg-white shadow-sm p-4 space-y-3 mb-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium">Feature assessment</h3>
        <ComplexityChip complexity={assessment.complexity} />
      </div>

      {assessment.summary && (
        <p className="text-sm text-gray-700">{assessment.summary}</p>
      )}

      {assessment.repoFullNames && assessment.repoFullNames.length > 0 && (
        <div>
          <h4 className="text-xs font-semibold text-gray-500 uppercase mb-1">Repos in scope</h4>
          <div className="flex flex-wrap gap-1">
            {assessment.repoFullNames.map((r) => (
              <span key={r} className="text-xs font-mono bg-gray-100 px-2 py-0.5 rounded">{r}</span>
            ))}
          </div>
        </div>
      )}

      {assessment.crossRepoDeps && assessment.crossRepoDeps.length > 0 && (
        <Section title="Cross-repo dependencies" items={assessment.crossRepoDeps} />
      )}

      {assessment.risks && assessment.risks.length > 0 && (
        <Section title="Risks" items={assessment.risks} accent="amber" />
      )}
    </div>
  );
}

function ComplexityChip({ complexity }: { complexity: string }) {
  const colors: Record<string, string> = {
    S: 'bg-green-100 text-green-700',
    M: 'bg-yellow-100 text-yellow-700',
    L: 'bg-red-100 text-red-700',
  };
  return (
    <span className={`text-xs px-2 py-0.5 rounded ${colors[complexity] ?? 'bg-gray-100 text-gray-700'}`}>
      Complexity {complexity}
    </span>
  );
}

function Section({ title, items, accent }: { title: string; items: string[]; accent?: 'amber' }) {
  return (
    <div>
      <h4 className="text-xs font-semibold text-gray-500 uppercase mb-1">{title}</h4>
      <ul className="space-y-0.5 text-sm">
        {items.map((it, i) => (
          <li key={i} className={accent === 'amber' ? 'text-amber-700' : 'text-gray-700'}>
            • {it}
          </li>
        ))}
      </ul>
    </div>
  );
}
