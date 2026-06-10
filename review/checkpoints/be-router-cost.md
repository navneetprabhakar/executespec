# be-router-cost — raw findings

> Batch: `router/` + `cost/` + `scoring/` + `report/` + `smoketest/` + `pricing/` (+ matching tests), reviewed as-is on branch `benchmark` **including uncommitted changes** (FireworksModelMapper.java new; LlmRouter/LlmProvider/AvailableProviderResolver/OpenRouterModelMapper/application.yml modified). Conclusions from code + tracked YAML only; no docs consulted. 2026-06-10.

## 1. Inventory

| Package | Files | LOC | Read |
|---|---|---|---|
| `router/` | 18 | 1,992 | all, fully |
| `cost/` | 5 | 1,308 | all, fully |
| `scoring/` | 5 | 782 | all, fully |
| `report/` | 14 | 3,207 | all, fully (ValidationReportService 1,647 in 3 chunks) |
| `smoketest/` | 6 | 819 | all, fully |
| `pricing/` | 3 | 72 | all, fully |
| **Total main** | **51** | **8,180** | |
| Matching tests | 20 | 2,830 | all (12 fully, 8 via method-name census) |

Cross-cutting reads required to verify claims: `agent/LlmRouter.java` (668, full — incl. uncommitted `callFireworks`), `agent/RunKeyContext.java`, `agent/ResolvedKeys.java`, `config/ModelPricingInitializer.java`, excerpts of `integration/RunOrchestrator.java` (estimate + pin-bind blocks), `integration/RunValidationKickoff.java`, `harness/SequentialExecutor.tierConfigFor`, `terminalbench/TerminalBenchRunService.java`, `auth/service/OrgService.java`, `auth/controller/OrgController.java`, `api/controller/{ModelController,PricingController}.java`, `application.yml` (model-matrix lines 84–382) + `application-{dev,prod,benchmark}.yml` (grep: **no** model-matrix overrides in any profile; only `spring.ai.*` base-url/api-key blocks — no `spring.ai.fireworks` block in any profile YAML, base `application.yml:80-82` only).

---

## 2. Findings

### Real bugs first

**[BUG-1] Every OpenRouter-only model records $0 actual cost — pricing lookup goes to the wrong provider key.**
`cost/RunCostTracker.java:720-727` — `ownerProviderFor(model)` maps prefixes `claude-*`→anthropic, `gpt-*`→openai, `gemini-*`→gemini, **everything else → `"openai"`**. Its comment ("every matrix model is claude-* / gpt-* / gemini-*") has been stale since the OpenRouter-only models were added. `priceCall` (`RunCostTracker.java:669-672`) rewrites `pricingProvider` through this helper for **every** `provider="openrouter"` call, then looks up `("openai", "deepseek-v4-pro")` — but `ModelPricingInitializer.java:72` seeds those rows under the YAML model's own provider, i.e. `("openrouter", "deepseek-v4-pro")` (`application.yml:240-241` `provider: openrouter`). Lookup misses → `RunCostTracker.java:673-677` returns **0.0 with only a DEBUG log**. Affected ids: `deepseek-v4-pro`, `deepseek-v4-flash`, `kimi-k2.6`, `qwen3-coder`, and the new uncommitted `mimo-v2.5-pro` (`application.yml:308-318`). The dispatch side passes exactly these matrix ids (`agent/LlmRouter.java:230` records `request.provider()`/`request.model()`; the slug mapping happens later in `callOpenRouter:586`). Consequences: `pipeline_run.actual_cost_usd`, the `/cost` endpoint, usage reports, `run_cost_history` rows and **benchmark cost claims** (TB run for `mimo-v2.5-pro` is the stated purpose of the uncommitted change) all read $0 for these routes — while the **estimate** path prices them correctly (`estimateRun` at `RunCostTracker.java:238` uses `providerKey(provider)` directly, no owner rewrite), so credits are charged against a non-zero estimate and actuals report zero. These routes are customer-reachable: Enterprise org overrides allow `openrouter:<tier> → <allow-list id>` (`OrgController.java:352` exposes `OpenRouterModelMapper.allowList()`; `OrgModelOverrideService.java:176-181` validates against the same list). Fix shape: try `("openrouter", model)` first (the seeded truth), fall back to `ownerProviderFor` for the big-3 pass-throughs.

**[BUG-2] FIREWORKS provider is dispatch-only — unroutable through `ModelResolver`, unpriced, and BYOK-less (uncommitted work, incomplete).**
- No `model-matrix.providers.fireworks` block exists (`application.yml:85-105` defines anthropic/gemini/openai/openrouter/deepseek only) → `ModelResolver.lookupModel` (`router/ModelResolver.java:258-263`) throws `IllegalStateException("No model matrix config for provider: FIREWORKS")` **before** the `RunModelOverrides.lookup` call at line 267, so not even a per-run pin or org override can route it. Any pipeline run with provider=fireworks dies at the first resolve (planner); only the worker fallback catch in `SequentialExecutor.tierConfigFor:1325-1339` (run-level model passthrough) and `terminalbench/TerminalBenchRunService.java:215-279` (bypasses ModelResolver entirely, provider+model straight from the request) can reach `LlmRouter.callFireworks`.
- No model in `model-matrix.models` has `provider: fireworks` → `ModelPricingInitializer` seeds zero fireworks rows → `priceCall("fireworks", "qwen3-coder-fw", …)` returns **$0 silently** (`RunCostTracker.java:673-677`). The five `FireworksModelMapper` ids (`qwen3-coder-fw`, `deepseek-v3-fw`, `deepseek-v3p1-fw`, `kimi-k2-fw`, `glm-4p6-fw`, `FireworksModelMapper.java:25-31`) appear nowhere in the YAML — unknown to `ModelRegistry`, invisible to the picker, unpriced.
- `LlmRouter.java:148-150` claims "org BYOK via `RunKeyContext.keyFor("fireworks")`" but `RunKeyContext.keyFor` (`agent/RunKeyContext.java:47-56`) has **no case for "fireworks" (or "deepseek")** — `default -> null` → always falls to the platform key. Same false claim for DeepSeek at `LlmRouter.java:139-141`. Both enums are documented "ENTERPRISE BYOK" (`LlmProvider.java:10-19`) yet structurally can only ever use the platform `spring.ai.{deepseek,fireworks}.api-key`.
- `AvailableProviderResolver.java:119` got the `FIREWORKS -> "fireworks"` switch arm, but the class is dead code (see §3), so the edit changes nothing observable.
Net: of the six things a provider needs (enum, dispatch case, key plumbing, matrix column, pricing rows, availability), the uncommitted change ships two.

**[BUG-3] `VerifiedScoreBackfillService` parses a JSON shape that the column never contains — `unfixedCritical` is always 0 on real rows.**
`report/VerifiedScoreBackfillService.java:109-129` reads `severity=="CRITICAL"`, `autoFixed`, `fixSucceeded`, `id` from `validation_report.issues_json`. But that column is written exclusively from `ReportIssueDTO` (`ValidationReportService.java:91-92` + `extractIssues:349-352`), whose shape is `{description, severity, fix, stage, filePath}` with severity already mapped to `WOULD_CAUSE_BUG`/`WOULD_FAIL_REVIEW` (`mapSeverity:442-448`) and **no** id/autoFixed/fixSucceeded fields (`report/model/ReportIssueDTO.java:6-7`). So `"CRITICAL".equals(severity)` never matches → every backfilled row recomputes with `unfixedCriticalCount=0`. The unit test (`VerifiedScoreBackfillServiceTest.java:22-95`) pins hand-written JSON in the *ReviewIssue* shape that the production column never holds — it validates a fantasy input. Compounding: `backfillAll` (`VerifiedScoreBackfillService.java:67-74`) calls the 6-arg `computeVerifiedScore`, which threads `filesGenerated=-1` ("unknown → treat as delivered", `ValidationReportService.java:905-916`) and no `testCoverageLevel`, even though the entity has `filesGenerated` and `accuracyJson` right there — so a backfill silently rewrites historical scores with a *different* formula than live runs (no CODE_DELIVERED slot, no coverage damping). The javadoc still describes the retired multiplicative "+10 endpoint bonus" era. Live SUPER_ADMIN endpoint exists (`auth/controller/SuperAdminController.java`), so this is reachable, not dormant.

**[BUG-4] `waitFor`-then-read pipe deadlock in all three process runners.**
`smoketest/WorkspaceTestRunner.java:119-130` starts the test process, calls `proc.waitFor(timeout)` **first**, and only reads stdout after exit. With `redirectErrorStream(true)` everything goes to one pipe (~64KB OS buffer); any suite whose output exceeds it blocks the child on write → `waitFor` burns the full timeout (600s for mvn/pytest/jest, `TestCommandResolver.java:44-71`) → `destroyForcibly` → `Optional.empty()` → the verifiedScore TESTS slot falls back to damped LLM coverage. So precisely the verbose suites (large Maven/Jest runs — the ones the `scoring.real-test-exec` flag exists for) silently never produce a real count, and each one adds up to 10 minutes of dead wall-clock to report generation. Same pattern in `scoring/RunRubricVerifier.verifyShell:159-170` (currently dead feature, §3) and `smoketest/CliSmokeRunner.invoke:80-89` (`readNBytes(2048)` after `waitFor`; 15s cap so cost is a false TIMEOUT, plus no stdin redirect — an interactive CLI blocks forever until the timeout). Standard fix: consume the stream concurrently (or read fully before `waitFor` with a watchdog).

**[BUG-5] Gradle (and likely quiet-Maven) test counting can never succeed.**
`smoketest/TestCommandResolver.java:46-50` emits `./gradlew test --console=plain` while its own comment says "`--info` surfaces the per-class 'Tests run' lines" — the flag is **not in the command**; and Gradle does not print Surefire-grammar `Tests run: N, Failures: …` lines to stdout in any mode (that grammar is Maven's), so `TestOutputParser.parseSurefire` (`TestOutputParser.java:88-101`) finds nothing → permanent fallback to LLM coverage for every Gradle workspace. The Maven command `mvn -q test` (`TestCommandResolver.java:44`) is also suspect: `-q` suppresses INFO-level output and the Surefire per-class/summary `Tests run:` lines are logged at INFO, so a fully *passing* suite likely produces no parseable output either (failing suites print at ERROR and may parse). The runner's claimed purpose — "real passed/total instead of an LLM coverage guess" — is structurally defeated for the two biggest JVM ecosystems; combined with BUG-4 the real-test-exec path is reliable mainly for small pytest/go/cargo projects. Tests cover only the parser grammar with canned strings (`TestOutputParserTest`), never a real runner invocation, so this never showed.

**[BUG-6] Cost-history k-NN strict tier is dead for any multi-task run (writer/reader feature mismatch).**
Writer: `cost/CostHistoryService.extractTaskCount:174-178` **always records `taskCount=1`** ("Default to 1; future revision…"), and `extractRepoCount:180-189` derives repo count by counting commas in the raw `selectedRepoIds` JSON. Reader: `RunOrchestrator.java:2406-2408` queries `estimateFromHistory(strategy, provider, repoCount=computeRepoCountForEstimate(...), taskCount=complexities.size())`. `findSimilarCosts` (`RunCostHistoryRepository.java:31-44`) requires exact `taskCount` match → for every run with ≥2 planned tasks the strict query matches zero rows by construction and silently degrades to the loose strategy+provider query — the "repoCount+taskCount similarity" tier advertised in the query javadoc is unreachable. Also `verifiedScore` is always written `null` with a comment promising a "cost-history sweeper" backfill that does not exist anywhere in the repo (grep: no other writer of `RunCostHistory`) — dead column.

### Risks

**[RISK-1] Enterprise per-run model pin accepts unknown model ids (lenient `owner == null`).**
`integration/RunOrchestrator.java:908-918`: a pinned model whose `providerFor` is null (any typo, any unregistered id) is **accepted** and overlaid on all three tier cells via `RunModelOverrides.withRunPin`. Every subsequent LLM call dispatches the bogus id; provider returns 4xx which `LlmRouter.isTransientError` correctly refuses to retry → the run fails mid-pipeline after charging credits, instead of a 400 at submit. The leniency is deliberate ("forward-compat") but there is no distinction between "registry is behind" and "typo".

**[RISK-2] Cached input double-billed for claude-via-OpenRouter.**
`RunCostTracker.priceCall:690-696` applies the Anthropic token-shape rule (`normalIn = inTok`, no cacheRead subtraction) whenever `pricingProvider=="anthropic"` — which is exactly what `ownerProviderFor` produces for `openrouter` + `claude-*`. But OpenRouter speaks the OpenAI wire shape (`prompt_tokens` **includes** cached tokens — handled by `OpenAiNativeToolClient`), so any nonzero `cacheRead` on that route is billed once inside `normalIn` at full input rate *and* again at the cache rate. Tier-routed openrouter cells are all `claude-sonnet-4-6` (`application.yml:98-101`), so this is the *default* OpenRouter path, in the over-charging direction.

**[RISK-3] Case-sensitivity drift between the two scoring formulas.**
Additive guards switch on raw `testCoverageLevel` (`ValidationReportService.java:1168-1174` and `:1183-1190` — `case "NONE"`, `"PARTIAL"`, `"GOOD"`…) while the multiplicative path normalises via `toUpperCase()` (`testFactorFromCoverageLevel:1465-1471`) and `ReviewScoringService.llmCoverageLevel:236` also uppercases. An LLM emitting `"partial"` silently disables the B+E anti-gaming cap and the damped-coverage slot in the additive formula while still affecting the multiplicative one. One normalisation chokepoint is missing.

**[RISK-4] Report regeneration silently invalidates share links.**
`ValidationReportService.generateAndPersist:249-251` deletes the existing report row and inserts a fresh UUID — `shareToken`/`sharedAt` are not carried over, so a previously shared public URL 404s after any re-generate (rework/re-review path). No test covers this.

**[RISK-5] `ReviewScoringService.scanWorkspace` walk-limit applied before exclusions + heavy head-reads.**
`scoring/ReviewScoringService.java:274-277`: `Files.walk(root).filter(isRegularFile).limit(5000)` — the node_modules/.git/target exclusion happens *inside* the forEach, **after** the limit consumed entries. A workspace whose first 5,000 files are `node_modules/` (typical Node clone order) yields `sourceFilesChanged=0` → testCoverage dimension nulled and auth-hint signal lost. Additionally `readHead:321-327` does `Files.readAllBytes` (≤200KB) per file until an auth hint matches — worst case ~1GB of I/O per scoring pass for a 4,000-byte need.

**[RISK-6] Adaptive routing is a placebo: its data source is only the legacy router.**
`ModelPerformanceTracker.recordCall` is invoked **only** from `FallbackLlmRouter` (grep: no `recordCall` in `agent/LlmRouter.java`). The V2 router never records, so the worker/planner/reviewer provider×model cells stay at `UNKNOWN_SCORE` (`ModelPerformanceTracker.java:74-93`, `MIN_SAMPLE_COUNT=5`) and `isMateriallyBetter:117-123` returns false. If anyone flips `routing.adaptive-routing.enabled`, decisions ride success-rates of ~14 non-tool legacy agents only — never of the models actually being routed. Either wire `recordCall` into `LlmRouter.dispatchWithRetry` or delete the tracker + adaptive branch.

**[RISK-7] `LlmRouter.maybeFailover` can fail over a DEEPSEEK/FIREWORKS-primary run onto big-3 platform keys.**
`FAILOVER_EXCLUDED_PROVIDERS` (`LlmRouter.java:111-112`) excludes deepseek/openrouter/fireworks **as targets**, not as sources. A deepseek-primary run (system key → `source=PLATFORM`, since `RunKeyContext.keyFor("deepseek")` is null per BUG-2) with the V226 flag ON walks the CAPABLE chain (`fallbackChainFor` of an unknown/`deepseek-*` model → default "CAPABLE", `ModelRegistry.java:233-237`) → claude-sonnet on the platform Anthropic key. For a benchmark or a cost-sensitive operator route ("deliberate operator route", `LlmProvider.java:18`), a silent mid-run switch to a 10×-priced model is surprising; the BYOK guard at `LlmRouter.java:314` does not trigger because the deepseek key was never `Source.USER/ORG`. Flag is default OFF — design-time risk for the redesign.

### Smells / inconsistencies / duplication

- **[INCONSISTENT]** `ResolvedModel.providerName()` javadoc (`router/ResolvedModel.java:21-26`) explains a `LlmProvider#GOOGLE` mapping; `GOOGLE` no longer exists in the enum. The method body contradicts its own doc.
- **[INCONSISTENT]** `ProviderHealthTracker` javadoc key-design block says TTL 60s/120s (`ProviderHealthTracker.java:24-26`); constants are 180s/300s (`:36-38`).
- **[INCONSISTENT]** `ModelMatrixProperties.providers` javadoc says keys are `"anthropic", "google", "openai"` (`ModelMatrixProperties.java:22`); actual YAML keys are anthropic/gemini/openai/openrouter/deepseek.
- **[INCONSISTENT]** `RunRubricRow.verificationSpec` javadoc documents `path:regex` single-colon (`scoring/entity/RunRubricRow.java:76-78`); `RunRubricVerifier.verifyFileContent:203-208` requires `::`.
- **[INCONSISTENT]** `UsageReportService.estimateCost:284-286` hardcodes blended-Haiku $0.80/$4.00 — both stale vs the matrix ($1.00/$5.00, `application.yml:136-139`) and a hardcoded-pricing survivor of the YAML consolidation. Fallback-only (B2 prefers `actual_cost_usd`), but actuals are $0 for OpenRouter-only models (BUG-1) → this wrong fallback actually fires for exactly those orgs.
- **[DUPLICATION]** `ProviderStatusService.FAILOVER_ORDER` (`:45-49`) is a hardcoded copy of `application.yml failover-order:` (373-382). Dead code anyway (§3), but if revived it drifts.
- **[DUPLICATION]** Credits/hard-cap formula duplicated with diverging constants: `RunCostTracker.estimateRun:256` uses `(low+high)/2*1.10` while `estimateRunTierAware:323` uses `BUFFERED_MULTIPLIER=1.20`, and `CostHistoryService.estimateFromHistory:149-150` re-inlines `*1.20 / 5.0` magic numbers instead of the constants. Three places to update on a credit-policy change.
- **[DUPLICATION]** `detectLanguage` + `truncate` exist in both `ValidationReportService.java:717-726,1643-1646` and `ValidationReportGenerator.java:363-382` — with *different* language sets (7 vs 12 entries), so the report narrative and the persisted `languages` column can disagree on the same run.
- **[DUPLICATION]** `AvailableProviderResolver.providerToString:112-121` re-implements `name().toLowerCase()` as a switch that must be hand-extended per enum value (the uncommitted diff did exactly that, on dead code).
- **[SMELL]** `ModelResolver` uses field injection `@Autowired(required=false) FeatureFlagService` (`ModelResolver.java:48-49`) — the only field injection in the batch, violating the project's constructor-injection rule; the test-convenience rationale would be equally served by an `ObjectProvider` constructor param (cf. `ProviderRecommendationService.java:33`).
- **[SMELL]** Six agent names are resolved that are absent from `agent-tier-map` (`application.yml:346-372`): `"ChatService"` (`chat/ChatService.java:128,135`), `"SpecChatService"` (`specchat/SpecChatService.java:131,138`), `"ApplyService"` (`chat/ApplyService.java:122,129`), `"ProjectChatAgent"` (`memory/ProjectChatService.java:339`), `"PlannerAgent"` (`execution/service/ExecutionPlannerService.java:126`), `"FilePurposeSummaryService"` (`codeintel/FilePurposeSummaryService.java:145`). The first four pass an explicit tier override, but `resolve(name, provider, tierOverride)` (`ModelResolver.java:170-180`) first runs the 2-arg resolve → logs the `No tier mapping … defaulting to WORKHORSE` WARN **on every chat message** and double-resolves. The last two are 2-arg → silently land on WORKHORSE; "PlannerAgent" reads like it wanted PLANNING. (Currently masked because all three tiers are the same model per provider — `application.yml:86-105` is all-flagship — but it's a landmine for the redesign that reintroduces tier differentiation.)
- **[SMELL]** Dead ternary: `ValidationReportService.extractIssues:359-369` computes `compilerNote` with a `passed() ? "Resolved by CompilerAgent…" : …` branch inside a block guarded by `!passed()` — first branch unreachable.
- **[SMELL]** `TestCoverageRescorer.java:51-59` logs "testCoverageScore {} → {}" *after* `setTestCoverageScore(score)` — old and new value print identically.
- **[SMELL]** `computeVerifiedScoreAdditive`: `codeDelivered` is provably true after the early-return at `ValidationReportService.java:1113-1117`, so `codeScore = codeDelivered ? 100.0 : 0.0` (`:1124`) and the `codeDelivered &&` in the guard (`:1128`) are dead conditions.
- **[SMELL]** `ModelRegistry.workhouseModelFor` — typo'd public API name ("workhouse") with 6 call sites cementing it.
- **[SMELL]** `ValidationReportGenerator` reads its prompt via `promptTemplate.getContentAsString` (`ValidationReportGenerator.java:45-51`) — the raw-resource pattern the codebase elsewhere routes through `PromptAssembler`; also still on the deprecated `FallbackLlmRouter` (`:39`, documented-allowed for non-tool agents, but it is the path that keeps `recordCall`/pin-store half-alive).
- **[PERF]** `RunCostTracker.record` logs at INFO per LLM call with 12 args (`:184-199`) — that's one log line per worker iteration; fine today, but it is the hottest line in the batch.
- **[PERF]** `OrgModelOverrideService.currentOverrides`/`overridesForRun` do `orgRepo.findById` per call with a 5-min Caffeine cache (`:47-60`) — fine; noted only because `UsageReportService.collectOrgUsage:149-167` does 2×`userRepo.findById` per top-user row (≤10 queries — acceptable).

---

## 3. Dead code & junk (grep-verified, incl. src/test + frontend)

| Item | Evidence | Notes |
|---|---|---|
| **`AvailableProviderResolver` (entire class)** | Only reference outside itself is a *comment* at `auth/service/OrgService.java:525`; no injection site, no test (`grep -rn AvailableProviderResolver src/ frontend/`) | A Spring bean reading 3 `spring.ai.*` keys that nobody calls. The uncommitted diff extends its switch for FIREWORKS — editing dead code. Section 4(f) answer: **nothing consumes it.** |
| **`ProviderStatusService` (entire class)** | No injection site (`grep -rln ProviderStatusService` → only itself); `provider:status:*` Redis keys written at `:121-122` are read **nowhere** (grep incl. frontend); `isAnyProviderHealthy`, `recordQueuedDueToOutage`, `recordOutageQueueReleased` have zero callers | Worse than dead: `@Scheduled(fixedDelay=60_000)` (`:111`) actively polls status.anthropic.com / status.openai.com / status.cloud.google.com every 60s **in every running instance** and writes Redis keys nobody reads. The `queuedDueToOutage` gauge is registered and forever 0. Delete or wire. |
| **`ProviderRecommendationService` (entire class)** | Zero callers (`grep -rn "ProviderRecommendationService\|recommend(" src/` → only itself) | The `provider.smart-routing.enabled` flag gates unreachable code; `reasonCode` + the `provider_recommendation_log` ambition are dead. |
| **`ModelTaskMetric` + `ModelTaskMetricRepository`** | Zero references outside the two files | Entity for table `model_task_metric` with no writer and no reader — "Collected from day 1, used … in Phase 2" (`ModelTaskMetric.java:13-15`) never happened. Dead table + dead JPA scan weight. |
| **Scoring Revamp Phase A: `RunRubricVerifier` + `RunRubricRow` + `RunRubricRowRepository`** | `verifyRubric` has no production caller (only `RunRubricVerifierTest`); nothing in `src/main` builds a `RunRubricRow` | The planner never emits rubric rows, so the V197 table is empty in every environment and the 23 tests pin a feature that cannot fire. Contains the unsandboxed shell (see §7). |
| **`ModelResolver.resolveFallbackExcluding`** | Zero callers incl. tests (`grep -rn resolveFallbackExcluding`) | "Mid-task recovery" public API never wired. |
| **`ModelRegistry.notesFor`** | Zero callers (`grep -rn "\.notesFor("` → none); its javadoc claims `ModelPricingInitializer` uses it, which actually reads `config.getNotes()` directly (`ModelPricingInitializer.java:79`) | Dead method with a false javadoc. |
| **`RunCostHistory.verifiedScore` column** | Always written `null` (`CostHistoryService.java:87-90`); the promised "cost-history sweeper" backfill does not exist (no other writer of `RunCostHistory` in repo) | Dead column + dead promise. |
| **`RunProviderPinStore`** | Callers only in `FallbackLlmRouter` (+ its test) | Legacy-only; survives correctly per the deprecation policy but belongs on the redesign's kill-list with `FallbackLlmRouter`. |
| **Stale comment in `OpenRouterModelMapper.java:13-16`** | Claims "every actively tier-routed matrix model has an exact OpenRouter slug … verified 2026-05-21" — five non-tier-routed ids were since added; header no longer describes the list | Cosmetic. |
| TODO/FIXME census | `grep -rn "TODO\|FIXME\|XXX"` over all six packages → **zero hits** | The junk here is dead *classes* and stale *javadoc*, not TODO markers. Stale-promise comments quoted verbatim above: `ModelTaskMetric.java:13-15` ("Phase 2"), `CostHistoryService.java:87-90` ("fetched later when the cost-history sweeper backfills"), `RunCostTracker.java:717-719` ("every matrix model is claude-*/gpt-*/gemini-*"). |

NOT dead (verified, despite looking suspicious): `ValidationReportRepository`'s seven "v3 landing" native queries are all consumed by `landing/LandingController.java`; `CliSmokeRunner` is used by `RunOrchestrator`; `VerifiedScoreBackfillService` is wired to `SuperAdminController`; `freeTierModel/freeTierProvider` are used by `FallbackLlmRouter` (legacy-only).

---

## 4. Model/LLM touchpoints — exhaustive map

### (a) Public API of `ModelRegistry` + `ModelResolver`, with grep-verified callers

**`ModelRegistry` (`router/ModelRegistry.java`)** — stateless façade over `ModelMatrixProperties`; `@PostConstruct validate()` only `log.error`s on bad config (boot never fails on a broken matrix — a deliberate but redesign-relevant choice, `:37-90`).

| Method | Callers (src/main) |
|---|---|
| `canonicalize(String)` `:112` | `RunOrchestrator:907` (pin validation), `OrgModelOverrideService:172` |
| `providerFor(String)` `:135` | `OrgController`, `RunOrchestrator:909`, `RunPipelineSupport`, `RunValidationKickoff`, `OrgModelOverrideService:183` |
| `isKnownModel(String)` `:141` | `OrgModelOverrideService:173` only |
| `contextLimitFor(String)` `:150` | `agent/TokenBudgetMonitor:38` only |
| `contextLimitForOrPrefixFallback(String)` `:172` | `memory/ProjectChatController` (chat-header context chip) |
| `maxOutputForModel(String)` `:190` | `agent/TokenBudgetMonitor:84,153,198` |
| `pricingFor(String)` `:199` | `agent/CostEstimator:354` only |
| `notesFor(String)` `:206` | **none — dead** |
| `displayLabelFor(String)` `:218` | `api/controller/ModelController:68,79…`, `api/controller/PricingController` |
| `fallbackChainFor(String)` `:233` | `agent/LlmRouter.maybeFailover:302` (flag V226, default OFF), `agent/FallbackLlmRouter:912` |
| `fallbackChainForTier(String)` `:240` | `agent/FallbackLlmRouter:915` (CAPABLE default) |
| `freeTierModel()/freeTierProvider()` `:254,261` | `FallbackLlmRouter` only (FREE-tier lock is enforced only on the legacy path) |
| `workhouseModelFor(provider)` `:269` [sic] | `ConversationAgent`, `CostEstimator`, `ApiTestSuiteProperties`, `OrgController`, `RunContextComposer`, `RunOrchestrator` |
| `planningModelFor(provider)` `:276` | `OrgController`, `config/AgentModelConfig`, `optimiser/PromptOptimiserProperties` |
| `lightweightModelFor(provider)` `:283` | `ConversationAgent`, `CostEstimator`, `ConversationController`, `OrgController`, `context/ContextAgentProperties`, `eval/LlmJudgeValidator` |
| `allModels()` `:293` | `PricingController`, `OrgController`, `config/ModelPricingInitializer:41` |

**`ModelResolver` (`router/ModelResolver.java`)**

| Method | Callers |
|---|---|
| `resolve(agentName, provider)` `:59` | 38 files (full list captured in review): every tier-routed agent — `SequentialExecutor.tierConfigFor:1318` (worker), `OutlinePlannerAgent`, `ReviewerAgent`, `SmartClassifierAgent`, `TaskExpanderAgent`, `CoherencePlannerAgent`, `codeintel/*` (5), `testgen/*` (3), `prreview/PrReviewAgent`, `codereview/CodeReviewAgent`, `memory/*` (4), `chat/*` (2), `specchat/SpecChatService`, `ValidationReportGenerator:58`, `RunCostTracker:340,583` (estimates), `CostEstimator`, `eval/v2/TierEvalRunner:90,406`, `ExecutionPlannerService:126`, `RunValidationKickoff`, `RunLifecycleService`, `apitests/ApiTestSuiteAgent`, `optimiser/PromptOptimiserAgent`, `tool/{ToolOutputCompactor,WorkerHistoryCompactor}`, `ProjectChatController` |
| `resolve(agentName, provider, tierOverride)` `:170` | `SequentialExecutor.tierConfigFor` (complexity→tier), `RunCostTracker.{computeWorkerCost,ratesForTier}`, chat services (see §2 SMELL), `TierEvalRunner` |
| `resolveFallbackExcluding(...)` `:192` | **none — dead** |

### (b) Exact resolution chain for a model lookup, as implemented

For a tier-routed agent call (`ModelResolver.resolve`, `router/ModelResolver.java:59-167` + `lookupModel:258-276`):

1. **Agent → tier**: `matrixProps.getAgentTierMap().get(agentName)` (`:60`); missing → WARN + `WORKHORSE` default (`:61-65`). Tier override variant re-resolves base first (`:170-180`).
2. **Circuit breaker**: `ProviderHealthTracker.isHealthy(userProvider)` (`:69`) — Redis `provider:health:{NAME}`, 10 consecutive failures → open 180s, fail-open on Redis errors (`ProviderHealthTracker.java:36-58`). Note: only `FallbackLlmRouter` ever calls `recordFailure/recordSuccess`, so for V2 traffic the circuit can only be opened by legacy-agent failures.
3. **Adaptive override** (healthy path only): gated `!Boolean.getBoolean("executespec.strict-provider") && featureFlagService.isEnabled("routing.adaptive-routing.enabled")` (`:84-92`), then `selectAdaptiveProvider` consults `ModelPerformanceTracker.isMateriallyBetter` (≥10pp, ≥5 samples each side) — effectively inert (RISK-6).
4. **Cell lookup** `lookupModel(provider, tier)` (`:258`):
   a. `matrixProps.getProviders().get(provider.name().toLowerCase())` — **null → throw IllegalStateException** (`:261-263`). This happens **before** any override consultation → a provider absent from YAML (FIREWORKS today) is unroutable even with a pin (BUG-2).
   b. `RunModelOverrides.lookup(provider, tier)` (`:267`) — ThreadLocal map keyed `"<provider>:<tier>"` (`router/RunModelOverrides.java:80-87`). Bound once per run on the pipeline thread at `RunOrchestrator.java:941-942` as `withRunPin(orgOverrides, pinProvider, validatedPin)`:
      - **org overrides** = `OrgModelOverrideService.overridesForRun(orgId)` — Org JSON column, validated lenient (drops now-invalid cells, `OrgModelOverrideService.java:62-83`), 5-min Caffeine cache;
      - **run pin** (ENTERPRISE, `RunRequest.modelName`) overlays all three tier cells of the pinned provider (`RunModelOverrides.withRunPin:66-77`) — pin > org cell > YAML. Pin validation is provider-scoped but lenient on unknown ids (RISK-1).
   c. YAML default: `providers.<provider>.{planning|workhorse|lightweight}` (`application.yml:85-105`).
5. **Failover** (unhealthy primary): walk `failover-order.<provider>` (`application.yml:373-382`; defined only for anthropic/gemini/openai — OPENROUTER/DEEPSEEK/FIREWORKS have no chain) re-checking health (`:124-152`); all unhealthy → force the user's provider anyway (`:154-166`).
6. **Aliases**: `legacy-aliases` (`application.yml:320-326`) are applied in `ModelRegistry.canonicalize` — i.e. on *metadata lookups, org-override writes, and pin validation*, **not** inside `ModelResolver` (the tier cells are expected to be canonical; `validate()` checks that at boot).

Worker specifics: `SequentialExecutor.tierConfigFor:1192-1340` maps task complexity LOW→LIGHTWEIGHT / MEDIUM→WORKHORSE / HIGH→PLANNING, then `resolve("WorkerAgent", provider, tier)`; on **any** resolution exception it silently falls back to the run-level `{provider, model}` passthrough (`:1325-1339`) — the only place an unregistered provider/model pair can still execute (and the accidental enabler of TB fireworks runs).

Dispatch-side mappings after resolution (`agent/LlmRouter.dispatch:398-412`): `openrouter` → `OpenRouterModelMapper.toSlug` (fail-fast allow-list, 13 ids); `deepseek` → `stripNativeSuffix` (`:662-667`); `fireworks` → `FireworksModelMapper.toSlug` (allow-list **plus** a pass-through for any id already starting `accounts/fireworks/models/` — `FireworksModelMapper.java:38-41` — which quietly weakens the "never send an unverified slug" guarantee the OpenRouter mapper enforces).

### (c) Everywhere model PRICING is read, and the missing-model behavior

Single persistence source: `model_pricing` table, **rebuilt on every boot** from `model-matrix.models[*].pricing` by `ModelPricingInitializer.seed` (`config/ModelPricingInitializer.java:39-86`) — upsert + delete-stale, keyed `(models[id].provider, id)`. YAML `pricing` block = `input-per-1m` / `cached-input-per-1m` / `output-per-1m` (`ModelMatrixProperties.Pricing:79-84`).

Readers:
1. **`RunCostTracker.priceCall`** (`cost/RunCostTracker.java:665-699`) — the actuals path, called from `record(...)` ← `LlmRouter.dispatchWithRetry:230` on every successful call. Provider key rewritten for openrouter via `ownerProviderFor` (BUG-1). **Missing model/row ⇒ `return 0.0` + DEBUG log** (`:673-677`) — tokens recorded, dollars silently zero; pinned by `RunCostTrackerCachePricingTest.noPricingRow_returnsZero:161-166`. Cache math: `normalIn` (Anthropic-shape no-subtract vs OpenAI/Gemini subtract, `:690-691`), cacheWrite ×1.25 input (Anthropic-only by data, `:695`), cacheRead at YAML `cached-input-per-1m` with a `inRate*0.10` fallback when the column is null (`:684-685`).
2. **`RunCostTracker.estimateRun`** (`:235-260`) — `pricingRepo` lookup with `providerKey(provider)` (no owner rewrite — asymmetric with #1); missing row ⇒ rates 0.0 ⇒ estimate $0 ⇒ `credits = max(1, …) = 1`.
3. **`RunCostTracker.estimateRunTierAware` family** (`:285-345`, `:301-327`, `:427-435`) — per-tier rates via `ratesForTier` → `modelResolver.resolve("WorkerAgent", p, tier)` + `rates()` (`:581-608`); any exception ⇒ `{0.0, 0.0}` (silent $0 phase estimates). Token bands + phase constants `:437-531`. Caller: `RunOrchestrator:2411` (after the k-NN attempt, BUG-6).
4. **`RunCostTracker.computeCost`** (`:644-646`) — public no-cache-fields helper; callers: `codeintel/{ProjectDocGeneratorService:256, ProjectArchitectureService:244,399, FilePurposeSummaryService:169}`, `tool/ToolOutputCompactor:228`. Same silent-$0 on missing row.
5. **`ModelRegistry.pricingFor`** (`router/ModelRegistry.java:199-203`) — YAML-direct (not the table); caller `agent/CostEstimator:354`. Returns `null` for unknown ⇒ caller's concern.
6. **`api/controller/PricingController`** — `allModels()` + `getChainTier` (`:48`) → frontend pricing table (`frontend/src/ide/IdeBilling.tsx`, `lib/api/core.ts` `chainTier`).
7. **`UsageReportService.costOrFallback/estimateCost`** (`report/UsageReportService.java:273-286`) — prefers summed `actual_cost_usd`; hardcoded Haiku-blend fallback (stale rates, §2).

Bottom line for (c): **a model id absent from the matrix is silently $0 everywhere on the actuals path** (DEBUG-level breadcrumb only), and BUG-1 makes *registered* OpenRouter-only models behave as if absent.

### (d) `OpenRouterModelMapper` + `FireworksModelMapper` allow-lists vs the YAML models list

YAML `model-matrix.models` (15 ids, `application.yml:107-318`): `claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5-20251001`, `gemini-2.5-flash`, `gemini-3-flash-preview`, `gemini-3-pro-preview`, `gemini-3.5-flash`, `gpt-5.4`, `gpt-5.4-mini`, `claude-opus-4-7`, `gpt-5.5`, `gpt-5.5-mini`, `deepseek-v4-pro`, `deepseek-v4-pro-native`, `deepseek-v4-flash-native`, `deepseek-v4-flash`, `kimi-k2.6`, `qwen3-coder`, `mimo-v2.5-pro` — (19 incl. the four `-native`/openrouter twins; 15 distinct base models).

**OpenRouter allow-list (13)** vs YAML:
- In BOTH: `claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5-20251001`, `gpt-5.4`, `gpt-5.4-mini`, `gemini-3.5-flash`, `gemini-3-flash-preview`, `deepseek-v4-pro`, `deepseek-v4-flash`, `kimi-k2.6`, `qwen3-coder`, `mimo-v2.5-pro`.
- In mapper but NOT in YAML models: **`claude-haiku-4-5`** (`OpenRouterModelMapper.java:27`) — it is a *legacy alias* (`application.yml:323`), not a canonical id; org-override validation canonicalises before the allow-list check (`OrgModelOverrideService:172-178`), so this entry is reachable only by a raw pre-canonical id — harmless but redundant.
- In YAML but NOT in mapper (→ `toSlug` throws if ever routed via openrouter): `gemini-2.5-flash`, `gemini-3-pro-preview` (deliberate, per comment), `claude-opus-4-7`, `gpt-5.5`, `gpt-5.5-mini` (ENTERPRISE-pin Beta models — an Enterprise org **cannot** set them as openrouter cells, fine, but note `OrgModelOverrideService` *will* accept them for their native provider cells), `deepseek-v4-pro-native`, `deepseek-v4-flash-native` (native-route twins, correct to omit).

**Fireworks allow-list (5)** vs YAML: `qwen3-coder-fw`, `deepseek-v3-fw`, `deepseek-v3p1-fw`, `kimi-k2-fw`, `glm-4p6-fw` — **zero overlap with YAML**; none registered, none priced, none pickable (BUG-2). Also note the slugs reference different model *generations* than the matrix's OpenRouter twins (`deepseek-v3`/`v3p1` vs matrix `deepseek-v4-*`; `kimi-k2-instruct` vs `kimi-k2.6`) — if the intent was apples-to-apples latency comparison, these are not the same models. The class header itself says "verify … and refine if one 404s post-smoke" — i.e. unverified slugs, contradicting the "explicit allow-list" discipline, plus the full-slug pass-through (`:38-41`) accepts arbitrary strings.

### (e) chain-tier (ARCHITECT/CAPABLE/UTILITY) vs agent tiers (PLANNING/WORKHORSE/LIGHTWEIGHT)

Two disjoint taxonomies that never meet in code:
- **AgentTier** is a *selection* axis: `agent-tier-map` (26 entries, `application.yml:346-372`; all 26 grep-verified as actually resolved) + `providers.<p>.<tier>` columns decide which model an agent gets (`ModelResolver`).
- **chain-tier** is a *per-model* attribute (`ModelConfig.chainTier`) used only to pick a **failover chain** after a model is already chosen: `ModelRegistry.fallbackChainFor(model)` → `fallback-chains.{ARCHITECT|CAPABLE|UTILITY}` (`application.yml:328-340`); unknown model/tier defaults to CAPABLE (`ModelRegistry.java:235,245-248`).
- Consumers of the chains: (1) `FallbackLlmRouter:912-915` — the legacy multi-provider fallback (live for ~14 non-tool agents); (2) `LlmRouter.maybeFailover:302` — V2 multi-provider failover, **flag `router.failover.enabled` (V226) default OFF** → on the V2 path the chains are currently *not consumed in production*; (3) `PricingController:48` exposes `chainTier` as display metadata to the frontend pricing table. Nothing else reads it.
- Inconsistency to carry into the redesign: the UTILITY chain's second entry is gemini-3-flash-preview *before* openai (`application.yml:337-340`) while every `failover-order` puts openai ahead of gemini for anthropic — two failover orders (model-chain vs provider-order) encode different opinions, and a third hardcoded copy lives in dead `ProviderStatusService`.

### (f) `AvailableProviderResolver` — availability logic and consumers

Logic (`router/AvailableProviderResolver.java:54-110`): merge system keys (`spring.ai.anthropic.api-key`, `spring.ai.openai.api-key`, `spring.ai.google.genai.api-key`) per `keySourceMode` SYSTEM/OWN_KEYS/HYBRID with `ResolvedKeys` user/org keys (+ openRouterKey ⇒ OPENROUTER), filter by org `enabledProviders`, fall back to system providers, last-resort `{ANTHROPIC}`. Notably it can never return DEEPSEEK or FIREWORKS (no key fields exist on `ResolvedKeys`, `agent/ResolvedKeys.java:16-28`), so the `providerToString` arms for them (`:118-119`, incl. the uncommitted one) are unreachable.

**Consumers: none.** `resolve(runId)` has zero call sites; the only textual reference is a comment in `OrgService.java:525`. The UI's actual provider/model surfaces are `ModelController GET /api/models/available` (iterates `matrixProps.getProviders()` — so "fireworks" will not appear until a providers block exists) and `OrgController.modelsByProvider` → `selectableModelsFor` (`OrgController.java:331-352`, openrouter = mapper allow-list keys). Provider availability-by-key is effectively re-decided ad hoc at dispatch time (`LlmRouter.callOpenRouter/callDeepSeek/callFireworks` throw `IllegalStateException` when no key, `LlmRouter.java:579-583,609-613,640-644`).

### YAML key cross-check (`model-matrix.*`)

- **Read-and-defined**: `providers` (5 provider blocks), `models`, `legacy-aliases` (all 6 targets exist), `fallback-chains` (all 9 entries reference registered models), `free-tier` (gpt-5.4-mini/openai — note free-tier lock is enforced only via `FallbackLlmRouter`), `agent-tier-map` (26/26 names resolved somewhere), `failover-order` (3 chains; validated by `FailoverOrderEnumAlignmentTest` per its name — exists under `arch/`).
- **Read-but-undefined**: `providers.fireworks` (read implicitly by `lookupModel` for provider FIREWORKS — throws; BUG-2). Six agent names are *looked up* in `agent-tier-map` but not defined (see SMELL above) — they hit the WARN+WORKHORSE default path.
- **Defined-but-never-read**: none at the key level — every `ModelMatrixProperties` field has live readers; per-model `notes` reaches the DB via `ModelPricingInitializer` (though `ModelRegistry.notesFor` is dead). `gemini-2.5-flash` and the `gpt-5.5*`/`claude-opus-4-7` entries are registry-resident but not tier-routed (pin/back-compat only — by design).
- Profile YAMLs (`application-{dev,prod,benchmark}.yml`): no `model-matrix` overrides; deepseek/openrouter `spring.ai` blocks present in all three, **fireworks block in none** (base only) — benchmark profile TB runs on fireworks will work only via env-var/`-D` override of `spring.ai.fireworks.api-key`.

---

## 5. Libraries

- **Spring AI 1.1.x** — only Anthropic uses the Spring AI `ChatModel`; the batch's exposure is `ChatResponse`/`Usage`. `RunCostTracker.record` extracts Anthropic cache fields **by reflection** (`callUsageMethod0("getNativeUsage")` → `cacheCreationInputTokens()`, `RunCostTracker.java:129-144,736-760`) — silently returns 0 on any Spring AI upgrade that renames these; there is no canary test that would catch the regression (cache pricing tests stub the repo, not the Usage shape).
- **Caffeine** — `OrgModelOverrideService` (5-min/10k) and `LlmRouter.anthropicByKey` (128/1h, SHA-256-keyed). Appropriate.
- **Micrometer** — counters/gauges in resolver/health-tracker/status-service; the `executespec.provider.outage.queued_runs` gauge is dead (§3).
- **Jackson** — `ObjectMapper` injected except `VerifiedScoreBackfillService` which news up its own static `MAPPER` (`:43`) — inconsistent but harmless.
- **Thymeleaf + Flying Saucer (`org.xhtmlrenderer.pdf.ITextRenderer`)** — `ValidationReportPdfService`. Flying Saucer's PDF output rides the ancient iText 2.x lineage (OpenPDF in newer versions — worth confirming which artifact the pom pins; iText 2.x has known CVEs and an LGPL/MPL license question). Markdown "stripping" before PDF is 3 regexes (`:84-89`) — bold/italic/header only; links/tables/code fences leak raw into the PDF.
- **`java.net.http.HttpClient`** — static client in dead `ProviderStatusService`.
- **JPA/Hibernate + Postgres native queries** — `ValidationReportRepository` uses `COUNT(*) FILTER (WHERE …)` (Postgres-only, fine given Cloud SQL; breaks H2-style test slices).
- No new third-party deps introduced by the uncommitted Fireworks work (good — reuses `OpenAiNativeToolClient`).

## 6. Test coverage

20 test files / 2,830 LOC, all unit-level (no Spring context in scope; no integration test touches the DB-seeded pricing path).

- **Strong**: the verifiedScore formula family — `ValidationReportServiceVerifiedScoreTest` (23, pins multiplicative), `…AdditiveScoreTest` (15, incl. gaming-guard tighten cases), `…SpecAwareScoringTest` (18), `…RubricScoreTest` (13), `…R09Test` (14, reconcile), `…CountUnfixedCriticalTest` (8), `…BootVerificationTest` (5). `RunRubricVerifierTest` (23) — thorough, but pins a dead feature. `RunCostTrackerCachePricingTest` (8) pins cache math per provider shape + the silent-$0 contract. `RunModelOverridesResolverTest` (7) pins pin>org>YAML incl. all-three-tiers overlay. `OrgModelOverrideServiceTest` (9) pins validation incl. the openrouter allow-list branch. `ModelResolverAdaptiveOptInTest` (5) pins the Fix-G opt-in gate. `ModelRegistryContextLimitTest` (11) pins prefix fallbacks + display labels. `TestOutputParserTest` (9) pins all five grammars. `RunCostTrackerEstimateStage1Test` (5), `RunCostTrackerReasoningTest` (4), `BuildCommandResolverRnTest` (4), `OpenRouterModelMapperTest` (4 — **not updated for `mimo-v2.5-pro`**).
- **Zero coverage** (in-scope classes with no test at all): `FireworksModelMapper` (new, uncommitted), `AvailableProviderResolver`, `ProviderHealthTracker`, `ProviderStatusService`, `ModelPerformanceTracker`, `ProviderRecommendationService`, `RunProviderPinStore`, `ModelResolver` failover/forced paths (`:124-167`) + `resolveFallbackExcluding`, `CostHistoryService` (percentile/feature-extraction logic untested), `RunTokenAttributor` (residual-split logic untested), `UsageReportService`, `ValidationReportGenerator`, `ValidationReportPdfService`, `ReviewScoringService` (whole 6-dimension scorer + workspace scan), `TestCoverageRescorer`, `CliSmokeRunner`, `BuildCommandResolver` non-RN branches, `ModelPricingInitializer`.
- **Tests that mask bugs**: `VerifiedScoreBackfillServiceTest` (wrong JSON shape — BUG-3); `WorkspaceTestRunnerTest` (2 tests, only the no-runner/null-workspace short-circuits — never executes a process, so BUG-4/BUG-5 invisible); `RunCostTrackerCachePricingTest` seeds rows under the *owner* provider key, never exercising the openrouter-seeded-row path (BUG-1 invisible).

## 7. Security notes (parked)

- **Unsandboxed LLM-authored shell in `RunRubricVerifier.verifyShell`** (`scoring/RunRubricVerifier.java:158-181`): runs `verificationSpec` via `/bin/sh -c` on the host with no `ShellSandboxPolicy`, no env hardening, no network isolation — the class's own javadoc calls the spec "untrusted" and only path-checks the *file* handlers. Currently unreachable (no rubric writer, §3), but it is a loaded gun: the day a planner emits rubric rows, prompt-injected spec text reaches a raw host shell. Either delete with the dead feature or route through `ShellSandboxPolicy` like `WorkspaceTestRunner` does (`WorkspaceTestRunner.java:76-84` is the good example: resolver-derived command + sandbox validate + cleanroom container + env-clear).
- `WorkspaceTestRunner`/`CliSmokeRunner`/`BuildCommandResolver` host-shell execution matches the documented buildProject trust boundary; env hardening (`hardenEnv`, PATH/HOME/JAVA_HOME only) is present in both runners.
- `FireworksModelMapper.toSlug` full-slug pass-through (`:38-41`) lets any caller-supplied `accounts/fireworks/models/…` string go to the wire — combined with the lenient run-pin (RISK-1) this is the only model-id path with no allow-list at all (Enterprise-only inputs, low severity, but contrary to the OpenRouter mapper's stated design).
- Share tokens: `SecureRandom` 32B URL-safe, single-use lookup, no expiry (`ValidationReportService.java:711-715`) — fine; regeneration invalidation is RISK-4 (availability, not confidentiality).
- BYOK: per-key Anthropic model cache keyed by SHA-256(key) (`LlmRouter.java:115-122,490-516`) is sound; the false BYOK claims for deepseek/fireworks (BUG-2) are a *billing/data-residency expectations* problem, not a key-leak problem.
