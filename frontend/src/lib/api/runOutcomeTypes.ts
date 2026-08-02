/** Server-owned customer-safe interpretation of a terminal run. */
export interface RunTerminalOutcome {
  schemaVersion: 1;
  code: string;
  category:
    | 'SUCCESS'
    | 'WARNING'
    | 'ACTION_REQUIRED'
    | 'POLICY_BLOCKED'
    | 'USER_STOPPED'
    | 'PLATFORM_FAILURE';
  customerTitle: string;
  customerMessage: string;
  impact: {
    actualCostUsd: number | null;
    capUsd: number | null;
    codeChanged: boolean | null;
    publication: 'PR_CREATED' | 'NOT_CREATED';
    prUrl: string | null;
  };
  retryable: boolean;
  nextAction: {type: string; label: string; href: string | null} | null;
  supportCode: string | null;
  automationDisposition:
    | 'SUCCEEDED'
    | 'COMPLETED_WITH_WARNINGS'
    | 'ACTION_REQUIRED'
    | 'STOPPED'
    | 'FAILED';
  automationExitCode: number;
}
