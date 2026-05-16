# ExecuteSpec

**v1.0.0-M1** · Spec-driven AI coding platform. Submit a natural-language spec; ExecuteSpec plans the work, codes across one or more repos, builds + tests, opens a pull request with a deterministic verified score. Human approves the plan; everything after is automatic.

**Stack:** Java 21 · Spring Boot 3.5 · Spring AI 1.1.6 · PostgreSQL · Redis · React 18 · Vite 5 · Tailwind CSS 4 · TypeScript 6 · GCP (us-east4)

---

## Quick start

```bash
# Prerequisites: Java 21, PostgreSQL (localhost:5432/executespec), Redis, Node.js 18+

export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANTHROPIC_API_KEY=sk-ant-...    # default provider since 1.0.0-M1
export OPENAI_API_KEY=sk-...           # for openai-targeted runs + embeddings
export GOOGLE_API_KEY=AIza...          # for gemini-targeted runs (optional)

# Option 1: Supervisor (recommended)
./supervisor.sh start

# Option 2: Manual
npm --prefix frontend install && npm --prefix frontend run build
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -DskipTests
```

Open **http://localhost:8080**. Default admin: `admin@executespec.ai` / `REDACTED_FOR_PUBLIC_RELEASE` (forced password change on first login).

---

## Pipeline (V2 — the only execution path)

```
POST /api/runs  →  RunOrchestrator
                       ↓
                 SmartClassifierAgent (LIGHTWEIGHT)
                   heuristic fast-path for tiny specs; LLM verifier otherwise.
                   Verdict: SINGLE_SHOT | SMALL | MEDIUM | LARGE | LEGACY
                       ↓
                 Strategy router (per verdict):
                   • SINGLE_SHOT  → OutlinePlannerAgent.synthesizeSingleShot (no LLM, 1-task plan)
                   • SMALL/MEDIUM → OutlinePlannerAgent FULL_PLAN (PLANNING tier, 32k cap)
                   • LARGE        → SKELETON_ONLY outline → parallel TaskExpanderAgent (WORKHORSE)
                                    → CoherencePlannerAgent (LIGHTWEIGHT, patchPlan tool)
                   • LEGACY       → LARGE pipeline + recursive depth-2 EPIC expansion
                       ↓
                 Plan approval gate
                   PRODUCTION: pauses at AWAITING_APPROVAL until human approves
                   PROTOTYPE / skipApproval: bypasses
                       ↓
                 SequentialExecutor (one task at a time, per-task tier routing)
                   • LOW    → LIGHTWEIGHT model (30-iter / 4k token cap)
                   • MEDIUM → WORKHORSE   model (50-iter / 8k token cap)
                   • HIGH   → PLANNING    model (75-iter / 16k token cap)
                   • PATCH mode → per-provider caps (Anthropic 150, OpenAI 100, Gemini 120)
                   → WorkerAgent (multi-turn, native tool calling via LlmRouter)
                   → CompleteTaskTool terminates the loop
                   → buildProject smoke test (final task on multi-task plans)
                   → ONE retry with build error injected on smoke fail
                       ↓
                 ReviewerAgent — general pass + project-type-aware wiring pass
                       ↓
                 ★ COMPLETE / PARTIAL / FAILED
                    ↓ (background)
                 AccuracyAssessmentService → ValidationReport
                                            (verifiedScore is multiplicative + deterministic)
                    ↓
                 TestGenOrchestrator (plan → generate → compile → execute)
                    ↓
                 Auto-preview (5-min TTL) + GitHub branch + PR
                    (per-repo PR for multi-repo runs, cross-linked)
```

**Run modes:** `BUILD` (default — diagram above) and `PATCH` (single thin-agent loop against a failing-test spec; powers the four autofix surfaces — PR Review · TechDebt · CI failure webhook · Code Review severity buckets — and the SWE-bench / ProgramBench adapters).

**Versioning note (2026-05-07 onward):** Java code no longer carries V2/V3 markers. Backend packages were flattened from `com.navneet.executespec.v2.*` / `v3.*` to `com.navneet.executespec.*`; class suffixes / prefixes dropped. Flyway filenames + prompt-rollback archives stay versioned (intentionally — Flyway tracks applied migrations by exact filename).

---

## Key features

- **Multi-agent V2 pipeline** — Smart classifier · Outline planner (hierarchical for LARGE/LEGACY) · Sequential executor with per-task tier routing · Reviewer (general + wiring) · `WorkerAgent` resilience levers (force-edit watchdog at 0.7×maxIter, best-guess salvage on iter-limit / budget-exceeded / stagnation).
- **Patch mode + 4 autofix surfaces** — `finding/FindingPatchSubmitter` chokepoint routes PR-Review fix, TechDebt fix, CI-failure webhook (flag-gated), and Code-Review severity-bucket fix through the same patch-mode pipeline.
- **Deterministic verified score** — multiplicative: `clamp(0,100, baseAccuracy × buildOK × testRatio × reviewerMultiplier − criticalPenalty)`. No LLM self-rating in the score path.
- **Per-run codemap + 3-step project architecture** — per-repo codemap → per-repo HLD/LLD → cross-repo synthesis. Idempotent by SHA. Versioned. Lazy-refreshed before each run.
- **Project memory** — every completed run appends a digest; compaction rewrites a versioned snapshot every 10 runs. Planner reads it as `{{PROJECT_MEMORY}}`.
- **Always-on RAG** — Tika + pgvector + OpenAI embeddings. User-uploaded docs indexed; planner + chat read narrowed retrieval at run-time.
- **External project sharing** (V175 + V198) — cross-org `ProjectMember` invites + public read-only share links (6 surfaces, tier-gated). V198 (2026-05-16) adds a per-org `external_sharing_enabled` flag (default false) so each org explicitly opts in to inviting any-email-domain collaborators; gate fires before the existing tier check; only ORG_ADMIN of the inviting org or SUPER_ADMIN may flip it via `PUT /api/org/me/settings/external-sharing`.
- **PR Review + Code Review agents** (V172) — single-call PR review with line-anchored comments + whole-repo semgrep-augmented code review with markdown report.
- **Multi-repo runs** — clone each linked repo into `<workspace>/<runId>/repos/<name>/`; per-repo codemap; per-repo PR with cross-link.
- **TDD mode + spec templates + heuristic launch estimate + tech debt panel + workspace health rollup + post-merge cascade banner + cancel rollback** (closes any opened PRs idempotently with a live cleanup SSE channel).
- **Tier ladder** — FREE (5 runs/month, 1-credit specs) · PRO (HIDDEN — grandfathered, new buyers can't pick) · TEAM ($39/seat/month BYOK, min 2 seats) · ENTERPRISE (custom, unlimited).
- **Cost-proportional run credits** — `credits = max(1, ceil(estimate × 1.10 / $5.0))`. Charged at plan approval, refunded on FAILURE. Customer can lower the ceiling via `userCostCap`. BYOK orgs skip the platform cap entirely.
- **Multi-gateway billing** (Razorpay + Stripe) via `PaymentGatewayRouter`, subscriptions + run top-up credits + invoicing with GST, full payment audit + CSV export.
- **Auth** — JWT (1h access / 7d refresh) + OAuth2 (Google, GitHub). 7 roles: SUPER_ADMIN / ORG_ADMIN / BILLING_ADMIN / DEVELOPER / PROJECT_ADMIN / PROJECT_DEVELOPER / PROJECT_VIEWER. Personal org auto-provisioned on self-registration. Force-logout via feature-flag epoch.
- **8-layer security** + 3 guardrail levels (LOW / MEDIUM / HIGH) flippable via `security.guardrail_level` feature flag.
- **YAML model matrix** — single source of truth for every model identifier / context window / pricing rate / display label / fallback chain / agent→tier map. `ModelRegistry` reads `application.yml model-matrix.*`.
- **Dual-mode previews** — Cloud Run (dev/prod, 5-min TTL, managed HTTPS) or Docker Compose (local, Caddy + Cloudflare Origin Certificate).
- **GitHub integration** — create repos, push code, create branches + auto-open PRs, webhook flips PR status (HMAC SHA-256). `PullRequestSnapshot.headSha` powers CI check-run polling on PR refresh.
- **Slack + JIRA + Confluence integrations** — Slack org-level OAuth with per-project channel routing (Block Kit messages); JIRA/Confluence inbound webhooks create runs + outbound comments on `MergeCompleted`.
- **In-app notifications** — run completion, quota warning (≥80% of monthly cap, dedupe per cycle), gate-opened events.
- **Pause / resume** on V2 runs (interrupt + resume-from-disk; flag `v2.pause.enabled`, default OFF).
- **GDPR data export** — `GET /api/auth/me/data-export` returns a ZIP (manifest + payload + README) with sensitive fields stripped.
- **Cleanroom benchmarks** — SWE-bench Lite adapter + ProgramBench adapter run in a container with `--network none` bind-mounted to the worker's workspace dir.
- **GCP deployment** — single GCE VM serves prod + dev; Cloud Run for on-demand previews; Cloud SQL + Redis; Cloudflare in front of `*.executespec.ai`.

---

## Security

8-layer defense-in-depth for running untrusted user input through LLM agents:

1. **Request validation** — Jakarta Bean Validation (`@Size`, `@Pattern`, `@NotBlank`).
2. **Content scanning** — `InputSanitizer` (26 regexes + NFKC normalization + zero-width strip + base64 decode-and-scan + obfuscation detection).
3. **LLM prompt defense** — user input tagged untrusted in planner / worker prompts; tool results wrapped with `untrusted="true"` to prevent second-order injection from repo files.
4. **Code abuse scanning** — `CodeAbuseScanner` blocks writes matching malware patterns (crypto miners, reverse shells, exfil, cloud metadata, obfuscated payloads).
5. **Shell sandbox** — 3-layer (`ShellSandboxPolicy`): subshell blocking + blocklist + pattern blocklist + chained-command splitting + 32-prefix allowlist + chmod allowed inside workspace but blocked against system dirs.
6. **File sandbox** — path normalization + workspace boundary enforcement (`safeResolve()`).
7. **Process isolation** — secret env-var stripping + 5-minute hard timeout + Redis-backed per-tenant rate limiting.
8. **Container hardening** — `.dockerignore` excludes `.env*` / `credentials.json` / `secrets.*` from preview/deploy images; container console command allowlist enforced server-side; cleanroom containers run `--rm --network none`.

Configurable headers (CSP, HSTS, X-Frame-Options) via `SecurityProperties`. Three guardrail levels (LOW / MEDIUM / HIGH) tighten input sanitiser, code abuse scanner, rate-limit policy, shell sandbox, and file sandbox in unison.

---

## Documentation

| Document | Audience | Description |
|----------|----------|-------------|
| [CLAUDE.md](CLAUDE.md) | AI assistant + new engineers | Session-start instructions, architecture snapshot, conventions, API contracts, key file locations, current state |
| [CHANGELOG.md](CHANGELOG.md) | All | Release-note style — Unreleased + 1.0.0-M1 + pre-1.0 history in git |
| [docs/CODEMAP.md](docs/CODEMAP.md) | Engineers | Every package + key class → one-line purpose |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Engineers | System design, Mermaid diagrams, design decisions |
| [docs/JAVADOC.md](docs/JAVADOC.md) | Engineers | Class-level reference (historical detail; for current state see CLAUDE.md) |
| [docs/PRD.md](docs/PRD.md) | Product / Business | Product requirements, tier ladder, user journeys |
| [docs/WORKFLOWS.md](docs/WORKFLOWS.md) | Engineers / Product | Mermaid workflow diagrams |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | DevOps / Engineers | Local setup + GCP deployment + Terraform operations |
| [docs/RBAC.md](docs/RBAC.md) | Engineers | Role definitions and permission matrix |
| [docs/USER_MANUAL.md](docs/USER_MANUAL.md) | End users | How-to guide for the ExecuteSpec UI |
| [docs/openapi.yaml](docs/openapi.yaml) | Engineers | OpenAPI 3 spec |
| [docs/frontend-api-usage.md](docs/frontend-api-usage.md) | Frontend engineers | Frontend API client audit |
| [docs/user-journeys.md](docs/user-journeys.md) + [project-user-journeys.md](docs/project-user-journeys.md) | Product / QA | UI user journeys + project workbench journeys |
| [docs/plans/pending.md](docs/plans/pending.md) | Engineers | Consolidated pending plans (Mobile follow-ups · Figma v2 · Harness V2 gaps · PARTIAL-status removal · Backlog M-* · V2 batch · distributed processing · VS Code extension · Vite split · Readymades · Migrations) |
| [docs/design/ui-tokens.md](docs/design/ui-tokens.md) | Frontend | Single source of truth for tokens |
| [docs/design/code-style.md](docs/design/code-style.md) | Engineers | Documented deviations from Google Java + TypeScript style guides |
| [docs/operations/slos.md](docs/operations/slos.md) | Ops | Published SLOs + alert thresholds |
| [docs/document_map.md](docs/document_map.md) | Engineers | Which docs to update for each type of change |

---

## Database migrations

186 Flyway migrations (`V1__init.sql` → `V186__pull_request_ci_status.sql`) run automatically on startup. The per-migration index lives in [`docs/CODEMAP.md`](docs/CODEMAP.md) and the per-package `CLAUDE.md` files. Range highlights:

| Range | Theme |
|-------|-------|
| V1–V44 | Core schema, auth, billing, integrations, design system |
| V45–V60 | Stripe gateway + OAuth + force-logout + cost-proportional credits |
| V61–V90 | Batch execution + preview registry + accuracy reports + Pilot v3.0 (project workspace) |
| V91–V120 | Project memory + assessment + plan-version tooling + Validation v3 + V120 LlmProvider GEMINI rename |
| V121–V129 | v2.6.1 persona-roadmap waves (TEAM tier + activation funnel + SSO/GitHub-app schema + smart-routing scaffold + startup program) |
| V130 | Hierarchical planner phase-0 schema |
| V131–V139 | Enterprise readiness — `.executespec/` folder + activity feed + contracts + standards + ADR + invariants + notification prefs |
| V140–V146 | Tier-config alignment with website pricing + ladder consolidations + annual billing plans |
| V147–V164 | v2.7 Foundation batch (audit-chain, governance, retention, multi-org seam) |
| V165–V168 | Drop MAX tier (rename to TEAM @ $39) + FREE→5 revert + pause/resume |
| V169–V178 | PR record `project_id`/`kind`, CI auto-fix counter, R09 reconcile, PR/Code review agents, external sharing, Solo tier (introduced V176/177, retired V179/185), user cost cap |
| V179–V186 | Product-split retired, lint autofix flag, PR comment inbox, run_mode (BUILD/PATCH), triggered_by_finding, widen last_llm_failure, Solo branding retired, PR CI status columns |
| V187–V199 | Validation rubric row, file purpose summary, generated-doc fingerprint, execution_run.failure_reason, **per-org `external_sharing_enabled` flag** (V198) gating cross-org invites, **org.tier sync backfill** (V199) — aligns each `org.tier` with the highest active subscription/member tier so the Plans page no longer mismatches paid users' permission view |

---

## Model matrix

YAML is the single source of truth (`application.yml model-matrix.*`). `ModelRegistry` reads it.

| Agent tier | Anthropic | OpenAI | Gemini |
|------------|-----------|--------|--------|
| PLANNING | `claude-sonnet-4-6` | `gpt-5.4` | `gemini-3-pro-preview` |
| WORKHORSE | `claude-sonnet-4-6` | `gpt-5.4-mini` | `gemini-3-flash-preview` |
| LIGHTWEIGHT | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-3-flash-preview` |

**Default provider:** Anthropic (changed from OpenAI in 1.0.0-M1). User-selectable per project. Failover chain via YAML `failover-order:`. Reviewer routes Gemini to WORKHORSE tier so Flash's 1024-token implicit-cache threshold actually fires (Pro at 4096 was hitting 0% cache hit rate empirically).

---

## Build check

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q    # backend (spotless gate + tests)
npm --prefix frontend run build                          # frontend
npm --prefix frontend run format:check                   # prettier gate
npm --prefix frontend run lint                           # eslint gate
```

CI runs `mvn spotless:check` + `npm run format:check` + `npm run lint` on every PR (`.github/workflows/lint.yml`), and `mvn verify` + frontend build + GCE deploy + health check on push to `dev`/`main` (`.github/workflows/deploy.yml`).
