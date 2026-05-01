/**
 * F-A11Y (round-3 audit) — token-level contrast regression test.
 *
 * Pins the WCAG ratios for every (token, surface) pair the round-3
 * audit landed a fix for. Vitest-shape — dormant until vitest lands in
 * the frontend (an existing dormant test sits at tierDisplay.test.ts).
 *
 * Manual run: `cd frontend && node --experimental-vm-modules` is not
 * needed; once vitest is installed, `npx vitest run contrast` will
 * pick this up.
 *
 * The fixed values below mirror primitives.css + tokens.css. If a
 * future token tweak shifts a hex, update BOTH the CSS and the
 * fixed value here so the test stays a true regression guard.
 */
// @ts-expect-error — test runner not yet installed; types resolve once vitest is added
import {describe, expect, it} from 'vitest';
import {contrastRatio, WCAG} from './contrast';

// Surface tokens from primitives.css (dark default).
const GRAPHITE_2 = '#14171C'; // gray-100, primary card surface
const GRAPHITE_3 = '#1B1F26'; // gray-200, elevated card
const PAPER_1 = '#F3F4F6'; // light card
const WHITE = '#FFFFFF';

// Fixed token values (must mirror primitives.css + tokens.css).
const TOKENS = {
  signalBlue: '#3B6FCC', // --c-signal-blue (button surface)
  signalBlueHover: '#2B5BB3', // --c-signal-blue-hover
  signalBlueOnDark: '#7AA5F5', // --c-signal-blue-on-dark (link text on dark)
  popOrange: '#EC4899', // --c-pop-orange (brand accent — pink despite the name)
  popAmber: '#FBBF24', // --c-pop-amber
  graphite5: '#333B46', // legacy border-divider-subtle
  graphite7: '#5E6771', // F-15 — new --border-default
  graphite8: '#7B8593', // F-15 — new --border-hover
  textPrimary: '#ECEEF2', // --c-ink-100
  textSecondary: '#C9D1DB', // --c-ink-200
  textMuted: '#8A94A6', // --c-ink-300
  statusSuccess: '#10B981', // --c-success
  statusError: '#EF4444', // --c-error
  statusPartial: '#F5A524', // --status-partial
  statusExecuting: '#5B89E0', // F-17 — was #4A77D6, lifted to AA-body
};

describe('WCAG-AA contrast — body text on dark surface (graphite-2)', () => {
  it('text-primary passes AA-body', () => {
    expect(contrastRatio(TOKENS.textPrimary, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
  it('text-secondary passes AA-body', () => {
    expect(contrastRatio(TOKENS.textSecondary, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
  it('text-muted passes AA-body', () => {
    expect(contrastRatio(TOKENS.textMuted, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
  it('signal-blue-on-dark passes AA-body (F-16)', () => {
    expect(contrastRatio(TOKENS.signalBlueOnDark, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
  it('status-executing passes AA-body (F-17)', () => {
    expect(contrastRatio(TOKENS.statusExecuting, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
  it('status-success passes AA-body', () => {
    expect(contrastRatio(TOKENS.statusSuccess, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
  it('status-partial passes AA-body', () => {
    expect(contrastRatio(TOKENS.statusPartial, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
  it('status-error passes AA-body', () => {
    expect(contrastRatio(TOKENS.statusError, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
  it('pop-orange passes AA-body', () => {
    expect(contrastRatio(TOKENS.popOrange, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
  it('pop-amber passes AA-body', () => {
    expect(contrastRatio(TOKENS.popAmber, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
});

describe('WCAG-AA contrast — non-text UI components on dark surfaces (SC 1.4.11)', () => {
  it('border-default passes AA-non-text on graphite-2 (F-15)', () => {
    expect(contrastRatio(TOKENS.graphite7, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_NON_TEXT);
  });
  it('border-default passes AA-non-text on graphite-3 (F-15)', () => {
    expect(contrastRatio(TOKENS.graphite7, GRAPHITE_3)).toBeGreaterThanOrEqual(WCAG.AA_NON_TEXT);
  });
  it('border-hover passes AA-non-text on graphite-2', () => {
    expect(contrastRatio(TOKENS.graphite8, GRAPHITE_2)).toBeGreaterThanOrEqual(WCAG.AA_NON_TEXT);
  });
});

describe('WCAG-AA contrast — primary button surface (white text on signal-blue)', () => {
  it('white text on signal-blue passes AA-body', () => {
    expect(contrastRatio(WHITE, TOKENS.signalBlue)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
  it('white text on signal-blue-hover passes AA-body', () => {
    expect(contrastRatio(WHITE, TOKENS.signalBlueHover)).toBeGreaterThanOrEqual(WCAG.AA_BODY);
  });
});

describe('Counter-examples — these MUST still fail (so we trust the math)', () => {
  it('pre-fix --border-default (graphite-5) FAILS AA-non-text on graphite-2', () => {
    expect(contrastRatio(TOKENS.graphite5, GRAPHITE_2)).toBeLessThan(WCAG.AA_NON_TEXT);
  });
  it('pre-fix --status-executing (#4A77D6) FAILS AA-body on graphite-2', () => {
    expect(contrastRatio('#4A77D6', GRAPHITE_2)).toBeLessThan(WCAG.AA_BODY);
  });
  it('signal-blue (button surface) used as TEXT on graphite-2 FAILS AA-body — must use signal-blue-on-dark instead', () => {
    expect(contrastRatio(TOKENS.signalBlue, GRAPHITE_2)).toBeLessThan(WCAG.AA_BODY);
  });
});
