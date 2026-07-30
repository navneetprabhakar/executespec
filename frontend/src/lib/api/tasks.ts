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
        postReview?: boolean;
        approvalId?: string;
        proposalVersion?: number;
        proposalDigest?: string;
        workerAttempt?: ContinuationAttemptEvidence;
        reviewerAttempt?: ContinuationAttemptEvidence;
        beforeWorkspaceGeneration?: string;
        afterWorkspaceGeneration?: string;
        diffDigest?: string;
        changedFiles?: string[];
        unifiedPatch?: string;
        patchMetadata?: CorrectionPatchMetadata;
        validationDelta?: CorrectionValidationDelta;
        evidenceInvalidated?: boolean;
        validationRegenerated?: boolean;
      }[]
    | null;
  objective?: string | null;
  completionNotes?: string | null;
  parentTaskId?: string | null;
  subtaskIndex?: number | null;
  subtaskTotal?: number | null;
}

export interface CorrectionPatchMetadata {
  capturedBytes?: number;
  fullBytes?: number;
  maxBytes?: number;
  truncated?: boolean;
  redacted?: boolean;
}

export interface CorrectionValidationDelta {
  before?: CorrectionValidationSnapshot | null;
  after?: CorrectionValidationSnapshot | null;
  verifiedScoreDelta?: number | null;
  rubricScoreDelta?: number | null;
}

export interface CorrectionValidationSnapshot {
  status?: string | null;
  workspaceGeneration?: string | null;
  build?: {verdict?: string | null; passed?: boolean | null; exitCode?: number | null} | null;
  tests?: {
    verdict?: string | null;
    passed?: number | null;
    total?: number | null;
    executionVerified?: boolean | null;
    evidenceScope?: string | null;
    exitCode?: number | null;
  } | null;
  workflow?: {status?: string | null; fileCount?: number; reason?: string | null} | null;
  reviewer?: {verdict?: string | null} | null;
  contracts?: {
    status?: string | null;
    baselineDigest?: string | null;
    currentDigest?: string | null;
    missingCount?: number;
    addedCount?: number;
  } | null;
  score?: {verifiedScore?: number | null; rubricScore?: number | null} | null;
  pipelineResult?: string | null;
  executableVerificationStatus?: string | null;
  executionFailureStage?: string | null;
}

export interface ContinuationAttemptEvidence {
  provider?: string;
  model?: string;
  attribution?: 'LEDGER' | 'MIXED' | 'UNATTRIBUTED' | string;
  callIndexFloor?: number;
  firstCallIndex?: number;
  lastCallIndex?: number;
  physicalAttemptIds?: string[];
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
