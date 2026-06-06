# Tiered + task-relevant codemap/architecture injection

> Status: **Phase 1 implemented** (flag `codeintel.tiered.enabled`, V230 in the current worktree, default OFF). Phases 2–3 designed, not built.
> Owner: platform. Created 2026-06-03 (RL-2026-06-003).

## Problem

Large repos generate large codemaps. matplotlib (4363 files) → a **58 KB** codemap with this shape:

| section | lines | content |
|---|---|---|
| header + `## Top-level layout` | 1–27 | repo name, dir tree |
| `## Files (listing)` | 28–1031 | flat list of ~1000 file paths |
| `## Functions` | **1032–1798** | **per-file symbols (`class Colormap`, `def to_rgba`, 661 signatures)** |

Injection is **head-truncated** to `CODEINTEL_PER_REPO_CAP = 6000` chars (`RunContextComposer.buildCodeIntelContext` → `truncate`). 6000 chars runs out around file ~200 of the flat listing, so **the `## Functions` symbol section is cut 100%**. We inject the *file paths* (noise) and drop the *symbols* (the localization signal).

Raising the cap is the wrong fix: 58 KB × ~30 worker turns (the V2 worker keeps full history) = cost explosion **and** attention dilution — the model loses `colors.py` among 4000 paths.

Empirically this matters: the dominant SWE-bench failure mode is `wrong_built_but_failed` (a patch that compiles but fixes the wrong place), and the project-mode-vs-blind eval injecting **paths-only** shows a near-flat lift — consistent with "a directory listing doesn't help localization."

## Reframe: tier **and** select

Two orthogonal axes:
1. **Tier** the artifact so each tier is independently injectable/retrievable.
2. **Select** by task-relevance — inject the slice that matters for *this* spec, not the alphabetical head.

Tiering without selection still faces "which slice fits the budget." Selection is the keystone, and the engine already exists: `project/rag/` (`CodeIndexingService` + `CodeChunkStore` over pgvector `project_code_chunk` + `RetrievalService`, V223) — currently wired only into project-chat `search_code`.

## Design

### Codemap — 3 tiers
- **T0 Skeleton** (always, ~1.5 KB, deterministic): repo name, file count, top-level package tree, `## Routes` + `## Models / entities` if present. Replaces the arbitrary head-200-paths.
- **T1 Symbol digests** (selected, ~3–4 KB): the `## Functions` section split per-file; inject only the symbol blocks for the **top-K files relevant to the spec**. Phase 1 = lexical relevance; Phase 2 = pgvector semantic.
- **T2 Full map** (on-demand): the complete symbol map, **not injected** — a `codemap_lookup(path)` worker tool. Unbounded depth, zero standing context cost.

### Architecture — 2 tiers
- **L0 Overview** (always, ~1.5 KB): the system-shape paragraph (today's arch head — formalize as a stored field).
- **L1 Subsystem deep-dives** (selected): inject the subsystem the task touches; per-repo arch in multi-repo is already a natural tier.

## Phasing

| phase | what | infra | risk |
|---|---|---|---|
| **1 (this PR)** | Deterministic tiered codemap injection: T0 skeleton + **lexical**-relevance-ranked symbol blocks; drop the flat file-listing. Flag `codeintel.tiered.enabled` (default OFF → byte-identical head-truncate). | none new | low — pure function, fail-safe to head-truncate |
| 2 | Swap the T1 selector lexical→**semantic** (reuse `project/rag` pgvector index; embed the spec, retrieve top-K). Same injection shape. | reuse V223 | medium |
| 3 | T2 `codemap_lookup` worker tool for unbounded drill-down; L1 arch subsystem selection. | new tool | medium |

## Phase 1 implementation (this PR)

- **`codeintel/TieredCodemapSelector.java`** — pure static `select(codemapMarkdown, specText, budget)`:
  - parses the codemap into the title block + `## ` sections; splits `## Functions` into per-file `### \`path\`` blocks;
  - scores each block by **lexical overlap** with the spec (path-token match weighted ×3 over symbol-text match);
  - assembles **T0** (title + Top-level layout + Routes + Models) **+ relevant Function blocks** (score > 0) within `budget`, dropping `## Files (listing)`;
  - **fail-safe**: returns `null` (→ caller head-truncates) only when the codemap has no parseable structure; returns **T0-only** when no block matches (still better than the head listing).
- **`RunContextComposer.buildCodeIntelContext(projectId, repos, specText, tiered)`** — when `tiered`, run the selector (fallback to head-truncate on null); else unchanged.
- Both call sites (`RunOrchestrator` BUILD + PATCH) pass the spec (`userRequest` / `userRequestLive`) + `featureFlagService.isEnabled("codeintel.tiered.enabled")`.
- **V230** seeds `codeintel.tiered.enabled = false`.

## Cost / quality

Phase 1 is fully deterministic (T0 + symbol extraction are free; no extra LLM). It **reduces** injected tokens (targeted, not head-6 KB) → lowers the per-turn re-bill **and** improves localization. It targets the `wrong_built_but_failed` residual directly (model sees the right file's symbols).

## Validation

A/B the same project-mode DeepSeek set with `codeintel.tiered.enabled` ON vs OFF; expect gains concentrated in large repos (matplotlib/django/sympy/sklearn) where head-truncation drops the symbol section entirely. The current paths-only run is the lower bound.
