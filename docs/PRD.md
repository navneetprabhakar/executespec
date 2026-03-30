# ExecuteSpec - Product Requirements Document

> **Version:** 3.2
> **Last Updated:** 2026-03-30
> **Status:** Production (v1)
> **Author:** Navneet Prabhakar

---

## 1. Executive Summary

**ExecuteSpec** is an autonomous coding platform that transforms natural language requirements into production-ready code through a multi-agent AI pipeline. Users describe what they want built, and the system plans, implements, validates, reviews, and deploys the code -- with a human approval gate at the plan stage.

The platform serves individual developers, teams, and enterprises through a tiered SaaS model with integrated billing, role-based access, and deployment to Google Cloud.

### Value Proposition

| For | Value |
|-----|-------|
| **Solo developers** | Skip boilerplate -- describe a feature, get working code in minutes |
| **Teams** | Standardized code quality via automated validation and review gates |
| **Enterprises** | BYOK (Bring Your Own Keys), audit trails, org-scoped projects, GST-compliant invoicing |

---

## 2. Product Architecture

### 2.1 System Overview

```
User (Browser) --> React SPA --> Spring Boot API --> Multi-Agent Pipeline
                                       |                    |
                                       +-- PostgreSQL       +-- PlannerAgent (PLANNING tier / Opus)
                                       +-- Redis            +-- WorkerAgent (WORKHORSE tier / Sonnet)
                                       +-- GitHub           +-- CompilerAgent (WORKHORSE tier / Sonnet)
                                       +-- Razorpay         +-- ReviewerAgentV2 (PLANNING tier / Opus)
                                                            +-- ConversationAgent (LIGHTWEIGHT tier / Haiku)
                                                            +-- TestGenOrchestrator (WORKHORSE/PLANNING)
```

### 2.2 Technology Stack

| Layer | Technology |
|-------|-----------|
| **Frontend** | React 18, TypeScript 5.3, Vite 5, Tailwind CSS 3.4, Zustand, Monaco Editor |
| **Backend** | Java 21, Spring Boot 3.3, Spring AI 1.1.0 |
| **Database** | PostgreSQL (Cloud SQL), Redis (Memorystore) |
| **AI Providers** | Anthropic (Claude), Google (Gemini), OpenAI (GPT) -- 3-provider fallback |
| **Infrastructure** | GCP N. Virginia (us-east4) -- Cloud Run, GCE, Artifact Registry, GCS |
| **Payments** | Razorpay (subscriptions + one-time orders) |
| **Observability** | Micrometer + OpenTelemetry, Grafana/Tempo (local), Cloud Trace/Monitoring (prod) |

### 2.3 Multi-Agent Pipeline

```
POST /api/runs
  |
  v
Reference Extraction (JIRA/Confluence context enrichment)
  |
  v
[Optional] Clarification Gate -- user provides missing context
  |
  v
PlannerAgent (Claude Sonnet) -- natural language -> structured task plan
  |
  v
Plan Approval Gate -- user reviews & approves/rejects plan
  |
  v
SequentialTaskExecutor (tasks run one at a time)
  +-- WorkerAgent per task (Claude Haiku, escalates to Sonnet/Opus on stall)
  |     +-- Tool dispatch: readFile, writeFile, shell, git, diff
  |     +-- 15-min wall-clock limit, stall detection at 5min
  |     +-- Sliding window: first 2 + last 18 messages (context control)
  |     +-- Token truncation: readFile 8K, shell 6K, diff 5K chars
  |
  +-- CodeValidator (pluggable gate chain: SYNTAX -> TYPE -> LINT -> TEST)
       +-- Java: javac, Checkstyle
       +-- TypeScript: tsc, eslint
       +-- Python: mypy, pylint
       +-- React: eslint, component tests
  |
  v
CompilerAgent -- compile & fix build errors (up to 3 iterations)
  |
  v
Auto-Merge -- all task branches merged to main (no manual gate)
  |
  v
★ COMPLETE ★ (user sees run as done here -- before review/tests)
  |
  v
Auto-Preview (10-min TTL, both modes) -- user can interact immediately
  |
  v
GitHub Push (non-blocking)
  |
  v
Background Validation Thread (PRODUCTION mode, non-blocking):
  +-- ReviewerAgentV2 -- inspect merged code, auto-fix issues (2 attempts, max 8 issues)
  +-- TestGenOrchestrator -- generate integration tests
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

The system uses a 3-provider fallback chain with tier-based model escalation and weighted round-robin load distribution (Anthropic 4 : Google 3 : OpenAI 3):

| Chain | Primary | Fallback 1 | Fallback 2 |
|-------|---------|-----------|-----------|
| ARCHITECT | Claude Opus 4.6 | Gemini 3.1 Pro Preview | GPT-4o |
| CAPABLE | Claude Sonnet 4.6 | Gemini 3 Flash Preview | GPT-4o |
| UTILITY | Claude Haiku 4.5 | Gemini 3 Flash Preview | GPT-4o Mini |

| Agent | Default Model | Escalation Path |
|-------|--------------|-----------------|
| PlannerAgent | Sonnet | -- |
| WorkerAgent | Haiku | Haiku -> Sonnet -> Opus (on stall/retry) |
| CompilerAgent | Haiku | -- |
| ReviewerAgentV2 | Haiku | -- |
| ConversationAgent | Haiku | -- |
| ContextAgent | Haiku | -- |
| PromptOptimiserAgent | Haiku | -- |
| ApiTestSuiteAgent | Haiku | -- |

---

## 3. Tiers & Pricing

### 3.1 Tier Matrix

| Capability | FREE | PRO | ENTERPRISE |
|-----------|------|-----|------------|
| Monthly runs | 5 | 100 | Unlimited |
| Concurrent runs | 1 | 3 | 10+ |
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
1. REGISTER
   POST /api/auth/register -> email verification link
   Verify email -> account active, tier = FREE

2. FIRST RUN
   "Build a React todo app with local storage"
   POST /api/runs (goal, source=WEB)
   -> PlannerAgent generates task plan
   -> User reviews plan in UI, clicks "Approve"
   -> WorkerAgent creates files (App.tsx, TodoList.tsx, etc.)
   -> CompilerAgent validates build
   -> ReviewerAgentV2 reviews & auto-fixes issues
   -> Code auto-merged to main, workspace ready

3. PREVIEW
   Click "Preview" -> Cloud Run ephemeral container
   10-minute TTL with countdown timer
   3 preview starts per run (FREE limit)

4. DOWNLOAD
   GET /api/runs/{id}/download -> zip file of workspace

5. HIT LIMITS
   After 5 runs/month -> "Upgrade to PRO" nudge
   Cannot use chat copilot
   Cannot deploy to production
   Sees only own runs

6. UPGRADE PATH
   /plans page -> Razorpay checkout -> PRO tier activated
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
   Reviewer auto-fixes issues (CRITICAL/MINOR severity, up to 8 issues)
   Auto-fix: 2 attempts with FAST -> CAPABLE model escalation
   Click "Apply Suggestions" from chat -> apply additional fixes
   Max 10 apply suggestions per run (PRO limit)

7. DEPLOY
   POST /api/deployments/analyze -> detect requirements
   POST /api/deployments/confirm -> Cloud Run Direct deployment
   5 active deployments max

8. GITHUB INTEGRATION
   Settings page -> save GitHub PAT + owner
   Runs auto-push to configured repo on merge
   Manual: POST /api/runs/{runId}/push-github

9. SLACK NOTIFICATIONS
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
1. SUBSCRIBE
   /plans -> select PRO plan
   POST /api/billing/subscribe -> Razorpay checkout
   POST /api/billing/verify -> activate subscription
   Seat-based pricing (included seats + per-seat add-on)

2. MANAGE SUBSCRIPTION
   GET /api/billing/status -> current plan, usage, renewal date
   POST /api/billing/seats -> add/remove seats
   POST /api/billing/cancel -> cancel at period end

3. BUY RUN CREDITS
   GET /api/billing/topup/packages -> available packages
   POST /api/billing/topup/purchase -> Razorpay one-time order
   POST /api/billing/topup/verify -> credits added to org
   Credits consumed FIFO (oldest first)

4. BILLING PROFILE
   PUT /api/billing/profile -> GSTIN, PAN, address, currency
   Required for GST-compliant invoicing

5. VIEW INVOICES
   GET /api/billing/invoices -> list with tax breakdown
   GET /api/billing/invoices/{id}/pdf -> download invoice PDF
   Invoice types: SUBSCRIPTION, TOPUP, CREDIT_NOTE

6. TRACK USAGE
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

---

### 4.6 PROJECT_ADMIN Journey

**Persona:** Tech lead managing a specific project within the org.

```
1. PROJECT SETUP
   POST /api/projects (name, description, repoUrl)
   Link to GitHub repository for code context

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

6. GLOBAL AUDIT
   GET /api/admin/audit-logs -> all activity across platform
   Filter by user, org, event type, date range

7. PLATFORM MONITORING
   Grafana dashboards (local) / Cloud Monitoring (prod)
   30+ custom metrics + standard auto-instrumented
   All runs visible regardless of org/project scoping
```

**Permissions:**
- Unlimited everything (-1 for all limits)
- Full access to all runs, users, orgs
- Tier and pricing configuration
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

## 5. Feature Inventory

### 5.1 Core Pipeline

| Feature | Status | Description |
|---------|--------|-------------|
| Plan generation | Done | PlannerAgent (Sonnet) converts NL to task plan |
| Plan approval gate | Done | Redis-backed, 500ms polling, 2h TTL |
| Sequential task execution | Done | Tasks run one at a time, virtual threads, context propagation |
| Worker code generation | Done | XML tool_call format, manual dispatch |
| Code validation | Done | Pluggable gate chain (Java/TS/Python/React) |
| Model escalation | Done | HAIKU -> SONNET -> OPUS on stall/retry |
| Compiler agent | Done | Compile & fix (3 iterations) |
| Agentic reviewer | Done | INSPECT merged code -> auto-fix (2 attempts, FAST->CAPABLE escalation, max 8 issues, CRITICAL/MINOR severity) |
| Validation report | Done | LLM-generated quality summary with share & PDF export |
| Auto-merge | Done | Automatic merge of all task branches to main after compile (no manual gate) |
| Post-merge agents | Done | Context, optimizer, API test suite |
| Task retry | Done | Retry individual failed tasks |
| Run pause/resume | Done | Graceful pause with state preservation |
| Manual resume | Done | Users resume stuck runs manually via UI (auto-resume disabled) |
| Stall detection | Done | 5min no output -> model escalation |
| 15-min time limit | Done | Wall-clock limit per task |

### 5.2 Integrations

| Feature | Status | Description |
|---------|--------|-------------|
| JIRA context extraction | Done | Auto-detect keys, fetch issue details |
| Confluence extraction | Done | Fetch page content for enrichment |
| Clarification gate | Done | Pause pipeline, ask for missing context |
| GitHub push | Done | Auto-push on merge |
| Slack notifications | Done | Block Kit, per-project channels, verbosity |
| JIRA/Confluence webhooks | Done | Auto-submit runs from events |
| ServiceNow adapter | Done | Incident/change request integration |
| PagerDuty adapter | Done | Incident response integration |

### 5.3 Preview & Deploy

| Feature | Status | Description |
|---------|--------|-------------|
| Cloud Run preview | Done | 10-min ephemeral containers |
| Dockerfile auto-detect | Done | Node/React/Spring Boot/Python/Full-stack/Static HTML/Monorepo |
| Path A: Cloud Run Direct | Done | Simple containerized deployment |
| Path B: Docker Compose on VM | 0-10% | Multi-container with persistent storage |
| Path C: Terraform + Managed | 0-10% | Cloud SQL + LB + auto-scaling |
| Rollback | Done | Instant rollback to previous revision |
| Deployment credentials | Done | Per-user, per-project GCP creds |

### 5.4 Auth & Organizations

| Feature | Status | Description |
|---------|--------|-------------|
| JWT auth | Done | Access 24h, refresh 7d |
| RBAC | Done | 8 roles across 3 levels |
| Org management | Done | Create, invite, domain enforcement |
| Project scoping | Done | Member management, run filtering |
| Activity audit | Done | 35+ event types |
| API key management | Done | User + org + system fallback |

### 5.5 Billing

| Feature | Status | Description |
|---------|--------|-------------|
| Razorpay subscriptions | Done | Monthly/annual, seat-based |
| Run top-up credits | Done | Packs + admin grants, FIFO |
| GST invoicing | Done | Sequential numbering, tax breakdown, PDF |
| Double-entry accounting | Done | Journal entries per transaction |
| GSTR-1 export | Done | Compliance filing data |

### 5.6 Observability

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

---

## 6. Frontend Pages & Navigation

| Route | Page | Accessible By |
|-------|------|--------------|
| `/` | RunsPage -- run list, submit new run | All authenticated |
| `/dashboard` | DashboardPage -- analytics, model comparison | PRO+ |
| `/pricing` | PricingPage -- public pricing info | All |
| `/plans` | PlansPage -- subscription management | All |
| `/billing` | BillingPage -- usage, invoices, top-ups | BILLING_ADMIN+ |
| `/integrations` | IntegrationsPage -- JIRA, Confluence, Slack | PRO+ ORG_ADMIN |
| `/settings` | SettingsPage -- GitHub, AI keys, JIRA, Confluence | All authenticated |
| `/audit` | AuditPage -- activity audit trail | ORG_ADMIN, SUPER_ADMIN |
| `/invoices` | InvoicesPage -- invoice management | FINANCE |
| `/accounting` | AccountingPage -- ledger, GST, revenue | FINANCE |
| `/deployments` | DeploymentsPage -- deployment history | PRO+ |
| `/admin` | AdminPage -- user/org/tier management | SUPER_ADMIN |

### Key UI Components

- **NewRunModal** -- Goal input, repo selection, model override, Fast Mode toggle
- **RunDetail** -- Live task graph, agent logs, file viewer (Monaco), timeline
- **PreviewTab** -- Cloud Run preview with 10-min countdown, build logs
- **RunConversation** -- Chat copilot with apply suggestion
- **TaskGraph** -- Visual task dependency graph
- **DeployWizardModal** -- Guided deployment flow
- **CommandPalette** -- Keyboard navigation (Cmd+K)

---

## 7. Database Schema

### 7.1 Migrations Summary (V1-V45)

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

---

## 8. Infrastructure

### 8.1 GCP Resources

| Service | Usage |
|---------|-------|
| GCE | App server (n2-standard-8, systemd, us-east4) |
| Cloud SQL | PostgreSQL 15 (private IP, auto-backup) |
| Memorystore | Redis 7 (gate coordination + caching) |
| Cloud Run | Ephemeral previews (10-min TTL) |
| Artifact Registry | Docker images |
| Cloud Storage | Workspace archives |
| Cloud Trace | Distributed tracing (OTel) |
| Cloud Monitoring | Custom + standard metrics |
| Cloud Logging | Structured JSON logs |
| Secret Manager | API keys, DB passwords |
| Cloud IAP | Admin SSH access |
| Load Balancer | HTTPS termination (managed SSL) |

### 8.2 Service Account Roles

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

## 9. Observability

### 9.1 Span Hierarchy

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
  +-- executespec.run.phase.review
  +-- executespec.run.phase.report
  +-- executespec.run.phase.post_merge
```

### 9.2 Key Custom Metrics

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

### 9.3 Pre-built Dashboards

1. **ExecuteSpec Overview** -- 18 panels: runs, LLM latency/tokens/errors, tasks, handovers, gates, preview/deploy lifecycle
2. **JVM & HTTP** -- 8 panels: HTTP rate/latency, JVM heap/GC, threads, HikariCP, Redis

---

## 10. Security

### 10.1 Authentication
- JWT (RS256): access 24h, refresh 7d (httpOnly cookie)
- Email verification required
- Password policy: min 8 chars, forced change for admin

### 10.2 Authorization
- RBAC: 8 roles across global, org, project levels
- RunAccessGuard + RunVisibilitySpec: query-level filtering
- TierPermissionService: feature flags per user/tier

### 10.3 Data Protection
- API keys encrypted at rest
- GCP deployment credentials encrypted (V30)
- Secret Manager for production keys
- HTTPS enforced (managed SSL)
- Configurable CORS

---

## 11. Current State & Roadmap

### Working (Production)
- Full multi-agent pipeline with plan approval gate and automatic merge
- 3-provider LLM fallback with model escalation and 4:3:3 weighted round-robin
- Cloud Run previews with auto-detect Dockerfile generation
- JWT auth with RBAC (8 roles, 3 levels)
- Razorpay billing (subscriptions + top-up credits)
- GST-compliant invoicing with double-entry accounting
- JIRA/Confluence/Slack/GitHub integrations
- Chat copilot with apply suggestion
- Activity audit trail (35+ events)
- Full observability stack (traces, metrics, structured logs)
- Cloud Run Direct deployment (Path A)

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
- Model matrix mismatch: WORKHORSE tier uses Sonnet for all worker tasks (expensive for simple tasks)
- test-worker.txt prompt still hardcodes Java/RestAssured references despite language-agnostic test gen

---

*Document generated 2026-03-30. For implementation details, see CLAUDE.md and memory files.*
