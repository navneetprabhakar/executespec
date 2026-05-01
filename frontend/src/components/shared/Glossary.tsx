import {ReactNode} from 'react';
import {Tooltip} from './Tooltip';
import {lookupTerm} from '@/lib/glossary';

interface GlossaryProps {
  /** Lookup key (case-insensitive). See `lib/glossary.ts` for the dictionary. */
  term: string;
  /**
   * Override the rendered text. Defaults to the dictionary entry's
   * `display`. Use this when the surrounding sentence wants a different
   * wording (e.g. "Verified" instead of "verified score").
   */
  children?: ReactNode;
  /** Tooltip side. Default: top. */
  side?: 'top' | 'bottom' | 'left' | 'right';
  /** Optional className applied to the wrapped span. */
  className?: string;
}

/**
 * Glossary — wraps any jargon with a hover/focus tooltip carrying the
 * plain-English definition from `lib/glossary.ts`.
 *
 * Plan 1 (this-week #4): "what does that mean?" is the most common UX
 * question raised in the assessment. Solving it in-place — without leaving
 * the page — is cheaper than a full vocabulary rewrite.
 *
 *   <Glossary term="codemap">codemap</Glossary>
 *   <Glossary term="verified score">Verified</Glossary>
 *
 * Falls through cleanly when the term isn't in the dictionary — renders the
 * children unchanged so missing entries never break layout. Adds a subtle
 * dotted underline to signal the affordance, which screen-readers also
 * announce via `aria-describedby` on the inner Tooltip.
 */
export function Glossary({term, children, side = 'top', className}: GlossaryProps) {
  const entry = lookupTerm(term);
  const label = children ?? entry?.display ?? term;
  if (!entry) return <>{label}</>;
  return (
    <Tooltip text={entry.definition} side={side} wrap maxWidthClass='max-w-72'>
      <span
        className={`underline decoration-dotted decoration-muted/60 underline-offset-[3px] cursor-help ${className ?? ''}`}
        tabIndex={0}
        aria-label={`${entry.display}: ${entry.definition}`}
      >
        {label}
      </span>
    </Tooltip>
  );
}
