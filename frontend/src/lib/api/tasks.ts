import type {GoalDonenessDecisionData} from './core';

/** Task-level run evidence, including requested and physically observed model routes. */
export interface TaskSummary {
  taskId: string;
  title: string;
  complexity?: string | null;
  status: string;
  assignedModel: string;
  provider?: string | null;
  model?: string | null;
  effectiveProvider?: string | null;
  effectiveModel?: string | null;
  effectiveRouteAttribution?: 'LEDGER' | 'MIXED' | 'UNATTRIBUTED' | null;
  routeDiverged?: boolean | null;
  routingTier?: 'LIGHTWEIGHT' | 'WORKHORSE' | 'PLANNING' | string | null;
  routingTaskCategory?: string | null;
  routingSource?: string | null;
  inputTokens: number;
  outputTokens: number;
  durationMs: number;
  branchName: string | null;
  startedAt: string | null;
  completedAt: string | null;
  readmeUpdated: boolean;
  failureReason?: string | null;
  goalDoneness?: GoalDonenessDecisionData | null;
  continuationDecision?: {
    decision?: string;
    source?: string;
    approvalRequiredReason?: string;
    selectedCandidate?: RouteCandidate | null;
    policy?: ContinuationPolicy;
    reasons?: string[];
  } | null;
  continuationActions?:
    | {
        status?: string;
        decision?: string;
        provider?: string;
        model?: string;
        reason?: string;
        limitName?: string;
        usedLimit?: number;
        maxLimit?: number;
        attempt?: number;
        source?: string;
        code?: string;
        beforeWorkspaceGeneration?: string;
        afterWorkspaceGeneration?: string;
      }[]
    | null;
  objective?: string | null;
  completionNotes?: string | null;
  parentTaskId?: string | null;
  subtaskIndex?: number | null;
  subtaskTotal?: number | null;
}

interface ContinuationPolicy {
  taskCategory?: string;
  requiredQualityThreshold?: number;
  defaultRequiredQualityThreshold?: number;
  providerSwitchesUsed?: number;
  maxProviderSwitches?: number;
  modelStepUpsUsed?: number;
  maxModelStepUps?: number;
  modelStepDownsUsed?: number;
  maxModelStepDowns?: number;
  lowComplexityWorkerStartTierConfigured?: string;
  lowComplexityWorkerStartTierEffective?: string;
  requireConfirmationBeforeExpensiveContinuation?: boolean;
  requireBenchmarkPriorForAutomaticContinuation?: boolean;
  maxTotalCostUsd?: number;
  maxTotalTokens?: number;
  maxTaskWallClockMs?: number;
  providerBehaviorPoor?: boolean;
  userConfirmedExpensiveContinuation?: boolean;
  userConfirmedContinuationAction?: string;
  userConfirmedContinuationProvider?: string;
  userConfirmedContinuationModel?: string;
  routePinned?: boolean;
  source?: string;
  actingEnabled?: boolean;
  maxRerunsPerTask?: number;
  rankingPolicy?: string;
  rankingQualityTieBand?: number;
  rankingReasons?: string[];
  eligibleCandidateCount?: number;
  rejectedBelowQualityThresholdCount?: number;
  topEligibleCandidates?: RouteCandidate[];
  topRejectedCandidates?: RouteCandidate[];
}

interface RouteCandidate {
  provider?: string;
  model?: string;
  taskCategory?: string;
  qualityScore?: number;
  requiredQualityThreshold?: number;
  costPerMillionTokens?: number;
  latencyP50Ms?: number;
  latencyP95Ms?: number;
  benchmarkQualityPriorAvailable?: boolean;
  reasons?: string[];
}
