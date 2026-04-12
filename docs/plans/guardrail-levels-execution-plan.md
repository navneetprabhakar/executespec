# 3-Level Security Guardrails — Execution Plan

> Status: draft for review (2026-04-13). Owner: next session. Depends on: nothing.

## Goal

Introduce a runtime-switchable security posture (`LOW | MEDIUM | HIGH`, default `LOW`) that parameterises five existing safety components instead of each one carrying its own hard-coded thresholds. Operators flip one flag to trade permissiveness for paranoia; code paths don't branch.

## Non-goals

- Not replacing any existing check. Every current rule stays — only its threshold / reject-vs-warn behaviour becomes level-aware.
- Not introducing per-tenant overrides in v1 (single global flag). Per-org is a v2 extension.
- Not touching V2 tool-level policy beyond the shell sandbox it already shares with V1.

## Open questions (resolve before implementing)

1. **Resolution scope** — global flag, per-request header override (admin only), or per-org? Proposed: **global** for v1. Admin UI toggle already exists via `FeatureFlagController`.
2. **Default level** — handoff doc says `LOW`. Confirm we want "current behaviour" to remain `LOW` (so flipping the flag is the only way to get stricter).
3. **Fail-open vs fail-closed on resolver error** — if `FeatureFlagService` is unreachable, do we fall back to `LOW` (fail-open, safer for uptime) or `HIGH` (fail-closed, safer for exposure)? Proposed: **LOW** (preserve current behaviour, Redis hiccup shouldn't lock users out).
4. **Request binding** — the handoff says "resolved once per request". Implementation candidate: a `RequestLocalGuardrail` bean populated by a `OncePerRequestFilter` placed before `RateLimitFilter`; consumers read `GuardrailLevel.current()` (thread-local) OR constructor-inject a resolver. Thread-local is simpler; constructor-inject is cleaner for tests. Proposed: **constructor-inject a `GuardrailLevelResolver` singleton** that reads the flag fresh per call (already cached in `FeatureFlagService`).

## Architecture

```
FeatureFlagService
      │  (key: security.guardrail_level, value: LOW|MEDIUM|HIGH)
      ▼
GuardrailLevelResolver.current() : GuardrailLevel
      │
      ├──► InputSanitizer.sanitize(input, level)
      ├──► CodeAbuseScanner.scan(code, level)
      ├──► RateLimitFilter (reads level per request, picks limit bucket)
      ├──► ShellSandboxPolicy.validate(cmd, level)
      └──► FileSandboxPolicy.validate(path, op, level)      ← new, extracted from FileSystemTool
```

New types:
- `com.navneet.executespec.security.guardrail.GuardrailLevel` (enum)
- `com.navneet.executespec.security.guardrail.GuardrailLevelResolver` (`@Service`, wraps `FeatureFlagService`)
- `com.navneet.executespec.tool.FileSandboxPolicy` (extracted — currently inline in `FileSystemTool`)

## Per-level behaviour matrix

| Component | LOW (default, today's behaviour) | MEDIUM | HIGH |
|---|---|---|---|
| **InputSanitizer** | NFKC normalize + zero-width strip + base64 decode-and-scan. Log-only on suspicious patterns. | + reject (400) on suspicious patterns instead of logging. | + reject on any non-printable control char; 8KB payload cap. |
| **CodeAbuseScanner** | Scan for crypto/reverse-shell/PowerShell/DNS-exfil/cloud-metadata/obfuscation. Emit warning, allow run to proceed. | Block run at plan-approval if ≥1 pattern matches. | Block at plan-approval AND at every worker `writeFile` / `editFile` (pre-commit gate). |
| **RateLimitFilter** | Current per-tier limits. | Current × 0.5 for WRITE / DEPLOY / PREVIEW groups. | Current × 0.25 across all groups; add per-IP global cap. |
| **ShellSandboxPolicy** | Current allowlist (`ProcessExecutor` command list). | + reject if any arg contains `curl \| sh`, `wget \| sh`, `base64 -d \| sh`, `eval`, `exec`. | + allowlist becomes strict (no `npm run <user-string>`; only canonical verbs — `install`, `build`, `test`, `start`). |
| **FileSandboxPolicy** | Today's inline checks in `FileSystemTool` (workspace-root containment). | + deny writes outside `<runWorkspace>/src`, `/test`, `/public`, root-level config files (`package.json`, `pom.xml`, `tsconfig.json`, `Dockerfile`, `.env.example`, `README.md`). | + deny reads of anything outside workspace; deny any write to dotfiles except `.env.example` and `.gitignore`. |

Rule of thumb: `LOW` must be a strict superset of current behaviour (zero-risk rollout). `MEDIUM` enforces what V1 already logs. `HIGH` adds belt-and-braces checks that trade false-positives for exposure reduction.

## Phased execution

### P1 — Scaffolding (1 PR, low risk)
1. Create `GuardrailLevel` enum + `GuardrailLevelResolver` service.
2. Register feature flag `security.guardrail_level` via Flyway `V74__seed_guardrail_flag.sql` (default `LOW`).
3. Expose on `/api/feature-flags` admin endpoint (already works — just verify).
4. Add `/api/admin/guardrail` diagnostic endpoint (`GET` returns current level + resolved source).

Deliverable: flag resolves correctly. No behaviour change anywhere.

### P2 — Wire InputSanitizer + CodeAbuseScanner (1 PR, medium risk)
5. Add `sanitize(String, GuardrailLevel)` overload; old overload delegates with `resolver.current()`.
6. Same pattern for `CodeAbuseScanner.scan(...)`.
7. Add unit tests: each level against known-bad / known-good corpus. Target: ≥ 95% coverage on new branches.
8. Integration test via `/api/runs` — malicious payload accepted at LOW (warned), rejected at MEDIUM/HIGH.

Deliverable: request-side hardening gated on flag.

### P3 — RateLimitFilter (1 PR, medium risk — user-visible if mis-tuned)
9. Extend `RateLimitProperties` with per-level multipliers (see matrix).
10. `RateLimitPolicy.resolveLimit(tier, group, level)` — returns effective limit.
11. Document MEDIUM/HIGH impact in `docs/USER_MANUAL.md` ("support can temporarily raise limits via the flag").

Deliverable: knob operators can pull during incidents.

### P4 — ShellSandboxPolicy (1 PR, medium-high risk — could break real runs)
12. Extract MEDIUM/HIGH deny patterns into `ShellSandboxPolicy.DenyPatterns` constants.
13. Add `validate(String cmd, List<String> args, GuardrailLevel level)`.
14. Call-site updates: `ShellTool`, `ShellToolV2`.
15. Canary: run full `scripts/v2-eval.sh` at MEDIUM — confirm no false-positive breaks on real build commands (`npm install`, `mvn test`, `cargo build`, etc.).

Deliverable: worker shell chatter gated.

### P5 — FileSandboxPolicy (1 PR, high risk — refactors FileSystemTool)
16. Extract today's inline `FileSystemTool` containment checks into `FileSandboxPolicy`.
17. Wire into `FileSystemTool` (V1) + `FileSystemTool`/`WriteFileTool`/`EditFileTool` (V2 — separate callers now, each should call the same policy).
18. MEDIUM/HIGH path allowlist + dotfile rules.
19. Canary via eval harness at MEDIUM.

Deliverable: filesystem chatter gated.

### P6 — Docs + release (1 PR, low risk)
20. Update `CLAUDE.md` "Security: 8-layer defense-in-depth" section with the level model.
21. Update `docs/USER_MANUAL.md` admin section.
22. CHANGELOG + version bump.

## Test strategy

- **Unit** — per-level truth table for each of the 5 components. Table-driven `@ParameterizedTest`.
- **Integration** — one happy-path run per level × per provider (gemini LOW band, cheapest). Verify accuracy score doesn't regress at MEDIUM; HIGH is expected to reject a small set of edge-case prompts (acceptable).
- **Load** — run `scripts/v2-eval.sh` under MEDIUM for one full 9-run matrix; compare cost + accuracy to baseline. Must not regress below baseline − 5%.
- **Rollback** — flipping the flag back to `LOW` at any time must restore current behaviour with no data / DB migration needed.

## Rollout

1. Ship P1–P6 behind flag at `LOW` (no-op) on main.
2. Run staging at `MEDIUM` for 48h. Monitor:
   - `executespec.security.abuse.blocked` counter
   - `executespec.ratelimit.rejected` counter
   - Run accuracy score median
3. Promote to prod at `MEDIUM` if the above hold.
4. `HIGH` is documented as on-demand (incident response), not steady-state.

## Risks

| Risk | Mitigation |
|---|---|
| Legit builds blocked by shell deny patterns at MEDIUM | Canary via eval harness before flipping; allowlist additions are a single constant + hot reload via flag. |
| RateLimit × 0.5 at MEDIUM frustrates power users | Per-tier override already exists; expose a "trusted org" bypass flag if needed. |
| Feature flag cache stale after flip | `FeatureFlagService` already does Redis pub/sub invalidation — confirm `security.*` keys are in the invalidation topic. |
| Rollback after DB-visible audit changes | P1 migration is seed-only; safe to leave in place even if code rolls back. |

## Files that will change

### New
- `src/main/java/com/navneet/executespec/security/guardrail/GuardrailLevel.java`
- `src/main/java/com/navneet/executespec/security/guardrail/GuardrailLevelResolver.java`
- `src/main/java/com/navneet/executespec/tool/FileSandboxPolicy.java`
- `src/main/resources/db/migration/V74__seed_guardrail_flag.sql`
- Tests: `GuardrailLevelResolverTest`, `InputSanitizerLevelTest`, `CodeAbuseScannerLevelTest`, `RateLimitPolicyLevelTest`, `ShellSandboxPolicyLevelTest`, `FileSandboxPolicyLevelTest`

### Modified
- `security/InputSanitizer.java`
- `security/CodeAbuseScanner.java`
- `ratelimit/RateLimitFilter.java`, `ratelimit/RateLimitPolicy.java`, `ratelimit/RateLimitProperties.java`
- `tool/ShellSandboxPolicy.java`, `tool/ShellTool.java`
- `tool/FileSystemTool.java` (V1)
- `v2/tool/ShellToolV2.java`, `v2/tool/WriteFileTool.java`, `v2/tool/EditFileTool.java`, `v2/tool/ReadFileTool.java`
- `docs/USER_MANUAL.md`, `CLAUDE.md`, `CHANGELOG.md`, `VERSION`

Estimated size: ~800 LOC net (mostly tests).

## Suggested commit boundaries

1. `security(guardrail): enum + resolver + flag seed (P1)`
2. `security(guardrail): InputSanitizer + CodeAbuseScanner level-aware (P2)`
3. `security(guardrail): RateLimitFilter level-aware (P3)`
4. `security(guardrail): ShellSandboxPolicy level-aware (P4)`
5. `security(guardrail): FileSandboxPolicy extracted + level-aware (P5)`
6. `docs(guardrail): user manual + CLAUDE.md + version (P6)`
