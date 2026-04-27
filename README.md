# ExecuteSpec

**v2.5.11** · Multi-agent autonomous coding system. Decomposes natural-language goals into tasks, executes them sequentially with tier-routed LLM worker agents, validates, reviews, ships a PR, and remembers what was built. **v2.5.11** kicks off the Validation v3 rewrite (Pillar 1 of 5) — planner now enumerates every spec requirement as a graph and every task cites which requirement IDs it covers. **v2.5.10** rebuilds the scoring layer: `verifiedScore` is derived deterministically from build + tests + reviewer verdict (no LLM hallucination); PARTIAL status now always carries an explicit `status_reason` (`CLEAN_WITH_INTERMEDIATE_WARNINGS`, `FINAL_BUILD_FAILED`, `TESTS_FAILED:N_OF_M`, etc.) surfaced via the StatusBadge tooltip + a new `SignalBreakdown` chip (`Build ✓ · Tests 73/73 · Reviewer CLEAN · Verified 92 · Est. 97`). **v2.5.9** — cross-provider eval hardening: `ExecutionPlannerService` honours `run.aiProvider`, strict NO_FALLBACK mode, `execution_solution_dependency` dedup, Gemini default → `gemini-3-flash-preview`, `RUN_PROVIDER_SWITCHED` SSE event. Premium feature: ENTERPRISE + MAX tiers. Full changelog in [CHANGELOG.md](CHANGELOG.md); Validation v3 5-pillar plan at [`.claude/plans/immutable-painting-platypus.md`](.claude/plans/immutable-painting-platypus.md).

**Stack:** Java 21 · Spring Boot 3.3 · Spring AI 1.1.0 · PostgreSQL · Redis · React 18 · Vite 5 · Tailwind · GCP

---

## Quick Start

```bash
# Prerequisites: Java 21, PostgreSQL (localhost:5432/executespec), Redis, Node.js 18+

export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export OPENAI_API_KEY=sk-...        # Default provider
export ANTHROPIC_API_KEY=sk-ant-... # Fallback

# Option 1: Supervisor (recommended)
./supervisor.sh start

# Option 2: Manual
npm --prefix frontend install && npm --prefix frontend run build
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -DskipTests
```

Open **http://localhost:8080**

Default admin: `admin@executespec.ai` / `Admin@123` (forced password change on first login)

---

## Pipeline

```
POST /api/runs  →  RequestTriageService (SINGLE_SHOT / MEDIUM / MULTI_TASK)
                      ↓ (if MEDIUM or MULTI_TASK)
                ReferenceExtractor (JIRA/Confluence context)
                      ↓
                Clarification Gate  (if ambiguous references detected)
                      ↓
                PlannerAgent (PLANNING tier, retry up to 3 attempts on transient failure)
                   → outputs project_type → .agent-context/project.json
                      ↓
                Plan Approval Gate  (or auto-bypass with skipApproval)
                      ↓
                SequentialTaskExecutor (tiered parallel groups)
                   → SubtaskDecomposer (splits multi-file tasks into per-file subtasks)
                   → WorkerAgent per task (WORKHORSE tier, 25-min limit, self-play prevention)
                   → Retry with model escalation on stall (WORKHORSE → PLANNING)
                   → Progressive merge to main after each task
                   (Batch mode: BatchClientRouter → BatchPollService → BatchResumeService
                    → auto-retry failed batch tasks in real-time → compile)
                      ↓
                CompilerAgent (full project build, 3 rounds)
                      ↓
                Auto-merge all task branches
                      ↓
                ★ COMPLETE  →  Auto-preview (30-min TTL, skipped for batch runs)
                      ↓ (background virtual thread)
                ReviewerAgentV2 — Phase 1: inspect + auto-fix (CRITICAL/MINOR issues)
                                — Phase 2: targeted re-reads + fix verification
                                — Phase 3: AccuracyAssessmentService (spec adherence scoring)
                      ↓
                TestGenOrchestrator (plan → generate → compile → execute)
                   → ApiProber (live endpoint probe before test gen)
                   → ApiSurfaceExtractor (route extraction from workspace)
                      ↓
                ValidationReport + TestReport
                      ↓
                MergeCompleted event → JIRA/Confluence/Slack outbound
                      ↓
                GitHub branch + PR (if existing repo configured)

PARTIAL recovery: PartialRunResumeService scans every 5 min, retries failed/stuck tasks,
                  resets PARTIAL → WORKING (cap: 3 tasks per run per invocation)
```

---

## Key Features

- **Tiered parallel task execution** with progressive merge, model escalation (WORKHORSE → PLANNING on stall), and subtask decomposition (multi-file → per-file)
- **Self-play prevention** — strips fabricated `<tool_result>` blocks from LLM responses, XML-first parsing for PATH B providers
- **Plan approval gate** — human reviews task breakdown (auto-bypass via `skipApproval`)
- **Automatic merge** — no manual merge gate; pipeline auto-merges after compile
- **PlannerAgent retry** — up to 3 attempts (5s/15s backoff) on transient LLM failure; prevents 0/0-task thread death
- **PARTIAL run auto-recovery** — `PartialRunResumeService` (@Scheduled 5-min, @SchedulerLock) retries failed/stuck tasks and resets run to WORKING
- **Batch execution** — dual-option plan approval (real-time vs batch with 50% discount), all 3 LLM providers, auto-retry failed batch tasks in real-time, batch model/provider attribution
- **Manual test execution (Beta)** — "Run Tests" button in PreviewTab for batch runs (preview must be running)
- **Background validation** — three-phase review (inspect → fix → spec adherence scoring), test gen, test exec, and report run after COMPLETE
- **Spec adherence scoring** — `AccuracyAssessmentService` evaluates functional, API, persistence, UI, and security dimensions; stores grade + score on ValidationReport
- **Prompt architecture** — composable fragment system via `PromptAssembler` (`{{include:name}}` markers), scaffold templates per project type, SHA-256 prompt version tracking to `prompt_audit` table, failure pattern injection into planner
- **Code validation** — language toolchains for Java, TypeScript, Python, React (syntax → type → lint → test)
- **ReviewerAgentV2** — two-phase inspection + auto-fix (CRITICAL/MINOR issues, 2 attempts per issue, max 8), plus Phase 3 spec adherence
- **Test generation** — language-agnostic pipeline: plan → generate → compile → execute against live preview; live API probe via `ApiProber`
- **Validation report** — LLM-generated quality summary with accuracy score/grade, shareable via public URL, downloadable as PDF
- **Container console** — terminal panel in PreviewTab; executes allowlisted commands inside the running preview container via `docker compose exec -T` (no sidecar binary required)
- **Request triage** — 3-tier classification (SINGLE_SHOT / MEDIUM / MULTI_TASK) with workspace context injection, confidence scoring (< 0.7 → escalates), and full-stack safety check
- **Model routing** — 3-provider fallback (Anthropic/Google/OpenAI) with Redis-backed circuit breaker (5 failures → open, 60s recovery)
- **Auth** — JWT (1h access / 7d refresh) + OAuth2 (Google, GitHub), RBAC, orgs, projects, tiers (FREE/PRO/ENTERPRISE)
- **Billing** — Multi-gateway (Razorpay + Stripe) via `PaymentGatewayRouter`, subscriptions + run top-up credits + invoicing with GST
- **Payment audit** — Full audit trail with CSV export, webhook event log (admin)
- **Feature flags** — DB-backed with Redis pub/sub cache invalidation, admin-manageable
- **Rate limiting** — Redis-backed per-tenant sliding window
- **JIRA/Confluence integration** — inbound webhooks create runs, outbound comments on MergeCompleted
- **Slack notifications** — org-level OAuth, per-project channel routing, Block Kit messages
- **Dual-mode previews** — Cloud Run (dev/prod, 30-min TTL, managed HTTPS) or Docker Compose (local, 30-min TTL, Caddy)
- **GitHub integration** — create repos, push code, create branches + auto-open PRs for existing repos
- **Post-run AI chat** — conversation panel with workspace context injection
- **Run archive** — archive completed runs
- **Design system integration** — 54 company design systems (Stripe, Linear, Notion, etc.) loaded from filesystem-backed DESIGN.md files with 3-signal priority resolution (explicit hint > planner > heuristic), Caffeine caching, and token-budget truncation
- **GCP deployment** — GCE MIG + Cloud SQL + Redis + GCS + Load Balancer + Cloud Run

---

## Security

8-layer defense-in-depth model for running untrusted user input through LLM agents:

1. **Request validation** — Jakarta Bean Validation (`@Size`, `@Pattern`, `@NotBlank`) rejects malformed input
2. **Content scanning** — `InputSanitizer` detects prompt injection, shell embedding, abuse patterns (26 regexes + Unicode NFKC normalization + obfuscation detection)
3. **LLM prompt defense** — user input tagged as untrusted in planner/worker prompts; tool results wrapped with `untrusted="true"` to prevent second-order injection from repo files
4. **Code abuse scanning** — `CodeAbuseScanner` **blocks** writes of files matching malware patterns (crypto miners, reverse shells, data exfiltration, obfuscated payloads)
5. **Shell sandbox** — 3-layer validation: subshell blocking + blocklist (23) + pattern blocklist (43) + chained-command splitting + allowlist (32 prefixes)
6. **File sandbox** — path normalization + workspace boundary enforcement (`safeResolve()`)
7. **Process isolation** — secret env var stripping + 5-minute hard timeout + rate limiting (Redis per-tenant)
8. **Container hardening** — `.dockerignore` excludes `.env*`, `credentials.json`, `secrets.*` from preview/deploy images; container console command allowlist enforced server-side

Configurable security headers (CSP, HSTS, X-Frame-Options) via `SecurityProperties`.

---

## Documentation

| Document | Audience | Description |
|----------|----------|-------------|
| [CLAUDE.md](CLAUDE.md) | AI assistant | Session instructions, conventions, patterns |
| [docs/CODEMAP.md](docs/CODEMAP.md) | Engineers | Every package + key class → one-line purpose |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Engineers | System design, Mermaid diagrams, design decisions |
| [docs/JAVADOC.md](docs/JAVADOC.md) | Engineers | Detailed code documentation per package and class |
| [docs/PRD.md](docs/PRD.md) | Product/Business | Product requirements, tiers, user journeys |
| [docs/WORKFLOWS.md](docs/WORKFLOWS.md) | Engineers/Product | Mermaid workflow diagrams for every feature |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | DevOps/Engineers | Local setup + GCP deployment + Terraform operations |
| [docs/RBAC.md](docs/RBAC.md) | Engineers | Role definitions and permission matrix |
| [docs/USER_MANUAL.md](docs/USER_MANUAL.md) | End users | How-to guide for the ExecuteSpec UI |
| [docs/frontend-api-usage.md](docs/frontend-api-usage.md) | Frontend Engineers | Frontend API client audit |
| [docs/document_map.md](docs/document_map.md) | Engineers | Which docs to update for each type of change |

---

## Database Migrations

150+ Flyway migrations (V1–V150 on `feature/v2.7-foundation`) run
automatically on startup. The full per-migration index lives in
[`docs/CODEMAP.md`](docs/CODEMAP.md) → §Database Migrations and
the per-package `CLAUDE.md` files. Highlights:

| Range | Theme |
|-------|-------|
| V1–V44 | Core schema, auth, billing, integrations, design system |
| V45–V60 | Stripe gateway + OAuth + force-logout + cost-proportional credits |
| V61–V90 | Batch execution + preview registry + accuracy reports + Pilot v3.0 (project workspace) |
| V91–V120 | Project memory + assessment + plan-version tooling + Validation v3 + V120 LlmProvider GEMINI rename |
| V121–V129 | v2.6.1 persona-roadmap waves (TEAM tier + activation funnel + SSO/GitHub-app schema + smart-routing scaffold + startup program) |
| V130 | Hierarchical planner phase-0 schema (`feature/hierarchical-planner`) |
| V131–V139 | Enterprise readiness (`feature/enterprise-readiness-eval`) — 4 phases (folder + activity + contracts + standards + ADR + invariants + notification prefs) |
| V140–V146 | Tier-config alignment with website pricing + V141 ladder consolidation + V142 dead-flag drops + V143 product-split discriminator + V144 per-seat TEAM reinstatement + V145 FREE 5→10 + V146 annual billing plans |
| V147–V150 | v2.7 Foundation batch — V147 audit-chain (M-019), V148 governance caps (M-032/033/039), V149 TEAM-BYOK plan (M-051), V150 retention flag seed (M-038) |

---

## Model Matrix

> Provider id is `gemini` post-V120 (was `google` until v2.5.13).

| Agent Tier | Agents | Anthropic | Gemini | OpenAI |
|------------|--------|-----------|--------|--------|
| PLANNING | PlannerAgentV2, OutlinePlannerAgent, ReviewerAgentV3, AccuracyAssessmentService | `claude-sonnet-4-6` | `gemini-3-flash-preview` | `gpt-5.4` |
| WORKHORSE | WorkerAgentV2 (MEDIUM), TaskExpanderAgent, TestPlannerAgent, TestWorkerAgent + 4 more | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |
| LIGHTWEIGHT | WorkerAgentV2 (LOW), SmartClassifierAgent, CoherencePlannerAgent, ConversationAgent, ContextAgent | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |

Default provider: **OpenAI** (user-selected per project). Health-based failover via Redis circuit breaker.

---

## Build Check

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q    # Java
npm --prefix frontend run build                           # Frontend
```
