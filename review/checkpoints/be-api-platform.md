# be-api-platform — raw findings

Scope read in full: `api/` (47), `validator/` (27), `readymade/` (24), `landing/` (4), `onboarding/` (5), `anonrun/` (7), `contact/` (4), `analytics/` (6), `assessment/` (2), `notification/` (10), `ratelimit/` (3), `security/` (7), `crypto/` (4), `observability/` (1), `config/` (23), `util/` (3). Matching tests skimmed. Branch `benchmark`, working tree as-is. Conclusions are from code + `application*.yml` + repo-wide grep (incl. `src/test`, `frontend/`) only.

Key cross-cutting fact that drives several verdicts: **every registered user gets a personal org auto-provisioned and is set as `ORG_ADMIN` of it** (`AuthService.register` → `OrgService.bootstrapPersonalOrg`, sets `user.orgId` + ORG_ADMIN). So (a) `user.getOrgId()` is essentially never null for a real user, and (b) `guard.requireOrgAdmin()` passes for **every** authenticated user. This makes some "null-org → global" branches harmless but turns several `requireOrgAdmin`-gated `findAll()` reads into cross-tenant leaks.

---

## 1. Inventory (LIVE/DORMANT verdicts for the questionable packages)

| Package | Verdict | Evidence |
|---|---|---|
| **validator/** (27 files) | **DORMANT / vestigial** | `@Component`/`@Service` beans exist (`CodeValidatorImpl`, `GateChain`, 5 `*Toolchain`, parsers) so Spring instantiates them, but repo-wide grep finds **zero** callers of `CodeValidator`, `validateFile`, `validateProject`, `GateChain.execute`, or any `com.navneet.executespec.validator.*` type **outside the package itself** (main + test). No `@RestController`, **no tests** (`src/test/.../validator` does not exist). Its own `CLAUDE.md` claims results "feed into ValidationReportService" — false; `ValidationReportService` does not import it. Dead framework that still boots beans + logs "Validator toolchain ready/not installed" per language on startup. ~2K LOC of inert code. |
| **readymade/** (24 files) | **LIVE** | 28 seed YAMLs in `resources/readymades/`, `ReadymadeSeeder.onStartup()` loads on boot, `/library` + `/library/:slug` routes wired in `frontend/src/App.tsx`, consumed by `IdeDiscovery.tsx` + `discovery.ts`. `instantiate` dispatches a real `ExecutionRun`. |
| **landing/** (4) | **DORMANT (backend) — no live consumer** | `LandingController` mounted `/api/v3/landing/*` + `/api/v3/public/exec-snapshot`, permitAll in SecurityConfig. **Zero frontend callers** — `getLandingStats`/`getExecSnapshot`/`getSampleRuns` not defined anywhere in `frontend/src`; `/landing` route is a `LegacyRedirect`. Endpoints are reachable but nothing calls them. |
| **onboarding/** (5) | **LIVE** | `listOnboardingSamples()` + `pickOnboardingSample()` + `getSandboxStatus()` consumed by `frontend/src/ide/IdeSurfaces.tsx` (compose page "start from a sample" row). |
| **anonrun/** (7) | **DORMANT** | `/api/v3/anonymous-runs` permitAll, but (a) **no frontend caller** (no `anonymous-run`/`claimAnonymous` reference in `frontend/src`), and (b) the only dispatcher is `StubAnonymousRunDispatcher` returning a hardcoded "Sign up to ship" — the real LLM dispatcher behind `v3.anonymous-trial.real-dispatch` (default OFF) **was never written** (the interface Javadoc lists it as TODO). Endpoint works but is a stub with no UI. |
| **assessment/** (2) | **LIVE** | `FeatureAssessmentService.assessAsJson` called by `RunOrchestrator` (project-context runs); result surfaced via `RunMapper`/`RunDetail.assessmentResult`. |
| **analytics/** (6) | **LIVE** | `EventTrackingService.track` called from `AuthService`, `ProjectService`, `RunOrchestrator`, `RunController`, `PullRequestRecordService`; PostHog forward optional. `ActivationFunnelController` is SUPER_ADMIN (`/api/admin/**`). |
| api/v3 growth-loop (`ReferralController`, `EntitlementsController`, `SignupClaimController`) | **DORMANT** | `/api/v3/referrals`, `/api/v3/entitlements`, `/api/v3/auth/claim-anonymous-session` — **zero frontend callers** (grep on `frontend/src` + `website/` empty). `ReferralService` referral-grant hook is wired into `RunOrchestrator`, so the referral *credit* mechanism is live server-side, but the v3 invite/lookup/claim **HTTP surface** has no consumer. `SignupClaimControllerTest` is the only test that touches any of these. |

Net: the whole **v3 anonymous-trial / landing / referral-UI / entitlements-UI** cluster is a built-but-unwired growth-loop. Controllers + SecurityConfig permitAll rules + tests exist; no frontend invokes them and the anon-run core is stubbed.

> Note on the prompt's "29 unread notifications on a fresh account": **not reproducible from this code.** Every `notificationService.create(...)` call site (grep-verified: `RunCompletionNotifier`, `PullRequestRecordService:255`, `PostMergeCascadeService`, `sharing/ProjectInviteService:238`, `billing/RenewalReminderScheduler:66`) requires a pre-existing run / PR / invite / subscription owned by the user. There is **no signup-time notification seed** anywhere in `notification/`. A genuinely fresh account produces 0 rows. The 29 must come from a seeded/shared test account with prior run history or an out-of-scope backfill. Dedup (`existsByLinkAndType`) is global-not-user-scoped but that *suppresses* duplicates — it can't inflate. The `QuotaWarning` path uses shared link `/billing` but embeds `userId` in the `dedupType` suffix (`RunCompletionNotifier.java:~225`) so no cross-user collision.

---

## 2. Findings — real bugs first

### [BUG] TEAM tier (the primary paid tier) falls through to FREE rate limits
`ratelimit/RateLimitPolicy.java:58-95` — `LIMITS` maps only `FREE`/`PRO`/`ENTERPRISE`/`DEFAULT` keys per group. `TierEnforcementService.resolveUserTier` (`auth/service/TierEnforcementService.java:753-763`) returns the org tier verbatim, uppercased. The live ladder is **FREE → PRO(hidden, grandfathered) → TEAM → ENTERPRISE** — TEAM is the active paid BYOK tier. A TEAM org resolves to `"TEAM"`, which is **not a key** in any group map, so `getLimit` falls to `DEFAULT` = the **FREE** number (RUNS 5/min, READ 300/min, WRITE 60/min, PREVIEW 30/min). Paying TEAM customers are throttled as FREE on every endpoint group. `RateLimitPolicyLevelTest` never asserts `getLimit(*, "TEAM")`, so it's untested. Fix: add `TEAM` rows (or alias TEAM→ENTERPRISE) in `LIMITS`.

### [BUG] `forkRun` bypasses the monthly run-quota counter — same class as the fixed BUG-006
`api/controller/RunController.java:540` — `forkRun` calls `tierEnforcementService.assertCanSubmitRun(userId)`, which only **reads** the quota (`TierEnforcementService.java:214`, no increment). The `submit()` path was deliberately changed (RunController.java:146-153 comment) from `assertCanSubmitRun` to `assertAndIncrementRunCount` precisely because the non-incrementing probe let FREE users "submit forever". `forkRun` still uses the old probe and never calls `assertAndIncrementRunCount`/`incrementRunCount` — it only does `usageTrackingService.increment(...,"RUNS",1)` (the *billing* surface, RunController.java:578), which is a different counter from the tier monthly-run quota. Result: **forking a run does not consume the FREE monthly run cap** — a FREE user can fork unlimited runs. `POST /api/runs/{id}/fork` is a real, reachable run-dispatch path.

### [BUG] `DeploymentController.list()` (no `runId`) and `live()` leak every org's deployments
`api/controller/DeploymentController.java:97-111` (`list`) and `:171-177` (`live`). When `runId` is absent, `list()` calls `guard.requireOrgAdmin(userDetails)` then `deploymentRepo.findAll()` — **no org filter**; `live()` calls `findByStatus(LIVE)` globally after `requireOrgAdmin`. Because every solo user is ORG_ADMIN of their personal org (see header), `requireOrgAdmin` passes for **every authenticated user**, so any user gets the full cross-tenant deployment list (run IDs, repo names, statuses, URLs). The RLS layer does NOT save this: (a) the `deployment` table has **no RLS policy** (`V67__rls_tenant_isolation.sql` has no `deployment` entry — grep empty), and (b) `TenantConnectionInterceptor`'s `SET LOCAL` only fires inside an active transaction, and these controller `findAll()` reads aren't `@Transactional`. The controller IS active in default config (`@ConditionalOnProperty agent.deployment.enabled`, and `application.yml:460 agent.deployment.enabled: true`).

### [SMELL] `AdminAnalyticsController` USER-scope check is broken (but masked by the SecurityConfig admin gate)
`api/controller/AdminAnalyticsController.java:122-130`. For `entityType=USER`, after the own-id check it allows **any** caller whose `orgMember.getOrgRole()=="ORG_ADMIN"` for **any** `entityId` — the comment says "ORG_ADMIN can view members in their org (handled by checking org membership)" but **no org-membership check on the target user is performed**. Were this the only gate, every user (ORG_ADMIN of their personal org) could read any victim's usage/billing. **CONFIRMED NOT externally exploitable**: SecurityConfig `:234` maps `/api/admin/**` → `hasRole("SUPER_ADMIN")`, which fronts `/api/admin/analytics/**` and blocks all non-super callers at the filter before the controller runs. So this is a broken in-controller guard relying entirely on the path-level gate — defense-in-depth that doesn't actually defend. If anyone ever loosens the `/api/admin/**` matcher (or remounts analytics outside `/api/admin`), it becomes a live cross-tenant leak. Fix the controller check to actually verify the target user shares the admin's org.

### [RISK] `RateLimitFilter.getClientIp` uses the rightmost X-Forwarded-For entry → all unauthenticated traffic shares one bucket
`ratelimit/RateLimitFilter.java:248-257`. Takes `parts[parts.length-1]` (rightmost XFF). Behind Cloudflare + a GCE LB, the rightmost hop is the LB's own IP (constant for all external clients), so **every unauthenticated request from the entire internet** (AUTH group: login/register/forgot, keyed by IP) collapses into a single shared counter. Effect is twofold: a global limit that one abuser can exhaust to DoS all logins, and trivially-defeated per-attacker limiting. The "rightmost is set by infra, not client" comment is true but the trusted-proxy *depth* is hardcoded to 1; with 2 proxies the value is wrong. Needs a configurable trusted-proxy count.

### [BUG] `FeatureFlagService.reloadAll()` clears the cache non-atomically every 60s — every flag reads `false` for a window
`config/featureflag/FeatureFlagService.java:124-134`. `scheduledReload()` (`@Scheduled(fixedRate=60_000)`) → `reloadAll()` does `cache.clear()` then re-`put`s in a loop on a background thread. A concurrent `isEnabled(key)` / `getInt(key)` landing in the clear→repopulate window sees `null` → returns `false` (or the supplied default). The window hits *every* flag at once, once per minute, forever. The codebase gates scoring formula (`scoring.additive-v2`), anti-gaming guards, autofix, maintenance-pause, etc. on flags, so a mis-timed read can silently flip scoring, skip a guard, or 503 a submit. Fix: build a fresh map and swap the reference (make the field non-final / `AtomicReference`), or merge-then-prune instead of clear-then-fill. The per-key pub/sub path (`onMessage`) is fine; only the scheduled full reload has the gap. (The `Scope` enum comment at `FeatureFlag.java:56-60` already documents an *adjacent* fragility — one unknown enum value empties the whole cache via `findAll()` throwing — so this surface is known-brittle.)

### [BUG] `PricingController.upsert` silently wipes `cachedInputCostPer1m` on the live row
`api/controller/PricingController.java:90-98`. A SUPER_ADMIN `PUT /api/pricing/{provider}/{model}` rebuilds `ModelPricing.builder()` with only `inputCostPer1m`/`outputCostPer1m`/`notes` — `cachedInputCostPer1m` is left null and `save()` overwrites the row. Cache-read tokens for that model then price at $0 in `RunCostTracker` until the next restart (`ModelPricingInitializer.seed()` re-seeds the cached rate from YAML at boot — `config/ModelPricingInitializer.java:78`). `PricingUpdateRequest` (`api/dto/PricingUpdateRequest.java`) has no cached-rate field either, so the admin can't supply it. Self-heals on reboot, SUPER_ADMIN-only, but mis-prices cache-read cost in the interim.

### [BUG] `RunRequest.aiProvider` regex rejects DEEPSEEK / FIREWORKS while the providers are wired live
`api/dto/RunRequest.java` — `@Pattern(regexp = "ANTHROPIC|GEMINI|GOOGLE|OPENAI|OPENROUTER")`. The working tree just added `DEEPSEEK` + `FIREWORKS` to `LlmProvider` (`router/LlmProvider.java:14,19`) with `callDeepSeek`/`callFireworks` dispatch in `LlmRouter`, and `ModelController.PROVIDER_LABELS` (`api/controller/ModelController.java:49-50`) labels both so `/api/models/available` surfaces them to the UI dropdown. But a run submitted with `aiProvider:"DEEPSEEK"` is rejected at the DTO with a 400 before reaching the pipeline — the picker offers a provider the submit endpoint refuses. (Both are ENTERPRISE-BYOK/operator routes so blast radius is small, but DTO and router are out of sync. Fireworks is also missing from `PROVIDER_LABELS`, so it'd render as raw `fireworks`.)

### [INCONSISTENT] Two error-envelope shapes coexist
The canonical 4xx/5xx envelope is `{timestamp,status,error,message,(fields|errorId|retryAfterSeconds)?}` (`ValidationAdvice`, `GlobalExceptionAdvice`, `RateLimitFilter.rateLimitedBody`). But several controllers hand-roll ad-hoc bodies that do **not** match:
- `OptimisticLockAdvice.java:33` → `{error,message,entity,id}` (no `timestamp`/`status`).
- `IntegrationController.java:46-54` → `{error:"invalid_signature"}` / `{error:msg}` bare maps for 401/400/500.
- `DeploymentController` `analyze` returns `Map.of("manifest",...,"decision",...)` and errors via `ResponseStatusException` (canonical) — mixed within one controller.
- `PreviewController.start/rebuild` swallow `PreviewException` and return **HTTP 200** with a `PreviewStatusDto{status:"FAILED"}` body (`:108-127`, `:138-156`) — a failure surfaced as 200. `execInContainer` returns 200/400/503 with an `ExecResponse` that's never the canonical shape.
Frontend's generic `ApiError` parser expects the canonical shape; these endpoints will fall to its catch-all string.

### [LAYERING] Field injection in `IntegrationStatusController` violates the documented MUST-NOT
`api/controller/IntegrationStatusController.java:36-47` uses `@Autowired` on fields (`manager`, `guard`, `orgService`, `slackIntegrationRepo`, `slackChannelRepo`) instead of constructor injection. CLAUDE.md "Patterns: MUST NOT use → Field injection (`@Autowired` on fields)". Every other controller in scope uses `@RequiredArgsConstructor`. (`RateLimitPolicy.java:38` and `InputSanitizer.java:40` also use field/`@Autowired` constructor wiring with a no-arg back-compat constructor — acceptable since they're not field injection, just a dual constructor.)

### [SMELL] `RunController` is a 1,448-LOC god controller with ~10 dead 410-Gone stubs
`api/controller/RunController.java`. Endpoints `clarify` (:487), `merge` (:498), `retry` (:508), `edit-and-restart` (:615), `implement-suggestions` (:1035), `rework` (:1043), `reopen-merge` (:1050), `edit-plan` (:1319) all just `throw ResponseStatusException(GONE, ...)`. `RunConversationController.iterate` (:203) too. These are V1 vestiges kept as 410s for old frontend callers — reasonable as a transition, but 9 of them is a lot of permanent dead surface, and the controller also embeds a private in-memory `costCache` (`:287`), Kahn's-algorithm topological sort (`:1382`), and JSON parsing inline. Prime split candidate (matches the documented god-class debt).

### [SMELL] `ReadymadeController.instantiate` Javadoc is stale ("Stubbed in W3 — returns 501")
`readymade/controller/ReadymadeController.java:43-44` Javadoc says instantiate is "**Stubbed in W3 — returns 501**". The actual method (`:121-145`) dispatches a real `ExecutionRun` via `readymadeOrchestrator.instantiate` and returns 202. Stale doc on a live, billable, write path.

### [SMELL] `PreviewController.toDto` tier detection is a hardcoded heuristic
`api/controller/PreviewController.java:279-282` — `String tier = run.getOrgId() != null ? "PRO" : "FREE"` with a literal `// simplified tier detection` comment, then looks up `maxPreviewStarts` for that fake tier. Since every user now has an orgId (personal org), this resolves **everyone to "PRO"** preview limits regardless of their actual tier. The real per-run user tier is never consulted.

---

## 3. Dead code & junk (grep-verified)

- **Entire `validator/` package (~2K LOC, 27 files)** — no external caller (see §1). Worse than inert: `CodeValidatorImpl`'s constructor runs `toolchainMap.forEach(tc -> tc.isAvailable())` at startup (`CodeValidatorImpl.java:37-47`), and each `*Toolchain.isAvailable()` **shells out a subprocess** (`mvn --version`, `tsc --version`, etc. — `JavaToolchain.java:229`) — so a dead framework spawns 5+ external processes on every boot just to log "toolchain ready/not installed." `prompt/RetryPromptBuilder.java`, all `language/*Toolchain`, `execution/*Parser`, `gate/GateChain`, `CodeValidatorImpl` — all reachable only from within the package. The `validator:` block in `application.yml:680` is also dead config.
- **`anonrun/StubAnonymousRunDispatcher`** is the *only* `AnonymousRunDispatcher` impl; the real one was never written (`AnonymousRunDispatcher.java:11-20` lists it as an explicit TODO). The whole anon path is a stub with no UI caller.
- **9 × 410-Gone dead endpoints** in `RunController` + 1 in `RunConversationController` (enumerated in §2 god-controller finding).
- **v3 growth-loop HTTP surface** (`landing/`, `anonrun/` controller, `ReferralController`, `EntitlementsController`, `SignupClaimController`) — reachable, permitAll-configured, but zero frontend consumers. Not strictly dead (server beans wire into RunOrchestrator for referrals), but the controllers are orphaned.
- **Stale TODO/FIXME sweep**: scope is unusually clean — the only `@Deprecated` is `RunController.pushToGitHub` (`:664`, correctly points to `push-scm`). No `FIXME`/`HACK`/`XXX` in any scope package. The dead-code is structural (whole packages/endpoints), not litter.

---

## 4. Model / LLM touchpoints

- **`GET /api/models/available`** (`api/controller/ModelController.java`) — exposes the YAML model matrix to the UI: per-provider PLANNING + WORKHORSE model **ids** (e.g. `claude-sonnet-4-6`, `gpt-5.4`, `gemini-3-flash-preview`), tier labels, provider display labels incl. the Enterprise-Beta `deepseek`/`openrouter`. Falls through to `/api/**→authenticated`. Leaks the exact internal model lineup to any logged-in user (not secret, but it's the full routing map).
- **`GET /api/pricing` + `/{provider}/{model}`** (`PricingController`) — any authenticated user reads per-model input/cached-input/output $/1M + chain-tier; `PUT` is SUPER_ADMIN. Pricing rows are DB-backed (`ModelPricingRepository`) and hydrated with YAML `chainTier`/`displayLabel`.
- **`POST /api/conversation`** (`ConversationController`) — pre-flight spec-refinement chat, **available to all tiers incl. FREE** (no auth guard beyond `/api/**`). Pins to Anthropic Haiku via `fallbackLlmRouter.callDirect("anthropic", lightweightModel, ...)`, `maxTokens=4096`, system prompt from `prompts/conversation-system.md`. Server caps history at 50 (`MAX_MESSAGES`). This is a real, ungated LLM-cost surface for FREE users (cheap Haiku, but unmetered by run quota).
- **`POST /api/runs/{id}/chat` + `/apply`** (`RunConversationController`) — `/chat` wired to `ChatService` (LIGHTWEIGHT, read-only tools); `/apply` runs a real 25-iter WorkerAgent loop on a bounded `applyExecutor` (clamp 4–12 threads), Sonnet-grade. `/iterate` is 410.
- **`GET /api/model-comparison`** + **`/api/user-runs-summary`** + **`/api/metrics`** (`TimelineController`, `UserRunsController`, `MetricsController`) — per-model/per-user task stats. All use the `getOrgIdOrNull`/`getOrgId()!=null` pattern → org-scoped for real users, global only for SUPER_ADMIN. **Not a leak in practice** because every real user has an orgId (header fact); the null branch is SUPER_ADMIN-only. (Would be a leak if a user could ever have null orgId.)
- `ReadymadeController.instantiate` and `RunController.fork` ultimately drive the LLM pipeline; covered above.
- **Working-tree diff (uncommitted, branch `benchmark`)** adds a **Fireworks AI** provider (US-hosted fast inference for the Chinese open-weight models — Qwen3-Coder/DeepSeek/Kimi/GLM at 100+ tok/s, to fix OpenRouter latency). `LlmRouter.callFireworks` (`agent/LlmRouter.java`, diff) reuses `OpenAiNativeToolClient` with a per-call base-URL exactly like the DeepSeek path; `FIREWORKS` is added to `FAILOVER_EXCLUDED_PROVIDERS` alongside `deepseek`/`openrouter` (operator route, never a failover target). The untracked `router/FireworksModelMapper.java` is an explicit 5-slug allow-list (`qwen3-coder-fw`, `deepseek-v3-fw`, `deepseek-v3p1-fw`, `kimi-k2-fw`, `glm-4p6-fw` → `accounts/fireworks/models/…`), throwing on unmapped ids. `spring.ai.fireworks.{api-key,base-url}` added to `application.yml`. The wiring is internally consistent; the only gaps are the two DTO/label mismatches noted above (§2 [BUG] RunRequest regex).

---

## 5. Libraries

- **Spring Boot 3.5 / Spring Security 6.x / Spring AI** — `ConversationController` uses `org.springframework.ai.anthropic.AnthropicChatOptions` + `FallbackLlmRouter.callDirect` (the documented legacy non-tool path; correct per CLAUDE.md). No direct provider HTTP in scope.
- **GCP Secret Manager** (`com.google.cloud.secretmanager.v1`) — `ConfigAdminController` (secret list/upsert, SUPER_ADMIN via `@PreAuthorize`), `SimpleKeyEncryptionService` (key fetch). `SecretManagerServiceClient.create()` is opened per-call in a try-with-resources — fine for admin-rate endpoints.
- **SnakeYAML** — wrapped by `util/SafeYamlLoader` (`SafeConstructor`, `maxAliasesForCollections=50`, no duplicate keys) to neutralize the CVE-2022-1471 gadget class; `ReadymadeSeeder` routes through it. Good hardening.
- **AES-256-GCM** via JCA (`SimpleKeyEncryptionService`) — random 12-byte IV, 128-bit tag, key from local-config→GCP-SM→passthrough with fail-fast when `require-encryption=true`. Sound.
- **Jackson, Micrometer, Lombok** throughout. `RateLimitFilter`/`CustomMetricsConfig` register counters (`executespec.redis.failopen`, `VALIDATION_TOTAL`).
- **PostHog** via a hand-rolled `analytics/PostHogClient` (HTTP) injected as optional `ObjectProvider<PostHogClient>` — analytics never blocks the request path.

---

## 6. Test coverage

Thin, and absent on the highest-risk surfaces.

| Package | Test classes | Gap |
|---|---|---|
| api | 2 (`SignupClaimControllerTest`, `RunRequestBranchAndPrCommentsTest`) | **No test for `RunController` (1,448 LOC), `DeploymentController`, `PreviewController`, `RunConversationController`, `RunMapper`, the advice classes.** The two leaks + the fork-quota bug are untested. |
| validator | **0** | Entire dead framework untested (consistent with it being dead). |
| readymade | 2 (`ReadymadeServiceTierGateTest`, `StackNormalizerTest`) | `instantiate`/orchestrator/seeder untested. |
| landing / onboarding / contact / assessment / crypto / util / observability | **0 each** | `SimpleKeyEncryptionService` (crypto, encrypts API keys) has **no test**. `SafeYamlLoader` untested. |
| anonrun | 1 (`AnonymousRunServiceTest`, 8 tests) | Service only; controller untested. |
| analytics | 2 (`EventTrackingServiceTest`, `UserActivationEventJsonbBindingTest`) | Reasonable. |
| notification | 2 (`RunCompletionEmailListenerTest`, `NotificationPrefServiceTest`) | `RunCompletionNotifier` (the in-app trigger) + dedup logic untested. |
| ratelimit | 1 (`RateLimitPolicyLevelTest`, 6 tests) | Guardrail-level multipliers only; **never asserts `getLimit(*, "TEAM")`** (would have caught the FREE fall-through, §2); `RateLimitFilter` (Redis path, XFF parsing, fail-open) untested. |
| config | 1 (`SpaFallbackControllerTest`) | `FeatureFlagService` cache-coherence (the clear-then-fill race, §2), `ModelPricingInitializer`, `ConfigRefreshService`, `TenantConnectionInterceptor` untested. |
| security | 3 (`InputSanitizerLevelTest`, `CodeAbuseScannerDnsExfilTest`, `GuardrailLevelTest`) | Best-covered package in scope. |

Most existing tests pin guardrail-level multipliers and DTO/JSONB binding — not controller authz or the money/quota paths.

---

## 7. Security notes (parked)

1. **`DeploymentController.list()/live()` cross-tenant deployment enumeration** (§2 BUG). HIGH — any user, default config, no RLS backstop on `deployment`.
2. **`AdminAnalyticsController` USER-scope authz hole** (§2 SMELL). Broken in-controller check, but **confirmed masked** by SecurityConfig `:234` `/api/admin/**→SUPER_ADMIN` (fronts analytics → non-super blocked at filter). Latent: becomes a live cross-tenant leak the moment that path matcher is loosened or analytics is remounted.
3. **`forkRun` quota bypass** (§2 BUG) — abuse/cost, FREE users fork unlimited runs without burning the monthly counter.
4. **`RateLimitFilter` XFF rightmost-only** (§2 RISK) — global IP-bucket collapse behind 2 proxies → login-DoS + ineffective per-attacker limiting.
5. **Personal-org-as-ORG_ADMIN is the amplifier** — `guard.requireOrgAdmin()` no longer means "real org admin"; it means "any logged-in user". Every `requireOrgAdmin` + unfiltered `findAll()` is suspect. Audit all `requireOrgAdmin` call sites against this (SlackIntegrationController correctly pairs it with `ProjectAccessGuard`/org-scoped repo calls; DeploymentController does not).
6. **`LandingController` is permitAll and `/landing/sample-runs` exposes truncated customer spec text** (`landing/LandingController.java:63-83`, first 90 chars of `findTopGalleryRuns`) to unauthenticated callers. Currently no UI calls it, but the endpoint is live — public spec-snippet disclosure if a gallery run contains sensitive text. Privacy review before any re-wiring.
7. **`SimpleKeyEncryptionService.decrypt` fail-opens to plaintext on any exception** (`crypto/...:161-167`) — a corrupted/tampered ciphertext returns the raw stored bytes instead of erroring. Deliberate legacy-migration tolerance, but it means a decrypt failure is silent; pair with the `isLikelyEncrypted` guard, acceptable but worth a metric.
7b. **`crypto.require-encryption=false` in prod** — `application-prod.yml:179` sets `executespec.crypto.enabled: true` + `gcp-secret-name` but leaves `require-encryption` off (the yml comment at line 177 even points at it). If the boot-time GCP Secret Manager fetch throws (`SimpleKeyEncryptionService.init` priority-2 catch falls through), prod silently enters **passthrough plaintext mode** for every BYOK API key with only a WARN. Recommend flipping `require-encryption: true` in prod so a Secret Manager hiccup fails the boot instead of persisting keys in cleartext.
7c. **`isLikelyEncrypted` `sk-`/`AIza`/`xoxb-` prefix allowlist** (`crypto/SimpleKeyEncryptionService.java:200-208`) conflates "is a known API-key shape" with "is plaintext." DeepSeek (`sk-…`) and OpenRouter (`sk-or-…`) keys match the `sk-` prefix; the migration runner uses this only to skip already-encrypted rows (so those keys still migrate), but the heuristic is the fragile kind that breaks silently when a new provider ships an `sk-`-style token. Not a confirmed live bug.
8. **Slack OAuth callback** correctly maps exceptions to an allowlisted error code (no raw message in redirect) — `SlackIntegrationController.java:105-113`. Good.
9. **CORS** is per-env allowlisted (`application-prod.yml:160` etc.), `allowCredentials=true` with explicit origins (no `*`). Good.

Non-security correctness items also worth surfacing to the consumer (full detail in §2): **TEAM-tier rate-limit fall-through to FREE** (paid customers throttled as FREE), **`FeatureFlagService` 60s non-atomic cache clear** (flags flicker to `false`), **`PricingController` cached-rate wipe**, **`RunRequest.aiProvider` regex out of sync with the live DeepSeek/Fireworks providers**, and the **`validator/` package booting 5+ subprocesses for a dead framework**.
