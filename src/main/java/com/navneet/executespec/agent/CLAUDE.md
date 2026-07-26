# `agent/` — LLM agents + routing

## Prompt authority boundary (2026-07-18)

`PromptTrustBoundary` is the shared repository/project evidence wrapper. It normalizes Unicode
compatibility forms, removes zero-width delimiter tricks, strips cross-label delimiters, and
neutralizes Base64-encoded boundary closers. Repository, document, memory, report, build, diff,
tool-derived, and user-controlled payloads must remain `UserMessage`/tool evidence; only static
role/tool/output policy belongs in `SystemMessage`. Legacy post-run conversation and suggestion-plan
paths now follow that separation as well.

> Last reviewed: 2026-07-13 (ExecuteSpec 2.0 release track).

All LLM-calling agents + the routing infrastructure for the V2 pipeline.

## Key files

| File | Purpose |
|------|---------|
| `LlmRouter.java` | Canonical native-tool-calling dispatcher. Routes Anthropic via Spring AI, Gemini via `GeminiNativeToolClient`, and OpenAI-wire providers through `OpenAiNativeToolClient` (OpenAI, OpenRouter, DeepSeek, Fireworks). Every physical attempt flows through `RunCostTracker` and the V283 canonical attempt ledger; failures are recorded before retry/failover with actual key source, attempt number and bounded error classification. Durable tenant attribution is accepted only as the exact paired `LlmRequest.llmExecutionContextId` + `llmExecutionScope`; never infer it from source/correlation ids. Bounded same-provider retries precede the **graduated multi-provider failover policy (V266)**, which walks the configured primary provider's exact YAML order after transient exhaustion while preserving BYOK boundaries; a provider without a `failover-order` entry has no cross-provider route. V275 exact-lock runs are checked against their durable requested provider/model before authorization and dispatch, force `strictProvider`, reject managed-AI model changes, and cannot enter cross-provider failover. Anthropic Sonnet 5 / Opus 4.7+ calls use cached model-specific clients with no deprecated temperature defaults. |
| `AnthropicModelOptions.java` | Shared Anthropic request-option guard. Pre-skips `temperature` for `claude-sonnet-5`, `claude-opus-4-7`, `claude-opus-4-8`, and `claude-fable-5` model families and recognizes Anthropic's backtick-wrapped "`temperature` is deprecated" 400. Used by `LlmRouter`, system model config, testgen/API-test helpers, and accuracy assessment. |
| `AnthropicForcedToolClient.java` | **(RL-2026-05-059)** Forces an Anthropic tool call for the WorkerAgent Lever-B salvage when `LlmRequest.toolChoice="required"`. Bypasses `AnthropicChatModel` (no `tool_choice` in its options) → single-turn `AnthropicApi` POST with `ToolChoiceAny` + restricted tools (writeFile/editFile/completeTask) over a flattened transcript. Returns `null` on ANY failure → caller falls through, so it can't regress production Anthropic traffic + only runs on an already-failing zero-write salvage. |
| `OpenAiNativeToolClient.java` + `OpenAiMessageSerializer.java` | OpenAI-wire native-tool client — used by OpenAI, OpenRouter, DeepSeek, and Fireworks. The client owns transport, request policy, response parsing, and usage; `OpenAiMessageSerializer` owns Spring AI `Message` → chat-completions wire serialization, including media parts, tool-call ids/results, and DeepSeek `reasoning_content` round-trip. Supports per-call `baseUrl`, `apiKey`, `extraBody`, and read-timeout; all four routes propagate the request deadline, with native DeepSeek falling back to its configured 300s override only when the caller omits one. Output ceilings are provider-aware: native DeepSeek receives its supported `max_tokens` field, while OpenAI and aggregator routes retain `max_completion_tokens`; sending the OpenAI-only field to DeepSeek previously left the requested cap unenforced. GPT-5.6 requests omit unsupported `temperature` on both direct OpenAI and OpenRouter wires. Tool calls returned with an abnormal `finish_reason` are suppressed before dispatch while the response usage remains available for billing. |
| `GeminiNativeToolClient.java` | Gemini native-tool client — schema sanitization + thought-signature round-trip; implicit-cache prefix preserved. The provider-neutral `toolChoice` contract maps `required/none/auto` to Gemini `ANY/NONE/AUTO`, and the exact mode participates in explicit-cache identity/config so one mode cannot control another. Gemini 3.6 Flash / 3.5 Flash-Lite requests omit Google's deprecated sampling parameters and round-trip provider function-call IDs through subsequent function responses. **Explicit context caching** (RL-2026-05-059, flag `gemini.explicit-cache.enabled`, V225, default OFF): mints a `/v1beta/cachedContents` resource for the stable system+tools+toolConfig prefix, references it by name per call + OMITS those from the request body (Gemini 400s if `cachedContent` + system/tools/tool_config are both set) → prefix billed at the cache rate; keyed by SHA-256(model+systemText+toolsJson+toolMode+credentialFingerprint), fail-safe inline fallback on any mint/stale-cache 4xx. Records `cache_read_input_tokens` on every call. |
| `SmartClassifierAgent.java` | (LIGHTWEIGHT) Picks `PlanStrategy ∈ {SINGLE_SHOT, SMALL, MEDIUM, LARGE, LEGACY}`. Heuristic fast path (≤150-char tiny specs, no multi-feature keywords) returns at 0ms / $0; LLM verifier hits Haiku/Flash with the heuristic verdict as a hint. Its system message is fixed JSON-only policy; repository names, inferred project type and project memory are separately framed inside the user prompt. Redis cache 1h TTL keyed by `SHA-256(spec + sortedRepos + projectId)`. Always falls back to heuristic on parse / enum / timeout error. |
| `OutlinePlannerAgent.java` | (PLANNING) Two modes — `FULL_PLAN` (32k cap, single `submitPlan` tool call) for SMALL/MEDIUM; `SKELETON_ONLY` (8k cap, id/title/complexity/repos/dependsOn/type plus compact `meetsRequirements` ownership) for LARGE/LEGACY. Skeleton requirement allocation is validated before parallel expansion and retried once when invalid. The LARGE prompt and admission boundary share a 16-task cap: an oversized outline is never silently truncated because that can drop acceptance/setup work; `PlannerTaskCapPolicy` requests one complete coarsened re-emission and then fails closed if the provider still exceeds the cap. The retry must independently cover every requirement with only known IDs and no ownerless tasks; stale ownership from the rejected attempt is never unioned into the retry because that can make one task inherit unrelated domains. Each expander therefore receives only its owned/prerequisite requirement rows instead of repeatedly ingesting and independently reinterpreting the entire spec. Recursive EPIC decomposition carries the parent's exact requirement IDs/text and rejects child outlines that renumber, omit, or invent those IDs; fallback expands the parent through `TaskExpanderAgent` as one real LEAF rather than executing a title-only placeholder. SINGLE_SHOT bypasses the LLM via `synthesizeSingleShot`; `SingleShotSurfaceClassifier` deterministically preserves the requested runtime surface: CLI/library markers win before broad backend/server words (including negated constraints such as "do not create an HTTP server"), ordinary web-app/browser/UI language produces `STATIC_WEB` + `node-frontend`, and explicit API/backend/server language produces `HTTP`. Malformed native-tool JSON gets one bounded same-route schema-repair call (`planner.outline.schema-repair`); provider/auth/timeout failures are not retried by that lane. A failed SMALL/MEDIUM/LARGE planner terminates as `PLANNER_UNAVAILABLE`; it never collapses a nontrivial request into one LOW synthetic task. Both prompts (`outline-planner.md` + `outline-planner-skeleton.md`) are assembled through `PromptAssembler`, carry the planner security-rules include, and bias toward **single-responsibility / fewest-cohesive-tasks** (NOT single-file-per-task) to curb over-decomposition (RL-2026-05-011). Requirement extraction preserves operative semantics instead of weakening exact replay, response-field, public expected-version, or forward-migration contracts; initialization is explicitly not migration. Plans must not invent standalone OpenAPI/Swagger/auth/deploy/dashboard surfaces from generic "production-quality" wording. Focused maintained tests travel in the same task as production code; later test-only tasks are only for additional cross-cutting coverage. Task breadth is bounded to roughly two architectural layers plus focused tests so one worker does not inherit service, API, startup, specification, documentation, and the entire test matrix in one growing context. TDD mode keeps test-first work and implementation inside the same cohesive task; mirrored test-only then implementation-only tasks are forbidden except for genuinely cross-cutting suites, avoiding duplicate worker context and unstable task boundaries. Repository/workspace summaries, project memory/brief, retrieved docs, contracts, ADRs and PR-review comments are no longer interpolated into the system prompt; `PromptTrustBoundary` frames them separately as project-controlled `UserMessage` evidence while preserving the planner's `submitOutline` contract. |
| `TaskExpanderAgent.java` | (WORKHORSE) Per-LEAF dependency-wave expander. `submitTaskDetail` fills the description plus `additionalTouchesFiles` / `additionalHardConstraints` deltas; authoritative skeleton arrays stay server-owned and the task evidence states the exact remaining file/constraint capacities. Requirement ownership is closed at the outline: provider-emitted current or legacy requirement-ID arrays are ignored with structured warning counts and cannot broaden `meetsRequirements`. `TaskExpansionValueMerger` accepts file/constraint additions only inside their capacities and records provider overflow, pre/post-merge drops, authoritative drops, and truncation counts; legacy field names remain a warned compatibility fallback. Independent tasks in a topological wave use the expansion pool concurrently; consumers expand only after their declared prerequisites and receive those full established descriptions/files/constraints, preventing parallel tasks from inventing incompatible schemas or interfaces. The whole phase retains one 20-min wall timeout (LARGE/LEGACY); cyclic skeleton dependencies fail planning before cyclic consumers spend worker tokens. Production details include conventional focused test paths in the same task and preserve every semantic clause of cited requirement rows, including a real ordered/versioned migration rather than baseline initialization; disposable root smoke scripts are forbidden. Each expander also receives whole, untruncated original source clauses exactly mapped to its requirement IDs; when exact allocation is unavailable it receives the complete source, so outline summarization cannot erase a rollback, lifecycle, idempotency, producer, or ownership clause before expansion. A generated description over 4,000 characters is a repairable schema failure: one bounded same-route retry must return the complete brief inside the envelope, and a repeated oversize response fails closed rather than silently deleting instructions. Final consumer reconciliation repairs action-obvious save/update/deactivate route drift in descriptions and constraints, scopes response-schema fields to the matching resource, and regenerates its derived bindings on repeat normalization. Per-task failures fall back to title-derived LEAFs. TaskExpander and Coherence prompts load through `PromptAssembler`, matching the OutlinePlanner include/assembly contract. Workspace, generated plan and task content are separately framed as project-controlled `UserMessage` evidence; tool metadata no longer interpolates the generated task ID. |
| `PlanExpansionDependencyEnricher.java` | Pre-expansion, provider-neutral graph repair. Functional frontend skeletons gain cycle-safe dependencies on backend implementation skeletons; test/E2E skeletons gain dependencies on implementation tasks. Transitively redundant edges are then removed, retaining the nearest producer frontier so established contracts reach consumers without replaying every upstream task body. |
| `ApiResponseContractExtractor.java` | Resource-scoped response-schema selection used by the final producer/consumer gate; prevents fields from one response DTO leaking into sibling endpoint bindings in the same producer task. It also distinguishes compact lowercase typed-client helper inventories such as `get/post/patch/del` from real HTTP operation declarations. |
| `PlanApiArtifactOwnershipGate.java` | Fail-closed route ownership check at the final plan boundary. When a concrete backend router/controller artifact uniquely owns a resource, provider-authored implementation operations on another task are rejected; incidental read/verification mentions remain non-owning. Server-generated `API PRODUCER CONTRACTS` lines are mechanically relocated to that concrete owner before validation. |
| `TaskExpansionRouteContract.java` | Rejects one literal parameterized GET route assigned both collection and item semantics. Context is bounded by sentence/newline/semicolon/compacted Markdown-bullet delimiters so a repeated correct item route cannot inherit an adjacent collection route's wording. |
| `PlanApiContractReconciler.java` + `PlanApiQueryAligner.java` + `PlanApiProducerContractReconciler.java` + `PlanApiActionAliasReconciler.java` | Final provider-neutral API reconciliation. `PlanApiContractReconciler` owns consumer alignment, including unique-producer repair of legacy malformed consumer routes such as `/audit<int>&page=<int>` into the producer's public path and named query string; missing or ambiguous ownership stays malformed for the syntax gate. `PlanApiQueryAligner` owns query-syntax normalization and unsupported-query removal; explicitly labelled endpoint-local producer lists such as `query parameters: q, page, page_size` are authoritative. `PlanApiProducerContractReconciler` adds a missing consumer-required operation only to the unique backend route-prefix owner and persists that operation in authoritative hard constraints as well as bounded description text. Relative endpoints in backend `api/`, `routers/`, and `routes/` modules inherit the owned resource prefix; frontend `src/api/` client modules never become producers. Cross-cutting CORS/global-error/OpenAPI-metadata tasks also cannot become producers merely by repeating operation names; real router/controller artifacts remain authoritative. A backend task that names an exact public path without restating its method can own a consumer-required operation when it is the unique implementation owner, while resource-title/file inference remains read-only so mutations are never invented from a collection noun. Compact multi-router tasks may also declare Markdown sections such as **Warehouse endpoints** (`/api/warehouses`):; relative operations bind to the nearest preceding `endpoints`/`routes` section after all explicit and section prefixes are offset-sorted. Read-intent operations bind to the route-owning GET producer even when the same collection also has POST/create. PUT/PATCH item consumers with no exact producer bind to the sole same-path PUT/PATCH producer; GET and DELETE siblings do not compete. Explicit mutating action verbs or POST-style `operation=...` requests bind to a unique declared action suffix such as `/reserve` without treating lifecycle fields such as `reserved` as verbs; enum values inside GET query/filter contexts never imply mutation. Cancel and release remain distinct operations: a stale bare-path alias is rewritten only when the same frontend/test task independently invokes the unique canonical producer route; a release-only consumer never collapses onto cancel. Replacements are scoped to the extracted operation context and path-bounded, so fixing one collection occurrence cannot rewrite sibling item/action routes. A second requirement-backed query pass runs after method repair. Explicitly requested FastAPI `/openapi.json`/`/docs` reads are recognized as framework-provided rather than invented backend work. Keep this deterministic transport/schema repair outside provider prompts. |
| `PlanApiRouteIdentityContract.java` | Stack-neutral route identity boundary before final reconciliation. Parameter names such as `:id`, `{id}`, and `{reservation_id}` compare as one path; the unique producer's exact spelling is copied into consumer and test descriptions/constraints. One mutating action exposed at both collection and item cardinality fails closed until one producer path is authoritative. A collection GET whose operation-local clause claims single-resource semantics fails with a missing-item-path diagnostic instead of being accepted as the collection route; detail wording for a different resource in the same UI paragraph does not contaminate that collection read. |
| `PlanDatabaseStackReconciler.java` | Mechanical database engine/driver closure. A task that explicitly selects SQLAlchemy `create_async_engine` and still carries a synchronous `sqlite:///` URL is normalized to `sqlite+aiosqlite:///`; the existing executable-contract reconciler then binds `aiosqlite` into the dependency-manifest owner. This does not select async for an otherwise synchronous plan. Bare aggregate-local `id` fields are excluded from cross-entity type comparison, while qualified fields such as `warehouse_id` remain strict shared contracts. |
| `PlanSafetyContractEnricher.java` + `PlanSafetyAuditSupport.java` + `PlanSafetyMigrationSupport.java` + `PlanContractQualityGate.java` + `CoherencePlannerAgent.java` | The deterministic provider-neutral pre-pass applies safety contracts before LIGHTWEIGHT model coherence. Explicit order states from the authoritative requirement override legacy reservation defaults (`DRAFT`/`COMMITTED` are supported), schema normalization emits that complete lifecycle dynamically without rewriting similarly named persistence columns such as `Inventory.reserved`, and canonical idempotency records reject/repair leaked business states outside `PROCESSING`/`COMPLETED`. Once the schema contract establishes `Order.status` or `Reservation.status`, exact dotted aliases in downstream descriptions and hard constraints are normalized to that same persisted field without rewriting another aggregate's `state`. One implementation task owns the IdempotencyRecord schema/migration; verification tasks remain consumers. When an outline puts a versioned “all tables” migration in an infrastructure scaffold before downstream domain schemas, migration ownership is moved to the final domain-schema producer and all earlier tasks are constrained to configuration/model work. `PlanMigrationOwnershipReconciler` closes `PlanScopeOwnershipReconciler` bindings before deriving revisions, recognizes requirements expressed as `migration`, Alembic, or “must migrate,” and therefore guarantees that a concrete model reassigned to a later task is present in that owner's `touchesFiles` and receives an ordered model-local migration after its prerequisite schema revision. A unique upstream schema/persistence task wins model-file ownership over a downstream service/API title, preventing reciprocal T2↔T3 assignments; stale generated sibling-owner corrections are removed from the owning brief instead of surviving as contradictory worker instructions. Required idempotency headers cannot become optional typed-client parameters, SQLite ownership uses one `BEGIN IMMEDIATE` serialization point without polling, and health/readiness polling in the same task is not confused with polling an uncommitted idempotency record. Browser E2E requirements that launch both real application tiers must drive business setup/workflows/assertions through Playwright UI interactions rather than direct API seeding. Their executable contracts use the plan's documented backend/frontend commands plus fresh isolated test state by default; Alembic, uvicorn, Vite, and `DATABASE_URL` are required only when the assembled plan positively establishes that FastAPI/Vite/Alembic stack. Requirement-derived immutable audit operations are bound consistently across producer/consumer tasks; one logical transfer produces one `TRANSFER` event rather than invented OUT/IN pairs, and a stock-mutation task cannot acquire the audit-history GET when a separate concrete audit router owns it. An explicit async database scaffold also resolves downstream “async or sync” alternatives to `AsyncSession`. Final producer/consumer validation derives HTTP methods, canonical `/api` paths, endpoint-local query parameters, and response fields from the current assembled plan. Impossible consumer `GET` mutations are corrected without allowing a collection `GET` to overwrite their method; missing operations are bound only to the unique backend router artifact owning that resource prefix, and unsupported prose-only query suggestions are removed. Natural hyphenated requirement prose is not treated as a literal schema identifier; one merged, bounded requirement-signal paragraph retains all existing and newly missing high-signal terms, renders internal concept keys such as `exactly_once` as executable phrases such as `exactly once`, and preserves underscores on literal schema fields. HTML control wording such as “text input or select” is not misclassified as an unresolved technology choice. SQL `SELECT` queries, including `SELECT FOR UPDATE`, are excluded from the unresolved-choice language check unless they explicitly select an implementation stack or strategy. Cross-task scope validation requires a mutation verb to govern the mentioned artifact directly, so compacted read-only references such as “use the session from database.py” and “env.py already configures” do not become false ownership conflicts. Router-artifact ownership wins over incidental endpoint mentions. An unowned frontend aggregate read composes declared collection producers client-side, and an unowned requirements-file install is rewritten to the plan's actual pyproject location. Shared-schema field parsing accepts optional object/list wrappers and explicit multiline bullet declarations without dropping fields; exact model/DTO names are compared across tasks so identifier substitutions and collapsed multi-reference fields fail before worker execution. The gate rejects unresolved implementation choices, clause-local incompatible lifecycle claims, divergent explicit shared-model fields, async/sync database drift, and unmapped `state`/`status` vocabulary. Duplicate task-level contradiction findings are reported once with their task ID rather than repeated as an unattributed global violation. When a task claims one global nested HTTP error envelope, `PlanGlobalErrorEnvelopeGate` also requires its handler owner to cover framework HTTP and request-validation exceptions, rejects backend producers that still promise raw `{detail: ...}` bodies, and requires frontend consumers to use the shared client's normalized error boundary instead of reading raw or nested wire fields directly. Test assertions that merely verify persisted fields do not masquerade as schema definitions. `patchPlan` supports bounded exact `REPLACE_DESCRIPTION_TEXT` repairs. If deterministic final validation still reports contradictions, `RunPlanningCoordinator` performs one same-provider targeted coherence repair and validates again; a second failure remains fail-closed before worker spend. The repair keeps the assembled plan byte-stable and appends validator findings after it as bounded untrusted evidence, preserving the reusable prompt prefix and preventing transient findings from leaking into `planSummary`. Post-coherence normalization bounds every task description at 4,000 characters while retaining explicit binding paragraphs; its sentence/newline boundary search is strictly inside that envelope, so a delimiter at offset 4,000 cannot leak a 4,001st character past the fail-closed validator. Workspace and assembled task content remain `PromptTrustBoundary` evidence, never system authority. |

Model ownership closure uses the same unique upstream schema/persistence owner in normalization and
semantic validation. Stale generated `MODEL OWNER CONTRACT` rows, imported-from-sibling prose, and
downstream wrapper/re-export ownership instructions are removed when a concrete model artifact is
returned to that owner; a generic resource noun in an API title cannot override that decision.

Optional `Idempotency-Key` contradictions are detected clause-locally and deterministic repair
rewrites both the expanded description and hard constraints. A typed remaining expansion rejection
survives hierarchical coordination so the durable sanitized failure evidence names the exact task
and violation rather than projecting a provider outage.

Before that targeted provider repair, `RunPlanningCoordinator` performs exactly one additional
bounded normalize/compile/revalidate stabilization pass and skips provider spend when it clears the
findings. Remaining triggers are logged as a secret-redacted, single-line, count/length-bounded
summary. Coherence telemetry calls the combined deterministic-plus-model count “repair operations,”
not model patches.

`PlanModelOwnershipReconciler` preserves schema fields while changing a non-owner's model-definition
block into an explicit consumer contract bound to the unique model/entity artifact owner;
`PlanContractQualityGate` remains the stack-neutral fail-closed backstop for unresolved ownership
drift. Its focused package-private `PlanLifecycleValueContractGate` collaborator parses
conjunction-separated values from both “values are exactly” and “lifecycle is exactly” contracts,
then binds each explicit `Entity.status/state=value` assertion to that entity. A release state on
one aggregate therefore cannot be attributed to another aggregate
mentioned in the same sentence. Unqualified request/process/test status prose is never attributed
merely because a canonical entity appears elsewhere in the clause, while an explicit invalid
`Entity.status/state=value` assertion and a genuinely undeclared compound state still fail closed.
An unqualified natural-language transition (`status becomes/changes to X`) also fails generically
when `X` belongs to no declared lifecycle; it is never assigned to an entity by proximity. A
lexically explicit non-domain subject such as `request status becomes PROCESSING` remains outside
the domain lifecycle gate.

The final gate also parses compact Markdown model declarations and TypeScript object aliases,
rejects foreign model-definition claims and SQL updates to fields absent from the canonical model,
and validates explicit `XRepository (from Tn)` references against the named producer. Model-local
status sections must agree with the canonical lifecycle even when their values are not repeated in
an endpoint contract. Route inventories fail on unbalanced code spans, duplicate operations, or
mixed versioned namespaces; documented test commands fail when their target is unowned, their
pytest token is malformed, or their frontend/backend origin variable contradicts the plan. Worker
briefs containing a synthetic compaction-omission marker or a dangling numbered step are rejected,
as is one task that both requires migrations and permits runtime `create_all` initialization.
`CoherencePlannerEvidence` deduplicates repeated exact constraints only in the coherence prompt by
pointing later copies to the first task; persisted worker constraints remain byte-complete.

An explicit single/consolidated Alembic owner is authoritative regardless of task order:
`PlanMigrationOwnershipReconciler` removes later sibling numbered revisions even when model scope
was reassigned. Migration-directory metadata such as `.gitkeep` and `__init__.py` never qualifies as
a revision; an explicit owner without a concrete revision receives `001_initial_schema.py`.
Concrete model/entity files, page files, and versioned migration files are exclusive implementation
artifacts; final validation rejects any exact path still assigned to multiple tasks.
Plain “create/define X model” prose is subject to the same unique model-file ownership gate as bold
schema blocks. When the assembled plan establishes an async SQLAlchemy runtime, seed/bootstrap/data
fixture tasks that explicitly use a synchronous `Session` fail closed before worker execution.
`PlanPersistenceFieldUsageGate` also compares concrete cross-task SQL `UPDATE ... SET` fields with
an explicit named table/model field declaration, so a service cannot silently mutate undeclared
storage columns such as an alternate stock vocabulary. Cross-task persistence declarations with a
shared semantic entity stem and at least 75% field overlap fail as near-duplicate aliases, preventing
parallel `AuditEvent`/`AuditLog`-style models unless the plan declares distinct ownership.

`TaskExecutionBriefCompiler.ExecutablePlanContractException` is the typed final-boundary rejection
used for empty plans, unknown/duplicate requirements, worker-brief overflow, null tasks, and final
contract violations. `RunOrchestrator` persists and projects exact rejection evidence only for that
type; an unexpected compiler/runtime exception remains a generic planning failure and cannot be
turned into `PLAN_VALIDATION_REJECTED` by manufacturing an exception-message prefix.

`PlanSharedFieldContractGate` treats a bare local `Props` declaration as component-scoped rather
than a globally shared schema. Component-qualified props types and explicitly
shared/exported/reused `Props` contracts still receive cross-task field-drift validation.

Release-critical semantic reconciliation additionally follows the IdempotencyRecord schema actually
established by its producer even when the requirement omits exact-replay keywords; it does not
impose those names on a different replay model. Consumer-alias repair is field-specific: the
normalizer may replace `idempotency_key` only when the producer proves `(key, scope)`, and may
replace `scope_hash` only when the producer proves `request_body_hash`; proof of either half never
authorizes invention of the other. A stale `PENDING row/record exists` replay sentence is rejected
against the canonical `PROCESSING`/`COMPLETED` lifecycle; normalization removes it when an explicit
`COMPLETED` replay branch already exists, otherwise changes only the proven lifecycle state without
inventing schema fields. Scope ownership also rejects `/models/` paths explicitly described as
service-layer files, including grouped paths such as `models/{warehouse,product}.py`, and does not
emit owner bindings from that contradictory evidence. Verification-worded requirements retain their
high-signal terms in a verification owner, so an implementation task's mention of rollback cannot
hide its loss from an acceptance suite. The last normalization boundary reruns
`PlanExecutableContractReconciler`: real-server subprocess fixtures receive a stack-neutral bounded
readiness contract, global canonical-error owners cover framework HTTP and request-validation
paths, and incomplete Playwright bindings are replaced with one runner-consistent lifecycle,
isolation, readiness, and teardown contract. Black-box instruction canonicalization and the worker
brief compactor recover explicit `(binding)` boundaries even when coherence flattened adjacent
markers without whitespace, so later description compaction cannot truncate the runner,
dependency, both process lifecycles, isolated state, readiness, or teardown contract.

`IdempotencyResponseSemanticValidator` is opt-in to explicit response semantics: when a task
distinguishes first execution from replay and names a replay marker/header, the first response must
omit that marker and replay must return the stored status and body with it. Generic idempotency
contracts that do not declare a marker remain valid.

`PlanBackendContractGate` also fails closed on runtime database-driver closure. A configured async
URL such as `sqlite+aiosqlite` or `postgresql+asyncpg` is executable only when the
dependency-owning task explicitly lists the matching driver; a runtime configuration mention is
not a dependency declaration.

Query reconciliation is two-phase: repair consumer methods/paths without stripping query fields,
then promote only requirement-backed fields against that repaired route, and finally align the
consumer to the producer contract. A contradictory mutation URL on a clearly read-oriented
frontend/test task can bind to a GET route only when the title or an exact-path constraint resolves
to one unique GET operation; exact/action/context-specific producer matching runs before the
task-wide read fallback, so a read-oriented page cannot erase its own valid mutation route.
Route-specific contradiction repair additionally requires the task's exact path authority and
domain evidence to favor the GET producer over the current consumer. Ambiguous or
mutation-oriented tasks remain fail-closed.
`PlanApiReadIntentResolver` owns that bounded read override.

`PlanApiTypedClientActionResolver` aligns compact typed-client action functions with their declared
mutation routes. Function/endpoint inventories are matched in order, bounded verb/noun aliases
cover adjustment, reservation, cancellation, deactivation, commitment, and transfer routes, and
resolution requires one unique producer; ambiguous aliases remain unchanged for the final gate.
`PlanApiFrameworkRouteSupport` owns the FastAPI OpenAPI/docs exemption. It requires positive
route-specific authority for `/openapi.json` and `/docs`, and rejects negated, disabled,
custom-routed, and OpenAPI-file-only evidence rather than treating generic framework selection as
runtime-route authority. Only after those checks pass, consumer references to FastAPI's prefixed
defaults (`/api/openapi.json` and `/api/docs`) are canonicalized to the actual root routes.

`PlanFrontendApiCallSiteNormalizer` keeps frontend HTTP and typed-client call sites relative when
the assembled plan explicitly establishes a shared `baseURL: '/api'`; backend/public producer
contracts remain canonical `/api/...`, preventing generated `/api/api/...` requests.
`PlanE2eToolchainGate` rejects a browser-test task that mixes Python pytest/`.py` Playwright
artifacts with Node Playwright package/config artifacts, and rejects assigning Node's `webServer`
configuration to `conftest.py`. One runner must own a matching dependency and executable script,
but ordinary backend pytest files owned by a broad scaffold task do not count as Python
Playwright evidence unless the Python artifact is browser/E2E-scoped or `pytest-playwright` is
explicitly selected.
both application processes, a bounded readiness probe, a fresh/isolated `DATABASE_URL`, and
teardown. `PlanExecutableTaskGate` independently rejects blank descriptions and the
`(no description)` expander sentinel even when later safety enrichment prepended generic
constraints; titles, requirement IDs, and file lists are not sufficient worker instructions.
`PlanExecutableContractReconciler` closes the mechanically derivable side of those gates before
provider coherence: runtime URL drivers are declared by the actual dependency-manifest owner,
subprocess HTTP fixtures gain bounded readiness, incomplete Playwright tasks receive one
runner/dependency/script/process/isolation/teardown contract, and final page tasks take ownership
of the scaffold router artifact needed to replace their placeholder routes. It also resolves an
explicit AsyncClient/TestClient choice to `httpx.AsyncClient` only when the assembled plan already
establishes an async stack, completes exact-path constraints from action routes already declared
by the same task, closes title/domain file ownership before stripping stale foreign-file mutation
instructions, and leaves unrelated implementation choices unresolved. These repairs are
idempotent and provider-neutral; project-specific contract decisions still fail closed for
coherence rather than being invented.
`TaskExpansionRouteContract` also performs a bounded final repair for a literal parameterized GET
route that the provider declared twice with opposite item/collection semantics: the first exclusive
cardinality declaration is authoritative, only later contradictory clauses are removed, adjacent
operations are preserved, and uncertain contexts remain for fail-closed validation.
`PlanPythonNamespaceLayoutReconciler` resolves only marker-only Python package/module collisions.
When `x.py` coexists solely with `x/__init__.py`, the monolithic module remains canonical, marker
ownership moves to the parent `__init__.py`, and a binding forbids the shadow package. A `base.py`
is marker-only only when every task owning that exact path explicitly calls it an empty/no-runtime
package marker; filename alone is never proof. A package containing a substantive `base.py` or any
other concrete implementation module is never rewritten and remains a strict collision for the
final gate. Detection is case-insensitive but emitted filesystem paths preserve their original
case.
Scope ownership treats package initializers and model/entity registry barrels (`__init__`,
`index`, `barrel`, `package-info`, and `mod`) as shared integration metadata rather than exclusive
domain implementations. Concrete model/entity modules, page components (including page entrypoints),
and versioned migrations remain single-owner artifacts and still fail closed on duplicate task
ownership. A narrow sequential page-composition hand-off is not treated as competing ownership:
every later page-touching task must directly depend on the prior owner and explicitly name that
exact task in an `owned by Tn` clause. Missing dependencies, implied integration, wrong owner IDs,
and every model/entity or migration duplicate remain fail-closed.
`TaskExecutionBriefCompiler` preserves both the final superseding paragraph and any earlier
provider-neutral executable Playwright/readiness/dependency contract when it compacts an oversized
worker brief. A later requirement-signal paragraph therefore cannot truncate the dependency,
runner, application-process lifecycle, readiness, isolation, or teardown instructions that the
final E2E quality gate requires.
`PlanFrontendRouteBindingGate` compares placeholder routes declared by a frontend scaffold with
later final page artifacts. A final page may own its App/router update, or one explicit non-scaffold
integration task may own the shared router and name every component-to-route binding. The gate
reports only bindings left unresolved by both ownership paths, including detail/create routes
derived from the declared collection route.
When a compact backend task has no single filename-derived router prefix,
`PlanApiProducerContractReconciler` may still own a missing consumer read through its
uniquely-matching declared route family; this fallback never invents mutations. The normalizer
reruns consumer binding after adding that producer operation so producer and consumer authority
converge in one pass rather than requiring a second normalization cycle.
`PlanCrossStackIdentifierTypeGate` compares identifier declarations by canonical entity and field
across Java/Python backend response shapes and TypeScript interfaces/type aliases. Backend UUIDs
may serialize as frontend strings, but a numeric frontend identifier for the same resource field
fails closed; unrelated entities' generic `id` fields are never conflated.
| `PlanExpansionDependencyEnricher.java` | Adds producer-first expansion edges without domain constants. Frontend feature consumers expand only after domain-relevant backend producers inferred from their outline titles/artifacts; frontend shells do not ingest every backend contract. Test-only tasks expand after preceding implementation tasks only. Later packaging/documentation tasks are never made prerequisites of an earlier E2E task, and cycle checks observe edges added earlier in the same enrichment pass. This bounds both contract invention and expansion prompt growth. |
| `PlanScopeOwnershipReconciler.java` | Closes concrete model/page/migration ownership before execution. Model-focused dependency tasks retain explicitly named model artifacts instead of losing them to downstream service/API titles; reassignment removes stale generated owner constraints. Pages remain exclusive except for an explicit dependency-ordered `owned by Tn` composition hand-off; models and migrations never receive that exception. |
| `WorkerAgent.java` | **→ relocated to `engine/WorkerAgent.java` (Phase 4, RL-2026-05-052).** Still WORKHORSE-tier (mapping below unchanged); see [`engine/CLAUDE.md`](../engine/CLAUDE.md) for the worker loop. (per-task tier — LOW=LIGHTWEIGHT / MEDIUM=WORKHORSE / HIGH=PLANNING) Sequential multi-turn loop. Native tool calling end-to-end via `LlmRouter`. `CompleteTaskTool` terminates. **One progress signal** (RL-2026-05-005): `itersSinceProgress` → one UserMessage nudge at `progressWarnAfter` (also on a text-only stop), force-terminate at `progressForceAfter`. **Lever B** best-guess salvage on iter-limit / budget-exceeded / progress-force when nothing was written. Snapshots conversation to Redis every 5 iters via `ContextStore`. |
| `PatchAgent.java` | **Deleted in ExecuteSpec 2.0.** PATCH-mode single-task codegen is owned directly by `engine/CodeGenEngineImpl`, which loads `prompts/patch-agent.md`, applies provider patch fragments, sets per-provider iter caps, scopes the test-verification gate, and toggles the common critic/VBC flags before calling `WorkerAgent` directly. |
| `PlanStrategyCircuitBreaker.java` | Redis-backed. 5 consecutive failures → 1h open → ladder degrade LEGACY → LARGE → MEDIUM → SMALL → legacy. Fail-open on Redis errors. |
| `PlanStrategy.java` + `ClassificationResult.java` + `Plan.java` + `PlannedTask.java` + `RequirementSpec.java` | Plan data types. `RequirementSpec{id, text, kind}` (FUNCTIONAL / NON_FUNCTIONAL / TEST / CONSTRAINT) — every task cites which IDs it covers via `meetsRequirements`. |
| `requirement/RequirementIrDocument.java` + compiler/validator/codec | RI-P1 schema-first contract. Compiles the exact original source and final legacy plan into a separate schema-v1 requirement/acceptance artifact with exact UTF-16 source spans, USER_STATED vs INFERRED provenance, reciprocal task/acceptance links, blocking ambiguity/conflict/omission issues, compiler/source fingerprints, and strict canonical JSON. It does not add fields to `Plan`/`PlannedTask`, mutate `plan_json`, call a provider, or enable plan-gate enforcement. Frozen fixtures pin deterministic zero-spend behavior before persistence/integration. |
| `PlannerOutputUtils.java` | Static helpers: `parsePlanFromArgs`, `validatePlan` (Validation v3 Pillar 1), `salvagePlanFromText` (Gemini pseudo-code recovery), `warnIfConstraintsUnderpropagated`, `cleanTitle`, `resolveProviderForTitle`, `truncate`. |
| `CompleteTaskTool.java` | Native termination tool — the worker calls it as a tool to end the loop. |
| `ConversationAgent.java` | **Legacy** chat copilot + the live `generateSuggestionPlan` (Apply-to-Run mini-plan). Both text paths use `LlmRouter.callText`; suggestion-plan generation requests `LlmRequest.PromptCacheStrategy.SYSTEM_ONLY` so Anthropic retains its stable-system-prompt cache behavior. The live post-run **chat** is `chat/ChatService` (LlmRouter + read-only `chat/RunChatTools` tool loop since RL-2026-05-047), NOT this `chat()` method. |
| `CostEstimator.java` + `RunCostEstimate.java` | Pre-plan cost estimate. V2 path uses `RunCostTracker.estimateRunTierAware` (see `cost/`). |
| `ApiKeyResolver.java` | Resolves API keys: user → org → system fallback. V237 adds user/org DeepSeek and Fireworks key lanes (`app_user.*` + `org.*`) alongside Anthropic/OpenAI/Gemini and org-only OpenRouter. `isOwnKeysMode(userId)` returns true when org is `OWN_KEYS` (drives the BYOK cost-cap exemption). |
| `RunKeyStore.java` | **Deleted 2026-07-19.** The unused JWT-secret-derived Redis credential cache was removed; V282 dispatch reloads current credentials by exact tenant context. |
| `executioncontext/LlmExecutionPolicyFactory.java` | Creates idempotent secret-free run, agent-turn, project-job and explicit platform-internal route snapshots from authoritative tenant policy. |
| `executioncontext/LlmExecutionDispatchGuard.java` | Reloads V282 plus current credential provenance before every physical attempt and enforces allowed provider/model, exact pins, failover, key source and durable budgets. |
| `RunKeySourceModeResolver.java` | Transitional compatibility seam used only when V282 enforcement is explicitly disabled. It is not the production dispatch authority. |
| `ByokContextUnavailableException.java` | Transitional compatibility failure for legacy pure-BYOK requests. V282 policy exceptions are the canonical fail-closed path. |
| `PromptAssembler.java` + `PromptAudit.java` | Composable prompt fragment system. Resolves `{{include:fragment}}` markers from `prompts/fragments/` + `{{VAR}}` substitutions. SHA-256 prompt hash → `prompt_audit` table. |

## Tier mapping (`application.yml agent-tier-map`)

| Tier | Agents |
|------|--------|
| PLANNING | OutlinePlannerAgent, ReviewerAgent, RepoArchitectureGenerator, ArchitectureDocGenerator, ProjectChatCompiler, PrReviewAgent, CodeReviewAgent |
| WORKHORSE | WorkerAgent, TaskExpanderAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ProjectDocGeneratorService, ValidationReportGenerator, PromptOptimiserAgent |
| LIGHTWEIGHT | SmartClassifierAgent, CoherencePlannerAgent, ContextAgent, RunLedgerAgent, RunTitleSummarizer, ProjectChatCompactor, ProjectBriefService, ConversationAgent, TestReportGenerator, **ToolOutputCompactor** (in `tool/`, RL-2026-05-052) |

## Patterns

- **Native tool calling end-to-end.** No XML `<tool_call>` parsing in V2 — that was a V1 idiom. Strict JSON schemas on every `tool/` entry.
- **Sliding-window context:** first 2 + last 18 messages applies only to legacy non-tool callers that have not yet moved to the canonical text seam. The V2 `WorkerAgent` loop (`engine/`) keeps its FULL conversation history (no window); `ToolOutputCompactor` (default OFF, `worker.tool-compactor.enabled`) is the only worker-context compaction lever.
- **Token-cost control:** `readFile` full reads cap at 60KB/2000 lines, **head-first** truncation at a line boundary (byte-exact from line 1 so `editFile` matches; range reads untruncated — RL-2026-05-062, was a 5KB head+tail-with-a-hole), `shell` / `buildProject` 6k chars inline (`ToolOutput.budget` — full log spilled to disk + a `readFile` path).
- **Never call providers directly** — every production LLM call goes through `LlmRouter` (native tools or `callText`). Text-only compatibility callers that omit an output cap inherit the registered YAML model `max-output-tokens`, ensuring Anthropic requests always include the required `max_tokens` field while explicit caps remain unchanged.
- **BYOK boundary fails CLOSED through V282 (§7 #2, 2026-07-17).** Every production `LlmRequest` carries an exact `LlmExecutionReference`. `LlmExecutionDispatchGuard` reloads the tenant-scoped immutable policy and current encrypted USER/ORG credential immediately before each physical attempt. Missing/stale/cross-tenant contexts, key-source changes, unkeyed providers, model/pin violations and exhausted budgets fail before transport. OWN_KEYS snapshots cannot contain PLATFORM sources. Failover and managed-AI model changes are re-authorized against the same snapshot, and `AvailableProviderResolver` derives candidates from V282/current credential provenance. Legacy mode checks remain only behind explicitly disabled execution-context enforcement.
- **Gemini credentials are header-only (2026-07-19).** `GeminiNativeToolClient` sends the current V282-authorized key through `x-goog-api-key` for generation and explicit-cache mint calls. Never put provider credentials in a URI query, log message or tracing attribute.
- **Worker progress signal (RL-2026-05-005):** ONE counter `itersSinceProgress` (consecutive iters with no `writeFile`/`editFile`) drives everything. At `progressWarnAfter` (per-tier `req.stagnationWarnAfter`, default `STAGNATION_WARN_AFTER_N=8`) inject ONE "PROGRESS CHECK" UserMessage — the same one-shot nudge also fires on a premature text-only stop (model emits a summary without calling `completeTask`). At `progressForceAfter` (default `STAGNATION_FORCE_AFTER_N=12`) force-terminate with `COMPLETED_BY_STAGNATION` (Lever B salvage first if nothing was written). UserMessage (not SystemMessage) so Gemini's implicit cache prefix stays intact. This replaced the old Lever A + Lever C + stagnation warn/force quartet.
- **gitCommit denied for the worker** — `ToolRegistry.bindForWorker(ctx)` filters it (auto-push happens at run-end via `ScmPushService.pushMultiRepo`).
- **Remote worker schemas are canonical, not compatibility catalogues.** A local-hands session advertises 25 one-name-per-capability schemas implemented by the released CLI runtime; Terminal-Bench advertises only the 11 canonical schemas implemented by its Harbor adapter. The 26 removed alias/stub schemas remain registered below the binding layer so an older persisted/in-flight tool call can still be dispatched, but they no longer consume every model turn or compete in tool selection.
- **Validation v3 Pillar 1:** `PlannerOutputUtils.validateRequirementCoverage` always enforces complete/valid requirement ownership on hierarchical skeletons before expansion; one bounded outline retry is allowed, then planning fails closed. `validatePlan` adds the expanded-body checks; its legacy FULL_PLAN retry remains gated behind `validation.v3-strict` (default OFF).
- **Prompts** in `src/main/resources/prompts/*.md` plus active `.txt` prompts loaded by specific non-tool agents. Dead rollback archives (`planner.v*.txt`, legacy `planner.txt`, `triage.txt`, `reviewer-v2.txt`) were removed in the 2026-07-02 prompt-audit cleanup; rollback uses `PromptAuditEntry.previousContent` and dynamically-created backups for live prompt files, not inert classpath archives.
