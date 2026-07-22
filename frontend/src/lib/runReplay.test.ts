import {describe, expect, it} from 'vitest';

import {
  mergeRunEntries,
  normalizeEventTimestamp,
  replayEntry,
  replayNotice,
  runEventKey,
} from './runReplay';

describe('RR-1381 — replay timestamp normalization', () => {
  it('parses an ISO-8601 string (the persisted replay shape) instead of dropping it to 1970', () => {
    const iso = '2026-07-21T10:15:30Z';
    expect(normalizeEventTimestamp(iso)).toBe(Date.parse(iso));
    expect(replayEntry({type: 'LOG', timestamp: iso}).timestamp).toBe(Date.parse(iso));
  });

  it('passes a finite epoch number through and rejects unparseable/absent values as 0', () => {
    expect(normalizeEventTimestamp(1200)).toBe(1200);
    expect(normalizeEventTimestamp('not-a-date')).toBe(0);
    expect(normalizeEventTimestamp(undefined)).toBe(0);
    expect(normalizeEventTimestamp(Number.NaN)).toBe(0);
  });
});

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
