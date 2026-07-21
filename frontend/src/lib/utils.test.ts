import {describe, expect, it} from 'vitest';

import {UNKNOWN_COST, errMsg, fmtCost} from './utils';

// RR-1538 — `fmtCost` declared a non-nullable `number` but returned the precise value
// `$0.0000` for non-finite input, so an unguarded caller on a spend-reporting surface
// would report a run of unknown cost as having cost nothing.
describe('fmtCost', () => {
  it('renders unknown cost as an explicit unknown marker, not as zero', () => {
    expect(fmtCost(null)).toBe(UNKNOWN_COST);
    expect(fmtCost(undefined)).toBe(UNKNOWN_COST);
    expect(fmtCost(Number.NaN)).toBe(UNKNOWN_COST);
    expect(fmtCost(Number.POSITIVE_INFINITY)).toBe(UNKNOWN_COST);
  });

  it('keeps a genuine zero distinguishable from unknown', () => {
    expect(fmtCost(0)).toBe('$0.0000');
  });

  it('formats real amounts to four decimal places', () => {
    expect(fmtCost(1.23456)).toBe('$1.2346');
  });
});

describe('errMsg', () => {
  it('unwraps Errors, strings and message-bearing objects', () => {
    expect(errMsg(new Error('boom'))).toBe('boom');
    expect(errMsg('boom')).toBe('boom');
    expect(errMsg({message: 'boom'})).toBe('boom');
    expect(errMsg(42)).toBe('42');
  });
});
