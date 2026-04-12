# Figma Integration — V2 Design & Execution Plan

> Status: draft for review (2026-04-13). Owner: next session.
> Supersedes `figma-integration-design-doc (1).md` (April 2026, targeted V1 package layout).
> This plan targets the V2 harness (`com.navneet.executespec.v2.*`) and the existing
> integration / billing / OAuth patterns actually present in this repo.

## Scope

- Consume Figma designs as structured context for the V2 planner + worker.
- Gated: only available to users whose org tier is **ENTERPRISE** or **MAX**, and only when the org has explicitly enabled the integration (parity with Slack / JIRA / Confluence).
- Primary extraction path: **Figma MCP Server** (`https://mcp.figma.com/mcp`), invoked **lazily** on first tool call per run.
- Automatic fallback to **Figma REST API** when MCP is unreachable, rate-limited, or returns an unusable response.
- Multi-tenant OAuth 2.0 (identical pattern to Slack `SlackOAuthService`); Personal Access Tokens only as an admin-only dev escape hatch.

## Non-goals

- Not generating pixel-perfect clones. We produce "design-informed" code; deviation is expected.
- Not implementing Zeplin in this phase. Re-evaluated after Figma ships.
- Not bundling asset export (SVG/PNG exports) in the first drop — that lands in Phase 3.
- Not replacing `UiStyleResolver`. Figma context is additive to the existing DESIGN.md pipeline; planner uses both.

## Open questions (resolve before implementing)

1. **MCP server-to-server auth** — `HttpClientSseClientTransport` in Spring AI 1.1.4 takes a fixed URL at construction; per-user bearer token injection is not obviously supported. Three options, decide after P0 probe:
   - **(a)** Per-user singleton MCP client, cached with TTL on Caffeine — simple, but loses cache benefits across orgs.
   - **(b)** Per-request client construction — higher cost per call, cleanest isolation.
   - **(c)** Custom transport that accepts `HttpHeaders` from `RequestAttributes` — most work, best long-term fit.
2. **Design-context injection site** — two places can read the Figma data:
   - **Once at plan time** — `PlannerAgentV2` fetches context before emitting the plan, embeds a summary into `PlanV2` (new field `designContextRef`). Worker reads the ref and re-fetches only if it needs deeper detail.
   - **Per worker task** — `WorkerAgentV2` invokes the `figmaDesignContext` tool as needed.
   - **Proposed: both.** Planner prefetch for overall architecture; worker tool for targeted drill-in on a specific frame.
3. **Rate-limit blast radius** — Figma's per-plan limits are strict. Do we queue concurrent runs for the same org, or let them fail fast with `FigmaRateLimitException`? **Proposed: fail fast, surface as a validation-report warning, re-runable.**
4. **Screenshot storage** — Figma screenshot returns PNG bytes; planner may want them for visual diff, validation report may want them embedded. Where do we store them?
   - **(a)** Drop into workspace `<runId>/.figma/*.png` (simplest, ephemeral).
   - **(b)** Upload to GCS like other artifacts, URL-ref in plan.
   - **Proposed: (a)** for v1; revisit if validation-report-share traffic grows.

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                       V2 Run Submission                              │
│   POST /api/runs  body={userRequest, figmaUrls?[]}                   │
└──────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│   FigmaIntegrationGate.enforce(orgId, runRequest)                    │
│     - tier ∈ {ENTERPRISE, MAX}?           → TierEnforcementService   │
│     - integration enabled?                → IntegrationConfig        │
│     - org has valid Figma OAuth creds?    → FigmaOAuthService        │
│     - else: reject 403 with actionable error                         │
└──────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│   V2RunOrchestrator.submit(...)                                      │
│     if figmaUrls present:                                            │
│         figmaService.prefetch(runId, orgId, urls) -- async --▶       │
│     invokes PlannerAgentV2                                           │
└──────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│   PlannerAgentV2                                                     │
│     system prompt augmented with prefetched design summary (if any)  │
│     plan JSON carries designContextRef → runId (or S3 URL)           │
└──────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│   WorkerAgentV2 (per task)                                           │
│     has access to `figmaDesignContext` tool via ToolRegistryV2       │
│                                                                      │
│     tool call ─▶ FigmaExtractionService.extract(url, opts)           │
│                  ├─ try MCP (FigmaMcpClient, lazy-initialized)       │
│                  │   └─ Spring AI McpSyncClient per-org, Caffeine-   │
│                  │      cached with 30min TTL, bearer from OAuth     │
│                  │                                                   │
│                  └─ on failure (circuit-breaker): REST (FigmaRest-   │
│                      ApiService) — RestClient, node-tree walker      │
└──────────────────────────────────────────────────────────────────────┘
```

### Package layout (aligns with existing `com.navneet.executespec.integration.*`)

```
com.navneet.executespec.integration.figma/
├── FigmaAdapter.java              (extends OutboundAdapter — parity with JiraAdapter)
├── FigmaProperties.java           (@ConfigurationProperties, executespec.figma.*)
├── FigmaOAuthService.java         (mirrors SlackOAuthService)
├── FigmaOAuthController.java      (authorize + callback; /api/integrations/figma/*)
├── FigmaIntegrationGate.java      (tier + enablement check)
├── FigmaExtractionService.java    (facade: MCP primary, REST fallback)
├── FigmaUrlParser.java
├── FigmaRateLimiter.java          (mirrors SlackRateLimiter; Redis-backed token bucket)
├── client/
│   ├── FigmaMcpClient.java        (lazy McpSyncClient, Caffeine org-keyed)
│   └── FigmaRestClient.java       (Spring WebClient, 429 exponential backoff)
├── mapper/
│   └── FigmaNodeMapper.java       (Figma node → V2 DesignContext)
├── model/
│   ├── FigmaDesignContext.java    (V2 domain record — serialized into PlanV2)
│   ├── FigmaUrlInfo.java
│   ├── FigmaDesignToken.java
│   └── mcp / rest / exception subpackages
└── CostHook.java                  (records MCP / REST call cost on V2RunCostTracker)

com.navneet.executespec.v2.tool/
└── FigmaDesignContextTool.java    (V2 Tool contract; delegates to FigmaExtractionService)
```

### DB schema additions

One new table + one column:

```sql
-- V78__figma_integration.sql
CREATE TABLE figma_credential (
  id                BIGSERIAL PRIMARY KEY,
  org_id            UUID NOT NULL REFERENCES org(id) ON DELETE CASCADE,
  user_id           UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  kind              TEXT NOT NULL CHECK (kind IN ('OAUTH','PAT')),
  access_token      TEXT NOT NULL,  -- encrypted via KeyEncryptor
  refresh_token     TEXT,           -- OAuth only; encrypted
  expires_at        TIMESTAMPTZ,
  scopes            TEXT[],
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (org_id, user_id)
);
CREATE INDEX idx_figma_cred_org ON figma_credential (org_id);

-- V79__pipeline_run_figma_columns.sql
ALTER TABLE pipeline_run
  ADD COLUMN figma_urls TEXT[],               -- original URLs submitted with the run
  ADD COLUMN figma_context_path TEXT;         -- workspace-relative path to cached design JSON
```

### Feature flags

- `integration.figma.enabled` (global kill switch, default `false`)
- `integration.figma.mcp_enabled` (default `true` — if flipped off, goes straight to REST)
- `integration.figma.rest_enabled` (default `true`)
- `integration.figma.max_screenshots_per_run` (int, default `5`)

All managed through the existing `FeatureFlagService` + admin UI.

## Component-by-component

### FigmaOAuthService

- POST `/api/integrations/figma/authorize` → returns Figma OAuth URL with `state` param signed by `JwtUtil`.
- GET  `/api/integrations/figma/callback` → exchange `code`, persist to `figma_credential`, encrypted via `KeyEncryptor`.
- POST `/api/integrations/figma/disconnect` → soft-deletes the credential.
- Refresh flow identical to Slack's: lazy refresh when `expires_at < now() + 5min`.

### FigmaIntegrationGate

Called from **two places**:
1. Run submission in `RunController` / `V2RunController` — if spec references Figma, reject 403 when tier ≠ ENTERPRISE/MAX, integration disabled at org level, or no valid credential.
2. Tool dispatch in `ToolRegistryV2.bind(ctx)` — the `figmaDesignContext` tool is only bound into the registry when the gate passes for the current run's org. Prevents workers from even seeing the tool if they can't use it.

### FigmaExtractionService (primary orchestration)

```java
public FigmaDesignContext extract(String runId, UUID orgId, FigmaUrlInfo url, ExtractOptions opts) {
    FigmaCredential cred = oauthService.getCredential(orgId);          // throws FigmaNotConnectedException
    if (!rateLimiter.tryAcquire(orgId)) throw new FigmaRateLimitException(...);
    if (mcpEnabled && !circuitBreaker.isOpen("mcp")) {
        try {
            return mcpClient.fetch(url, cred, opts);
        } catch (Exception mcpErr) {
            circuitBreaker.recordFailure("mcp", mcpErr);
            log.warn("Figma MCP fetch failed, falling back to REST: {}", mcpErr.getMessage());
        }
    }
    if (restEnabled) {
        return restClient.fetch(url, cred, opts);
    }
    throw new FigmaExtractionException("Neither MCP nor REST path available");
}
```

- Circuit breaker: Resilience4j, 5 failures / 60s window → open for 60s.
- Caffeine cache keyed by `(orgId, fileKey, nodeId, format)` → cached `FigmaDesignContext` for 30 min.

### FigmaMcpClient (lazy MCP)

- `McpSyncClient` is **not** instantiated at app startup.
- First call for org `X` → construct transport with that org's bearer token, initialize, cache client under `orgId`.
- Caffeine cache: max 100 clients, 30-min idle TTL. On eviction: `client.close()`.
- Avoids the Spring AI YAML approach (no per-request auth) and matches the decision needed in open question #1.

### FigmaRestClient (REST fallback)

- Endpoints used: `/v1/files/{key}?depth=2`, `/v1/files/{key}/nodes?ids=...`, `/v1/files/{key}/variables/local`, `/v1/images/{key}?ids=...&format=svg`.
- Exponential backoff on 429 (500ms base, 8 retries, jitter). Circuit breaker tied to same key as MCP but separate meter.
- Response truncation at 10MB JSON — above threshold, throw `FigmaFileTooLargeException` and require user to pass a specific node URL.

### FigmaDesignContextTool (V2 Tool)

- JSON schema:
  ```json
  {
    "type": "object",
    "properties": {
      "figmaUrl":       { "type": "string", "description": "Figma frame/file URL" },
      "outputFormat":   { "type": "string", "enum": ["react_tailwind","html_css","vue","angular"] },
      "includeScreenshot": { "type": "boolean", "default": false }
    },
    "required": ["figmaUrl"]
  }
  ```
- Bound into `ToolRegistryV2` conditionally: only if `FigmaIntegrationGate.canUse(runCtx)` returns true.
- Returns `ToolResult.Ok` with the serialized `FigmaDesignContext` JSON, or `ToolResult.Err` with kind `UNAVAILABLE / FORBIDDEN / RATE_LIMITED / NOT_FOUND / UPSTREAM_ERROR`.
- Cost attributed through `CostHook` to `V2RunCostTracker` under phase `INTEGRATION`.

### Planner prompt changes

- `resources/prompts/v2/planner.md` gets a new optional section injected when `figma_urls` are present in the triage payload. Instructs planner to:
  1. Pre-call `figmaDesignContext` once per URL to form the architecture.
  2. Embed structural references (component names, spacing scale, color tokens) into each task's `description` + `hardConstraints`.
  3. Not duplicate the raw Figma JSON into the plan — only the ref.

### Worker prompt changes

- `resources/prompts/v2/worker.md` — one paragraph clarifying that `figmaDesignContext` is the authoritative source when present, overriding generic styling defaults from `UiStyleResolver`.

### Validation Report

- `ValidationReportPanel` gains a "Design Compliance" row when the run had a Figma context. Data persisted on `ValidationReport` as a new structured field `designComplianceJson` (color match %, typography match %, layout match %).

## Phased execution

### P0 — Spike (1 day, throwaway branch)
1. Manual probe: can an MCP client initialized with a Figma OAuth bearer token successfully call `get_design_context` from our backend (not from an IDE)?
2. If yes → unblock Phase 1. If no → file the blocker with Figma, start at REST.
3. Document findings at `docs/figma-probe-notes.md`.

### P1 — OAuth + gating (1 PR, low risk)
4. `figma_credential` table + Flyway migrations.
5. `FigmaProperties`, `FigmaOAuthService`, `FigmaOAuthController`, `FigmaIntegrationGate`.
6. Admin UI toggle under Settings → Integrations (mirrors Slack/JIRA UI).
7. Feature flags seeded via migration, `false` by default.

### P2 — REST extraction path (1 PR, medium risk)
8. `FigmaRestClient`, `FigmaNodeMapper`, `FigmaUrlParser`.
9. `FigmaExtractionService` with REST-only implementation (no MCP yet).
10. `FigmaDesignContextTool` wired to `ToolRegistryV2`.
11. Unit tests with recorded REST fixtures; WireMock integration test.
12. Behind flag `integration.figma.enabled`, dogfood internally.

### P3 — MCP primary path (1 PR, medium risk)
13. `FigmaMcpClient` with lazy per-org client cache.
14. `FigmaExtractionService` tries MCP first, falls back to REST on failure.
15. Circuit breaker + rate-limit metrics (`executespec.v2.figma.*`).
16. Eval: one V2 run with a real Figma URL per provider at each tier.

### P4 — Planner/worker prompt integration (1 PR, medium risk)
17. Inject Figma section into `planner.md` + `worker.md`.
18. Extend `PlannerAgentV2` tool-call triage so the design context tool is actually used when URLs are present.
19. `PlanV2.designContextRef` (optional).
20. Extend `ValidationReport` with `designComplianceJson`.

### P5 — UI + docs (1 PR, low risk)
21. `NewRunModal` — optional "Figma URL" field, visible only if org enabled + tier allowed.
22. `ValidationReportPanel` — Design Compliance row.
23. `USER_MANUAL.md` + `CLAUDE.md` updates.
24. CHANGELOG + version bump.

### P6 — Asset pipeline (follow-up PR, deferred)
- Export images/icons as SVG via REST `/v1/images`; drop into workspace `<runId>/assets/`.
- Worker can reference them directly without re-fetching.

## Test strategy

- **Unit** — `FigmaUrlParser`, `FigmaNodeMapper`, MCP JSON-RPC shape assembly, OAuth token-refresh path.
- **Contract (WireMock)** — one fixture per REST endpoint + one per MCP tool, recorded from actual Figma calls in P0.
- **Integration** — full E2E: OAuth connect → V2 run with Figma URL → assert `figmaDesignContext` was invoked → assert `designComplianceJson` appears on the report.
- **Graceful degradation** — simulate MCP 503 → confirm REST fallback → confirm run still succeeds with warning logged.
- **Eval** — one pilot spec with a real Figma URL, run across all 3 providers at MEDIUM. Must not regress accuracy; should improve "design compliance" subjective grade.

## Cost / billing

- Figma MCP is free during beta, paid post-GA. We don't charge the user for Figma itself until Figma charges us.
- MCP + REST call counts logged to `run_cost_log` under a new phase label `INTEGRATION`. Per-run visible on the cost widget.
- Hard cap: `integration.figma.max_calls_per_run` (default 20) in `FigmaRateLimiter`. Runaway loops can't exhaust quota.

## Risks

| Risk | Mitigation |
|---|---|
| MCP server rejects server-to-server auth | P0 probe catches this; REST fallback is production-ready |
| Figma rate limits on Starter tier | Gate to ENTERPRISE/MAX orgs only; Figma plan likely BUSINESS+; fail-fast with re-run path |
| Token leakage via logs | `KeyEncryptor` at rest; `@JsonIgnore` on DTOs; shared helper `redactToken()` for structured logs |
| Large Figma files stall the run | Depth-limited REST (`depth=2`); specific-node URL requirement for >10MB |
| OAuth refresh races with in-flight runs | Credential lookup is transactional; refresh attempt happens once per request with pessimistic lock |
| MCP beta shuts down | REST path was already the fallback; flip `mcp_enabled` = false |

## Files that will change

### New (~22 files)
- Integration package as listed above (13 files)
- `FigmaDesignContextTool.java` (V2 tool)
- `V78__figma_integration.sql`, `V79__pipeline_run_figma_columns.sql`
- Tests: 5 fixture / contract / integration files

### Modified
- `V2RunOrchestrator.java` — prefetch wire, persist `figmaUrls` on `PipelineRun`
- `PlannerAgentV2.java` — gate-aware prompt augmentation
- `WorkerAgentV2.java` — nothing (picks up tool automatically via registry)
- `ToolRegistryV2.java` — conditional bind in `bind(ctx)`
- `ValidationReport.java` + `ValidationReportService.java` — new field
- `resources/prompts/v2/planner.md`, `worker.md`
- `frontend/src/components/runs/NewRunModal.tsx` — optional Figma URL input
- `frontend/src/components/runs/ValidationReportPanel.tsx` — design compliance row
- `frontend/src/pages/SettingsPage.tsx` (+ Integrations card) — connect/disconnect Figma
- `CLAUDE.md`, `docs/USER_MANUAL.md`, `CHANGELOG.md`, `VERSION`

Estimated size: ~2400 LOC net.

## Suggested commit boundaries

1. `integration(figma): probe findings (P0)` (docs only)
2. `integration(figma): OAuth + credential table + gate (P1)`
3. `integration(figma): REST extraction + V2 tool + tests (P2)`
4. `integration(figma): MCP primary path + circuit breaker (P3)`
5. `integration(figma): planner + worker prompt integration (P4)`
6. `integration(figma): UI + docs (P5)`
7. `integration(figma): asset export pipeline (P6, follow-up)`
