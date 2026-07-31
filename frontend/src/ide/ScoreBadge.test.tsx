import {describe, expect, it} from 'vitest';

import {
  scoreDisplayLabel,
  scoreTrustLabel,
  scoreZeroCause,
  shouldSuppressUnexplainedZero,
} from './ScoreBadge';

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
  it('suppresses numeric zero on list surfaces until causal evidence is loaded', () => {
    expect(shouldSuppressUnexplainedZero(0)).toBe(true);
    expect(shouldSuppressUnexplainedZero(0, [])).toBe(true);
    expect(shouldSuppressUnexplainedZero(0, [{id: 'score-zero-cause'}])).toBe(false);
    expect(shouldSuppressUnexplainedZero(1)).toBe(false);
    expect(scoreDisplayLabel(0)).toBe('Score unavailable · open result');
    expect(scoreDisplayLabel(0, undefined, true)).toBe('Score unavailable');
    expect(scoreDisplayLabel(0)).not.toContain('0');
  });

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
