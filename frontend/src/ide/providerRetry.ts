import type {RunSummary} from '@/lib/api';

export type ForkRun = (
  runId: string,
  overrides: {userRequest?: string; executionMode?: string; aiProvider?: string} | undefined,
  idempotencyKey: string,
) => Promise<RunSummary>;

/** Provider retries fork the persisted run so project/repository scope remains server-owned. */
export function retryRunWithProvider(
  forkRun: ForkRun,
  runId: string,
  aiProvider: string,
  idempotencyKey: string,
) {
  return forkRun(runId, {aiProvider}, idempotencyKey);
}
