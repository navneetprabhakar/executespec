import {describe, expect, it} from 'vitest';

import {scoreTrustLabel} from './ScoreBadge';

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
