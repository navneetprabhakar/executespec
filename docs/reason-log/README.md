# Reason Log

> **Why** we changed things — not **what** changed (that's `git log`).

A changelog answers "what shipped." This answers "why we made this call" — the
incident, the constraint, the tradeoff, the thing that will look arbitrary to
whoever reads the code in six months. It exists because our hot-path code
(`WorkerAgent`, `SequentialExecutor`, the scorers, the prompts) had accreted
multi-paragraph inline comments citing run IDs and dates — rationale buried in
code, impossible to skim, never pruned. The rationale belongs here; the code
keeps a one-line pointer.

## Convention

1. **One file per month**, `YYYY-MM.md`, newest entries at the top. Rotate on
   the 1st — start a fresh file; old files stay as the archive.
2. **Each entry has a stable id**: `RL-YYYY-MM-NNN` (e.g. `RL-2026-05-003`).
   Ids are never reused or renumbered, even across rotation.
3. **Code references the id, not the story.** Instead of a 12-line comment
   explaining an incident, write:
   ```java
   // Force-terminate on no-progress. Rationale: RL-2026-05-004.
   ```
   The reader who wants the why opens the reason log; the reader skimming the
   loop is not taxed.
4. **The entry references the code back** — `file:symbol` (and line if stable).
   The link is bidirectional so neither side silently drifts.
5. **Entry shape** (keep it tight):
   ```
   ### RL-YYYY-MM-NNN — <one-line title>
   - **Change:** what moved, in one line (link the commit if useful).
   - **Why:** the actual reason — incident / constraint / decision. This is the
     part that matters. Concrete: "run X did Y" beats "seemed flaky."
   - **Tradeoff:** what we gave up, if anything.
   - **Code:** `path/File.java#method` (+ line if stable).
   ```
6. **What earns an entry:** anything non-obvious — a guardrail, a magic number,
   a prompt directive added after a failure, a formula choice, a deliberate
   "we do NOT do X." Trivial renames/typos do not.
7. **Pruning:** when a change is reverted or superseded, mark the old entry
   `~~superseded by RL-…~~` rather than deleting it — the history of *why we
   changed our mind* is itself valuable.

## Index

- [2026-05.md](2026-05.md) — current
