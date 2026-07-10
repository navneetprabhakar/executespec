import {describe, expect, it} from 'vitest';
import {semanticTranscriptFromEvents} from './semanticTranscript';

describe('semanticTranscriptFromEvents', () => {
  it('groups tokens and removes transport-only noise', () => {
    const result = semanticTranscriptFromEvents([
      {event: 'ack', data: {session_id: 's', turn_id: 't', sequence: 1}},
      {
        event: 'route_decision',
        data: {route: 'READ_ONLY_CODE_HELP', confidence: 0.9, reason_code: 'read'},
      },
      {event: 'token', data: {delta: 'hello '}},
      {event: 'token', data: {delta: 'world'}},
      {event: 'tool_call', data: {id: 'c1', tool: 'searchFiles', args: '{}'}},
      {event: 'tool_result', data: {id: 'c1', result: '{"ok":true}'}},
      {event: 'done', data: {turn_id: 't', turn_count: 1}},
    ]);
    expect(result.map((item) => item.kind)).toEqual([
      'route',
      'assistant',
      'tool_call',
      'tool_result',
      'completion',
    ]);
    expect(result[1]).toMatchObject({kind: 'assistant', text: 'hello world'});
  });
});
