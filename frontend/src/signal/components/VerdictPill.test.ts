import {describe, expect, it} from 'vitest';
import {verdictFor} from './VerdictPill';

/**
 * C13 (UI/UX review 2026-06-11) — pin the canonical verdict bands
 * (≥85 Shippable · 70–84 Needs review · <70 Failed, since 2026-05-16).
 * Several surfaces used to hardcode their own thresholds (ResultPage was ≥90);
 * this test keeps the single source of truth honest.
 */
describe('verdictFor bands', () => {
  it('≥85 is Shippable', () => {
    expect(verdictFor(85).label).toBe('Shippable');
    expect(verdictFor(100).label).toBe('Shippable');
  });
  it('70–84 is Needs review', () => {
    expect(verdictFor(70).label).toBe('Needs review');
    expect(verdictFor(84).label).toBe('Needs review');
  });
  it('<70 is Failed/Blocked band', () => {
    expect(verdictFor(69).label).not.toBe('Needs review');
    expect(verdictFor(69).label).not.toBe('Shippable');
    expect(verdictFor(0).label).toBe(verdictFor(69).label);
  });
  it('null score does not crash and is not Shippable', () => {
    expect(verdictFor(null).label).not.toBe('Shippable');
    expect(verdictFor(undefined).label).not.toBe('Shippable');
  });
});
