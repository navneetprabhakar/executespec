# Audit: `security/` — 8-layer defense components (input/code-abuse scanning, dep policy, encryption, guardrails)

Status: **DONE** · Updated 2026-05-29 · 7 files / 1,141 lines · all read in full

## Files
- `dependency/DependencySecurityPolicy.java` (350) — guardrail-tiered npm/bun/pnpm/yarn install enforcement ✓
- `InputSanitizer.java` (240) — user-spec prompt-injection/shell/abuse scan ✓
- `CodeAbuseScanner.java` (176) — agent-generated-code malware scan ✓
- `ContextSanitizer.java` (149) — workspace-context sanitize + LLM-output secret redaction ✓
- `KeyEncryptor.java` (102) — AES-256-GCM for Redis-persisted secrets ✓
- `guardrail/GuardrailLevelResolver.java` (71) + `GuardrailLevel.java` (53) ✓

## What it does
The reusable security primitives behind the CLAUDE.md "8-layer defense": scan the user's spec before it hits the LLM (`InputSanitizer`), scan agent-written files before they hit disk (`CodeAbuseScanner`), sanitize repo/context text injected into prompts + redact secrets from LLM output (`ContextSanitizer`), gate dependency-install shell commands (`DependencySecurityPolicy`), and encrypt sensitive values at rest (`KeyEncryptor`). All tightening is keyed to a runtime `GuardrailLevel` (LOW default / MEDIUM / HIGH) read from the `security.guardrail_level` flag.

## Findings — genuinely well-engineered evasion-resistant scanners; the headline is that the teeth are OFF by default.

### The default-posture finding (cross-cutting)
- 🟡 **Most of the tightening is dormant at the default `LOW` level.** `GuardrailLevel` defaults to LOW (the flag's seed value), and LOW is documented as "today's behaviour, zero-risk rollout". Consequences at the default posture:
  - `DependencySecurityPolicy.validateSegment` returns `allow()` immediately at LOW — so **dependency-install hardening (--ignore-scripts lifecycle-RCE block, lockfile-mode, off-registry block, audit, registry pin) is entirely OFF in default prod.** A worker `npm install`-ing a malicious package with a `postinstall` hook is unguarded unless an operator has flipped the flag to MEDIUM+.
  - `CodeAbuseScanner`'s *blocking* (throw 400) only happens via the level-aware overload at MEDIUM+. At LOW the plain `scan()` returns a `ScanResult` the caller may merely log — so a reverse-shell / crypto-miner pattern written by the agent is, by default, **flagged-and-warned, not blocked** (depends on caller; verified the blocking overload is MEDIUM-gated). 
  - `InputSanitizer`'s MEDIUM 8 KB payload cap + HIGH 10%-non-printable tightening are off; only the base injection/shell/abuse pattern throws fire at LOW.
  - **For a platform whose entire job is running untrusted-prompt-driven codegen, LOW-by-default is a permissive posture.** The machinery to be strict exists and is good — it's just not on. Worth an explicit product decision (and the [validator.md](validator.md)/incremental-gate work would compound here). Note this is a posture choice, not a bug; flagging because the "8-layer defense" framing in CLAUDE.md overstates the *default* protection.

### Engineering quality (🟢 — the scanners themselves are strong)
- 🟢 **`InputSanitizer` is evasion-resistant by construction**: strips zero-width/invisible chars THEN applies Unicode **NFKC normalization** (collapses homoglyphs — Cyrillic 'а'→'a', fullwidth 'ｉｇｎｏｒｅ'→'ignore') BEFORE pattern matching, so the classic homoglyph/invisible-char prompt-injection evasions are defeated. Also **base64-decodes long blocks and re-scans the decoded content** against all three pattern lists (catches encoded payloads), plus token-stuffing/repetition defense. The injection/shell/abuse pattern throw at *every* level (the base scan is always on). This is above-average input hardening.
- 🟢 **`DependencySecurityPolicy` has a real, well-documented threat model** (lifecycle-script RCE, lockfile drift, off-registry, pre-release supply-chain, known-CVE, registry hijack) with per-manager flag knowledge across all 4 JS managers, and is honest about its own scope limit: *"True network egress blocking … needs container-level isolation — the worker today runs in the same JVM context as the orchestrator"* (SocketDevAdapter is a stub). So it's command-shape validation, not runtime egress control — correctly scoped + documented.
- 🟢 `ContextSanitizer` does defense-in-depth the right way: neutralizes injection phrases + XML system tags in *injected context*, boundary-wraps data, and **redacts secrets from LLM output** (`sk-`/`sk-ant-`/`AIza`/`Bearer`/generic key=val) before returning to the user. `GuardrailLevelResolver` is **fail-open to LOW** (a Redis hiccup can't lock users out) + emits a single Prometheus block-counter series tagged by layer+level (good operability).

### Cryptography
- 🟡 **`KeyEncryptor` derives its AES-256 key from `SHA-256(app.jwt.secret)`** — no separate KMS. AES-256-GCM itself is correct (random 12-byte IV per encrypt, 128-bit tag, IV‖ct Base64). Two consequences of the shared-secret coupling: (1) anyone who holds the JWT signing secret can decrypt every Redis-persisted secret (blast radius of a JWT-secret leak now includes BYOK keys, not just token forgery); (2) rotating the JWT secret silently makes all previously-encrypted Redis entries undecryptable (documented "graceful degradation to SYSTEM_ONLY", but it's a hidden coupling between auth-token rotation and BYOK-key durability). Single-iteration SHA-256 as a KDF is acceptable here only because the input is a high-entropy secret (not a password). Consider a dedicated key + envelope encryption before this scales.
- 🟡 **`KeyEncryptor` exists but the per-run `github_pat` / `bitbucket_app_password` are stored PLAINTEXT** (finding from [state.md](state.md)/[integration.md](integration.md)). So the platform has at-rest encryption available and uses it for *some* secrets (Redis BYOK keys) but not the per-run SCM credentials on `pipeline_run`. Inconsistent application of the one encryption primitive it has. Wiring `KeyEncryptor` over those columns is the obvious closure.

### Tech-debt (minor)
- 📝 `InputSanitizer` has a back-compat no-arg constructor (`resolver=null → LOW`). As a `@Component` it's DI'd with the resolver, but any direct `new InputSanitizer()` (tests/legacy) silently ignores the guardrail flag → LOW. Low risk; flag if a non-test caller uses it.
- 📝 All scanners are **regex denylists** — inherently incomplete (a determined attacker rephrases). That's the accepted nature of this layer (it's one of eight), but worth stating: these reduce, not eliminate, the attack surface; the real isolation is supposed to come from the container layer (which the dep-policy doc admits isn't fully there for the in-JVM worker).

## Cross-refs
Guardrail flag drives MEDIUM/HIGH tightening across → `ratelimit/` (halved limits, next this tier), `tool/ShellSandboxPolicy` + file sandbox (Tier 1). `DependencySecurityPolicy` sits behind → `tool/ShellSandboxPolicy` (Tier 1, returns its `ValidationResult`). `KeyEncryptor` should-but-doesn't cover → [state.md](state.md)/[integration.md](integration.md) (plaintext `github_pat`/`bitbucket_app_password`). `InputSanitizer.scan` called at submit → [integration.md](integration.md) (`RunOrchestrator`). Container-isolation gap (the real egress control) → cleanroom containers, [smoketest.md](smoketest.md)/`programbench/` (Tier 6). The in-JVM-worker caveat ties to the no-interrupt finding → [engine.md](engine.md).
