# ExecuteSpec v1.2.6 — Comprehensive Review Report

> Generated: 2026-04-05 | Branch: `dev` | Scope: Full codebase (backend + frontend + infra + docs + scripts)

---

## Table of Contents

1. [Task 1: PHP & Ruby Removal Plan](#task-1-php--ruby-removal)
2. [Task 2: LLM Model Configuration Cleanup](#task-2-llm-model-configuration-cleanup)
3. [Task 3: Full Code Review](#task-3-full-code-review)
   - [Backend (Java/Spring Boot)](#backend)
   - [Frontend (React/TypeScript)](#frontend)
   - [Infrastructure (Terraform/CI-CD/Docker)](#infrastructure)
   - [Scripts & Documentation](#scripts--documentation)
4. [Summary & Priority Matrix](#summary--priority-matrix)

---

## Task 1: PHP & Ruby Removal

**Scope:** 23 files to modify, 2 files to delete, ~209 line-level changes.

### Files to DELETE

| File | Reason |
|------|--------|
| `deployment/strategy/PhpDeploymentStrategy.java` | Entire PHP deploy strategy (~100 lines) |
| `deployment/strategy/RubyDeploymentStrategy.java` | Entire Ruby deploy strategy (~104 lines) |

### Enum Removals (2 files)

| File | Change |
|------|--------|
| `deployment/preview/PreviewProjectType.java` | Remove `RUBY(4567)` and `PHP(8080)` enum values |
| `state/entity/Deployment.java` | Remove `RUBY` and `PHP` from `DeployStrategy` enum |

### DevDockerfileGenerator.java (~10 methods + ~24 switch cases)

**Methods to delete entirely:**
- `cloudRun_Ruby()`, `phpDockerfile()`, `cloudRun_FullStack_NodeRuby()`, `cloudRun_FullStack_NodePhp()`
- `fullStackNodeRuby()`, `fullStackNodePhp()`, `findRubyEntryPoint()`, `rubyDockerfile()`
- `cliRubyDockerfile()`, `cliPhpDockerfile()`

**Switch cases to remove:** 24 occurrences across dispatchers (`dockerComposeDockerfile`, `cloudRunDockerfile`, `fullStackDockerfile`, `cloudRun_FullStack`, `cloudRun_FullStack_StaticBackend`, `fullStackStaticBackend`, `cliDockerfile`)

### Other Backend Files (6 files)

| File | Change |
|------|--------|
| `deployment/PreviewAgent.java` | Remove 2 switch cases (RUBY, PHP docker-compose blocks) |
| `deployment/DockerfileGenerator.java` | Remove 2 map entries + update error message text |
| `deployment/analyzer/HealthCheckDetector.java` | Remove `RUBY, PHP` from case statement |
| `state/StateStore.java` | Remove 2 `TechEntry` definitions (php, ruby) |
| `harness/UiStyleResolver.java` | Remove `"RUBY", "PHP"` from `BACKEND_ONLY_TYPES` set |
| `harness/LanguageValidator.java` | Remove `.rb`, `.php` extensions + case mappings |

### Frontend Files (5 files)

| File | Change |
|------|--------|
| `frontend/src/lib/techDetect.ts` | Remove PHP/Ruby tech definitions + `WORD_BOUNDARY_IDS` entry |
| `frontend/src/components/runs/TechBadges.tsx` | Remove PHP/Ruby icon SVGs |
| `frontend/src/components/runs/RunDetailTabs.tsx` | Remove `rb`/`php` from `codeExts`, `EXT_TO_LANG`, `MONACO_LANG_MAP` |
| `frontend/src/components/runs/PreviewTab.tsx` | Remove `RUBY`/`PHP` from `TYPE_LABELS` |
| `frontend/src/components/run/FileViewer.tsx` | Remove `rb`/`php` language mappings |

### Prompt Files (4 files)

| File | Change |
|------|--------|
| `resources/prompts/planner.txt` | Remove `RUBY` and `PHP` from project_type list, update examples |
| `resources/prompts/worker.txt` | Remove Ruby/PHP error handler and CORS references |
| `resources/prompts/worker-openai.txt` | Same as worker.txt |
| `resources/prompts/worker-gemini.txt` | Same as worker.txt |
| `resources/prompts/api-test-suite.txt` | Remove Ruby/Rails + PHP/Laravel route detection lines |

### Documentation (4 files + CLAUDE.md)

| File | Change |
|------|--------|
| `CLAUDE.md` | Update "11 languages" to "9 languages", remove PHP/Ruby mentions |
| `deployment/CLAUDE.md` | Update PreviewProjectType list, detection flow |
| `docs/DEPLOYMENT.md` | Remove RUBY/PHP from supported types table |
| `docs/CODE_GEN_ARCHITECTURE.md` | Update project_type list |
| `docs/requirements/deployment-preview.md` | Remove RUBY/PHP table rows |

### Execution Order

1. Delete 2 strategy files
2. Remove enum values (PreviewProjectType, DeployStrategy)
3. Remove all switch cases + methods in DevDockerfileGenerator
4. Update remaining 6 backend files
5. Update 5 frontend files
6. Update 4 prompt files
7. Update 5 documentation files
8. Compile + build to verify

---

## Task 2: LLM Model Configuration Cleanup

### Critical Issues

#### 2.1 Hardcoded Model in LlmJudgeValidator (CRITICAL)
- **File:** `eval/LlmJudgeValidator.java:35`
- **Issue:** `private static final String JUDGE_MODEL = "claude-haiku-4-5-20251001"` hardcoded in Java
- **Problem:** Won't update if model changes in config
- **Fix:** Inject `ModelRegistry` and resolve dynamically

#### 2.2 Wrong max-tokens for Anthropic (CRITICAL)
- **File:** `application.yml:64`
- **Issue:** `spring.ai.anthropic.chat.options.max-tokens: 64000` — comment says "Sonnet supports 64k" but this applies globally to ALL Anthropic models including Haiku
- **Problem:** Haiku's actual output limit is ~8192. This sets an invalid ceiling
- **Fix:** Either set to 8192 or move max-tokens to per-model config in `model-matrix.models`

#### 2.3 Wrong CAPABLE Model in RecoveryProperties (CRITICAL)
- **File:** `recovery/RecoveryProperties.java:59`
- **Issue:** `capableModels` maps `"anthropic" -> "claude-haiku-4-5-20251001"` but CAPABLE tier should use Sonnet
- **Problem:** Recovery agent uses Haiku when it should use Sonnet for complex recovery tasks
- **Fix:** Change to `"claude-sonnet-4-6"`

#### 2.4 Stale Gemini Default Model (HIGH)
- **File:** `application.yml:76`
- **Issue:** `spring.ai.google.genai.chat.options.model: gemini-3-flash-preview` but canonical is `gemini-2.5-flash`
- **Problem:** Legacy alias resolves it, but creates confusion when reading config
- **Fix:** Update to `gemini-2.5-flash`

### Moderate Issues

#### 2.5 Deprecated ModelConstants.java Still Exists
- **File:** `config/ModelConstants.java`
- **Status:** Marked `@Deprecated(forRemoval = true)` but still referenced in comments
- **Fix:** Delete entirely or remove all references

#### 2.6 Hardcoded Model Labels in ModelController
- **File:** `api/controller/ModelController.java:33-41`
- **Issue:** `MODEL_LABELS` map hardcoded with model names — won't pick up new models added to YAML
- **Fix:** Source from `model-matrix.models.{name}.display-name` or `notes` field

#### 2.7 Scattered Agent Model Defaults in Java Properties
Models defined in 4 separate places:
- `AgentProperties.java:90` (Compiler) — `claude-haiku-4-5-20251001`
- `ContextAgentProperties.java:12` — `claude-haiku-4-5-20251001`
- `PromptOptimiserProperties.java:25` — `claude-sonnet-4-6`
- `RecoveryProperties.java:59` — `claude-haiku-4-5-20251001` (WRONG tier)

**Recommendation:** Centralize all agent model assignments to YAML:
```yaml
agent:
  model-assignments:
    compiler: { provider: anthropic, model: claude-haiku-4-5-20251001 }
    context: { provider: anthropic, model: claude-haiku-4-5-20251001 }
    optimiser: { provider: anthropic, model: claude-sonnet-4-6 }
    recovery: { provider: anthropic, model: claude-sonnet-4-6 }
```

#### 2.8 spring.ai.*.chat.options Are Dead Defaults
- `spring.ai.anthropic.chat.options.model`, `spring.ai.openai.chat.options.model`, `spring.ai.google.genai.chat.options.model` are set but `LlmRouter` / `ModelResolver` / `AgentModelConfig` always override them
- These only matter if Spring AI is called directly (which it never is — everything goes through LlmRouter)
- **Recommendation:** Add clear comment documenting these are "Spring AI fallback defaults — overridden by model-matrix for all agent calls"

### Configuration Map (Where Models Are Defined)

| Location | Purpose | Authoritative? |
|----------|---------|---------------|
| `application.yml` → `spring.ai.*.chat.options` | Spring AI global defaults | No (always overridden) |
| `application.yml` → `model-matrix.providers` | Per-provider tier assignments | **Yes** |
| `application.yml` → `model-matrix.models` | Model registry (context windows, costs) | **Yes** |
| `application.yml` → `model-matrix.legacy-aliases` | Old→new name resolution | Yes |
| `AgentProperties.java` defaults | Compiler model | Partial (overridable via YAML) |
| `ContextAgentProperties.java` defaults | Context agent model | Partial |
| `PromptOptimiserProperties.java` defaults | Optimiser model | Partial |
| `RecoveryProperties.java` defaults | Recovery models | Partial (HAS BUG) |
| `LlmJudgeValidator.java` constant | Eval judge model | No (SHOULD use registry) |
| `ModelController.java` map | UI display labels | No (SHOULD source from YAML) |
| `ModelConstants.java` constants | Legacy model IDs | No (DEPRECATED) |

---

## Task 3: Full Code Review

### Backend

#### Critical (3)

| # | Issue | File | Description |
|---|-------|------|-------------|
| B1 | JWT in Query Params | `JwtAuthenticationFilter.java:49-60` | SSE tokens accepted via `request.getParameter("token")` — exposed in browser history, server logs, CDN/proxy logs. Violates RFC 6750. Mitigated by 5-min TTL but still a risk. |
| B2 | Race Condition in Cost Cache | `RunController.java:186-214` | Non-atomic check-then-use on `costCache` ConcurrentHashMap. Multiple threads can simultaneously cache/evict. Use `compute()` instead. |
| B3 | Silent Webhook Audit Failure | `BillingWebhookController.java:106-131` | Webhook event logging catches all exceptions silently. If DB is down, payment audit trail is lost with no alert. |

#### High (7)

| # | Issue | File | Description |
|---|-------|------|-------------|
| B4 | N+1 Query on Run List | `RunController.java:163-182` | `list()` fetches runs then maps each — if `toSummary()` loads related entities, creates N+1 queries. Use batch fetching or DTO projections. |
| B5 | Unbounded testCompletionFutures Map | `PipelineHarness.java:164-165` | ConcurrentHashMap with no eviction — memory leak for failed/orphaned runs. Replace with Caffeine cache. |
| B6 | No Pagination Validation | `RunController.java:144-145` | `page` and `size` params unchecked. `size=999999` causes OOM/full table scans. Cap at 100. |
| B7 | Webhook Schema Not Validated | `BillingService.java:48` | `processRazorpayWebhook()` accepts raw JSON without structure validation. Silent deserialization failures. |
| B8 | Non-Atomic Quota Increment | `RunController.java:94-97` | Three separate service calls before quota increment — if last fails, quota was already incremented. Wrap in single `@Transactional`. |
| B9 | Swallowed Exception in InputSanitizer | `InputSanitizer.java:143` | Base64 decode `catch (IllegalArgumentException ignored)` — no diagnostics for malformed payloads. |
| B10 | Unbounded Pipeline Queue | `PipelineHarness.java:214-224` | `LinkedBlockingQueue(maxRuns * 2)` can still exhaust memory on burst. Should be 1:1 with semaphore. |

#### Medium (5)

| # | Issue | File | Description |
|---|-------|------|-------------|
| B11 | XSS in Error Messages | `WorkerPool.java:198-200` | User-controlled filenames passed directly to SSE events. If frontend doesn't escape, XSS possible. |
| B12 | Full User Load on Every Request | `JwtAuthenticationFilter.java:34-35` | `loadUserById()` on every JWT auth check — loads entire entity. Use lightweight JWT-based UserDetails instead. |
| B13 | No Signature Length Check | `WebhookSignatureVerifier.java:28` | No max length check before `MessageDigest.isEqual()`. 1MB signature causes unnecessary allocation. |
| B14 | Missing @Transactional on Cost Query | `RunController.java:212` | `computeCostForRun()` may throw LazyLoadingException if not transactional. |
| B15 | Unhelpful orElseThrow() | `StateStore.java:83-84` | `runRepo.findById(runId).orElseThrow()` — no error message. Hard to debug in logs. |

### Frontend

#### Critical (2)

| # | Issue | File | Description |
|---|-------|------|-------------|
| F1 | Token in localStorage | `useAuthStore.ts`, `AuthCallbackPage.tsx` | Access tokens stored in localStorage — vulnerable to XSS. Should use httpOnly cookies. |
| F2 | SSE Race Conditions | `useSse.ts:88-96` | Multiple SSE events trigger concurrent `fetchRun()` calls with no debouncing. Causes redundant API calls and state flicker. |

#### High (4)

| # | Issue | File | Description |
|---|-------|------|-------------|
| F3 | Full JWT Fallback in SSE URL | `useSse.ts:51-54` | Falls back to full JWT in URL if scoped token fails. Full JWT in URL is a security risk. Should fail instead. |
| F4 | Excessive `any` Types | 16+ files | `catch (e: any)` pattern everywhere. No type safety for error handling. Use `unknown` + type guards. |
| F5 | No Error Boundaries | `App.tsx` | No React Error Boundary. Single component crash takes down entire app. |
| F6 | Unvalidated OAuth Callback | `AuthCallbackPage.tsx:16-22` | `searchParams.get('token')` used without JWT format validation or CSRF check. |

#### Medium (4)

| # | Issue | File | Description |
|---|-------|------|-------------|
| F7 | Unnecessary Re-renders | `RunList.tsx:53-57` | `fetchRuns()` not memoized. All RunCard children re-render on every fetch. |
| F8 | Data Cleared on Error | `useApi.ts:14-19` | Error handler sets `data: null`, clearing previously loaded data. Should preserve last-known-good. |
| F9 | Missing Accessibility | All interactive components | Buttons/inputs lack `aria-label`, `aria-describedby`. Screen readers can't identify elements. |
| F10 | Console.debug in Prod | `telemetry.ts:22,44` | Debug logs visible in production. Guard with `NODE_ENV` check. |

### Infrastructure

#### Critical (5)

| # | Issue | File | Description |
|---|-------|------|-------------|
| I1 | No Terraform State Locking | `infra/backend.tf` | GCS backend without locking. Concurrent `terraform apply` can corrupt state. |
| I2 | DB Password in Terraform Output | `infra/modules/database/outputs.tf:16-18` | Missing `sensitive = true`. Password appears in CLI output and logs. |
| I3 | DB Password via GCE Metadata | `infra/modules/compute/main.tf:42` | Password injected via instance metadata — accessible to all workloads on VM. Use Secret Manager. |
| I4 | No Secrets Rotation | `infra/modules/secrets/main.tf` | API keys in Secret Manager with no rotation policy or TTL. |
| I5 | Tests Skipped in CI/CD | `.github/workflows/deploy.yml:77` | `mvn package -DskipTests` — no tests run before production deployment. |

#### High (4)

| # | Issue | File | Description |
|---|-------|------|-------------|
| I6 | No Secret Scanning | `.github/workflows/deploy.yml` | No truffleHog/gitleaks step. Committed credentials go undetected. |
| I7 | No Dependency Audit | `.github/workflows/deploy.yml` | No `npm audit` or `mvn dependency-check`. Vulnerable deps ship to prod. |
| I8 | No Cloud Armor/WAF | `infra/modules/loadbalancer/main.tf` | Load balancer has no security policy. No DDoS protection or WAF rules. |
| I9 | No Connection Draining | `infra/modules/loadbalancer/main.tf:55-59` | Health check marks unhealthy but no drain timeout. In-flight requests to DB fail. |

#### Medium (4)

| # | Issue | File | Description |
|---|-------|------|-------------|
| I10 | No Variable Validation | `infra/variables.tf:31-40` | `domain` and `subdomain_prefix` not validated for DNS format. |
| I11 | Redis No Persistence | `infra/modules/cache/main.tf` | Memory-only Redis. Data loss on restart. |
| I12 | No Backup Restore Testing | `infra/modules/database/main.tf:25-34` | Backups configured but never tested for restoreability. |
| I13 | Single VM, No Auto-Healing | `infra/modules/compute/main.tf:3` | Single GCE instance with no MIG or auto-restart on crash. |

### Scripts & Documentation

| # | Severity | Issue | File | Description |
|---|----------|-------|------|-------------|
| S1 | HIGH | Default Credentials in E2E | `scripts/e2e-test.sh:11` | Falls back to `admin@executespec.ai` if env var not set. Should fail instead. |
| S2 | HIGH | No Cleanup Trap | `scripts/e2e-test.sh:1` | Missing `trap` handler. Temp files leak on script failure. |
| S3 | MEDIUM | Outdated README | `README.md` | Missing recent features (design systems, billing, deployment changes). |
| S4 | LOW | Grafana Default Password | `observability/docker-compose.yml:32` | Hardcoded `admin/admin`. Should use env var. |

---

## Summary & Priority Matrix

### Issue Counts

| Category | Critical | High | Medium | Low | Total |
|----------|----------|------|--------|-----|-------|
| Backend | 3 | 7 | 5 | 0 | 15 |
| Frontend | 2 | 4 | 4 | 1 | 11 |
| Infrastructure | 5 | 4 | 4 | 1 | 14 |
| Scripts/Docs | 0 | 2 | 1 | 1 | 4 |
| LLM Config | 3 | 1 | 4 | 0 | 8 |
| **Total** | **13** | **18** | **18** | **3** | **52** |

### Recommended Execution Order

**Phase 1 — PHP/Ruby Removal (This Sprint)**
1. Delete 2 strategy files
2. Remove enums, switch cases, methods (~23 files)
3. Update prompts and docs
4. Compile + verify

**Phase 2 — LLM Config Cleanup (This Sprint)**
1. Fix RecoveryProperties wrong CAPABLE model (CRITICAL)
2. Fix application.yml max-tokens and Gemini model name
3. Remove hardcoded model in LlmJudgeValidator
4. Delete deprecated ModelConstants.java
5. Add comments clarifying spring.ai defaults vs model-matrix

**Phase 3 — Security Hardening (Next Sprint)**
1. Move tokens from localStorage to httpOnly cookies (F1)
2. Add Terraform state locking (I1)
3. Move DB password to Secret Manager (I3)
4. Add `sensitive = true` to Terraform outputs (I2)
5. Enable tests in CI/CD (I5)
6. Add Cloud Armor WAF (I8)
7. Add secret scanning + dependency audit to CI (I6, I7)

**Phase 4 — Reliability & Performance (Backlog)**
1. Replace unbounded ConcurrentHashMaps with Caffeine caches (B5, B2)
2. Add pagination validation (B6)
3. Add Error Boundaries to React app (F5)
4. Debounce SSE-triggered fetches (F2)
5. Fix webhook audit logging (B3)
6. Add connection draining to LB (I9)

---

*No code changes were pushed. All findings are local to this report.*
