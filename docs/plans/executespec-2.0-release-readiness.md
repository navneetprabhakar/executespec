# ExecuteSpec 2.0 release readiness

`PASS` requires current evidence on the integration branches. Historical green runs are context only.

| Gate | State | Evidence / blocker |
|---|---|---|
| One canonical execution/routing system | FAIL | Multiple chat paths and fragmented routing remain |
| Context planner active with headroom | PARTIAL | Added provider-window `ContextPlanner` contract and tests; worker integration, durable journal, and compaction graduation remain |
| Typed deterministic repository search | PARTIAL | `RepositorySearchService` now drives `searchFiles` with typed citations/fingerprints/paging; RepoOrientation and broader symbol/test indexing still need migration |
| Real Context Scout and verifier subagents | PARTIAL | Bounded persisted SCOUT/VERIFIER child-session protocol now exists with depth, child, turn, cost, cancellation, ownership, and evidence guards; model execution/coordinator wiring remains |
| Automatic verification default | FAIL | Critical V255 gates are OFF |
| Hot handoff and client reconnect | FAIL | Pending tool calls can time out on malformed/disconnected clients |
| TUI route shell and Inspector removed | FAIL | Current default is route-first with fixed rails |
| Exactly rich OpenTUI and plain renderer | FAIL | Four interaction paths ship |
| Web/CLI semantic state shared | FAIL | Clients consume transport-specific state |
| SOTA comparator gate | BLOCKED | No frozen paired repeated comparator; provider caps limit statistical power |
| Benchmark manifests reproduce | FAIL | Current sample evidence lacks one complete identity manifest |
| Provider readiness honest | PARTIAL | Existing live probes are not a release-wide readiness registry |
| Sarvam absent from production | PASS (static) | Removed from provider enum/config/router/catalog/UI; V256 clears active persisted dispatch state. Historical benchmark/docs references remain explicitly marked historical. |
| Clean install and migration upgrade | PENDING | Docker/Postgres integration migration run still required; static schema/upgrade contract passes |
| Flyway backward compatibility | PARTIAL | V1–V256 remain immutable and V256 is forward-only; clean-install baseline is intentionally deferred until schema freeze |
| Backend verify | PASS | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q test` — 2203 tests, 0 failures/errors |
| Frontend build/lint/format/tests | PENDING | Not run on integration branch |
| CLI/core/VS Code build/tests | PASS | 906/906 on `codex/cli-1.0` (core 114, CLI 780, VS Code 12) |
| Visual QA | FAIL | Existing gate can pass text-only/blank visual defects |
| Docs links and integrity | FAIL | Broken links and duplicate reason-log IDs |
| No NUL bytes | PASS | Literal NULs removed from both Java sources; repository source-text test added |
| No orphan prompts | UNKNOWN | Verifier covers only a small critical subset |
| No unauthorized provider spend | PARTIAL | LlmRouter + deprecated FallbackLlmRouter require exact reservations in goal-live mode; direct provider/batch transports still need the same guard |
| Provider ledger below caps | PASS (local state only) | Canonical ledger is initialized at $0 spent/$0 reserved; this is not a release-wide spend claim until all transports join the guard |
