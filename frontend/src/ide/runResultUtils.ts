import type * as api from '@/lib/api';
import type {DiffLine} from '@/ide/IdeKit';
import {isAwaitingApprovalRunStatus, isLiveRunStatus, isQueuedRunStatus} from '@/lib/runStatus';

export type Verdict = {label: string; kind: 'ok' | 'warn' | 'bad'};

export type RunSurfaceDestination = 'plan' | 'live' | null;

/**
 * Keeps the terminal result surface honest: plan-gated runs belong on the plan surface and every
 * active pipeline phase belongs on the live surface. A missing report during these phases is
 * expected pending evidence, not a terminal report-load failure.
 */
export function runSurfaceDestination(status: string | null | undefined): RunSurfaceDestination {
  const normalized = status?.trim().toUpperCase();
  if (isAwaitingApprovalRunStatus(normalized)) return 'plan';
  if (
    isQueuedRunStatus(normalized) ||
    isLiveRunStatus(normalized) ||
    ['GENERATING_REPORT', 'REPORTING'].includes(normalized || '')
  ) {
    return 'live';
  }
  return null;
}

export interface RunArtifactActionAvailability {
  push: boolean;
  revertWorkspace: boolean;
  preview: boolean;
  deploy: boolean;
  reportPdf: boolean;
  codeZip: boolean;
  shareReport: boolean;
  workspaceReason: string;
  publicationReason: string;
  reportReason: string;
}

export interface TerminalFailureEvidence {
  title: string;
  detail: string;
  kind: 'warn' | 'bad' | 'neutral';
  nextAction?: string | null;
  supportCode?: string | null;
}

export type TerminalOutcomePrimaryAction = {
  type: string;
  label: string;
  behavior: 'navigate' | 'retry' | 'tab';
  href?: string;
  tab?: 'Logs' | 'Validation';
};

/**
 * Turns the server-owned next action into one executable primary control. An explicit server href
 * always wins; type fallbacks keep older payloads actionable without inventing extra choices.
 */
export function terminalOutcomePrimaryAction(
  outcome: api.RunTerminalOutcome | null | undefined,
): TerminalOutcomePrimaryAction | null {
  const action = outcome?.nextAction;
  if (!action) return null;
  if (isSafeTerminalActionHref(action.href)) {
    return {type: action.type, label: action.label, behavior: 'navigate', href: action.href};
  }
  switch (action.type) {
    case 'REVIEW_EVIDENCE':
      return {type: action.type, label: action.label, behavior: 'tab', tab: 'Validation'};
    case 'RETRY_RUN':
      return {type: action.type, label: action.label, behavior: 'retry'};
    case 'OPEN_PROVIDER_SETTINGS':
      return {type: action.type, label: action.label, behavior: 'navigate', href: '/provider-keys'};
    case 'REVIEW_POLICY':
      return {type: action.type, label: action.label, behavior: 'navigate', href: '/org/settings'};
    case 'START_NEW_RUN':
      return {type: action.type, label: action.label, behavior: 'navigate', href: '/compose'};
    case 'CONTACT_SUPPORT': {
      const supportCode = outcome.supportCode ? ` ${outcome.supportCode}` : '';
      return {
        type: action.type,
        label: action.label,
        behavior: 'navigate',
        href: `mailto:navneet@executespec.ai?subject=${encodeURIComponent(
          `ExecuteSpec run support${supportCode}`,
        )}`,
      };
    }
    default:
      return {type: action.type, label: action.label, behavior: 'tab', tab: 'Logs'};
  }
}

function isSafeTerminalActionHref(href: string | null | undefined): href is string {
  if (!href) return false;
  return (href.startsWith('/') && !href.startsWith('//')) || href.startsWith('mailto:');
}

export interface PublicationTrustFact {
  label: string;
  value: string;
  kind: 'ok' | 'warn' | 'bad' | 'neutral';
}

export function missingValidationReportExplanation(
  run: Pick<api.RunDetail, 'tasks'> | null | undefined,
): string {
  return run?.tasks?.length
    ? 'Worker steps ran, but no completed validation report was produced. Use the activity log and publication evidence to diagnose where validation stopped.'
    : 'Execution did not reach the worker phase, so no validation report was produced.';
}

export type CorrectionEvidenceAction = NonNullable<
  NonNullable<api.TaskSummary['continuationActions']>[number]
>;

export interface CorrectionEvidenceEntry {
  action: CorrectionEvidenceAction;
  taskId: string;
  taskTitle: string;
  taskIndex: number;
  actionIndex: number;
}

/**
 * A non-complete report cannot truthfully present its previously computed pipeline result as
 * current evidence. Missing lifecycle status remains backward compatible with older servers.
 */
export function reportPipelineLabel(
  report: Pick<api.ValidationReportData, 'reportStatus' | 'pipelineResult'> | null | undefined,
): string {
  if (!report) return '—';
  const status = report.reportStatus?.trim().toUpperCase();
  if (!status) return report.pipelineResult || '—';
  if (status === 'COMPLETE') return report.pipelineResult || '—';
  if (status === 'STALE') return 'Stale — validation regeneration required';
  if (status === 'PENDING') return 'Validation pending';
  if (status === 'FAILED') return 'Validation unavailable — generation failed';
  return `Validation ${status.toLowerCase()}`;
}

/**
 * Returns every durable correction in the exact task/action order delivered by run detail.
 * Actions do not carry a global timestamp, so this deliberately avoids inventing chronology.
 */
export function correctionEvidenceHistory(
  run: Pick<api.RunDetail, 'tasks'> | null | undefined,
): CorrectionEvidenceEntry[] {
  return (run?.tasks || []).flatMap((task, taskIndex) =>
    (task.continuationActions || [])
      .map((action, actionIndex) => ({action, actionIndex}))
      .filter(
        ({action}) =>
          action.beforeWorkspaceGeneration ||
          action.afterWorkspaceGeneration ||
          (action.code || '').toUpperCase().includes('CORRECTION'),
      )
      .map(({action, actionIndex}) => ({
        action,
        taskId: task.taskId,
        taskTitle: task.title,
        taskIndex,
        actionIndex,
      })),
  );
}

export function correctionEvidenceInvalidationLabel(
  evidenceInvalidated: boolean | undefined,
): string {
  if (evidenceInvalidated === true) return 'Yes — prior evidence was made stale';
  if (evidenceInvalidated === false) return 'No — prior evidence was explicitly retained';
  return 'Not recorded';
}

/**
 * Independent trust facts for the publication boundary. No score is used as a proxy for any row:
 * an eligible score cannot hide missing tests, a stale generation, or a route mismatch.
 */
export function publicationTrustFacts(
  run: api.RunDetail | null,
  publication?: api.PublicationEligibility | null,
): PublicationTrustFact[] {
  if (!run) return [];
  const tasks = run.tasks || [];
  const completed = tasks.filter((task) =>
    ['COMPLETE', 'DONE'].includes((task.status || '').toUpperCase()),
  ).length;
  const terminal = tasks.filter((task) =>
    ['COMPLETE', 'DONE', 'FAILED', 'PARTIAL', 'BLOCKED', 'CANCELLED', 'SKIPPED'].includes(
      (task.status || '').toUpperCase(),
    ),
  ).length;
  const failedOrBlocked = tasks.filter((task) =>
    ['FAILED', 'BLOCKED', 'CANCELLED'].includes((task.status || '').toUpperCase()),
  ).length;
  const actualRoutes = [
    ...new Set(
      tasks
        .filter((task) => task.effectiveProvider || task.effectiveModel)
        .map(
          (task) =>
            `${task.effectiveProvider || 'unknown'}/${task.effectiveModel || 'unknown'} (${task.effectiveRouteAttribution || 'UNATTRIBUTED'})`,
        ),
    ),
  ];
  const approvedActions = tasks.flatMap((task) =>
    (task.continuationActions || []).filter(
      (action) => (action.status || '').toUpperCase() === 'APPROVED',
    ),
  );
  const approvedRoutes = [
    ...new Set(
      approvedActions.map(
        (action) => `${action.provider || 'unknown'}/${action.model || 'unknown'}`,
      ),
    ),
  ];
  const exactAttempt = (
    action: (typeof approvedActions)[number],
    attempt: (typeof approvedActions)[number]['workerAttempt'],
  ) =>
    attempt?.attribution === 'LEDGER' &&
    attempt.provider?.toLowerCase() === action.provider?.toLowerCase() &&
    attempt.model === action.model &&
    (attempt.physicalAttemptIds?.length || 0) > 0;
  const workerAttemptsExact =
    approvedActions.length > 0 &&
    approvedActions.every((action) => exactAttempt(action, action.workerAttempt));
  const reviewerAttemptsExact =
    approvedActions.length > 0 &&
    approvedActions.every((action) => exactAttempt(action, action.reviewerAttempt));
  const attemptSummary = (attempt: (typeof approvedActions)[number]['workerAttempt']): string =>
    `${attempt?.provider || 'unattributed'}/${attempt?.model || 'unattributed'} (${
      attempt?.attribution || 'UNATTRIBUTED'
    } · ${attempt?.physicalAttemptIds?.length || 0} physical attempt${
      attempt?.physicalAttemptIds?.length === 1 ? '' : 's'
    })`;
  const correctionHistory = correctionEvidenceHistory(run);
  const correctionSummary = correctionHistory
    .map(({action, taskTitle, taskId, taskIndex, actionIndex}) => {
      const status = action.status || action.code || 'RECORDED';
      const detail = [
        `${taskIndex + 1}.${actionIndex + 1} ${taskTitle || taskId}: ${status}`,
        `${action.beforeWorkspaceGeneration?.slice(0, 12) || 'unknown'} → ${
          action.afterWorkspaceGeneration?.slice(0, 12) || 'unknown'
        }`,
        action.changedFiles?.length
          ? `${action.changedFiles.length} changed: ${action.changedFiles.join(', ')}`
          : 'changed files unreported',
        action.diffDigest ? `diff ${action.diffDigest.slice(0, 12)}` : null,
        action.evidenceInvalidated === true
          ? 'prior evidence invalidated'
          : action.evidenceInvalidated === false
            ? 'prior evidence explicitly retained'
            : null,
        action.validationRegenerated === true ? 'validation regenerated' : null,
      ]
        .filter(Boolean)
        .join(' · ');
      return detail;
    })
    .join(' | ');
  const correctionHasFailure = correctionHistory.some(({action}) =>
    ['REJECTED', 'ROLLED_BACK', 'FAILED'].includes((action.status || '').toUpperCase()),
  );
  const generationFresh =
    publication?.evidenceWorkspaceGeneration != null &&
    publication.currentWorkspaceGeneration != null &&
    publication.evidenceWorkspaceGeneration === publication.currentWorkspaceGeneration;
  const testsPassed = run.testsPassed ?? 0;
  const testsSkipped = run.testsSkipped ?? 0;
  const testsTotal = run.testsTotal;
  const testPopulationValid =
    testsTotal != null &&
    testsTotal > 0 &&
    testsPassed > 0 &&
    testsSkipped >= 0 &&
    testsPassed + testsSkipped === testsTotal;

  const facts: PublicationTrustFact[] = [
    {
      label: 'Tasks terminal',
      value: `${terminal}/${tasks.length} terminal · ${completed}/${tasks.length} completed`,
      kind:
        tasks.length > 0 && completed === tasks.length
          ? 'ok'
          : failedOrBlocked > 0
            ? 'bad'
            : 'warn',
    },
    {
      label: 'Approved correction route',
      value: approvedRoutes.length ? approvedRoutes.join(', ') : 'No approved continuation',
      kind: approvedRoutes.length ? 'neutral' : 'neutral',
    },
    {
      label: 'Actual worker route',
      value:
        approvedActions.length > 0
          ? approvedActions.map((action) => attemptSummary(action.workerAttempt)).join(', ')
          : actualRoutes.length > 0
            ? actualRoutes.join(', ')
            : `${run.effectiveProvider || 'unattributed'}/${run.effectiveModel || 'unattributed'} (${run.effectiveRouteAttribution || 'UNATTRIBUTED'})`,
      kind:
        approvedActions.length > 0
          ? workerAttemptsExact
            ? 'ok'
            : 'bad'
          : run.routeDiverged === true ||
              tasks.some(
                (task) => task.routeDiverged === true || task.effectiveRouteAttribution === 'MIXED',
              )
            ? 'bad'
            : 'neutral',
    },
    {
      label: 'Build',
      value:
        run.finalBuildPassed === true
          ? 'Passed'
          : run.finalBuildPassed === false
            ? 'Failed'
            : 'Unmeasured',
      kind: run.finalBuildPassed === true ? 'ok' : run.finalBuildPassed === false ? 'bad' : 'warn',
    },
    {
      label: 'Tests',
      value:
        run.testsTotal != null
          ? `${run.testsPassed ?? 0}/${run.testsTotal} passed · ${run.testsSkipped ?? 0} skipped · ${
              run.testExecutionVerified ? 'execution verified' : 'not execution verified'
            }`
          : 'Unmeasured',
      kind:
        run.testExecutionVerified && testPopulationValid
          ? 'ok'
          : run.testsTotal != null
            ? 'bad'
            : 'warn',
    },
    {
      label: 'Workflow/config',
      value: [run.workflowConfigStatus || 'Unmeasured', run.workflowConfigReason]
        .filter(Boolean)
        .join(' · '),
      kind:
        run.workflowConfigStatus === 'PASSED' || run.workflowConfigStatus === 'NOT_APPLICABLE'
          ? 'ok'
          : run.workflowConfigStatus === 'FAILED'
            ? 'bad'
            : 'warn',
    },
    {
      label: 'Protected contracts',
      value: run.brownfieldHttpCompatibilityStatus || 'Not applicable or unmeasured',
      kind:
        run.brownfieldHttpCompatibilityStatus === 'COMPATIBLE' ||
        run.brownfieldHttpCompatibilityStatus === 'BASELINE'
          ? 'ok'
          : run.brownfieldHttpCompatibilityStatus === 'BREAKING'
            ? 'bad'
            : 'neutral',
    },
    {
      label: 'Reviewer result',
      value: run.reviewerVerdict || 'Unmeasured',
      kind:
        run.reviewerVerdict === 'CLEAN' || run.reviewerVerdict === 'FIXED_ISSUES'
          ? 'ok'
          : run.reviewerVerdict === 'UNRESOLVED_ISSUES' || run.reviewerVerdict === 'REVIEW_FAILED'
            ? 'bad'
            : 'warn',
    },
    {
      label: 'Reviewer route',
      value:
        approvedActions.length > 0
          ? approvedActions.map((action) => attemptSummary(action.reviewerAttempt)).join(', ')
          : `${run.reviewerProvider || 'unattributed'}/${
              run.reviewerEffectiveModel || 'unattributed'
            }`,
      kind:
        approvedActions.length > 0
          ? reviewerAttemptsExact
            ? 'ok'
            : 'bad'
          : run.reviewerProvider && run.reviewerEffectiveModel
            ? 'neutral'
            : 'warn',
    },
    {
      label: 'Correction transactions',
      value: correctionHistory.length
        ? `${correctionHistory.length} recorded in task/action order (not chronology) · ${correctionSummary}`
        : 'No correction transaction',
      kind: correctionHasFailure ? 'warn' : 'neutral',
    },
    {
      label: 'Evidence freshness',
      value:
        publication?.evidenceWorkspaceGeneration && publication.currentWorkspaceGeneration
          ? `${generationFresh ? 'Fresh' : 'Stale'} · evidence ${publication.evidenceWorkspaceGeneration.slice(
              0,
              12,
            )} · current ${publication.currentWorkspaceGeneration.slice(0, 12)}`
          : run.evidenceWorkspaceGeneration
            ? `Evidence ${run.evidenceWorkspaceGeneration.slice(0, 12)} · current generation unavailable`
            : 'Unbound',
      kind: generationFresh ? 'ok' : publication ? 'bad' : 'warn',
    },
    {
      label: 'Publication',
      value: publication
        ? `${publication.eligibility} · ${publication.code}${
            publication.blockingGate ? ` · ${publication.blockingGate}` : ''
          }`
        : 'Eligibility unavailable',
      kind: publication?.eligible ? 'ok' : publication ? 'bad' : 'warn',
    },
    {
      label: 'Usage',
      value: `${run.totalInputTokens ?? 0} input · ${run.totalOutputTokens ?? 0} output · ${
        run.actualCostUsd == null ? 'cost unavailable' : `$${run.actualCostUsd.toFixed(4)}`
      }`,
      kind: 'neutral',
    },
  ];
  return facts;
}

/**
 * Reloadable terminal evidence comes only from the durable run row. In particular, a planner
 * rejection has no task rows or validation report, so those surfaces must render this evidence
 * instead of incorrectly claiming that there is nothing to show.
 */
export function terminalFailureEvidence(
  run?: Pick<api.RunDetail, 'status' | 'statusReason' | 'failureReason' | 'terminalOutcome'> | null,
): TerminalFailureEvidence | null {
  const status = (run?.status || '').toUpperCase();
  const outcome = run?.terminalOutcome;
  if (outcome && ['FAILED', 'ERROR', 'PARTIAL', 'CANCELLED'].includes(status)) {
    return {
      title: outcome.customerTitle,
      detail: outcome.customerMessage,
      kind:
        outcome.category === 'PLATFORM_FAILURE'
          ? 'bad'
          : outcome.category === 'USER_STOPPED'
            ? 'neutral'
            : 'warn',
      nextAction: outcome.nextAction?.label,
      supportCode: outcome.supportCode,
    };
  }
  const detail = run?.failureReason?.trim();
  if (!detail || !['FAILED', 'ERROR', 'PARTIAL'].includes(status)) return null;
  return {
    title:
      run?.statusReason === 'PLAN_VALIDATION_REJECTED'
        ? 'Planning rejection evidence'
        : run?.statusReason === 'PLANNER_UNAVAILABLE'
          ? 'Planning failure evidence'
          : 'Terminal failure evidence',
    detail,
    kind: 'bad',
  };
}

/**
 * Artifact actions require produced evidence, not merely a workspace cloned for the run. A clean
 * checkout can contain hundreds of files even when planning failed before the executor started.
 */
export function runArtifactActionAvailability(input: {
  status?: string | null;
  changedWorkspaceFiles: number;
  hasExecutionEvidence: boolean;
  hasValidationReport: boolean;
  publication?: api.PublicationEligibility | null;
  publicationCheckFailed?: boolean;
}): RunArtifactActionAvailability {
  const status = (input.status || '').toUpperCase();
  const terminal = ['COMPLETE', 'COMPLETED', 'PARTIAL', 'FAILED'].includes(status);
  const hasProducedWorkspace =
    terminal && input.hasExecutionEvidence && input.changedWorkspaceFiles > 0;
  const hasReport = terminal && input.hasValidationReport;
  const publicationEligible = input.publication?.eligible === true;
  const workspaceReason = !terminal
    ? 'This action becomes available after the run finishes.'
    : !input.hasExecutionEvidence
      ? 'This run stopped before execution and produced no code changes.'
      : input.changedWorkspaceFiles <= 0
        ? 'This run produced no code changes.'
        : '';
  const reportReason = !terminal
    ? 'This action becomes available after the run finishes.'
    : !input.hasValidationReport
      ? 'No validation report was produced for this run.'
      : '';
  const publicationReason = input.publicationCheckFailed
    ? 'Publication eligibility could not be verified. Retry the eligibility check before publishing.'
    : input.publication == null
      ? 'Checking publication eligibility…'
      : input.publication.eligible
        ? ''
        : input.publication.reason;
  return {
    push: hasProducedWorkspace && publicationEligible,
    revertWorkspace: hasProducedWorkspace,
    preview: hasProducedWorkspace,
    deploy:
      hasProducedWorkspace && ['COMPLETE', 'COMPLETED'].includes(status) && publicationEligible,
    reportPdf: hasReport,
    codeZip: hasProducedWorkspace,
    shareReport: hasReport,
    workspaceReason,
    publicationReason,
    reportReason,
  };
}

/** Bootstrap repository metadata exists before worker execution and is not generated product code. */
export function isBootstrapWorkspaceMetadata(path: string): boolean {
  const normalized = path.replace(/\\/g, '/').replace(/^\.\/+/, '');
  const basename = normalized.slice(normalized.lastIndexOf('/') + 1);
  return basename === '.gitignore' || basename === '.gitattributes';
}

export function countProducedWorkspaceFiles(
  entries: Array<Pick<api.FileWithStatus, 'path' | 'status'>>,
): number {
  return entries.filter(
    (entry) =>
      (entry.status === 'ADDED' || entry.status === 'MODIFIED') &&
      !isBootstrapWorkspaceMetadata(entry.path),
  ).length;
}

/**
 * A completed validation report owns the product-diff count. The workspace-status endpoint is a
 * broader inventory heuristic and can include deterministic run metadata such as README.md.
 */
export function authoritativeProductFileCount(
  reportedFiles: number | null | undefined,
  workspaceDeltaCandidates: number,
): number {
  return reportedFiles != null && reportedFiles >= 0 ? reportedFiles : workspaceDeltaCandidates;
}

/** Reviewer verdict is the gate result; severity buckets are only a legacy fallback. */
export function reviewerSignal(
  report: Pick<api.ValidationReportData, 'reviewerVerdict' | 'reviewFailures'> | null | undefined,
): string {
  if (!report) return '—';
  switch ((report.reviewerVerdict || '').toUpperCase()) {
    case 'CLEAN':
      return 'clean';
    case 'FIXED_ISSUES':
      return 'fixed issues';
    case 'UNRESOLVED_ISSUES':
      return 'unresolved issues';
    case 'REVIEW_FAILED':
      return 'review failed';
    default:
      return report.reviewFailures > 0 ? `${report.reviewFailures} unresolved` : 'not measured';
  }
}

export function verdictFor(
  score: number | null | undefined,
  status?: string | null,
  terminalOutcome?: api.RunTerminalOutcome | null,
): Verdict {
  const s = (status || '').toUpperCase();
  if (terminalOutcome?.category === 'USER_STOPPED') return {label: 'Cancelled', kind: 'warn'};
  if (
    terminalOutcome &&
    ['ACTION_REQUIRED', 'POLICY_BLOCKED', 'WARNING'].includes(terminalOutcome.category)
  ) {
    return {label: terminalOutcome.customerTitle, kind: 'warn'};
  }
  if (terminalOutcome?.category === 'PLATFORM_FAILURE') return {label: 'Failed', kind: 'bad'};
  if (s === 'FAILED' || s === 'ERROR' || s === 'CANCELLED') return {label: 'Failed', kind: 'bad'};
  if (score == null) {
    if (s === 'COMPLETE') return {label: 'Complete', kind: 'warn'};
    if (s === 'PARTIAL') return {label: 'Needs review', kind: 'warn'};
    return {label: 'In progress', kind: 'warn'};
  }
  if (score >= 85) return {label: 'Shippable', kind: 'ok'};
  if (score >= 70) return {label: 'Needs review', kind: 'warn'};
  return {label: 'Failed', kind: 'bad'};
}

export function missingScoreExplanation(
  run: Pick<api.RunSummary, 'runMode' | 'status' | 'triggeredByFinding'>,
): string {
  const status = (run.status || '').toUpperCase();
  if (run.runMode === 'PATCH' || run.triggeredByFinding) {
    return "No verified score for this run — patch and benchmark runs aren't scored by the validation pipeline.";
  }
  if (['FAILED', 'ERROR', 'CANCELLED', 'TIMED_OUT', 'STOPPED', 'PARTIAL'].includes(status)) {
    return 'No verified score was produced because this run stopped before validation completed.';
  }
  return 'A verified score will appear after validation completes.';
}

export const SEV: Record<string, {l: string; k: 'ok' | 'warn' | 'bad'}> = {
  WOULD_BREAK_BUILD: {l: 'BUILD', k: 'bad'},
  WOULD_FAIL_REVIEW: {l: 'REVIEW', k: 'warn'},
  WOULD_CAUSE_BUG: {l: 'BUG', k: 'warn'},
  WOULD_FAIL_LINT: {l: 'LINT', k: 'ok'},
};

export const evidenceKind = (status?: string): 'ok' | 'warn' | 'bad' =>
  status === 'PASS' ? 'ok' : status === 'FAIL' ? 'bad' : 'warn';

export const confidenceColor = (confidence?: string) =>
  confidence === 'HIGH'
    ? 'var(--ide-green)'
    : confidence === 'LOW'
      ? 'var(--ide-red)'
      : 'var(--ide-yellow)';

export const usd = (n?: number | null) => (n == null ? '—' : `$${n.toFixed(4)}`);
export const num = (n?: number | null) => (n == null ? '—' : n.toLocaleString());

export const TABS = [
  'Overview',
  'Code',
  'Screens',
  'Tasks',
  'Timeline',
  'Logs',
  'Validation',
  'Cost',
  'PR',
  'Tests',
  'Chat',
] as const;

export type Tab = (typeof TABS)[number];

export const TAB_LABEL: Record<Tab, string> = {
  Overview: 'Overview',
  Code: 'Code',
  Screens: 'Screens',
  Tasks: 'Steps',
  Timeline: 'Timeline',
  Logs: 'Activity log',
  Validation: 'Evidence',
  Cost: 'Cost',
  PR: 'Pull request',
  Tests: 'Tests',
  Chat: 'Ask',
};

export function toLines(s: string): string[] {
  return (s || '').replace(/\n$/, '').split('\n');
}

/**
 * LCS line diff: `base` (old) -> `head` (new). Deletions in a hunk are emitted before additions
 * that replace them, matching standard diff grouping. The public boundary is deliberately bounded:
 * the classic traceback matrix is quadratic and must never be allocated for arbitrary provider- or
 * repository-sized content.
 */
export const LINE_DIFF_LIMITS = Object.freeze({
  maxInputCharsPerSide: 200_000,
  maxLinesPerSide: 2_000,
  maxMatrixCells: 1_000_000,
});

export interface LineDiffResult {
  lines: DiffLine[];
  truncated: boolean;
  reason: string;
}

export function boundedLineDiff(
  base: string,
  head: string,
  limits: {
    maxInputCharsPerSide?: number;
    maxLinesPerSide?: number;
    maxMatrixCells?: number;
  } = {},
): LineDiffResult {
  const maxInputCharsPerSide = limits.maxInputCharsPerSide ?? LINE_DIFF_LIMITS.maxInputCharsPerSide;
  const maxLinesPerSide = limits.maxLinesPerSide ?? LINE_DIFF_LIMITS.maxLinesPerSide;
  const maxMatrixCells = limits.maxMatrixCells ?? LINE_DIFF_LIMITS.maxMatrixCells;
  const a = toLines(base);
  const b = toLines(head);
  const n = a.length;
  const m = b.length;
  if (base.length > maxInputCharsPerSide || head.length > maxInputCharsPerSide) {
    return {
      lines: [],
      truncated: true,
      reason: `Content diff was not computed because one version exceeds the ${maxInputCharsPerSide.toLocaleString()}-character safety limit.`,
    };
  }
  if (n > maxLinesPerSide || m > maxLinesPerSide) {
    return {
      lines: [],
      truncated: true,
      reason: `Content diff was not computed because one version exceeds the ${maxLinesPerSide.toLocaleString()}-line safety limit.`,
    };
  }
  if (n * m > maxMatrixCells) {
    return {
      lines: [],
      truncated: true,
      reason: `Content diff was not computed because the ${n.toLocaleString()} × ${m.toLocaleString()} comparison exceeds the ${maxMatrixCells.toLocaleString()}-cell safety limit.`,
    };
  }
  const lcs: number[][] = Array.from({length: n + 1}, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({sign: ' ', text: a[i]});
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({sign: '-', text: a[i]});
      i++;
    } else {
      out.push({sign: '+', text: b[j]});
      j++;
    }
  }
  while (i < n) out.push({sign: '-', text: a[i++]});
  while (j < m) out.push({sign: '+', text: b[j++]});
  return {lines: out, truncated: false, reason: ''};
}

/** Backward-compatible safe line list for existing viewers that do not render truncation metadata. */
export function lineDiff(base: string, head: string): DiffLine[] {
  return boundedLineDiff(base, head).lines;
}

export function fmtDur(ms?: number | null) {
  if (!ms) return '—';
  const s = Math.round(ms / 1000);
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
}

export interface TimelineTaskTiming {
  startedAt?: string | null;
  completedAt?: string | null;
}

/**
 * Convert persisted task timestamps into safe chart coordinates.
 *
 * Historical and interrupted tasks can legitimately have no start timestamp. JavaScript treats
 * `new Date(null)` as the Unix epoch, which previously rendered those rows as multi-million-minute
 * tasks. Only finite, positive starts are chartable; a malformed or pre-start completion is also
 * rejected instead of fabricating a duration.
 */
export function timelineTiming(
  task: TimelineTaskTiming,
  nowMs = Date.now(),
): {startMs: number; endMs: number; durationMs: number} | null {
  if (!task.startedAt) return null;
  const startMs = Date.parse(task.startedAt);
  if (!Number.isFinite(startMs) || startMs <= 0) return null;

  const parsedEnd = task.completedAt ? Date.parse(task.completedAt) : nowMs;
  if (!Number.isFinite(parsedEnd) || parsedEnd < startMs) return null;
  return {startMs, endMs: parsedEnd, durationMs: parsedEnd - startMs};
}

export function testResultSummary(
  tests: Pick<api.TestReportData, 'totalTests' | 'passed' | 'failed' | 'errors'>,
) {
  if (tests.totalTests <= 0) {
    return {
      measured: false,
      headline: 'Not measured',
      label: 'No tests executed',
      explanation:
        'A compiled test plan is not execution evidence. Re-run tests to publish measured pass/fail results.',
    };
  }
  return {
    measured: true,
    headline: `${tests.passed}/${tests.totalTests}`,
    label: 'passed',
    explanation: null,
  };
}

export function testEvidenceSummary(
  passed: number | null | undefined,
  total: number | null | undefined,
  evidence?: Pick<api.VerificationEvidence, 'source' | 'status'> | null,
) {
  const measured = total != null && total > 0;
  const source = (evidence?.source || '').toUpperCase();
  const status = (evidence?.status || '').toUpperCase();
  const functional = source === 'DETERMINISTIC_FUNCTIONAL' && status !== 'UNKNOWN';
  const workspaceOnly = source === 'DETERMINISTIC_WORKSPACE_SUITE';
  return {
    measured,
    functional,
    workspaceOnly,
    label: measured
      ? `${passed ?? 0}/${total}${
          functional ? ' functional' : workspaceOnly ? ' workspace-only' : ' unverified'
        }`
      : 'not measured',
  };
}

/** Human-readable source for a continuation decision. */
export function continuationSourceLabel(source?: string | null): string | null {
  switch ((source || '').toUpperCase()) {
    case 'POST_REVIEW':
      return 'Reviewer/build trigger';
    case 'POST_VALIDATION_REPORT':
      return 'Final validation trigger';
    case 'TASK_BOUNDARY':
      return 'Task trigger';
    default:
      return source ? source.replace(/_/g, ' ').toLowerCase() : null;
  }
}

export function taskGoalLine(t: api.TaskSummary): string | null {
  const goal = t.goalDoneness;
  const continuation = t.continuationDecision;
  if (!goal && !continuation) return null;
  const source = continuationSourceLabel(continuation?.source || continuation?.policy?.source);
  const goalPart =
    goal?.state && goal?.recommendedDecision
      ? `Goal ${goalStateLabel(goal.state)} -> ${goalDecisionLabel(goal.recommendedDecision)}`
      : goal?.state
        ? `Goal ${goalStateLabel(goal.state)}`
        : null;
  const nextPart = continuation?.decision
    ? `${source ? `${source} · ` : ''}Next ${goalDecisionLabel(continuation.decision)}`
    : null;
  const reason = continuation?.reasons?.[0] || goal?.reason || null;
  return [goalPart, nextPart, reason].filter(Boolean).join(' · ');
}

export function continuationApprovalCandidate(t: api.TaskSummary) {
  const continuation = t.continuationDecision;
  const candidate = continuation?.selectedCandidate;
  const explicitConfirmation = continuation?.policy?.requireConfirmationBeforeExpensiveContinuation;
  const benchmarkConfidenceBlocked =
    continuation?.policy?.requireBenchmarkPriorForAutomaticContinuation &&
    candidate?.benchmarkQualityPriorAvailable === false;
  if (
    continuation?.decision !== 'STOP_BLOCKED' ||
    (!explicitConfirmation && !benchmarkConfidenceBlocked) ||
    !candidate?.provider ||
    !candidate?.model
  ) {
    return null;
  }
  if (matchingContinuationApprovalRecorded(t)) return null;
  return candidate;
}

/**
 * The task decision can remain STOP_BLOCKED briefly after approval while the async retry settles.
 * The durable action history is authoritative during that window: never offer the same paid
 * approval twice when the selected route already has an APPROVED or EXECUTED action.
 */
export function matchingContinuationApprovalRecorded(t: api.TaskSummary): boolean {
  const candidate = t.continuationDecision?.selectedCandidate;
  if (!candidate?.provider || !candidate.model) return false;
  const provider = candidate.provider.toLowerCase();
  const model = candidate.model.toLowerCase();
  return (t.continuationActions || []).some((action) => {
    const status = (action.status || '').toUpperCase();
    return (
      ['APPROVED', 'EXECUTED'].includes(status) &&
      (action.provider || '').toLowerCase() === provider &&
      (action.model || '').toLowerCase() === model
    );
  });
}

export function continuationApprovalReason(t: api.TaskSummary): string | null {
  const continuation = t.continuationDecision;
  if (continuation?.decision !== 'STOP_BLOCKED') return null;
  const source = continuationSourceLabel(continuation.source || continuation.policy?.source);
  const prefix = source ? `${source}: ` : '';
  switch ((continuation.approvalRequiredReason || '').toUpperCase()) {
    case 'BENCHMARK_PRIOR_REQUIRED':
      return `${prefix}Approval needed: no usable benchmark prior for this model on this task category.`;
    case 'EXPENSIVE_CONTINUATION_CONFIRMATION_REQUIRED':
      return `${prefix}Approval needed: this run requires confirmation before model/provider upgrade spend.`;
    case 'ROUTE_PINNED':
      return `${prefix}Blocked: this run is pinned to its selected provider/model, so automatic upgrade cannot change route.`;
    case 'APPROVAL_REQUIRED':
      return `${prefix}Approval needed before this model/provider upgrade can run.`;
    default:
      break;
  }
  const candidate = continuation.selectedCandidate;
  if (
    continuation.policy?.requireBenchmarkPriorForAutomaticContinuation &&
    candidate?.benchmarkQualityPriorAvailable === false
  ) {
    return `${prefix}Approval needed: no usable benchmark prior for this model on this task category.`;
  }
  if (continuation.policy?.requireConfirmationBeforeExpensiveContinuation) {
    return `${prefix}Approval needed: this run requires confirmation before model/provider upgrade spend.`;
  }
  if (continuation.policy?.routePinned) {
    return `${prefix}Blocked: this run is pinned to its selected provider/model, so automatic upgrade cannot change route.`;
  }
  return null;
}

export function latestContinuationAction(t: api.TaskSummary): string | null {
  const actions = t.continuationActions || [];
  const action = actions.length > 0 ? actions[actions.length - 1] : null;
  if (!action?.decision) return null;
  if (action.decision === 'CORRECTION_RESOLUTION') {
    const status = (action.status || 'UNKNOWN').replace(/_/g, ' ').toLowerCase();
    const generationChanged =
      action.beforeWorkspaceGeneration &&
      action.afterWorkspaceGeneration &&
      action.beforeWorkspaceGeneration !== action.afterWorkspaceGeneration;
    return [
      `Correction ${status}`,
      action.code,
      action.reason,
      generationChanged ? 'new workspace evidence required' : null,
    ]
      .filter(Boolean)
      .join(' · ');
  }
  if (!action.provider || !action.model) return null;
  const rawStatus = (action.status || '').toUpperCase();
  const status =
    rawStatus === 'SKIPPED' ? 'Skipped' : rawStatus === 'APPROVED' ? 'Approved' : 'Used';
  const source = continuationSourceLabel(action.source);
  const sourcePart = source ? `${source}: ` : '';
  const limit =
    action.limitName && action.maxLimit != null
      ? ` · ${action.limitName} ${action.usedLimit ?? 0}/${action.maxLimit}`
      : '';
  return `${sourcePart}${status} ${action.decision} -> ${action.provider}/${action.model}${limit}`;
}

export function goalStateKind(state?: string | null): 'ok' | 'warn' | 'bad' {
  const s = (state || '').toUpperCase();
  if (s === 'DONE') return 'ok';
  if (s === 'FAILED' || s === 'BLOCKED' || s === 'WASTE_RISK') return 'bad';
  return 'warn';
}

export function goalProofKind(proofStatus?: string | null): 'ok' | 'warn' | 'bad' {
  const status = (proofStatus || '').toUpperCase();
  if (status === 'PROVEN') return 'ok';
  if (status === 'BLOCKING') return 'bad';
  return 'warn';
}

export function goalProofLabel(proofStatus?: string | null): string {
  const status = (proofStatus || '').toUpperCase();
  if (status === 'PROVEN') return 'Proven';
  if (status === 'BLOCKING') return 'Blocking';
  if (status === 'UNPROVEN') return 'Unproven';
  return 'Context';
}

function preciseUsd(n?: number | null): string {
  if (n == null) return '—';
  if (Math.abs(n) < 1) return `$${n.toFixed(4)}`;
  return usd(n);
}

function positive(n?: number | null): number | null {
  return typeof n === 'number' && Number.isFinite(n) && n > 0 ? n : null;
}

export function taskBudgetFacts(t: api.TaskSummary): {k: string; v: string}[] {
  const goal = t.goalDoneness;
  if (!goal) return [];
  const policy = t.continuationDecision?.policy || {};
  const facts: {k: string; v: string}[] = [];
  const cost = positive(goal.costUsd);
  const costLimit = positive(policy.maxTotalCostUsd);
  if (cost != null || costLimit != null) {
    facts.push({
      k: 'Cost',
      v: costLimit != null ? `${preciseUsd(cost)} / ${preciseUsd(costLimit)}` : preciseUsd(cost),
    });
  }
  const tokens = positive(goal.totalTokens);
  const tokenLimit = positive(policy.maxTotalTokens);
  if (tokens != null || tokenLimit != null) {
    facts.push({
      k: 'Tokens',
      v: tokenLimit != null ? `${num(tokens)} / ${num(tokenLimit)}` : num(tokens),
    });
  }
  const wall = positive(goal.wallClockMs);
  const wallLimit = positive(policy.maxTaskWallClockMs);
  if (wall != null || wallLimit != null) {
    facts.push({
      k: 'Time',
      v: wallLimit != null ? `${fmtDur(wall)} / ${fmtDur(wallLimit)}` : fmtDur(wall),
    });
  }
  if (goal.budgetCeilingReason) facts.push({k: 'Ceiling', v: goal.budgetCeilingReason});
  return facts;
}

const RAW_COMMAND_TITLE =
  /^(?:\$|>|npm(?:\s+run)?|npx|pnpm|yarn|mvnw?|gradlew?|gradle|pytest|python\d*|docker|git|go\s+test|cargo)\b/i;

/**
 * Planner titles are durable audit evidence, but some older plans used a build command or a
 * multi-line instruction as the title. Keep that raw value in Technical details and give the
 * default customer surface a bounded, readable label.
 */
export function customerStepTitle(t: Pick<api.TaskSummary, 'title'>, index: number): string {
  const firstLine = (t.title || '').trim().split(/\r?\n/, 1)[0]?.trim() || '';
  if (!firstLine || RAW_COMMAND_TITLE.test(firstLine)) return `Step ${index + 1}`;
  const withoutCommandFragments = firstLine
    .replace(
      /`(?:npm|npx|pnpm|yarn|mvnw?|gradlew?|gradle|pytest|python\d*|docker|git|go\s+test|cargo)[^`]*`/gi,
      'the project checks',
    )
    .replace(/\s+/g, ' ')
    .trim();
  if (!withoutCommandFragments) return `Step ${index + 1}`;
  return withoutCommandFragments.length > 120
    ? `${withoutCommandFragments.slice(0, 117).trimEnd()}…`
    : withoutCommandFragments;
}

/** One non-technical status sentence for the collapsed customer-facing step card. */
export function customerStepSummary(t: Pick<api.TaskSummary, 'status'>): string {
  switch ((t.status || '').toUpperCase()) {
    case 'COMPLETE':
    case 'SUCCESS':
      return 'Completed with recorded execution evidence.';
    case 'FAILED':
    case 'ERROR':
      return 'This step did not complete. Review technical details or retry it.';
    case 'BLOCKED':
      return 'This step needs input or approval before it can continue.';
    case 'PARTIAL':
      return 'This step completed only part of its objective. Review the remaining work.';
    default:
      return 'This step is still in progress.';
  }
}

export function customerStepStatusLabel(status?: string | null): string {
  switch ((status || '').toUpperCase()) {
    case 'COMPLETE':
    case 'SUCCESS':
      return 'Complete';
    case 'FAILED':
    case 'ERROR':
      return 'Failed';
    case 'BLOCKED':
      return 'Needs input';
    case 'PARTIAL':
      return 'Needs review';
    default:
      return 'In progress';
  }
}

/** Customer-safe continuation copy; exact action enums, routes and models remain in diagnostics. */
export function customerContinuationSummary(t: api.TaskSummary): string | null {
  if (matchingContinuationApprovalRecorded(t)) {
    return 'The approved recovery was recorded. Review its result in technical details.';
  }
  if (continuationApprovalCandidate(t)) {
    return 'Approval is needed before ExecuteSpec can try the recommended recovery.';
  }
  const decision = (t.continuationDecision?.decision || '').toUpperCase();
  if (['STEP_UP_MODEL', 'STEP_DOWN_MODEL', 'CHANGE_PROVIDER'].includes(decision)) {
    return 'ExecuteSpec identified another recovery option for this step.';
  }
  if (decision === 'STOP_BLOCKED') {
    return 'Automatic recovery is blocked. Open technical details for the recorded reason.';
  }
  return null;
}

/**
 * Do not surface the legacy catch-all "budget, token, iteration, or wall-clock ceiling" sentence.
 * Resolve a concrete reached limit when the recorded metrics allow it; otherwise state honestly
 * that a configured guard fired and point to the disclosed diagnostics.
 */
export function customerLimitSummary(t: api.TaskSummary): string | null {
  if (['COMPLETE', 'SUCCESS'].includes((t.status || '').toUpperCase())) return null;
  const reason = t.goalDoneness?.budgetCeilingReason?.trim();
  if (!reason) return null;
  const goal = t.goalDoneness;
  const policy = t.continuationDecision?.policy;
  const cost = positive(goal?.costUsd);
  const costLimit = positive(policy?.maxTotalCostUsd);
  if (cost != null && costLimit != null && cost >= costLimit) {
    return `Stopped at the configured ${preciseUsd(costLimit)} cost limit.`;
  }
  const tokens = positive(goal?.totalTokens);
  const tokenLimit = positive(policy?.maxTotalTokens);
  if (tokens != null && tokenLimit != null && tokens >= tokenLimit) {
    return 'Stopped at the configured processing limit.';
  }
  const wall = positive(goal?.wallClockMs);
  const wallLimit = positive(policy?.maxTaskWallClockMs);
  if (wall != null && wallLimit != null && wall >= wallLimit) {
    return `Stopped at the configured ${fmtDur(wallLimit)} time limit.`;
  }
  return 'Execution stopped at a configured safety limit. Open technical details to see the recorded guard.';
}

export function shouldUseRunEvidenceChat(message: string): boolean {
  const text = message.trim().toLowerCase();
  if (!text) return true;
  const explicitMutation =
    /\b(change|modify|edit|fix|implement|add|remove|refactor|rewrite|create|start|rerun|re-run|iterate|apply|push|deploy|open\s+(a\s+)?pr)\b/.test(
      text,
    );
  const questionPrefix =
    /^(why|what|where|which|how|explain|show|summarize|tell|list|did|was|were|has|have)\b/.test(
      text,
    );
  const evidenceKeyword =
    /\b(evidence|score|cost|token|credit|test|build|review|file|diff|log|failure|failed|partial|status|result|report|artifact|pr)\b/.test(
      text,
    );
  const hardEvidenceKeyword =
    /\b(evidence|score|cost|token|credit|log|report|artifact|status|result)\b/.test(text);
  if (explicitMutation && !questionPrefix && !hardEvidenceKeyword) return false;
  if (questionPrefix || evidenceKeyword) return true;
  return !explicitMutation;
}

/**
 * Provider/dispatch failures are operator diagnostics, not useful chat answers. Keep the durable
 * failure visible in server logs while giving the run owner a bounded recovery path instead of
 * leaking an implementation exception into the Ask surface.
 */
export function friendlyAiFailureMessage(error: unknown): string {
  const raw = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  const normalized = raw.toLowerCase();
  if (
    normalized.includes('durable llm execution-context budget is exhausted') ||
    (normalized.includes('llmdispatchpolicyexception') && normalized.includes('budget'))
  ) {
    return 'This run has used its available AI context, so it cannot answer another question in the current context. Start follow-up work for a code change, or open Evidence, Code, and Activity log to inspect this run without additional AI spend.';
  }
  if (
    normalized.includes('managed ai credit') ||
    normalized.includes('ai credits required') ||
    normalized.includes('provider budget')
  ) {
    return 'Ask is unavailable because this account has no usable AI budget. Review the run evidence without additional AI spend, or update the provider or AI-credit settings before retrying.';
  }
  if (
    normalized.includes('provider credential') ||
    normalized.includes('api-key not configured') ||
    normalized.includes('api key not configured')
  ) {
    return 'Ask cannot reach the selected provider. Check the provider credential and route, then retry.';
  }
  return raw || 'Ask could not complete. Retry, or inspect Evidence and Activity log for this run.';
}

/**
 * Preserve the typed Ask completion contract at the rendering boundary. Current servers append the
 * same notice to the reply, while this fallback keeps mixed-version deployments honest.
 */
export function runChatResponseContent(response: api.ChatResponse): string {
  const content = friendlyAiFailureMessage(response.reply);
  if (response.complete !== false) return content;
  if (
    response.incompleteReason === 'PROVIDER_OUTPUT_LIMIT' &&
    !content.toLowerCase().includes('provider stopped at its output limit')
  ) {
    return `${content}\n\n_(The provider stopped at its output limit, so this answer is incomplete. Ask a narrower question or name a specific file, then retry.)_`;
  }
  return content;
}

export function isFailedTerminalStatus(status?: string | null): boolean {
  return ['FAILED', 'ERROR', 'PARTIAL'].includes((status || '').toUpperCase());
}

export function hasRunCostEvidence(
  run:
    | Pick<
        api.RunDetail,
        | 'totalInputTokens'
        | 'totalOutputTokens'
        | 'aiCreditsCharged'
        | 'actualCostUsd'
        | 'classifierCostUsd'
        | 'outlineCostUsd'
        | 'expansionCostUsd'
      >
    | null
    | undefined,
): boolean {
  if (!run) return false;
  return (
    (run.totalInputTokens ?? 0) > 0 ||
    (run.totalOutputTokens ?? 0) > 0 ||
    (run.aiCreditsCharged ?? 0) > 0 ||
    (run.actualCostUsd ?? 0) > 0 ||
    (run.classifierCostUsd ?? 0) > 0 ||
    (run.outlineCostUsd ?? 0) > 0 ||
    (run.expansionCostUsd ?? 0) > 0
  );
}

export function testRerunState({
  status,
  hasTestPlan,
  hasTests,
  hasProducedWorkspace = false,
}: {
  status?: string | null;
  hasTestPlan: boolean;
  hasTests: boolean;
  hasProducedWorkspace?: boolean;
}): {canRerunTests: boolean; emptyMessage: string} {
  const canRerunTests = hasTestPlan || hasTests || hasProducedWorkspace;
  if (canRerunTests) {
    return {canRerunTests, emptyMessage: 'No test report yet.'};
  }
  const emptyMessage = isFailedTerminalStatus(status)
    ? 'No test plan or measured tests are available for this failed run. Re-run is disabled until the run has generated code or a test plan to execute.'
    : 'No test report yet.';
  return {canRerunTests, emptyMessage};
}

/**
 * Manual test execution is queued by the API and completes asynchronously. Keep
 * the result surface honest while that work is in flight instead of treating the
 * first 0/0 response as the final report.
 */
export function isTestExecutionTerminal(status?: string | null): boolean {
  return ['COMPLETED', 'FAILED'].includes((status || '').toUpperCase());
}

export const TEST_EXECUTION_POLL_INTERVAL_MS = 2000;
export const TEST_EXECUTION_MAX_POLLS = 45;

export function fileCountSummary({
  reportedFiles,
  workspaceFiles,
}: {
  reportedFiles?: number | null;
  workspaceFiles: number;
}): {label: string; title: string; mismatch: boolean; explanation: string | null} {
  const mismatch = reportedFiles != null && reportedFiles !== workspaceFiles;
  const label =
    reportedFiles != null ? `${reportedFiles} product change(s)` : String(workspaceFiles);
  return {
    label,
    title:
      reportedFiles != null
        ? `${reportedFiles} product file change(s) proven by validation`
        : `${workspaceFiles} workspace delta candidate(s); validation evidence is not available`,
    mismatch,
    explanation: mismatch
      ? `Validation proves ${reportedFiles} product change(s). The workspace inventory also contains ${workspaceFiles} delta candidate(s), which can include deterministic run metadata.`
      : null,
  };
}

export function emptyCodeTabMessage({
  hasFiles,
  status,
}: {
  hasFiles: boolean;
  status?: string | null;
}): string {
  if (hasFiles) return 'Select a file';
  return isFailedTerminalStatus(status)
    ? 'No generated files. This failed run did not leave product code to inspect.'
    : 'No generated files';
}

export type RunPhaseDiagnosis = {
  title: string;
  body: string;
  facts: string[];
  primaryTab: Tab;
};

export function goalStateLabel(state?: string | null): string {
  switch ((state || '').toUpperCase()) {
    case 'DONE':
      return 'Goal proven';
    case 'FAILED':
      return 'Proof failed';
    case 'BLOCKED':
      return 'Blocked';
    case 'WASTE_RISK':
      return 'Waste risk';
    case 'IN_PROGRESS':
      return 'Still checking';
    case 'UNKNOWN':
      return 'Proof unknown';
    default:
      return state ? titleCaseType(state) : 'Proof unknown';
  }
}

export function goalDecisionLabel(decision?: string | null): string {
  switch ((decision || '').toUpperCase()) {
    case 'STOP_DONE':
      return 'Proof accepted';
    case 'STOP_FAILED':
      return 'Proof failed';
    case 'STOP_BLOCKED':
      return 'Needs input';
    case 'STOP_WASTE_GUARD':
      return 'Stopped by limit';
    case 'CONTINUE':
    case 'CONTINUE_SAME_MODEL_PROVIDER':
      return 'Keep working';
    case 'STEP_UP_MODEL':
      return 'Try stronger model';
    case 'STEP_DOWN_MODEL':
      return 'Try cheaper model';
    case 'CHANGE_PROVIDER':
      return 'Try another provider';
    default:
      return decision ? titleCaseType(decision) : 'No decision yet';
  }
}

export function goalDecisionHelp(decision?: string | null): string | null {
  switch ((decision || '').toUpperCase()) {
    case 'STOP_FAILED':
      return 'The system could not prove the requested outcome from available evidence. This is a proof failure, not a claim that every generated file is unusable.';
    case 'STOP_BLOCKED':
      return 'The run needs user input, missing environment access, or explicit approval before it can continue.';
    case 'STOP_WASTE_GUARD':
      return 'The run stopped because a configured spend, token, time, or repetition guard fired.';
    case 'STOP_DONE':
      return 'The available evidence was enough to accept the goal as complete.';
    default:
      return null;
  }
}

function lastUsefulLog(events?: api.RunLogEvent[] | null): string | null {
  const event = [...(events || [])]
    .reverse()
    .find(
      (ev) =>
        !isNoisyLogEvent(ev) &&
        !['RUN_DETAIL', 'RUN_STATUS'].includes((ev.type || '').toUpperCase()),
    );
  return event ? logMessage(event) : null;
}

export function runPhaseDiagnosis({
  status,
  tasks,
  report,
  tests,
  eventLog,
}: {
  status?: string | null;
  tasks?: Pick<api.TaskSummary, 'status' | 'failureReason'>[] | null;
  report?: Pick<
    api.ValidationReportData,
    'reviewerVerdict' | 'reviewFailures' | 'finalBuildPassed' | 'testsTotal' | 'testsPassed'
  > | null;
  tests?: Pick<api.TestReportData, 'totalTests' | 'passed'> | null;
  eventLog?: api.RunLogEvent[] | null;
}): RunPhaseDiagnosis | null {
  const s = (status || '').toUpperCase();
  const reviewLike = ['REVIEWING', 'VALIDATING', 'GENERATING_REPORT', 'REPORTING'].includes(s);
  if (!reviewLike) return null;

  const taskList = tasks || [];
  const completeTasks = taskList.filter(
    (t) => (t.status || '').toUpperCase() === 'COMPLETE',
  ).length;
  const blockedTasks = taskList.filter((t) =>
    ['FAILED', 'PARTIAL', 'BLOCKED'].includes((t.status || '').toUpperCase()),
  ).length;
  const runningTasks = taskList.filter((t) =>
    ['RUNNING', 'WORKING', 'IN_PROGRESS'].includes((t.status || '').toUpperCase()),
  ).length;
  const facts: string[] = [];
  if (taskList.length > 0) {
    facts.push(
      `${completeTasks}/${taskList.length} worker tasks executed · final verification is separate`,
    );
  }
  if (runningTasks > 0)
    facts.push(`${runningTasks} step${runningTasks === 1 ? '' : 's'} still active`);
  if (blockedTasks > 0)
    facts.push(`${blockedTasks} step${blockedTasks === 1 ? '' : 's'} need attention`);

  if (report) {
    facts.push(`reviewer ${report.reviewerVerdict || 'reported'}`);
    if ((report.reviewFailures ?? 0) > 0) facts.push(`${report.reviewFailures} reviewer issue(s)`);
    if (report.finalBuildPassed === true) facts.push('final build passed');
    if (report.finalBuildPassed === false) facts.push('final build failed');
  } else {
    facts.push('validation report not ready');
  }

  const totalTests = tests?.totalTests ?? report?.testsTotal ?? null;
  const passedTests = tests?.passed ?? report?.testsPassed ?? null;
  if (totalTests != null && totalTests > 0) {
    facts.push(`${passedTests ?? 0}/${totalTests} tests passed`);
  }

  const latest = lastUsefulLog(eventLog);
  if (latest) facts.push(`latest: ${latest}`);

  if (s === 'REVIEWING') {
    return {
      title: 'Reviewer is still resolving this run.',
      body:
        blockedTasks > 0
          ? 'At least one worker step already needs attention. Check Steps and Activity Log before assuming the reviewer is simply slow.'
          : 'Worker output is waiting on reviewer or final build/test validation. If this does not move, the Activity Log should show the last reviewer, tool, or validation event.',
      facts: facts.slice(0, 5),
      primaryTab: blockedTasks > 0 ? 'Tasks' : 'Logs',
    };
  }

  return {
    title: 'Validation evidence is still being assembled.',
    body: 'The run has moved past worker execution, but the final report or measured proof is not ready yet. Check Activity Log for the last validation event and Evidence once the report appears.',
    facts: facts.slice(0, 5),
    primaryTab: 'Logs',
  };
}

export function shouldShowModelRanking(
  t: Pick<api.TaskSummary, 'continuationDecision' | 'goalDoneness'>,
): boolean {
  const continuation = t.continuationDecision;
  const policy = continuation?.policy;
  if (!policy?.rankingPolicy || !policy.topEligibleCandidates?.length) return false;

  const decision = (continuation?.decision || '').toUpperCase();
  const goalDecision = (t.goalDoneness?.recommendedDecision || '').toUpperCase();
  if (decision === 'STOP_DONE' || goalDecision === 'STOP_DONE') return false;

  return ['STEP_UP_MODEL', 'STEP_DOWN_MODEL', 'CHANGE_PROVIDER', 'STOP_BLOCKED'].includes(decision);
}

type ContinuationPolicy = NonNullable<
  NonNullable<api.TaskSummary['continuationDecision']>['policy']
>;

type RankingCandidate = NonNullable<ContinuationPolicy['topEligibleCandidates']>[number];

function sourceLabel(source?: string | null): string | null {
  switch ((source || '').toUpperCase()) {
    case 'POST_REVIEW':
      return 'Reviewer/build trigger';
    case 'POST_VALIDATION_REPORT':
      return 'Final validation trigger';
    case 'TASK_BOUNDARY':
      return 'Task trigger';
    default:
      return source ? source.replace(/_/g, ' ').toLowerCase() : null;
  }
}

function providerDisplay(provider?: string | null): string {
  const raw = text(provider);
  if (!raw) return 'Provider';
  switch (raw.toUpperCase()) {
    case 'OPENAI':
      return 'OpenAI';
    case 'OPENROUTER':
      return 'OpenRouter';
    case 'DEEPSEEK':
      return 'DeepSeek';
    case 'FIREWORKS':
      return 'Fireworks';
    case 'GEMINI':
    case 'GOOGLE':
      return 'Gemini';
    case 'ANTHROPIC':
      return 'Anthropic';
    default:
      return raw.replace(/_/g, ' ').replace(/\b\w/g, (m) => m.toUpperCase());
  }
}

export function modelRankingLabel(policy?: string | null): string {
  return policy === 'quality_desc_then_cost_asc_then_latency_asc'
    ? 'Selected by quality first, then cost and latency'
    : policy
      ? titleCaseType(policy)
      : 'Model route comparison';
}

export function modelRankingDetails(policy: ContinuationPolicy): string[] {
  const details: string[] = [];
  const source = sourceLabel(policy.source);
  if (source) details.push(source);
  if (
    typeof policy.rankingQualityTieBand === 'number' &&
    Number.isFinite(policy.rankingQualityTieBand)
  ) {
    details.push(
      `cost and latency break ties within ${policy.rankingQualityTieBand.toFixed(1)} quality points`,
    );
  }
  if (policy.requireBenchmarkPriorForAutomaticContinuation === true) {
    details.push('automatic upgrades need task-relevant benchmark evidence');
  } else if (policy.requireBenchmarkPriorForAutomaticContinuation === false) {
    details.push('benchmark evidence is advisory for this run');
  }
  if (policy.requireConfirmationBeforeExpensiveContinuation === true) {
    details.push('manual approval controls model/provider upgrades');
  }
  if (policy.routePinned) details.push('route pinned by request');
  if (policy.lowComplexityWorkerStartTierEffective) {
    const effective = policy.lowComplexityWorkerStartTierEffective.toLowerCase();
    const configured = policy.lowComplexityWorkerStartTierConfigured?.toLowerCase();
    details.push(
      configured && configured !== effective
        ? `light tasks start on ${effective} (${configured} ignored)`
        : `light tasks start on ${effective}`,
    );
  }
  return details;
}

export function modelRankingCandidateLabel(c: RankingCandidate): string {
  return `${providerDisplay(c.provider)}/${c.model || 'model'}`;
}

export function modelRankingCandidateMetrics(c: RankingCandidate): string {
  const quality =
    c.qualityScore == null ? 'quality unknown' : `quality ${Math.round(c.qualityScore)}`;
  const cost =
    c.costPerMillionTokens == null
      ? 'cost unknown'
      : `$${c.costPerMillionTokens.toFixed(2)} per 1M tokens`;
  const latency = c.latencyP50Ms == null ? 'latency unknown' : `p50 ${fmtDur(c.latencyP50Ms)}`;
  const benchmark =
    c.benchmarkQualityPriorAvailable === true
      ? 'benchmark evidence available'
      : c.benchmarkQualityPriorAvailable === false
        ? 'benchmark evidence missing'
        : 'benchmark evidence unknown';
  return `${quality} · ${cost} · ${latency} · ${benchmark}`;
}

export function logIcon(type?: string): string {
  const t = (type || '').toUpperCase();
  if (t.includes('TOOL')) return '🔧';
  if (t.includes('LLM') || t.includes('COMPLETION')) return '💬';
  if (t.includes('FILE')) return '✏️';
  if (t.includes('BUILD') || t.includes('COMPILE')) return '🔨';
  if (t.includes('TEST')) return '🧪';
  if (t.includes('GATE')) return '⏸️';
  if (t.includes('COST') || t.includes('TOKEN')) return '💰';
  if (t.includes('COMPLETE')) return '✅';
  if (t.includes('FAIL') || t.includes('ERROR')) return '❌';
  if (t.includes('WARN')) return '⚠️';
  if (t.includes('PROVIDER')) return '🔀';
  if (t.includes('WAVE') || t.includes('PARALLEL')) return '≋';
  if (t.includes('GOAL') || t.includes('DECISION')) return '🧭';
  if (t.includes('CONTEXT')) return '🧭';
  if (t.includes('RECOVERY')) return '♻️';
  if (t.includes('STATUS')) return '●';
  return '·';
}

export function isNoisyLogEvent(ev: api.RunLogEvent): boolean {
  return (ev.type || '').toUpperCase().includes('RUN_DETAIL');
}

function eventData(ev: api.RunLogEvent): Record<string, unknown> {
  return (
    ev.data && typeof ev.data === 'object' ? {...ev, ...(ev.data as Record<string, unknown>)} : ev
  ) as Record<string, unknown>;
}

function text(v: unknown): string | null {
  if (v == null) return null;
  const s = String(v).trim();
  return s.length ? s : null;
}

function money(v: unknown): string | null {
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? `$${n.toFixed(4)}` : null;
}

function shortId(v: unknown): string | null {
  const s = text(v);
  return s && s.length > 8 ? s.slice(0, 8) : s;
}

function parseJsonObject(v: unknown): Record<string, unknown> | null {
  const s = text(v);
  if (!s || !s.startsWith('{')) return null;
  try {
    const parsed = JSON.parse(s);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function titleCaseType(type?: string): string {
  return (type || 'log')
    .toLowerCase()
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (m) => m.toUpperCase());
}

function titleCaseProvider(value: string | null): string | null {
  if (!value) return null;
  const normalized = value.toUpperCase().replace(/[-\s]+/g, '_');
  if (normalized === 'OPENAI') return 'OpenAI';
  if (normalized === 'OPENROUTER') return 'OpenRouter';
  if (normalized === 'DEEPSEEK') return 'DeepSeek';
  if (normalized === 'FIREWORKS') return 'Fireworks';
  if (normalized === 'ANTHROPIC') return 'Anthropic';
  if (normalized === 'GEMINI') return 'Gemini';
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function providerSwitchParts(ev: api.RunLogEvent): {
  from: string | null;
  to: string | null;
  reason: string | null;
} {
  const d = eventData(ev);
  const from = text(d.fromProvider ?? d.previousProvider ?? d.sourceProvider);
  const to = text(d.toProvider ?? d.nextProvider ?? d.selectedProvider ?? d.provider);
  const reason = text(d.reason ?? d.message ?? d.error ?? d.payload);
  const legacy = text(d.taskId ?? ev.taskId);
  if ((from || to || reason) && !legacy?.includes('|')) {
    return {from, to, reason: sanitizeProviderSwitchReason(reason)};
  }
  const parts = (legacy || '').split('|');
  if (parts.length >= 3) {
    return {
      from: parts[0] || from,
      to: parts[1] || to,
      reason: sanitizeProviderSwitchReason(parts.slice(2).join('|') || reason),
    };
  }
  return {from, to, reason: sanitizeProviderSwitchReason(reason)};
}

function sanitizeProviderSwitchReason(value: string | null): string | null {
  if (!value) return null;
  const lower = value.toLowerCase();
  if (
    lower.includes('invalid x-api-key') ||
    lower.includes('authentication_error') ||
    lower.includes('401')
  ) {
    return 'provider authentication failed';
  }
  if (lower.includes('not configured') || lower.includes('no user/org key')) {
    return 'provider key is not configured';
  }
  return value.length > 160 ? `${value.slice(0, 160)}…` : value;
}

export function logLabel(type?: string): string {
  const t = (type || '').toUpperCase();
  if (t === 'RUN_STATUS') return 'Run';
  if (t === 'RUN_PROVIDER_SWITCHED') return 'Provider';
  if (t === 'TASK_STATUS') return 'Task';
  if (t === 'GATE_OPENED' || t === 'GATE_RESOLVED') return 'Approval';
  if (t === 'TASK_WAVE_PLAN') return 'Plan';
  if (t === 'TOKEN_USAGE') return 'Usage';
  if (t === 'TOOL_CALL') return 'Tool';
  if (t === 'TOOL_RESULT') return 'Tool result';
  if (t === 'FILE_WRITTEN') return 'File';
  if (t === 'BUILD_RESULT' || t === 'BUILD_FAILED' || t === 'BUILD_COMPLETE') return 'Build';
  if (t === 'TEST_RESULT' || t === 'TESTS_FAILED' || t === 'TESTS_COMPLETE') return 'Tests';
  if (t === 'REVIEWER_RESULT' || t === 'REVIEWER_FAILED' || t === 'REVIEW_STARTED') return 'Review';
  if (t === 'GOAL_DECISION') return 'Goal';
  if (t === 'CONTINUATION_DECISION') return 'Continuation';
  if (t === 'CONTINUATION_ACTION' || t === 'CONTINUATION_ACTION_SKIPPED') return 'Action';
  if (t === 'COMPLETE') return 'Complete';
  if (t === 'COST_UPDATE') return 'Cost';
  if (t === 'LOG') return 'Log';
  return titleCaseType(type);
}

export function logMessage(ev: api.RunLogEvent): string {
  const d = eventData(ev);
  const type = (ev.type || '').toUpperCase();
  const payload = text(d.payload);
  const task = shortId(d.taskId ?? ev.taskId);

  if (type === 'RUN_STATUS') return `Run status: ${payload || text(d.status) || 'updated'}`;
  if (type === 'RUN_PROVIDER_SWITCHED') {
    const parts = providerSwitchParts(ev);
    const route = [titleCaseProvider(parts.from), titleCaseProvider(parts.to)]
      .filter(Boolean)
      .join(' → ');
    return ['Provider switched', route || null, parts.reason].filter(Boolean).join(' · ');
  }
  if (type === 'TASK_STATUS') {
    const status = payload || text(d.status) || 'updated';
    return task ? `Task ${task}: ${status}` : `Task status: ${status}`;
  }
  if (type === 'GATE_OPENED') {
    const parts = (payload || '').split('|');
    const gate = parts[0] || 'approval';
    const credits = parts[1] ? `${parts[1]} credits` : null;
    const estimate = parts[2] ? `est. $${parts[2]}` : null;
    return ['Plan gate opened', gate, credits, estimate].filter(Boolean).join(' · ');
  }
  if (type === 'GATE_RESOLVED') {
    return `Plan gate ${String(payload || 'resolved').toLowerCase()}`;
  }
  if (type === 'TASK_WAVE_PLAN') {
    const mode = text(d.executionMode);
    const waveCount =
      typeof d.waveCount === 'number'
        ? d.waveCount
        : Array.isArray(d.waves)
          ? d.waves.length
          : null;
    const cap = text(d.maxParallelTasks);
    return [
      payload || 'Task execution plan ready',
      mode ? `mode ${mode}` : null,
      waveCount != null ? `${waveCount} wave${waveCount === 1 ? '' : 's'}` : null,
      cap ? `cap ${cap}` : null,
    ]
      .filter(Boolean)
      .join(' · ');
  }
  if (type === 'TOKEN_USAGE') {
    const explicit = text(d.message ?? d.line ?? d.summary);
    if (explicit) return explicit.length > 280 ? `${explicit.slice(0, 280)}…` : explicit;
    const provider = text(d.provider);
    const model = text(d.model);
    const input = text(d.inputTokens);
    const output = text(d.outputTokens);
    const total = text(d.totalTokens);
    const cost = money(d.costUsd);
    return [
      'Token usage',
      [provider, model].filter(Boolean).join(' / ') || null,
      input && output ? `${input} in / ${output} out` : total ? `${total} tokens` : payload,
      cost,
    ]
      .filter(Boolean)
      .join(' · ');
  }
  if (type === 'TOOL_CALL') {
    const frame = parseJsonObject(d.payload);
    const name = text(d.toolName) || text(frame?.name);
    const permission = text(d.permissionKind);
    return ['Tool requested', name, permission].filter(Boolean).join(' · ');
  }
  if (type === 'TOOL_RESULT') {
    const frame = parseJsonObject(d.payload);
    const name = text(d.toolName) || text(frame?.name);
    const ok = text(d.ok ?? d.success ?? frame?.ok ?? frame?.success);
    const result = ok == null ? null : ok === 'true' ? 'OK' : ok === 'false' ? 'ERR' : ok;
    const message = text(d.message ?? d.error ?? frame?.message ?? frame?.error);
    return ['Tool result', name, result, message].filter(Boolean).join(' · ');
  }
  if (type === 'FILE_WRITTEN')
    return `File written: ${payload || text(d.relativePath) || 'workspace'}`;
  if (type === 'BUILD_RESULT' || type === 'BUILD_COMPLETE' || type === 'BUILD_FAILED') {
    const status = text(d.status) || (type === 'BUILD_FAILED' ? 'failed' : null);
    const command = text(d.command);
    const message = text(d.message ?? d.error ?? d.summary ?? d.reason) || payload;
    return ['Build', status, command, message].filter(Boolean).join(' · ');
  }
  if (type === 'TEST_RESULT' || type === 'TESTS_COMPLETE' || type === 'TESTS_FAILED') {
    const status = text(d.status) || (type === 'TESTS_FAILED' ? 'failed' : null);
    const passed = text(d.passedTests ?? d.passed);
    const total = text(d.totalTests ?? d.total);
    const counts = passed && total ? `${passed}/${total} passed` : null;
    const message = text(d.message ?? d.error ?? d.summary ?? d.reason) || payload;
    return ['Tests', status, counts, message].filter(Boolean).join(' · ');
  }
  if (type === 'REVIEWER_RESULT' || type === 'REVIEWER_FAILED' || type === 'REVIEW_STARTED') {
    const status = text(d.status ?? d.verdict) || (type === 'REVIEWER_FAILED' ? 'failed' : null);
    const pass = text(d.pass ?? d.phase ?? d.stage);
    const issueCount = text(d.issueCount ?? d.issues);
    const issueText = issueCount ? `${issueCount} issue${issueCount === '1' ? '' : 's'}` : null;
    const message = text(d.message ?? d.error ?? d.summary ?? d.reason) || payload;
    return ['Reviewer', pass, status, issueText, message].filter(Boolean).join(' · ');
  }
  if (type === 'GOAL_DECISION') return payload || text(d.reason) || 'Goal decision recorded';
  if (type === 'CONTINUATION_DECISION') {
    const decision = text(d.decision);
    const reason = Array.isArray(d.reasons) ? text(d.reasons[0]) : text(d.reason);
    return [payload || 'Continuation decision recorded', decision, reason]
      .filter(Boolean)
      .join(' · ');
  }
  if (type === 'CONTINUATION_ACTION' || type === 'CONTINUATION_ACTION_SKIPPED') {
    return [
      payload || titleCaseType(type),
      text(d.decision),
      text(d.selectedProvider),
      text(d.selectedModel),
      text(d.status),
    ]
      .filter(Boolean)
      .join(' · ');
  }
  if (type === 'COMPLETE') {
    const status = payload || text(d.status) || 'terminal';
    const reason = text(d.reason ?? d.message ?? d.summary);
    return ['Run complete', status, reason].filter(Boolean).join(' · ');
  }
  if (type === 'COST_UPDATE') return `Cost update: ${money(payload) || payload || 'updated'}`;

  const raw =
    d.message ??
    d.line ??
    d.payload ??
    d.summary ??
    d.relativePath ??
    d.status ??
    d.reason ??
    d.prompt;
  const body =
    raw != null && String(raw).length
      ? String(raw)
      : (ev.type || '').toLowerCase().replace(/_/g, ' ');
  return body.length > 280 ? `${body.slice(0, 280)}…` : body;
}

export function logMeta(ev: api.RunLogEvent): string[] {
  const d = eventData(ev);
  const type = (ev.type || '').toUpperCase();
  const out = new Set<string>();
  const add = (v: unknown) => {
    const s = text(v);
    if (s) out.add(s);
  };

  if (type === 'RUN_PROVIDER_SWITCHED') {
    const parts = providerSwitchParts(ev);
    const route = [titleCaseProvider(parts.from), titleCaseProvider(parts.to)]
      .filter(Boolean)
      .join(' → ');
    add(route);
    return [...out].slice(0, 5);
  }
  if (ev.taskId || d.taskId) add(`task ${shortId(d.taskId ?? ev.taskId)}`);
  if (type === 'TOKEN_USAGE') {
    add(text(d.totalTokens) ? `${d.totalTokens} tokens` : null);
    add(text(d.cacheReadTokens) ? `${d.cacheReadTokens} cache read` : null);
    add(money(d.costUsd));
    add(text(d.calls) ? `${d.calls} calls` : null);
  } else if (type === 'TASK_WAVE_PLAN') {
    add(text(d.executionMode));
    add(text(d.maxParallelTasks) ? `max ${d.maxParallelTasks} parallel` : null);
    if (Array.isArray(d.waves)) add(`${d.waves.length} waves`);
  } else if (type === 'TOOL_CALL') {
    add(text(d.callId) ? `call ${shortId(d.callId)}` : null);
    add(text(d.permissionKind));
  } else if (type.includes('CONTINUATION')) {
    add(text(d.decision));
    add(text(d.selectedProvider));
    add(text(d.selectedModel));
  }

  return [...out].slice(0, 5);
}
