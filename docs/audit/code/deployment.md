# Audit: `deployment/` — preview (docker/vm/cloud-run) + Cloud Run deploy + GCS workspace

Status: **DONE** · Updated 2026-05-29 · 79 files / 17,053 lines · security surface read in full + scanned; generators (DevDockerfileGenerator 3538, PreviewService 1817) bulk-read against the detailed package CLAUDE.md

## What it does
Triple-mode preview (Docker Compose local / VM dev / Cloud Run prod, 5-min TTL) + permanent Cloud Run deploy (9 languages) + GCS workspace archiving + GitHub deploy push. The largest package in the codebase.

## Findings — security-hardened infra; the strongest sandboxing in the system.

### 🟢 Security (this is the package that does isolation *right*)
- 🟢 **Env-var whitelist on preview containers** (`ComposeExecutor`): clears the JVM's inherited environment and passes ONLY `PATH/HOME/USER/LANG/TZ/DOCKER_*/JAVA_HOME/*_PROXY` — so **`JWT_SECRET`, LLM API keys, DB creds never leak into a preview of generated code**. This is exactly the env-strip the dead [validator.md](validator.md) `ProcessExecutor` was flagged for *lacking*; here it's correctly implemented.
- 🟢 **Cloud-metadata SSRF blocking**: app preview containers get `extra_hosts` redirecting `169.254.169.254` + `metadata.google.internal` → `127.0.0.1`, so a generated app can't reach the GCP metadata endpoint to steal the VM's service-account token.
- 🟢 **Container hardening**: preview app containers + the test-exec container run `no-new-privileges:true` + `cap_drop: ALL`; test execution adds `--read-only --memory --cpus` + read-only workspace mount. Cloud Run previews are GCP-managed isolation. (DB containers exempt — need chown.)
- 🟢 **No shell-injection surface**: every subprocess is `ProcessBuilder(argv-list)` (docker build/push, `gcloud`, `git`, `docker compose`) — never `/bin/sh -c`. All `gcloud`/`docker` calls have **timeouts** (deploy 10min / delete 5min / list 2min); stdout+stderr drained in parallel (`CompletableFuture`) to avoid pipe-buffer deadlock.
- 🟢 **Deploy credentials AES-256-GCM encrypted** (`credential/CredentialEncryptionService`: 12-byte IV + GCM tag). NOTE: this is the **3rd** independent AES-256-GCM implementation in the codebase (after `security/KeyEncryptor` for Redis + `crypto/SimpleKeyEncryptionService` for DB cols) — all three correct, but a consolidation candidate (one `EncryptionService` abstraction with pluggable key sources). 🟡 minor.
- 🟢 **TOCTOU-safe concurrency**: preview-start synchronized on the concurrent-limit check; 3-layer GC for VM previews (app-side 60s in-memory + DB-backed 10min + VM cron 5min) so an app restart can't orphan a preview.

### 🟢 Lifecycle / cost
- 🟢 5-min preview TTL (prod), `cleanupExpired()` every 60s + `DeploymentCleanupJob` cron for permanent deploys (`tier_config.max_deployments` enforced: FREE=1/PRO=3/TEAM+ unlimited). Cloud Run resources (service + AR image) cleaned up on preview failure; orphan-cleanup race fixed by storing `cloudRunServiceName` upfront. PATCH-mode runs (autofix + bench) correctly skip auto-preview.

### 📝 Tech-debt
- 📝 **`DevDockerfileGenerator` is 3,538 LOC** — the single largest file in the codebase. It's 9-project-type Dockerfile/heuristic generation (SPRING_BOOT/JAVA/NODE_*/PYTHON/GO/RUST/FULL_STACK/STATIC_HTML), readable but a god-file; the project-type detection is duplicated across `DevDockerfileGenerator` + `DockerfileGenerator` + `ComposeFileGenerator` (each reads `.agent-context/project.json` then falls back to file heuristics). Consolidating the detection into one resolver would cut a lot of parallel heuristic code.
- 📝 `PreviewService` (1,817) is a god-service spanning all three preview modes; candidate to split per-mode behind the existing engine abstraction.

## Cross-refs
Auto-preview kickoff at run-end → [integration.md](integration.md) (`RunOrchestrator`). Test-exec container reused by → [bench-eval-testgen.md](bench-eval-testgen.md) (`testgen/HeadlessTestExecutor`). Env-strip done-right contrast → [validator.md](validator.md) (dead `ProcessExecutor` lacked it). 3rd AES-GCM impl → [security.md](security.md) (`KeyEncryptor`), [crypto.md](crypto.md) (`SimpleKeyEncryptionService`). Deploy push uses per-user PAT → [auth.md](auth.md) (plaintext `github_pat`). GCS workspace archive → [state.md](state.md) (run_repo_snapshot), `recovery/` (Tier 3).
