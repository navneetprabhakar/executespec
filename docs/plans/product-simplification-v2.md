# ExecuteSpec product simplification plan

Status: active release workstream

## Product decision

ExecuteSpec has accumulated more platform machinery than its primary coding workflow can currently justify. The next release work must optimize the user-visible loop before adding new surfaces:

> submit task → understand plan → watch useful progress → inspect changes → verify result → recover when stuck

Internal sophistication is not treated as product value until it improves one of those six outcomes.

## What remains core

- One durable coding-run workflow shared by Web, CLI, and TUI.
- Provider/model selection, bounded failover, cost controls, and honest evidence.
- Tenant isolation, authentication, approval gates, sandboxing, and real test/build verification.
- Multi-repository execution only where the workflow has a tested customer path.

## What must be isolated

- Benchmark adapters and comparator tooling.
- Enterprise standards, contracts, ADRs, and governance surfaces.
- Billing, managed-credit, and payment administration.
- Legacy protocol/read compatibility and migration-only fields.
- Project architecture generation, RAG, memory, and auxiliary intelligence surfaces.
- Operator-only diagnostics and benchmark controls.

Isolation means separate packages/modules and explicit contracts; it does not mean deleting required behavior before replacement evidence exists.

## Engineering rules for the remainder of v2.0 / CLI-TUI 1.0

1. No new customer-facing surface unless it improves the six-step core loop or closes a release gate.
2. Every compatibility path must have an owner, test, removal condition, and documented reason to remain.
3. Web, CLI, and TUI should consume the same run/session/evidence contracts; surface-specific parsing and duplicated transport are defects.
4. Move pure policy and DTO groups out of god files before attempting broad orchestration rewrites.
5. Keep platform machinery out of the hot path where possible; lazy-load or operator-gate it.
6. Measure user-visible latency: submit-to-first-progress, input-to-first-paint, tool-result-to-render, recovery time, and large-repository retrieval—not proxy timings alone.
7. Treat score, benchmark, and reviewer output as evidence with provenance, not as product quality by themselves.

## Ordered cleanup backlog

### P0 — core-loop coherence

- Finish the Web/CLI/TUI shared session and run contract audit.
- Remove duplicated transport and parser paths where compatibility tests permit.
- Make plan, progress, changes, tests, verification, cost, and recovery visible without raw logs.
- Establish one canonical failure/recovery model.

### P1 — structural simplification

- Extract remaining pure policy/DTO groups from `RunOrchestrator` and `frontend/src/lib/api/core.ts`.
- Keep compatibility wrappers thin and move them to explicit compatibility modules.
- Separate operator/benchmark/enterprise packages from the customer run path.
- Add characterization tests before each orchestration extraction.

### P1 — evidence and performance

- Capture input-to-first-paint and submit-to-first-progress on native TUI and Web.
- Benchmark representative large repositories, not only synthetic fixtures.
- Compare provider/model quality, latency, cost, and recovery on a frozen corpus.
- Revalidate every screenshot, recording, manual, and score against exact candidate SHAs.

### P2 — deferred expansion

- FastContext integration.
- Independent SOTA comparator.
- Sarvam experimentation.
- Additional enterprise/admin surfaces.

These remain explicitly non-blocking until the core loop is coherent and measured.

## Release decision rule

The system is not ready merely because the architecture is feature-complete or tests are green. Release requires a coherent core workflow, truthful evidence, cross-surface parity, and a documented reason for every retained compatibility or platform layer.
