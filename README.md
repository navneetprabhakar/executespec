# ExecuteSpec

**v1.0.0-M1** · Multi-agent autonomous coding system. Decomposes natural-language goals into tasks, executes them sequentially with tier-routed LLM worker agents, validates, reviews, ships a PR, and remembers what was built.

**v1.0.0-M1 (2026-05-02 — first public milestone):** consolidates every shipping feature from the v2.5.x development line into the first numbered release. Highlights — V2 multi-agent pipeline (Smart classifier → outline planner → sequential executor → reviewer → final smoke + auto-preview); validation v3 pillar 1 (planner emits a requirement graph, every task cites which requirement IDs it implements); deterministic verifiedScore (derived from build + tests + reviewer verdict, not LLM self-rating); 4-tier ladder (Free 5 runs/month, Pro $20/mo grandfathered, Team $39/seat BYOK, Enterprise); audit r3 fix batch (EntitlementResolver tier-rank, WCAG-AA token contrast, V3 billing PRO cap render, outcome-led empty-state copy); Google Java + TypeScript style refactor with CI lint enforcement; multi-repo project runs now correctly route worker writes into per-repo `repos/<name>/` subdirs; pause/resume on V2 runs (interrupt + resume-from-disk semantic, behind the `v2.pause.enabled` flag). Full inventory in [CHANGELOG.md](CHANGELOG.md); deviations from Google style guides in [docs/design/code-style.md](docs/design/code-style.md); live dev verification in [docs/audits/dev-verification-2026-05-04.md](docs/audits/dev-verification-2026-05-04.md).

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

## Pipeline (current as of v1.0.0-M1)

> **Versioning note (2026-05-07 onward):** Java code no longer carries V2/V3 markers. Backend packages were flattened from `com.navneet.executespec.v2.*` and `com.navneet.executespec.v3.*` to `com.navneet.executespec.*`; class suffixes/prefixes (`LlmRouter`, `ReviewerAgent`, `LandingController`, …) dropped. Flyway migration filenames and prompt-rollback archives stay versioned (intentionally — see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full rationale).



```
POST /api/runs  →  RunOrchestrator
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
                SequentialExecutor (one task at a time, tier-routed
                   per-task — LOW=LIGHTWEIGHT, MEDIUM=WORKHORSE,
                   HIGH=PLANNING)
                   → WorkerAgent (multi-turn, native tool calling via
                     LlmRouter; CompleteTaskTool terminates the loop)
                   → buildProject smoke test (final task only on
                     multi-task plans)
                   → ONE retry with build error injected on smoke fail
                      ↓
                Auto-merge per task — no merge gate
                      ↓
                ReviewerAgent — general pass + wiring pass
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
> `PartialRunResumeService`, `ReviewerAgent`) deleted on 2026-04-13;
> V2 has folded triage into the SmartClassifier+Outline pair, smoke-tests
> per-task instead of running a separate compile pass, no merge gate,
> uses ReviewerAgent, and uses `StartupRunResumerV2` to mark interrupted
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
- **ReviewerAgent** — two-pass review (general + project-type-aware wiring) backed by `WorkerAgent`, 30-iter cap each. Verdict: CLEAN / FIXED_ISSUES / UNRESOLVED_ISSUES / REVIEW_FAILED. The deterministic `verifiedScore` (build + tests + reviewer outcome) is the canonical run-quality anchor; `accuracyScore` from `AccuracyAssessmentService` is rendered as a demoted "Est." chip per v2.5.10.
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
| PLANNING | PlannerAgentV2, OutlinePlannerAgent, ReviewerAgent, AccuracyAssessmentService | `claude-sonnet-4-6` | `gemini-3-flash-preview` | `gpt-5.4` |
| WORKHORSE | WorkerAgent (MEDIUM), TaskExpanderAgent, TestPlannerAgent, TestWorkerAgent + 4 more | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |
| LIGHTWEIGHT | WorkerAgent (LOW), SmartClassifierAgent, CoherencePlannerAgent, ConversationAgent, ContextAgent | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |

Default provider: **OpenAI** (user-selected per project). Health-based failover via Redis circuit breaker.

---

## Build Check

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q    # Java
npm --prefix frontend run build                           # Frontend
```
