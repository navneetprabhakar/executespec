import {ReactNode} from 'react';

/**
 * Plain-English dictionary for ExecuteSpec jargon — same 5 terms v3 ships.
 * Surfacing the definition on hover/focus is much friendlier than expecting
 * users to grep the docs for "verified score" or "status reason".
 */
export const GLOSSARY: Record<string, string> = {
  codemap:
    'A per-repo index of file purposes, public symbols, and dependency edges. We generate it before planning so the agent can reason about your code without re-reading every file.',
  'wiring pass':
    'A reviewer step that fixes call-site mismatches after the worker has finished editing — e.g. an old import that no longer resolves, or a function whose signature changed mid-run.',
  'verified score':
    'A 0–100 score combining build, tests, coverage, reviewer, security, and architecture signals — multiplicatively, so any single failure caps the score.',
  'status reason':
    'A machine-readable label for why a run landed in its current status — e.g. TESTS_FAILED:2_OF_8 or REVIEWER_UNRESOLVED. Used by the UI to explain badges.',
  classifier:
    'A lightweight model that picks the plan strategy (SINGLE_SHOT, SMALL, MEDIUM, LARGE, LEGACY) before the heavier planner runs, so we only pay for hierarchical planning when the spec needs it.',
};

/**
 * Glossary — wraps a term with a hover/focus tooltip that surfaces the
 * plain-English definition. Falls back to plain text if the term isn't
 * in the dictionary so callers can use it defensively.
 *
 * Usage: <Glossary term="verified score">verified score</Glossary>
 */
export function Glossary({term, children}: {term: string; children?: ReactNode}) {
  const def = GLOSSARY[term.toLowerCase()];
  const label = children ?? term;
  if (!def) return <>{label}</>;
  return (
    <span className='signal-glossary' tabIndex={0} role='button' aria-describedby={`glossary-${term}`}>
      {label}
      <span className='signal-glossary-tip' id={`glossary-${term}`} role='tooltip'>{def}</span>
    </span>
  );
}
