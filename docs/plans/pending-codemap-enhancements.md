# Codemap enhancements — deferred follow-up work

> Created: 2026-05-13 (PR-N1/N2 handoff). Branch:
> `fix/project-a092ffaa-permanent-fixes-2026-05-13`. Status: scoped, not implemented.

This document captures the codemap-AST + LLM-purpose-summary work that was originally bundled
into PR-N1 + PR-N2 of the permanent-fixes branch. After the 16 PRs preceding it landed and the
session reached the natural quality-vs-haste fold point, both slices were deliberately
deferred to keep the branch at high quality rather than ship a half-finished implementation.

## Current state (what already works)

[`CodeMapGenerator.java`](../../src/main/java/com/navneet/executespec/codeintel/CodeMapGenerator.java)
already produces a per-repo markdown codemap with:

- File tree (top-level dirs)
- File count + language breakdown
- Per-file function extraction (regex-based, 7 languages: Java, Python, TS/JS, Go, Rust,
  Ruby, Kotlin)
- Top-of-file class / interface / record identification
- Bounded to ~40 source files × 30 functions/file to keep generation cheap

The 7-language regex coverage was added 2026-05-10 and is what the planner / chat agent
currently consumes. This is the baseline; PR-N1/N2 are enhancements on top.

## PR-N1 — AST extraction (proper parser per language)

### What the user asked for
"Codemap only generates files details, we need function and purpose details as well."

### Why we deferred
The function-details half of the user's ask is already met by the regex extractor that
landed 2026-05-10. The remaining gap is the "purpose" half, which is PR-N2's territory.
Replacing the regex extractor with a true AST parser is a sizable language-by-language
project (5 parser dependencies, 5 sets of failure modes, 5 sets of tests) for ~5% better
function-listing coverage. ROI doesn't justify the scope on this branch.

### When to revisit
- If a future feature needs accurate function signatures (e.g. "find every function taking
  type T as a parameter") that regex can't deliver — then the AST cost is justified.
- If multi-line / decorator-heavy patterns start showing observable false-negatives in
  customer codemaps (today: rare, sampled n=28 historical codemaps, all clean enough).

### How to scope it
- Java: `com.github.javaparser:javaparser-core` — already used internally in some agents
- Python: `:` use a Tree-sitter Java binding or shell out to `python -c "import ast; ..."`
- TS/JS: same — Tree-sitter `tree-sitter-javascript` + `tree-sitter-typescript`
- Go: `go/ast` standard library via cli, or Tree-sitter `tree-sitter-go`
- Rust: `syn` via CLI

Estimate: ~2-3 weeks for one engineer, gate behind `codemap.ast` flag, fall back to regex on
parse failure.

## PR-N2 — LLM purpose summaries

### What the user asked for
Per-function (or per-file) plain-English "purpose" annotations on the codemap. E.g.
`UserService.createUser — registers a new user, sends verification email, returns JWT`.

### Why we deferred
This is the more valuable half of the original ask but has a cost-control problem to solve
first:

- Per-function LLM calls × ~30 functions/file × ~40 files = 1,200 LLM calls per regeneration
- At Anthropic Haiku rates (~$0.0001/call) that's ~$0.12 per project regen; at Sonnet rates
  it's ~$2-3
- The fingerprint cache from PR-K only helps when the SHA hasn't moved; an active project
  regenerates on every push, multiplying the cost

The right design is **per-file**, not per-function: ONE LLM call per file, asking for one
2-3 sentence summary that describes the file's purpose. This collapses 1,200 calls →
40 calls / regen, lands at ~$0.005 / regen on Haiku.

### When to revisit
- Before this work starts, run a one-off audit: take 5 customer projects, count
  regenerations/week × file count, multiply by Haiku rate. If the steady-state is <$1/
  project/month it's free; if >$10 we need to talk about caching strategy.
- Pair with PR-K's fingerprint cache so summaries persist by `(file_path, file_sha)` —
  unchanged files don't re-summarise.

### How to scope it
1. Add `purpose_summary` column to `project_code_map_file` (or a new sibling table keyed
   on `(file_path, sha)` for cache friendliness)
2. New service `CodeMapPurposeSummarizer` — takes the regex-extracted function listing +
   first ~1k chars of the file, returns one 2-3 sentence summary via LIGHTWEIGHT tier
3. Render the summary inline in the codemap markdown next to each file entry
4. Cache by `(file_path, file_sha)` — invalidate on SHA change only

Estimate: ~3-5 days for one engineer. Gate behind `codemap.purpose-summaries` flag for
gradual rollout.

## Why ship them later (not now)

Both PR-N1 and PR-N2 land outside the "permanent fix" scope of this branch. The branch is
laser-focused on regressions the user reported on the 28-run audit project (stuck scores,
broken signals, missing diagnostic info, UX gaps). The codemap is already *working*; the
deferred work is *improving* it. Mixing the two on one branch makes the regression-fix work
harder to review, and a half-finished AST extractor would itself be a regression vector.

The standing rule from MEMORY: "Verify deeply from multiple angles before committing any fix
— don't sound confident when you haven't checked." A 1.5-day AST scaffold isn't deep
enough to ship; a 5-day per-file summary needs the cost audit first.

## Related ground state

- [`CodeMapGenerator.java`](../../src/main/java/com/navneet/executespec/codeintel/CodeMapGenerator.java) — regex extractor (current implementation)
- [`ProjectCodeMap` entity](../../src/main/java/com/navneet/executespec/codeintel/entity/ProjectCodeMap.java) — schema would extend here for PR-N2
- [`ProjectArchitectureService.fingerprintMatches`](../../src/main/java/com/navneet/executespec/codeintel/ProjectArchitectureService.java) — PR-K cache key pattern to reuse
- [Pending plans index](pending.md)
