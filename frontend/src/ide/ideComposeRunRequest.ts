import type * as api from '@/lib/api';

export type ComposeRunRequestInput = {
  spec: string;
  provider: string;
  mode: 'PROTOTYPE' | 'PRODUCTION';
  projectId: string | null;
  selectedRepoIds: string[];
  costCap: number;
  tddMode: boolean;
  confirmModelUpgrades: boolean;
  requireBenchmarkedUpgrades: boolean;
  figmaUrl: string;
  tier: string;
  modelName: string;
};

export function buildIdeComposeRunRequest(
  input: ComposeRunRequestInput,
): Parameters<typeof api.createRun>[0] {
  const body: Parameters<typeof api.createRun>[0] = {
    userRequest: input.spec.trim(),
    aiProvider: input.provider,
    executionMode: input.mode,
  };
  if (input.projectId) body.projectId = input.projectId;
  if (input.selectedRepoIds.length > 0) body.selectedRepoIds = input.selectedRepoIds;
  if (input.costCap > 0) body.userCostCap = input.costCap;
  if (input.tddMode) body.tddMode = true;
  if (input.confirmModelUpgrades) body.requireConfirmationBeforeExpensiveContinuation = true;
  if (input.requireBenchmarkedUpgrades) {
    body.requireBenchmarkPriorForAutomaticContinuation = true;
  }
  if (input.figmaUrl.trim()) body.figmaUrl = input.figmaUrl.trim();
  // Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22). Only ENTERPRISE may pin,
  // and '' means "Default (org / system matrix)" -> send no modelName at all.
  if (input.tier === 'ENTERPRISE' && input.modelName) body.modelName = input.modelName;
  return body;
}
