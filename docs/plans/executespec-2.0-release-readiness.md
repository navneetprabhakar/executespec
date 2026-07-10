# ExecuteSpec 2.0 release readiness

`PASS` requires current evidence on the integration branches. Historical green runs are context only.

| Gate | State | Evidence / blocker |
|---|---|---|
| One canonical execution/routing system | FAIL | Multiple chat paths and fragmented routing remain |
| Context planner active with headroom | PARTIAL | Worker now plans model-window headroom before request assembly and invokes safe compaction when reserves are unsafe; durable journal and broader evidence-bundle integration remain |
| Typed deterministic repository search | PARTIAL | `RepositorySearchService` now drives `searchFiles` with typed citations/fingerprints/paging; RepoOrientation and broader symbol/test indexing still need migration |
| Real Context Scout and verifier subagents | PARTIAL | Bounded persisted SCOUT/VERIFIER execution now runs read-only SpecChat rounds with tool allow-list, per-round turns/cost, evidence, and cancellation; parent coordinator/UI integration remains |
| Automatic verification default | PARTIAL | Evidence-safe `worker.verify-before-complete.enabled` is default ON via V239; edit-burst auto-verify and broader verification graduation remain OFF |
| Hot handoff and client reconnect | PARTIAL | Pending local tool calls now renew their lease on reconnect and malformed frames terminate immediately with durable `CLIENT_PROTOCOL_ERROR`; server-restart/session-resume proof remains |
| TUI route shell and Inspector removed | PARTIAL | Session route hides persistent sidebar/Inspector and now has an explicit 80x24 transcript budget; non-session route chrome and duplicate renderers remain |
| Exactly rich OpenTUI and plain renderer | FAIL | Frame coalescing is now bounded and tested, but route shell, Inspector, Ink, split-footer, and readline paths still ship |
| Web/CLI semantic state shared | PARTIAL | Shared semantic transcript adapters now exist in CLI core and Web; CLI session route and Web `/agent` route now hide dashboard chrome, but a single renderer/state path is not complete |
| SOTA comparator gate | BLOCKED | No frozen paired repeated comparator; provider caps limit statistical power |
| Benchmark manifests reproduce | FAIL | Current sample evidence lacks one complete identity manifest |
| Provider readiness honest | PARTIAL | Existing live probes are not a release-wide readiness registry |
| Sarvam absent from production | PASS (static) | Removed from provider enum/config/router/catalog/UI; V256 clears active persisted dispatch state. Historical benchmark/docs references remain explicitly marked historical. |
| Clean install and migration upgrade | PENDING | Docker/Postgres integration migration run still required; static schema/upgrade contract passes |
| Flyway backward compatibility | PARTIAL | V1–V256 remain immutable and V256 is forward-only; docs pin the 256-file chain and aggregate hash; clean-install/upgrade execution is still required |
| Backend verify | PASS | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q clean test` — 2221 tests, 0 failures/errors; focused context/subagent/routing suites pass |
| Frontend build/lint/format/tests | PASS | 151 tests, production build, format check, and lint pass; one existing hook-dependency warning |
| CLI/core/VS Code build/tests | PASS | 912/912 on `codex/cli-1.0` (core 115, CLI 785, VS Code 12) |
| Visual QA | FAIL | Existing TUI/Web visual gates still accept text-only/blank/stale defects; semantic adapters and frame coalescing are foundations, not visual acceptance |
| Docs links and integrity | PASS | Canonical entrypoints, release facts, duplicate-ID policy, cross-repo source paths, and archive-link policy pass `python3 tools/docs_integrity.py --strict` with 0 findings |
| No NUL bytes | PASS | Literal NULs removed from both Java sources; repository source-text test added |
| No orphan prompts | UNKNOWN | Verifier covers only a small critical subset |
| No unauthorized provider spend | PARTIAL | LlmRouter + deprecated FallbackLlmRouter require exact reservations in goal-live mode; direct provider/batch transports still need the same guard |
| Provider ledger below caps | PASS (local state only) | Canonical ledger is initialized at $0 spent/$0 reserved; this is not a release-wide spend claim until all transports join the guard |
