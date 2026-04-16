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
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 space-y-2.5 mb-3">
      <div className="flex items-center justify-between">
        <h3 className="text-[12px] font-medium text-primary">Feature assessment</h3>
        <ComplexityChip complexity={assessment.complexity} />
      </div>

      {assessment.summary && (
        <p className="text-[11px] text-secondary">{assessment.summary}</p>
      )}

      {assessment.repoFullNames && assessment.repoFullNames.length > 0 && (
        <div>
          <h4 className="text-[10px] font-medium text-muted uppercase mb-1">Repos in scope</h4>
          <div className="flex flex-wrap gap-1">
            {assessment.repoFullNames.map((r) => (
              <span key={r} className="text-[10px] font-mono bg-[var(--gray-200)] text-secondary px-1.5 py-0.5 rounded-sm">{r}</span>
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
    S: 'bg-emerald-500/10 text-emerald-400',
    M: 'bg-accent-amber/10 text-accent-amber',
    L: 'bg-accent-red/10 text-accent-red',
  };
  return (
    <span className={`text-[10px] px-1.5 py-0.5 rounded-sm ${colors[complexity] ?? 'bg-[var(--gray-200)] text-muted'}`}>
      Complexity {complexity}
    </span>
  );
}

function Section({ title, items, accent }: { title: string; items: string[]; accent?: 'amber' }) {
  return (
    <div>
      <h4 className="text-[10px] font-medium text-muted uppercase mb-1">{title}</h4>
      <ul className="space-y-0.5 text-[11px]">
        {items.map((it, i) => (
          <li key={i} className={accent === 'amber' ? 'text-accent-amber' : 'text-secondary'}>
            • {it}
          </li>
        ))}
      </ul>
    </div>
  );
}
