import {describe, expect, it} from 'vitest';
import {parseAgentRunEvent, terminalStatusFromEvent} from './agentRunStream';

describe('agent run event stream', () => {
  it('parses typed JSON events and rejects malformed frames', () => {
    expect(parseAgentRunEvent('{"type":"RUN_STATUS","payload":"WORKING"}')).toEqual({
      type: 'RUN_STATUS',
      payload: 'WORKING',
    });
    expect(parseAgentRunEvent('not-json')).toBeNull();
  });

  it('recognises terminal status reasons carried in RUN_STATUS payloads', () => {
    expect(
      terminalStatusFromEvent({type: 'RUN_STATUS', payload: 'PARTIAL|FINAL_BUILD_FAILED'}),
    ).toBe('PARTIAL|FINAL_BUILD_FAILED');
    expect(terminalStatusFromEvent({type: 'RUN_STATUS', payload: 'WORKING'})).toBeNull();
  });

  it('recognises the COMPLETE event so a closed SSE does not reconnect forever', () => {
    expect(terminalStatusFromEvent({type: 'COMPLETE', payload: 'COMPLETE'})).toBe('COMPLETE');
  });

  it('reads terminal status from a typed RUN_DETAIL snapshot', () => {
    expect(
      terminalStatusFromEvent({type: 'RUN_DETAIL', data: {status: 'CANCELLED'}}),
    ).toBe('CANCELLED');
  });
});
