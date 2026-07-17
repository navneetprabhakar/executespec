# ExecuteSpec Enterprise-Hardening Current-State Audit

**Captured:** 2026-07-17 IST
**Scope:** backend/Web, standalone CLI/OpenTUI/VS Code, canonical documentation, current dev runtime and published npm packages
**Purpose:** reproducible starting evidence for the enterprise-hardening and benchmark goal; this is a point-in-time audit, not release approval
**Release state:** **NOT READY**
**Paid benchmark gate:** **CLOSED**

This document records facts observed directly from Git, GitHub, local source/tests and authenticated dev APIs. It deliberately separates current evidence from historical claims. Secrets, tokens and credential values are excluded.

## 1. Exact source tuple

| Surface | Repository/branch | Local HEAD at capture | Remote branch HEAD | Divergence from base | Worktree at capture |
| --- | --- | --- | --- | --- | --- |
| Backend + Web | `executespec` / `codex/brownfield-rca-remediation-20260717` | `4c9442bd8634305686b372efc746427b73cb506f` | `31682354e4d2d0f2965810c18c9ae433ac9aa9bc` | 10 commits ahead of `origin/dev` | Dirty: active goal controls, credential hardening and review-admission work |
| CLI + OpenTUI + VS Code | `executespec-cli` / same branch | `46970f3815cbd22788acbde83a1a2880cc7e91ea` | same | 4 commits ahead of `origin/main` | Dirty: fail-closed local-project binding plus tests/docs/baseline digest |
| Documentation | `executespec-docs` / same branch | `c906287295188158514be495339f8449382e3d9a` | same | 13 commits ahead of `origin/main` | Dirty: local duplicate reason-ID correction/navigation work |

The backend local HEAD is one unpushed commit ahead of its remote feature branch. The dirty files are intentional active-goal work and must not be treated as a synchronized release tuple.

### Branch-only backend commits (`origin/dev..HEAD`)

1. `4c9442bd8` — credential migration no-op/column-width repair.
2. `31682354e` — BYOK dispatch guard.
3. `8536bd6f8` — model-aware pre-submit Launch estimate.
4. `8ff4bdab9` — model-aware run cost estimate.
5. `764aac821` — transactional goal/continuation persistence documentation.
6. `b95d42db9` — transactional goal/continuation fast-fail persistence.
7. `1110cc772` — local-source binding, provider-quality allowlist and credential-security gate.
8. `c15675ce2` — brownfield evidence provenance.
9. `b8f52b27a` — guarded live-provider profile propagation.
10. `ca8f86926` — brownfield evidence/routing hardening.

The master tracker retains AUD-02 as open until every patch above and every CLI/docs branch-only patch has a recorded semantic classification and regression disposition.

### Initial per-commit patch classification

This is the first semantic pass over the actual branch patches (not their messages). `FAIL` means a concrete defect or invalid closure claim was found; `MIXED` means useful code/evidence and unresolved risk coexist; `PENDING` means no defect was proven yet but the patch still needs its completion gate. It is intentionally not the final AUD-02 sign-off.

| Repository/commit | Classification | Patch-level finding/disposition |
| --- | --- | --- |
| B `ca8f86926` | MIXED | Large 132-file/5,991-line cross-layer patch introduced useful source readiness, route evidence and UI surfaces, but also cemented synthetic feature IDs, pipeline-only route recovery and an over-broad atomic change. Current dev does not expose two of its capability/readiness endpoints. Requires decomposition-by-contract and current-tuple E2E evidence. |
| B `b8f52b27a` | PENDING | Bounded live-LLM Maven-profile propagation with a contract test. No provider call was made in this audit; budget-reservation and subprocess behavior still need guarded runtime proof. |
| B `c15675ce2` | MIXED | Strengthened product-diff/test provenance, but source-level evidence cannot inherit the later “all live closed” claim. Keep changes; replay validation on the final tuple. |
| B `1110cc772` | FAIL | Valuable local-source/credential/provider work, but CLI explicit project binding remained fail-open, credential migration silently swallowed required-mode failures, PipelineRun SCM values were outside the converter, remaining columns could overflow, and project write semantics remained too broad. Active remediation addresses these items. |
| B `b95d42db9` | PENDING | Focused transactional persistence repair with unit evidence. Needs cross-JVM/retry integration proof as part of SEC-10 rather than isolated acceptance. |
| B `764aac821` | PENDING | Documentation-only follow-up to `b95d42db9`; no independent runtime claim. |
| B `8ff4bdab9` | MIXED | Model-aware post-submit estimate is directionally correct and tested; canonical attempt-level usage/reconciliation remains absent, so estimate accuracy is not closed. |
| B `8536bd6f8` | MIXED | Model-aware pre-submit estimate and detailed audit doc are useful; the handoff correctly lists gaps, but later routing audit shows the project/provider boundary is broader than this patch fixes. |
| B `31682354e` | FAIL | Added a real OWN_KEYS dispatch check for attributable pipeline runs, but unknown/synthetic IDs explicitly resolve false/platform-capable and are cached; the comments call that fail-safe although it is fail-open for enterprise spend. Must be superseded by universal durable context. |
| B `4c9442bd8` | FAIL | Corrected one JdbcTemplate overload and added V279, but did not fail required-mode startup, did not cover PipelineRun snapshots, did not widen the last Bitbucket columns, and assumed every table key was `id`. Active V280/runner/converter/Testcontainers remediation supersedes it without rewriting V279. |
| C `da5d6480` | MIXED | Adds provider-readiness/run-route evidence and TUI tests; server capability deployment is older (404), so client/server compatibility is not proven. |
| C `d1073e60` | PENDING | Node 26/OpenTUI CI coverage is useful; visual/world-class UX and native runtime QA are separate gates. |
| C `f1d3bd03` | PENDING | Makes the standalone CI matrix self-contained; no product semantic defect found in the first pass. |
| C `46970f38` | FAIL | Adds path-free registration/heartbeat/binding, but explicit project registration/lease failures could fall through to an unbound submit. Active CLI remediation now aborts before submit and has a 404/409/5xx/malformed/offline matrix. |
| D `0ca6784` | MIXED | Adds useful enterprise/deployment/brownfield contracts, but they describe a pre-current migration/runtime tuple and cannot serve as release truth without reconciliation. |
| D `c0fe682` | MIXED | Corrected V277 evidence at the time; later V278-V282 changes make it historical rather than current release evidence. |
| D `31c4e50` | FAIL | “Final remediation checkpoint” wording is incompatible with the current confirmed gaps and unsynchronized tuple. Retain as history, remove it from current release authority. |
| D `b744d6e` | MIXED | Guarded DeepSeek verification is bounded historical evidence only; it is not broad provider quality or current-tuple proof. |
| D `3692e37` | MIXED | Canonical RCA register is useful, but linked evidence was later reclassified and must not imply release closure. |
| D `6e89cdc` | MIXED | Adds a large adversarial evidence packet (~22k text lines plus binaries/archive). This is difficult to review, risks secret/PII retention and is over-engineered as a Git payload; it requires publication secret scanning, provenance checks and retention rules before acceptance. |
| D `5cbc8e8` | MIXED | Fixed then-current reason-ID collisions and repinned FastContext synthetic evidence; docs CI is red again on later duplicate IDs, and synthetic FastContext remains non-product evidence. |
| D `43edeb8` | PENDING | Test plan and generated-code assessment only; execution against the final candidate remains open. |
| D `99d024e`, `0081d7f`, `0164805` | FAIL AS CURRENT CLAIM | The progressive “10/19”, “16/19” and “all 19 LIVE_CLOSED” records are historical observations. Current routing, role, deployment and generated-output defects prevent inheriting their closure status. Preserve, label historical, rerun the acceptance matrix. |
| D `c7eddb9` | MIXED | Records model-aware Launch estimate changes but uses a reason-ID sequence that required local collision correction. Estimate accuracy/reconciliation remains open. |
| D `c906287` | FAIL AS COMPLETE BOUNDARY | Documents the partial pipeline OWN_KEYS guard, but the full dispatch audit proves the boundary is not fail-closed for synthetic/project/background contexts. Must be amended, not used as enterprise isolation proof. |

The highest-risk patches are the large mixed-concern B `ca8f86926`/`1110cc772` and D `6e89cdc` packets. The final per-commit gate must also run secret/PII scanners, validate every evidence manifest against its producing SHA, and confirm no docs-only “closed/final” label is routed as current product truth.

## 2. Pull requests and CI

| Repository | Draft PR | Base | Pushed-head CI at capture | Important qualification |
| --- | --- | --- | --- | --- |
| Backend/Web | [#284](https://github.com/navneetprabhakar/executespec/pull/284) | `dev` | Java test and frontend lint jobs green | Green checks cover pushed HEAD `31682354e`, not local `4c9442bd8` or dirty work |
| CLI | [#21](https://github.com/navneetprabhakar/executespec-cli/pull/21) | `main` | Node 20/22/26, macOS and Windows jobs green | Green checks do not include the current fail-closed local-binding work |
| Docs | [#1](https://github.com/navneetprabhakar/executespec-docs/pull/1) | `main` | `docs-integrity` red | Failure is duplicate reason IDs at pushed HEAD; local IDs were corrected and the 55-test local docs gate passed, but that correction is not pushed |

No PR is ready for review or merge. A later verified checkpoint must push all three repositories, wait for their checks and pin their exact compatible tuple.

## 3. Version and deployment truth

| Item | Observed value | Assessment |
| --- | --- | --- |
| Backend source version file | `2.0.0` | Does not prove the deployed build identity |
| CLI root workspace | `1.0.0` | Workspace metadata differs from publishable package versions |
| Local `executespec` package | `1.0.2` | Candidate source version |
| Local `@executespec/core` package | `1.0.2` | Candidate source version |
| Local VS Code extension | `1.0.0` | Needs a compatibility declaration against server/client contracts |
| Published `executespec` | `1.0.2` | npm observation |
| Published historical `@executespec/cli` | `1.0.1` | Old package lane; requires explicit deprecation/compatibility handling |
| Published `@executespec/core` | `1.0.2` | npm observation |
| Dev root and health | HTTP 200 | Availability only, not candidate identity |
| Authenticated permissions | HTTP 200, SUPER_ADMIN | Credentials worked; values were not recorded |
| Dev `/api/client-capabilities` | HTTP 404 | Deployed server predates the current capability contract |
| Dev `/api/v3/provider-readiness` | HTTP 404 | Deployed server predates the current provider-readiness contract |

The dev environment is not the current candidate and cannot serve as release evidence until the exact server SHA, migration head, Web assets and client compatibility contract are exposed and verified.

## 4. Authenticated dev feature-flag evidence

The authenticated flag API returned 61 flags. The material observed values were:

- Scoring/review safeguards enabled: `scoring.additive-v2`, `scoring.gaming-guards`, `scoring.real-test-exec`, `scoring.v2-verified`, `reviewer.third-pass.enabled`.
- Local hands enabled: `worker.local-hands.enabled=true`.
- Conservative routing controls: `provider.smart-routing.enabled=false`, `worker.cross-provider-fallback=false`, `worker.provider-prompt-tuning=false`.
- Context/worker experiments disabled: history compaction, tool compaction, build TDD, boot verification, background jobs, capability profiles, localization and progress-v2.
- Chat enabled: `spec.chat.enabled=true`.
- Pause disabled: `v2.pause.enabled=false`.
- **Security guardrail level is `LOW`.** This is not an acceptable default for an enterprise pilot and must be reconciled with policy, UI and safe-outage behavior before release.

These are deployed runtime facts, not source defaults. A release gate needs a versioned effective-configuration export for the customer environment.

## 5. Flyway and credential-security audit

At the start of this audit the committed source claimed V279 while older documentation still claimed V277/V278. Active work now adds:

- **V280:** widen the remaining `app_user.bitbucket_app_password` and `pipeline_run.bitbucket_app_password` credential columns to `TEXT`, because versioned AES-GCM envelopes can exceed the old 512-character width.
- **V281:** bind PR reviews to a linked repository and enforce project-scoped uniqueness (review-admission workstream).

The V279 migration was necessary but not sufficient: many credential columns were already `TEXT`, while the two remaining Bitbucket columns were still bounded. More importantly, `KeyMigrationRunner` caught per-column migration errors and continued startup even when encryption was mandatory. That behavior created a false security guarantee.

The active hardening slice now:

1. Fails application startup when a credential migration fails under `crypto.require-encryption=true`.
2. Preserves warn-and-continue compatibility only for optional local/development encryption.
3. Applies `EncryptedValueConverter` to `pipeline_run.github_pat` and `pipeline_run.bitbucket_app_password`.
4. Migrates legacy `pipeline_run` SCM snapshots in addition to account/org/integration secrets.
5. Adds a real Postgres V278-to-current upgrade test with an over-512-character legacy Bitbucket secret, REDACTED_FOR_PUBLIC_RELEASE checks and restart idempotence.

Unit inventory/migration-runner tests passed locally after V281 was added. The standalone Postgres 16 rehearsal then intentionally exercised real SQL and exposed an additional defect: the generic runner queried `pipeline_run.id`, but that table's key is `run_id`. The key column was made explicit, a focused regression was added, and the V278→V281 rehearsal passed on rerun: three forward migrations, eight legacy/plaintext values enveloped and decrypted (including the 480-character secret), and a second startup/Flyway run made zero changes. The separate V280→V281 PR-review migration rehearsal also passed. Final-head clean install, production-profile validation and docs-head reconciliation remain required because V282+ work is now in progress.

## 6. Confirmed semantic product gaps

### BYOK and routing

- `RunKeyStore` has no production population path, yet provider availability reads it. The resulting view can degrade to system-provider availability rather than the org's actual OWN_KEYS/allowlist policy.
- Missing, synthetic or stale execution identity is treated as platform-capable in important paths, and the negative resolution is cached. Enterprise/BYOK must fail closed before dispatch or spend.
- Several project/background operations use synthetic IDs without a durable user/org/project/key-source/route context. Restart therefore loses authoritative routing provenance.
- Project selection resolves a provider but does not by itself prove the org allowlist, exact model matrix and key source were enforced for every downstream stage.
- Some pre-planner architecture, intent-routing, chat and background paths bind key/route context under identifiers different from the actual LLM request identifier.
- The embedding path uses a global OpenAI embedding key/model directly, outside the canonical router, BYOK policy and usage ledger.

These findings require a durable execution-context contract and a fail-closed hydration chokepoint, not isolated null checks.

### Project and source handling

- The previously published CLI path could continue an explicitly project-bound local run after registration/heartbeat failure, silently converting it into an unbound run. Active CLI work now aborts on 404/409/5xx, malformed registration, heartbeat failure or `OFFLINE`; projectless compatibility remains explicit.
- Whole-repo Code Review and PR Review admitted names/coordinates that were not necessarily linked project repositories and had incomplete direct-read/service-boundary authorization. Active V281/review-controller work addresses this slice; it remains open until tests and frontend contracts pass.
- Project mutation authority previously treated any same-org user as a writer unless an explicit PROJECT_VIEWER row existed. Active V285/access-guard work removes that compatibility shortcut: SUPER_ADMIN, host ORG_ADMIN, or explicit PROJECT_ADMIN/PROJECT_DEVELOPER membership is now required, and a migration backfills missing creator-admin rows for legacy/imported projects. The direct member-administration API also used to accept arbitrary role strings and cross-org user IDs, bypassing the email-bound external invite/accept policy; it now validates the three project roles and restricts direct adds to users in the host org. Full endpoint-role regression and a real final-head migration rehearsal remain open.
- Local-folder support is intentionally a local-hands lease, not server filesystem access. Web and documentation must not imply that a cloud worker can read an arbitrary client path.
- Bitbucket Cloud has an adapter but does not yet have complete normal-user, lifecycle and live evidence parity with GitHub.
- The Bitbucket gap is architectural, not a missing UI toggle. `CreateRepoRequest`, remote-catalogue attach, stack/readiness sync, codemap/architecture, staleness, workspace health, `.executespec/` bootstrap, Code/PR Review and webhooks all consume GitHub-specific identity or APIs. The separate Bitbucket clone/push/PR helper only covers a run-output path; it does not make Bitbucket a normal project source.
- Large-repository text search contained an inverted hot path: `SearchFilesTool` always completed `RepositorySearchService.search` first, which inventories and content-hashes the whole repository, so the later documented ripgrep path ran only after a structured-search exception. The active fix makes direct ripgrep the first path for EXACT/REGEX, keeps typed SYMBOL/RELATIONSHIPS on the structured index, and stops the subprocess as soon as the 1,000-match contract ceiling is reached rather than leaving it blocked on stdout.
- Large-repository semantic/codemap completeness is still not honest enough: semantic indexing stops after 4,000 eligible files in traversal order, codemap output lists at most 1,000 paths and samples functions from 40 files, and GitHub's recursive-tree `truncated=true` signal is not carried into the codemap or an operator-visible coverage manifest. A successful search result therefore does not yet prove adequate repository coverage.
- Uploaded-document RAG's ingestion defect is addressed in the active V288 slice: multipart bytes are bounded and content-inspected before persistence; supported PDF/text/OOXML paths have polyglot/container/decompression controls, Enterprise scanning fails closed, Tika output and project totals are quota-bound, and UUID storage plus `project_document_blob` removes filename collision/Latin-1 corruption and enables restart recovery. Typed retry/error status is client-visible while raw paths/parser failures are not. Verification is not closed: the focused adversarial rerun is green and a real PostgreSQL rehearsal is added, but the combined integration run, full local AV adapter, parser process isolation and live hostile-corpus evidence remain.
- Public share links have good scope-to-project IDOR checks and explicit revocation, but the governance contract is below Enterprise level: expiry is optional and unbounded, the bearer token is stored in plaintext, successful reads only update a best-effort mutable counter, and there is no append-only access record or token-scoped anomaly control. The public result DTOs also need an explicit redaction contract before they can be treated as safe publication surfaces.
- Project export is still a synchronous request-thread operation that materializes the entire compressed archive in heap. It has no durable job identity, lease/heartbeat, cancellation/retry, object-backed expiry, export-specific quota or download audit, and the controller reflects exception messages into the HTTP error. The archive also documents that member, memory, run and repository artefacts are intentionally missing, so the current endpoint is neither large-project safe nor a complete portability/compliance export.

### Reliability, usage and security

- Token/cost evidence is split across ledgers; failed provider attempts and some retry/failover evidence are not canonical durable usage facts.
- Cross-JVM project/background recovery and exactly-once spend/action semantics are incomplete.
- The customer-facing background-work problem is broader than Code/PR Review. The source contains more than 250 async/executor/scheduler references; the high-risk LLM/project subset includes fixed in-JVM pools for Code Review and PR Review, lazy `@Async` code indexing/document processing/bootstrap, and handler-owned chat apply pools. Most have status columns but no common tenant-scoped lease, heartbeat, fencing token, restart poller or attempt ledger. V282 provides that primitive only; adoption is still required before those jobs are restart-safe.
- Code semantic indexing uses a JVM-local `inProgress` set, has no cross-node exclusion, deletes then reinserts file chunks without a job fence, does not remove deleted source files, and considers a repo "indexed" after any chunk exists. On the first pass it can stop after 4,000 changed files; because later chat turns see `isIndexed=true`, the lazy trigger will not resume the remainder. This is silent partial coverage and stale-result risk.
- Guardrail `LOW` on dev, synchronous/broad exports, upload hardening, public-link governance, secret scanning and admin observability remain open enterprise gates.
- FastContext must remain contained and unexposed until a reproducible Microsoft runtime exists. Sarvam must remain outside all normal provider/config/UI/failover surfaces.

## 7. Current verified local evidence

| Slice | Evidence | Result |
| --- | --- | --- |
| Credential migration unit/inventory | `KeyMigrationRunnerTest`, `FlywayMigrationInventoryTest` | PASS after V281 inventory adjustment |
| Credential envelope real upgrade | Standalone Testcontainers PostgreSQL 16, V278→V281 | PASS after the rehearsal found and drove the `pipeline_run.run_id` fix; 8 values encrypted/decrypted; rerun idempotent |
| PR-review tenant migration | Standalone Testcontainers PostgreSQL, V280→V281 | 1/1 PASS: backfill, project-scoped uniqueness and idempotence |
| CLI fail-closed local binding | Core suite | 160/160 PASS |
| CLI fail-closed local binding | CLI suite | 746/746 PASS |
| CLI local-run regression | Targeted suite | 53/53 PASS |
| CLI binding fault matrix | Focused tests | 9/9 PASS |
| CLI build/format/patch hygiene | core+CLI build, format, `git diff --check` | PASS |
| Durable LLM execution-context foundation | Policy/lifecycle/migration/persistence cohort | 12/12 PASS; compile + Spotless PASS; router/feature adoption deliberately still open |
| Large-repo local text search hot path | ripgrep selection + fixed-string + stdout-ceiling tests with existing search/citation cohort | 15 focused tests PASS |
| FastContext/Sarvam containment | Backend/Web/CLI/VS Code/docs focused suites and production-class inspection | PASS locally; final commit/tree evidence provenance still pending |
| Docs local integrity after duplicate-ID correction | complete docs gate | 55 tests PASS; not pushed |

Passing focused tests prove only their named contracts. They do not close full product QA, deployment compatibility, BYOK dispatch, benchmark or release gates.

## 8. Immediate decisions and next evidence

1. Finish review-admission and project/BYOK execution-context work, including negative role/key/restart tests.
2. Finish the Postgres upgrade rehearsal, then clean-install and production-profile validation at current migration head.
3. Audit all project features and chat tools against the same durable route, role and usage context.
4. Produce a canonical attempt-level usage event and admin roll-ups before claiming spend governance.
5. Deploy the synchronized candidate to an isolated dev/QA environment and repeat authenticated Web + released/local CLI adversarial journeys.
6. Only after product gates pass: review current official benchmark rules, run the 10-case native-DeepSeek local canary through the benchmark API/UI, freeze config, provision disposable benchmark infrastructure and authorize full-suite spend.
7. Keep release and paid benchmark gates closed until the master tracker's explicit completion criteria are evidenced.

## 9. Reproduction command families

The audit used non-secret forms of the following command families:

```bash
git status --short --branch
git rev-parse HEAD
git rev-list --left-right --count <base>...HEAD
git log --oneline <base>..HEAD
gh pr view <number> --json number,url,isDraft,state,headRefName,baseRefName,statusCheckRollup
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -Dtest=KeyMigrationRunnerTest,FlywayMigrationInventoryTest test
```

Authenticated requests read credentials from the user-provided local file without printing or persisting their values. Provider calls, benchmark infrastructure and DNS mutations remained at **$0 / not started** during this audit slice.
