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
  // R-191 (post-cutover) — the v1-era "mission" copy-rename of "run" was
  // reverted. v3 vocabulary is "run" everywhere (URL, DTO, copy). This
  // glossary entry is removed to keep lookupTerm("mission") returning
  // undefined so any leftover stale link reads as "no glossary entry"
  // rather than "see the historical mission rename".
  run: {
    display: 'run',
    definition:
      'The unit of work ExecuteSpec ships against your codebase. One run = plan → execute → review → preview → PR.',
  },
  // X-12 (UX audit, 2026-05-03) — second batch of definitions for
  // jargon that surfaces in StatusBadge tooltips, ScoreCard rows, and
  // RunDetailTabs. Covers the operational vocabulary an enterprise
  // user encounters within their first 2-3 runs.
  'smoke test': {
    display: 'smoke test',
    definition:
      "A quick build/compile check after each task to catch broken code early. Doesn't run your full test suite — just confirms the change didn't syntax-error or break the project structure.",
  },
  'plan strategy': {
    display: 'plan strategy',
    definition:
      'How the planner staged your run: SINGLE_SHOT (1 task, no LLM call), SMALL (≤4 tasks, full plan), MEDIUM (≤15 tasks), LARGE (parallel skeleton + expansion), LEGACY (recursive depth-2 expansion for huge specs).',
  },
  'reviewer verdict': {
    display: 'reviewer verdict',
    definition:
      "The post-build review pass's opinion: CLEAN (nothing to fix), FIXED_ISSUES (auto-fixed warnings), UNRESOLVED_ISSUES (warnings escaped), REVIEW_FAILED (reviewer hit an error).",
  },
  'post-merge cascade': {
    display: 'post-merge cascade',
    definition:
      'When a PR opened by a run gets merged on GitHub, ExecuteSpec auto-regenerates the codemap + architecture for the affected repo so the next run starts from current state — not the pre-merge snapshot.',
  },
  'stale codemap': {
    display: 'stale codemap',
    definition:
      'The repo on GitHub has commits past the SHA the codemap was generated against. The planner is reading slightly out-of-date structure. Click "Analyse" to refresh.',
  },
  'final build': {
    display: 'final build',
    definition:
      "The build/compile check run AFTER the last task plus the reviewer's wiring pass complete. The authoritative gate that flips a run from PARTIAL → COMPLETE.",
  },
  'plan approval gate': {
    display: 'plan approval gate',
    definition:
      'The pause between planning and execution where you review the task list, see the credit cost, and approve or reject. Skipped in Quick mode (no review) — used in Reviewed mode.',
  },
  'verdict pill': {
    display: 'verdict pill',
    definition:
      'The Shippable / Needs review / Blocked badge on a finished run. Derived from final build + tests + reviewer verdict + verified score together — one signal you can trust at a glance.',
  },
};

/** Look up a glossary entry, case-insensitive. */
export function lookupTerm(term: string): GlossaryEntry | undefined {
  return GLOSSARY[term.toLowerCase()];
}
