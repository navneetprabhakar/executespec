# infra-build — raw findings

Repo: `/Users/navneetprabhakar/Documents/antigravity/executespec`, branch `benchmark`. Conclusions from code/config only (no docs read). Every finding has file:line.

---

## 1. Inventory — workflows, scripts, infra dirs, profiles

### GitHub workflows (`.github/workflows/`)
Live state from `gh workflow list --all`: **all 4 application workflows are `disabled_manually`**; only `Dependabot Updates` is active.

| Workflow | File | Purpose | Verdict |
| --- | --- | --- | --- |
| Build & Deploy | `deploy.yml` (498 ln) | On push to `dev`/`benchmark`: frontend lint+build, `mvn verify`, upload JAR to GCS by SHA, SSH-deploy to GCE VM, health-check, Slack notify | **DISABLED** (gh: `disabled_manually`). Content current — `benchmark` branch + env wired (`deploy.yml:9,37,186`). `main` deliberately excluded until prod creds exist (`deploy.yml:3-6`). |
| Lint | `lint.yml` (152 ln) | PR→dev/main: spotless:check, prettier+eslint, vitest, npm audit (warn-only) | **DISABLED**. Content current. |
| Test | `test.yml` (66 ln) | PR→dev/main: `mvn -B test` (unit only) | **DISABLED**. Content current; newest file (2026-05-29 header). |
| Clients | *(file deleted)* | CLI/VS Code workspace CI | **GONE + DISABLED** — file removed in `8f17c686` ("split to executespec-cli") but the workflow still shows `disabled_manually` server-side (orphaned registration `288331755`). |
| Dependabot Updates | *(implicit)* | Dependency PRs | **ACTIVE** (the only live workflow). |

> Net: **CI/CD is entirely off.** Every PR/lint/test/deploy gate is disabled; nothing gates merges or ships builds right now. (Plausibly intentional for the throwaway `benchmark` push cycle, but it means the documented gates below are all dormant.)

### Top-level scripts (`scripts/`) — 132 tracked files
| Script | Purpose | Verdict |
| --- | --- | --- |
| `a1-flag-eval.sh` | Worker-lever A/B eval (5 flags OFF vs ON) per provider, local-only | LIVE (newest, Jun 1) |
| `benchmark-run.sh` (40 KB) | Original 9-run "production readiness" benchmark, Anthropic+OpenAI, PROTOTYPE | STALE (May 4; Google "API unstable" note `:6`, predates gemini matrix) |
| `build-eval.sh` + `build-eval-cases/` | Blind held-out-test BUILD eval | LIVE-skeleton (1 sample case `rest-api-low` only; `:28` "harness skeleton") |
| `dev-cleanup.sh` / `dev-wipe-runs-and-projects.sh` + `.sql` | Dev DB/workspace/Redis wipe (TRUNCATE CASCADE, 8 statements) | LIVE (dev-only, destructive) |
| `e2e-matrix-test.sh` (51 KB) | 5 specs × 3 providers | STALE (defaults `BASE_URL=https://dev.executespec.ai` `:18` — violates local-only memory rule) |
| `e2e-preview-test.sh` / `e2e-test.sh` (34 KB) | Preview + provider E2E | STALE-ish (May 4) |
| `exhaustive-eval.sh` (59 KB) | 16 specs × 2 providers | STALE (May 4) |
| `hostel-e2e.sh` | One-off HostelMgmt project driver, reads `/tmp/hostel-pid.txt` | STALE/junk (single-target throwaway) |
| `project-view-e2e.sh` | 12-repo project-view driver, references `docs/plans/project-view-test-plan.md` | STALE |
| `test-providers.sh` (19 KB) | Anthropic+OpenAI cost/time/accuracy | STALE (no gemini; `:3` "Anthropic and OpenAI") |
| `test-resilience.sh` | Crash/resume local test | LIVE |
| `tier-eval-v2.sh` + `-results/` | Per-(provider,tier) eval via `/api/admin/eval/v2/run` | LIVE (newest) |
| `v2-eval.sh` / `v2-eval-strict.sh` + `v2-eval-specs/` `-results/` | 3-band × 3-provider full-pipeline eval | LIVE (`v2-eval.sh` newest, Jun 3) |
| `slice-email.py` / `slice-telegram.py` | Per-slice progress notify (reads creds from `application-local.yml`) | STALE (Apr; enterprise-readiness-branch tooling) |
| `programbench/` | ProgramBench runner (py) + yaml | LIVE-adjacent |
| `rn-smoke/` | React-Native smoke app — **`node_modules/` (479 entries) + 409 KB `package-lock.json` committed** | JUNK (see §3) |
| `trust-pack/render.mjs` | Trust-pack PDF renderer | STALE (Apr 28) |

### Infra dirs (`infra/`, Terraform — 50 tracked files)
- Root module: `main.tf` (181 ln, modular: network/database/cache/storage/compute/compute-sa/loadbalancer), `variables.tf`, `outputs.tf`, `backend.tf` (GCS remote state, workspace-keyed), `versions.tf` (google ~>5.0).
- `environments/{dev,prod,benchmark}.tfvars` — sizing per env.
- `modules/{network,database,cache,storage,compute,compute/sa,loadbalancer,secrets}/`.
- `scripts/startup.sh` (511 ln — GCE first-boot bootstrap) + `seed-secrets.sh`.
- `infra/benchmark/` — **standalone root module, LOCAL state** (`versions.tf:1-17`), throwaway SWE-bench/TB VM + `gcs.tf` results bucket + `tb/es_harbor_agent.py` (238 ln) + `scripts/{run-tb,eval-campaign,run-sharded,deploy,sync-results,setup-tb,make-full300}.sh` + `tb-tracker.py` (208 ln).
- `.terraform/`, `.terraform.lock.hcl` correctly gitignored (`infra/.gitignore:5`).

### Spring profiles (`src/main/resources/`)
`application.yml` (799 ln, base) + `-dev.yml` (211) + `-prod.yml` (226) + `-benchmark.yml` (153). `-local.yml` present but **not read per instructions**. `logback-spring.xml` (console for `!gcp`, Logstash JSON for `gcp`). `static/` present (index.html + assets gitignored `:46-47`; logos/manifest/sw.js tracked).

---

## 2. Findings

### [BUG] CI default `mvn test` never loads the Spring context — the only context-load smoke is tagged out
`ExecutespecApplicationTests` is the single `@SpringBootTest` context-load smoke, but it carries `@Tag("integration")` (`ExecutespecApplicationTests.java:24`), and surefire excludes `e2e,integration,chaos` (`pom.xml:450`). So `test.yml`'s `mvn -B test` (`test.yml:48`) and the deploy's `mvn verify` (`deploy.yml:99`) **never instantiate the application context** — a broken bean graph / bad `@Value` / missing migration wiring passes CI and only fails at VM boot. The file's own javadoc admits it needs real Postgres+Redis (`ExecutespecApplicationTests.java:9-14`), but nothing provides a containerized context test in the default run. This is exactly the failure class the deploy health-check (`deploy.yml:349`) exists to catch *after* shipping — too late.

### [RISK] No `concurrency:` guard on any workflow — deploys can race
`deploy.yml` has no `concurrency:` block (confirmed: none). Two pushes to `dev`/`benchmark` in quick succession run two deploy jobs in parallel, both SSH the same VM, both `ln -sf …/app.jar` + `systemctl restart executespec` (`deploy.yml:322-324`), and both race the shared `CURRENT_SHA`/`RUNNING_SHA` writes. Last-writer-wins on the symlink with an interleaved restart can boot a JAR whose `RUNNING_SHA` marker doesn't match. `lint.yml`/`test.yml` also lack `concurrency:` (wasted runners, not dangerous).

### [BUG] Health-check + deploy run as separate SSH sessions with no rollback
On health-check failure the job `exit 1`s (`deploy.yml:371`) but the **symlink + systemd unit already point at the new (broken) JAR** (`deploy.yml:322-324`) — there is no rollback-to-previous-SHA step. A red deploy leaves prod/dev pointing at a non-booting JAR until a human intervenes; `Restart=always` (`startup.sh` unit) just crash-loops it. The failure path only dumps logs (`deploy.yml:374-402`), never reverts the `app.jar` symlink.

### [INCONSISTENT] `EXECUTESPEC_REPO_MAX_SIZE_KB` default vs Terraform disk sizing — n/a; real one: TB model default contradicts YAML "not tier-active"
`infra/benchmark/scripts/run-tb.sh:15` defaults `ES_MODEL=deepseek-v4-pro-native`, but `application.yml:251-263` marks that exact model **"NOT TIER-ACTIVE YET"** (Pro is thinking-mode; `api.deepseek.com` needs `reasoning_content` echo that `OpenAiNativeToolClient` doesn't yet capture). A default TB run via `run-tb.sh` thus pins a model the backend documents as broken for tool-calling. The sibling `eval-campaign.sh:31` correctly defaults to `deepseek-v4-flash-native`. One of the two is wrong; given the YAML note, `run-tb.sh`'s default is the bug.

### [SMELL] CI lint is effectively warn-only on the backend-critical path; eslint cap hides all warnings
- Frontend eslint runs with `--max-warnings 9999` (`frontend/package.json:12`) — only hard *errors* fail; the entire warning class (incl. `any`, unused, etc.) is silently tolerated in both `lint.yml:74` and `deploy.yml:86`.
- `npm audit` is `continue-on-error: true` (`lint.yml:133`) — high/critical CVEs never block (documented backlog of vite/esbuild advisories, `lint.yml:96-101`).
- So CI's *enforcing* backend gates reduce to: spotless format (`pom.xml:489`), unit tests (e2e/integration/chaos excluded, `pom.xml:450`), frontend prettier + eslint-errors-only. **No coverage gate** — jacoco runs `report` only (`pom.xml:507`), no `check`/threshold.

### [RISK] Deploy SSH service account holds `compute.instanceAdmin.v1` + `serviceAccountUser` project-wide
`modules/compute/sa/main.tf:55-72` grants the GCE SA `roles/compute.instanceAdmin.v1`, `roles/iam.serviceAccountUser`, and `roles/iap.tunnelResourceAccessor` at project scope. The VM's own runtime SA can therefore administer (start/stop/reset/delete-config) **every** instance in `executespecv1` and impersonate SAs — far broader than "let CI SSH in." A compromise of the app JVM (which runs arbitrary LLM-generated code in workspaces) inherits project-wide compute-admin via ADC.

### [RISK] systemd unit runs the app as `root` with hardening explicitly disabled
`infra/scripts/startup.sh` writes `User=root`, `NoNewPrivileges=false`, `ProtectSystem=false`, `ProtectHome=false` (the unit block ~`startup.sh:300-345`). The same process executes untrusted generated code + `docker`/`git`/`gcloud` via ProcessBuilder. No systemd sandboxing on a root process that runs customer-spec code is a meaningful blast-radius risk (the in-app sandbox layers are the only containment).

### [SMELL] `mvn verify` in deploy is redundant with — and slower than — the (disabled) `Test` workflow, and re-runs the same excluded suite
`deploy.yml:99` runs `mvn verify -q` (spotless + the same unit suite), duplicating `test.yml` + `lint.yml`'s `spotless:check`. With all workflows disabled this is moot, but when re-enabled the deploy redoes lint+test work the PR gates already did, on the critical-path, with no `-pl`/`-o` optimization.

### [INCONSISTENT] Provider naming drift in eval scripts: `GOOGLE` vs `gemini`
Memory rule + backend say V2 uses `gemini`; scripts are split. `e2e-matrix-test.sh`/`e2e-test.sh` still pass `GOOGLE` (and `e2e-matrix-test.sh` usage `:8` says `google`), `exhaustive-eval.sh:29` defaults `PROVIDERS="ANTHROPIC OPENAI GOOGLE"`, `infra/benchmark/scripts/eval-campaign.sh:31` uses `GOOGLE:`. Backend still accepts the legacy alias (`RunRequest.java:55` regex `GOOGLE`, `RunContextComposer.java:309` `"google"→"gemini"`), so it works — but the scripts disagree with the canonical name and with `a1-flag-eval.sh:19` which correctly uses `gemini`.

### [SMELL] eval scripts hardcode an admin email/password fallback
`benchmark-run.sh:23-24`, `test-providers.sh:21-22`, `exhaustive-eval.sh:29-30`, `e2e-matrix-test.sh:18-19`, `e2e-preview-test.sh:17-18`, `v2-eval-strict.sh:34-35`, `hostel-e2e.sh:12-13` default `EMAIL=admin@executespec.ai` / `PASSWORD=Admin@123` inline. Not a live credential (overridable), but a literal default password string shipped in 7 scripts; `e2e-matrix-test.sh` additionally defaults `BASE_URL` to the **dev** environment (`:18`), so a careless invocation hits dev with a guessable admin pair.

### [INCONSISTENT] `e2e-test.sh` requires `E2E_EMAIL`/`E2E_PASSWORD` (`:12-13`, `:?`) while its siblings default them
Good hygiene in one script, undermined by the 7 above. No shared auth helper — every eval script re-implements login + token extraction (`jq -r '.accessToken // .token'`) independently, so a contract change (e.g. token field rename) breaks them one-by-one.

### [SMELL] `benchmark-run.sh` / `test-providers.sh` measure `accuracyScore`, not `verifiedScore`
`v2-eval.sh:172-173` reports `accuracyScore`; `build-eval.sh`/`exhaustive-eval.sh`/`test-providers.sh`/`v2-eval-strict.sh` also key on `accuracyScore`. Only `a1-flag-eval.sh` + `v2-eval-strict.sh` reference `verifiedScore`. Per the system, `accuracyScore` is the deprecated pre-V106 LLM-judged fallback and the UI surfaces only `verifiedScore` — so the primary eval harness (`v2-eval.sh`) is grading on the metric the product no longer trusts.

---

## 3. Dead code & junk

- **`scripts/rn-smoke/node_modules/` committed** — 479 entries + a 409 KB `package-lock.json` (`scripts/rn-smoke/`). A React-Native smoke fixture with its full dependency tree checked into git. Should be gitignored / removed; bloats every clone.
- **Tracked eval result artifacts** — `scripts/v2-eval-results/` and `scripts/tier-eval-v2-results/` have **87 git-tracked files** (timestamped `*.json`/`*.md`/`*-raw/` run dumps back to `20260504`). `.gitignore:220-222` ignores `*-raw/`/`*.json`/`*.md` under v2-eval-results going forward, but the historical set was committed *before* those rules and is still tracked (e.g. `v2-eval-20260504-*`, `a1-20260530-*`, `OPTIMIZATION-LOOP-2026-05-20.md`). 52 dirs on disk. These are throwaway run outputs that don't belong in the repo.
- **`scripts/dev-wipe-runs-and-projects.sql`** committed alongside the `.sh` — a destructive TRUNCATE-CASCADE script (8 statements) living in the tree; fine as a tool but worth noting it's a footgun if run against the wrong DB (defaults to `localhost/executespec`).
- **Orphaned `Clients` workflow** — `gh` still lists a `disabled_manually` `Clients` workflow (id `288331755`) though `clients.yml` was deleted (`8f17c686`). Stale server-side registration.
- **One-off throwaway drivers** — `hostel-e2e.sh` (reads `/tmp/hostel-pid.txt`, hard-codes `navneetprabhakar/hostel-frontend`), `project-view-e2e.sh` (provisions against `exspec-test` GH owner). Single-use; not generalized harnesses.
- **`benchmark-run.sh` (40 KB)** + **`exhaustive-eval.sh` (59 KB)** + **`e2e-matrix-test.sh` (51 KB)** — large May-4 scripts superseded by `v2-eval.sh`/`tier-eval-v2.sh`; Google-skipping / accuracyScore-based; candidates for retirement.
- **Stale workspace Dockerfiles** under `workspace/<uuid>/Dockerfile` (6) — generated run artifacts in the tree (the `workspace/` dir is run scratch; should be gitignored).

---

## 4. Model/LLM touchpoints (model/provider ids hardcoded in scripts)

These feed the consistency redesign — every model id pinned outside `application.yml`:

| File:line | Pinned id | Note |
| --- | --- | --- |
| `infra/benchmark/scripts/eval-campaign.sh:31` | `gpt-5.4`, `claude-sonnet-4-6`, `gemini-3.5-flash`, `deepseek-v4-flash-native` | Default 4-arm matrix (PLANNING-tier models) |
| `infra/benchmark/scripts/eval-campaign.sh:20` | `deepseek-v4-flash-native` | Preset-B full300 |
| `infra/benchmark/scripts/run-tb.sh:15-16` | `deepseek-v4-pro-native` (ES) / `deepseek/deepseek-v4-pro` (Harbor) | **Contradicts YAML "not tier-active"** — see §2 |
| `infra/benchmark/tb/es_harbor_agent.py:18-21` (docstring) | `deepseek-v4-flash-native`, `deepseek/deepseek-v4-flash` | Example invoke |
| `scripts/programbench/programbench_runner.py:16,297` | `anthropic/claude-sonnet-4-5-20250929` | **Stale** — YAML aliases `claude-sonnet-4-5 → claude-sonnet-4-6` (`application.yml:322`); the dated `-20250929` suffix isn't in the registry |
| `scripts/a1-flag-eval.sh:18-19` | providers `anthropic openai gemini` (no model pin — uses defaults) | Correct gemini naming |
| `scripts/test-providers.sh:22`, `exhaustive-eval.sh:29`, `e2e-matrix-test.sh`, `benchmark-run.sh:6` | provider names only (`ANTHROPIC OPENAI [GOOGLE]`) | No model pin; rely on YAML matrix |

Backend canonical source: `application.yml model-matrix` (`:84-318`) is the single source of truth; the scripts above pin models that can drift from it (the programbench runner already has). `application.yml:60,66,73` also set the legacy Spring-AI default-model constants (`claude-haiku-4-5-20251001` / `gpt-5.4-mini` / `gemini-3-flash-preview`) used by `FallbackLlmRouter` non-tool agents.

---

## 5. Profiles — per-profile override table + risks

Diffed against base `application.yml`. (Base sets `ddl-auto: validate`, localhost DS, `app.jwt.secret` = a literal dev string `:631`, CORS `localhost:5173`, free tier 5 runs `:640`.)

| Setting | base | dev | prod | benchmark |
| --- | --- | --- | --- | --- |
| Postgres/Redis | localhost literals | env-var w/ localhost fallback | env-var w/ localhost fallback | env-var w/ localhost fallback |
| `flyway.out-of-order` | (false) | **true** (`-dev.yml:49`) | — (false) | **true** (`-benchmark.yml:28`) |
| `agent.concurrency.max-runs` | 50 | (inherits 50) | **20** (`-prod.yml:98`) | (inherits 50) |
| `auto-resume-on-startup` | false | **true** (`-dev.yml:128`) | false (`-prod.yml:105`) | false (`-benchmark.yml:95`) |
| `forward-headers-strategy` | (none) | framework | framework | framework |
| `auth.refresh-cookie.secure` | false (base) | true | true | true |
| `executespec.crypto.enabled` | false (`:676`) | true | true | true |
| `app.jwt.secret` | literal dev string | env w/ **literal dev fallback** (`-dev.yml:158`) | env w/ "MUST-BE-OVERRIDDEN" fallback (`-prod.yml:154`) | env w/ literal benchmark fallback (`-benchmark.yml:120`) |
| OTLP tracing | enabled (base `:738`) | **disabled** + autoconfig-excluded (`-dev.yml:71`) | enabled, sampling 0.1 (`-prod.yml:216`) | disabled + autoconfig-excluded |
| billing default-gateway | razorpay (`:690`) | (inherits razorpay) | **stripe** (`-prod.yml:188`) | (inherits razorpay) |
| OAuth client-id/secret | (none in base) | **literal Google + GitHub secrets** (`-dev.yml:79,84`) | env w/ `:none`/empty | env-only (empty fallback) |
| SMTP password | "" | **literal app-password fallback** (`-dev.yml:156`) | env w/ empty | env w/ empty |

**Risks:**
1. **Literal OAuth secrets committed** (see §7) — `-dev.yml:79` Google `client-secret`, `-dev.yml:84` GitHub `client-secret`. prod/benchmark correctly use env-only; dev does not.
2. **Literal SMTP app-password fallback** — `-dev.yml:156`.
3. **Weak JWT fallback secrets** — all three profiles embed a literal `executespec-*-secret-key-…` default (`-dev.yml:158`, `-prod.yml:154`, `-benchmark.yml:120`). prod's reads `MUST-BE-OVERRIDDEN` (still a *valid* HS256 key if the env var is forgotten → silent weak-key prod). The base `:631` is the same string verbatim.
4. **`flyway.out-of-order: true` on dev + benchmark** — masks migration-ordering mistakes locally that would then fail on prod (which runs in-order); the very class of drift `out-of-order` was added to paper over (`-dev.yml:49` comment).
5. **`preview.engine.api-key` default `change-me-in-prod`** in base (`application.yml:576`) — prod overrides to a required no-default (`-prod.yml:142`), but base/dev/local fall back to the placeholder string.
6. **`preview.engine.registry-url` dev default `…/CHANGE_ME/preview-images`** (`-dev.yml:145`) — a `CHANGE_ME` placeholder shipped as the dev default.
7. **benchmark profile inherits `max-runs: 50` + auto-resume off** but the env is sized e2-standard-16 for parallel docker cleanrooms — concurrency cap may under-utilize, low risk.
8. **`management.endpoint.health.show-details: when_authorized`** (base `:737`) is sane; but prod/dev expose `prometheus` actuator (`:733`) — ensure the LB/IAP fronts `/actuator` (network module allows `:8080` only from LB+health-check ranges `network/main.tf:71,90`, so OK).

---

## 6. Test-suite shape

**210 total test files** under `src/test/java`. By top-level package (desc):

```
tool 21 · agent 19 · contracts 11 · deployment 10 · standards 9 · harness 9 · auth 9 ·
report 8 · execution 8 · integration 7 · arch 7 · codeintel 6 · activity 6 · router 5 ·
project 5 · folder 5 · reviewer 4 · batch 4 · smoketest 3 · security 3 · cost 3 · contract 3 ·
codereview 3 · chat 3 · specchat 2 · run 2 · referral 2 · readymade 2 · notification 2 ·
composer 2 · billing 2 · api 2 · analytics 2 · adr 2 · (1 each) terminalbench state sharing
scoring repo recovery ratelimit migration lint export entitlements engine e2e credit config
compliance chaos anonrun + ExecutespecApplicationTests
```

**Zero-test main packages with meaningful LOC** (top 10 by main LOC, cross-ref the prompt's list):

| Package | main LOC | tests |
| --- | --- | --- |
| `eval` | 4,486 | **0** |
| `testgen` | 3,664 | **0** |
| `memory` | 2,642 | **0** |
| `validator` | ~2,000 | **0** |
| `prreview` | 1,892 | **0** |
| `benchmark` | 1,816 | **0** (new V236 tracking layer) |
| `test` (operator test-controllers) | 1,757 | **0** |
| `swebench` | 1,106 | **0** |
| `context` | 932 | **0** |
| `programbench` | 902 | **0** |

Other notable zero-test: `sse` (851 — the live event broadcaster), `rollout` (584), `optimiser` (542), `saga` (435 — compensation/rollback), `crypto` (394 — at-rest encryption!), `onboarding` (395), `apitests` (385).

**Cross-ref to the prompt's LOC list:** `billing` (9,039 LOC) has only **2** test files; `api` (5,598) only **2**; `state` (4,675) only **1**; `engine` (2,797 — the relocated WorkerAgent) only **1**. `deployment` (17,156 LOC, the largest) has 10 — thin for its size. `auth` (12,097) 9. `integration` (10,833 — RunOrchestrator + collaborators) 7.

**Tagged-group reality:** the prompt assumed `e2e/integration/chaos` groups; the actual `@Tag` usage is **minimal**:
- `@Tag("e2e")`: **1 file** (`e2e/PipelineE2ETest.java:38`)
- `@Tag("integration")`: **2 files** (`integration/PostgresIntegrationBase.java`, `ExecutespecApplicationTests.java:24`)
- `@Tag("chaos")`: **1 file** (`chaos/ChaosScenariosTest.java`)
- `@Tag("network")`: **1 file** (`tool/MavenDependencyValidatorTest.java:33`) — **NOT in surefire's excludedGroups** (`pom.xml:450` excludes only e2e/integration/chaos), so this "network" suite *does* run in default CI; the class stubs the network in its unit paths (`:30-33`) but any genuinely network-touching `@Test` here would flake CI.

So the entire "tagged heavy-test" surface is **4 files**. Everything else in the 210 is a plain unit test that runs in the default `mvn test`. The `@SpringBootTest` full-context loaders are only `ExecutespecApplicationTests` (tagged out) + `PostgresIntegrationBase`/`PipelineE2ETest`/`ChaosScenariosTest` (all tagged out) — i.e. **no full Spring context loads in the fast CI run** (the BUG in §2). `analytics/EventTrackingServiceTest` is Mockito-only (`@ExtendWith(MockitoExtension.class)`, `:26`), not a context test.

---

## 7. Security notes (parked)

- **Literal secret present at `src/main/resources/application-dev.yml:79`** (Google OAuth `client-secret` default).
- **Literal secret present at `src/main/resources/application-dev.yml:84`** (GitHub OAuth `client-secret` default).
- **Literal secret present at `src/main/resources/application-dev.yml:156`** (SMTP/Gmail app-password default).
- **Weak literal JWT fallback secrets** at `application.yml:631`, `application-dev.yml:158`, `application-prod.yml:154`, `application-benchmark.yml:120` — valid HS256 keys usable if the env var is unset (silent weak-key risk; prod's literally reads `MUST-BE-OVERRIDDEN`).
- **Placeholder secrets shipped as defaults:** `preview.engine.api-key: change-me-in-prod` (`application.yml:576`); `preview.engine.registry-url: …/CHANGE_ME/…` (`application-dev.yml:145`).
- **GitHub OAuth `client-id` repeated literal** `Ov23lijrPPx9fhPemczg` across `-dev.yml:83`, `-prod.yml:79`, `-benchmark.yml:57` — client-ids are public so lower-severity, but coupled with the dev `client-secret` above it's a complete credential pair in the tree.
- **Over-broad GCE SA** (`modules/compute/sa/main.tf:55-72`: project-wide `compute.instanceAdmin.v1` + `serviceAccountUser`) — see §2 RISK.
- **App runs as root, systemd hardening disabled** (`infra/scripts/startup.sh` unit: `User=root`/`ProtectSystem=false`/`NoNewPrivileges=false`) — see §2 RISK.
- **Deploy injects API keys from a plaintext GCS file** (`config/api-keys`, read in `deploy.yml:274-280` + `startup.sh:165-180`) as the fallback path beside Secret Manager — plaintext-in-GCS is the weaker of the two and `seed-secrets.sh` exists to migrate off it but the fallback code is still live.
- Positive: prod/benchmark keep all real secrets env-only; `/api/v2/test/**` is now `SUPER_ADMIN`-gated (`SecurityConfig.java:242-243`); Terraform DB password is `random_password` → Secret Manager (`modules/database/main.tf:60-88`), never in tfvars; `.terraform.lock.hcl`/tfstate gitignored.

---
