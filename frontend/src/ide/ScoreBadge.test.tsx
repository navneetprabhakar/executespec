import {describe, expect, it} from 'vitest';

import {scoreTrustLabel, scoreZeroCause} from './ScoreBadge';

describe('score trust labels', () => {
  it('treats list scores without a loaded ledger as unverified', () => {
    expect(scoreTrustLabel()).toBe('unverified');
    expect(scoreTrustLabel(null)).toBe('unverified');
    expect(scoreTrustLabel([])).toBe('unverified');
  });

  it('treats a loaded ledger as review evidence rather than proof of correctness', () => {
    expect(scoreTrustLabel([{source: 'INTERNAL_FORMULA', confidence: 'MEDIUM'}])).toBe(
      'review evidence',
    );
  });
});

describe('score zero cause', () => {
  it('uses only the typed causal ledger row', () => {
    expect(
      scoreZeroCause(0, [
        {id: 'build', summary: 'Build failed.'},
        {
          id: 'score-zero-cause',
          summary: 'Deterministic code-delivery gate failed: no files were generated.',
        },
      ]),
    ).toBe('Deterministic code-delivery gate failed: no files were generated.');
  });

  it('does not invent a cause for legacy or nonzero scores', () => {
    expect(scoreZeroCause(0, [{id: 'score-summary', summary: 'verifiedScore=0'}])).toBeNull();
    expect(
      scoreZeroCause(42, [{id: 'score-zero-cause', summary: 'Deterministic gate failed.'}]),
    ).toBeNull();
  });
});
