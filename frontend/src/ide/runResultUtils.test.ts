import {describe, expect, it} from 'vitest';

import {
  confidenceColor,
  evidenceKind,
  fmtDur,
  isNoisyLogEvent,
  lineDiff,
  logIcon,
  logMeta,
  logMessage,
  num,
  shouldShowModelRanking,
  TAB_LABEL,
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
