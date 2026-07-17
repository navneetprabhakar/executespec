import {describe, expect, it} from 'vitest';

import {mergeRunEntries, replayEntry, replayNotice, runEventKey} from './runReplay';

describe('run replay reconciliation', () => {
  it('uses the server sequence to dedupe a replay row and its live duplicate', () => {
    const replay = replayEntry({type: 'TOOL_CALL', _eventId: '42', payload: 'read'});
    const live = {
      ...replay,
      id: runEventKey({type: 'TOOL_CALL', payload: 'read'}, '42'),
      timestamp: 100,
    };
    expect(mergeRunEntries([replay], [live])).toEqual([live]);
  });

  it('dedupes legacy rows using deterministic event content', () => {
    const event = {type: 'LOG', timestamp: 123, taskId: 't1', payload: 'building'};
    expect(mergeRunEntries([replayEntry(event)], [replayEntry(event)])).toHaveLength(1);
  });

  it('describes expired and infrastructure-unavailable replay honestly', () => {
    expect(
      replayNotice({
        events: [],
        available: false,
        reason: 'EXPIRED_OR_EMPTY',
        retainedUntil: null,
        lastEventId: null,
        truncated: false,
      }),
    ).toContain('new run or after');
    expect(
      replayNotice({
        events: [],
        available: false,
        reason: 'STORE_UNAVAILABLE',
        retainedUntil: null,
        lastEventId: null,
        truncated: false,
      }),
    ).toContain('could not be loaded');
  });
});
