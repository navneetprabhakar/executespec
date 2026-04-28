import type { ReactNode } from 'react';
import { lookupGlossary } from '../lib/glossary';

/**
 * R-106 — glossary term wrapper.
 *
 *   <Term name="run">Run</Term>
 *
 * Renders the children as the visible text + a small `?` icon.
 * Hover/focus surfaces a native `title` tooltip with the glossary
 * definition (the strict review §13 fix that "every undefined term
 * gets a ? icon"). Falls back gracefully if the term name doesn't
 * resolve in `glossary.ts` — just renders the children unchanged.
 *
 * Native `title` for v1; the v3 design system has a real Tooltip
 * primitive (frontend/src/components/shared/Tooltip.tsx in v2) we
 * can promote later if hover delays / styling matter.
 */
interface TermProps {
  name: string;
  children: ReactNode;
  /** When set, the `?` icon is suppressed and only the title-tooltip
   *  is wired (useful inside dense rows where a glyph would clutter). */
  iconOnly?: false;
  /** Suppress the `?` glyph entirely; tooltip still applies. */
  hideHint?: boolean;
}

export default function Term({ name, children, hideHint = false }: TermProps) {
  const entry = lookupGlossary(name);
  const tooltip = entry ? `${entry.term}: ${entry.definition}` : undefined;

  if (!entry) {
    // Honest fallback so unresolved names don't crash the page;
    // the lint rule (R-105 follow-up) will flag missing entries
    // separately.
    return <>{children}</>;
  }

  return (
    <span
      tabIndex={0}
      title={tooltip}
      style={{
        display: 'inline-flex',
        alignItems: 'baseline',
        gap: 2,
        cursor: 'help',
        borderBottom: '1px dotted rgba(148,163,184,0.4)',
      }}
    >
      {children}
      {!hideHint && (
        <span
          aria-hidden="true"
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 12,
            height: 12,
            marginLeft: 2,
            fontSize: 9,
            fontWeight: 700,
            color: '#94A3B8',
            border: '1px solid rgba(148,163,184,0.4)',
            borderRadius: '50%',
            lineHeight: 1,
          }}
        >
          ?
        </span>
      )}
    </span>
  );
}
