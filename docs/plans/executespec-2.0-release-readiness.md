# ExecuteSpec 2.0 release readiness

`PASS` requires current evidence on the integration branches. Historical green runs are context only.

| Gate | State | Evidence / blocker |
|---|---|---|
| One canonical execution/routing system | PARTIAL | `LlmRouter.callText` is now the canonical text transport seam; title summarization and project-brief generation migrated off `FallbackLlmRouter`. 12 legacy non-tool callers and compatibility chat paths still remain |
| Context planner active with headroom | PARTIAL | Worker now plans model-window headroom before request assembly, invokes safe compaction when reserves are unsafe, and the production prompt now mandates repository-localization/search-first behavior with explicit headroom and evidence reuse; durable journal and broader evidence-bundle integration remain. FastContext-compatible local-index seam is documented, but no external index dependency is required |
| Typed deterministic repository search | PARTIAL | `RepositorySearchService` drives `searchFiles` with typed citations/fingerprints/paging and a bounded metadata-aware line cache; RepoOrientation and broader symbol/test indexing still need migration |
| Real Context Scout and verifier subagents | PARTIAL | Bounded persisted SCOUT/VERIFIER execution now runs read-only SpecChat rounds with tool allow-list, per-round turns/cost, evidence, and cancellation; parent coordinator/UI integration remains |
| Automatic verification default | PARTIAL | Evidence-safe `worker.verify-before-complete.enabled` is default ON via V239; edit-burst auto-verify and broader verification graduation remain OFF |
| Hot handoff and client reconnect | PARTIAL | Pending local tool calls now renew their lease on reconnect and malformed frames terminate immediately with durable `CLIENT_PROTOCOL_ERROR`; server-restart/session-resume proof remains |
| TUI route shell and Inspector removed | PARTIAL | Session route hides persistent sidebar/Inspector and now has an explicit 80x24 transcript budget; native OpenTUI also exposes active provider/model context; non-session route chrome and duplicate renderers remain |
| Exactly rich OpenTUI and plain renderer | PARTIAL | Obsolete Ink implementation/dependencies/references are deleted; native OpenTUI and plain/readline remain, while split-footer compatibility is intentionally retained for runtimes without Node 26+ FFI |
| Web/CLI semantic state shared | PARTIAL | Shared semantic transcript adapters now exist in CLI core and Web; both session routes hide dashboard chrome and the Web agent run card consumes a reconnecting typed SSE stream with explicit connected/reconnecting/snapshot-only health, but a single renderer/state path is not complete |
| SOTA comparator gate | BLOCKED | No frozen paired repeated comparator; provider caps limit statistical power. Tier-eval result rows now carry a deterministic prompt fingerprint, but no live quality claim is made |
| Benchmark manifests reproduce | PARTIAL | A hash-sealed offline contract fixture is reproducible; historical SWE-bench manifests remain exploratory and do not support model/quality claims |
| Provider readiness honest | PARTIAL | Existing live probes are not a release-wide readiness registry |
| Sarvam absent from production | PASS (static) | Removed from provider enum/config/router/catalog/UI; V256 clears active persisted dispatch state. Historical benchmark/docs references remain explicitly marked historical. |
| Clean install and migration upgrade | PASS | pgvector PostgreSQL integration runs all 256 migrations, validates Hibernate startup, and the V255→V256 fixture clears active Sarvam state while preserving historical evidence |
| Flyway backward compatibility | PASS | V1–V256 remain immutable and V256 is forward-only; hash-pinned chain plus clean-install and V255→V256 upgrade/data-preservation tests pass |
| Backend verify | PASS | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q clean test` — 2418 tests, 0 failures/errors; focused context/subagent/routing/provider-guard/prompt suites pass |
| Frontend build/lint/format/tests | PASS | 155 tests, production build, format check, and lint pass; one existing hook-dependency warning |
| CLI/core/VS Code build/tests | PASS (worktree-scoped) | With `EXECUTESPEC_DOCS_ROOT=/Users/navneetprabhakar/Documents/antigravity/executespec-docs-2.0`, the full CLI worktree gate passes: core 115 + CLI 734 + VS Code 12 = 861 tests; the offline TS runtime comparator passes all scenarios and reports local-only timing/RSS |
| Visual QA | FAIL | Web now exposes live connection state and keyboard-contained modal overlays to screen readers and sighted operators, but authenticated protected-flow screenshots and full TUI visual acceptance are still missing |
| Docs links and integrity | PASS | Canonical entrypoints, release facts, duplicate-ID policy, cross-repo source paths, and archive-link policy pass `python3 tools/docs_integrity.py --strict` with 0 findings |
| No NUL bytes | PASS | Literal NULs removed from both Java sources; repository source-text test added |
| No orphan prompts | UNKNOWN | Verifier covers only a small critical subset |
| No unauthorized provider spend | PASS (transport guard) | LlmRouter, deprecated FallbackLlmRouter, and direct provider/batch transports now fail closed through the shared authorization guard; no live provider calls were made in this wave |
| Provider ledger below caps | PASS (local state only) | Canonical ledger is initialized at $0 spent/$0 reserved; this is not a release-wide spend claim until all transports join the guard |
