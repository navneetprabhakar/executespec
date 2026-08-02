import {describe, expect, it} from 'vitest';
import type {PublicationEligibility, RunDetail, ValidationReportData} from '@/lib/api';

import {
  boundedLineDiff,
  confidenceColor,
  continuationApprovalCandidate,
  continuationApprovalReason,
  continuationSourceLabel,
  countProducedWorkspaceFiles,
  customerContinuationSummary,
  customerLimitSummary,
  customerStepStatusLabel,
  customerStepSummary,
  customerStepTitle,
  evidenceKind,
  emptyCodeTabMessage,
  fileCountSummary,
  fmtDur,
  goalDecisionHelp,
  goalDecisionLabel,
  goalProofKind,
  goalProofLabel,
  goalStateLabel,
  goalStateKind,
  hasRunCostEvidence,
  isNoisyLogEvent,
  isBootstrapWorkspaceMetadata,
  isFailedTerminalStatus,
  isTestExecutionTerminal,
  lineDiff,
  latestContinuationAction,
  correctionEvidenceHistory,
  correctionEvidenceInvalidationLabel,
  reportPipelineLabel,
  logIcon,
  logLabel,
  logMeta,
  logMessage,
  missingScoreExplanation,
  modelRankingCandidateLabel,
  modelRankingCandidateMetrics,
  modelRankingDetails,
  modelRankingLabel,
  matchingContinuationApprovalRecorded,
  num,
  publicationTrustFacts,
  runArtifactActionAvailability,
  runChatResponseContent,
  friendlyAiFailureMessage,
  terminalFailureEvidence,
  runPhaseDiagnosis,
  shouldUseRunEvidenceChat,
  shouldShowModelRanking,
  TAB_LABEL,
  testRerunState,
  taskBudgetFacts,
  taskGoalLine,
  testEvidenceSummary,
  testResultSummary,
  timelineTiming,
  usd,
  verdictFor,
} from './runResultUtils';

describe('publicationTrustFacts', () => {
  it('keeps route, executable checks, generations, and eligibility as independent facts', () => {
    const run = {
      tasks: [
        {
          taskId: 'T1',
          status: 'COMPLETE',
          effectiveProvider: 'OPENROUTER',
          effectiveModel: 'x-ai/grok-4.20',
          effectiveRouteAttribution: 'LEDGER',
          continuationActions: [
            {
              status: 'APPROVED',
              provider: 'OPENROUTER',
              model: 'x-ai/grok-4.20',
              workerAttempt: {
                provider: 'OPENROUTER',
                model: 'x-ai/grok-4.20',
                attribution: 'LEDGER',
                physicalAttemptIds: ['worker-attempt'],
              },
              reviewerAttempt: {
                provider: 'OPENROUTER',
                model: 'x-ai/grok-4.20',
                attribution: 'LEDGER',
                physicalAttemptIds: ['reviewer-attempt'],
              },
              beforeWorkspaceGeneration: 'a'.repeat(64),
              afterWorkspaceGeneration: 'b'.repeat(64),
              diffDigest: 'c'.repeat(64),
              changedFiles: ['src/todo.ts'],
              evidenceInvalidated: true,
              validationRegenerated: true,
            },
          ],
        },
      ],
      effectiveProvider: 'OPENROUTER',
      effectiveModel: 'x-ai/grok-4.20',
      effectiveRouteAttribution: 'LEDGER',
      finalBuildPassed: true,
      testsPassed: 2,
      testsTotal: 2,
      testExecutionVerified: true,
      workflowConfigStatus: 'PASSED',
      workflowConfigReason: '1 workflow parsed',
      brownfieldHttpCompatibilityStatus: 'COMPATIBLE',
      reviewerVerdict: 'CLEAN',
      reviewerProvider: 'OPENROUTER',
      reviewerEffectiveModel: 'x-ai/grok-4.20',
      totalInputTokens: 100,
      totalOutputTokens: 20,
      actualCostUsd: 0.25,
    } as unknown as RunDetail;
    const publication = {
      eligible: true,
      eligibility: 'ELIGIBLE',
      code: 'PUBLICATION_ELIGIBLE',
      blockingGate: null,
      reason: 'all pass',
      retryable: false,
      remediation: null,
      evidenceWorkspaceGeneration: 'b'.repeat(64),
      currentWorkspaceGeneration: 'b'.repeat(64),
    } satisfies PublicationEligibility;

    const facts = publicationTrustFacts(run, publication);

    expect(Object.fromEntries(facts.map((fact) => [fact.label, fact.value]))).toMatchObject({
      'Tasks terminal': '1/1 terminal · 1/1 completed',
      'Approved correction route': 'OPENROUTER/x-ai/grok-4.20',
      'Actual worker route': 'OPENROUTER/x-ai/grok-4.20 (LEDGER · 1 physical attempt)',
      Build: 'Passed',
      Tests: '2/2 passed · execution verified',
      'Workflow/config': 'PASSED · 1 workflow parsed',
      'Protected contracts': 'COMPATIBLE',
      'Reviewer result': 'CLEAN',
      'Reviewer route': 'OPENROUTER/x-ai/grok-4.20 (LEDGER · 1 physical attempt)',
      'Correction transactions': expect.stringContaining(
        '1 changed: src/todo.ts · diff cccccccccccc · prior evidence invalidated · validation regenerated',
      ),
      'Evidence freshness': expect.stringContaining('Fresh'),
      Publication: 'ELIGIBLE · PUBLICATION_ELIGIBLE',
    });
  });

  it('does not let a score or terminal status conceal stale evidence and failed tests', () => {
    const facts = publicationTrustFacts(
      {
        status: 'COMPLETE',
        verifiedScore: 99,
        tasks: [],
        finalBuildPassed: true,
        testsPassed: 1,
        testsTotal: 2,
        testExecutionVerified: true,
      } as unknown as RunDetail,
      {
        eligible: false,
        eligibility: 'BLOCKED',
        code: 'PUBLICATION_EVIDENCE_STALE',
        blockingGate: 'EVIDENCE_FRESHNESS',
        reason: 'stale',
        retryable: true,
        remediation: 'regenerate',
        evidenceWorkspaceGeneration: 'a'.repeat(64),
        currentWorkspaceGeneration: 'b'.repeat(64),
      },
    );

    expect(facts.find((fact) => fact.label === 'Tests')).toMatchObject({kind: 'bad'});
    expect(facts.find((fact) => fact.label === 'Evidence freshness')).toMatchObject({kind: 'bad'});
    expect(facts.find((fact) => fact.label === 'Publication')).toMatchObject({kind: 'bad'});
  });

  it('reports terminal task state without claiming failed, partial, or blocked work executed', () => {
    const facts = publicationTrustFacts({
      tasks: [
        {taskId: 'T1', status: 'FAILED'},
        {taskId: 'T2', status: 'PARTIAL'},
        {taskId: 'T3', status: 'BLOCKED'},
        {taskId: 'T4', status: 'PENDING'},
      ],
    } as unknown as RunDetail);

    expect(facts.find((fact) => fact.label === 'Tasks terminal')).toMatchObject({
      value: '3/4 terminal · 0/4 completed',
      kind: 'bad',
    });
  });

  it('never renders an all-blocked task set as green execution evidence', () => {
    const facts = publicationTrustFacts({
      tasks: [
        {taskId: 'T1', status: 'BLOCKED'},
        {taskId: 'T2', status: 'BLOCKED'},
      ],
    } as unknown as RunDetail);

    expect(facts.find((fact) => fact.label === 'Tasks terminal')).toMatchObject({
      value: '2/2 terminal · 0/2 completed',
      kind: 'bad',
    });
  });
});

describe('correctionEvidenceHistory', () => {
  it('distinguishes explicit evidence retention from missing correction evidence', () => {
    expect(correctionEvidenceInvalidationLabel(true)).toBe('Yes — prior evidence was made stale');
    expect(correctionEvidenceInvalidationLabel(false)).toBe(
      'No — prior evidence was explicitly retained',
    );
    expect(correctionEvidenceInvalidationLabel(undefined)).toBe('Not recorded');
  });

  it('keeps every correction visible in stable task/action order', () => {
    const run = {
      tasks: [
        {
          taskId: 'task-a',
          title: 'First task',
          continuationActions: [
            {decision: 'OTHER', code: 'OTHER_ACTION'},
            {decision: 'CORRECTION_RESOLUTION', code: 'CORRECTION_REJECTED'},
          ],
        },
        {
          taskId: 'task-b',
          title: 'Second task',
          continuationActions: [
            {decision: 'CORRECTION_RESOLUTION', code: 'CORRECTION_ACCEPTED'},
            {decision: 'OTHER', code: 'UNRELATED'},
          ],
        },
      ],
    } as unknown as RunDetail;

    expect(
      correctionEvidenceHistory(run).map((entry) => [
        entry.taskId,
        entry.actionIndex,
        entry.action.code,
      ]),
    ).toEqual([
      ['task-a', 1, 'CORRECTION_REJECTED'],
      ['task-b', 0, 'CORRECTION_ACCEPTED'],
    ]);
  });

  it('preserves patch and validation delta without claiming global chronology', () => {
    const run = {
      tasks: [
        {
          continuationActions: [
            {decision: 'CORRECTION_RESOLUTION', code: 'CORRECTION_REJECTED'},
            {
              decision: 'CORRECTION_RESOLUTION',
              code: 'CORRECTION_ACCEPTED',
              beforeWorkspaceGeneration: 'before',
              afterWorkspaceGeneration: 'after',
              unifiedPatch: 'diff --git a/App.java b/App.java',
              patchMetadata: {capturedBytes: 34, fullBytes: 34, truncated: false},
              validationDelta: {
                before: {build: {verdict: 'PASSED'}},
                after: {build: {verdict: 'PASSED'}},
                verifiedScoreDelta: 4,
              },
            },
          ],
        },
      ],
    } as unknown as RunDetail;

    const actions = correctionEvidenceHistory(run).map(({action}) => action);
    expect(actions).toHaveLength(2);
    expect(actions[0]).toMatchObject({code: 'CORRECTION_REJECTED'});
    expect(actions[1]).toMatchObject({
      code: 'CORRECTION_ACCEPTED',
      unifiedPatch: 'diff --git a/App.java b/App.java',
      patchMetadata: {fullBytes: 34, truncated: false},
      validationDelta: {verifiedScoreDelta: 4},
    });
  });

  it('does not invent correction evidence for an untouched run', () => {
    expect(correctionEvidenceHistory({tasks: []} as unknown as RunDetail)).toEqual([]);
  });

  it('does not call reverse cross-task action order the latest correction', () => {
    const run = {
      tasks: [
        {
          taskId: 'task-early',
          title: 'Earlier plan task',
          continuationActions: [
            {
              decision: 'CORRECTION_RESOLUTION',
              status: 'ACCEPTED',
              code: 'CORRECTION_NEWER_IN_TIME',
            },
          ],
        },
        {
          taskId: 'task-late',
          title: 'Later plan task',
          continuationActions: [
            {
              decision: 'CORRECTION_RESOLUTION',
              status: 'REJECTED',
              code: 'CORRECTION_OLDER_IN_TIME',
            },
          ],
        },
      ],
    } as unknown as RunDetail;

    const correctionFact = publicationTrustFacts(run).find(
      (fact) => fact.label === 'Correction transactions',
    );

    expect(correctionFact?.value).toContain('task/action order (not chronology)');
    expect(correctionFact?.value).toContain('Earlier plan task: ACCEPTED');
    expect(correctionFact?.value).toContain('Later plan task: REJECTED');
    expect(correctionFact?.value).not.toContain('latest');
  });
});

describe('reportPipelineLabel', () => {
  it('does not present a stale pipeline result as current evidence', () => {
    expect(reportPipelineLabel({reportStatus: 'STALE', pipelineResult: 'PASSED'})).toBe(
      'Stale — validation regeneration required',
    );
  });

  it('keeps responses from older servers backward compatible', () => {
    expect(reportPipelineLabel({pipelineResult: 'PASSED'})).toBe('PASSED');
  });

  it('keeps intentionally suppressed stale markdown nullable in the API contract', () => {
    const stale = {reportMarkdown: null} satisfies Pick<ValidationReportData, 'reportMarkdown'>;
    expect(stale.reportMarkdown).toBeNull();
  });
});

describe('runArtifactActionAvailability', () => {
  it('blocks every artifact action when planning failed before producing evidence', () => {
    const changedWorkspaceFiles = countProducedWorkspaceFiles([
      {path: '.gitattributes', status: 'ADDED'},
      {path: '.gitignore', status: 'ADDED'},
    ]);
    expect(
      runArtifactActionAvailability({
        status: 'FAILED',
        changedWorkspaceFiles,
        hasExecutionEvidence: false,
        hasValidationReport: false,
      }),
    ).toEqual({
      push: false,
      revertWorkspace: false,
      preview: false,
      deploy: false,
      reportPdf: false,
      codeZip: false,
      shareReport: false,
      workspaceReason: 'This run stopped before execution and produced no code changes.',
      publicationReason: 'Checking publication eligibility…',
      reportReason: 'No validation report was produced for this run.',
    });
  });

  it('allows local workspace actions while withholding publication without eligibility', () => {
    const availability = runArtifactActionAvailability({
      status: 'FAILED',
      changedWorkspaceFiles: 3,
      hasExecutionEvidence: true,
      hasValidationReport: false,
    });

    expect(availability).toMatchObject({
      push: false,
      revertWorkspace: true,
      preview: true,
      deploy: false,
      codeZip: true,
      reportPdf: false,
      shareReport: false,
    });
  });

  it('allows all artifact actions only after produced changes and a report exist', () => {
    const availability = runArtifactActionAvailability({
      status: 'COMPLETE',
      changedWorkspaceFiles: 4,
      hasExecutionEvidence: true,
      hasValidationReport: true,
      publication: {
        eligible: true,
        eligibility: 'ELIGIBLE',
        code: 'PUBLICATION_ELIGIBLE',
        blockingGate: null,
        reason: 'eligible',
        retryable: false,
        remediation: null,
        evidenceWorkspaceGeneration: 'a'.repeat(64),
        currentWorkspaceGeneration: 'a'.repeat(64),
      },
    });

    expect(
      Object.entries(availability)
        .filter(([key]) => !key.endsWith('Reason'))
        .every(([, enabled]) => enabled),
    ).toBe(true);
  });

  it('does not expose terminal actions for a paused run with dirty files', () => {
    const availability = runArtifactActionAvailability({
      status: 'PAUSED',
      changedWorkspaceFiles: 2,
      hasExecutionEvidence: true,
      hasValidationReport: false,
    });

    expect(availability.push).toBe(false);
    expect(availability.preview).toBe(false);
    expect(availability.workspaceReason).toBe(
      'This action becomes available after the run finishes.',
    );
  });

  it('does not classify bootstrap git metadata as generated product code', () => {
    expect(isBootstrapWorkspaceMetadata('.gitignore')).toBe(true);
    expect(isBootstrapWorkspaceMetadata('./.gitattributes')).toBe(true);
    expect(isBootstrapWorkspaceMetadata('repos/service/.gitignore')).toBe(true);
    expect(isBootstrapWorkspaceMetadata('src/main.ts')).toBe(false);
    expect(
      countProducedWorkspaceFiles([
        {path: '.gitignore', status: 'ADDED'},
        {path: '.gitattributes', status: 'ADDED'},
        {path: 'src/main.ts', status: 'MODIFIED'},
        {path: 'src/unchanged.ts', status: 'UNCHANGED'},
      ]),
    ).toBe(1);
  });
});

describe('terminalFailureEvidence', () => {
  it('surfaces durable planner rejection evidence on reload', () => {
    expect(
      terminalFailureEvidence({
        status: 'FAILED',
        statusReason: 'PLAN_VALIDATION_REJECTED',
        failureReason: ' Plan validation rejected: T8 misses producer GET /api/products. ',
      }),
    ).toEqual({
      title: 'Planning rejection evidence',
      detail: 'Plan validation rejected: T8 misses producer GET /api/products.',
      kind: 'bad',
    });
  });

  it('does not label an unavailable planning path as deterministic rejection', () => {
    expect(
      terminalFailureEvidence({
        status: 'FAILED',
        statusReason: 'PLANNER_UNAVAILABLE',
        failureReason: 'planning could not produce a safe executable plan',
      }),
    ).toEqual({
      title: 'Planning failure evidence',
      detail: 'planning could not produce a safe executable plan',
      kind: 'bad',
    });
  });

  it('prefers the server-owned outcome and never renders a legacy raw exception', () => {
    const evidence = terminalFailureEvidence({
      status: 'FAILED',
      statusReason: 'INTERNAL_ERROR',
      failureReason:
        'RuntimeException: API key secret-value leaked from /Users/example/private/workspace',
      terminalOutcome: {
        schemaVersion: 1,
        code: 'INTERNAL_ERROR',
        category: 'PLATFORM_FAILURE',
        customerTitle: 'Run could not complete',
        customerMessage: 'An internal error stopped this run. Contact support if it repeats.',
        impact: {
          actualCostUsd: 0.42,
          capUsd: 1,
          codeChanged: null,
          publication: 'NOT_CREATED',
          prUrl: null,
        },
        retryable: true,
        nextAction: {type: 'RETRY', label: 'Retry this run', href: '/runs/r1'},
        supportCode: 'ES-RUN-R1',
        automationDisposition: 'FAILED',
        automationExitCode: 1,
      },
    });

    expect(evidence).toEqual({
      title: 'Run could not complete',
      detail: 'An internal error stopped this run. Contact support if it repeats.',
      kind: 'bad',
      nextAction: 'Retry this run',
      supportCode: 'ES-RUN-R1',
    });
    expect(JSON.stringify(evidence)).not.toContain('secret-value');
    expect(JSON.stringify(evidence)).not.toContain('/Users/example');
  });

  it('renders cancellation as a neutral user stop rather than a platform failure', () => {
    const terminalOutcome = {
      schemaVersion: 1 as const,
      code: 'USER_CANCELLED',
      category: 'USER_STOPPED' as const,
      customerTitle: 'Run cancelled',
      customerMessage: 'You cancelled this run.',
      impact: {
        actualCostUsd: 0.04,
        capUsd: 1,
        codeChanged: false,
        publication: 'NOT_CREATED' as const,
        prUrl: null,
      },
      retryable: true,
      nextAction: {type: 'START_NEW_RUN', label: 'Start again', href: '/compose'},
      supportCode: null,
      automationDisposition: 'STOPPED' as const,
      automationExitCode: 130,
    };

    expect(
      terminalFailureEvidence({
        status: 'CANCELLED',
        statusReason: 'Cancelled by user',
        failureReason: 'legacy failure copy',
        terminalOutcome,
      }),
    ).toMatchObject({
      title: 'Run cancelled',
      detail: 'You cancelled this run.',
      kind: 'neutral',
    });
    expect(verdictFor(null, 'CANCELLED', terminalOutcome)).toEqual({
      label: 'Cancelled',
      kind: 'warn',
    });
  });

  it('does not invent evidence for an in-flight or reasonless run', () => {
    expect(
      terminalFailureEvidence({status: 'RUNNING', statusReason: null, failureReason: 'waiting'}),
    ).toBeNull();
    expect(
      terminalFailureEvidence({status: 'FAILED', statusReason: 'PLANNER_UNAVAILABLE'}),
    ).toBeNull();
  });
});

describe('testResultSummary', () => {
  it('does not present a zero-test report as a passing measurement', () => {
    expect(testResultSummary({totalTests: 0, passed: 0, failed: 0, errors: 0})).toEqual({
      measured: false,
      headline: 'Not measured',
      label: 'No tests executed',
      explanation:
        'A compiled test plan is not execution evidence. Re-run tests to publish measured pass/fail results.',
    });
  });

  it('preserves real measured counts', () => {
    expect(testResultSummary({totalTests: 4, passed: 3, failed: 1, errors: 0})).toEqual({
      measured: true,
      headline: '3/4',
      label: 'passed',
      explanation: null,
    });
  });
});

describe('testEvidenceSummary', () => {
  it('distinguishes functional, workspace-only, and unverified counts', () => {
    expect(
      testEvidenceSummary(5, 5, {
        source: 'DETERMINISTIC_FUNCTIONAL',
        status: 'PASS',
      }),
    ).toEqual({measured: true, functional: true, workspaceOnly: false, label: '5/5 functional'});
    expect(
      testEvidenceSummary(7, 7, {
        source: 'DETERMINISTIC_WORKSPACE_SUITE',
        status: 'PASS',
      }),
    ).toEqual({
      measured: true,
      functional: false,
      workspaceOnly: true,
      label: '7/7 workspace-only',
    });
    expect(testEvidenceSummary(3, 3, {source: 'UNVERIFIED_COUNT', status: 'UNKNOWN'})).toEqual({
      measured: true,
      functional: false,
      workspaceOnly: false,
      label: '3/3 unverified',
    });
  });
});
import type * as api from '@/lib/api';

describe('runResultUtils', () => {
  it('maps scores and terminal statuses to verdict labels', () => {
    expect(verdictFor(92, 'COMPLETE')).toEqual({label: 'Shippable', kind: 'ok'});
    expect(verdictFor(72, 'COMPLETE')).toEqual({label: 'Needs review', kind: 'warn'});
    expect(verdictFor(null, 'COMPLETE')).toEqual({label: 'Complete', kind: 'warn'});
    expect(verdictFor(null, 'PARTIAL')).toEqual({label: 'Needs review', kind: 'warn'});
    expect(verdictFor(96, 'FAILED')).toEqual({label: 'Failed', kind: 'bad'});
  });

  it('builds a stable line diff with deletions before replacement additions', () => {
    expect(lineDiff('alpha\nold\nsame\n', 'alpha\nnew\nsame\n')).toEqual([
      {sign: ' ', text: 'alpha'},
      {sign: '-', text: 'old'},
      {sign: '+', text: 'new'},
      {sign: ' ', text: 'same'},
    ]);
  });

  it('computes at the exact line/matrix threshold and refuses the first comparison above it', () => {
    const limits = {maxInputCharsPerSide: 100, maxLinesPerSide: 4, maxMatrixCells: 9};
    const atThreshold = boundedLineDiff('a\nb\nc', 'a\nx\nc', limits);
    expect(atThreshold.truncated).toBe(false);
    expect(atThreshold.lines.some((line) => line.sign === '-' && line.text === 'b')).toBe(true);
    expect(atThreshold.lines.some((line) => line.sign === '+' && line.text === 'x')).toBe(true);

    const overThreshold = boundedLineDiff('a\nb\nc', 'a\nx\nc\nd', limits);
    expect(overThreshold).toMatchObject({
      lines: [],
      truncated: true,
    });
    expect(overThreshold.reason).toContain('3 × 4');
    expect(overThreshold.reason).toContain('9-cell safety limit');
  });

  it('rejects missing, epoch and inverted task timestamps instead of showing epoch durations', () => {
    const now = Date.parse('2026-07-28T04:30:00Z');
    expect(timelineTiming({startedAt: null, completedAt: null}, now)).toBeNull();
    expect(timelineTiming({startedAt: '1970-01-01T00:00:00Z'}, now)).toBeNull();
    expect(
      timelineTiming({startedAt: '2026-07-28T04:29:00Z', completedAt: '2026-07-28T04:28:00Z'}, now),
    ).toBeNull();
  });

  it('returns a bounded duration for valid completed and active task timestamps', () => {
    const now = Date.parse('2026-07-28T04:30:00Z');
    expect(
      timelineTiming({startedAt: '2026-07-28T04:28:00Z', completedAt: '2026-07-28T04:29:30Z'}, now),
    ).toEqual({
      startMs: Date.parse('2026-07-28T04:28:00Z'),
      endMs: Date.parse('2026-07-28T04:29:30Z'),
      durationMs: 90_000,
    });
    expect(timelineTiming({startedAt: '2026-07-28T04:29:00Z'}, now)?.durationMs).toBe(60_000);
  });

  it('formats log rows and skips noisy run-detail events', () => {
    expect(logIcon('TOOL_CALL')).toBe('🔧');
    expect(logIcon('TASK_WAVE_PLAN')).toBe('≋');
    expect(logIcon('GOAL_DECISION')).toBe('🧭');
    expect(logIcon('UNKNOWN')).toBe('·');
    expect(logLabel('RUN_STATUS')).toBe('Run');
    expect(logLabel('GATE_OPENED')).toBe('Approval');
    expect(logLabel('RUN_PROVIDER_SWITCHED')).toBe('Provider');
    expect(logLabel('TASK_WAVE_PLAN')).toBe('Plan');
    expect(logLabel('TOKEN_USAGE')).toBe('Usage');
    expect(logLabel('REVIEWER_FAILED')).toBe('Review');
    expect(logLabel('SOMETHING_NEW')).toBe('Something New');
    expect(isNoisyLogEvent({type: 'RUN_DETAIL'} as api.RunLogEvent)).toBe(true);
    expect(
      logMessage({type: 'TOKEN_USAGE', data: {message: 'tokens updated'}} as api.RunLogEvent),
    ).toBe('tokens updated');
    expect(logMessage({type: 'EMPTY_EVENT'} as api.RunLogEvent)).toBe('empty event');
  });

  it('turns retained SSE events into useful activity summaries', () => {
    expect(logMessage({type: 'RUN_STATUS', payload: 'AWAITING_APPROVAL'} as api.RunLogEvent)).toBe(
      'Run status: AWAITING_APPROVAL',
    );
    expect(
      logMessage({type: 'GATE_OPENED', payload: 'PLAN_APPROVAL|4|0.42'} as api.RunLogEvent),
    ).toBe('Plan gate opened · PLAN_APPROVAL · 4 credits · est. $0.42');
    expect(
      logMessage({
        type: 'TASK_WAVE_PLAN',
        payload: 'Sequential execution plan ready',
        data: {executionMode: 'SEQUENTIAL_ADVISORY', maxParallelTasks: 3, waves: [{}, {}]},
      } as api.RunLogEvent),
    ).toBe('Sequential execution plan ready · mode SEQUENTIAL_ADVISORY · 2 waves · cap 3');
    expect(
      logMessage({
        type: 'TOKEN_USAGE',
        data: {
          provider: 'anthropic',
          model: 'claude-sonnet-4-6',
          inputTokens: 1200,
          outputTokens: 180,
          costUsd: 0.03125,
        },
      } as api.RunLogEvent),
    ).toBe('Token usage · anthropic / claude-sonnet-4-6 · 1200 in / 180 out · $0.0313');
    expect(
      logMessage({
        type: 'TOOL_RESULT',
        data: {toolName: 'buildProject', ok: false, error: 'pytest not found'},
      } as api.RunLogEvent),
    ).toBe('Tool result · buildProject · ERR · pytest not found');
    expect(
      logMessage({
        type: 'REVIEWER_FAILED',
        data: {phase: 'fix-build', error: 'sandbox denied python'},
      } as api.RunLogEvent),
    ).toBe('Reviewer · fix-build · failed · sandbox denied python');
    expect(
      logMessage({
        type: 'TEST_RESULT',
        data: {status: 'FAILED', passed: 0, total: 3, summary: 'unit suite failed'},
      } as api.RunLogEvent),
    ).toBe('Tests · FAILED · 0/3 passed · unit suite failed');
    expect(
      logMessage({
        type: 'RUN_PROVIDER_SWITCHED',
        taskId:
          'anthropic|gemini|401 - {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"},"request_id":"req_123"}',
      } as api.RunLogEvent),
    ).toBe('Provider switched · Anthropic → Gemini · provider authentication failed');
    expect(
      logMessage({
        type: 'RUN_PROVIDER_SWITCHED',
        taskId: 'gemini|openai|Gemini not configured: no user/org key and no system GOOGLE_API_KEY',
      } as api.RunLogEvent),
    ).toBe('Provider switched · Gemini → OpenAI · provider key is not configured');
  });

  it('extracts compact activity metadata chips', () => {
    expect(
      logMeta({
        type: 'TOKEN_USAGE',
        taskId: '1234567890abcdef',
        data: {totalTokens: 3000, cacheReadTokens: 900, costUsd: 0.12, calls: 2},
      } as api.RunLogEvent),
    ).toEqual(['task 12345678', '3000 tokens', '900 cache read', '$0.1200', '2 calls']);
    expect(
      logMeta({
        type: 'TOOL_CALL',
        taskId: 'task-one',
        data: {callId: 'call-1234567890', permissionKind: 'LOCAL_APPROVAL'},
      } as api.RunLogEvent),
    ).toEqual(['task task-one', 'call call-123', 'LOCAL_APPROVAL']);
    expect(
      logMeta({
        type: 'RUN_PROVIDER_SWITCHED',
        taskId:
          'anthropic|gemini|401 - {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"},"request_id":"req_123"}',
      } as api.RunLogEvent),
    ).toEqual(['Anthropic → Gemini']);
  });

  it('caps very large log messages defensively', () => {
    const msg = logMessage({type: 'LOG', payload: 'x'.repeat(400)} as api.RunLogEvent);
    expect(msg).toHaveLength(281);
    expect(msg.endsWith('…')).toBe(true);
  });

  it('hides model rankings for tasks that already stopped done', () => {
    const policy: NonNullable<NonNullable<api.TaskSummary['continuationDecision']>['policy']> = {
      rankingPolicy: 'quality_desc_then_cost_asc_then_latency_asc',
      topEligibleCandidates: [{provider: 'openrouter', model: 'grok-4.20'}],
    };
    const doneTask: Pick<api.TaskSummary, 'continuationDecision' | 'goalDoneness'> = {
      goalDoneness: {recommendedDecision: 'STOP_DONE'},
      continuationDecision: {
        decision: 'STOP_DONE',
        policy,
      },
    };
    const providerChangeTask: Pick<api.TaskSummary, 'continuationDecision' | 'goalDoneness'> = {
      goalDoneness: {recommendedDecision: 'STOP_FAILED'},
      continuationDecision: {
        decision: 'CHANGE_PROVIDER',
        policy,
      },
    };
    const sameRouteTask: Pick<api.TaskSummary, 'continuationDecision' | 'goalDoneness'> = {
      goalDoneness: {recommendedDecision: 'CONTINUE_SAME_MODEL_PROVIDER'},
      continuationDecision: {
        decision: 'CONTINUE_SAME_MODEL_PROVIDER',
        policy,
      },
    };

    expect(shouldShowModelRanking(doneTask)).toBe(false);
    expect(shouldShowModelRanking(providerChangeTask)).toBe(true);
    expect(shouldShowModelRanking(sameRouteTask)).toBe(false);
  });

  it('renders goal proof decisions as proof state, not raw executor enums', () => {
    expect(goalStateLabel('DONE')).toBe('Goal proven');
    expect(goalStateLabel('FAILED')).toBe('Proof failed');
    expect(goalStateLabel('WASTE_RISK')).toBe('Waste risk');
    expect(goalDecisionLabel('STOP_DONE')).toBe('Proof accepted');
    expect(goalDecisionLabel('STOP_FAILED')).toBe('Proof failed');
    expect(goalDecisionLabel('STOP_BLOCKED')).toBe('Needs input');
    expect(goalDecisionLabel('CONTINUE_SAME_MODEL_PROVIDER')).toBe('Keep working');
    expect(goalDecisionLabel('CHANGE_PROVIDER')).toBe('Try another provider');
    expect(goalDecisionHelp('STOP_FAILED')).toContain('proof failure');
    expect(goalDecisionHelp('STOP_FAILED')).not.toContain('STOP_FAILED');
    expect(goalDecisionLabel('STOP_FAILED')).not.toContain('STOP_FAILED');
  });

  it('renders typed goal-proof reference states without exposing backend enums', () => {
    expect(goalProofLabel('PROVEN')).toBe('Proven');
    expect(goalProofLabel('BLOCKING')).toBe('Blocking');
    expect(goalProofLabel('UNPROVEN')).toBe('Unproven');
    expect(goalProofKind('PROVEN')).toBe('ok');
    expect(goalProofKind('BLOCKING')).toBe('bad');
    expect(goalProofKind('UNPROVEN')).toBe('warn');
  });

  it('keeps continuation and budget presentation logic independently testable', () => {
    const task: api.TaskSummary = {
      taskId: 'task-1',
      title: 'Example task',
      status: 'PARTIAL',
      assignedModel: 'gpt-5.4',
      inputTokens: 1000,
      outputTokens: 250,
      durationMs: 65000,
      branchName: null,
      startedAt: null,
      completedAt: null,
      readmeUpdated: false,
      goalDoneness: {
        state: 'IN_PROGRESS',
        recommendedDecision: 'STEP_UP_MODEL',
        reason: 'build evidence is incomplete',
        costUsd: 0.1256,
        totalTokens: 1250,
        wallClockMs: 65000,
        budgetCeilingReason: 'task limit',
      },
      continuationDecision: {
        source: 'POST_REVIEW',
        decision: 'STOP_BLOCKED',
        approvalRequiredReason: 'BENCHMARK_PRIOR_REQUIRED',
        selectedCandidate: {
          provider: 'OPENAI',
          model: 'gpt-5.4',
          benchmarkQualityPriorAvailable: false,
        },
        policy: {
          requireBenchmarkPriorForAutomaticContinuation: true,
          maxTotalCostUsd: 1,
          maxTotalTokens: 2000,
          maxTaskWallClockMs: 120000,
        },
      },
      continuationActions: [
        {
          source: 'TASK_BOUNDARY',
          status: 'SKIPPED',
          decision: 'STEP_UP_MODEL',
          provider: 'OPENAI',
          model: 'gpt-5.4',
          limitName: 'model steps',
          usedLimit: 1,
          maxLimit: 2,
        },
      ],
    };

    expect(continuationSourceLabel('POST_REVIEW')).toBe('Reviewer/build trigger');
    expect(taskGoalLine(task)).toContain('Goal Still checking -> Try stronger model');
    expect(taskGoalLine(task)).toContain('Reviewer/build trigger · Next Needs input');
    expect(continuationApprovalCandidate(task)).toMatchObject({provider: 'OPENAI'});
    expect(continuationApprovalReason(task)).toContain('no usable benchmark prior');
    expect(latestContinuationAction(task)).toContain('Skipped STEP_UP_MODEL');
    expect(latestContinuationAction(task)).toContain('model steps 1/2');
    expect(goalStateKind('IN_PROGRESS')).toBe('warn');
    expect(taskBudgetFacts(task)).toEqual([
      {k: 'Cost', v: '$0.1256 / $1.0000'},
      {k: 'Tokens', v: '1,250 / 2,000'},
      {k: 'Time', v: '1m 5s / 2m 0s'},
      {k: 'Ceiling', v: 'task limit'},
    ]);
  });

  it('surfaces a rolled-back correction independently of task completion', () => {
    const task: api.TaskSummary = {
      taskId: 'T1',
      title: 'Correct final build',
      status: 'COMPLETE',
      assignedModel: 'gpt-5.4',
      inputTokens: 0,
      outputTokens: 0,
      durationMs: 0,
      branchName: null,
      startedAt: null,
      completedAt: null,
      readmeUpdated: false,
      continuationActions: [
        {
          status: 'ROLLED_BACK',
          decision: 'CORRECTION_RESOLUTION',
          code: 'CORRECTION_FINAL_BUILD_UNPROVEN',
          reason: 'Correction did not prove a passing final build',
          beforeWorkspaceGeneration: 'same-generation',
          afterWorkspaceGeneration: 'same-generation',
        },
      ],
    };

    expect(latestContinuationAction(task)).toBe(
      'Correction rolled back · CORRECTION_FINAL_BUILD_UNPROVEN · Correction did not prove a passing final build',
    );
  });

  it('keeps model ranking copy user-facing instead of leaking policy internals', () => {
    const policy: NonNullable<NonNullable<api.TaskSummary['continuationDecision']>['policy']> = {
      source: 'POST_REVIEW',
      rankingPolicy: 'quality_desc_then_cost_asc_then_latency_asc',
      rankingQualityTieBand: 1,
      requireBenchmarkPriorForAutomaticContinuation: false,
      requireConfirmationBeforeExpensiveContinuation: true,
      lowComplexityWorkerStartTierConfigured: 'LIGHTWEIGHT',
      lowComplexityWorkerStartTierEffective: 'WORKHORSE',
      topEligibleCandidates: [],
    };

    expect(modelRankingLabel(policy.rankingPolicy)).toBe(
      'Selected by quality first, then cost and latency',
    );
    expect(modelRankingDetails(policy)).toEqual([
      'Reviewer/build trigger',
      'cost and latency break ties within 1.0 quality points',
      'benchmark evidence is advisory for this run',
      'manual approval controls model/provider upgrades',
      'light tasks start on workhorse (lightweight ignored)',
    ]);
    const detailsText = modelRankingDetails(policy).join(' ');
    expect(detailsText).not.toContain('unbenchmarked');
    expect(detailsText).not.toContain('auto-run');

    const candidate = {
      provider: 'OPENROUTER',
      model: 'anthropic/claude-sonnet-4.6',
      qualityScore: 93.2,
      costPerMillionTokens: 4.5,
      latencyP50Ms: 2100,
      benchmarkQualityPriorAvailable: false,
    };
    expect(modelRankingCandidateLabel(candidate)).toBe('OpenRouter/anthropic/claude-sonnet-4.6');
    expect(modelRankingCandidateMetrics(candidate)).toBe(
      'quality 93 · $4.50 per 1M tokens · p50 2s · benchmark evidence missing',
    );
    expect(modelRankingCandidateMetrics(candidate)).not.toContain('no bench');
  });

  it('keeps raw planner commands, route jargon, and token volume out of collapsed step copy', () => {
    const task: api.TaskSummary = {
      taskId: 'T1',
      title: 'npm run build && npm test\nthen inspect the raw compiler output',
      status: 'BLOCKED',
      assignedModel: 'grok-4.20',
      provider: 'openrouter',
      model: 'grok-4.20',
      inputTokens: 120_000,
      outputTokens: 8_000,
      durationMs: 65_000,
      branchName: null,
      startedAt: null,
      completedAt: null,
      readmeUpdated: false,
      goalDoneness: {
        budgetCeilingReason: 'budget, token, iteration, or wall-clock ceiling reached',
        totalTokens: 128_000,
        wallClockMs: 65_000,
      },
      continuationDecision: {
        decision: 'STOP_BLOCKED',
        approvalRequiredReason: 'BENCHMARK_PRIOR_REQUIRED',
        selectedCandidate: {
          provider: 'openrouter',
          model: 'grok-4.20',
          benchmarkQualityPriorAvailable: false,
        },
        policy: {
          requireBenchmarkPriorForAutomaticContinuation: true,
          maxTotalTokens: 100_000,
        },
        reasons: ['proposed action CHANGE_PROVIDER -> openrouter/grok-4.20'],
      },
    };

    const collapsed = [
      customerStepTitle(task, 0),
      customerStepStatusLabel(task.status),
      customerStepSummary(task),
      customerContinuationSummary(task),
      customerLimitSummary(task),
    ].join(' ');

    expect(customerStepTitle(task, 0)).toBe('Step 1');
    expect(customerStepStatusLabel(task.status)).toBe('Needs input');
    expect(customerContinuationSummary(task)).toBe(
      'Approval is needed before ExecuteSpec can try the recommended recovery.',
    );
    expect(customerLimitSummary(task)).toBe('Stopped at the configured processing limit.');
    expect(collapsed).not.toContain('npm run build');
    expect(collapsed).not.toContain('CHANGE_PROVIDER');
    expect(collapsed).not.toContain('openrouter');
    expect(collapsed).not.toContain('grok-4.20');
    expect(collapsed).not.toContain('128,000');
    expect(collapsed).not.toContain('ceiling reached');
  });

  it('does not claim a configured limit stopped a completed step with stale ceiling evidence', () => {
    expect(
      customerLimitSummary({
        status: 'COMPLETE',
        goalDoneness: {
          budgetCeilingReason: 'budget, token, iteration, or wall-clock ceiling reached',
        },
      } as api.TaskSummary),
    ).toBeNull();
    expect(customerStepTitle({title: 'Run `npm test` and repair failures'}, 2)).toBe(
      'Run the project checks and repair failures',
    );
  });

  it('does not offer or request the same recovery approval after its durable action was recorded', () => {
    const task: api.TaskSummary = {
      taskId: 'T-approved',
      title: 'Recover failed build',
      status: 'PARTIAL',
      assignedModel: 'grok-4.20',
      inputTokens: 0,
      outputTokens: 0,
      durationMs: 1000,
      branchName: null,
      startedAt: null,
      completedAt: null,
      readmeUpdated: false,
      continuationDecision: {
        decision: 'STOP_BLOCKED',
        approvalRequiredReason: 'BENCHMARK_PRIOR_REQUIRED',
        selectedCandidate: {
          provider: 'openrouter',
          model: 'grok-4.20',
          benchmarkQualityPriorAvailable: false,
        },
        policy: {
          requireBenchmarkPriorForAutomaticContinuation: true,
        },
      },
      continuationActions: [
        {
          status: 'APPROVED',
          decision: 'CHANGE_PROVIDER',
          provider: 'OPENROUTER',
          model: 'grok-4.20',
        },
        {
          status: 'SIMULATED',
          decision: 'CHANGE_PROVIDER',
          provider: 'OPENROUTER',
          model: 'grok-4.20',
        },
      ],
    };

    expect(matchingContinuationApprovalRecorded(task)).toBe(true);
    expect(continuationApprovalCandidate(task)).toBeNull();
    expect(customerContinuationSummary(task)).toBe(
      'The approved recovery was recorded. Review its result in technical details.',
    );
    expect(customerContinuationSummary(task)).not.toContain('Approval is needed');

    const unrelatedApproval: api.TaskSummary = {
      ...task,
      continuationActions: [
        {
          status: 'EXECUTED',
          decision: 'CHANGE_PROVIDER',
          provider: 'OPENAI',
          model: 'gpt-5.4',
        },
      ],
    };
    expect(matchingContinuationApprovalRecorded(unrelatedApproval)).toBe(false);
    expect(continuationApprovalCandidate(unrelatedApproval)).not.toBeNull();
  });

  it('routes run evidence questions away from fresh-run proposals', () => {
    expect(shouldUseRunEvidenceChat('Why did this run fail?')).toBe(true);
    expect(
      shouldUseRunEvidenceChat(
        'Did worker code generation start? Explain the exact planning failure and whether the provider was unavailable.',
      ),
    ).toBe(true);
    expect(shouldUseRunEvidenceChat('Show evidence for the reported files')).toBe(true);
    expect(shouldUseRunEvidenceChat('What did it create?')).toBe(true);
    expect(shouldUseRunEvidenceChat('Implement the missing endpoint')).toBe(false);
    expect(shouldUseRunEvidenceChat('Start a new run that fixes the tests')).toBe(false);
  });

  it('identifies failed terminal runs and zero-cost evidence states', () => {
    expect(isFailedTerminalStatus('PARTIAL')).toBe(true);
    expect(isFailedTerminalStatus('FAILED')).toBe(true);
    expect(isFailedTerminalStatus('COMPLETE')).toBe(false);
    expect(
      hasRunCostEvidence({
        totalInputTokens: 0,
        totalOutputTokens: 0,
        aiCreditsCharged: 0,
        actualCostUsd: 0,
        classifierCostUsd: 0,
        outlineCostUsd: 0,
        expansionCostUsd: 0,
      } as api.RunDetail),
    ).toBe(false);
    expect(
      hasRunCostEvidence({
        totalInputTokens: 0,
        totalOutputTokens: 12,
        aiCreditsCharged: 0,
      } as api.RunDetail),
    ).toBe(true);
    expect(
      hasRunCostEvidence({
        totalInputTokens: 0,
        totalOutputTokens: 0,
        aiCreditsCharged: 3,
      } as api.RunDetail),
    ).toBe(true);
  });

  it('disables test rerun with explicit copy for failed no-code/no-test runs', () => {
    expect(testRerunState({status: 'PARTIAL', hasTestPlan: false, hasTests: false})).toEqual({
      canRerunTests: false,
      emptyMessage:
        'No test plan or measured tests are available for this failed run. Re-run is disabled until the run has generated code or a test plan to execute.',
    });
    expect(testRerunState({status: 'COMPLETE', hasTestPlan: false, hasTests: false})).toEqual({
      canRerunTests: false,
      emptyMessage: 'No test report yet.',
    });
    expect(testRerunState({status: 'FAILED', hasTestPlan: true, hasTests: false})).toEqual({
      canRerunTests: true,
      emptyMessage: 'No test report yet.',
    });
    expect(
      testRerunState({
        status: 'PARTIAL',
        hasTestPlan: false,
        hasTests: false,
        hasProducedWorkspace: true,
      }),
    ).toEqual({canRerunTests: true, emptyMessage: 'No test report yet.'});
  });

  it('recognizes only terminal manual test execution states', () => {
    expect(isTestExecutionTerminal('EXECUTING')).toBe(false);
    expect(isTestExecutionTerminal('COMPILED')).toBe(false);
    expect(isTestExecutionTerminal('COMPLETED')).toBe(true);
    expect(isTestExecutionTerminal('failed')).toBe(true);
    expect(isTestExecutionTerminal(null)).toBe(false);
  });

  it('diagnoses reviewing runs from task, report, test, and retained-log evidence', () => {
    const eventLog: api.RunLogEvent[] = [
      {type: 'RUN_STATUS', payload: 'REVIEWING'},
      {
        type: 'REVIEWER_FAILED',
        data: {phase: 'fix-build', error: 'sandbox denied python'},
      },
    ];
    expect(
      runPhaseDiagnosis({
        status: 'REVIEWING',
        tasks: [
          {status: 'COMPLETE', failureReason: null},
          {status: 'PARTIAL', failureReason: 'final build failed'},
        ],
        report: null,
        tests: null,
        eventLog,
      }),
    ).toEqual({
      title: 'Reviewer is still resolving this run.',
      body: 'At least one worker step already needs attention. Check Steps and Activity Log before assuming the reviewer is simply slow.',
      facts: [
        '1/2 worker tasks executed · final verification is separate',
        '1 step need attention',
        'validation report not ready',
        'latest: Reviewer · fix-build · failed · sandbox denied python',
      ],
      primaryTab: 'Tasks',
    });
  });

  it('diagnoses validation/reporting waits without inventing a failure', () => {
    expect(
      runPhaseDiagnosis({
        status: 'VALIDATING',
        tasks: [{status: 'COMPLETE', failureReason: null}],
        report: {reviewerVerdict: 'CLEAN', reviewFailures: 0, finalBuildPassed: true},
        tests: {totalTests: 3, passed: 3},
        eventLog: [],
      }),
    ).toEqual({
      title: 'Validation evidence is still being assembled.',
      body: 'The run has moved past worker execution, but the final report or measured proof is not ready yet. Check Activity Log for the last validation event and Evidence once the report appears.',
      facts: [
        '1/1 worker tasks executed · final verification is separate',
        'reviewer CLEAN',
        'final build passed',
        '3/3 tests passed',
      ],
      primaryTab: 'Logs',
    });
    expect(runPhaseDiagnosis({status: 'COMPLETE'})).toBeNull();
  });

  it('explains validation-reported file counts versus Code-tab workspace files', () => {
    expect(fileCountSummary({reportedFiles: 3, workspaceFiles: 1})).toEqual({
      label: '3 reported · 1 visible',
      title: '1 generated or modified product file(s)',
      mismatch: true,
      explanation:
        'File counts differ because validation reports persisted generation evidence, while this count uses current git-derived product changes and excludes bootstrap metadata.',
    });
    expect(fileCountSummary({reportedFiles: 2, workspaceFiles: 2})).toMatchObject({
      label: '2 reported',
      mismatch: false,
      explanation: null,
    });
    expect(fileCountSummary({reportedFiles: null, workspaceFiles: 4})).toMatchObject({
      label: '4',
      mismatch: false,
      explanation: null,
    });
  });

  it('explains empty Code tab differently for failed terminal runs', () => {
    expect(emptyCodeTabMessage({hasFiles: true, status: 'PARTIAL'})).toBe('Select a file');
    expect(emptyCodeTabMessage({hasFiles: false, status: 'PARTIAL'})).toBe(
      'No generated files. This failed run did not leave product code to inspect.',
    );
    expect(emptyCodeTabMessage({hasFiles: false, status: 'COMPLETE'})).toBe('No generated files');
  });

  it('explains missing scores from the actual run lifecycle', () => {
    expect(
      missingScoreExplanation({
        runMode: 'BUILD',
        status: 'FAILED',
        triggeredByFinding: null,
      } as api.RunSummary),
    ).toBe('No verified score was produced because this run stopped before validation completed.');
    expect(
      missingScoreExplanation({
        runMode: 'PATCH',
        status: 'COMPLETE',
        triggeredByFinding: null,
      } as api.RunSummary),
    ).toContain("patch and benchmark runs aren't scored");
    expect(
      missingScoreExplanation({
        runMode: 'BUILD',
        status: 'VALIDATING',
        triggeredByFinding: null,
      } as api.RunSummary),
    ).toBe('A verified score will appear after validation completes.');
  });

  it('keeps small formatting helpers stable', () => {
    expect(fmtDur(59_400)).toBe('59s');
    expect(fmtDur(61_000)).toBe('1m 1s');
    expect(usd(1.234)).toBe('$1.2340');
    expect(usd(0.00009)).toBe('$0.0001');
    expect(num(12345)).toBe('12,345');
    expect(evidenceKind('PASS')).toBe('ok');
    expect(evidenceKind('FAIL')).toBe('bad');
    expect(confidenceColor('LOW')).toBe('var(--ide-red)');
    expect(TAB_LABEL.Chat).toBe('Ask');
  });

  it('turns dispatch-budget failures into actionable Ask guidance', () => {
    const message = friendlyAiFailureMessage(
      new Error('LlmDispatchPolicyException: Durable LLM execution-context budget is exhausted'),
    );

    expect(message).toContain('used its available AI context');
    expect(message).toContain('without additional AI spend');
    expect(message).not.toContain('LlmDispatchPolicyException');
  });

  it('keeps an unknown Ask failure visible for diagnosis', () => {
    expect(friendlyAiFailureMessage(new Error('chat service unavailable'))).toBe(
      'chat service unavailable',
    );
  });

  it('does not discard typed Ask incompleteness when a server omits the text notice', () => {
    const content = runChatResponseContent({
      reply: 'Partial repository analysis',
      messageCount: 1,
      maxMessages: 50,
      complete: false,
      incompleteReason: 'PROVIDER_OUTPUT_LIMIT',
    });

    expect(content).toContain('Partial repository analysis');
    expect(content).toContain('answer is incomplete');
    expect(content).toContain('specific file');
  });
});
