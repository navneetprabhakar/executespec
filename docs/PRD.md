# ExecuteSpec — Product Requirements Document (PRD)

> **Version**: 2.0
> **Date**: 2026-03-15
> **Status**: Living Document
> **Audience**: Product Managers, SEMs, TLs, Business Stakeholders

---

## Table of Contents

1. [Product Vision](#1-product-vision)
2. [Problem Statement](#2-problem-statement)
3. [Target Users & Personas](#3-target-users--personas)
4. [Feature Overview](#4-feature-overview)
5. [User Journeys](#5-user-journeys)
6. [System Architecture](#6-system-architecture)
7. [Data Model](#7-data-model)
8. [API Surface](#8-api-surface)
9. [Non-Functional Requirements](#9-non-functional-requirements)
10. [Monetization & Tiers](#10-monetization--tiers)
11. [Success Metrics](#11-success-metrics)
12. [Risks & Mitigations](#12-risks--mitigations)

---

## 1. Product Vision

**ExecuteSpec** is an autonomous software engineering platform that transforms natural language specifications into production-ready code. Users describe what they want to build; ExecuteSpec plans, implements, validates, reviews, and deploys — all autonomously.

**One-liner**: *"Describe it. We build it."*

### Value Proposition

| For | Who | ExecuteSpec |
|-----|-----|-------------|
| Solo developers | Want to prototype fast | Generates full-stack apps from a description |
| Engineering teams | Need to reduce boilerplate work | Handles implementation of well-defined tasks |
| Product managers | Have specs but no bandwidth | Converts PRDs into working code |
| CTOs / Tech leads | Want to evaluate AI coding | Provides governed, auditable AI coding with org controls |

---

## 2. Problem Statement

### Current State
- Developers spend 60-70% of time on boilerplate, scaffolding, and well-understood patterns
- AI coding assistants (Copilot, Cursor) require developer-in-the-loop for every decision
- No existing product handles end-to-end: plan → code → validate → review → deploy

### Desired State
- Users submit a natural language goal and receive working, reviewed, deployable code
- The system autonomously handles planning, multi-file implementation, code validation, review, and conflict resolution
- Users maintain control via approval gates (plan review, merge review) without micromanaging

---

## 3. Target Users & Personas

### Persona 1: Solo Developer ("Alex")
- **Tier**: FREE → PRO
- **Goal**: Rapid prototyping, learning, building side projects
- **Usage pattern**: 3-5 runs/week, simple single-service apps
- **Key feature**: Instant preview, GitHub push

### Persona 2: Engineering Lead ("Priya")
- **Tier**: PRO (Org Admin)
- **Goal**: Accelerate team's development velocity on defined tasks
- **Usage pattern**: 50-100 runs/month across team, complex multi-file changes
- **Key feature**: Org management, project grouping, JIRA/Confluence integration, Slack notifications

### Persona 3: Enterprise Architect ("James")
- **Tier**: ENTERPRISE
- **Goal**: Evaluate and govern AI-assisted development at scale
- **Usage pattern**: Unlimited runs, BYOK (bring your own keys), SSO integration
- **Key feature**: Audit trail, domain enforcement, custom API keys, cost control

---

## 4. Feature Overview

### 4.1 Core Pipeline

```mermaid
graph LR
    A[User Goal] --> B[Reference Extraction]
    B --> C[Planning Agent]
    C --> D{Plan Approval Gate}
    D -->|Approved| E[Task Execution]
    D -->|Rejected| Z[Run Failed]
    E --> F[Code Validation]
    F -->|Pass| G[Compiler Check]
    F -->|Fail| E
    G --> H[Review Agent]
    H --> I{Merge Gate}
    I -->|Merge| J[Git Merge]
    I -->|Rework| E
    I -->|Decline| Z
    J --> K[Post-Merge Processing]
    K --> L[Run Complete]
```

### 4.2 Feature Matrix

| Feature | FREE | PRO | ENTERPRISE |
|---------|------|-----|------------|
| Runs per month | 5 | 100 | Unlimited* |
| Concurrent runs | 1 | 3 | 10 |
| Preview deployments | 3/run | 10/run | Unlimited |
| Concurrent previews | 1 | 3 | 10 |
| Chat copilot | - | Yes | Yes |
| Apply suggestions | 2/run | 10/run | Unlimited |
| Organization | - | Yes (10 members) | Yes (unlimited) |
| Projects | - | Yes | Yes |
| JIRA/Confluence | - | Yes (admin) | Yes |
| Slack notifications | - | Yes | Yes |
| Audit trail | - | - | Yes |
| BYOK (API keys) | - | Admin only | Yes |
| GitHub integration | Yes | Yes | Yes |
| Instant preview | Yes | Yes | Yes |
| Cloud Run deployment | Yes | Yes | Yes |

*Unlimited requires BYOK (`apiKeysProvided=true`)

---

## 5. User Journeys

### Journey 1: First Run (New User Onboarding)

```mermaid
sequenceDiagram
    actor User
    participant App as ExecuteSpec
    participant Email as Email Service
    participant Agent as AI Pipeline
    participant CR as Cloud Run

    User->>App: POST /register (email, password, name)
    App->>Email: Send verification email
    Email->>User: Click verification link
    User->>App: GET /verify-email?token=xxx
    App->>User: Auto-login → Dashboard

    User->>App: Click "New Run"
    User->>App: Enter goal: "Build a React todo app with Express backend"
    User->>App: Submit run

    App->>Agent: PlannerAgent.plan()
    Agent->>App: TaskPlan (3 tasks)
    App->>User: SSE: Plan ready for review

    User->>App: Review plan → Click "Approve"
    App->>Agent: Execute tasks sequentially

    loop For each task
        Agent->>Agent: WorkerAgent writes code
        Agent->>Agent: CodeValidator validates
        Agent->>Agent: Merge to main branch
    end

    Agent->>Agent: CompilerAgent verifies build
    Agent->>Agent: ReviewerAgent reviews code
    App->>User: SSE: Review ready

    User->>App: Click "Merge"
    App->>User: Run COMPLETE

    User->>App: Click "Preview"
    App->>CR: Build → Push → Deploy
    CR->>User: Preview URL (10 min TTL)
```

### Journey 2: Team Workflow (Org User with JIRA)

```mermaid
sequenceDiagram
    actor Admin as Org Admin
    actor Dev as Developer
    participant App as ExecuteSpec
    participant JIRA as JIRA
    participant Slack as Slack

    Admin->>App: Create Organization
    Admin->>App: Configure JIRA credentials
    Admin->>App: Connect Slack workspace
    Admin->>App: Invite Developer

    Dev->>App: Accept invite → Join org

    JIRA->>App: Webhook: Issue moved to "Ready for Coding"
    App->>App: Extract issue context (summary, description, AC)
    App->>App: Auto-create run with JIRA context

    App->>Slack: "New run started from JIRA-123"

    App->>App: Plan → Execute → Validate → Review
    App->>Slack: "Run complete — 3 tasks, all passed"

    Dev->>App: View run → Review changes
    Dev->>App: Chat with copilot: "Can you add input validation?"
    Dev->>App: Click "Apply to Run"
    App->>App: Generate mini-plan → Execute → Validate

    Dev->>App: Click "Push to GitHub"
    App->>App: Create repo "swift-ember" → Push code
```

### Journey 3: Post-Completion Iteration

```mermaid
sequenceDiagram
    actor User
    participant App as ExecuteSpec
    participant Chat as Chat Copilot
    participant Pipeline as Pipeline

    User->>App: View completed run
    User->>Chat: "The login form should validate email format"
    Chat->>User: "I suggest adding email regex validation..."

    User->>App: Click "Apply to Run"
    App->>Pipeline: Generate suggestion plan (≤6 tasks)
    Pipeline->>Pipeline: Execute new tasks only
    Pipeline->>Pipeline: Compile check
    Pipeline->>Pipeline: Review new changes

    alt Preview was active
        Pipeline->>Pipeline: Auto-restart preview
    end

    App->>User: Updated run with applied changes
    User->>App: Click "Preview" → See updated app
```

### Journey 4: Billing & Top-Up

```mermaid
sequenceDiagram
    actor User
    participant App as ExecuteSpec
    participant Razorpay as Razorpay

    User->>App: Run submission fails (429: quota exceeded)
    User->>App: Navigate to Billing page
    User->>App: View "Buy Extra Runs" section
    User->>App: Select "25 Runs - $11.99"

    App->>Razorpay: Create order
    Razorpay->>User: Checkout modal
    User->>Razorpay: Complete payment
    Razorpay->>App: Webhook: order.paid

    App->>App: Credit 25 runs to user (FIFO tracking)
    App->>User: "25 runs added to your account"
    User->>App: Submit run → Success (bonus run consumed)
```

### Journey 5: Preview Lifecycle

```mermaid
stateDiagram-v2
    [*] --> IDLE : No preview active

    IDLE --> ANALYZING : User clicks "Start Preview"
    ANALYZING --> BUILDING : Dependencies detected
    BUILDING --> PUSHING : Docker image built
    PUSHING --> DEPLOYING : Image pushed to AR
    DEPLOYING --> STARTING : Cloud Run service created
    STARTING --> RUNNING : Health check passed

    RUNNING --> TIMED_OUT : 10-min TTL expires
    RUNNING --> IDLE : User clicks "Stop"
    RUNNING --> FAILED : Runtime error

    TIMED_OUT --> IDLE : Cleanup job deletes service
    FAILED --> IDLE : User retries

    note right of RUNNING
        Countdown timer visible
        Amber warning at <2 min
    end note
```

---

## 6. System Architecture

### 6.1 High-Level Architecture

```mermaid
graph TB
    subgraph "Client Layer"
        Browser[React SPA<br/>Vite 5 / Tailwind]
    end

    subgraph "Load Balancer"
        LB[HTTPS LB<br/>Cookie Affinity<br/>600s timeout]
    end

    subgraph "Compute Layer (GCE MIG)"
        subgraph "Instance 1"
            SB1[Spring Boot 3.3<br/>Java 21 / ZGC]
        end
        subgraph "Instance 2"
            SB2[Spring Boot 3.3<br/>Java 21 / ZGC]
        end
    end

    subgraph "Data Layer"
        PG[(PostgreSQL 15<br/>Cloud SQL)]
        Redis[(Redis<br/>Memorystore)]
        GCS[(GCS Buckets<br/>Workspaces + Deploy)]
    end

    subgraph "AI Providers"
        Anthropic[Anthropic API<br/>Claude 3.5/4]
        OpenAI[OpenAI API<br/>GPT-4o]
        Google[Google AI<br/>Gemini 2.5]
    end

    subgraph "External Services"
        CR[Cloud Run<br/>Preview + Deploy]
        AR[Artifact Registry<br/>Docker Images]
        GH[GitHub API<br/>Repo Management]
        RP[Razorpay<br/>Payments]
        SL[Slack API<br/>Notifications]
        JR[JIRA / Confluence<br/>Context]
    end

    Browser --> LB
    LB --> SB1
    LB --> SB2
    SB1 --> PG
    SB1 --> Redis
    SB1 --> GCS
    SB2 --> PG
    SB2 --> Redis
    SB2 --> GCS
    SB1 --> Anthropic
    SB1 --> OpenAI
    SB1 --> Google
    SB1 --> CR
    SB1 --> AR
    SB1 --> GH
    SB1 --> RP
    SB1 --> SL
    SB1 --> JR
```

### 6.2 Pipeline Architecture

```mermaid
graph TB
    subgraph "Pipeline Harness"
        Submit[Run Submission<br/>Quota Check]
        RefExtract[Reference Extractor<br/>JIRA/Confluence]
        Planner[Planner Agent<br/>Claude Sonnet]
        PlanGate{Plan Approval<br/>Redis Gate}
        Executor[Sequential Task Executor<br/>Topological Sort]
        Compiler[Compiler Agent<br/>Claude Haiku]
        Reviewer[Reviewer Agent V2<br/>Claude Haiku]
        MergeGate{Merge Gate<br/>Redis Gate}
        Merge[Git Merge Service<br/>4-tier conflict resolution]
        PostMerge[Post-Merge Orchestrator]
    end

    subgraph "Per-Task Execution"
        Worker[Worker Agent<br/>15-min time limit]
        Validator[Code Validator<br/>SYNTAX→TYPE→LINT→TEST]
        Retry{Retry?<br/>Max 3}
        Escalate[Model Escalation<br/>FAST→CAPABLE→POWER]
    end

    subgraph "Post-Merge Agents"
        Context[Context Agent<br/>Update CODEBASE_CONTEXT.md]
        PromptOpt[Prompt Optimiser<br/>Every 10 runs]
        ApiTest[API Test Suite<br/>Generate OpenAPI]
        Slack[Slack Notification<br/>Block Kit messages]
    end

    Submit --> RefExtract --> Planner --> PlanGate
    PlanGate -->|Approved| Executor
    PlanGate -->|Rejected| Failed[FAILED]

    Executor --> Worker --> Validator
    Validator -->|Pass| Merge
    Validator -->|Fail| Retry
    Retry -->|Yes| Escalate --> Worker
    Retry -->|No| Failed

    Executor --> Compiler --> Reviewer --> MergeGate
    MergeGate -->|Merge| Merge
    MergeGate -->|Rework| Executor
    MergeGate -->|Decline| Failed

    Merge --> PostMerge
    PostMerge --> Context
    PostMerge --> PromptOpt
    PostMerge --> ApiTest
    PostMerge --> Slack
```

### 6.3 LLM Provider Routing

```mermaid
graph LR
    subgraph "Fallback Chains"
        direction TB
        A1[ARCHITECT] --> A2[Claude Opus] --> A3[Gemini 2.5 Pro] --> A4[GPT-4o]
        C1[CAPABLE] --> C2[Claude Sonnet] --> C3[Gemini Flash] --> C4[GPT-4o]
        U1[UTILITY] --> U2[Claude Haiku] --> U3[Gemini Flash] --> U4[GPT-4o Mini]
    end

    subgraph "Model Router"
        direction TB
        LOW[LOW complexity] --> FAST[FAST tier]
        MED[MEDIUM complexity] --> FAST
        HIGH[HIGH complexity] --> CAPABLE[CAPABLE tier]
    end

    subgraph "Retry Escalation"
        direction TB
        R1[Attempt 1-2] --> Stay[Same tier]
        R3[Attempt 3] --> Up[Escalate tier]
    end
```

---

## 7. Data Model

### 7.1 Core Entity Relationships

```mermaid
erDiagram
    APP_USER ||--o{ PIPELINE_RUN : creates
    APP_USER ||--o| ORG : belongs_to
    ORG ||--o{ ORG_MEMBER : has
    ORG ||--o{ PROJECT : owns
    ORG ||--o{ ORG_INVITATION : sends
    PROJECT ||--o{ PROJECT_MEMBER : has
    PROJECT ||--o{ PIPELINE_RUN : groups

    PIPELINE_RUN ||--o{ AGENT_TASK : contains
    PIPELINE_RUN ||--o{ AGENT_MESSAGE : logs
    PIPELINE_RUN ||--|| PREVIEW_SESSION : previews
    AGENT_TASK ||--o{ FILE_LOCK : acquires

    APP_USER {
        string id PK
        string email UK
        string password_hash
        string global_role
        string tier
        string org_id FK
        int personal_run_count
        string github_pat
        string anthropic_api_key
    }

    ORG {
        string id PK
        string name
        string tier
        string domain_patterns
        string key_source_mode
        int monthly_run_count
        int monthly_run_limit_override
    }

    PIPELINE_RUN {
        string run_id PK
        string status
        string goal
        string created_by_user_id FK
        string org_id FK
        string project_id FK
        int preview_start_count
        int apply_suggestion_count
    }

    AGENT_TASK {
        string task_id PK
        string run_id FK
        string status
        string complexity
        string depends_on
        int attempt
    }
```

### 7.2 Billing Entity Relationships

```mermaid
erDiagram
    APP_USER ||--o| BILLING_SUBSCRIPTION : has
    BILLING_SUBSCRIPTION }|--|| BILLING_PLAN : uses
    APP_USER ||--o{ RUN_TOPUP : purchases
    ORG ||--o{ RUN_TOPUP : receives
    APP_USER ||--o{ BILLING_USAGE : tracks

    BILLING_PLAN {
        string id PK
        string name
        string tier
        string key_mode
        string period
        string razorpay_plan_id
        long base_amount_inr
        long base_amount_usd
        int run_limit
    }

    BILLING_SUBSCRIPTION {
        string id PK
        string user_id FK
        string plan_id FK
        string gateway_subscription_id
        string status
        timestamp current_period_end
        int total_seats
    }

    RUN_TOPUP {
        string id PK
        string user_id FK
        string org_id FK
        int quantity
        int remaining
        string source
        string status
        timestamp expires_at
    }

    TOPUP_PACKAGE {
        string id PK
        string name
        int run_count
        long amount_inr
        long amount_usd
        boolean active
    }
```

---

## 8. API Surface

### 8.1 API Categories

| Category | Endpoints | Auth Required |
|----------|-----------|---------------|
| Run Lifecycle | 11 | Yes (JWT) |
| Run Data | 9 | Yes |
| Preview & Deploy | 8 | Yes |
| Chat & Apply | 3 | Yes |
| Auth & Settings | 16 | Partial |
| Organization | 12 | Yes (ORG_ADMIN) |
| Projects | 7 | Yes |
| Billing | 10 | Yes |
| Integrations | 12 | Yes |
| Admin | 16 | Yes (SUPER_ADMIN) |
| **Total** | **~104** | |

### 8.2 Core Run API Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant API as REST API
    participant SSE as SSE Stream
    participant Pipeline as Pipeline

    C->>API: POST /api/runs {goal, plannerModel, ...}
    API-->>C: 202 {runId}
    C->>SSE: GET /api/runs/{id}/events (EventSource)

    Pipeline->>SSE: PLANNING event
    Pipeline->>SSE: PLAN_READY event
    C->>API: POST /api/runs/{id}/approve {approved: true}
    Pipeline->>SSE: WORKING event
    Pipeline->>SSE: TASK_STARTED / TASK_COMPLETED events
    Pipeline->>SSE: VALIDATION_PASSED events
    Pipeline->>SSE: REVIEWING event
    Pipeline->>SSE: REVIEW_READY event
    C->>API: POST /api/runs/{id}/merge {decision: "MERGE"}
    Pipeline->>SSE: COMPLETE event

    C->>API: POST /api/runs/{id}/preview/start
    API-->>C: {status: "ANALYZING", expiresAt: "..."}
    Pipeline->>SSE: PREVIEW_BUILDING / PREVIEW_DEPLOYING
    Pipeline->>SSE: PREVIEW_RUNNING {url: "*.run.app"}
```

---

## 9. Non-Functional Requirements

### 9.1 Performance

| Metric | Target | Current |
|--------|--------|---------|
| Run submission latency | < 2s | ~1s |
| Plan generation (Sonnet) | < 30s | 10-25s |
| Task execution (per task) | < 15 min | 2-12 min |
| Preview deploy time | < 5 min | 2-4 min |
| SSE event latency | < 500ms | ~200ms |
| API response (p95) | < 200ms | ~100ms |

### 9.2 Scalability

| Dimension | Current | Design Limit |
|-----------|---------|--------------|
| Concurrent runs | 10 (per instance) | 20 (2x MIG) |
| Active previews | 20 | 50 (Cloud Run) |
| Users | ~100 | ~10,000 |
| Runs/day | ~200 | ~2,000 |
| Workspace size | < 100MB | GCS archive |

### 9.3 Reliability

| Metric | Target |
|--------|--------|
| Uptime SLA | 99.5% |
| Data durability | 99.99% (Cloud SQL + GCS) |
| Recovery time (RTO) | < 15 min (MIG auto-heal) |
| Stuck run detection | < 30 min |

### 9.4 Security

- JWT (HS256) with 24h access / 7d refresh tokens
- RBAC: SUPER_ADMIN, ORG_ADMIN, BILLING_ADMIN, DEVELOPER
- Workspace sandbox: `safeResolve()` prevents path traversal
- Shell sandbox: configurable command allow/blocklist
- API keys: encrypted at rest (application.yml env vars)
- Domain enforcement: ENTERPRISE mandatory, PRO optional

---

## 10. Monetization & Tiers

### 10.1 Tier Comparison

```mermaid
graph LR
    subgraph "FREE"
        F1[5 runs/month]
        F2[1 concurrent]
        F3[No org]
        F4[3 previews/run]
    end

    subgraph "PRO ($X/month)"
        P1[100 runs/month]
        P2[3 concurrent]
        P3[Org: 10 members]
        P4[JIRA + Slack]
        P5[10 previews/run]
    end

    subgraph "ENTERPRISE (Custom)"
        E1[Unlimited runs]
        E2[10 concurrent]
        E3[Unlimited members]
        E4[Full audit trail]
        E5[BYOK required]
    end

    FREE --> PRO --> ENTERPRISE
```

### 10.2 Revenue Streams

1. **Subscriptions**: Monthly/annual plans via Razorpay
2. **Top-up credits**: One-time run pack purchases (10/25/50 runs)
3. **Seat licenses**: Per-seat pricing for PRO tier
4. **Enterprise contracts**: Custom pricing with SLAs

### 10.3 Cost Structure (Per Run)

| Component | Estimated Cost |
|-----------|---------------|
| Planner (Sonnet) | $0.05 - $0.15 |
| Workers (Haiku × 3-6) | $0.10 - $0.40 |
| Reviewer (Haiku) | $0.02 - $0.05 |
| Compiler (Haiku) | $0.01 - $0.03 |
| Preview (Cloud Run, 10 min) | $0.014 |
| **Total per run** | **$0.18 - $0.63** |

---

## 11. Success Metrics

### 11.1 North Star Metric
**Successful runs per week** — runs that reach COMPLETE status without user intervention (auto-merge enabled).

### 11.2 Key Metrics

| Metric | Definition | Target |
|--------|-----------|--------|
| Run success rate | COMPLETE / total runs | > 75% |
| First-try pass rate | COMPLETE without retries | > 50% |
| Preview adoption | Runs with preview started | > 40% |
| Apply adoption | Runs with applied suggestions | > 20% |
| Time to first preview | Submit → preview URL | < 10 min |
| User retention (7-day) | Return within 7 days | > 60% |
| Free → PRO conversion | Monthly conversion rate | > 5% |
| NPS | Net Promoter Score | > 40 |

### 11.3 Operational Metrics

| Metric | Monitor | Alert Threshold |
|--------|---------|----------------|
| Stuck runs | StuckRunMonitor (2 min cycle) | > 5 concurrent |
| LLM error rate | ProviderHealthTracker | > 10% (5 min window) |
| Preview failure rate | PreviewService | > 30% |
| Orphaned Cloud Run services | Cleanup job (30 min) | > 10 |
| API latency (p99) | Actuator metrics | > 5s |

---

## 12. Risks & Mitigations

### 12.1 Technical Risks

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| LLM API outage | All runs fail | Medium | 3-provider fallback chains |
| LLM cost spike | Margin erosion | Medium | Token budgets, cache-aware pricing |
| Prompt injection | Unexpected code | Low | Workspace sandbox, shell policy |
| Cloud Run quota | No previews | Low | Service cleanup, per-user limits |
| PostgreSQL failure | Total outage | Very Low | Cloud SQL HA, automated backups |

### 12.2 Business Risks

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Low code quality | User churn | Medium | Multi-stage validation (4 gates), model escalation |
| Competitor entry | Market share loss | High | Focus on end-to-end value (plan → deploy) |
| API key abuse | Financial loss | Low | Tier limits, rate limiting, BYOK option |
| Compliance (GDPR) | Legal exposure | Medium | Data residency controls, audit trail |

### 12.3 Known Limitations (v2.0)

1. **Single org per user** — users can only belong to one organization
2. **No real-time collaboration** — only one user can approve/review a run
3. **No IDE integration** — web-only interface (no VS Code extension yet)
4. **Preview logs are placeholder** — real Cloud Run logs not yet fetched
5. **No SSR/SSG preview** — static builds only for frontend previews
6. **English-only** — prompts and UI are English; no i18n support

---

## Appendix A: Run State Machine

```mermaid
stateDiagram-v2
    [*] --> QUEUED : Run submitted (quota OK)
    QUEUED --> PLANNING : Slot acquired

    PLANNING --> AWAITING_CLARIFICATION : Vague JIRA/Confluence ref
    AWAITING_CLARIFICATION --> PLANNING : User provides ref / skips

    PLANNING --> AWAITING_APPROVAL : Plan generated
    PLANNING --> FAILED : Planner error

    AWAITING_APPROVAL --> WORKING : User approves
    AWAITING_APPROVAL --> FAILED : User rejects

    WORKING --> REVIEWING : All tasks complete
    WORKING --> PARTIAL : Some tasks failed
    WORKING --> PAUSED : User pauses
    WORKING --> FAILED : All tasks failed

    PAUSED --> WORKING : User resumes

    REVIEWING --> AWAITING_MERGE : Review complete
    REVIEWING --> FAILED : Reviewer error

    AWAITING_MERGE --> COMPLETE : User merges
    AWAITING_MERGE --> PARTIAL : User declines
    AWAITING_MERGE --> WORKING : User requests rework
    AWAITING_MERGE --> WORKING : User implements suggestions

    PARTIAL --> WORKING : User retries task
    COMPLETE --> [*]
    FAILED --> [*]
```

## Appendix B: RBAC Permission Matrix

```mermaid
graph TB
    subgraph "Global Roles"
        SA[SUPER_ADMIN<br/>Full system access]
        U[USER<br/>Own data + org scoped]
    end

    subgraph "Org Roles"
        OA[ORG_ADMIN<br/>Settings, projects, members<br/>API keys, integrations]
        BA[BILLING_ADMIN<br/>Cost/usage, billing, tier]
        D[DEVELOPER<br/>Submit runs, view org data]
    end

    subgraph "Project Roles"
        PA[PROJECT_ADMIN<br/>Project settings, members]
        PD[PROJECT_DEVELOPER<br/>View/create runs]
    end

    SA --> OA
    OA --> PA
    U --> D
    D --> PD
```

## Appendix C: Integration Architecture

```mermaid
graph TB
    subgraph "Inbound"
        JW[JIRA Webhook]
        CW[Confluence Webhook]
        SW[ServiceNow Webhook]
        PW[PagerDuty Webhook]
        RW[Razorpay Webhook]
    end

    subgraph "Integration Manager"
        IM[IntegrationManager<br/>Route + Dedup]
        RE[ReferenceExtractor<br/>Context Enrichment]
    end

    subgraph "Outbound"
        JIRA_OUT[JIRA Status Updates]
        SLACK_OUT[Slack Block Kit Messages]
        GH_OUT[GitHub Repo Push]
    end

    JW --> IM
    CW --> IM
    SW --> IM
    PW --> IM
    RW --> IM

    IM --> RE
    RE --> Pipeline[Pipeline Harness]

    Pipeline --> JIRA_OUT
    Pipeline --> SLACK_OUT
    Pipeline --> GH_OUT
```

---

*This PRD is a living document. Last updated: 2026-03-15.*
