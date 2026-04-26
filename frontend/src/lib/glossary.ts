/**
 * Plain-English definitions for ExecuteSpec jargon — surfaced via the
 * <Glossary term="..." /> wrapper (see `components/shared/Glossary.tsx`).
 *
 * Plan 1 (this-week #4): jargon is the second-biggest UX complaint behind
 * the dual-score problem. Rather than rewriting every label, we make every
 * unfamiliar word self-explaining via a hover/focus tooltip with a
 * one-sentence answer.
 *
 * Keep entries short — under ~25 words — so they fit a tooltip without a
 * scroll. Link to deeper docs from there if needed.
 *
 * Add new terms as they surface in user testing. Term keys are
 * case-insensitive at lookup time.
 */

export interface GlossaryEntry {
  /** Canonical user-facing display text. May differ in case/spacing from the lookup key. */
  display: string;
  /** Plain-English definition shown in the tooltip. Single sentence ideally. */
  definition: string;
}

/**
 * Initial seed of 5 terms (Plan 1 starting set). Order is alphabetical for
 * easy scanning.
 */
export const GLOSSARY: Record<string, GlossaryEntry> = {
  classifier: {
    display: 'classifier',
    definition:
      'The first agent that looks at your spec and decides how big the job is. Picks one of SINGLE_SHOT / SMALL / MEDIUM / LARGE / LEGACY, which controls how the rest of the pipeline is staged.',
  },
  codemap: {
    display: 'codemap',
    definition:
      'A compact summary of one repo — file tree, key types, public APIs — generated once per commit. Lets the planner reason about the code without re-reading every file.',
  },
  'status reason': {
    display: 'status reason',
    definition:
      'A machine-readable code on every finished run that explains WHY it landed where it did (e.g. CLEAN_COMPLETION, FINAL_BUILD_FAILED, TESTS_FAILED:2_OF_7). Replaces the older free-text PARTIAL guesswork.',
  },
  'verified score': {
    display: 'verified score',
    definition:
      'A 0–100 number computed from real signals — final build pass, test pass rate, reviewer verdict — not from an LLM self-rating. ≥85 ships, 50–84 needs review, <50 is blocked.',
  },
  'wiring pass': {
    display: 'wiring pass',
    definition:
      'A second reviewer pass that checks every new file is actually imported / registered / called somewhere. Catches "code that compiles but is unreachable" — Express routes not mounted, Spring beans not component-scanned, Rust mods not declared.',
  },
};

/** Look up a glossary entry, case-insensitive. */
export function lookupTerm(term: string): GlossaryEntry | undefined {
  return GLOSSARY[term.toLowerCase()];
}
