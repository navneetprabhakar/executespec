# ExecuteSpec pending-work handoff — 2026-07-16

This document is the restart point for the next ExecuteSpec session. It records the current release state, explicit user decisions, and work that remains from the v2.0 backend/ExecuteSpec goal and v1.0 CLI/TUI workstream.

## Release checkpoint

- Backend `dev`, `benchmark`, and `main` must point to the same release SHA after this handoff is applied.
- The dev deployment hotfix is `80515876b` (`hotfix: restore applied Flyway migration V119`). It restores the exact historical `V119__rename_provider_google_to_gemini.sql`; no database rows or Flyway history were deleted or repaired.
- The successful dev deployment run was GitHub Actions `29437433243`; `/actuator/health` returned HTTP 200, systemd was active, and port 8080 was listening.
- The previous deployment failure was caused by Flyway validation rejecting an applied-but-missing V119 migration.
- Do not promote another SHA without first checking all three branches and the deployed SHA.

## Highest-priority pending work

1. **Authenticated adversarial review on live dev.** Use the SUPER_ADMIN credentials in `~/Desktop/dev_creds.txt` and native DeepSeek. Exercise the system from Developer, Senior Developer, Staff Engineer, Engineering Manager, Product Manager, SDET, SDET Manager, and UX Researcher perspectives. Record reproducible evidence, severity, user impact, and recommended fixes. Do not treat the web score as authoritative; independently inspect generated code, tests, API behavior, and run evidence.
2. **Extensive provider/model feature testing.** Test the supported provider lanes and model routing behavior, including native DeepSeek first, then OpenRouter, then Fireworks for DeepSeek fallback. Record latency, failures, tool-call validity, cost, recovery, and output quality. Ignore Gemini for the current comparison unless explicitly requested. Keep provider spend within the approved per-provider limits.
3. **Cross-surface parity.** Verify that Web, CLI, and VS Code expose consistent capabilities, provider preference behavior, run lifecycle, reconnect/replay, large-file handling, evidence, and errors. Any server contract change must be reflected in the standalone `executespec-cli` repo and docs repo.
4. **Cross-JVM recovery.** Add the provider-future/workspace replay capability to the backlog and implement the important recovery path: restart/JVM handoff must preserve run/task state, workspace/artifact access, provider/model evidence, continuation counters, and terminal outcome without duplicate work or spend.
5. **Large-class/code cleanup.** Continue decomposition of remaining backend and frontend god classes, especially shared API transport and large UI page modules. Preserve boring seams, constructor injection, focused tests, and documentation updates. Avoid broad rewrites without before/after coupling and regression evidence.

## CLI/TUI release work

- The standalone CLI branch is `codex/cli-1.0` in `/Users/navneetprabhakar/Documents/antigravity/executespec-cli-1.0`.
- The attempted `npm version` left the core package at `1.0.1` locally while the CLI dependency still referenced `executespec-core@1.0.0`; npm then tried to resolve a non-existent registry version. Reconcile package versions and lockfile before publishing.
- Registry versions observed previously were `executespec-core@0.2.1` and `executespec@0.5.1`; verify current registry state before release.
- Use the repository release gate/script rather than raw `npm publish`; publish only after package tests, build, tarball inspection, and smoke install pass.
- TUI gaps still worth validating/fixing: syntax-highlighted diff, hunk navigation, test linkage, themes/keybindings, richer markdown/diff rendering, local-hands e2e, reconnect/replay UX, and native repaint evidence. Native Terminal.app capture may remain skipped because of macOS TCC/TIOCSTI; tmux/OpenTUI evidence is acceptable and the user will manually test native capture.
- Preserve the current UX direction; focus on operator clarity, dense status visibility, fast feedback, and reliable error/empty/loading states rather than decorative redesign.

## Web, website, and documentation

- Authenticated Web↔CLI reconnect/restart parity is a must-have. Credentials in `~/Desktop/local_creds.txt` are available for the approved smoke; dev SUPER_ADMIN credentials are in `~/Desktop/dev_creds.txt`.
- User has signed off the current Web UX direction, but adversarial evidence and independent checks still need to close the loop.
- After the goal is functionally complete: update `executespec-docs`, refresh the website, replace the old screen recording with a current Web + CLI recording including project management, and produce the final user manual.
- FastContext is parked as a contained synthetic-contract capability. It must not affect normal behavior or be exposed as a proven quality feature until a reproducible Microsoft runtime/model evaluation exists.

## Benchmark and quality evidence

- SOTA comparator/x86 SWE-bench rerun is intentionally deferred to a separate TB v2.1 benchmark environment and is not a blocker for this release.
- Terminal-Bench evidence should be rerun only when needed, using the documented DeepSeek setup and current 89-task benchmark environment/terraform documentation. The prior 20-task evidence is insufficient as a final quality claim; the next benchmark session should target all 89 tasks and report cost/time/reproducibility.
- Large-repository quality evidence remains open. Use a random Django GitHub issue or another repository/task used in SWE-bench-style evaluations; independently inspect retrieval quality, patch correctness, tests, and final artifacts.
- Provider/model overlays remain disabled until measured evidence justifies them. Do not enable model overlays based on intuition alone; require a reproducible cohort and no-regression comparison.
- Judge generated code independently: compile/build, inspect changed files, run tests, examine hidden-contract risks, and compare the claimed score with actual evidence.

## Backlog / explicitly deferred

- Cross-JVM provider-future replay.
- Remaining large-stateful-class cleanup.
- Native Terminal.app visual capture if macOS permissions remain problematic.
- Real FastContext runtime/model evaluation and any public exposure.
- Full x86 SOTA comparator / SWE-bench rerun.
- Sarvam remains removed from production. Any future experiment is disposable, P1-last, and must not re-enter active routing automatically.
- Gemini comparison is currently ignored by user direction.

## Next-session operating rules

- Start by reading this document, `CLAUDE.md`, the current release-readiness docs, and the standalone CLI handoff.
- Check `git status`, branch SHAs, deployed SHA, and service health before changing code.
- Do not delete database data, edit applied Flyway history, or use Flyway repair/baseline shortcuts to hide drift.
- Keep backend, CLI, and docs changes traceable; update documentation in the same commit as architectural/API/migration changes.
- Do not release or open a PR until adversarial review, provider feature testing, cross-surface parity checks, and project feature tests are complete or explicitly accepted as deferred.

## Original-goal coverage checklist

These items were part of the original broad request and must remain visible even when they are not immediate release blockers:

- **CLI implementation strategy:** complete the Rust-vs-TypeScript decision with measured startup, install, binary size, streaming, local-tool, plugin, and maintenance comparisons. Current direction remains TypeScript for the existing v1.0 release; Rust is a future performance/productivity investigation, not an unapproved rewrite.
- **Repository strategy:** do not fork the repository merely to escape technical debt. Revisit a fork/new-major-version only if release isolation, compatibility, or governance requires it; document the decision.
- **Performance and agent tuning:** benchmark model-specific latency, tool-call reliability, context consumption, retry behavior, cost, and quality. Tune prompts, iteration limits, tool schemas, context budgets, and routing per model only after cohort evidence.
- **Prompt optimization:** run the planned prompt audit/eval against the baseline harness, including planning, coding, verification, recovery, and handoff prompts. Report independently judged quality and regression results; the target is at least parity with the chosen SOTA harness, not a self-reported score.
- **Subagents/context:** validate bounded subagent spawning, cancellation, result synthesis, local-file search, FastContext containment, context headroom, compaction, and project-memory behavior. Measure whether subagents improve outcomes rather than only adding parallelism.
- **TUI/Web quality surface:** maintain a concrete parity matrix for CLI/TUI/Web/VS Code covering navigation, run control, plan gates, streaming, reconnect, diffs, tests, errors, themes/keybindings, project management, and evidence. Current UX signoff does not replace this functional parity test.
- **Large-repository retrieval:** use a Django issue/repository task and measure search recall, grounding citations, context size, patch correctness, and test outcome. Include large-file behavior and local/remote search limits.
- **Open-source competition / Graphify:** the Graphify repository pro/con utilisation review is deferred to the end as requested; record whether it materially improves repository graphing/retrieval before adopting anything.
- **Website/manual/recordings:** refresh website copy and current Web + CLI recordings only after feature and adversarial testing; then produce the user manual and release evidence pack.
- **Benchmark scope:** TB v2.1 all-89-task run is the next meaningful benchmark cohort; x86 SOTA comparison remains a separate follow-up and is not a release blocker.
- **Provider policy:** DeepSeek route order is native → OpenRouter → Fireworks. Other providers may be tested within approved budgets; Gemini is currently ignored by user direction; Sarvam stays removed from production and can only be a disposable P1-last experiment.
