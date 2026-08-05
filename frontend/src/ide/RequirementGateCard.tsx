import type {RequirementGateEvidence} from '@/lib/api';
import {IdeBadge} from '@/ide/IdeKit';

function humanize(value?: string | null): string {
  return (value || 'unknown')
    .toLowerCase()
    .split('_')
    .join(' ')
    .replace(/\b\w/g, (letter: string) => letter.toUpperCase());
}

/** Human-readable projection of the exact RequirementIR artifact bound to this plan gate. */
export function RequirementGateCard({evidence}: {evidence: RequirementGateEvidence}) {
  // Historical gates can predate the binding-mode column. Only the two RequirementIR modes have
  // evidence worth rendering; unknown/legacy rows keep the established approval presentation.
  if (evidence.bindingMode !== 'SHADOW' && evidence.bindingMode !== 'ENFORCED') return null;
  const blocked = evidence.enforced && !evidence.approvalReady;
  const blockingIssues = evidence.issues.filter((issue) => issue.blocking);
  const clarification = evidence.requirements.filter(
    (requirement) => requirement.state === 'NEEDS_CLARIFICATION',
  );

  return (
    <div
      className='ide-card'
      data-testid='requirement-gate'
      style={{borderColor: blocked ? 'var(--ide-red)' : 'var(--ide-border)', marginTop: 12}}
    >
      <div style={{display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap'}}>
        <strong style={{color: 'var(--ide-fg-bright)'}}>Requirement coverage</strong>
        <IdeBadge kind={blocked ? 'bad' : evidence.approvalReady ? 'ok' : 'warn'}>
          {evidence.approvalReady
            ? 'Ready'
            : humanize(evidence.completeness || evidence.bindingStatus)}
        </IdeBadge>
        {evidence.bindingMode === 'SHADOW' && <IdeBadge kind='warn'>Diagnostic only</IdeBadge>}
      </div>

      <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', lineHeight: 1.6, marginTop: 8}}>
        {evidence.requirementCount} requirement{evidence.requirementCount === 1 ? '' : 's'} ·{' '}
        {evidence.acceptanceCriterionCount} acceptance check
        {evidence.acceptanceCriterionCount === 1 ? '' : 's'} · {evidence.taskBindingCount} task
        binding{evidence.taskBindingCount === 1 ? '' : 's'}
      </div>

      {blockingIssues.length > 0 && (
        <div style={{marginTop: 10}}>
          <strong style={{fontSize: 12, color: 'var(--ide-red)'}}>Resolve before approval</strong>
          <ul style={{margin: '6px 0 0 18px', padding: 0, fontSize: 12, lineHeight: 1.6}}>
            {blockingIssues.map((issue, index) => (
              <li key={`${issue.code}-${index}`}>
                <span className='ide-mono'>{humanize(issue.code)}</span>: {issue.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {clarification.length > 0 && (
        <div style={{marginTop: 10}}>
          <strong style={{fontSize: 12}}>Needs clarification</strong>
          <ul style={{margin: '6px 0 0 18px', padding: 0, fontSize: 12, lineHeight: 1.6}}>
            {clarification.map((requirement) => (
              <li key={requirement.id}>
                <span className='ide-mono'>{requirement.id}</span>: {requirement.text}
              </li>
            ))}
          </ul>
        </div>
      )}

      {evidence.requirements.length > 0 && (
        <details style={{marginTop: 10}}>
          <summary style={{cursor: 'pointer', fontSize: 12}}>Review requirement mapping</summary>
          <div style={{marginTop: 8, display: 'grid', gap: 8}}>
            {evidence.requirements.map((requirement) => (
              <div
                key={requirement.id}
                style={{borderTop: '1px solid var(--ide-border)', paddingTop: 8, fontSize: 12}}
              >
                <div>
                  <span className='ide-mono'>{requirement.id}</span> · {humanize(requirement.kind)}{' '}
                  · {humanize(requirement.state)}
                </div>
                <div style={{color: 'var(--ide-fg-muted)', marginTop: 3}}>{requirement.text}</div>
                <div style={{color: 'var(--ide-fg-muted)', marginTop: 3}}>
                  Acceptance: {requirement.acceptanceIds.join(', ') || 'none'} · Steps:{' '}
                  {requirement.taskIds.join(', ') || 'none'}
                </div>
                {requirement.inferenceRationale && (
                  <div style={{color: 'var(--ide-fg-muted)', marginTop: 3}}>
                    Inference: {requirement.inferenceRationale}
                  </div>
                )}
                {requirement.deferredReason && (
                  <div style={{color: 'var(--ide-fg-muted)', marginTop: 3}}>
                    Deferred: {requirement.deferredReason}
                  </div>
                )}
              </div>
            ))}
            {evidence.acceptanceCriteria.map((criterion) => (
              <div
                key={criterion.id}
                style={{borderTop: '1px solid var(--ide-border)', paddingTop: 8, fontSize: 12}}
              >
                <div>
                  <span className='ide-mono'>{criterion.id}</span> · {humanize(criterion.kind)} ·{' '}
                  {humanize(criterion.evidenceType)}
                </div>
                <div style={{color: 'var(--ide-fg-muted)', marginTop: 3}}>
                  Expected: {criterion.expected}
                </div>
                <div style={{color: 'var(--ide-fg-muted)', marginTop: 3}}>
                  Requirements: {criterion.requirementIds.join(', ') || 'none'}
                </div>
              </div>
            ))}
          </div>
        </details>
      )}

      {evidence.boundDigest && (
        <div
          className='ide-mono'
          style={{
            fontSize: 12,
            color: 'var(--ide-fg-muted)',
            marginTop: 10,
            wordBreak: 'break-all',
          }}
        >
          Bound evidence {evidence.boundDigest}
        </div>
      )}
    </div>
  );
}

export default RequirementGateCard;
