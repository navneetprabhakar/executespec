/**
 * F-A11Y (round-3 audit) — pure-function helpers for WCAG contrast math.
 *
 * Why this exists: the round-3 token sweep (F-15/16/17) added new
 * tokens (--c-graphite-7, --c-signal-blue-on-dark, fixed
 * --status-executing) tuned to specific contrast ratios. Without a
 * regression test, the next palette tweak silently re-breaks the
 * thresholds (the previous R-batch did exactly that — math was done
 * against #FFFFFF, not the live #14171C surface).
 *
 * The helpers here let `contrast.test.ts` pin every (foreground,
 * background) pair the audit cares about. Pure functions, zero deps,
 * works in any test runner (vitest, jest, node).
 */

/** sRGB hex → [r,g,b] in 0–255. Returns null on invalid input. */
export function parseHex(hex: string): [number, number, number] | null {
  if (typeof hex !== 'string') return null;
  const trimmed = hex.trim();
  if (!trimmed.startsWith('#')) return null;
  const body = trimmed.slice(1);
  const expanded =
    body.length === 3
      ? body
          .split('')
          .map((c) => c + c)
          .join('')
      : body.padEnd(6, '0').slice(0, 6);
  if (!/^[0-9a-fA-F]{6}$/.test(expanded)) return null;
  return [
    parseInt(expanded.slice(0, 2), 16),
    parseInt(expanded.slice(2, 4), 16),
    parseInt(expanded.slice(4, 6), 16),
  ];
}

/** Relative luminance per WCAG 2.x §1.4.3. */
export function luminance(rgb: [number, number, number]): number {
  const f = (v: number) => {
    const x = v / 255;
    return x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * f(rgb[0]) + 0.7152 * f(rgb[1]) + 0.0722 * f(rgb[2]);
}

/** WCAG contrast ratio between two hex colors. Returns 1.0 on bad input. */
export function contrastRatio(fg: string, bg: string): number {
  const a = parseHex(fg);
  const b = parseHex(bg);
  if (!a || !b) return 1;
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/** WCAG AA thresholds. Use these as min-floors in regression tests. */
export const WCAG = {
  /** Normal-size body text (≥14pt regular, ≥18pt bold-ish). */
  AA_BODY: 4.5,
  /** Large text (≥18pt regular, ≥14pt bold). */
  AA_LARGE: 3.0,
  /** Non-text UI components (borders, icons, focus rings) — SC 1.4.11. */
  AA_NON_TEXT: 3.0,
  /** AAA body — aspirational. */
  AAA_BODY: 7.0,
} as const;
