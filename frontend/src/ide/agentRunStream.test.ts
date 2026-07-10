import {describe, expect, it} from 'vitest';
import {parseAgentRunEvent} from './agentRunStream';

describe('agent run event stream', () => {
  it('parses typed JSON events and rejects malformed frames', () => {
    expect(parseAgentRunEvent('{"type":"RUN_STATUS","payload":"WORKING"}')).toEqual({
      type: 'RUN_STATUS',
      payload: 'WORKING',
    });
    expect(parseAgentRunEvent('not-json')).toBeNull();
  });
});
