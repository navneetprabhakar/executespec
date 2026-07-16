import {describe, expect, it} from 'vitest';
import {boundedInlineDiff} from './agentDiff';

describe('boundedInlineDiff', () => {
  it('renders added files as additions without mutation affordances', () => {
    expect(
      boundedInlineDiff({path: 'src/a.ts', status: 'ADDED', base: '', head: 'one\ntwo'}),
    ).toEqual({
      lines: [
        {sign: '+', text: 'one'},
        {sign: '+', text: 'two'},
      ],
      truncated: false,
    });
  });

  it('bounds a large diff before it reaches the chat viewer', () => {
    const head = Array.from({length: 3_100}, (_, index) => `line-${index}`).join('\n');
    const result = boundedInlineDiff({path: 'large.ts', status: 'ADDED', base: '', head});
    expect(result.lines).toHaveLength(2_500);
    expect(result.truncated).toBe(true);
  });
});
