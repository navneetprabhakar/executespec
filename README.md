# ExecuteSpec

**v2.5.11** · Multi-agent autonomous coding system. Decomposes natural-language goals into tasks, executes them sequentially with tier-routed LLM worker agents, validates, reviews, ships a PR, and remembers what was built.

**2026-05-01 audit r3 + Google-style refactor + CI lint enforcement (PRs #55-57 → main):** seven credibility-class fixes shipped — F-14 EntitlementResolver tier-rank (paid users no longer leak as FREE), F-15/16/17 WCAG-AA contrast tokens (border 3.13 / status-executing 5.22 / signal-blue-on-dark 7.29), F-21 PRO-cap render in V3 billing, D3 outcome-led empty-state copy, tier-lifecycle reason hover-only, and pa11y-ci/contrast.test scaffolding. Layered on top: codebase-wide google-java-format + prettier/eslint pass (1303 files reformatted, 0 semantic changes, 839/839 tests green) + permanent CI enforcement (`mvn verify` lint gate in `deploy.yml` + new `.github/workflows/lint.yml` PR gate). Seven documented deviations in [docs/design/code-style.md](docs/design/code-style.md). Verified live on dev — see [docs/audits/dev-verification-2026-05-04.md](docs/audits/dev-verification-2026-05-04.md).

**v2.5.11** kicks off the Validation v3 rewrite (Pillar 1 of 5) — planner now enumerates every spec requirement as a graph and every task cites which requirement IDs it covers. **v2.5.10** rebuilds the scoring layer: `verifiedScore` is derived deterministically from build + tests + reviewer verdict (no LLM hallucination); PARTIAL status now always carries an explicit `status_reason` (`CLEAN_WITH_INTERMEDIATE_WARNINGS`, `FINAL_BUILD_FAILED`, `TESTS_FAILED:N_OF_M`, etc.) surfaced via the StatusBadge tooltip + a new `SignalBreakdown` chip (`Build ✓ · Tests 73/73 · Reviewer CLEAN · Verified 92 · Est. 97`). **v2.5.9** — cross-provider eval hardening: `ExecutionPlannerService` honours `run.aiProvider`, strict NO_FALLBACK mode, `execution_solution_dependency` dedup, Gemini default → `gemini-3-flash-preview`, `RUN_PROVIDER_SWITCHED` SSE event. Premium feature: ENTERPRISE + TEAM tiers (V141 renamed MAX → TEAM as the stable 4-tier ladder; the per-seat TEAM + MAX co-existence only lives on the un-merged `feature/product-split-prep` branch). Full changelog in [CHANGELOG.md](CHANGELOG.md); Validation v3 5-pillar plan at [`.claude/plans/immutable-painting-platypus.md`](.claude/plans/immutable-painting-platypus.md).

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

Default admin: `admin@executespec.ai` / `REDACTED_FOR_PUBLIC_RELEASE` (forced password change on first login)

---

## Pipeline (V2 — current as of v2.5.11)

```
POST /api/runs  →  V2RunOrchestrator
                      ↓
                SmartClassifierAgent (LIGHTWEIGHT — heuristic fast-path
                   for ≤150-char single-feature specs; LLM verifier
                   otherwise; verdict: SINGLE_SHOT | SMALL | MEDIUM |
                   LARGE | LEGACY)
                      ↓
                Strategy router:
                   • SINGLE_SHOT → OutlinePlannerAgent.synthesizeSingleShot
                                   (1-task plan, no LLM)
                   • SMALL/MEDIUM → OutlinePlannerAgent FULL_PLAN (PLANNING
                                    tier, 32k cap, submitPlan tool)
                   • LARGE → skeleton outline → parallel TaskExpanderAgent
                             (WORKHORSE) → CoherencePlannerAgent
                             (LIGHTWEIGHT, patchPlan tool)
                   • LEGACY → LARGE pipeline + recursive depth-2 EPIC
                              expansion
                      ↓
                Plan Approval Gate (PRODUCTION pauses at AWAITING_APPROVAL;
                   PROTOTYPE/skipApproval bypasses)
                      ↓
                SequentialExecutorV2 (one task at a time, tier-routed
                   per-task — LOW=LIGHTWEIGHT, MEDIUM=WORKHORSE,
                   HIGH=PLANNING)
                   → WorkerAgentV2 (multi-turn, native tool calling via
                     LlmRouterV2; CompleteTaskTool terminates the loop)
                   → buildProject smoke test (final task only on
                     multi-task plans)
                   → ONE retry with build error injected on smoke fail
                      ↓
                Auto-merge per task — no merge gate
                      ↓
                ReviewerAgentV3 — general pass + wiring pass
                                  (project-type-aware)
                      ↓
                ★ COMPLETE / PARTIAL / FAILED
                   ↓ (background)
                AccuracyAssessmentService → ValidationReport
                                          (verifiedScore deterministic,
                                           accuracyScore as Est. demoted)
                   ↓
                TestGenOrchestrator (plan → generate → compile → execute)
                   ↓
                Auto-preview (5-min TTL) + GitHub branch + PR
                   (if existing repo configured)
```

> Pipeline diagram refreshed for [F-08] (audit Sprint 1 PR-4, 2026-04-30).
> V1 references (`RequestTriageService` as a separate service,
> `CompilerAgent`, `SubtaskDecomposer`, `BatchClientRouter`, merge gate,
> `PartialRunResumeService`, `ReviewerAgentV2`) deleted on 2026-04-13;
> V2 has folded triage into the SmartClassifier+Outline pair, smoke-tests
> per-task instead of running a separate compile pass, no merge gate,
> uses ReviewerAgentV3, and uses `StartupRunResumerV2` to mark interrupted
> runs FAILED on boot (full replay is a planned follow-up).

---

## Key Features

- **Tiered parallel task execution** with progressive merge, model escalation (WORKHORSE → PLANNING on stall), and subtask decomposition (multi-file → per-file)
- **Self-play prevention** — strips fabricated `<tool_result>` blocks from LLM responses, XML-first parsing for PATH B providers
- **Plan approval gate** — human reviews task breakdown (auto-bypass via `skipApproval`)
- **Automatic merge** — no manual merge gate; pipeline auto-merges after compile
- **PlannerAgent retry** — up to 3 attempts (5s/15s backoff) on transient LLM failure; prevents 0/0-task thread death
- **PARTIAL run auto-recovery** — `PartialRunResumeService` (@Scheduled 5-min, @SchedulerLock) retries failed/stuck tasks and resets run to WORKING
- **Manual test execution (Beta)** — "Run Tests" button in PreviewTab for any run with a running preview
- **Background validation** — three-phase review (inspect → fix → spec adherence scoring), test gen, test exec, and report run after COMPLETE
- **Spec adherence scoring** — `AccuracyAssessmentService` evaluates functional, API, persistence, UI, and security dimensions; stores grade + score on ValidationReport
- **Prompt architecture** — composable fragment system via `PromptAssembler` (`{{include:name}}` markers), scaffold templates per project type, SHA-256 prompt version tracking to `prompt_audit` table, failure pattern injection into planner
- **Code validation** — language toolchains for Java, TypeScript, Python, React (syntax → type → lint → test)
- **ReviewerAgentV3** — two-pass review (general + project-type-aware wiring) backed by `WorkerAgentV2`, 30-iter cap each. Verdict: CLEAN / FIXED_ISSUES / UNRESOLVED_ISSUES / REVIEW_FAILED. The deterministic `verifiedScore` (build + tests + reviewer outcome) is the canonical run-quality anchor; `accuracyScore` from `AccuracyAssessmentService` is rendered as a demoted "Est." chip per v2.5.10.
- **Test generation** — language-agnostic pipeline: plan → generate → compile → execute against live preview; live API probe via `ApiProber`
- **Validation report** — LLM-generated quality summary with accuracy score/grade, shareable via public URL, downloadable as PDF
- **Container console** — terminal panel in PreviewTab; executes allowlisted commands inside the running preview container via `docker compose exec -T` (no sidecar binary required)
- **Request triage** — 3-tier classification (SINGLE_SHOT / MEDIUM / MULTI_TASK) with workspace context injection, confidence scoring (< 0.7 → escalates), and full-stack safety check
- **Model routing** — 3-provider fallback (Anthropic/Google/OpenAI) with Redis-backed circuit breaker (5 failures → open, 60s recovery)
- **Auth** — JWT (1h access / 7d refresh) + OAuth2 (Google, GitHub), RBAC, orgs, projects, tiers (FREE / PRO [HIDDEN — grandfathered] / TEAM [per-seat $49] / MAX [BYOK unlimited $29] / ENTERPRISE [contract])
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
