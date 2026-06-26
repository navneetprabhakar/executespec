import {describe, expect, it} from 'vitest';

import {
  confidenceColor,
  evidenceKind,
  fmtDur,
  isNoisyLogEvent,
  lineDiff,
  logIcon,
  logMessage,
  num,
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
    expect(logIcon('GOAL_DECISION')).toBe('🧭');
    expect(logIcon('UNKNOWN')).toBe('·');
    expect(isNoisyLogEvent({type: 'RUN_DETAIL'} as api.RunLogEvent)).toBe(true);
    expect(
      logMessage({type: 'TOKEN_USAGE', data: {message: 'tokens updated'}} as api.RunLogEvent),
    ).toBe('tokens updated');
    expect(logMessage({type: 'EMPTY_EVENT'} as api.RunLogEvent)).toBe('empty event');
  });

  it('caps very large log messages defensively', () => {
    const msg = logMessage({type: 'LOG', payload: 'x'.repeat(400)} as api.RunLogEvent);
    expect(msg).toHaveLength(281);
    expect(msg.endsWith('…')).toBe(true);
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
