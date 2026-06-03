# Preview & Live-Environment Rebuild — Execution Plan

> Created 2026-06-01 (RL-2026-05-066). Decision + analysis owner: founder review session.
> **Verdict: do NOT continue the current preview architecture — rebuild the *primitive*, salvage the
> plumbing, and DELETE the old path as each replacement lands.** This doc is the execution plan,
> security model, and cleanup schedule. Strangler-fig: every phase ships behind a flag, is validated,
> and removes the code it replaces in the SAME phase.

---

## 1. Why (the diagnosis, in one paragraph)

The current preview's job is *"take arbitrary AI-generated code and infer how to build + run it,
rewriting it as needed."* That is structurally unwinnable. Evidence: `DevDockerfileGenerator` =
**3,514 LOC / 433 heuristic branches / 22 regex rewrites of the agent's generated code** (injects
`--legacy-peer-deps`, ESM fixups, patches `vite.config` for `allowedHosts`/`0.0.0.0`); three
divergent backends (`docker`/`vm`/`cloud-run`); ~8-min cold starts; in-memory state needing a
3-layer GC; **16,891 LOC across 78 files**; and **zero wiring into the agent loop** (the worker can't
see or use the preview). More heuristics make it *less* predictable. Fix the contract, not the bugs.

## 2. Goals / non-goals

**Goals**
- **Predictability first** — the same artifact runs identically in every environment.
- **Agent-in-the-loop** — the worker authors a run-recipe, *boots + health-checks it before
  `completeTask`*, and the result feeds the deterministic score (the run-and-observe loop).
- **Net code reduction** — target ~16.9K LOC → ~4–5K by deleting the heuristic brain.
- **Security by construction** — untrusted generated code is the threat; isolation + least-privilege
  are non-negotiable (Section 6).

**Non-goals**
- Self-hosting iOS/Android simulators/emulators (delegate — Section 5).
- A general PaaS. Preview = "run the verified artifact," not "host anything."
- Building our own buildpack (use Nixpacks / Cloud Native Buildpacks).

## 3. The new contract

> **Preview runs the *verified artifact the agent committed to*. The agent authors the run-recipe and
> proves it boots in-loop; we build the image ONCE; every environment runs that same image; the host
> is chosen per target.**

Three pillars:
1. **Agent-authored run-recipe** — the worker emits a `Dockerfile` (or `Procfile`/`nixpacks.toml`) as
   a task deliverable. Default to **Nixpacks / CNB buildpacks** when the agent doesn't (or can't)
   author one; the agent's explicit Dockerfile overrides.
2. **Build-once, run-same** — build the OCI image in the pipeline, push to Artifact Registry, run that
   *same digest* in local (`docker run`), dev, and prod (Cloud Run). No per-environment rebuild, no
   per-environment detection.
3. **In-loop verification** — a `verifyBoot`/`runApp` worker tool boots the artifact, health-checks
   it, and returns pass/fail + logs. The worker fixes what's broken before `completeTask`; the
   boot/health result feeds the BUILD/TESTS scoring slots (de-LLM-judges the score).

## 4. Target matrix (per-target hosting, one contract)

| Target | Build | Host / preview | Verify signal (agent loop) |
|--------|-------|----------------|----------------------------|
| **Web / API** | buildpack or agent Dockerfile → OCI image (once) | Cloud Run (prod/dev) / `docker run` (local) — same digest | image builds + container boots + `GET /health`/declared port responds |
| **Static** | buildpack/static build | object store / Cloud Run static | build succeeds + index reachable |
| **Mobile (RN/Expo, managed)** | `expo export` (web + native bundle) | **Expo/EAS**: EAS Update + Expo Go/dev-client **QR**; `expo export --platform web` embedded in the web-preview surface | `tsc --noEmit` + `expo export` compiles + jest/RNTL tests + optional Maestro on web export |
| **Mobile (native artifacts)** | EAS Build | `.ipa`/`.apk` from EAS; device testing on a **device cloud** (BrowserStack / Sauce / AWS Device Farm) | EAS Build success + device-cloud smoke |

**iOS rule:** containers can NEVER preview iOS (Apple licensing → macOS only). **Android rule:**
emulator-in-container needs nested virt and is flaky — also delegated. **We never self-host mobile
runtimes.**

## 5. KILL list / KEEP list (cleanup is built into the phases below)

**KEEP (reuse the good plumbing):**
- `CloudRunClient`, `ArtifactRegistryClient`, `DockerfileGenerator`→buildpack adapter shell.
- Security hardening primitives: `ComposeFileGenerator` container hardening + metadata blocking,
  `ComposeExecutor` env-var whitelist, `ShellSandboxPolicy`, `security/CodeAbuseScanner`.
- Lifecycle/GC machinery (`PreviewService` lifecycle skeleton, `PreviewGarbageCollector`,
  `preview_registry` as the durable source of truth), `PreviewPortPool`, tier limits in
  `DeploymentService`.

**KILL (delete as the replacement lands — do NOT leave as debt):**
- `DevDockerfileGenerator` heuristic brain (~3,514 LOC) + all 22 code-rewrites → replaced by
  buildpack default + agent Dockerfile. **Delete the class.**
- The **`vm` mode** entirely: `preview/vm/*` (`PreviewDeploymentService`, `PreviewVmController`,
  `PreviewVmProperties`, the VM `PreviewGarbageCollector` variant) + retire the separate
  `navneetprabhakar/preview-engine` repo (SSH/SCP to a shared GW VM = SPOF + op debt).
- Per-mode divergence in `PreviewService` (collapse 3 modes → "build-once image + run it").
- The 10 hand-rolled `deployment/strategy/*` Dockerfile strategies → buildpack (keep only thin
  adapters if a target genuinely needs one).
- `preview.mode`-branching config once one path remains.

**Cleanup acceptance:** each KILL item has a deletion commit in its phase, `grep` proves no remaining
references, and the per-package `deployment/CLAUDE.md` + `docs/CODEMAP.md` are updated in the SAME
commit. A phase is not "done" until its replaced code is gone.

## 6. Security checks & constraints (first-class — preview runs UNTRUSTED generated code)

**Threat model:** AI-generated, possibly internet-reachable code may attempt: secret/credential
exfiltration, SSRF to cloud metadata, network pivot to internal infra, crypto-mining/resource abuse,
container escape, supply-chain (malicious build), and data exfil via a public preview URL.

**Controls (✅ = already exists, reuse; ➕ = new in this rebuild):**

1. **Container hardening** ✅ keep + enforce everywhere: `no-new-privileges:true`, `cap_drop: ALL`,
   run as **non-root**, **read-only rootfs** + explicit tmpfs for writable paths, seccomp default
   profile. ➕ add a **seccomp/AppArmor** profile + drop the Docker socket entirely (never mount
   `/var/run/docker.sock` into a preview).
2. **Network egress — deny-by-default** ➕ the single most important new control. Each preview runs in
   an **isolated network namespace** with **no egress except an explicit allowlist** (package
   registries during build only; nothing at runtime unless the spec requires it). Build network and
   runtime network are separate; runtime defaults to **no outbound**.
3. **Cloud metadata + internal blocking** ✅ keep `extra_hosts` redirect of
   `metadata.google.internal` + `169.254.169.254` → `127.0.0.1`; ➕ also block the VPC/internal CIDRs
   and link-local at the network policy, not just `/etc/hosts`.
4. **Secrets** ✅ keep `ComposeExecutor` env-var whitelist (PATH/HOME/USER/LANG/TZ/JAVA_HOME/proxy
   only — never JWT_SECRET / API keys). ➕ preview env is **secret-free by default**; any app secret
   is a per-preview ephemeral value, never a platform credential; build args never bake secrets into
   image layers.
5. **Least-privilege cloud identity** ➕ Cloud Run previews run under a **dedicated, minimal-scope
   service account** (no access to prod data, buckets, or the platform project) — ideally a **separate
   GCP project** for preview workloads. Egress via a restricted-egress VPC connector or none.
6. **Public-URL exposure — FIX** ➕ `CloudRunClient` currently uses `--allow-unauthenticated`
   (preview URLs are public + guessable-adjacent). New default: **authenticated previews** behind a
   **scoped, short-TTL preview token** (reuse the SSE scoped-token pattern — `scope=preview` +
   `runId` claim, 5–15 min) enforced by `PreviewProxyFilter`; public sharing is an explicit opt-in
   with an unguessable token + TTL, never the default.
7. **Resource quotas / DoS** ✅ tier deployment limits + concurrency synchronization; ➕ enforce
   per-preview **CPU / memory / PID / disk / wall-clock** caps and a **dollar/cost cap** + per-org
   rate limit; short TTL teardown is a safety control, not just UX.
8. **Build-time / supply-chain** ➕ run `security/CodeAbuseScanner` over the workspace **before**
   build (crypto/shell/DNS-exfil/metadata/obfuscation patterns already covered); **pin base images by
   digest**; buildpacks pulled from a trusted, pinned source; reject builds that fetch from
   non-allowlisted hosts. ➕ optional image vuln scan (Trivy/Grype) gate before run.
9. **Agent `runApp`/`verifyBoot` tool** ➕ MUST route through the **same trust boundary** as
   `shell`/`buildProject`: `ShellSandboxPolicy` + `CodeAbuseScanner` pre-run, the cleanroom container
   dispatch (`--rm --network none` for the verify boot unless the spec needs network), per-call
   **timeout** + **output budget** (6k inline + disk spill, like `shell`), no host network, no host
   FS beyond the mounted workspace. The boot health-check hits the app on the container-internal port
   only.
10. **Mobile / device-cloud constraints** ➕ never ship platform Expo/EAS credentials into a generated
    app; **scope EAS/Expo tokens per-org (BYOK)**; **device-cloud** sessions must use scrubbed/ephemeral
    devices with **data-residency** honored and artifacts deleted post-run; QR/preview links are
    **signed + short-lived**; EAS builds are signed and never reuse platform signing keys.
11. **Tenancy & audit** ✅ preview ownership (`runId`/`orgId`/`projectId`) + access guards; ➕ every
    preview start/stop/extend/share emits an **audit row**; cross-tenant access is impossible by
    construction (scoped token bound to the run's tenant).
12. **Fail-closed** ➕ any security gate that errors (scanner unavailable, egress policy not
    applicable, SA misconfigured) **blocks the preview** rather than falling back to a permissive
    path. The old "best-effort / ignored" catch blocks in `PreviewService` are removed.

**Security exit gate (every phase):** no new public-by-default surface; egress deny-by-default holds;
`CodeAbuseScanner` runs pre-build; no platform secret reachable from a preview; a red-team checklist
(SSRF to metadata, secret env probe, egress to an arbitrary host, oversized-resource job) passes.

## 7. Phased execution (each phase: scope · flag · security gate · cleanup · exit criteria)

> Strangler-fig. New path default-OFF behind a flag; validate; **delete the replaced code in the
> same phase**. Migrations `V227+`.

### Phase 0 — Foundations + safety rails (no user-visible change)
- **Scope:** introduce the **run-recipe artifact** concept + a buildpack adapter (`Nixpacks`/`pack`)
  behind `preview.buildpack.enabled` (OFF). Add the egress-deny-by-default network policy + dedicated
  preview service account/project wiring. Add `CodeAbuseScanner` pre-build hook.
- **Security gate:** egress policy + SA isolation validated by the red-team checklist.
- **Cleanup:** none yet (additive).
- **Exit:** a hand-written sample app builds via buildpack into an image that boots under the new
  network policy with deny-by-default egress.

### Phase 1 — Agent authors + verifies the run-recipe (the predictability + agent-loop win)
- **Scope:** `verifyBoot`/`runApp` worker tool (Section 6.9 constraints); worker prompt directive to
  author a `Dockerfile`/`Procfile` and **boot + health-check before `completeTask`**; feed boot/health
  into the BUILD/TESTS scoring slots. Flag `worker.verify-boot.enabled` (OFF).
- **Security gate:** tool runs in the cleanroom sandbox; timeout + output budget; no host network.
- **Cleanup:** none yet (the old preview still runs in parallel).
- **Exit:** eval shows the agent reliably produces a bootable artifact; boot/health is a deterministic
  score axis; no sandbox escapes in the red-team run.

### Phase 2 — Build-once → run-same for web/API; collapse modes
- **Scope:** build the image once in the pipeline, push to AR, run that digest in every env;
  `PreviewService` runs the prebuilt image instead of building at preview time. Authenticated previews
  + scoped preview token (Section 6.6); remove `--allow-unauthenticated` default.
- **Security gate:** preview URLs no longer public by default; token TTL enforced; audit rows emitted.
- **Cleanup (built-in):** **delete the build-at-preview-time path** + the `preview.mode` `docker`/
  `cloud-run` divergence branches that are now dead. `grep` proves no references.
- **Exit:** local/dev/prod previews run the identical digest; ~8-min cold start eliminated on
  re-preview; old build-at-preview code deleted.

### Phase 3 — Buildpack default + DELETE the heuristic generator
- **Scope:** Nixpacks/CNB is the default build; the agent's Dockerfile overrides.
- **Cleanup (the big one):** **delete `DevDockerfileGenerator` (~3,514 LOC) + all 22 code-rewrites +
  the 10 `deployment/strategy/*` Dockerfile strategies.** Update `deployment/CLAUDE.md` + CODEMAP in
  the same commit.
- **Security gate:** base images pinned by digest; buildpack source pinned; optional Trivy gate.
- **Exit:** no heuristic detection or code-rewriting remains; arbitrary sample apps build
  deterministically; LOC down >50%.

### Phase 4 — DELETE the `vm` mode + retire `preview-engine`
- **Scope:** remove `preview/vm/*` + the SSH/SCP-to-GW-VM path; decommission the shared `preview-gw`
  VM and archive the `navneetprabhakar/preview-engine` repo.
- **Cleanup (built-in):** delete the package, the VM config, the GW-VM infra; `grep`/IaC prove no
  references; remove the SPOF.
- **Exit:** one preview path remains (build-once image + run it); no SSH/VM orchestration in the repo.

### Phase 5 — Mobile path (Expo/EAS + device cloud)
- **Scope:** add a `MOBILE_EXPO` target; wire `expo export --platform web` into the existing
  web-preview surface; EAS Update + Expo Go/dev-client **QR** for on-device preview; EAS Build for
  artifacts; device-cloud adapter for verification (behind `preview.mobile.enabled`, OFF).
- **Security gate:** per-org EAS token scoping; signed short-lived QR/preview links; device-cloud
  data-residency + post-run scrub; no platform signing keys in generated apps.
- **Cleanup:** remove any stale "mobile = unsupported / typecheck-only" stubs once the real path lands.
- **Exit:** a generated Expo app previews on a real device via QR + web export, with the mobile verify
  signal (export compiles + RNTL tests) feeding the score.

### Phase 6 *(scale/isolation, optional)*
- **Scope:** stronger isolation when running many concurrent untrusted previews — **microVM
  (Firecracker) / gVisor**, or a managed agent-sandbox (E2B / Modal / Daytona). Evaluate vs the
  hardened-container baseline once concurrency justifies it.

## 8. Feature flags & migration
- `preview.buildpack.enabled` (P0), `worker.verify-boot.enabled` (P1), `preview.build-once.enabled` +
  `preview.auth-required.enabled` (P2), `preview.mobile.enabled` (P5) — all default **OFF**, seeded
  `V227+`, SUPER_ADMIN-toggleable. Old path stays until the replacing phase validates, then is
  **deleted** (cleanup is in-phase, not deferred).

## 9. Risk register
| Risk | Mitigation |
|------|------------|
| Agent can't author a bootable recipe for some stacks | Buildpack default catches the long tail; failure to boot is surfaced as a real (lower) score, not hidden. |
| Deleting `vm`/generator breaks a working env mid-migration | Strangler-fig — delete only after the replacement validates on eval; flags allow instant fallback before deletion. |
| Egress deny-by-default breaks apps that legitimately need network | Per-spec allowlist; default-deny with explicit opt-in declared in the run-recipe + reviewed at the plan gate. |
| Public→authenticated preview breaks existing share links | Public sharing remains an explicit opt-in path (unguessable token + TTL); default flips to authenticated. |
| Mobile device-cloud cost/latency | Web export is the default fast preview; device cloud only on explicit request, cost-capped. |
| Buildpack image bloat / slow builds | Cache layers; build-once amortizes; pin + prune. |

## 10. Rollback
Each phase is flag-gated; flipping the flag OFF restores the prior behavior **until that phase's
deletion commit lands**. Deletions happen only after the phase's eval + security gate pass, so the
window where both paths exist is the safety net. Post-deletion rollback = revert the phase's commits
(kept small + self-contained per phase for exactly this reason).

---

### Cross-references
- Decision analysis: this session (RL-2026-05-066). Latency interplay: [`latency-reduction-phased.md`](latency-reduction-phased.md).
- Reuses: `security/CodeAbuseScanner`, `tool/ShellSandboxPolicy`, `deployment/preview/ComposeFileGenerator`
  hardening, `deployment/CloudRunClient`, the SSE scoped-token pattern.
- Connects to the Devin gap analysis: this IS the "live environment the agent can run-and-observe in" +
  "verification as the spine" lever — preview becomes the agent's verifier, not just a human demo.
