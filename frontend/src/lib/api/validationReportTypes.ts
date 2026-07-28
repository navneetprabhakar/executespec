export interface GoalProofEvidenceReference {
  evidenceId: string;
  label: string;
  proofStatus: 'PROVEN' | 'BLOCKING' | 'UNPROVEN' | 'CONTEXT' | string;
  observedStatus: string;
  source?: string | null;
  confidence?: string | null;
  artifact?: string | null;
  observed?: number | null;
  total?: number | null;
}
