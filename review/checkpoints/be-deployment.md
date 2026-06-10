# be-deployment — raw findings

Reviewed: `src/main/java/com/navneet/executespec/deployment/` — 82 files / ~17.2K LOC + 10 test files (~2.4K LOC). Branch `benchmark`, HEAD `77d7e391`, working tree as-is. Conclusions from code + `application*.yml` (tracked, non-local) only.

Liveness is dominated by two config facts:
- `preview.engine.route-via` defaults **true** in base/dev/prod (`${PREVIEW_ENGINE_ROUTE_VIA:true}`), so the **engine-handoff** path is the live preview path everywhere. The docker-compose path is the final `else` in `PreviewService.start` and only runs when route-via is explicitly false.
- `preview.mode` defaults `docker` and `preview.vm.enabled=false` everywhere → the `vm/` subsystem and `cloud-run` mode are never selected in any tracked profile.
- `agent.deployment.workspace.gcs-enabled` is hardcoded `false` in base `application.yml:484` and **never overridden** by dev/prod/benchmark → the whole GCS archival subsystem is inert in every tracked profile (see §2 F8).

---

## 1. Inventory (with LIVE / FLAG-GATED / DORMANT / DEAD verdicts)

### Root `deployment/`
| File | LOC | Verdict | Notes |
|---|---|---|---|
| `PreviewService.java` | 1807 | **LIVE** | Triple-mode lifecycle. In tracked profiles only the engine-handoff branch + cleanup schedulers run. |
| `DeploymentService.java` | 446 | **LIVE** | Permanent Cloud Run deploy via system ADC + gcloud. Reachable `POST /api/deployments`, tier-gated. |
| `CloudRunClient.java` | 578 | **LIVE (partial)** | `deploy`/`deleteService`/`deleteImage`/`healthCheck` live via DeploymentService; `deployPreview` only via cloud-run preview mode = DORMANT; `listPreviewServicesWithLabels` + 5-arg `deployPreview` = DEAD (no callers). |
| `ArtifactRegistryClient.java` | 115 | **LIVE** | Build+push for DeploymentService; also cloud-run preview mode (dormant). |
| `DockerfileGenerator.java` | 180 | **LIVE** | Deploy-path Dockerfile gen (9 strategies). Used by DeploymentService + DeployEngineOrchestrator + WorkspaceExportService repo-zip + dead PreviewAgent. |
| `DeploymentProperties.java` | 35 | **LIVE** | `@ConfigurationProperties("agent.deployment")`. |
| `DeploymentCleanupJob.java` | 53 | **LIVE** | TTL cron for permanent deploys. |
| `PreviewAgent.java` | 367 | **DEAD** | Zero callers (grep: only self + log strings). See §3. |
| `DockerExecutor.java` | 248 | **DEAD** | Only referenced by `DockerExecutorTest`. Carries a real env-leak (§2 B2). |
| `WorkspaceLockService.java` | 25 | **LIVE (half-wired)** | Only `DeploymentService` locks; preview never does — purpose half-met (§2 S1). |
| `WorkspaceRestoreService.java` | 183 | **LIVE** | GCS restore + git re-init; used by preview/deploy/file/export/analyze. |
| `WorkspaceExportService.java` | 323 | **LIVE** | Run download (zip / repo-zip) + zip source for GCS jobs. |
| `WorkspaceFileService.java` | 621 | **LIVE** | Run-detail file tree + diff. Wide consumer set (RunController, chat, ConversationAgent). |
| `GcsWorkspaceStore.java` | 223 | **LIVE wrapper / mostly idle** | SDK wrapper; callers gated off (§2 F8). |
| `WorkspaceArchiveJob.java` | 167 | **FLAG-GATED OFF** | `gcsEnabled()` false in all profiles → hourly job no-ops. |
| `WorkspaceGcsUploadListener.java` | 124 | **FLAG-GATED OFF** | Same gate → event + 5-min sweep no-op. |
| `GcsTaskCheckpointService.java` | 92 | **CONDITIONALLY LIVE** | Different gate: `durability.task-checkpoint.enabled` (default true) + `gcsStore.isConfigured()` — NOT `gcsEnabled()` (§2 F8 inconsistency). |
| `DeploymentGitHubService.java` | 155 | **LIVE** | Deploy-path GitHub push (per-user PAT). |

### `preview/` (docker-compose path = local-dev only when route-via=false)
| File | LOC | Verdict |
|---|---|---|
| `DevDockerfileGenerator.java` | 3514 | **LIVE** for `generate(type,ws,mode)` + `detect`/`detectMode`/`dockerIgnore` (engine + export + analyzer). `generateForCloudRun` + all `cloudRun_*`/`cr*` helpers = **DORMANT** (cloud-run mode only). |
| `ComposeFileGenerator.java` | 323 | **FLAG-GATED** (docker path; route-via=false) |
| `ComposeExecutor.java` | 313 | **FLAG-GATED** (docker path) — also has bounded-read deadlock (§2 P1) |
| `CaddyProxyManager.java` | 170 | **FLAG-GATED** `@ConditionalOnProperty preview.proxy.enabled=true` (bean exists dev/prod) but only `buildAndStart` (docker path) calls it → effectively idle when route-via=true |
| `PreviewProxyFilter.java` | 237 | **FLAG-GATED** proxy.enabled=true + `@ConditionalOnMissingBean(CaddyProxyManager)` → never active alongside Caddy; unauth-by-shortId (§7) |
| `PreviewPortPool.java` | 140 | **FLAG-GATED** (docker path) |
| `DatabaseDetector.java` | 317 | **LIVE** (preview + analyzer) |
| `EnvVarDetector.java` | 571 | **LIVE** (preview analyze + analyzer) |
| `GlobalPreviewLimiter.java` | 116 | **LIVE** (every `start`/`stop`) — slot leak on queue path (§2 B1) |
| `PreviewProperties.java` / `PreviewSession.java` / `PreviewMode`/`PreviewStatus`/`PreviewProjectType`/`DatabaseType`/`ExecResult`/`PreviewException`/`DeadlineExceededException` | — | **LIVE** support types |
| `engine/PreviewEngineHandoff.java`, `PreviewEngineClient.java`, `PreviewImagePusher.java` | 457 | **LIVE** (prod/dev preview path) |
| `engine/BuildCachePruneJob.java` | 100 | **LIVE** (6h, no-op when route-via=false) |
| `verify/*` (BootHealthEvaluator, PreviewVerifier, PreviewBootEngine, RunRecipe) | 265 | **DORMANT/INERT** — `preview.buildpack.enabled` OFF (V227) + no `PreviewBootEngine` bean → fail-open empty. |

### `preview/vm/` — **DORMANT** (all `@ConditionalOnProperty preview.mode=vm`, disabled everywhere)
PreviewDeploymentService, PreviewGarbageCollector, PreviewVmController, PreviewRegistry(+Repo), PreviewMetric(+Repo), PreviewVmProperties, PreviewVmResult, PreviewDeploymentException — beans not even instantiated under mode=docker.

### `strategy/` — **LIVE** (DeploymentService + DeployEngineOrchestrator deploy path)
FullStack(@Order0), SpringBoot(1), Gradle(2), Node(3), Python(4), Go(5), Rust(8), DotNet(9), Static(10), interface. DOTNET has **no** PreviewProjectType analogue (deploy-only).

### `analyzer/` — **LIVE** (DeploymentController.analyze + DeployEngineOrchestrator)
DeploymentAnalyzer, DeploymentDecisionEngine, InfrastructureManifest, DeploymentPath, Cache/Queue/Storage/HealthCheck/StatelessnessDetector. NOTE: decision engine only ever executes `CLOUD_RUN_DIRECT` (controller rejects the other two paths at `/confirm`, DeploymentController.java:240) → COMPOSE_ON_VM / MANAGED_INFRASTRUCTURE comparison output is **display-only / DORMANT**.

### `credential/` — **LIVE** (`@ConditionalOnProperty agent.deployment.enabled=true`, true in base)
CredentialController, CredentialService, CredentialEncryptionService, UserGcpCredentialProvider, DeploymentCredential(+Repo).

---

## 2. Findings

### BUGS

**[BUG] B1 — GlobalPreviewLimiter slot leak on the VM queue path.** `PreviewService.start` acquires a Redis limiter slot inside the synchronized block (`globalPreviewLimiter.tryAcquire`, line 272) and only releases it on `stop()`/`fail()`. But the at-capacity branch (lines 365–392) early-returns a `QUEUED` placeholder **without releasing the slot**, and `drainPreviewQueue` (766–783) removes the placeholder then calls `start()` again → `tryAcquire` a **second** time for the same user. Net: every queued-then-drained preview leaks one slot; for FREE (limit 1) the counter sticks at 1 with no active preview until the 1-hour `COUNTER_TTL` expires, locking the user out of previews. Self-heals after 1h. **Currently DORMANT** because the queue path is `if (isVmMode())` and `mode=vm` is disabled in all tracked profiles — but it is a real accounting bug the moment VM mode is enabled. The `ACTIVE_PREVIEWS` gauge is correctly decremented here (368); only the Redis limiter is not.

**[BUG] B2 — `DockerExecutor.exec` leaks the full host environment into docker subprocesses.** `DockerExecutor.java:195` does `pb.environment().putAll(System.getenv())`, the exact opposite of `ComposeExecutor.exec` (240–274) which clears the env and whitelists ~17 safe vars specifically to avoid leaking `JWT_SECRET`/API keys. `DockerExecutor` is **DEAD** (only `DockerExecutorTest` references it), so no live exposure — but if anyone re-wires it, it ships the secret leak. Recommend delete (§3) rather than fix.

**[BUG] P1 / B3 — `ComposeExecutor.exec` can hang any compose build that emits >~1 MB of output until the timeout kills it.** Lines 281–291 read `is.readNBytes(MAX_OUTPUT_BYTES=1MB)` from the merged stdout/stderr **then** `waitFor`. Once the buffer is full the reader stops draining; the child blocks writing once the OS pipe fills, never finishes, `waitFor` returns false, `destroyForcibly`. So a `docker compose up --build` with verbose output is killed at timeout instead of completing. Same shape in dead `DockerExecutor` (203–209). `CloudRunClient`/`ArtifactRegistryClient` correctly drain fully (readAllBytes / readLine loop) so they're fine. Docker path is local-dev-only today, so impact is bounded.

**[BUG] B4 — `cleanupOrphans` deletes other instances' previews when scaled >1.** `PreviewService.cleanupOrphans` (1757–1794, every 30 min, **no `@SchedulerLock`**) lists all compose projects / Cloud Run services with the `es-preview-` prefix and tears down any not in **this instance's** in-memory `active` map. On a multi-instance deployment, instance A would delete instance B's live previews. Safe today only because prod+dev run on a single GCE VM (one instance). `cleanupExpired` (1739, 60s) is also unlocked — harmless (idempotent stop on own map) but same scaling caveat.

**[BUG] B5 — `PreviewController.toDto` reports a guessed tier, so `maxPreviewStarts` shown to the user is wrong.** Lines 279–282: `tier = run.getOrgId() != null ? "PRO" : "FREE"` with a `// simplified tier detection` comment, then looks up `maxPreviewStarts` from that fabricated tier instead of the run creator's real tier. Any non-PRO org user sees the PRO limit. Cosmetic (the real enforcement in `start()` uses the actual tier), but the surfaced number is a lie.

### INCONSISTENT

**[INCONSISTENT] F8 — GCS workspace archival is inert in every tracked profile, and its three writers use two different gates.** `agent.deployment.workspace.gcs-enabled` is `false` in `application.yml:484` and **not overridden** by dev/prod/benchmark (verified: grep finds the key only in base). So `WorkspaceArchiveJob.archiveOldWorkspaces` (line 58 guard) and `WorkspaceGcsUploadListener` (lines 68, 81 guards) **no-op in production** despite dev/prod configuring `gcs-bucket`. Meanwhile `GcsTaskCheckpointService` (line 58–59) gates on `durability.task-checkpoint.enabled` (default **true**) + `gcsStore.isConfigured()` (ADC + bucket resolved) — **not** `gcsEnabled()` — so it *can* run when the other two are off. And `WorkspaceRestoreService` restores from `gcsWorkspaceKey`, which only the (disabled) upload listener or the checkpoint service ever set. Net: archival/cleanup of local workspaces is effectively never happening in prod, while a different code path may still upload — a tangled, surprising state. Either wire one gate or document that prod intentionally keeps local workspaces.

**[INCONSISTENT] I2 — preview health check accepts 4xx as healthy.** `PreviewService.healthCheck` (1534–1541) returns `true` for any 4xx ("reachable but sus"). Documented as deliberate (audit K#61) but means an app that 404s on `/` is marked RUNNING. `CloudRunClient.healthCheck`/`UserCloudRunDeployer.healthCheck` use `< 500`, also accepting 4xx. `DeployEngineOrchestrator.executePathA` goes further and marks LIVE even when `healthCheck` returns false (lines 231–235). Three different "healthy" definitions across the package.

**[INCONSISTENT] I3 — deploy eligibility differs by path.** `DeploymentService.deploy` requires status `COMPLETE` (line 113); `DeployEngineOrchestrator.deployCloudRunDirect` allows `COMPLETE` **or** `FAILED` (line 95). Likely intentional but undocumented.

**[INCONSISTENT] I4 — Cloud Run preview TTL label hardcoded.** `CloudRunClient.deployPreview` writes `ttl-minutes=30` literally (line 247) while the session TTL comes from `props.getPreviewTtlMinutes()` (dev=15). The label is only read by `listPreviewServicesWithLabels`, which is **DEAD** — so the value is both wrong and unused.

### RISK

**[RISK] R1 — `preview.engine.api-key` default `"change-me-in-prod"`.** `PreviewProperties.EngineConfig.apiKey` (line 76) and base `application.yml:576` default to `change-me-in-prod`, sent verbatim as `X-Preview-Api-Key` (`PreviewEngineClient` lines 81, 116). Prod yml requires the env var (no default) and dev defaults empty, so the literal only ships under the base/default profile — but a misconfigured deploy that doesn't set the profile would auth to preview-engine with the placeholder secret.

**[RISK] R2 — `CredentialEncryptionService` generates an ephemeral key when unconfigured.** Lines 36–43: no `agent.deployment.credential-encryption-key` → random per-boot AES key (warned). Any stored GCP service-account credential becomes undecryptable after a restart ("key may have changed", line 88). Prod must set the env var; nothing enforces it at startup (no fail-fast).

**[RISK] R3 — `composeProjectName` truncates to 12 alphanumerics → collision.** Lines 1726–1729: `runId` stripped to `[a-zA-Z0-9]` and cut to 12 chars. Two runs sharing the first 12 alnum chars get the same compose project; `stop`/`cleanupOrphans` on one tears down the other. Low probability with UUIDs, real with sequential/prefixed ids.

**[RISK] R4 — `GcsWorkspaceStore` uses raw `spring.profiles.active` as the object-key prefix.** Line 49 + `objectKey` (216): multi-profile (`prod,gcp`) yields key prefix `prod,gcp/workspace/...`. Idle today (F8) but would split archives across malformed prefixes.

### LAYERING / SMELL

**[SMELL] S1 — `WorkspaceLockService` half-delivers its stated purpose.** Its javadoc says it prevents preview *and* deploy from touching a workspace simultaneously, but only `DeploymentService` ever calls `getLock` (verified grep). `PreviewService` never locks, and `removeLock` is never called → the `ConcurrentMap<String,ReentrantLock>` grows unbounded (one entry per deployed run, forever).

**[SMELL] S2 — `DevDockerfileGenerator` is 3514 LOC of mostly string-templating with embedded multi-line `node -e` scripts.** `crViteAllowedHostsPatch`/`crViteProxyPatch`/`crViteDevScriptPatch`/`singlePackageFullStackDockerfile` embed JS as Java string literals with 4-level backslash escaping (the file's own audit comment N#72 flags this as a silent-breakage hazard and proposes writing the script to a workspace file instead — still deferred). All the `cloudRun_*` half (≈360–1490) is DORMANT (cloud-run mode unused).

**[SMELL] S3 — duplicated docker-compose templates.** `PreviewAgent.writeDockerCompose` (dead) and `ComposeFileGenerator` both emit compose YAML; the 8 `switch` arms in `PreviewAgent` (256–363) are near-identical (only env vars differ). `DockerExecutor` (dead) duplicates `ComposeExecutor`'s subprocess plumbing.

**[SMELL] S4 — `PreviewSession` 16-field record with 3 telescoping constructors** drives the dozens of verbose `updateSession(new PreviewSession(s.runId(), s.composeProjectName(), …))` copies throughout `PreviewService` (every state transition re-lists all 14–16 fields). A `withStatus`/builder would remove ~200 LOC and the attendant copy-paste risk.

### DUPLICATION

**[DUPLICATION] D1 — env-var whitelist + subprocess exec repeated.** `ComposeExecutor.exec`, `DockerExecutor.exec`, `CloudRunClient.exec`/`deployOnce`/`deployPreview`/`ensurePublicAccess`/`listPreviewServices`/`rollbackToRevision`, `ArtifactRegistryClient`, `PreviewImagePusher.exec`, `BuildCachePruneJob.exec`, `PreviewDeploymentService.execute`, `WorkspaceFileService.runGit`/`runGitShow`, `WorkspaceRestoreService.runGit` each hand-roll ProcessBuilder + timeout + stream draining with subtly different behaviour (drain-fully vs bounded-read, parallel-stderr vs merged, env-clear vs env-inherit). No shared helper.

**[DUPLICATION] D2 — `readSafe` (read file ≤50 KB) is copy-pasted** in CacheDetector, QueueDetector, StorageDetector, HealthCheckDetector, StatelessnessDetector (5 identical private methods).

---

## 3. Dead code & junk (grep-verified)

- **`PreviewAgent.java` (367 LOC) — DEAD.** `grep -rn PreviewAgent src/` finds only the class itself + its log strings; no injector, no test. The `generate_preview` task it claims every plan carries is no longer dispatched to it. Entire file removable.
- **`DockerExecutor.java` (248 LOC) — DEAD** save `DockerExecutorTest`. Carries the env-leak (B2). Removable (delete test too).
- **`CloudRunClient.listPreviewServicesWithLabels` (lines 418–464) — DEAD** (no callers). The 5-arg `deployPreview` (188–192) — DEAD (only the 6-arg overload is called). `inspectHostPort` / `listPreviewContainers` in `DockerExecutor` — dead-with-their-class.
- **`DeploymentCredentialRepository` dead methods:** `findByUserIdAndCloudProvider`, `deleteByUserIdAndCloudProvider`, `deleteUserDefault`, `findByUserId` — all unreferenced in main (verified grep).
- **`verify/` package (265 LOC) — inert by design**, not dead: `preview.buildpack.enabled` OFF + no `PreviewBootEngine` bean → `PreviewVerifier.verify` always returns empty. Wired into scoring but fail-open; will activate at preview Phase 0.
- **`vm/` package — instantiated only under `preview.mode=vm`**, disabled in all tracked profiles. Not dead (conditional), but unreachable today.
- **Stale TODO/FIXME:** no literal `TODO`/`FIXME` in the package (grep clean). The standing deferral lives in a javadoc — `DevDockerfileGenerator.java:388-395` (audit N#72): *"the multi-line `node -e` fragments below are embedded as Java string literals which is an escape-level hazard … The cleaner fix is to write the node patch script to `.agent-context/vite-allowed-hosts-patch.js` … Deferred …"* — still unaddressed.
- **`COMPOSE_ON_VM` / `MANAGED_INFRASTRUCTURE` decision branches** (`DeploymentDecisionEngine` 83–150, cost estimators) compute rich path comparisons that `DeploymentController.confirm` (line 240) hard-rejects — display-only / dead-end.

## 4. Model / LLM touchpoints

**None.** This package is entirely deterministic. `DeploymentAnalyzer` javadoc explicitly stresses rule-based (not LLM) detection because results drive real cloud provisioning. No `LlmRouter`/`FallbackLlmRouter`/provider calls anywhere in `deployment/`. Project-type detection reads the planner-written `.agent-context/project.json` (`DevDockerfileGenerator.detect` 64–139, `DockerfileGenerator.resolveFromProjectJson` 115–134) but does not itself call a model.

## 5. Libraries — the 6 GCP SDKs

| SDK (pom) | Used by | Verdict |
|---|---|---|
| `google-cloud-storage` 2.68.0 | `GcsWorkspaceStore`, `UserImageBuilder` (build bucket + source upload), `config/GcsRuntimeConfig` | **Used.** GcsWorkspaceStore callers are gated off (F8) but `UserImageBuilder` (BYOK deploy) uses it live. |
| `google-cloud-run` 0.92.0 | `UserCloudRunDeployer` (engine path) | **Used** (BYOK Path A). System-ADC path uses gcloud CLI instead. |
| `google-cloud-build` 3.94.0 | `UserImageBuilder.build` | **Used** (BYOK Path A). |
| `google-cloud-artifact-registry` 1.91.0 | `UserImageBuilder.ensureArtifactRegistryRepo` (FQN `com.google.devtools.artifactregistry.*`) | **Used** (BYOK Path A). |
| `google-cloud-resourcemanager` 1.94.0 | `CredentialService.checkPermissions` (`ProjectsClient.testIamPermissions`) | **Used** (credential validation only). |
| `google-cloud-secretmanager` 2.92.0 | **NOT in `deployment/`** — used by `config/SecretManagerConfigLoader`, `crypto/SimpleKeyEncryptionService` | Out-of-package; not deployment bloat. |

GCP **client lifecycle** is correct: `UserImageBuilder`, `UserCloudRunDeployer`, `CredentialService` all create clients in try-with-resources per call (`CloudBuildClient.create`, `ServicesClient.create`, `ProjectsClient.create`, `ArtifactRegistryClient.create`) — appropriate for per-user-credential isolation, not a per-call-leak smell. `GcsWorkspaceStore` builds one `Storage` at construction (singleton) and `UserImageBuilder` builds a `Storage` per upload/bucket op (needs per-user creds, justified). `commons-compress` (pom 213) used by `UserImageBuilder.createTarball` — used.

## 6. Test coverage

10 test files / ~2.4K LOC, all under `preview/` + `verify/` + `GcsTaskCheckpointServiceTest`.
- **Well covered:** `DevDockerfileGenerator` (62 tests — detection, generation, monorepo, full-stack, node-install safety, python edge cases), `PreviewService` (start guards, concurrency limit, idempotency, stop/cleanup, all project types, per-run start limit), `PreviewSession` (7), `PreviewPortPool` (6), `BootHealthEvaluator` (6), `PreviewVerifier` (8 — fail-open), `RunRecipe` (4), `DockerExecutor` (10 — testing dead code), `GcsTaskCheckpointService` (4).
- **Zero tests:** `DeploymentService`, `CloudRunClient`, `ArtifactRegistryClient`, `DockerfileGenerator`, all `strategy/*`, all `analyzer/*` (8 detectors + decision engine), `credential/*` (encryption, permission checks, the defense-in-depth `assertUserCanWriteProject`), `engine/*` (UserCloudRunDeployer/UserImageBuilder/DeployEngineOrchestrator), `vm/*`, `GcsWorkspaceStore`, `WorkspaceFileService` (621 LOC of git-diff parsing — untested), `WorkspaceExportService`, `CaddyProxyManager`, `ComposeExecutor`, `ComposeFileGenerator`, `EnvVarDetector` (571 LOC of classification heuristics — untested), `GlobalPreviewLimiter` (the slot logic in B1). The deterministic detectors that "drive real cloud resource provisioning" have no unit tests.

## 7. Security notes (parked)

- **S7.1 `PreviewService.execInContainer` allowlist includes `curl`** (EXEC_ALLOWLIST 536–552) reachable via `POST /api/runs/{id}/preview/exec`. Container is hardened (`cap_drop ALL`, `no-new-privileges`, metadata IPs → 127.0.0.1) and args are ProcessBuilder-array + metachar-blocklisted (585–618), so it's bounded, but `curl` from inside the preview enables limited egress/SSRF from the preview network. Docker-mode only.
- **S7.2 `PreviewProxyFilter` serves previews with no auth, keyed only by 8-char shortId** (doFilter 124–142, `findPortByPrefix`). Runs at `HIGHEST_PRECEDENCE` before Spring Security. Anyone resolving `preview-<8hex>.executespec.ai` reaches that run's preview; it also strips the upstream's CSP/X-Frame-Options (66–75) so the app can be iframed. By design for public previews + 5-min TTL, but the only protection is shortId unguessability.
- **S7.3 `UserCloudRunDeployer.setPublicAccess` grants `allUsers → roles/run.invoker`** (241–278) when `allowUnauth=true`, which `DeploymentController.confirm` always passes for CLOUD_RUN_DIRECT. Logged loudly (audit I#54). User-initiated + into the user's own GCP project, so acceptable, but every engine deploy is internet-public.
- **S7.4 SSH `StrictHostKeyChecking=accept-new`** in `PreviewDeploymentService` (226, 247) — TOFU, documented (audit I#55). DORMANT (vm mode off).
- **S7.5 Credential encryption** is solid: AES-256-GCM, random 12-byte IV per encrypt, key from env, never logged/returned (`DeploymentCredential.encryptedData` is BYTEA, summary DTO omits it). Defense-in-depth re-check `assertUserCanWriteProject` (CredentialService 419–449) backs the controller guard. GitHub PAT redaction in `DeploymentGitHubService.createRepo` (100–106). No plaintext-secret leaks found in this package (the one latent leak is dead-code B2).
