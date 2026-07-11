import {describe, expect, it} from 'vitest';

import {
  confidenceColor,
  evidenceKind,
  emptyCodeTabMessage,
  fileCountSummary,
  fmtDur,
  goalDecisionHelp,
  goalDecisionLabel,
  goalStateLabel,
  hasRunCostEvidence,
  isNoisyLogEvent,
  isFailedTerminalStatus,
  isTestExecutionTerminal,
  lineDiff,
  logIcon,
  logLabel,
  logMeta,
  logMessage,
  modelRankingCandidateLabel,
  modelRankingCandidateMetrics,
  modelRankingDetails,
  modelRankingLabel,
  num,
  runPhaseDiagnosis,
  shouldUseRunEvidenceChat,
  shouldShowModelRanking,
  TAB_LABEL,
  testRerunState,
  usd,
  verdictFor,
} from './runResultUtils';
import type * as api from '@/lib/api';

describe('runResultUtils', () => {
  it('maps scores and terminal statuses to verdict labels', () => {
    expect(verdictFor(92, 'COMPLETE')).toEqual({label: 'Shippable', kind: 'ok'});
    expect(verdictFor(72, 'COMPLETE')).toEqual({label: 'Needs review', kind: 'warn'});
    expect(verdictFor(null, 'COMPLETE')).toEqual({label: 'Complete', kind: 'warn'});
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

    expect(shouldShowModelRanking(doneTask)).toBe(false);
    expect(shouldShowModelRanking(providerChangeTask)).toBe(true);
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

  it('routes run evidence questions away from fresh-run proposals', () => {
    expect(shouldUseRunEvidenceChat('Why did this run fail?')).toBe(true);
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
        '1/2 steps complete',
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
      facts: ['1/1 steps complete', 'reviewer CLEAN', 'final build passed', '3/3 tests passed'],
      primaryTab: 'Logs',
    });
    expect(runPhaseDiagnosis({status: 'COMPLETE'})).toBeNull();
  });

  it('explains validation-reported file counts versus Code-tab workspace files', () => {
    expect(fileCountSummary({reportedFiles: 3, workspaceFiles: 1})).toEqual({
      label: '3 reported · 1 visible',
      title: '1 file(s) currently visible in the Code tab',
      mismatch: true,
      explanation:
        'File counts differ because validation reports generated-file evidence, while the Code tab lists the current workspace files. Failed scaffold runs can expose seed files even when no product code was delivered.',
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

  it('keeps small formatting helpers stable', () => {
    expect(fmtDur(59_400)).toBe('59s');
    expect(fmtDur(61_000)).toBe('1m 1s');
    expect(usd(1.234)).toBe('$1.23');
    expect(num(12345)).toBe('12,345');
    expect(evidenceKind('PASS')).toBe('ok');
    expect(evidenceKind('FAIL')).toBe('bad');
    expect(confidenceColor('LOW')).toBe('var(--ide-red)');
    expect(TAB_LABEL.Chat).toBe('Ask');
  });
});
