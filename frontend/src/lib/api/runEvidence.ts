/** Durable trust facts projected onto run detail independently from task completion. */
export interface RunEvidenceDetail {
  effectiveProvider?: string | null;
  effectiveModel?: string | null;
  effectiveRouteAttribution?: 'LEDGER' | 'MIXED' | 'UNATTRIBUTED' | null;
  routeDiverged?: boolean | null;
  reviewerVerdict?: 'CLEAN' | 'FIXED_ISSUES' | 'UNRESOLVED_ISSUES' | 'REVIEW_FAILED' | null;
  reviewerProvider?: string | null;
  reviewerEffectiveModel?: string | null;
  finalBuildPassed?: boolean | null;
  testsPassed?: number | null;
  testsSkipped?: number | null;
  testsTotal?: number | null;
  testExecutionVerified?: boolean | null;
  validationEvidenceStatus?: string | null;
  evidenceWorkspaceGeneration?: string | null;
  workflowConfigStatus?: 'PASSED' | 'FAILED' | 'NOT_APPLICABLE' | 'UNAVAILABLE' | null;
  workflowConfigReason?: string | null;
  brownfieldHttpBaselineDigest?: string | null;
  brownfieldHttpCurrentDigest?: string | null;
  brownfieldHttpCompatibilityStatus?: 'BASELINE' | 'COMPATIBLE' | 'BREAKING' | string | null;
}
