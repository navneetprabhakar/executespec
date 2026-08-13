import type {WorkspaceGenerationAttemptEvidence} from '../lib/api/workspaceGenerationEvidence';

function phaseLabel(status?: string | null): string {
  const s = (status || '').toUpperCase();
  if (s === 'CLASSIFYING') return 'Classifying';
  if (s === 'PLANNING') return 'Planning';
  if (s === 'AWAITING_APPROVAL') return 'Awaiting approval';
  if (s === 'REVIEWING') return 'Reviewing changes';
  if (s === 'PROVIDER_SWITCHING') return 'Switching provider';
  if (s === 'PAUSED') return 'Paused';
  if (s === 'WORKING' || s === 'RUNNING') return 'Generating code';
  if (s === 'COMPLETED' || s === 'COMPLETE') return 'Completed';
  if (s === 'PARTIAL') return 'Completed with warnings';
  if (s === 'FAILED' || s === 'ERROR') return 'Failed';
  if (s === 'CANCELLED') return 'Cancelled';
  return 'Status unavailable';
}

export function runPhaseLabel(
  run?: {
    status?: string | null;
    workspaceGenerationAttempts?: WorkspaceGenerationAttemptEvidence[] | null;
  } | null,
): string {
  const generation = run?.workspaceGenerationAttempts?.[0];
  if (generation?.state === 'RECOVERY_FAILED') {
    return generation.recoveryExhaustedAt
      ? 'Workspace recovery failed'
      : 'Recovery retry scheduled';
  }
  if (generation?.state === 'ROLLING_BACK') return 'Recovering workspace';
  return phaseLabel(run?.status);
}
