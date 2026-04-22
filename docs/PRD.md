# ExecuteSpec - Product Requirements Document

> **Version:** 4.8
> **Last Updated:** 2026-04-22 (v2.6.0 — UX v3 Graphite+Signal + LlmProvider GEMINI rename + pricing v3 + US-first pivot + PH launch kit)
> **Status:** Production (v2.6.0 — V2 harness + pilot v3.0 project workspace + project memory + execution view + project shell + dark/light theme + Exec summary + $20=20 credits flat-band pricing)
> **Author:** Navneet Prabhakar

> **⚠️ Implementation note (2026-04-13).** Product requirements below are
> harness-agnostic and remain accurate. Internal references to V1 pipeline
> stages (compile, merge gate, clarification gate, batch execution, subtask
> decomposition) describe the previous architecture; under V2 these are
> replaced by sequential per-task execution + smoke test + reviewer.
> Operational truth lives in [`CLAUDE.md`](../CLAUDE.md).
>
> **Pilot v3.0 (commit `6bbfde9b`)** adds a project workspace layer on top of
> V2: every run can target a Project, which carries a catalogue of GitHub repos
> (ORG-shared or USER-personal), a versioned architecture document, and a
> trended quality scorecard. New product capabilities introduced under this
> release are summarised in
> [`docs/plans/pilot-v3-plan.md`](plans/pilot-v3-plan.md) (R1–R7).

---

## 1. Executive Summary

**ExecuteSpec** is an autonomous coding platform that transforms natural language requirements into production-ready code through a multi-agent AI pipeline. Users describe what they want built, and the system plans, implements, validates, reviews, and deploys the code -- with a human approval gate at the plan stage.

The platform serves individual developers, teams, and enterprises through a tiered SaaS model with integrated billing (Razorpay + Stripe), role-based access, OAuth2 login, and deployment to Google Cloud.

### Value Proposition

| For | Value |
|-----|-------|
| **Solo developers** | Skip boilerplate -- describe a feature, get working code in minutes |
| **Teams** | Standardized code quality via automated validation and review gates |
| **Enterprises** | BYOK (Bring Your Own Keys), audit trails, org-scoped projects, GST-compliant invoicing, payment audit with CSV export |

---

## 2. Product Architecture

### 2.1 System Overview

```
User (Browser) --> React SPA --> Spring Boot API --> Multi-Agent Pipeline
                                       |                    |
                                       +-- PostgreSQL       +-- PlannerAgent (PLANNING tier)
                                       +-- Redis            +-- WorkerAgent (WORKHORSE tier)
                                       +-- GitHub           +-- CompilerAgent (WORKHORSE tier)
                                       +-- Razorpay/Stripe  +-- ReviewerAgentV2 (PLANNING tier)
                                       +-- OAuth2 (Google,  +-- ConversationAgent (LIGHTWEIGHT tier)
                                           GitHub)          +-- TestGenOrchestrator (WORKHORSE/PLANNING)
                                       +-- Feature Flags    +-- RequestTriageService (LIGHTWEIGHT tier)
                                       +-- Rate Limiter
```

### 2.2 Technology Stack

| Layer | Technology |
|-------|-----------|
| **Frontend** | React 18, TypeScript 5.3, Vite 5, Tailwind CSS 3.4, Zustand, Monaco Editor |
| **Backend** | Java 21, Spring Boot 3.3, Spring AI 1.1.0 |
| **Database** | PostgreSQL (Cloud SQL), Redis (Memorystore) -- caching, gate coordination, feature flags, rate limiting |
| **AI Providers** | OpenAI (default), Anthropic (Claude), Google (Gemini) -- user-selectable per project, health-based failover |
| **Infrastructure** | GCP N. Virginia (us-east4) -- Cloud Run, GCE, Artifact Registry, GCS |
| **Payments** | Multi-gateway via PaymentGatewayRouter: Razorpay + Stripe (subscriptions + one-time orders) |
| **Auth** | JWT (access 1h, refresh 7d) + OAuth2 (Google, GitHub login), scoped SSE tokens (5-min TTL) |
| **Observability** | Micrometer + OpenTelemetry, Grafana/Tempo (local), Cloud Trace/Monitoring (prod) |

### 2.3 Multi-Agent Pipeline

```
POST /api/runs
  |
  v
Request Triage -- single-shot fast path for simple requests (skips full pipeline)
  |
  v
Reference Extraction (JIRA/Confluence context enrichment)
  |
  v
[Optional] Clarification Gate -- user provides missing context
  |
  v
PlannerAgent (PLANNING tier) -- natural language -> structured task plan
  |
  v
Plan Approval Gate -- user reviews & approves/rejects plan (Redis, 500ms polling, 2h TTL)
  |
  v
SequentialTaskExecutor (tasks run one at a time, progressive merge)
  +-- WorkerAgent per task (WORKHORSE tier, escalates to PLANNING tier on stall)
  |     +-- Tool dispatch: readFile, writeFile, shell, git, diff
  |     +-- Target 20 iterations, hard limit 35 tool calls (HARD_ITERATION_LIMIT)
  |     +-- 25-min wall-clock limit, stall detection at 5min
  |     +-- Sliding window: first 2 + last 18 messages (context control)
  |     +-- Token truncation: readFile 8K, shell 6K, diff 5K chars
  |     +-- Mid-task provider failure recovery: compact conversation + switch to fallback provider
  |     +-- Mid-task cost enforcement: checks budget every 5 LLM calls
  |     +-- Each task merged to main immediately after completion
  |
  v
CompilerAgent -- compile & fix build errors (up to 3 iterations, dynamic error truncation based on file count)
  |
  v
Auto-Merge -- all task branches merged to main (no manual gate)
  |
  v
★ COMPLETE ★ (user sees run as done here -- before review/tests)
  |
  v
Auto-Preview (5-min TTL, both modes) -- user can interact immediately
  |
  v
GitHub Push (non-blocking) -- branch `executespec/{runId-short}` + auto PR
  |
  v
Background Validation Thread (non-blocking, emits VALIDATION_FAILED SSE on thread failure):
  +-- ReviewerAgentV2 -- inspect merged code, auto-fix issues (2 attempts, max 8 issues, softened README gate: taskCount <= 5 -> MINOR)
  +-- TestGenOrchestrator -- generate language-agnostic integration tests
  +-- TestPreviewCoordinator -- reuse existing preview, execute tests
  +-- ValidationReportAgent -- LLM-generated quality summary (non-fatal)
  |     +-- Aggregates: WorkerReports, CompileResult, ReviewReport, TestResults
  |     +-- Framed as CI/CD quality gate (never mentions AI/models)
  |     +-- Shareable via public URL, downloadable as PDF
  +-- Post-Merge Agents (async):
        +-- ContextAgent, PromptOptimiserAgent, ApiTestSuiteAgent
        +-- SlackNotificationService -- Block Kit messages to configured channels
```

### 2.4 LLM Router & Model Matrix

The system uses a user-selected provider (default: OpenAI) with tier-based model routing and health-based failover via Redis-backed circuit breaker (5 failures opens circuit, 60s TTL auto-recovery):

| Agent Tier | Agents | Anthropic | Google | OpenAI |
|------------|--------|-----------|--------|--------|
| PLANNING | PlannerAgent, ReviewerAgentV2 | `claude-sonnet-4-6` | `gemini-3-flash-preview` | `gpt-5.4` |
| WORKHORSE | WorkerAgent, CompilerAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ConflictResolverAgent, PromptOptimiserAgent, ValidationReportGenerator | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |
| LIGHTWEIGHT | ConversationAgent, ContextAgent, TestReportGenerator, RequestTriageService | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |

**Failover order:** Anthropic -> [OpenAI, Google], Google -> [OpenAI, Anthropic], OpenAI -> [Google, Anthropic].
**Mid-task recovery:** `ModelResolver.resolveFallbackExcluding()` for mid-task provider failure recovery (compact conversation + switch provider).
**Token budget:** `TokenBudgetMonitor` caps output budget to model max via `ModelRegistry.maxOutputForModel()`.
**Stall escalation:** WorkerAgent promoted from WORKHORSE -> PLANNING tier on stall/retry.

### 2.5 Security Model (8-Layer Defense-in-Depth)

| Layer | Component | Description |
|-------|-----------|-------------|
| 1 | Request validation | Input size limits, content-type enforcement |
| 2 | Input sanitization | `InputSanitizer` -- XSS, injection prevention |
| 3 | LLM prompt defense | Prompt injection detection and filtering |
| 4 | Code abuse scanning | `CodeAbuseScanner` -- detects malicious code patterns |
| 5 | Shell sandbox | Restricted command execution environment |
| 6 | File sandbox | Workspace isolation, path traversal prevention |
| 7 | Process isolation | Virtual threads with context wrapping |
| 8 | Container hardening | Docker/Cloud Run security policies |

Additional security controls:
- Configurable CSP, HSTS, X-Frame-Options via `SecurityProperties`
- Per-tenant rate limiting via Redis-backed `RateLimitFilter`
- Payment webhook signature verification (Razorpay HMAC, Stripe `Stripe-Signature`)
- Force-logout via feature flag epoch-based JWT invalidation

---

## 3. Tiers & Pricing

### 3.1 Tier Matrix

> **v2.6.0 pricing v3 (locked 2026-04-21):** $1 = 1 credit, flat per complexity band (not per-provider). LOW=1, MEDIUM=1, HIGH=4, Mobile=2. Rationale: "your provider, your choice" — picking Anthropic (expensive but accurate) should not cost the user more than picking OpenAI. Anthropic HIGH runs at `-32%` gross margin; blended 3-provider HIGH margin is `+59%`. Source of truth: `website/tier-decision.md` + `website/pricing-analysis.md`.

| Capability | FREE | PRO | ENTERPRISE |
|-----------|------|-----|------------|
| Monthly runs | 5 | 100 | Unlimited |
| Max concurrent runs | 1 | 3 | 50 |
| Chat copilot | No | 10 msgs/run | Unlimited |
| Apply suggestions | 2/run | 10/run | Unlimited |
| Preview starts | 3/run | 10/run | Unlimited |
| Deployments | 0 | 5 active | Unlimited |
| Org members | -- | 10 (1 admin) | Unlimited (multi-admin) |
| AI key management | System only | SYSTEM / OWN_KEYS / HYBRID | Full BYOK |
| Integrations (JIRA, Slack) | No | Yes | Yes |
| Audit logs | No | No | Yes (ORG_ADMIN) |
| Domain enforcement | -- | Optional | Enforced |
| Run visibility | Own runs only | Org runs | All org runs |

### 3.2 Run Top-Up Credits

Users can purchase additional run credits beyond their monthly limit:

- **TopupPackage** -- Admin-configurable packages (e.g., "50 runs for INR 2,499")
- **Purchased runs** -- Never expire
- **Admin-granted bonus runs** -- Expire after 90 days (configurable)
- **Consumption** -- FIFO (oldest credits used first)
- **Enforcement** -- `TierEnforcementService` (429) + `UsageTrackingService` (402)

### 3.3 Invoicing & GST Compliance

- Sequential invoice numbering (ES-2526-0001)
- SAC code 998314 (IT software services)
- Tax breakdown: CGST + SGST (intra-state) or IGST (inter-state)
- Billing profile with GSTIN, PAN, address
- Double-entry accounting ledger
- GSTR-1 export for GST filing
- Credit notes and void support

---

## 4. Role-Based Access & User Journeys

### 4.1 Role Hierarchy

```
SUPER_ADMIN (global -- full system access)
  |
  +-- FINANCE (global -- invoicing, accounting, GST)
  |
  +-- USER (global default)
       |
       +-- ORG_ADMIN (org-level -- settings, members, keys, integrations)
       |    |
       |    +-- BILLING_ADMIN (org-level -- subscriptions, top-ups)
       |    |
       |    +-- PROJECT_ADMIN (project-level -- project settings, members)
       |
       +-- DEVELOPER (org-level default)
       |
       +-- PROJECT_DEVELOPER (project-level default)
```

---

### 4.2 FREE User Journey

**Persona:** Solo developer exploring the platform.

```
1. REGISTER (Email or OAuth2)
   Option A: POST /api/auth/register -> email verification link -> account active
   Option B: GET /api/auth/oauth2/authorize?provider=google -> Google consent ->
             GET /api/auth/oauth2/callback/google -> JWT issued, tier = FREE
   Option C: GET /api/auth/oauth2/authorize?provider=github -> GitHub consent ->
             GET /api/auth/oauth2/callback/github -> JWT issued, tier = FREE

2. FIRST RUN
   "Build a React todo app with local storage"
   POST /api/runs (goal, source=WEB)
   -> Request triage: simple enough for single-shot fast path? -> skip full pipeline
   -> Otherwise: PlannerAgent generates task plan
   -> User reviews plan in UI, clicks "Approve"
   -> WorkerAgent creates files (App.tsx, TodoList.tsx, etc.)
   -> CompilerAgent validates build
   -> ★ COMPLETE ★ (run done from user perspective)
   -> Auto-preview starts (5-min TTL)
   -> Background: ReviewerAgentV2 reviews & auto-fixes, tests generated & run

3. PREVIEW
   Click "Preview" -> Cloud Run ephemeral container
   5-minute TTL with countdown timer
   3 preview starts per run (FREE limit)

4. DOWNLOAD
   GET /api/runs/{id}/download -> zip file of workspace

5. HIT LIMITS
   After 5 runs/month -> "Upgrade to PRO" nudge
   Cannot use chat copilot
   Cannot deploy to production
   Sees only own runs

6. UPGRADE PATH
   /plans page -> Razorpay or Stripe checkout -> PRO tier activated
```

**Permissions:**
- Can create runs (5/month, 1 concurrent)
- Can preview (3 starts/run)
- Cannot chat, deploy, or create orgs
- Sees only own runs
- System AI keys only

---

### 4.3 PRO Developer Journey

**Persona:** Developer on a team, member of an organization.

```
1. ONBOARDING
   Receives org invitation email from ORG_ADMIN
   POST /api/org/accept-invite -> joins org as DEVELOPER
   Inherits org tier (PRO) and shared settings

2. CREATE PROJECT
   POST /api/projects (name, description)
   Assign team members to project
   Select AI provider per project (OpenAI default, or Anthropic/Google)

3. SUBMIT RUN WITH CONTEXT
   "Implement the user authentication flow from JIRA ticket AUTH-42"
   System detects JIRA reference -> fetches issue details
   If vague: Clarification Gate -> user provides JIRA key
   Enriched context passed to PlannerAgent

4. FAST MODE (skip plan approval)
   Toggle "Fast Mode" in NewRunModal
   -> skipApproval=true
   -> Pipeline runs end-to-end without human gates

5. CHAT COPILOT
   POST /api/runs/{runId}/chat "Can you also add password reset?"
   ConversationAgent responds with suggestions
   "Apply" button -> creates new worker tasks on same run

6. REVIEW & ITERATE
   Background validation auto-fixes issues (CRITICAL/MINOR severity, up to 8 issues)
   Auto-fix: 2 attempts with WORKHORSE -> PLANNING model escalation
   Click "Apply Suggestions" from chat -> apply additional fixes
   Max 10 apply suggestions per run (PRO limit)

7. DEPLOY
   POST /api/deployments/analyze -> detect requirements
   POST /api/deployments/confirm -> Cloud Run Direct deployment
   5 active deployments max

8. GITHUB INTEGRATION
   Settings page -> save GitHub PAT + owner + repo
   On merge: auto-creates branch `executespec/{runId-short}` + opens PR
   PR URL shown in run overview tab
   Manual: POST /api/runs/{runId}/push-github

9. VIEW GITHUB PR
   Run overview tab shows "View PR" link when `githubPrUrl` is set
   Click -> opens PR on GitHub in new tab
   PR includes all task changes on dedicated branch

10. ARCHIVE OLD RUNS
    POST /api/runs/{id}/archive -> move run to archived state
    Archived runs hidden from default list, retrievable via filter

11. SLACK NOTIFICATIONS
    Configured by ORG_ADMIN
    Receives run events in project-linked Slack channels
    Verbosity: BRIEF / NORMAL / DETAILED
```

**Permissions:**
- 100 runs/month, 3 concurrent
- Chat copilot (10 msgs/run)
- 10 apply suggestions/run, 10 preview starts/run
- 5 active deployments
- Sees all org runs
- Can manage own AI keys (if org allows HYBRID/OWN_KEYS)
- Cannot manage org settings, integrations, or billing

---

### 4.4 ORG_ADMIN Journey

**Persona:** Team lead or CTO managing the organization.

```
1. CREATE ORG
   POST /api/org (name, domain)
   Becomes ORG_ADMIN automatically

2. CONFIGURE AI KEYS
   PUT /api/org/me/settings/key-source -> SYSTEM / OWN_KEYS / HYBRID
   PUT /api/org/me/settings/ai-keys -> org-level Anthropic, OpenAI, Google keys
   Keys encrypted at rest, used as fallback after user keys

3. INVITE TEAM
   POST /api/org/invite (email, role)
   PRO: max 10 members, 1 admin
   ENTERPRISE: unlimited members, multiple admins
   Domain enforcement: OPEN (anyone) or ENFORCED (org domain only)
   Common domain blocking (gmail, hotmail) -- ENTERPRISE always on

4. MANAGE MEMBERS
   GET /api/org/me/members -> list with roles
   PUT /api/org/me/members/{userId}/role -> change to BILLING_ADMIN, DEVELOPER
   PUT /api/org/me/members/{userId}/suspend -> suspend/unsuspend
   DELETE /api/org/me/members/{userId} -> remove from org

5. CREATE PROJECTS
   POST /api/projects -> project scoping for runs
   POST /api/projects/{id}/members -> assign developers
   Runs tagged with projectId for filtering and reporting

6. CONFIGURE INTEGRATIONS
   Settings -> JIRA credentials (URL, email, API token)
   Settings -> Confluence credentials (URL, email, API token)
   Validate connection before saving
   Enable webhook triggers from JIRA/Confluence

7. CONFIGURE SLACK
   GET /api/slack/install -> OAuth flow
   POST /api/slack/projects/{projectId}/channels -> link project to channel
   PUT verbosity (BRIEF/NORMAL/DETAILED)
   Test notification before going live

8. MONITOR & AUDIT
   /audit -> Activity audit trail (35+ event types)
   Filter by user, action, date range
   Events: user_created, role_changed, org_updated, invitation_sent,
           project_created, run_submitted, settings_updated, etc.

9. VIEW METRICS & COSTS
   /dashboard -> Token usage by provider/model
   Cost breakdown per run, per user
   Model comparison charts (latency, tokens, cost)
```

**Permissions:**
- All DEVELOPER permissions plus:
- Manage org settings, members, domains
- Configure AI keys and key source mode
- Set up integrations (JIRA, Confluence, Slack)
- View audit logs, pricing, metrics, cost breakdown, token usage

---

### 4.5 BILLING_ADMIN Journey

**Persona:** Finance team member managing subscriptions and payments.

```
1. SUBSCRIBE (Multi-Gateway)
   /plans -> select PRO plan
   POST /api/billing/subscribe -> PaymentGatewayRouter selects gateway
   Option A: Razorpay checkout (INR, Indian payments)
   Option B: Stripe checkout (USD/global, card payments)
   Webhook verification: Razorpay HMAC or Stripe-Signature
   -> Subscription activated on webhook confirmation

2. STRIPE PAYMENT FLOW
   POST /api/billing/subscribe {gateway: "stripe"}
   -> Stripe Checkout Session created
   -> User redirected to Stripe hosted page
   -> POST /api/webhooks/stripe (checkout.session.completed)
   -> Subscription activated, invoice generated

3. MANAGE SUBSCRIPTION
   GET /api/billing/status -> current plan, usage, renewal date, gateway
   POST /api/billing/seats -> add/remove seats
   POST /api/billing/cancel -> cancel at period end

4. BUY RUN CREDITS
   GET /api/billing/topup/packages -> available packages
   POST /api/billing/topup/purchase -> routed to active gateway
   -> Credits added to org on payment confirmation
   Credits consumed FIFO (oldest first)

5. BILLING PROFILE
   PUT /api/billing/profile -> GSTIN, PAN, address, currency
   Required for GST-compliant invoicing

6. VIEW INVOICES
   GET /api/billing/invoices -> list with tax breakdown
   GET /api/billing/invoices/{id}/pdf -> download invoice PDF
   Invoice types: SUBSCRIPTION, TOPUP, CREDIT_NOTE

7. VIEW PAYMENT HISTORY
   GET /api/billing/payment-history -> org-scoped payment history
   Shows all transactions across both gateways

8. TRACK USAGE
   /billing -> QuotaUsageBar showing runs used / remaining
   Monthly reset on subscription renewal
   Top-up credits shown separately (never expire)
```

**Permissions:**
- All DEVELOPER permissions plus:
- Manage subscriptions (subscribe, cancel, update seats)
- Purchase run top-up credits
- Update billing profile (GSTIN, address)
- View invoices and download PDFs
- View payment history

---

### 4.6 PROJECT_ADMIN Journey

**Persona:** Tech lead managing a specific project within the org.

```
1. PROJECT SETUP
   POST /api/projects (name, description, repoUrl)
   Link to GitHub repository for code context
   Select AI provider for project (OpenAI default)

2. TEAM MANAGEMENT
   POST /api/projects/{id}/members -> add developers
   DELETE /api/projects/{id}/members/{userId} -> remove
   Members see only project-scoped runs

3. DEPLOYMENT CREDENTIALS
   Configure project-specific GCP credentials (V31)
   Separate from user-level defaults
   Used for project-scoped Cloud Run deployments

4. SLACK CHANNEL LINKING
   POST /api/slack/projects/{projectId}/channels
   Configure per-project notification channels
   Set verbosity level per channel

5. RUN MANAGEMENT
   Filter runs by project
   PATCH /api/runs/{runId}/project -> reassign run to project
   View project-specific metrics and costs
```

**Permissions:**
- All DEVELOPER permissions plus:
- Manage project members and settings
- Link Slack channels to project
- Manage project-scoped deployment credentials

---

### 4.7 SUPER_ADMIN Journey

**Persona:** Platform operator (admin@executespec.ai).

```
1. USER MANAGEMENT
   GET /api/admin/users -> all users with tier, status, org
   PUT /api/admin/users/{id}/status -> enable/disable user
   PUT /api/admin/users/{id}/tier -> change tier (FREE/PRO/ENTERPRISE)
   PUT /api/admin/users/{id}/role -> assign global role (SUPER_ADMIN/FINANCE)
   POST /api/admin/users/{id}/grant-runs -> grant bonus runs (with expiry)

2. ORG MANAGEMENT
   GET /api/admin/orgs -> all orgs with tier, member count
   PUT /api/admin/orgs/{id}/status -> enable/disable org
   PUT /api/admin/orgs/{id}/tier -> change org tier
   PUT /api/admin/orgs/{id}/run-limit -> set custom run limit
   POST /api/admin/orgs/{id}/grant-runs -> grant org bonus runs

3. TIER CONFIGURATION
   GET /api/admin/tier-config -> current tier limits
   PUT /api/admin/tier-config/{tier} -> update limits:
     monthlyRunLimit, concurrentRunLimit, maxChatMessages,
     maxApplySuggestions, maxPreviewStarts, maxDeployments, canDeploy

4. TOP-UP PACKAGE MANAGEMENT
   CRUD on /api/admin/topup-packages
   Configure name, run count, INR/USD pricing

5. MODEL PRICING
   GET /api/pricing -> pricing table (per provider/model)
   PUT /api/pricing/{provider}/{model} -> update input/output token prices

6. FEATURE FLAG ADMINISTRATION
   GET /api/feature-flags -> list all platform feature flags
   POST /api/feature-flags/{key} -> toggle feature flag on/off
   Flags cached in Redis with pub/sub invalidation + 60s scheduled reload
   Use cases: force-logout, maintenance mode, feature rollout, A/B testing
   Changes propagate to all app instances via Redis pub/sub

7. PAYMENT AUDIT
   GET /api/admin/payment-audit -> paginated audit log of all payments
   Filter by gateway, status, date range, org
   GET /api/admin/payment-audit/export -> download full audit as CSV
   GET /api/admin/webhook-events -> webhook event log (filterable)
   Full trail: payment initiated -> webhook received -> subscription activated

8. GLOBAL AUDIT
   GET /api/admin/audit-logs -> all activity across platform
   Filter by user, org, event type, date range

9. PLATFORM MONITORING
   Grafana dashboards (local) / Cloud Monitoring (prod)
   30+ custom metrics + standard auto-instrumented
   All runs visible regardless of org/project scoping
```

**Permissions:**
- Unlimited everything (-1 for all limits)
- Full access to all runs, users, orgs
- Tier and pricing configuration
- Feature flag management
- Payment audit with CSV export
- Global audit log access

---

### 4.8 FINANCE Role Journey

**Persona:** Accountant managing invoicing and GST compliance.

```
1. INVOICE MANAGEMENT
   GET /api/billing/invoices (admin) -> all invoices across users
   POST /api/admin/invoices/{id}/send -> email invoice to user
   POST /api/admin/invoices/{id}/void -> void invoice
   POST /api/admin/invoices/{id}/credit-note -> issue credit note

2. SELLER CONFIGURATION
   GET /api/admin/seller-config -> ExecuteSpec's business details
   PUT /api/admin/seller-config -> update GSTIN, PAN, address

3. REVENUE REPORTING
   GET /api/accounting/revenue -> revenue summary by period
   Breakdown: subscriptions, top-ups, credit notes
   Breakdown by gateway: Razorpay vs Stripe

4. GST COMPLIANCE
   GET /api/accounting/gst-summary -> CGST/SGST/IGST totals
   GET /api/accounting/gstr1-export -> GSTR-1 filing data

5. ACCOUNTING LEDGER
   /accounting page -> double-entry journal entries
   Entry types: REVENUE, GST_OUTPUT, ACCOUNTS_RECEIVABLE, BANK, REFUND
   Each invoice generates corresponding journal entries
```

**Permissions:**
- View all invoices, send/void/credit-note
- Update seller configuration (company GST details)
- Access revenue reports and GST summaries
- Export GSTR-1 data for compliance

---

### 4.9 OAuth2 Login Journey

**Persona:** Any user logging in via Google or GitHub.

```
1. INITIATE OAUTH2
   User clicks "Sign in with Google" or "Sign in with GitHub"
   GET /api/auth/oauth2/authorize?provider=google (or github)
   -> Redirect to provider consent screen

2. PROVIDER CONSENT
   User grants permissions on Google/GitHub
   Provider redirects to callback URL with authorization code

3. CALLBACK & TOKEN EXCHANGE
   GET /api/auth/oauth2/callback/{provider}?code=xxx
   -> Backend exchanges code for provider access token
   -> Fetches user profile (email, name, avatar)
   -> If email matches existing account: link OAuth identity, issue JWT
   -> If new user: create account (tier=FREE), issue JWT

4. SESSION ESTABLISHED
   OAuthSuccessHandler issues JWT (access 1h, refresh 7d)
   Frontend stores access token, refresh in httpOnly cookie
   User lands on dashboard, fully authenticated

5. SUBSEQUENT LOGINS
   "Sign in with Google/GitHub" -> skip consent (already granted)
   -> Instant JWT issuance, no password needed
```

---

### 4.10 GitHub PR Viewing Journey

**Persona:** Developer reviewing generated code on GitHub.

```
1. CONFIGURE GITHUB
   Settings page -> save GitHub PAT + owner + repo URL
   Validates credentials on save

2. RUN COMPLETES
   Pipeline reaches COMPLETE
   PipelineHarness.pushToGitHubIfConfigured() fires:
   -> Creates branch: executespec/{runId-short}
   -> Pushes all generated code to branch
   -> Opens PR via GitHubRepoService.createPullRequest()
   -> PR URL stored in PipelineRun.githubPrUrl

3. VIEW PR IN UI
   Run overview tab shows "View PR on GitHub" link
   githubPrUrl returned in RunDetail API response
   Click -> opens GitHub PR in new tab

4. REVIEW ON GITHUB
   PR shows all file changes from the run
   Team can review, comment, request changes via standard GitHub flow
   PR targets default branch (looked up via GitHubRepoService.getDefaultBranch())
```

---

### 4.11 Run Archive Journey

**Persona:** Developer cleaning up completed runs.

```
1. ARCHIVE A RUN
   From run list, select completed run
   POST /api/runs/{id}/archive
   Run moved to archived state

2. VIEW ARCHIVED RUNS
   Default run list hides archived runs
   Toggle filter to show archived runs
   Archived runs are read-only (no new actions)

3. WORKSPACE PRESERVED
   Archived runs retain workspace, preview history, validation report
   Can still download zip, view report, access shared report links
```

---

## 5. Feature Inventory

### 5.1 Core Pipeline

| Feature | Status | Description |
|---------|--------|-------------|
| Request triage | Done | Single-shot fast path for simple requests via `RequestTriageService` |
| Plan generation | Done | PlannerAgent (PLANNING tier) converts NL to task plan with `project_type` |
| Plan approval gate | Done | Redis-backed, 500ms polling, 2h TTL |
| Sequential task execution | Done | Tasks run one at a time, virtual threads, context propagation, progressive merge |
| Worker code generation | Done | XML tool_call format, manual dispatch |
| Code validation | Done | Pluggable gate chain (Java/TS/Python/React) -- deferred to review phase |
| Model escalation | Done | WORKHORSE -> PLANNING tier on stall/retry |
| Mid-task provider recovery | Done | WorkerAgent compacts conversation and switches to fallback provider via `ModelResolver.resolveFallbackExcluding()` |
| Token budget control | Done | `TokenBudgetMonitor` caps output budget to model max via `ModelRegistry.maxOutputForModel()` |
| Compiler agent | Done | Compile & fix (3 iterations, dynamic error truncation based on file count) |
| Background validation | Done | Review, test gen, test exec, report run in background after COMPLETE; emits VALIDATION_FAILED SSE on thread failure |
| Agentic reviewer | Done | INSPECT merged code -> auto-fix (2 attempts, WORKHORSE->PLANNING escalation, max 8 issues, CRITICAL/MINOR severity, softened README gate: taskCount <= 5 -> MINOR) |
| Language-agnostic test gen | Done | TestGenOrchestrator generates tests matching project language/framework |
| Validation report | Done | LLM-generated quality summary with share & PDF export |
| Auto-merge | Done | Automatic merge of all task branches to main after compile (no manual gate) |
| Post-merge agents | Done | Context, optimizer, API test suite |
| Task retry | Done | Retry individual failed tasks |
| Run pause/resume | Done | Graceful pause with state preservation |
| Manual resume | Done | Users resume stuck runs manually via UI |
| PARTIAL run auto-recovery | Done | `PartialRunResumeService` (`@Scheduled` every 5 min, `@SchedulerLock`) auto-resumes stale PARTIAL runs by retrying up to 3 failed/stuck tasks per sweep; guarded by `isRunActive` check |
| Batch execution | Done | OpenAI Batch API + Anthropic Batch API (50% cost discount). User selects real-time or batch at plan approval. `BatchPollService` polls every 30s; `BatchResumeService` parses results and publishes `BatchCompleteEvent`; failed batch tasks auto-retried in real-time |
| Planner retry | Done | `PlannerAgent.plan()` retried up to 3 attempts (5 s / 15 s backoff) on transient LLM failure before marking run FAILED — eliminates "0/0 tasks" thread-death failures |
| Spec adherence assessment | Done | Phase 3 of review pipeline: `AccuracyAssessmentService` scores generated code against original spec (0-100, A-F grade, endpoint coverage, schema accuracy, test coverage, missing requirements). Score persisted to `validation_report` via V74 migration |
| Run archive | Done | Archive completed runs, hide from default list |
| Stall detection | Done | 5min no output -> model escalation |
| 25-min time limit | Done | Wall-clock limit per task, target 20 iterations, hard limit 35 tool calls |
| Mid-task cost enforcement | Done | Checks budget every 5 LLM calls in worker loop |
| Design system integration | Done | 54 company design systems (DESIGN.md files) loaded from filesystem, 3-signal resolution (explicit hint > planner > heuristic), Caffeine cache, token-budget truncation |

### 5.2 Integrations

| Feature | Status | Description |
|---------|--------|-------------|
| JIRA context extraction | Done | Auto-detect keys, fetch issue details |
| Confluence extraction | Done | Fetch page content for enrichment |
| Clarification gate | Done | Pause pipeline, ask for missing context |
| GitHub push + PR | Done | Auto-creates branch `executespec/{runId-short}` + opens PR |
| Slack notifications | Done | Block Kit, per-project channels, verbosity |
| JIRA/Confluence webhooks | Done | Auto-submit runs from events, outbound comments on MergeCompleted |
| ServiceNow adapter | Done | Incident/change request integration |
| PagerDuty adapter | Done | Incident response integration |

### 5.3 Preview & Deploy

| Feature | Status | Description |
|---------|--------|-------------|
| Cloud Run preview | Done | 30-min ephemeral containers (auto-start on COMPLETE; TTL increased from 5 min to outlive background validation) |
| Docker Compose preview | Done | 30-min TTL, Caddy proxy with Cloudflare Origin Certificate |
| Container console | Done | Docker mode only: run allowlisted diagnostic commands inside preview container via `POST /api/runs/{runId}/preview/exec` (ls, ps, cat, curl, env, df, free, netstat, ss, ping, whoami, hostname, date, uptime, pwd). Server-side allowlist + shell metacharacter rejection. Frontend `ConsolePanel.tsx` in `PreviewTab` |
| Dockerfile auto-detect | Done | Node/React/Spring Boot/Python/Full-stack/Static HTML/Monorepo (fixed shell escape bug in full-stack Dockerfile generation) |
| Project type from planner | Done | Planner declares `project_type` -> `.agent-context/project.json` |
| Path A: Cloud Run Direct | Done | Simple containerized deployment |
| Path B: Docker Compose on VM | 0-10% | Multi-container with persistent storage |
| Path C: Terraform + Managed | 0-10% | Cloud SQL + LB + auto-scaling |
| Rollback | Done | Instant rollback to previous revision |
| Deployment credentials | Done | Per-user, per-project GCP creds |

### 5.4 Auth & Organizations

| Feature | Status | Description |
|---------|--------|-------------|
| JWT auth | Done | Access 1h, refresh 7d (RS256), scoped SSE tokens (5-min TTL) |
| OAuth2 login | Done | Google and GitHub providers via `OAuthSuccessHandler` |
| RBAC | Done | 8 roles across 3 levels |
| Force-logout | Done | Feature flag epoch-based JWT invalidation |
| Org management | Done | Create, invite, domain enforcement |
| Project scoping | Done | Member management, run filtering |
| Activity audit | Done | 35+ event types |
| API key management | Done | User + org + system fallback, encrypted at rest |

### 5.5 Billing & Payments

| Feature | Status | Description |
|---------|--------|-------------|
| Multi-gateway billing | Done | PaymentGatewayRouter: Razorpay + Stripe |
| Razorpay subscriptions | Done | Monthly/annual, seat-based (INR) |
| Stripe subscriptions | Done | Monthly/annual, seat-based (USD/global) |
| Run top-up credits | Done | Packs + admin grants, FIFO |
| GST invoicing | Done | Sequential numbering, tax breakdown, PDF |
| Double-entry accounting | Done | Journal entries per transaction |
| GSTR-1 export | Done | Compliance filing data |
| Payment audit trail | Done | Full audit log with CSV export (`PaymentAuditService`) |
| Webhook event log | Done | Filterable log of all payment webhooks |
| Payment history | Done | Org-scoped transaction history across gateways |

### 5.6 Platform Controls

| Feature | Status | Description |
|---------|--------|-------------|
| Feature flags | Done | DB-backed, Redis pub/sub cache invalidation, 60s reload, admin UI |
| Rate limiting | Done | Redis-backed per-tenant via `RateLimitFilter` |
| 8-layer security | Done | Request validation through container hardening |
| Configurable security headers | Done | CSP, HSTS, X-Frame-Options via `SecurityProperties` |

### 5.7 Observability

| Feature | Status | Description |
|---------|--------|-------------|
| Distributed tracing | Done | Micrometer + OTel spans across phases |
| Custom metrics | Done | 30+ meters (runs, LLM, tasks, preview, deploy) |
| Standard metrics | Done | HTTP, JVM, HikariCP, Redis, Spring AI |
| Structured logging | Done | JSON with traceId/spanId |
| Local stack | Done | Grafana + Prometheus + Tempo |
| GCP export | Done | Cloud Trace + Cloud Monitoring |
| Pre-built dashboards | Done | ExecuteSpec Overview + JVM/HTTP |
| Browser tracing | Done | OTel Web SDK |
| Model evaluation | Done | `eval/` package with EvalController + ModelEvaluator |

---

## 6. Frontend Pages & Navigation

| Route | Page | Accessible By |
|-------|------|--------------|
| `/` | RunsPage -- run list, submit new run, archive toggle | All authenticated |
| `/dashboard` | DashboardPage -- analytics, model comparison | PRO+ |
| `/pricing` | PricingPage -- public pricing info | All |
| `/plans` | PlansPage -- subscription management (Razorpay/Stripe) | All |
| `/billing` | BillingPage -- usage, invoices, top-ups, payment history | BILLING_ADMIN+ |
| `/integrations` | IntegrationsPage -- JIRA, Confluence, Slack | PRO+ ORG_ADMIN |
| `/settings` | SettingsPage -- GitHub, AI keys, JIRA, Confluence, OAuth2 linked accounts | All authenticated |
| `/audit` | AuditPage -- activity audit trail | ORG_ADMIN, SUPER_ADMIN |
| `/invoices` | InvoicesPage -- invoice management | FINANCE |
| `/accounting` | AccountingPage -- ledger, GST, revenue | FINANCE |
| `/deployments` | DeploymentsPage -- deployment history | PRO+ |
| `/admin` | AdminPage -- user/org/tier management, feature flags, payment audit | SUPER_ADMIN |

### Key UI Components

- **NewRunModal** -- Goal input, repo selection, model override, Fast Mode toggle, AI provider selection
- **RunDetail** -- Live task graph, agent logs, file viewer (Monaco), timeline, GitHub PR link
- **PreviewTab** -- Cloud Run preview with 5-min countdown, build logs
- **RunConversation** -- Chat copilot with apply suggestion
- **TaskGraph** -- Visual task dependency graph (grouped by source: copilot/review)
- **ValidationReportPanel** -- Quality report with share link and PDF download
- **DeployWizardModal** -- Guided deployment flow
- **CommandPalette** -- Keyboard navigation (Cmd+K)

---

## 7. API Contracts

### 7.1 Run Lifecycle

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/runs` | Submit run (202) |
| GET | `/api/runs` | List runs (paginated, filtered by visibility + tier) |
| GET | `/api/runs/{id}` | Run detail (includes `githubPrUrl`) |
| POST | `/api/runs/{id}/approve` | Plan gate approval |
| POST | `/api/runs/{id}/merge` | Merge gate (legacy -- merge is now automatic) |
| DELETE | `/api/runs/{id}` | Cancel/delete run |
| POST | `/api/runs/{id}/archive` | Archive run |
| POST | `/api/runs/{id}/tasks/{taskId}/retry` | Retry failed task |
| GET | `/api/runs/{id}/events` | SSE event stream (scoped token) |
| GET | `/api/runs/{id}/sse-token` | Get scoped SSE token (5-min TTL) |

### 7.2 Validation Report

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/runs/{id}/report` | Get validation report |
| GET | `/api/runs/{id}/report/pdf` | Download report as PDF |
| POST | `/api/runs/{id}/report/share` | Generate share link |
| GET | `/api/reports/shared/{token}` | Public shared report (no auth) |

### 7.3 Preview & Deploy

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/runs/{id}/preview/start` | Start preview (Cloud Run or Docker) |
| DELETE | `/api/runs/{id}/preview/stop` | Stop preview |
| POST | `/api/runs/{id}/deploy` | Deploy to Cloud Run (permanent) |
| POST | `/api/runs/{id}/push-github` | Push to GitHub (creates branch + PR) |

### 7.4 Auth & Org

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/register` | Self-register |
| POST | `/api/auth/login` | Login -> JWT |
| POST | `/api/auth/refresh` | Refresh JWT token |
| DELETE | `/api/auth/logout` | Logout (clears refresh cookie) |
| POST | `/api/auth/forgot-password` | Request password reset email |
| POST | `/api/auth/reset-password` | Reset password with token |
| GET | `/api/auth/verify-email` | Verify email link |
| GET | `/api/auth/me/permissions` | Tier-based permissions |
| GET | `/api/auth/oauth2/authorize` | OAuth2 authorization redirect (Google/GitHub) |
| GET | `/api/auth/oauth2/callback/{provider}` | OAuth2 callback handler |
| POST | `/api/org` | Create org |
| POST | `/api/org/invite` | Invite user |

### 7.5 Billing & Payments

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/billing/plans` | List billing plans |
| POST | `/api/billing/subscribe` | Initiate subscription (routed to Razorpay or Stripe) |
| POST | `/api/billing/topup/purchase` | Buy run top-up credits |
| GET | `/api/billing/payment-history` | Org-scoped payment history |
| POST | `/api/webhooks/razorpay` | Razorpay webhook (HMAC verified, no auth) |
| POST | `/api/webhooks/stripe` | Stripe webhook (Stripe-Signature verified, no auth) |

### 7.6 Models & Feature Flags

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/models/available` | Model matrix + provider tags |
| GET | `/api/feature-flags` | List all feature flags (SUPER_ADMIN) |
| POST | `/api/feature-flags/{key}` | Update feature flag (SUPER_ADMIN) |

### 7.7 Admin

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/payment-audit` | Payment audit log (paginated, SUPER_ADMIN) |
| GET | `/api/admin/payment-audit/export` | Export audit as CSV (SUPER_ADMIN) |
| GET | `/api/admin/webhook-events` | Webhook event log (filterable, SUPER_ADMIN) |

---

## 8. Database Schema

### 8.1 Migrations Summary (V1-V75)

| Range | Scope |
|-------|-------|
| V1-V3 | Core schema, tenant_id, cost/cache |
| V4-V7 | Auth, admin seed, tiers, password fix |
| V8-V9 | GitHub, conversations, iteration |
| V10-V14 | AI keys, GCS, merge conflicts, key source |
| V17-V20 | Auto-merge, org integrations, token breakdown, Slack |
| V21 | Tier permissions (max members, domain enforcement) |
| V22 | Activity audit (35+ event types) |
| V23-V26 | Billing core, usage alerts, free tier backfill, run top-up |
| V27 | Apply suggestion limits |
| V28 | Invoicing & GST accounting (6 new tables) |
| V29 | Preview limits |
| V30-V31 | Deploy engine, project-scoped credentials |
| V34 | Validation report |
| V35-V37 | Schema updates |
| V38-V44 | Report improvements, shared reports, validation enhancements |
| V45 | Widen plan_version VARCHAR(36) columns to VARCHAR(255)/VARCHAR(64) |
| V46 | Stripe and payment gateway support |
| V47 | OAuth support (GitHub, Google login) |
| V48 | View mode column |
| V49 | Feature flags |
| V50 | Free tier restrictions |
| V51 | Force logout flag |
| V52 | GitHub PR URL on pipeline_run |
| V53-V55 | Rate limiting, payment audit, security enhancements |
| V56 | Task completion notes |

---

## 9. Infrastructure

### 9.1 GCP Resources

| Service | Usage |
|---------|-------|
| GCE | App server (n2-standard-8, systemd, us-east4) |
| Cloud SQL | PostgreSQL 15 (private IP, auto-backup) |
| Memorystore | Redis 7 (gate coordination, caching, feature flags, rate limiting) |
| Cloud Run | Ephemeral previews (5-min TTL) |
| Artifact Registry | Docker images |
| Cloud Storage | Workspace archives |
| Cloud Trace | Distributed tracing (OTel) |
| Cloud Monitoring | Custom + standard metrics |
| Cloud Logging | Structured JSON logs |
| Secret Manager | API keys, DB passwords, OAuth secrets |
| Cloud IAP | Admin SSH access |
| Load Balancer | HTTPS termination (managed SSL) |

### 9.2 Service Account Roles

| Role | Purpose |
|------|---------|
| `roles/cloudsql.client` | Cloud SQL access |
| `roles/secretmanager.secretAccessor` | Read secrets |
| `roles/logging.logWriter` | Structured logs |
| `roles/monitoring.metricWriter` | Custom metrics |
| `roles/cloudtrace.agent` | Trace spans |
| `roles/artifactregistry.writer` | Docker push |
| `roles/compute.instanceAdmin.v1` | Instance management |
| `roles/iap.tunnelResourceAccessor` | SSH via IAP |
| `roles/storage.objectAdmin` | GCS read/write |

---

## 10. Observability

### 10.1 Span Hierarchy

```
executespec.run (root)
  +-- executespec.run.phase.planning
  +-- executespec.run.phase.approval_gate
  +-- executespec.run.phase.working
  |     +-- executespec.tier
  |     +-- executespec.task
  |     |     +-- executespec.task.iteration
  |     |     +-- executespec.tool.dispatch
  |     |     +-- executespec.llm.call
  +-- executespec.run.phase.compile
  +-- executespec.run.phase.merge
  +-- executespec.run.phase.review (background)
  +-- executespec.run.phase.test_gen (background)
  +-- executespec.run.phase.report (background)
  +-- executespec.run.phase.post_merge
```

### 10.2 Key Custom Metrics

| Metric | Type | Tags |
|--------|------|------|
| `executespec.run.total` | Counter | status |
| `executespec.run.duration` | Timer | status |
| `executespec.llm.calls` | Counter | provider, model |
| `executespec.llm.duration` | Timer | provider, model |
| `executespec.llm.tokens.input/output` | Counter | provider, model |
| `executespec.llm.errors` | Counter | provider, error_type |
| `executespec.handover.duration` | Timer | from_phase, to_phase |
| `executespec.preview.*` | Counter/Timer | -- |
| `executespec.deploy.*` | Counter/Timer | -- |

### 10.3 Pre-built Dashboards

1. **ExecuteSpec Overview** -- 18 panels: runs, LLM latency/tokens/errors, tasks, handovers, gates, preview/deploy lifecycle
2. **JVM & HTTP** -- 8 panels: HTTP rate/latency, JVM heap/GC, threads, HikariCP, Redis

---

## 11. Security

### 11.1 Authentication
- JWT (RS256): access 24h, refresh 7d (httpOnly cookie)
- OAuth2: Google and GitHub login via `OAuthSuccessHandler`
- Email verification required (email/password registration)
- Password policy: min 8 chars, forced change for admin
- Force-logout: feature flag epoch-based JWT invalidation

### 11.2 Authorization
- RBAC: 8 roles across global, org, project levels
- RunAccessGuard + RunVisibilitySpec: query-level filtering
- TierPermissionService: feature flags per user/tier
- Feature flags: DB-backed with Redis pub/sub cache invalidation

### 11.3 Data Protection
- API keys encrypted at rest
- GCP deployment credentials encrypted (V30)
- Secret Manager for production keys (including OAuth secrets)
- HTTPS enforced (managed SSL)
- Configurable CORS
- Configurable CSP, HSTS, X-Frame-Options via `SecurityProperties`

### 11.4 Input & Runtime Security
- `InputSanitizer`: XSS and injection prevention
- `CodeAbuseScanner`: malicious code pattern detection
- Shell sandbox: restricted command execution
- File sandbox: workspace isolation, path traversal prevention
- Per-tenant rate limiting via Redis-backed `RateLimitFilter`

### 11.5 Payment Security
- Razorpay webhook HMAC verification
- Stripe webhook `Stripe-Signature` verification
- Full payment audit trail with CSV export

---

## 12. Current State & Roadmap

### Working (Production)
- Full multi-agent pipeline with plan approval gate, automatic merge, and background validation
- Request triage: single-shot fast path for simple requests
- User-selected AI provider (default: OpenAI) with YAML model matrix and health-based failover
- 25-min wall-clock worker time limit with stall detection and model escalation
- Cloud Run previews with auto-detect Dockerfile generation (5-min TTL)
- Dual-mode previews: Cloud Run (dev/prod) or Docker Compose (local, Caddy proxy)
- JWT auth + OAuth2 (Google, GitHub) with RBAC (8 roles, 3 levels)
- Multi-gateway billing via PaymentGatewayRouter (Razorpay + Stripe)
- GST-compliant invoicing with double-entry accounting
- Payment audit trail with CSV export
- Feature flag system (DB + Redis pub/sub + admin UI)
- Redis-backed per-tenant rate limiting
- 8-layer security model
- JIRA/Confluence/Slack/GitHub integrations
- GitHub branch + PR creation for existing repos
- Chat copilot with apply suggestion
- Run archive feature
- Language-agnostic test generation
- Activity audit trail (35+ events)
- Full observability stack (traces, metrics, structured logs)
- Cloud Run Direct deployment (Path A)
- Model evaluation framework

### In Progress
| Feature | Progress |
|---------|----------|
| Deploy Engine Path B (Docker Compose on VM) | 0-10% |
| Deploy Engine Path C (Terraform + Managed Services) | 0-10% |

### Known Tech Debt
- V3 migration type mismatch (`actual_cost_usd` DOUBLE PRECISION)
- Branch retry RefAlreadyExistsException fallback
- Startup auto-resume disabled (users resume stuck runs manually via UI)
- `doReviewAndMerge()` not updated for background validation pattern (runs synchronous in resume/retry paths)
- Background validation thread has no retry on failure (single attempt)
- `@Lazy @Autowired` field injection on PreviewService in TestPreviewCoordinator (circular dependency workaround)
- test-worker.txt prompt still hardcodes Java/RestAssured references despite language-agnostic test gen
- Model matrix cost: WORKHORSE tier for Anthropic uses Haiku -- consider Sonnet for complex tasks

---

*Document generated 2026-04-03. For implementation details, see CLAUDE.md and memory files.*
