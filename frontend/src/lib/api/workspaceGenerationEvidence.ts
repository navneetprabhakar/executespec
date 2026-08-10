/** Customer-safe durable evidence for one accepted, rejected, or unresolved BUILD generation. */
export interface WorkspaceGenerationAttemptEvidence {
  attemptId: string;
  taskId: string;
  taskIndex: number;
  attemptNumber: number;
  attemptKind: string;
  state: string;
  beforeGeneration: string;
  candidateGeneration?: string | null;
  rejectedDiffDigest?: string | null;
  failureCode?: string | null;
  recoveryFailureCode?: string | null;
  recoveryFailureAt?: string | null;
  createdAt: string;
  resolvedAt?: string | null;
}
