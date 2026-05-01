/**
 * R-106 — glossary content for the <Term> primitive.
 *
 * Two-sentence definitions for every product term that's
 * non-obvious to a first-time user. Strict review §13 said
 * "every undefined term gets a ? icon"; this is the data source.
 *
 * Each entry is keyed lowercased. `<Term name="run">Run</Term>`
 * looks up `glossary.run`.
 */

export interface GlossaryEntry {
  /** Title shown at the top of the tooltip / glossary modal row. */
  term: string;
  /** Two-sentence definition. First sentence answers "what is it?",
   *  second sentence answers "why do I care?". */
  definition: string;
  /** Optional URL for "Learn more →" in the help modal. */
  href?: string;
}

export const GLOSSARY: Record<string, GlossaryEntry> = {
  run: {
    term: 'Run',
    definition:
      'A single submit-to-result cycle. ExecuteSpec reads your spec, plans the change, writes the code, runs the build and tests, and hands you a draft pull request you can merge.',
  },

  project: {
    term: 'Project',
    definition:
      'A collection of related runs, repos, and conversation history. One project per app or product area; each project keeps its own code map, memory, and team access.',
  },

  plan: {
    term: 'Plan',
    definition:
      'What ExecuteSpec proposes to change before it writes any code — files it will touch, cost estimate, and rough time. You approve the plan in the conversation; nothing ships until you do.',
  },

  verdict: {
    term: 'Verdict',
    definition:
      'The honest read on a finished run: Ready / Needs review / Failed. Backed by four signals — Build · Tests · Reviewer · Cost — each shown as its own pill so you can judge them individually.',
  },

  reviewer: {
    term: 'Reviewer',
    definition:
      'A second AI pass that reads the diff after the build passes, looking for missed wiring, broken imports, and obvious bugs. Reviewer outcomes: Clean (no issues), Fixed (issues found and fixed), Unresolved (issues found, fix attempted but not confirmed).',
  },

  'code map': {
    term: 'Code map',
    definition:
      "ExecuteSpec's understanding of your repo — file tree, key entry points, framework, conventions. Refreshed when you sync; the planner reads it to make better proposals.",
  },

  byok: {
    term: 'BYOK',
    definition:
      'Bring Your Own Keys. Use your own Anthropic / OpenAI / Gemini API quota instead of ExecuteSpec-managed credits. Saves money at scale; available on Pro and above.',
  },

  preview: {
    term: 'Preview',
    definition:
      'A short-lived deployment of your run output to a real URL so you can click through what was built. Previews live for 5 minutes by default; deploy to make it permanent.',
  },

  pulse: {
    term: 'Pulse',
    definition:
      "The one-line status row at the top of every project — last run, build state, repo count, recent PRs. The 'how is the project doing right now' read.",
  },

  'plan strategy': {
    term: 'Plan strategy',
    definition:
      'How ExecuteSpec decomposes your spec — single shot for tiny changes, hierarchical for large or cross-repo work. Picked automatically by the classifier; affects cost and latency.',
  },

  classifier: {
    term: 'Classifier',
    definition:
      "A fast first pass that decides how big your spec is and which planning strategy to use. Costs a fraction of a cent; saves you money on the planner you don't need.",
  },

  shippable: {
    term: 'Shippable',
    definition:
      "(Retired in v3.) The v2 chip that auto-claimed a run was 'shippable' regardless of evidence. Replaced by the four-pill verdict so you judge for yourself.",
  },

  awaiting_approval: {
    term: 'Plan ready',
    definition:
      'The state where ExecuteSpec has finished planning and is waiting for you to approve before writing code. In v3, this appears as an inline plan card in the conversation, not a blocking modal.',
  },
};

/** Resolve a name (case-insensitive) to a glossary entry, or null. */
export function lookupGlossary(name: string): GlossaryEntry | null {
  const key = name.trim().toLowerCase();
  return GLOSSARY[key] ?? null;
}
