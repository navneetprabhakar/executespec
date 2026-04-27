import Pill from '../components/Pill';
import Term from '../components/Term';
import { VERDICT_LABELS, STATUS_LABELS } from '../lib/terminology';

/**
 * R-101 — temporary v3 home placeholder.
 *
 * Doubles as the live verification page for new primitives (Pill,
 * Term, etc.) until the real R-120 project home lands. Each new
 * primitive added in Wave 1 gets a row here so we can eyeball it
 * end-to-end without booting the full chat shell.
 */
export default function V3HomePlaceholder() {
  return (
    <div className="v3-placeholder" style={{ textAlign: 'left' }}>
      <h1 style={{ textAlign: 'center' }}>v3 shell · ready</h1>
      <p style={{ textAlign: 'center' }}>
        Route <code>/v3/home</code> mounted via <code>V3Shell</code>.
      </p>

      <hr style={{ margin: '20px 0', border: 0, borderTop: '1px solid var(--v3-border)' }} />

      <p style={{ marginBottom: 8, color: 'var(--v3-text)', fontSize: 13, fontWeight: 600 }}>
        R-106 · Pill primitive
      </p>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 16 }}>
        <Pill tone="ok">{VERDICT_LABELS.READY}</Pill>
        <Pill tone="warn">{VERDICT_LABELS.NEEDS_REVIEW}</Pill>
        <Pill tone="err">{VERDICT_LABELS.FAILED}</Pill>
        <Pill tone="neutral">{STATUS_LABELS.CANCELLED}</Pill>
        <Pill tone="info" dot>{STATUS_LABELS.WORKING}</Pill>
        <Pill tone="brand">Anthropic</Pill>
        <Pill tone="ok" size="sm">Build ✓</Pill>
        <Pill tone="ok" size="sm">73/73 tests</Pill>
        <Pill tone="ok" size="sm">Reviewer 0 issues</Pill>
      </div>

      <p style={{ marginBottom: 8, color: 'var(--v3-text)', fontSize: 13, fontWeight: 600 }}>
        R-106 · Term primitive (hover for glossary)
      </p>
      <p style={{ marginBottom: 16 }}>
        A <Term name="run">run</Term> is one submit-to-result cycle. The{' '}
        <Term name="verdict">verdict</Term> is backed by four signals; the{' '}
        <Term name="reviewer">reviewer</Term> is the second AI pass. Your{' '}
        <Term name="code map">code map</Term> tells the planner what's in your repo.
      </p>

      <hr style={{ margin: '20px 0', border: 0, borderTop: '1px solid var(--v3-border)' }} />

      <p style={{ textAlign: 'center', color: 'var(--v3-text-subtle)', fontSize: 11.5 }}>
        Project home (<code>R-120</code>) lands next. Top-bar
        placeholders fill in via <code>R-103</code>, <code>R-104</code>,{' '}
        <code>R-124</code>.
      </p>
    </div>
  );
}
