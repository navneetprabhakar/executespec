/** Customer-safe durable evidence for one accepted, rejected, or unresolved BUILD generation. */
export interface WorkspaceGenerationAttemptEvidence {
  attemptId: string;
  taskId: string;
  taskIndex: number;
  attemptNumber: number;
  attemptKind: string;
  state: string;
  priorAcceptedGeneration?: string | null;
  beforeGeneration: string;
  candidateGeneration?: string | null;
  acceptedGeneration?: string | null;
  taskContractDigest?: string | null;
  initialFailureEvidence?: {
    outcome?: string | null;
    code?: string | null;
    detail?: string | null;
    command?: string | null;
    failedTargets?: string[];
    observedAt?: string | null;
  } | null;
  runtimeSurface?: string | null;
  baselineKind?: string | null;
  buildCommand?: string | null;
  testCommand?: string | null;
  requiredPaths?: string[];
  acceptanceChecks?: {
    kind: string;
    target: string;
    hidden: boolean;
    outcome?: 'PASSED' | 'FAILED' | 'UNAVAILABLE' | null;
    detail?: string | null;
  }[];
  requestedProvider?: string | null;
  requestedModel?: string | null;
  effectiveProvider?: string | null;
  effectiveModel?: string | null;
  workerIterations?: number | null;
  workerToolCalls?: number | null;
  modelCalls?: number | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  cacheReadTokens?: number | null;
  cacheWriteTokens?: number | null;
  reasoningTokens?: number | null;
  costUsd?: number | null;
  verificationReceiptId?: string | null;
  verificationOutcome?: string | null;
  verificationBuildPassed?: boolean | null;
  verificationTestsExecuted?: boolean | null;
  verificationTestsPassed?: number | null;
  verificationTestsFailed?: number | null;
  verificationFailureCategory?: 'PRODUCT' | 'ENVIRONMENT' | 'INTERNAL_PROTOCOL' | null;
  verificationEnvironmentCode?: string | null;
  verificationExecutionBoundary?: string | null;
  failureProjection?: {
    category:
      | 'NO_CANDIDATE'
      | 'VERIFICATION_REJECTED'
      | 'VERIFICATION_ENVIRONMENT_UNAVAILABLE'
      | 'CHECKPOINT_FAILED'
      | 'FRONTIER_CONFLICT'
      | 'ROLLBACK_PROVEN'
      | 'ROLLBACK_UNPROVEN'
      | 'AUTHORITY_LOST'
      | 'INTERNAL_PROTOCOL_CONFLICT';
    stage: string;
    retryable: boolean;
    candidatePresent: boolean;
    rollbackProven: boolean;
    publicationBlocked: boolean;
    recommendedAction: string;
  } | null;
  verificationEvidenceDigest?: string | null;
  rejectedDiffDigest?: string | null;
  failureCode?: string | null;
  recoveryFailureCode?: string | null;
  recoveryFailureAt?: string | null;
  recoveryAttemptCount?: number | null;
  recoveryNextAttemptAt?: string | null;
  recoverySupportId?: string | null;
  recoveryExhaustedAt?: string | null;
  recoveryStatus?: 'RECOVERING' | 'RETRY_SCHEDULED' | 'EXHAUSTED' | null;
  recoveryUserActionRequired?: boolean | null;
  createdAt: string;
  resolvedAt?: string | null;
}
