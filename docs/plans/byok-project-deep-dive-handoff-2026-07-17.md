# BYOK + Project Workbench Deep-Dive Handoff

**Date:** 2026-07-17  
**Repository:** ExecuteSpec backend 2.0  
**Purpose:** Durable handoff for the Enterprise BYOK and Project Workbench audit. This document records the user’s intended contract, the code paths inspected, confirmed strengths, concrete escape paths, test evidence, and the work required before claiming Enterprise project readiness.

## 1. User’s intended contract

For an Enterprise/BYOK organisation:

1. Only providers for which the organisation has supplied keys may be used.
2. Global/system keys must never be used as a fallback.
3. Global provider preferences and global model defaults must not override Enterprise policy.
4. If the organisation selects one provider, only that provider’s model matrix remains eligible.
5. Automatic step-up, step-down, and provider switching may only choose an explicitly allowed customer provider/model.
6. The same rules apply to every project feature, not only `/api/runs`:
   project chat, architecture, codemaps, generated docs, memory compaction, RAG, code review, PR review, tech-debt autofix, and background jobs.
7. A missing or stale routing/key context must fail closed, never silently become a platform-key route.

## 2. Code paths inspected

### Key and provider resolution

- `src/main/java/com/navneet/executespec/agent/ApiKeyResolver.java`
- `src/main/java/com/navneet/executespec/agent/RunKeyContextStore.java`
- `src/main/java/com/navneet/executespec/agent/RunKeyStore.java`
- `src/main/java/com/navneet/executespec/agent/ResolvedKeys.java`
- `src/main/java/com/navneet/executespec/agent/LlmRouter.java`
- `src/main/java/com/navneet/executespec/router/AvailableProviderResolver.java`
- `src/main/java/com/navneet/executespec/router/ProviderAvailabilityMap.java`
- `src/main/java/com/navneet/executespec/router/ModelResolver.java`
- `src/main/java/com/navneet/executespec/router/ModelProviderCandidateService.java`
- `src/main/java/com/navneet/executespec/router/ProjectProviderResolver.java`
- `src/main/java/com/navneet/executespec/router/OrgModelOverrideService.java`
- `src/main/java/com/navneet/executespec/router/RunRoutePolicyService.java`
- `src/main/java/com/navneet/executespec/router/RunModelOverrides.java`

### Project features

- Project CRUD/membership: `auth/controller/ProjectController.java`, `auth/service/ProjectService.java`
- Repository sources: `project/controller/ProjectRepoController.java`, `project/service/ProjectRepoService.java`, `project/service/LocalProjectSourceService.java`
- Authorization: `project/security/ProjectAccessGuard.java`
- Architecture/codemap: `codeintel/ProjectArchitectureController.java`, `codeintel/ProjectArchitectureService.java`, `codeintel/ArchLlmDispatch.java`
- Generated docs: `codeintel/ProjectDocGeneratorService.java`
- RAG: `project/rag/EmbeddingService.java`, `ProjectDocumentService.java`, `CodeIndexingService.java`, `RetrievalService.java`
- Memory/chat: `memory/ProjectChatController.java`, `ProjectChatService.java`, `MemoryCompactionService.java`, `RunLedgerAgent.java`, `memory/chattools/ProjectChatTools.java`
- Code review: `codereview/CodeReviewController.java`, `CodeReviewService.java`, `CodeReviewAgent.java`
- PR review: `prreview/PrReviewController.java`, `PrReviewService.java`, `PrReviewAgent.java`
- Tech debt: `project/techdebt/TechDebtService.java`, `TechDebtFixService.java`
- Contracts/standards/activity/sharing/export: corresponding `contracts/`, `standards/`, `activity/`, `sharing/`, and `export/` packages
- Frontend project workbench: `frontend/src/lib/api/projects.ts`, `frontend/src/ide/IdeProject*.tsx`, `frontend/src/ide/IdeProjectMore.tsx`
- Canonical product contract: `../executespec-docs/backend/features/project-workbench.md`

## 3. BYOK findings

### P0: missing `RunKeyStore` state becomes `SYSTEM_ONLY`

`RunKeyStore.get()` returns `ResolvedKeys.SYSTEM_ONLY` when both Caffeine and Redis miss. This is explicitly documented as a graceful platform fallback in `RunKeyStore.java` lines 74–92.

Impact:

- Redis expiry, outage, decryption failure, cross-JVM resume, or cache eviction can convert an Enterprise run into a global-key run.
- This violates both cost ownership and data-boundary expectations.

Required fix:

- Persist key-source mode and allowed-provider policy durably with the run.
- Unknown key context must raise `BYOK_CONTEXT_UNAVAILABLE` for `OWN_KEYS`.
- `SYSTEM_ONLY` may only be created for an explicitly SYSTEM-mode run, never for an unknown run.

### P0: orchestrator catches BYOK resolution errors and continues

`RunOrchestrator` resolves per-run keys, but its exception handler is explicitly non-fatal and says it “falls through to platform key dispatch.”

Impact:

- Database, encryption, or repository failures can make an Enterprise run spend against global credentials.

Required fix:

- Fail the run before quota/provider spend when key-source mode is `OWN_KEYS` and resolution fails.
- Persist an auditable terminal reason.

### P0: `RunKeyContextStore` cache miss means platform dispatch

`RunKeyContextStore.get()` returns null for an unknown/evicted run. `LlmRouter` treats null or a missing provider key as `PLATFORM` in lines 355–366.

Impact:

- Long-running, resumed, or background work can lose customer key context.

Required fix:

- Store durable route context or reload it from a run-scoped encrypted store.
- Missing context must be rejected for Enterprise/BYOK requests.

### P1: initial `ModelResolver` failover is not BYOK-aware

`ModelResolver` performs health-based failover using the global YAML failover chain before `LlmRouter` dispatch. It does not receive the run’s available-provider set.

Impact:

- It can select an alternative provider with no customer key.
- `LlmRouter` can then classify the missing key as PLATFORM.

Required fix:

- Pass an immutable allowed-provider set into every resolver call, or make the resolver fail closed when the selected provider is unavailable.

### P1: empty availability map is fail-open

`ModelProviderCandidateService.providerAvailable()` returns true when availability is empty/unknown. If `AvailableProviderResolver` fails and callers pass `Map.of()`, automatic routing can treat all matrix providers as candidates.

Required fix:

- For `OWN_KEYS`, empty availability means zero eligible providers.
- Keep “unknown” distinct from “available.”

### P1: Enterprise model overrides are sparse, not provider policy

`OrgModelOverrideService` intentionally stores sparse `(provider:tier) -> model` cells. Unset cells fall back to YAML.

Impact:

- Selecting DeepSeek and overriding DeepSeek cells does not disable Anthropic/OpenAI/Gemini.
- If those providers have keys, they remain eligible.

Required fix:

- Add an explicit immutable `allowedProviders` policy.
- Model overrides must be validated against that policy.
- Provider selection must not be inferred from a sparse model matrix.

### P1: project provider resolution ignores Enterprise provider policy

`ProjectProviderResolver` resolves:

1. `project.aiProvider`
2. creator’s account preference
3. Anthropic default

It does not consult organisation key-source mode, `enabledProviders`, or Enterprise route policy.

Therefore a project can resolve to a provider that has no customer key and later fall through to a platform key.

### P0: project feature LLM calls use synthetic IDs with no key context

Project architecture, docs, chat, and memory compaction use synthetic identifiers such as:

- `combined-arch-{projectId}`
- `project-doc-{type}-{projectId}`
- `chat-{projectId}`
- `chat-compile-{projectId}`
- `chat-compact-{projectId}`
- `compact-{projectId}`
- `project-brief-{projectId}`
- `file-purpose-{filePath}`
- `code-review-{scanId}`
- `pr-review-{reviewId}`

Those identifiers are not registered in `RunKeyContextStore`. The router therefore has no customer key context and can use platform credentials.

Required fix:

- Introduce a first-class `ProjectExecutionContext` containing project, org, user, key-source mode, allowed providers, and route policy.
- Resolve it before every project-scoped LLM operation.
- Do not overload synthetic run IDs as a security boundary.

### P0: embeddings bypass the entire model/key policy

`project/rag/EmbeddingService.java` directly calls OpenAI `text-embedding-3-small` using `spring.ai.openai.api-key`.

Impact:

- DeepSeek-only Enterprise still requires a global OpenAI key.
- Embedding spend is outside the canonical provider/cost ledger.
- Uploaded documents and source code leave the organisation through a provider not selected by the Enterprise admin.

Required fix:

- Decide whether embeddings are a separately governed platform service or customer-routed capability.
- In either case, expose provider, model, cost, retention, and data-residency policy explicitly.
- Remove direct REST provider calls from project features.

## 4. Project feature findings

### 4.1 Project CRUD and membership

Status: functional but role model is too broad.

`ProjectAccessGuard.assertCanWrite()` allows same-org callers without checking project role. Cross-org viewers are restricted, but host-org viewers are not equivalently restricted.

Required fix: resolve effective project role for all callers, including host-org members.

### 4.2 Repository linking

Status: GitHub-first, not provider-neutral.

`ProjectRepoController`, frontend repo picker, browse, sync, architecture, and PR paths all use GitHub-specific contracts. `BitbucketRepoService` exists but is not integrated into the project workbench lifecycle.

Required fix: SCM adapter interface covering discovery, attach, sync, branch/HEAD, file browse, clone, PR, webhook, and post-merge events.

### 4.3 Local folder sources

Status: safe admission design, intentionally not full project parity.

Path-free registration, installation identity, heartbeat, and ownership checks are good. Local sources are correctly rejected from cloud runs. However, local sources cannot currently participate in cloud architecture, RAG, or browse paths.

The UI must clearly label LOCAL as “local-hands project source,” not as a fully cloud-readable repository.

### 4.4 Codemap and architecture

Status: strong functional pipeline, weak job semantics.

The three-step pipeline and SHA reuse are sound. Problems remain:

- GitHub-only source acquisition.
- Synchronous user request for a potentially multi-minute operation.
- Partial artifacts if one repo fails after other repos complete.
- No durable job/cancel/resume contract.
- Heuristic relationship graph rather than language-aware graph.

### 4.5 Generated docs

Status: functional, but synthetic-context BYOK gap.

Caching by architecture version and source fingerprint is good. Provider selection is project-aware, but key context is not, so generated docs can use platform credentials.

### 4.6 Uploaded-document RAG

Status: P0 policy inconsistency.

OpenAI embeddings are hardcoded, indexing is fire-and-forget, duplicate filenames can overwrite workspace files, and there is no durable retry/job status.

### 4.7 Semantic code search

Status: useful prototype, not large-repository grade.

It clones GitHub repos, limits indexing to 4,000 files and 256 KB per file, skips many directories, and lazily indexes in the background.

The UI must show:

- index status
- indexed file count
- skipped/truncated count
- source SHA
- embedding provider/model
- freshness

Without this, “semantic search” can appear authoritative while silently covering only a subset.

### 4.8 Project memory

Status: conceptually strong, weak provenance.

Run ledger entries use real run IDs and are closer to correct BYOK attribution. Memory compaction uses synthetic project IDs and can use global keys.

There is no user-facing correction/deletion mechanism for an incorrect memory fact, and cross-org collaborators may inherit the same memory without sensitivity controls.

### 4.9 Project chat

Status: highest-risk project feature.

Problems:

- Synthetic context IDs bypass run key context.
- Chat send/compile/compact/clear are not consistently project-role gated for host-org users.
- Chat tools can trigger paid architecture/doc/review operations.
- Provider/model chip describes resolution but not actual key source.
- No unified project-level spend ledger for all chat rounds and tool calls.

Required fix: project-scoped route context, role-aware mutation policy, explicit spend proposal for paid tools, and durable provider/key evidence per turn.

### 4.10 Code review

Status: contains a confirmed provider and authorization defect.

`CodeReviewService` hardcodes `.provider("anthropic")` when constructing the agent request.

Additionally, `POST /api/code-reviews` validates tier/quota but does not call `projectAccessGuard.assertCanWrite()` before submitting a scan. The guard is used for later autofix/read paths, not initial submission.

Impact:

- Wrong provider for a project.
- Enterprise BYOK policy bypass.
- Potential cross-project scan submission and quota/cost abuse.

### 4.11 PR review

Status: provider resolution exists, but initial submission has the same guard concern.

`PrReviewService` resolves the project provider, but the actual LLM call uses synthetic `pr-review-{reviewId}` context with no key context.

`POST /api/pr-reviews` validates tier/quota but does not assert project access before creating the review.

Required fix: guard project access before queueing, bind project route context to the review job, and persist the route policy.

### 4.12 Tech-debt rollup/autofix

Status: useful but not deterministic enough for enterprise remediation.

The fix prompt caps at 30 issues, merges historical reports, and deduplicates only by file/description. There is no immutable issue snapshot or per-finding completion state.

Required fix: create a remediation batch with source report IDs, source SHAs, exact findings, selected repos, provider/model policy, and completion ledger.

### 4.13 Contracts and standards

Status: enterprise scaffolding, not always-on enforcement.

The canonical docs state planner injection and enforcement remain flag-gated. These features must not be presented as hard compliance controls until the flags, rule versions, source SHAs, and exception approvals are durable and auditable.

### 4.14 Sharing

Status: secure baseline, enterprise controls incomplete.

Public links correctly scope payloads to projects and support expiry/revocation. Remaining gaps are rate limits, access audit, viewer identity, password-protected links, and stronger redaction controls.

### 4.15 Export

Status: not suitable for large projects.

Export is synchronous and explicitly intended only for a few MB. It lacks async jobs, resumable downloads, object storage, checksums, and a sensitive-data manifest.

### 4.16 Dashboard, scores, tech debt, activity

Status: useful at small scale, insufficiently bounded.

Scores and tech-debt APIs load broad project histories and filter/aggregate in application code. Activity is better because it uses windows and limits, but some filtering remains in memory after fetching a bounded page.

Required fix: database aggregation, cursor pagination, source-SHA filters, and explicit deterministic-vs-LLM evidence labels.

## 5. Test coverage found

Existing tests cover many normal paths:

- `AvailableProviderResolverTest`
- `ModelResolverAdaptiveOptInTest`
- `RunRoutePolicyServiceTest`
- `GoalContinuationPolicyTest`
- `LlmRouterTest`
- `ProjectProviderResolverTest`
- `ProjectAccessGuardTest`
- `ProjectChatControllerProjectMemberTest`
- `ProjectArchitectureServiceFingerprintTest`
- `ProjectDocGeneratorServiceCacheHitTest`
- `MemoryCompactionServiceTest`
- `CodeIndexingServiceTest`
- `ProjectSharePublicControllerTest`
- `ProjectRepoServiceRunResolutionTest`

Focused project tests completed without reported test failures during this audit.

Important missing tests:

1. Enterprise project chat with global keys present and only one BYOK provider.
2. Project architecture/doc/memory compaction after key-context loss.
3. Redis miss/decryption failure for an `OWN_KEYS` project operation.
4. Empty provider-availability map under `OWN_KEYS`.
5. ModelResolver failover to an unkeyed provider.
6. Code-review submit by a user from another project/org.
7. PR-review submit by a user from another project/org.
8. Host-org `PROJECT_VIEWER` attempting project mutations.
9. Embedding calls under DeepSeek-only Enterprise with no OpenAI key.
10. Bitbucket project attach → browse → architecture → PR lifecycle.
11. Large project export and large dashboard history.
12. Partial architecture generation and restart/resume.

`AccessGuardCoverageTest` does not cover `/api/code-reviews` or `/api/pr-reviews` because those paths are not in its protected-segment list. This is why the missing submit-time guards are not caught by the architectural test.

## 6. Release gates before Enterprise project claim

### Mandatory P0 gates

- No project-scoped LLM call may use a synthetic ID as its only routing context.
- `OWN_KEYS` key-resolution failure, cache miss, or Redis miss must fail closed.
- RAG embeddings must have an explicit Enterprise policy and cost attribution.
- Code-review and PR-review submit endpoints must assert project write access before queueing.
- Code-review provider must come from project/Enterprise policy, not a hardcoded Anthropic string.

### Mandatory P1 gates

- Persist allowed providers/models with every project job.
- Make project role enforcement consistent for host-org and external members.
- Add durable jobs for architecture, indexing, docs, export, and autofix.
- Add Bitbucket Cloud adapter coverage across the complete project lifecycle.
- Bound dashboard, score, and tech-debt queries at the database layer.
- Add source SHA, provider, model, key source, and cost evidence to every project artifact.

## 7. Recommended implementation order

1. Build `ProjectExecutionContext` and route every project LLM operation through it.
2. Make BYOK context durable and fail-closed.
3. Add project-policy-aware provider/model eligibility to initial routing and failover.
4. Fix code-review/PR-review submit authorization and provider selection.
5. Decide and implement embedding governance.
6. Enforce project roles uniformly.
7. Add durable project jobs and resumable states.
8. Add Bitbucket end-to-end adapter and tests.
9. Add large-project query/export/indexing controls.
10. Repeat adversarial tests with global keys intentionally present.

## Bottom line

The project workbench has strong breadth and several good reliability primitives, but Enterprise BYOK isolation is not complete outside the normal run path. The most serious issue is that project-level background features select the right provider name but do not carry the customer’s key-source context into `LlmRouter`. As a result, global credentials can still be used for chat, architecture, docs, memory compaction, embeddings, and review paths.

Until the P0 gates are closed, the correct product claim is:

> “Enterprise BYOK is supported for the primary run pipeline; project intelligence and review features are not yet proven to be policy-isolated.”

## 8. Deep-pass addendum (2026-07-17)

The second pass inspected every project-scoped `LlmRouter.call` / `callText` entry point and the submission controllers for code review and PR review.

### Additional confirmed routing defects

#### P0: code-review worker hardcodes Anthropic

`codereview/CodeReviewService.java` constructs the agent request with `.provider("anthropic")` at lines 293–304, even though the project has a configured provider and `CodeReviewAgent` supports provider resolution.

Impact:

- Enterprise project selection is ignored for whole-repository code review.
- A DeepSeek-only or OpenRouter-only project is still sent to Anthropic.
- The provider name is not merely a default; it is injected by the orchestration service.

#### P0: code-review submission does not guard the project before queueing

`POST /api/code-reviews` validates authentication, tier, and quota, but does not call `ProjectAccessGuard.assertCanWrite()` before `CodeReviewService.submitScan()`.

The request accepts both `projectId` and arbitrary `repoFullName`. The service does not require that the repo is linked to the project before cloning/scanning.

Impact:

- A user who knows a project ID can submit a scan against an arbitrary GitHub repository visible to their PAT.
- The scan is persisted under the target project and consumes quota/LLM spend.
- `AccessGuardCoverageTest` does not catch this because `/api/code-reviews` is not included in its protected URL segments.

Required fix:

- Guard project write access before quota/queueing.
- Require the repository ID to be linked to the project and verify its full name matches the request.
- Add `/code-reviews` and `/pr-reviews` to the architecture guard coverage contract.

#### P0: PR-review submission has the same admission gap

`POST /api/pr-reviews` validates tier/quota and fetches any GitHub PR identified by `repoFullName` + number, but does not assert project access before creating the row.

Impact:

- Arbitrary PRs can be attached to another project ID.
- Project dashboards can contain reviews that were never authorized by the project.
- The review job later uses a synthetic `pr-review-{reviewId}` context with no BYOK key context.

#### P1: legacy run-codemap summary hardcodes provider and model

`codeintel/RunCodeMapService.java` calls:

```java
llmRouter.callText("anthropic", anthropicSummaryModel, ...)
```

The model comes from `spring.ai.anthropic.chat.options.model` rather than `ModelResolver`/the active run route.

Impact:

- A project run using DeepSeek, OpenAI, or Enterprise model overrides can still send codemap summaries to Anthropic.
- This path is especially easy to miss because it is a “secondary” context-generation call.

Required fix:

- Resolve the summary provider/model from the run’s immutable route policy.
- Attribute the call to the same run key context and cost ledger.

### Additional project-context evidence

The following project features use synthetic contexts that are not registered in `RunKeyContextStore`:

| Feature | Synthetic context |
| --- | --- |
| Project chat | `chat-{projectId}` |
| Chat compile | `chat-compile-{projectId}` |
| Chat compaction | `chat-compact-{projectId}` |
| Memory compaction | `compact-{projectId}` |
| Architecture synthesis | `combined-arch-{projectId}` |
| Generated docs | `project-doc-{type}-{projectId}` |
| Project brief | `project-brief-{projectId}` |
| File-purpose summary | `file-purpose-{filePath}` |
| PR review | `pr-review-{reviewId}` |
| Code review | `code-review-{scanId}` |

The current unit tests assert these synthetic IDs, but do not assert that they carry the correct organisation, key-source mode, allowed providers, or provider/model pin.

### Test-quality conclusion

The current tests provide good normal-path coverage but encode the current synthetic-context design as expected behaviour. They do not prove Enterprise isolation. The missing tests listed in Section 5 should be treated as release blockers, not optional hardening.

## 9. Chat-action and capability-boundary deep pass (2026-07-17)

### 9.1 Chat action tools are an unguarded mutation/spend boundary

`memory/chattools/ProjectChatTools.java` binds `regenerate_architecture`, `generate_project_doc`, and `run_code_review` into every project-chat turn. The tool callbacks receive only `projectId`, `userId`, and a PAT; they do not receive or enforce a project-role/capability decision.

- `regenerate_architecture` directly calls `ProjectArchitectureService.generate(...)`. Its tool description explicitly says it opens a PR per linked repo, so one chat turn can trigger repository writes and several LLM calls.
- `generate_project_doc` directly calls `ProjectDocGeneratorService.generate(...)`, creating versioned artefacts and spending tokens.
- `run_code_review` checks tier/quota and confirms the named GitHub repo is linked, but it does not perform a project-role check. The outer controller only checks org/member + tier and does not distinguish viewer/read-only members from writers.

This is a server-side authorization gap, not merely a missing UI confirmation. Any surface using project chat can invoke these callbacks. The product needs capability-scoped tools (`READ`, `ANALYSE`, `WRITE_REPO`, `SPEND_AI`) and an explicit confirmation gate for PR-producing architecture regeneration and paid scans. Read-only project members must not be able to cause these actions through chat.

### 9.2 Project chat access is broader than project write access

`ProjectChatController.requireChatAccess` admits same-org users based on org membership and cross-org users based on a `ProjectMember` row plus tier. It does not resolve an active project role for send/compile/clear/compact, and the tool context does not carry the member role. `ProjectAccessGuard` separately allows same-org writes broadly. There is therefore no single enforceable answer to “can this user ask the agent to mutate or spend against this project?”

Required fix: resolve an effective project capability at admission (`PROJECT_VIEWER`, `PROJECT_DEVELOPER`, `PROJECT_ADMIN`, org-admin/super-admin), carry it through the chat/session context, and enforce it again inside every tool callback/service. Do not rely on the web UI hiding an action.

### 9.3 Architecture regeneration does not carry provider/key policy from chat

`ProjectChatService.send` resolves the project provider and creates a synthetic execution id (`chat-{projectId}`), then binds project tools. The `regenerate_architecture` callback calls the three-argument `ProjectArchitectureService.generate(projectId, branch, pat)` overload; it does not pass the caller's Enterprise key-source context or a provider pin. The architecture path therefore inherits the global-default/system-key fallback risks already described, while also exposing a PR-producing side effect without spend confirmation.
## 10. SCM/provider parity finding

Bitbucket is not a normal project source today, despite a standalone `repo/BitbucketRepoService.java`.

- `project/dto/CreateRepoRequest` accepts only `githubRepoFullName`.
- `ProjectRepo` still persists `githubRepoFullName`/`githubOwner` as the primary identity; `scmProvider` and neutral `sourceKey` are additive fields but are not used by the project attach flow.
- `ProjectRepoService.createRepo` constructs `https://github.com/...`, calls `GitHubRepoService` for branch, size, monorepo marker, SHA, file inventory, and stack detection, and stores GitHub-specific values.
- The project workbench, architecture source acquisition, repo browser, sync, webhook, PR record, and review surfaces therefore remain GitHub-shaped. The Bitbucket service is currently useful for pipeline-run plumbing, not for the normal project lifecycle.

This is a product capability gap, not merely missing UI. A CommerceIQ-style Bitbucket Cloud project cannot reliably be attached, indexed, regenerated, reviewed, or pushed through the same project feature set. Before claiming SCM parity, introduce a provider-neutral `ScmRepositoryRef`/adapter boundary and make every project operation dispatch through it; add Cloud Bitbucket attach/read/branch/SHA/diff/PR/webhook coverage before enabling the UI.

## 11. Token-measurement deep dive (2026-07-17)

### 11.1 Confirmed strengths

- Successful canonical-router calls are recorded in both the in-memory `RunCostTracker` and durable `run_step_usage`.
- The ledger carries provider, model, task, role, step, retry lineage, input/output, cache read/write, reasoning, cost, and call index.
- Native Gemini parsing captures cached-content and thinking-token metadata.
- Run-level cost evidence exposes `stepCalls`, attributed cost, unattributed cost, and a reconciliation flag.
- Atomic SQL `addTokens()` avoids ordinary read-modify-write races when phase attribution persists totals.

### 11.2 P1: OpenAI/DeepSeek reasoning tokens are not extracted

`OpenAiNativeToolClient` parses `prompt_tokens`, `completion_tokens`, cache tokens, and provider cost, but does not parse `usage.completion_tokens_details.reasoning_tokens` (or the equivalent DeepSeek field) into the `reasoning_tokens` metadata key. `RunCostTracker` supports that key, but the OpenAI-wire client never supplies it. Therefore DeepSeek/OpenAI reasoning usage is reported as zero even when the provider returns it. Visible output tokens remain present, so this is primarily an observability and model-quality measurement defect, but it also makes cross-model cost/quality comparisons misleading.

### 11.3 P1: failed provider attempts are absent from the usage ledger

`LlmRouter` records usage only after `dispatch(...)` returns a successful `ChatResponse`. Transient failures, timeouts, rate-limit responses, and failed provider attempts that may still incur upstream billing are not represented in `run_step_usage` or `RunCostTracker`. A later retry/failover can therefore show less provider spend than was actually incurred, and the cost evidence cannot explain failed attempts.

### 11.4 P1: planner phase attribution includes earlier calls

`RunOrchestrator` calls `costTracker.snapshot(runId)` after planning and writes that absolute snapshot into the planner phase via `attributePhase`. The snapshot already includes classifier and any pre-planner context calls. The classifier is persisted separately by `RunPlanningCoordinator`, but the planner phase columns therefore include classifier/context tokens as well. Run totals are less affected because the atomic total is incremented from this same aggregate, but phase-level reports and provider attribution are incorrect.

Required fix: capture a phase baseline immediately before each phase and attribute only the delta. Do not infer phase boundaries from one cumulative snapshot.

### 11.5 P1: integer narrowing can overflow long runs

`RunCostTracker` stores counters as `AtomicLong`, but `Snapshot`, `CallUsage`, `RunTokenAttributor`, and `PipelineRunRepository.addTokens(...)` narrow token counts and deltas to `int`. A large repository, long multi-task run, or repeated continuation can exceed the signed 32-bit range and produce negative or wrapped totals. Database entity fields are wider, but the aggregation API is not.

### 11.6 P1: project/background calls are not reconciled into run cost

Project chat, architecture, document generation, memory, RAG embeddings, code review, and PR review use synthetic run IDs or direct provider calls. Their calls either do not enter the run ledger at all or are not associated with the customer project’s durable cost record. This leaves a gap between actual provider spend and the run/project cost evidence, even where the provider call itself succeeds.

### 11.7 Verification note

The focused token-accounting test command was attempted, but Maven compilation is currently blocked by an unrelated existing error: `benchmark/ProviderHandoffFixtureHarness.java:360` references `File` without a resolvable symbol/import. No token-accounting test result can therefore be treated as a fresh green build until that compile blocker is repaired.

### 11.8 P2: durable usage writes fail open

`RunStepUsageRecorder.record` catches every persistence exception and logs only at debug level. A database outage or schema mismatch can silently erase the per-call audit ledger while the in-memory/run-total path continues. The API can then report a misleadingly large `unattributedCostUsd` without an operational alert. Usage persistence should use an outbox/retry path or at minimum emit a metric and durable warning when a row cannot be written.

## 12. Required token audit report

The current `/api/admin/analytics` surfaces do not satisfy the requested audit. They provide run counts, aggregate cost, a model cost map, and a coarse user/project cost breakdown, but not a reconciled token ledger by user × provider × model × role × step.

### 12.1 Required dimensions

Every billable LLM call should be queryable by:

`orgId`, `userId`, `userEmail`, `projectId`, `runId/sessionId`, `sourceType`, `feature`, `agentRole`, `stepId`, `provider`, `model`, `modelClass/tier`, `attempt/retryOf`, `startedAt`, and `keySource` (SYSTEM / BYOK / HYBRID).

### 12.2 Required measures

For each row and every roll-up: input tokens, output tokens, cache-read tokens, cache-write tokens, reasoning tokens, total tokens, raw USD cost, managed-AI credits, call count, failed-call count, retry count, and reconciliation status.

### 12.3 Roll-ups the product needs

1. User summary: one row per user with total tokens/cost and provider/model mix.
2. Provider/model matrix: user × provider × model with input/output/reasoning/cache/cost.
3. Role summary: user × agent role (`WorkerAgent`, `ReviewerAgent`, `ProjectChatAgent`, etc.).
4. Feature summary: runs, chat, architecture, documents, memory, RAG, code review, PR review, and background jobs.
5. Project summary: project and repository cost centres.
6. Run/session drill-down: ordered calls with retries, failovers, route changes, and failed attempts.
7. BYOK/system split: customer-key spend versus platform-key spend, with policy violations surfaced as hard errors.

### 12.4 Current data gaps blocking this report

- `run_step_usage` has role/provider/model/tokens but no direct `userId`, `orgId`, `projectId`, source type, or key source; it requires joins and cannot cover synthetic project calls reliably.
- `agent_usage_ledger` has user/org/source/provider/model/tokens but no agent role or step.
- `managed_ai_usage_event` has user/org/role/step and credits, but only represents managed-AI accounting, not every BYOK/system call.
- `CodeReviewScan` and `PrReview` store aggregate tokens but do not expose a uniform per-call ledger.
- Existing `costByModel` is cost-only and is based on `model_task_metric`, not the authoritative token ledger.

### 12.5 Recommended implementation

Create one append-only `llm_usage_event` table as the canonical audit fact, emitted by `LlmRouter` for successful and failed attempts. Keep the current tables as compatibility projections, but build the admin report exclusively from this event table. Add indexed dimensions for `(org_id, user_id, created_at)`, `(provider, model, agent_role)`, `(run_id, task_id)`, and `(project_id, feature)`. Expose CSV/JSON export with timezone and date-range filters, plus a reconciliation footer comparing event totals with run totals, managed-AI events, and provider invoices where available.

## 13. Project feature gaps and security concerns

This section is the feature-by-feature release audit. Severity is based on customer impact, data exposure, unauthorized spend, or policy bypass—not implementation effort.

### P0 — must fix before Enterprise project rollout

#### 13.1 BYOK/provider isolation is not universal

Project chat, architecture, documents, memory, project briefs, file-purpose summaries, code review, PR review, RAG embeddings, and background jobs do not all inherit one immutable Enterprise route/key context. Several use synthetic IDs or direct provider calls.

Risk:

- customer code or prompts can leave through a global/system provider;
- the selected Enterprise provider can be ignored;
- cost and data-residency reporting becomes false;
- a provider failover can escape the customer-approved matrix.

Required control: create a project execution context containing `orgId`, `projectId`, `userId`, key-source mode, allowed providers/models, route pin, and feature name. Reject any project LLM call without that context.

#### 13.2 Chat can trigger repository writes and paid work without capability confirmation

`ProjectChatTools` exposes architecture regeneration, document generation, and code-review launch inside the same tool list as read-only queries. Architecture regeneration can open PRs. The tool context has no project role or spend capability.

Risk:

- `PROJECT_VIEWER` or external members may trigger mutations;
- a prompt-injection inside repository content could cause an expensive action;
- a conversational misunderstanding can open PRs or consume credits.

Required control: separate `READ`, `ANALYSE`, `SPEND_AI`, and `WRITE_REPO` capabilities; require explicit confirmation for paid scans and all PR-producing actions; re-check authorization inside the service, not only in the UI.

#### 13.3 Code Review and PR Review project admission is incomplete

The submit endpoints validate authentication/tier/quota but do not consistently enforce project write access and repository linkage before queueing work. `AccessGuardCoverageTest` does not cover these URL families.

Risk:

- arbitrary repositories or PRs can be attached to another project;
- users can consume another project’s quota or pollute its reports;
- cross-tenant metadata and review results can be exposed.

Required control: require a linked `repoId`, verify provider/full-name/tenant ownership, enforce project role, and add `/code-reviews` and `/pr-reviews` to architectural guard coverage.

#### 13.4 RAG embedding path bypasses provider and cost policy

`project/rag/EmbeddingService` directly calls OpenAI embeddings using the global OpenAI key/model.

Risk:

- DeepSeek-only or customer-key-only organisations still send source documents to OpenAI;
- embedding spend is absent from BYOK and project cost ledgers;
- data residency and approved-provider guarantees are violated.

Required control: make embeddings an explicit, policy-approved capability with its own provider/key route, or disable project indexing when the organisation has no approved embedding provider.

### P1 — required for a credible enterprise release

#### 13.5 Project role model is too broad for same-org users

Project chat and portions of `ProjectAccessGuard` treat same-org membership as sufficient, rather than consistently applying `PROJECT_VIEWER`, `PROJECT_DEVELOPER`, and `PROJECT_ADMIN` capabilities.

Risk: least-privilege controls are ineffective inside the customer organisation.

Required control: resolve effective project role for every project operation, including chat, compile, clear, compact, document upload, architecture regeneration, reviews, contracts, standards, exports, and member management.

#### 13.6 Bitbucket is not project-workbench complete

The normal project attach path still accepts GitHub-shaped identifiers and calls GitHub APIs for branch, SHA, size, inventory, architecture, webhooks, and PRs. The standalone Bitbucket service does not establish project-level parity.

Risk: CommerceIQ-style Bitbucket projects cannot receive the same guarantees as GitHub projects and may fall into unsupported or partially-working paths.

Required control: introduce a provider-neutral SCM adapter and test attach, clone, branch, SHA, inventory, diff, webhook, review, and PR flows for Bitbucket Cloud.

#### 13.7 Review/model routing is inconsistent

Code Review hardcodes Anthropic in one path. Legacy run codemap summary also hardcodes Anthropic/model configuration. Other project features resolve provider from project/default preference without enforcing the Enterprise allowed-provider set.

Risk: model selection shown to the user does not match actual provider usage; BYOK and data-governance promises are unreliable.

Required control: route every project feature through `ModelResolver` plus a project/org route policy and persist actual provider/model per call.

#### 13.8 Project background jobs are not durable or restart-safe

Architecture generation, document generation, RAG indexing, code indexing, reviews, and memory compaction are asynchronous or multi-step, but several paths lack a durable job state machine, cancellation, lease ownership, and resumable checkpoints.

Risk:

- duplicate LLM spend after retries/restarts;
- half-written or stale artifacts;
- jobs continue after project membership or key policy changes;
- operators cannot determine whether a result is complete.

Required control: persist job identity, route snapshot, owner, state, attempt, cost, heartbeat, cancellation, and output version; make retries idempotent.

#### 13.9 Project exports and shared links need stronger governance

Exports are synchronous and can contain broad project artifacts. Public share links are bearer credentials and need stronger lifecycle and monitoring controls.

Risk:

- large exports time out or exhaust server memory;
- leaked links expose code intelligence, reports, or member metadata;
- no clear download audit, expiration enforcement, or bulk-revocation workflow.

Required control: asynchronous object-backed exports, size limits, redaction policy, mandatory expiry, revocation, access logs, rate limiting, and anomaly detection.

#### 13.10 RAG/document ingestion has integrity and abuse risks

Document ingestion is asynchronous and filename sanitisation can still create collisions. Uploaded documents and extracted text become prompt context without a visible provenance/approval trail.

Risk:

- one document can overwrite another;
- prompt injection can influence agent actions;
- malformed or oversized files can create CPU, memory, or embedding-cost abuse.

Required control: immutable document IDs, collision-proof storage, MIME/content validation, decompression and page/token limits, malware scanning, provenance labels, prompt-injection isolation, and per-project quotas.

#### 13.11 Repository content can influence privileged agent actions

Architecture, chat, code review, and document generation feed repository text into LLM prompts. Repository instructions can attempt to override system policy or induce PR creation, secret access, or shell execution.

Required control: mark repository content untrusted, isolate it from control instructions, use structured context delimiters, enforce tool capabilities independently, and require confirmation for all external side effects.

### P2 — important hardening and operability

#### 13.12 Resource and query bounds are inconsistent

Dashboard, activity, memory, score, tech-debt, document, and review endpoints can aggregate broad project history. Large repositories and long-lived projects can create unbounded DB, embedding, and response costs.

Required control: pagination, maximum date ranges, bounded result sizes, async aggregation, query timeouts, and per-project retention policies.

#### 13.13 Tenant and project identifiers need systematic negative testing

Existing access-guard coverage focuses on selected URL patterns and does not guarantee every project-adjacent controller or tool callback is covered. Direct service invocation and chat-tool dispatch can bypass controller checks.

Required control: generated endpoint inventory, ArchUnit/service-level authorization tests, cross-org and viewer-negative tests, and authorization checks at both controller and service/tool boundaries.

#### 13.14 Audit evidence is incomplete

Activity events, run cost, managed-AI events, project actions, reviews, exports, share-link access, and source changes are not yet one immutable, queryable audit stream.

Required control: append-only audit events with actor, effective role, project, action, target, provider/model, key source, result, correlation ID, and before/after references.

#### 13.15 Secrets and PAT lifecycle need a unified policy

Project operations thread caller PATs into repository services, while provider keys and PATs have different storage, masking, rotation, and revocation paths.

Required control: encrypt all credentials at rest, never include them in URLs/logs/workspaces, support rotation/revocation propagation, and record secret-use audit events without storing secret material.

### Release gate

Do not claim “Enterprise-ready project workbench” until all P0 items are closed, P1 items have implemented controls plus negative tests, and the following evidence exists:

1. one-provider Enterprise isolation test across every project feature;
2. cross-org and project-viewer authorization matrix;
3. Bitbucket Cloud end-to-end project test;
4. restart/retry/idempotency test for every background project job;
5. user × provider × model × role × feature token reconciliation report;
6. prompt-injection and malicious-repository adversarial test set;
7. export/share-link abuse and revocation test set.

## 14. Project-chat guardrail assessment

### 14.1 Current controls that are already present

The project chat is not unprotected. It currently has:

- a fixed six-round manual tool loop (`MAX_TOOL_ROUNDS = 6`);
- duplicate-read detection and a forced final-answer turn;
- exact-signature caching for repeated read tools;
- a bounded tool list instead of arbitrary tool-name execution;
- no direct settings, provider-key, billing, run-approval, shell, or git-commit tools;
- project-memory, architecture, and retrieved-document blocks wrapped with `PromptTrustBoundary`;
- tool JSON envelopes that contain `ok` and bounded error messages;
- a system instruction to treat project context as evidence and not follow instructions inside it;
- separate read-oriented search tools rather than unrestricted workspace shell access;
- global input/code/shell/file guardrail infrastructure elsewhere in the run pipeline.

These controls reduce accidental loops and some basic prompt-injection cases. They are useful hygiene, but they are not a complete agent safety boundary.

### 14.2 P0/P1 missing controls

#### A. User chat input is not passed through `InputSanitizer`

`ProjectChatService.send` persists and forwards `userText` directly. The strong prompt-injection, Unicode-normalisation, base64, abuse, and payload-size checks in `InputSanitizer` are used by run submission paths, not this chat path.

Impact: chat can become an easier injection and token-stuffing entry point than normal run submission.

#### B. Repository/document context is framed but not semantically isolated

`PromptTrustBoundary` adds delimiters and instructions, but it does not classify, strip, quarantine, or score malicious content. Prior user/assistant history is added as ordinary messages, and the project name is interpolated into the system prompt without an explicit trust boundary.

Impact: a malicious README, generated document, memory entry, or earlier assistant response can still influence tool selection. Delimiters are prompt hygiene, not authorization.

#### C. No capability-based tool authorization

All project-chat tools are bound together. `ToolContext` contains only `projectId`, `userId`, and PAT—not effective project role, allowed capabilities, provider policy, spend budget, or confirmation state.

Impact: read-only users and external members may reach analysis, paid, or repository-writing tools through model tool calls. This is the most important gap.

#### D. No confirmation gate for side effects or spend

`regenerate_architecture` can open PRs; `run_code_review` consumes paid quota; document generation spends LLM tokens. The model can call these tools based on inferred intent without a separate user confirmation event.

Impact: prompt injection, ambiguous language, or model error can create external side effects or unexpected spend.

#### E. No per-turn spend/token/tool budget

Six rounds limits loop length, but each round can contain multiple calls and 2,048 output tokens. There is no hard per-turn token ceiling, USD ceiling, provider-call count ceiling, or project-chat daily budget enforced at the tool boundary.

Impact: a normal-looking request can burn the full loop budget repeatedly, especially with large retrieved context and expensive planning-tier models.

#### F. Chat output is not passed through secret redaction

`ContextSanitizer` contains secret-output patterns, but `ProjectChatService` saves and returns `assistantText` directly. The chat path therefore lacks the output redaction used by other context-aware paths.

Impact: repository secrets, credentials, PAT-like strings, or sensitive configuration can be echoed into chat history and browser responses.

#### G. Guardrail configuration fails open

`GuardrailLevelResolver` falls back to `LOW` if the feature flag cannot be read. That prioritises availability over safety during Redis/configuration failures, exactly when an operator may need the stricter posture.

Impact: an outage can silently downgrade protection without a hard alert or request-level evidence.

#### H. Tool argument and target validation is inconsistent

Tool schemas describe expected inputs, but callbacks still parse raw JSON and several service methods do not independently enforce project role, linked-repository ownership, provider policy, or action scope.

Impact: the model can supply a validly-shaped but unauthorized target; schema validity is not authorization.

#### I. Tool results can become instruction-bearing context

Tool envelopes are appended into the next model turn with no explicit untrusted-result wrapper. A repository search result, generated document, review finding, or error text can contain instructions that the model treats as authoritative.

Impact: indirect prompt injection can occur after a tool call, even if the original user message was clean.

### 14.3 Current maturity estimate

For a consumer-grade read-only project assistant: approximately **55–60%** of the necessary baseline exists (loop controls, limited tools, trust framing, and no shell/settings access).

For an Enterprise agent allowed to analyse repositories, spend credits, and open PRs: approximately **30–35%** is complete. The missing capability authorization, confirmation, spend controls, output redaction, and BYOK route binding are fundamental—not polish.

### 14.4 Required guardrail architecture

Introduce a `ProjectChatPolicy` resolved before every turn and every tool call:

`actor + org + project role + source trust + allowed providers/models + key source + capabilities + spend budget + confirmation state`.

Tool classes should declare capabilities, for example:

| Tool class | Examples | Required capability |
| --- | --- | --- |
| Read | list repos, architecture, memory, search | `PROJECT_READ` |
| Analyse | code indexing, tech-debt scan, architecture analysis | `PROJECT_ANALYSE` + budget |
| Spend | code review, document generation, embeddings | `SPEND_AI` + budget |
| External write | architecture PR, PR review posting | `WRITE_REPO` + explicit confirmation |

Every tool invocation should re-check the policy, record a decision event, and carry the immutable route/key context into `LlmRouter`.

### 14.5 Guardrail test suite required

Add adversarial tests for:

1. user prompt injection and Unicode/base64 variants;
2. malicious README, document, memory, search result, and tool-error content;
3. viewer/external-member attempts to invoke paid or write tools;
4. duplicate and recursive tool calls;
5. token/USD budget exhaustion;
6. secret output redaction;
7. provider/key-policy violations and failover attempts;
8. Redis/feature-flag failure without silent guardrail downgrade;
9. cross-project and unlinked-repository targets;
10. confirmation replay, expiry, and action substitution.
