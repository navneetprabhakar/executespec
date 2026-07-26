import {SANDBOX_PHASES} from '@/ide/agentRuntimeContract';

type SandboxPhase = (typeof SANDBOX_PHASES)[number]['key'];

export function sandboxPhaseKey(status?: string | null): SandboxPhase {
  const normalized = (status || '').toUpperCase();
  if (['QUEUED', 'PENDING', 'SUBMITTED', 'QUEUED_BATCH'].includes(normalized)) return 'queued';
  if (['CLASSIFYING', 'PLANNING'].includes(normalized)) return 'planning';
  if (normalized === 'AWAITING_APPROVAL') return 'approval';
  if (['WORKING', 'IN_PROGRESS', 'IN_PROGRESS_BATCH', 'CODING', 'RUNNING'].includes(normalized))
    return 'coding';
  if (['COMPILING', 'BUILDING', 'TESTING'].includes(normalized)) return 'building';
  if (normalized === 'REVIEWING') return 'reviewing';
  if (['COMPLETE', 'COMPLETED', 'FAILED', 'CANCELLED', 'ERROR', 'PARTIAL'].includes(normalized))
    return 'done';
  return 'queued';
}

export function phaseState(
  phase: SandboxPhase,
  active: SandboxPhase,
  terminal: boolean,
  failed: boolean,
  cancelled: boolean,
): string {
  const order = SANDBOX_PHASES.map((candidate) => candidate.key);
  const phaseIndex = order.indexOf(phase);
  const activeIndex = order.indexOf(active);
  const state =
    terminal && phase === 'done'
      ? failed
        ? 'bad'
        : cancelled
          ? 'muted'
          : 'done'
      : phaseIndex < activeIndex
        ? 'done'
        : phaseIndex === activeIndex
          ? 'active'
          : 'pending';
  return `agent-runcard-phase ${state}`;
}
