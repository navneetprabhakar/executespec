# ExecuteSpec enterprise-demo session handoff — updated 2026-07-28

> **Use this file to start the next Codex session.**
>
> This supersedes the session-state portion of the earlier handoff at
> `/Users/navneetprabhakar/Documents/antigravity/executespec-docs-ri-planir/backend/plans/session-handoff-2026-07-28-enterprise-demo.md`.
> The earlier document remains the complete programme backlog. This addendum records work performed
> after that handoff was pushed.

## Resume prompt

```text
Continue the active ExecuteSpec enterprise-demo programme from:

/Users/navneetprabhakar/Documents/antigravity/executespec/SESSION_HANDOFF_2026-07-28_ENTERPRISE_DEMO.md

Then read:
/Users/navneetprabhakar/Documents/antigravity/executespec-docs-ri-planir/backend/plans/session-handoff-2026-07-28-enterprise-demo.md

Inspect all three isolated worktrees, their current dirty diffs, and current test reports before
editing. Preserve every dirty file. Do not reset, clean, stash, overwrite, merge, deploy, publish,
activate flags, modify prompts, install Graphify, or spend provider budget without explicit
authorization. Keep requirement.ir.gate.mode=LEGACY.

First decide how to safely continue V354. Its atomic core tests are green, but the migration is
unsafe to commit or deploy until operation authority is propagated through every production root
and every physical provider/embedding attempt. Complete DB-clock lifecycle conversion before
activation. In parallel, finish the exact-current-tuple authenticated VS Code host run. Use
functional/E2E evidence and do not treat unit tests as generated-code quality evidence.
```

## Stable pushed checkpoint

| Repository | Isolated worktree | Branch | Pushed HEAD |
| --- | --- | --- | --- |
| Backend/frontend | `/Users/navneetprabhakar/Documents/antigravity/executespec-ri-planir` | `codex/repository-intelligence-planir-clients` | `32b99aa9996732e6cf6f7787efbab4c4ebac5bcc` |
| CLI/VS Code | `/Users/navneetprabhakar/Documents/antigravity/executespec-cli-ri-planir` | `codex/repository-intelligence-planir-clients` | `c7a41673fc7c87eb058a2400c0bee1fb1eaf0825` |
| Canonical docs | `/Users/navneetprabhakar/Documents/antigravity/executespec-docs-ri-planir` | `codex/repository-intelligence-planir-clients` | `21b2305a5460314b7973c9dee4f83ec14ab569c7` |

The stable backend checkpoint passed 5,875/5,875 tests. The docs checkpoint passed strict integrity
with zero findings and 98/98 docs/OpenAPI tests. No later implementation work has been committed.

## Detailed status against the morning goal

This ledger is the resume authority for work discussed and executed on 2026-07-28. “Source
complete” means the implementation and deterministic gates exist on the pushed tuple; it does not
mean the workflow has passed a provider-backed or manually observed dev-environment acceptance
test. The next session must not repeat items marked **DONE — do not redo** unless a later change
invalidates their evidence.

### A. Core programme checkpoints

| Goal item | Current status | Evidence/current truth | Next action; avoid duplicate work |
| --- | --- | --- | --- |
| Phase 0 baselines, isolated worktrees, fixture corpus and zero-spend ledger | **DONE — do not redo** | Backend, frontend, CLI and VS Code baselines; isolated worktrees; dev SHA/health snapshot; deterministic corpus and spend ledger are recorded in the canonical live status | Recheck only HEAD/status/processes at resume; do not recreate worktrees or baseline corpus |
| Atomic plan-gate APPROVE/REJECT/pre-approval CANCEL and canonical `DecisionEvidenceV1` | **DONE — do not redo** | V348 decision/receipt/outbox contract, lock order, exact replay, concurrent decision tests and rollback/recovery tests were committed before the current checkpoint | Extend only if V354 operation fencing changes the post-handoff execution path |
| Leased plan-gate continuation dispatcher and crash/startup recovery | **DONE — do not redo** | Durable `WAITING → READY → PROCESSING → COMPLETED`, exact-token fencing, startup sweep and executor-registration race fixes are committed and tested | Do not rebuild a second dispatcher |
| Tenant/payer/credential separation and RequirementIR compatibility | **DONE — do not redo** | V349/V350 identity corrections plus exact RequirementIR schema/compiler registry are committed | Preserve compatibility while finishing remaining enforcement gates |
| Run API, fork/iterate, Execution View and confirmed cloud-run admission | **DONE — do not redo** | These entry points route through the unified admission service; the earlier Execution View bypass is closed in source | Run final cross-surface acceptance after later V354 work; do not implement another admission path |
| RequirementIR evidence APIs and V352 blocked-evidence compatibility | **DONE — do not redo** | Run and execution evidence routes, authorization, secret-free DTO and 75/75 focused/PostgreSQL evidence were committed | Keep `requirement.ir.gate.mode=LEGACY`; final manual UI verification remains |
| RequirementIR enforcement graduation | **OPEN** | `LEGACY` remains active. Local/dev protected-flag snapshots found a real mismatch; dev lacks protected mode/shadow rows | Requires explicit authorization for dev mutation/deploy, exact parity comparison and all graduation gates |
| Per-attempt post-transfer LLM operation fencing | **IN PROGRESS / UNCOMMITTED / UNSAFE TO DEPLOY** | V354 atomic core and 9/9 new unit/PostgreSQL tests are green, but no production roots/transports carry authority | Follow the V354 safe order in this handoff; do not apply the trigger before complete root and physical-attempt wiring |

### B. Repository intelligence and generation-path work

| Goal item | Current status | Evidence/current truth | Next action; avoid duplicate work |
| --- | --- | --- | --- |
| Typed `RepositoryIntelligencePort` and bounded evidence contracts | **SOURCE COMPLETE — do not recreate** | Read-only typed snapshot/query/evidence/outcome model, deterministic selection bounds and explicit UNKNOWN semantics are pushed | Extend the existing port; do not introduce a second repository-intelligence abstraction |
| Tenant/actor/project/repository-bound root resolver | **DONE — do not redo** | Access-checked root resolution and path/symlink containment were committed and provider-free tested | Add consumers through this resolver |
| Persisted CODEMAP and per-repository architecture reads | **DONE — do not redo** | Provider-free persisted artifact reads are committed at the stable backend checkpoint | Use these readers as existing evidence sources |
| Real RepositoryExplorer service and pipeline consumers | **OPEN** | Only selection/bounding contracts exist. There is no real end-to-end exploration service driving planner/worker/reviewer | Implement definitions, callers, tests, config, documents, ADR/standards/contracts, freshness and provenance through the existing port |
| Actual generation-path retracing | **OPEN** | A read-only audit mapped key code paths, but the platform does not yet provide bounded runtime retracing to codegen agents | Build the explorer and measure per-source token contribution |
| FastContext | **DECIDED AS INTERNAL PATTERN** | It means separation of exploration from reasoning; no verified upstream runtime dependency was found | Apply the pattern internally; do not search for or install an assumed package again |
| Graphify | **NOT STARTED / AUTHORIZATION REQUIRED** | No install, security review or runtime experiment occurred | If explicitly approved: security review, deterministic fixture adapter and native-baseline comparison first |

### C. Enterprise-demo bugs from manual dev review

| Reported bug/workflow | Current status | What was actually fixed or proven | Remaining acceptance |
| --- | --- | --- | --- |
| Generated code does not satisfy requirements | **OPEN** | Deterministic harness/process defects were fixed, but no provider-backed medium/tough code-quality cohort was authorized on the current tuple | Explicit budget authorization, cheap non-frontier models, functional/E2E scoring and direct code review |
| Web-agent files truncate around 110–150 lines | **OPEN — do not confuse with Project Docs** | Ask and Project Docs output-limit handling were hardened; the Web-agent file-write/stream/storage path was not reproduced or fixed | Reproduce across cheap models, trace the exact file lifecycle and compare stored/downloaded bytes |
| Ask fails with durable execution-context budget exhausted | **PARTIAL SOURCE FIX; LIVE QUALITY OPEN** | Ask now returns additive typed completion/limit/source evidence and avoids treating truncated output as complete | Run a long real Ask turn, reconcile physical attempts/budget/tokens and verify useful recovery UX |
| Regenerate Docs fails | **SOURCE HARDENED; LIVE PROVIDER TEST OPEN** | Provider work was removed from broad DB transactions; deterministic repository-order source allocation, finish-reason checks, structure/safety validation and atomic publication were added | Authorized fresh-project generation plus human quality inspection |
| Refresh Map times out/fails | **SOURCE FIXED; LIVE SCM/MODEL TEST OPEN** | V353 durable DB-first architecture jobs, reload, confirmation, cancellation and unified entry points were implemented and tested | Real linked-repo refresh with authorized model call, restart/reload and manual dev UI |
| Compare History checks file presence instead of content diff | **SOURCE FIXED — do not redo** | Bounded authorized historical file-content diff is implemented and frontend-tested | One manual dev comparison against two real revisions |
| Semantic indexing has no compatible embedding model | **OPEN** | Lexical fallback and governed embedding groundwork exist, but the exact account/provider recovery path is not closed | Reproduce with the affected project/provider policy, make setup/recovery actionable, test indexing and retrieval |
| Timeline shows epoch-scale duration | **DETERMINISTIC FIX PROVEN; MANUAL DEV OPEN** | Provider-free fixture validates real timestamps and rejects epoch-scale duration; browser workflow passed | Verify one newly generated dev run after deployment |
| Screens disabled/not rendering | **OPEN** | Honest preview/screen source state exists, but the reported rendering workflow has not passed an actual render/reload test | Verify flag/config parity, render job, persistence, reload, failure UX and cleanup |
| Web Sandbox click/runtime failure | **OPEN** | Local-run constraints and no-shell/allowed-file persistence were strengthened; this is not a remote Sandbox lifecycle acceptance | Test create/approve/files/terminal/reconnect/close/cleanup and security on the real Sandbox surface |
| Steps details expose confusing model-routing internals | **OPEN / PARTIAL UI WORK ONLY** | Typed goal/continuation/ranking evidence exists, but the reported operator-heavy Steps presentation was not accepted manually | Reframe customer-facing content, keep audit detail behind an operator disclosure, run browser acceptance |
| Final Goal proof/evidence does not make sense | **SOURCE IMPROVED; REAL-RUN REVIEW OPEN** | Validation uses typed ledger references and separates deterministic proof from LLM assessment | Inspect evidence on medium/tough real runs and correct misleading claims |
| Create and test a fresh Project | **OPEN** | Project/architecture fixtures are partial and do not establish full onboarding quality | Link/create repo, index, map, docs, chat, run, PR, preview and deployment-tracking workflow |
| Protected Web fixture isolation | **FOCUSED COMPLETE / UNCOMMITTED** | Exact `web-qa`-only scripted stream gate; 18/18 related tests and static contract 8/8 green | Include in the next safe backend checkpoint and rerun combined browser/CLI/VS gate |

### D. Features added to the goal

These have detailed product/contract designs in
`backend/plans/dev-review-feature-candidates-2026-07-28.md` in the docs worktree. The design itself
must not be reported as implementation.

| Feature | Current status | Resume action |
| --- | --- | --- |
| Set provider/model in Web agent | **DESIGNED, NOT IMPLEMENTED** | Build server-authoritative preference/model validation and Web control |
| Per-task model matrix | **DESIGNED, NOT IMPLEMENTED** | Implement only after account/project precedence and audit contract are agreed |
| Link/create Project inside Web agent/Sandbox | **DESIGNED, NOT IMPLEMENTED** | Attach an authorized existing project; creation should hand off to canonical Projects flow |
| Close current workspace | **DESIGNED, NOT IMPLEMENTED** | Add explicit session close semantics without deleting project/run history |
| Conversation snapshot/history without recreating workspace | **DESIGNED, NOT IMPLEMENTED** | Persist/reload conversation snapshot separately from workspace recreation |
| OpenRouter keys in `/provider-keys` | **DESIGNED/KNOWN GAP, NOT IMPLEMENTED** | Add encrypted key CRUD, validation, provenance and redacted readiness |
| AI delivery lifecycle: code → PR → CI → fix → deploy → track | **DESIGNED, NOT IMPLEMENTED** | Compose existing canonical run/PR/CI/deploy records; do not create a duplicate lifecycle ledger |
| PilotSRE integrations: PagerDuty/New Relic/A2A | **DESIGNED, NOT IMPLEMENTED** | Adapter/security/idempotency work is later scope |

### E. Cross-client status

| Surface | Current status | Evidence | Remaining |
| --- | --- | --- | --- |
| Browser Web-QA | **PUSHED BASELINE GREEN** | Provider-free browser 21/21 at the stable checkpoint | Rerun exact tuple after V354/Web-QA changes; then manual Screens/Sandbox/project workflows |
| CLI | **PUSHED FUNCTIONAL BASELINE GREEN** | 124/124 provider-free functional checks at the stable checkpoint | Real terminal local-hands permissions, cancellation, approval/reconnect and generated-output evidence after shared protocol work |
| VS Code signed-out/package baseline | **GREEN** | Core 211/211, deterministic VS 51/51, typecheck/format/diff green, signed-out real host passed with 27 commands | Preserve these; do not rerun until client/backend tuple is stable |
| VS Code authenticated host | **IN PROGRESS / STOPPED** | Harness and minimal PAT scopes corrected; the successful exact-current-tuple run did not complete | Run once against the exact tuple with PAT revocation, zero provider attempts, flag digest, SSE dedupe, cancellation and cleanup |
| Shared server-owned protocol | **PARTIAL** | Existing core contracts are shared, but chat/action replay and some inventories still drift | Generate/enforce one protocol and remove manual mirrors |
| CLI/VS Code release | **NOT AUTHORIZED / NOT DONE** | No package or extension publication occurred | Publish only after exact tuple and release gates pass |

### F. Verification and release state

| Gate | Status |
| --- | --- |
| Stable backend full gate | **GREEN:** 5,875/5,875 at backend `32b99aa9996732e6cf6f7787efbab4c4ebac5bcc` |
| Stable docs gate | **GREEN:** 98/98 plus strict integrity 0 at docs `21b2305a5460314b7973c9dee4f83ec14ab569c7` |
| Current dirty V354 focused/PostgreSQL gate | **GREEN:** 4 unit + 1 migration + 4 service PostgreSQL tests |
| Current dirty backend clean full gate | **NOT RUN** |
| Current dirty authenticated browser/CLI/VS combined gate | **NOT RUN** |
| Provider-backed medium/tough output-quality gate | **NOT AUTHORIZED / NOT RUN** |
| Local/dev protected feature-flag parity | **OPEN:** mismatch identified; mutation requires authorization |
| Dev deployment | **NOT DONE / NOT AUTHORIZED in this handoff session** |
| Merge/release/CLI publish/VS Code publish | **NOT DONE** |
| Provider spend after the morning checkpoint | **$0** |

### G. Exact resume boundary

The next session should **not** begin with another broad audit. Perform only these short read-only
checks first:

1. Confirm the three isolated HEADs and dirty file lists match this document.
2. Confirm no Maven/Spring/PostgreSQL/Redis/Electron/VS host process or disposable container remains.
3. Inspect the current V354 diff and the three Surefire XML reports.
4. Inspect the authenticated VS Code harness diff.

Then resume in this order:

1. Convert the full operation lifecycle to database time.
2. Propagate authority and wire every physical transport/root before enabling V354.
3. Run focused transport matrices, PostgreSQL crash/concurrency tests and clean full backend gate.
4. Run one exact-tuple authenticated VS Code test and then the combined provider-free
   browser/CLI/VS gate.
5. Commit/push backend first, client second, docs exact-SHA checkpoint third.
6. Ask for provider-budget authorization before any generated-code quality run.

## Subagent and runtime state

- All subagents were explicitly stopped:
  - `c07_atomic_admission_core`: interrupted/stopped.
  - `v354_transport_root_audit`: completed, no edits.
  - `vscode_authenticated_host_impl`: interrupted/stopped.
- The last known disposable VS Code/Web-QA wrapper cleaned its backend and containers through its
  trap before the final retry.
- The current managed environment denied OS process enumeration after shutdown. The next session
  must perform a read-only process/container/port audit before starting Maven, Spring Boot,
  PostgreSQL, Redis, Electron, or VS Code Extension Host processes.

## Work completed after the earlier handoff

### 1. Web-QA scripted-stream isolation — focused scope complete

Dirty backend files:

```text
CLAUDE.md
src/main/java/com/navneet/executespec/agentsession/AgentStreamTurnRunner.java
src/main/java/com/navneet/executespec/agentsession/CLAUDE.md
src/test/java/com/navneet/executespec/agentsession/AgentStreamTurnRunnerTest.java
```

Behavior:

- Scripted fixture events require a reserved `web-qa-*` session id.
- `web-qa` must be the only active profile.
- `executespec.live-llm.enabled` must be false.
- Null environment, local/dev/test, mixed profiles, live dispatch, and ordinary ids fail closed.
- Ordinary sessions still execute the real `SpecChatService` stream in the Web-QA profile.

Executed evidence:

- `AgentStreamTurnRunnerTest`: 13/13 passed.
- `AgentSessionCreationServiceTest`: 5/5 passed.
- Static provider-free Web-QA contract: 8/8 passed.
- Targeted Spotless and `git diff --check`: passed.

No provider was called.

### 2. V354 atomic operation-admission core — implemented but not runtime-integrated

Dirty backend files:

```text
src/main/java/com/navneet/executespec/cost/LlmUsageAttemptIntent.java
src/main/java/com/navneet/executespec/executioncontext/LlmExecutionBudgetGate.java
src/main/java/com/navneet/executespec/executioncontext/LlmExecutionContextRepository.java
src/main/java/com/navneet/executespec/executioncontext/LlmExecutionOperation.java
src/main/java/com/navneet/executespec/executioncontext/LlmExecutionOperationAdmissionService.java
src/main/java/com/navneet/executespec/executioncontext/LlmExecutionOperationAuthority.java
src/main/resources/db/migration/V354__atomic_llm_operation_admission.sql
src/test/java/com/navneet/executespec/executioncontext/LlmExecutionOperationAdmissionMigrationIntegrationTest.java
src/test/java/com/navneet/executespec/executioncontext/LlmExecutionOperationAdmissionPostgresIntegrationTest.java
src/test/java/com/navneet/executespec/executioncontext/LlmExecutionOperationAdmissionServiceTest.java
```

Implemented:

- Opaque, Jackson-ignored, redacted in-process operation authority.
- All six V351 fence columns mapped on the attempt-intent entity.
- Context-lock then operation-lock atomic admission.
- Database-time lease/deadline validation.
- Aggregate budget reservation, lease/reclaim-horizon extension, and version-1 intent insertion in
  one `REQUIRES_NEW` transaction.
- Caller-provided fence material is overwritten by durable locked state.
- V354 forward migration replaces caller-clock temporal validation with database-clock validation.
- Historical durable version-0 rows are preserved; new context-free version-0 rows remain allowed;
  new durable version-0 rows are rejected.

Defects found and corrected during real PostgreSQL testing:

1. Hibernate had to `saveAndFlush` the extended operation horizon before the intent trigger read it.
   The transaction still rolls everything back if intent insertion fails.
2. The V351 completeness constraint trusted caller-provided `started_at`; V354 now replaces that
   constraint and leaves temporal validation to `clock_timestamp()`.
3. The migration test needed the `public` schema in its search path for the pgvector type.
4. A historical V293 global constraint-name probe caused false cross-schema clean-install
   interference; the test now drops the completed upgrade schema before exercising a genuinely
   separate clean install.
5. PostgreSQL post-condition assertions now retrieve `TIMESTAMPTZ` values and compare them in Java
   rather than asking `JdbcTemplate` to infer an `Instant` bind type.

Executed evidence, confirmed from current Surefire XML:

- `LlmExecutionOperationAdmissionServiceTest`: 4/4 passed.
- `LlmExecutionOperationAdmissionMigrationIntegrationTest`: 1/1 passed, zero failures/errors.
- `LlmExecutionOperationAdmissionPostgresIntegrationTest`: 4/4 passed, zero failures/errors.
- The PostgreSQL service class proves atomic reservation/horizon/intent commit, full rollback after
  duplicate-intent failure, one winner at the final provider-call unit under two-thread
  concurrency, stale-token rejection, and cancellation rejection.
- The migration class proves V353→V354 and clean-install behavior, historical-row preservation,
  exact version-1 fence admission, forged `started_at` irrelevance, and expired-lease rejection.

This work is **not accepted for commit/deployment** yet:

- No full clean backend verification has run after these changes.
- Migration inventory, CODEMAP, package/root docs, and reason log are not updated for V354.
- `LlmExecutionOperationService` still uses application `Clock` throughout its lifecycle.
- No production logical root claims/heartbeats/completes/fails/cancels operation authority.
- `ExecutionContext` and `LlmRequest` do not carry operation authority.
- `RunStepUsageRecorder` still creates version-0 durable-context intents.
- Provider retries/failover, Anthropic fallback paths, Gemini cache/retry paths, and embedding
  batches do not use atomic admission.
- Applying V354 now would reject existing durable provider calls. Do not merge or deploy it in this
  state.

### 3. V354 transport/root audit — complete, read-only

Critical conclusion: V354 requires both of these before activation:

1. Every logical production LLM root holds a live `LlmExecutionOperationAuthority`.
2. Every physical provider or embedding HTTP attempt atomically reauthorizes the route/credential,
   reserves budget, extends the operation horizons, and inserts a version-1 attempt intent.

Required physical seams:

| Path | Physical attempts requiring independent admission |
| --- | --- |
| OpenAI-wire | Every OpenAI, DeepSeek, OpenRouter, and Fireworks attempt; every same-provider retry; every failover attempt |
| Anthropic | Forced-tool attempt, normal attempt, and temperature-removal retry |
| Gemini | Explicit-cache mint, initial generation, every 429 retry, and stale-cache fallback |
| Embeddings | Every `EmbeddingService` batch before `OpenAiEmbeddingTransport.embed()` |

Minimal safe propagation:

- Add opaque operation authority to `tool.ExecutionContext`.
- Preserve it through `child()`.
- Derive it into `LlmRequest`.
- Never serialize, log, or reconstruct it from request data.
- Refactor `RunStepUsageRecorder` to build the intent for atomic admission instead of independently
  saving durable version-0 rows.
- Fail closed when a durable context reaches transport without authority.
- Do not use a thread-local; roots cross futures, virtual threads, recovery, and background jobs.

Clock blocker:

`LlmExecutionOperationService` must use one database-time sample per transaction for
claim/resume/new invocation/heartbeat/protect/complete/fail/cancel/recovery/acknowledgement.
Application-clock skew can otherwise mint already-expired leases, permit late completion, or
reclaim early.

Production roots requiring explicit lifecycle wiring include:

- Run classifier/planner/task worker/reviewer/validation/test generation/ledger/memory compaction.
- PATCH, continuation, retry, re-review, and crash recovery.
- Agent intent, durable session turns, run chat, project chat, chat compaction, subagents, and
  implementer actions.
- Durable architecture, docs, code review, PR review, preview, document indexing, code indexing,
  and retrieval-query embedding jobs.

Direct bypass found:

- `NativeToolsTestController` calls provider clients/models directly without canonical operation or
  attempt accounting. Route it through `LlmRouter` with an explicit platform-internal operation or
  remove/disable the live route.

Safe implementation order:

1. Convert the complete operation lifecycle to database time.
2. Propagate opaque authority through execution context/request.
3. Add one shared physical-attempt admission component.
4. Wire OpenAI-wire/failover.
5. Wire Anthropic's three physical paths.
6. Wire Gemini cache/generation/retry/fallback.
7. Wire embedding batches.
8. Wire all production roots plus recovery/cancellation.
9. Close direct/contextless bypasses and strengthen the architecture guard.
10. Run provider-free transport matrices, PostgreSQL crash/concurrency tests, full clean verify,
    then document and commit.

### 4. Authenticated VS Code host lane — deterministic/signed-out green, authenticated incomplete

Dirty client files:

```text
DOCS.md
README.md
package.json
vscode/README.md
vscode/CODEMAP.md
vscode/package.json
vscode/src/extension.ts
vscode/test-host/run.mjs
vscode/test-host/suite/index.cjs
vscode/test-host/authenticated-run.mjs
vscode/test/authenticated-host-contract.test.mjs
```

Implemented:

- Development-only activation API for real SecretStorage seed/clear, `/api/auth/me`, Runs tree,
  provider readiness, dispatch resolution, and SSE collection.
- Authenticated runner with loopback-only backend access, purpose-specific child environment,
  excluded provider/email/password secrets, owner-only `0600` PAT/fixture files, and `finally`
  cleanup.
- Two launches against the same VS Code profile for SecretStorage persistence.
- Deterministic contracts for the authenticated-host harness.
- README, CODEMAP, and test-command updates.

Executed evidence:

- VS Code typecheck: passed.
- Core client tests: 211/211 passed.
- Deterministic VS suite: 51/51 passed, including three new authenticated-host security contracts.
- Root format check and `git diff --check`: passed.
- Signed-out real VS Code Extension Host: passed; 27 commands registered and multi-root
  diff/unlink confinement remained intact.

Authenticated-host status:

- The first exact-worktree attempt overlapped backend Maven compilation and was correctly
  discarded.
- A non-exact immutable-backend baseline reached the server but the fixture route returned 403
  because new PATs are least-privilege.
- The harness was corrected to request only the required scopes:
  `api:read,runs:write,settings:write,admin`.
- PAT creation uses `expiresInDays: 1`.
- Revocation verification correctly expects the retained inventory row with `revoked=true`; the
  API revokes rather than deletes the row.
- Explicit SKIP reasons were added for Web Login, server chat, cloud submit/approval, local hands,
  and restricted-workspace journeys instead of claiming fixture coverage.
- The authenticated host was stopped before the corrected retry completed.

Still required:

- Run the corrected authenticated host against the exact current backend/client tuple.
- Verify PAT revocation, fixture deletion, flag digest, zero provider attempts, zero residue, SSE
  reconnect without duplicate ids, cancellation, provider availability, SecretStorage persistence,
  sign-out, and multi-root behavior.
- The larger product gaps from the earlier handoff remain: server chat/action replay,
  interrupted-turn reconciliation, linked-repository picker, breaking-contract modal/stale digest,
  local-hands pending calls and Allow Once, and Electron modal assertions.
- Do not publish or release the extension.

## Current dirty worktrees

### Backend/frontend

`/Users/navneetprabhakar/Documents/antigravity/executespec-ri-planir`

```text
M  CLAUDE.md
M  src/main/java/com/navneet/executespec/agentsession/AgentStreamTurnRunner.java
M  src/main/java/com/navneet/executespec/agentsession/CLAUDE.md
M  src/main/java/com/navneet/executespec/cost/LlmUsageAttemptIntent.java
M  src/main/java/com/navneet/executespec/executioncontext/LlmExecutionBudgetGate.java
M  src/main/java/com/navneet/executespec/executioncontext/LlmExecutionContextRepository.java
M  src/main/java/com/navneet/executespec/executioncontext/LlmExecutionOperation.java
M  src/test/java/com/navneet/executespec/agentsession/AgentStreamTurnRunnerTest.java
?? src/main/java/com/navneet/executespec/executioncontext/LlmExecutionOperationAdmissionService.java
?? src/main/java/com/navneet/executespec/executioncontext/LlmExecutionOperationAuthority.java
?? src/main/resources/db/migration/V354__atomic_llm_operation_admission.sql
?? src/test/java/com/navneet/executespec/executioncontext/LlmExecutionOperationAdmissionMigrationIntegrationTest.java
?? src/test/java/com/navneet/executespec/executioncontext/LlmExecutionOperationAdmissionPostgresIntegrationTest.java
?? src/test/java/com/navneet/executespec/executioncontext/LlmExecutionOperationAdmissionServiceTest.java
```

### CLI/VS Code

`/Users/navneetprabhakar/Documents/antigravity/executespec-cli-ri-planir`

```text
M  DOCS.md
M  README.md
M  package.json
M  vscode/README.md
M  vscode/package.json
M  vscode/src/extension.ts
M  vscode/test-host/run.mjs
M  vscode/test-host/suite/index.cjs
?? vscode/CODEMAP.md
?? vscode/test-host/authenticated-run.mjs
?? vscode/test/authenticated-host-contract.test.mjs
```

### Canonical docs

`/Users/navneetprabhakar/Documents/antigravity/executespec-docs-ri-planir` was clean at pushed
commit `21b2305a5460314b7973c9dee4f83ec14ab569c7`.

## Remaining programme scope

The full pending scope remains in the earlier handoff. In summary:

- Preserve the completed RequirementIR rolling compatibility and unified Execution View
  admission; finish remaining API/UI acceptance and local/dev protected-flag parity while keeping
  `LEGACY`.
- Complete the unified repository-intelligence port and real RepositoryExplorer consumers.
- Keep Graphify uninstalled unless explicitly approved.
- Integrate bounded repository evidence into planning/worker/reviewer/validation, with
  cross-provider prompt review if prompts later change.
- With fresh explicit budget authorization only: run cheap, non-frontier medium/tough generated
  code quality tests, maximum $10/provider, functional/E2E acceptance, and reconciled
  token/cache/reasoning/cost evidence.
- Close the original Web-agent truncation, Ask, Screens, sandbox, project, docs/map, semantic-index,
  history-diff, timeline, and new-project workflows.
- Finish shared protocol, CLI terminal E2E, and VS Code gaps.
- Run clean backend, real PostgreSQL, frontend, browser, CLI, VS Code, project, Ask, Screens, and
  sandbox gates before requesting dev deployment.

Feature candidates remain designed but not shipped: per-task model matrix, Web-agent model picker,
project attach/create, workspace close, chat snapshots, OpenRouter key UI, AI DLC, and PilotSRE
integrations.

## Hard constraints

- No provider spend without explicit authorization.
- No frontier models for evaluation.
- No deployment, merge, release, publication, production mutation, or feature activation without
  explicit authorization.
- Keep `requirement.ir.gate.mode=LEGACY`.
- No prompt changes during the current safety work.
- Do not install Graphify without approval.
- Preserve original and isolated dirty worktrees.
- Do not commit or deploy V354 until every durable physical attempt and logical root is fenced.
- Use functional/E2E evidence for output quality; unit tests do not establish generated-code
  quality.
