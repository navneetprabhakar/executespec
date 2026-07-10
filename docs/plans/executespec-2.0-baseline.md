# ExecuteSpec 2.0 baseline

Captured from backend base `9861f68f0` plus Milestone-0 controls, CLI `5ee52d51`, and docs `ba972e5`. The exact current worktree fingerprints and full prompt inventory are generated in [`evidence/executespec-2.0-inventory.json`](evidence/executespec-2.0-inventory.json). A value is `UNKNOWN` when the current artifacts do not carry enough identity or telemetry to reproduce it. Unknown is a baseline defect, not zero.

## Deterministic inventory

| Measure | Current value |
|---|---:|
| Backend Java files / LOC | 1,234 / 191,939 |
| REST controller classes / mapping annotations | 108 / 597 |
| Java test source files | 368 |
| Prompt resources | 91 (56 Markdown, 34 text resources, 1 JSON) |
| Flyway migrations / actual head | 258 / V258 |
| Frontend TS/TSX files / LOC / test files | 143 / 41,713 / 31 |
| CLI TS/TSX LOC / test files | 54,809 / 115 |
| CLI interaction implementations | 4 plus a duplicate preview/frame contract |
| CLI command groups / slash entries / palette-only actions | 26 / 51 / 20 |
| Docs tracked files / files under `output/` | 601 / 418 |
| Docs integrity findings before cleanup | 383 (325 broken links, 39 active-to-noncanonical, 18 duplicate reason IDs, 1 stale pointer) |

## Quality and benchmark evidence

| Metric | Baseline | Reproducibility |
|---|---|---|
| SWE-bench Pro sample-20 resolution, native DeepSeek Pro | 12/20 (60%), k=1, about $0.92 | Exploratory; sample seed 42 and official Scale scorer recorded, but complete prompt/tool/harness identities are not yet in one manifest |
| SWE-bench Pro sample-20 resolution, native DeepSeek Flash | 10/20 (50%), k=1, about $0.36 | Same limitation |
| Empty patch rate for that comparison | 0 reported | Per-task manifest required |
| Broad SOTA gap/parity | UNKNOWN | No frozen comparable reference harness and repeated paired runs |
| Patch-file recall | UNKNOWN | Not emitted consistently |
| Build pass / relevant-test pass / reviewer-clean | UNKNOWN | Historical result stores need extraction and identity join |
| False completion / repeated failure | UNKNOWN | Some signals exist, no versioned baseline query |
| Main-agent and total tokens | UNKNOWN | Current run usage is available, baseline cohort is not fixed |
| Cost per resolved task | UNKNOWN | Requires outcome-to-call join |
| Time to useful action/edit/verification/final proof | UNKNOWN | Semantic timestamps not consistently captured |
| Context overflow / repeated search / calls per resolved task | UNKNOWN | Context/search telemetry missing |
| TUI workflow completion | UNKNOWN | Existing QA largely checks text presence and non-empty PNG bytes |

The older oracle-mode 67%/60% SWE-bench numbers are invalid and excluded. The sample-20 result cannot be used as a broad model ranking or SOTA parity claim.

## Current deterministic validation

| Surface | Command | Result |
|---|---|---|
| CLI/core/VS Code | `npm test` on CLI base SHA | PASS: core 114, CLI 778, VS Code 12; 904 total |
| Provider budget control | `node --test scripts/provider-budget-ledger.test.mjs` | PASS: 9/9; canonical cross-worktree ledger, fail-closed missing state, exact reservation scope, dispatched-state accounting, concurrency, breach lock and upward micro-USD rounding |
| Benchmark manifest control | `node --test scripts/validate-benchmark-manifests.test.mjs` | PASS: 2/2; retrospective manifests remain explicitly non-reproducible |
| Cross-repo inventory | `node scripts/generate-2.0-inventory.mjs --check` | PASS; six production providers, 258 migrations/V258, 38 model rows and current code flag literals generated from current worktree fingerprints |
| Backend focused router guard | `mvn -q -Dtest=LlmRouterTest test` | PASS |
| Backend compile and formatting | `mvn -q -DskipTests compile spotless:check` | PASS |
| Frontend unit tests | `npm --prefix frontend test` | PASS: 31 files, 151 tests |
| Frontend production build | `npm --prefix frontend run build` | PASS: 2,054 modules; entry chunk 408.57 kB raw / 112.65 kB gzip |
| Frontend format/lint | `format:check`, `lint` | PASS formatting; lint has 0 errors and 1 existing hook-dependency warning |
| Backend full unit suite | `mvn -q test` | PASS: 2,371 tests, 0 failures/errors, 4 skipped |
| Docs integrity foundation | `python3 -m unittest tests/test_docs_integrity.py` in docs worktree | PASS: 5 tests; report inventories 383 findings (325 broken links, 39 active-to-noncanonical, 18 duplicate reason IDs, 1 stale pointer) |
| Source text NUL gate | `mvn -q -Dtest=SourceTextIntegrityTest test` | PASS after replacing two literal NUL bytes in Java sources with escaped delimiters |
| TUI visual workflow | New outcome-based matrix | PENDING |

Fresh `npm ci` reported 24 frontend dependency advisories: 1 low, 18 moderate, 4 high and 1 critical. The critical is the Vitest UI server arbitrary-file read/execute advisory affecting the pinned Vitest `<3.2.6`; npm proposes a semver-major Vitest upgrade. This is a release gate even though the vulnerable UI server is a development/test surface.

## Baseline repair work

Milestone 0 remains open until generated manifests can reproduce inventories and benchmark cohorts. Required additions:

1. Extend the machine-readable inventory from prompts/routes/providers/flags/migrations/adapters to exact active tool and chat-path caller manifests.
2. Benchmark manifest schema containing repository, model slug, prompt, tool-schema, harness, dataset slice, feature flags and all budgets.
3. Historical outcome extractor that joins task result, verification, timing, usage and provider/model identity without treating missing evidence as success.
4. Semantic timing/search/context events for metrics that do not exist today.
5. New TUI workflow and responsive visual baseline after the transcript vertical slice; the current route shell is evidence of the problem, not the target baseline.
