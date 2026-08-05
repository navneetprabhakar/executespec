/**
 * Goal-doneness / continuation decision evidence DTO.
 *
 * Extracted from `core.ts` to keep that shared registry under its committed god-class ceiling
 * (`GodClassLineCeilingTest`), which is explicit that the fix for a breach is to extract the new
 * responsibility rather than raise the limit. `core.ts` re-exports this so the stable import
 * surface documented in `frontend/CLAUDE.md` is unchanged for every existing caller.
 */
export interface GoalDonenessDecisionData {
  state?: string;
  recommendedDecision?: string;
  reason?: string;
  recommendedNextStep?: string;
  goalSummary?: string;
  completedItems?: string[];
  incompleteItems?: string[];
  evidence?: string[];
  risks?: string[];
  budgetCeilingReason?: string;
  inputTokens?: number;
  outputTokens?: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
  reasoningTokens?: number;
  totalTokens?: number;
  costUsd?: number;
  wallClockMs?: number;
  userInputRequired?: boolean;
  anotherModelProviderMightHelp?: boolean;
  /**
   * True when the ONLY remaining goal-proof gap is independent functional verification — the run
   * is clean and the UNCERTAIN state is an evidence disclosure, not a defect. Absent on older
   * servers, which must keep the existing advisory rendering.
   */
  independentProofGapOnly?: boolean;
}
