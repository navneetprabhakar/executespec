# ExecuteSpec — Architecture Document

> **Version**: 2.2
> **Date**: 2026-03-30
> **Audience**: Technical Leads, Staff Engineers, Platform Team

---

## 1. System Context

```mermaid
C4Context
    title System Context Diagram — ExecuteSpec

    Person(user, "User", "Developer / PM / Tech Lead")
    Person(admin, "Super Admin", "Platform operator")

    System(es, "ExecuteSpec", "Autonomous coding platform")

    System_Ext(anthropic, "Anthropic API", "Claude models")
    System_Ext(openai, "OpenAI API", "GPT models")
    System_Ext(google, "Google AI", "Gemini models")
    System_Ext(github, "GitHub", "Source code repos")
    System_Ext(jira, "JIRA", "Issue tracking")
    System_Ext(confluence, "Confluence", "Documentation")
    System_Ext(slack, "Slack", "Team notifications")
    System_Ext(razorpay, "Razorpay", "Payment processing")
    System_Ext(gcp, "GCP", "Cloud Run, AR, GCS, SQL")

    Rel(user, es, "Submit runs, review, preview, deploy")
    Rel(admin, es, "Manage users, orgs, billing")
    Rel(es, anthropic, "LLM calls (primary)")
    Rel(es, openai, "LLM calls (fallback)")
    Rel(es, google, "LLM calls (fallback)")
    Rel(es, github, "Push code, clone repos")
    Rel(es, jira, "Fetch context, update status")
    Rel(es, confluence, "Fetch page content")
    Rel(es, slack, "Push notifications")
    Rel(es, razorpay, "Process payments")
    Rel(es, gcp, "Preview/deploy, store artifacts")
```

---

## 2. Infrastructure Architecture

```mermaid
graph TB
    subgraph "Internet"
        Client[Browser / API Client]
    end

    subgraph "GCP — us-central1"
        subgraph "Network Layer"
            DNS[Cloud DNS<br/>dev.executespec.ai]
            LB[HTTPS Load Balancer<br/>SSL termination<br/>GENERATED_COOKIE affinity<br/>600s backend timeout]
        end

        subgraph "Compute — MIG (Managed Instance Group)"
            subgraph "VM 1 — n2-standard-8"
                JVM1[JVM<br/>Java 21 / G1GC<br/>-Xms8g -Xmx16g<br/>Spring Boot 3.3]
                WS1[Workspace<br/>/workspace/*]
            end
            subgraph "VM 2 — n2-standard-8"
                JVM2[JVM<br/>Java 21 / G1GC<br/>-Xms8g -Xmx16g<br/>Spring Boot 3.3]
                WS2[Workspace<br/>/workspace/*]
            end
        end

        subgraph "Data Layer"
            SQL[(Cloud SQL<br/>PostgreSQL 15<br/>Private IP<br/>Automated backups)]
            REDIS[(Memorystore<br/>Redis<br/>Pool: max-active 50<br/>Gates, sessions)]
        end

        subgraph "Storage"
            GCS_WS[(GCS<br/>executespec-workspaces<br/>Archived workspaces)]
            GCS_DEP[(GCS<br/>executespec-deploy<br/>JAR artifacts)]
            AR[(Artifact Registry<br/>Docker images<br/>Preview + deploy)]
        end

        subgraph "Serverless"
            CR1[Cloud Run<br/>Preview services<br/>es-preview-*]
            CR2[Cloud Run<br/>Deployed services<br/>es-deploy-*]
        end
    end

    Client --> DNS --> LB
    LB --> JVM1
    LB --> JVM2
    JVM1 --> SQL
    JVM2 --> SQL
    JVM1 --> REDIS
    JVM2 --> REDIS
    JVM1 --> GCS_WS
    JVM1 --> GCS_DEP
    JVM1 --> AR
    JVM1 --> CR1
    JVM1 --> CR2
```

---

## 3. Application Architecture

### 3.1 Package Dependency Map

```mermaid
graph TB
    subgraph "API Layer"
        RC[RunController]
        PC[PreviewController]
        DC[DeploymentController]
        AC[AuthController]
        BC[BillingController]
        SC[SseController]
    end

    subgraph "Service Layer"
        PH[PipelineHarness]
        PS[PreviewService]
        DS[DeploymentService]
        AS[AuthService]
        BS[BillingService]
        TE[TierEnforcementService]
        TP[TierPermissionService]
    end

    subgraph "Agent Layer"
        PA[PlannerAgent]
        WA[WorkerAgent]
        RA[ReviewerAgent/V2]
        CA[CompilerAgent]
        ConvA[ConversationAgent]
        CtxA[ContextAgent]
        TGO[TestGenOrchestrator]
        TWA[TestWorkerAgent]
        TPC[TestPreviewCoordinator]
        TRG[TestReportGenerator]
    end

    subgraph "Infrastructure Layer"
        SS[StateStore]
        LR[LlmRouter]
        MR[ModelRouter]
        GS[GateStore]
        SB[SseBroadcaster]
        GMS[GitMergeService]
        STE[SequentialTaskExecutor]
    end

    subgraph "Tool Layer"
        FST[FileSystemTool]
        GT[GitTool]
        ST[ShellTool]
        FLT[FileLockTool]
    end

    subgraph "External Clients"
        CRC[CloudRunClient]
        ARC[ArtifactRegistryClient]
        GHR[GitHubRepoService]
        RPC[RazorpayGateway]
    end

    RC --> PH
    RC --> SS
    PC --> PS
    DC --> DS
    AC --> AS
    BC --> BS
    SC --> SB

    PH --> PA & WA & RA & CA & TGO
    PH --> STE & GMS & GS
    PH --> SS & SB
    TGO --> TWA & TPC & TRG

    PS --> CRC & ARC
    DS --> CRC & ARC

    PA & WA & RA & CA & TGO & TWA & TRG --> LR
    LR --> MR

    WA --> FST & GT & ST & FLT

    TE --> SS
    BS --> RPC
```

### 3.2 Agent Architecture

**Pipeline flow:** plan → approve → work (sequential tasks) → compile → merge → **COMPLETE** → auto-preview (10-min TTL) → background validation thread: review → test gen → preview reuse → test exec → validation report

**Project type detection:** PlannerAgent outputs `project_type` in the plan JSON. This is written to `.agent-context/project.json` in the workspace. `DevDockerfileGenerator` and `DockerfileGenerator` read this value before falling back to file-based heuristics, preventing misdetection (e.g., static HTML being classified as NODE).

**Model Matrix:**

| Agent Tier | Agents | Anthropic | Google | OpenAI |
|------------|--------|-----------|--------|--------|
| PLANNING | PlannerAgent, ReviewerAgentV2, TestPlannerAgent | `claude-opus-4-6` | `gemini-3.1-pro-preview` | `gpt-5.4` |
| WORKHORSE | WorkerAgent, CompilerAgent, ApiTestSuiteAgent, ConflictResolverAgent, TestWorkerAgent | `claude-sonnet-4-6` | `gemini-3-flash-preview` | `gpt-5.4` |
| LIGHTWEIGHT | ConversationAgent, ContextAgent, PromptOptimiserAgent, TestReportGenerator, ValidationReportGenerator | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4-mini` |

```mermaid
graph TB
    subgraph "Agent Execution Model"
        direction TB

        subgraph "PlannerAgent"
            P_IN[User Goal + Context] --> P_LLM[LLM Call<br/>PLANNING tier]
            P_LLM --> P_OUT[TaskPlan<br/>1-35 tasks, sequential<br/>complexity per task<br/>+ project_type detection]
            P_OUT --> P_CTX[.agent-context/project.json<br/>project_type written here<br/>Read by DockerfileGenerator<br/>before file-based heuristics]
        end

        subgraph "WorkerAgent (per task, sequential)"
            W_PROMPT[Task + Context<br/>+ Workspace State] --> W_LOOP

            subgraph "W_LOOP [15-min Time Loop]"
                W_LLM[LLM Call<br/>WORKHORSE tier] --> W_PARSE[Parse XML<br/>tool_call tags]
                W_PARSE --> W_DISPATCH[Dispatch Tool<br/>readFile/writeFile/<br/>editFile/run/etc.]
                W_DISPATCH --> W_RESULT[Tool Result<br/>Truncated output]
                W_RESULT --> W_CHECK{Completion?<br/>Starts with '{'}
                W_CHECK -->|No| W_LLM
                W_CHECK -->|Yes| W_DONE[WorkerReport]
            end

            W_STALL{Stall Detection<br/>5 min no writes} -->|Yes| W_ESCALATE[Model Escalation]
            W_ESCALATE --> W_LLM
        end

        subgraph "ReviewerAgentV2 (two-phase)"
            R_DIFFS[Merged code on main] --> R_INSPECT[Phase 1: Inspect<br/>Read-only tool-use analysis]
            R_INSPECT --> R_ISSUES[Issues List<br/>CRITICAL/MINOR]
            R_ISSUES --> R_FIX[Phase 2: Auto-Fix<br/>FAST → CAPABLE escalation<br/>2 attempts per issue, max 8]
            R_FIX --> R_REPORT[ReviewReportV2<br/>APPROVED/NEEDS_FIXES/REJECTED]
        end

        subgraph "Test Generation Pipeline (after review)"
            TG_ORCH[TestGenOrchestrator<br/>Orchestrates full test pipeline]
            TG_PLAN[TestPlannerAgent<br/>PLANNING tier] --> TG_WORKER[TestWorkerAgent<br/>WORKHORSE tier<br/>Generates test files]
            TG_WORKER --> TG_COMPILE{Tests compile?}
            TG_COMPILE -->|Yes| TG_PREVIEW[TestPreviewCoordinator<br/>Starts preview AFTER tests compile<br/>Ensures full TTL for execution]
            TG_COMPILE -->|No| TG_RETRY[Retry compilation]
            TG_PREVIEW --> TG_EXEC[Test Execution<br/>Against live preview]
            TG_EXEC --> TG_REPORT[TestReportGenerator<br/>LIGHTWEIGHT tier]
        end

        subgraph "ValidationReportAgent (non-fatal)"
            VR_INPUT[WorkerReports + CompileResult<br/>+ ReviewReport + TestResults] --> VR_LLM[LLM Call<br/>LIGHTWEIGHT tier]
            VR_LLM --> VR_OUT[Markdown Report<br/>+ Structured Issues<br/>+ PDF Download]
            VR_OUT --> VR_DB[(validation_report<br/>table)]
        end
    end
```

### 3.3 Code Validation Pipeline

```mermaid
graph LR
    subgraph "CodeValidator (per task)"
        SYNTAX[SYNTAX Gate<br/>Compile check] --> TYPE[TYPE_CHECK Gate<br/>mvn compile / tsc]
        TYPE --> LINT[LINT Gate<br/>eslint / ruff]
        LINT --> TEST[UNIT_TEST Gate<br/>vitest / pytest / mvn test]
    end

    subgraph "Language Toolchains"
        JAVA[Java<br/>mvn compile -q -B<br/>mvn test -B]
        TS[TypeScript<br/>npx tsc --noEmit<br/>npx eslint]
        REACT[React<br/>extends TS<br/>Hook violation = FAIL]
        PY[Python<br/>py_compile<br/>mypy / ruff / pytest]
    end

    subgraph "On Failure"
        PARSE[OutputParser<br/>Extract errors] --> RETRY_PROMPT[RetryPromptBuilder<br/>Structured error list<br/>3000 char cap]
        RETRY_PROMPT --> WORKER[Back to WorkerAgent<br/>+ model escalation]
    end

    TEST -->|PASS| MERGE[Merge to main]
    TEST -->|FAIL| PARSE
    LINT -->|FAIL| PARSE
    TYPE -->|FAIL| PARSE
    SYNTAX -->|FAIL| PARSE
```

---

## 4. Data Architecture

### 4.1 Database Schema Overview

```mermaid
graph TB
    subgraph "Auth Domain (12 tables)"
        AU[app_user]
        ORG[org]
        OM[org_member]
        OI[org_invitation]
        PROJ[project]
        PM[project_member]
        TC[tier_config]
        ET[email_token]
        RTB[refresh_token_blocklist]
        AA[activity_audit]
    end

    subgraph "Pipeline Domain (8 tables)"
        PR[pipeline_run]
        AT[agent_task]
        AM[agent_message]
        FL[file_lock]
        GE[gate_event]
        CE[compaction_event]
        RR[run_recovery]
        MC[merge_conflict]
    end

    subgraph "Billing Domain (6 tables)"
        BP[billing_plan]
        BS[billing_subscription]
        BU[billing_usage]
        TP[topup_package]
        RT[run_topup]
        BA[billing_usage_alert]
    end

    subgraph "Integration Domain (5 tables)"
        IR[integration_run]
        IEL[integration_event_log]
        OSI[org_slack_integration]
        PSC[project_slack_channel]
        SNL[slack_notification_log]
    end

    subgraph "Analytics Domain (2 tables)"
        MP[model_pricing]
        MTM[model_task_metric]
    end

    AU --> ORG
    AU --> PR
    ORG --> OM
    ORG --> PROJ
    PR --> AT
    PR --> AM
    AU --> BS
    BS --> BP
```

### 4.2 Migration History (V1 — V45)

| Version | Description | Tables Affected |
|---------|-------------|----------------|
| V1-V3 | Core schema, tenant, cost | pipeline_run, agent_task, agent_message |
| V4-V7 | Auth, admin seed, tiers | app_user, tier_config |
| V8-V9 | GitHub, conversations | app_user, agent_message |
| V10-V14 | AI keys, GCS, merge, key source | app_user, org, merge_conflict |
| V15-V16 | Model metrics, prompt audit | model_task_metric, prompt_audit_entry |
| V17-V20 | Auto-merge, integrations, Slack | org, org_slack_integration |
| V21 | Tier permissions | tier_config, org |
| V22 | Activity audit | activity_audit |
| V23-V25 | Billing | billing_plan, billing_subscription, billing_usage |
| V26 | Run top-up | topup_package, run_topup |
| V27 | Apply suggestion | pipeline_run, tier_config |
| V28 | Invoicing & GST | billing_profile, invoice, etc. |
| V29 | Preview limits | pipeline_run, tier_config |
| V32 | Plan versions | plan_version |
| V34 | Validation report | validation_report |
| V37 | Test generation pipeline | test_plan, test_task, test_result |
| V38-V44 | Various (calibration, report, project provider, UI style) | pipeline_run, validation_report, project |
| V45 | Widen plan_version columns | plan_version (VARCHAR(36) → VARCHAR(255)) |

---

## 5. Security Architecture

```mermaid
graph TB
    subgraph "Authentication"
        LOGIN[POST /auth/login] --> JWT_GEN[Generate JWT<br/>HS256, 24h TTL]
        JWT_GEN --> ACCESS[Access Token<br/>Header: Authorization]
        JWT_GEN --> REFRESH[Refresh Token<br/>HttpOnly Cookie, 7d]
    end

    subgraph "Authorization (per request)"
        REQ[Incoming Request] --> JF[JwtFilter<br/>Validate token]
        JF --> SC[SecurityConfig<br/>Endpoint rules]
        SC --> RVS[RunVisibilitySpec<br/>Query-level access]
        SC --> RAG[RunAccessGuard<br/>Resource-level access]
    end

    subgraph "RBAC Hierarchy"
        SUPER[SUPER_ADMIN] --> ALL[All resources]
        ORG_ADMIN[ORG_ADMIN] --> ORG_RES[Org resources]
        BILLING[BILLING_ADMIN] --> BILL_RES[Billing only]
        DEV[DEVELOPER] --> OWN_RES[Org runs read<br/>Own runs write]
        FREE[FREE User] --> SELF[Own runs only]
    end

    subgraph "Sandbox"
        FS[FileSystemTool<br/>safeResolve()] --> WS[workspace/{runId}/]
        SHELL[ShellTool] --> POLICY[ShellSandboxPolicy<br/>Allow/blocklist]
    end
```

---

## 6. Deployment Architecture

### 6.1 CI/CD Pipeline

```mermaid
graph LR
    subgraph "GitHub Actions"
        PUSH[Push to main] --> BUILD[mvn package<br/>npm build]
        BUILD --> JAR[JAR artifact<br/>SHA-keyed name]
        JAR --> GCS_UP[Upload to GCS<br/>executespec-deploy]
    end

    subgraph "GCE Deployment"
        GCS_UP --> IAP[gcloud compute ssh<br/>via IAP tunnel]
        IAP --> DOWNLOAD[Download JAR]
        DOWNLOAD --> RESTART[supervisor.sh restart<br/>Health check]
    end

    subgraph "Rolling Update"
        RESTART --> VM1[VM 1: Deploy + verify]
        VM1 --> VM2[VM 2: Deploy + verify]
    end
```

### 6.2 Preview System (Dual-Mode)

The preview system operates in two modes, selected by the `executespec.preview.mode` property:

| Mode | Environment | TTL | Networking | Use Case |
|------|-------------|-----|------------|----------|
| `docker` | Local dev | 10 min | Docker Compose + Caddy reverse proxy (Cloudflare Origin Cert) | Local/VM development |
| `cloud-run` | Dev / Prod | 10 min | Cloud Run managed HTTPS (`*.run.app`) | Hosted environments |

**Key implementation details:**
- All subprocess commands enforce timeouts: `gcloud run deploy` 10 min, `gcloud run services delete` 5 min, `gcloud logging read` 2 min
- Stdout/stderr streams are read in parallel threads to prevent pipe buffer deadlock
- Virtual threads use `ContextSnapshotFactory.captureAll().wrap(runnable)` for Micrometer trace propagation
- `ACTIVE_PREVIEWS` gauge is correctly decremented on all terminal states (success, failure, TTL expiry)
- `probeApiDocs()` works for both modes: Docker (`localhost:{port}`) and Cloud Run (service URL)
- `DeploymentService` also uses context-wrapped virtual threads for trace continuity

#### 6.2.1 Cloud Run Preview Flow

```mermaid
sequenceDiagram
    participant PS as PreviewService
    participant DDG as DockerfileGenerator
    participant AR as ArtifactRegistryClient
    participant CR as CloudRunClient
    participant LOG as gcloud logging

    PS->>PS: Detect project type
    PS->>DDG: generateForCloudRun(type, workspace)
    Note over DDG: No ttyd — uses $PORT directly<br/>(port 8080)
    DDG-->>PS: Dockerfile

    PS->>AR: buildImage(workspace, tag)
    Note over AR: Subprocess with parallel<br/>stdout/stderr readers
    AR-->>PS: imageUri

    PS->>AR: pushImage(imageUri)

    PS->>CR: deployPreview(serviceName, imageUri, envVars)
    Note over CR: --min-instances=0<br/>--max-instances=1<br/>--port=8080<br/>--allow-unauthenticated<br/>--startup-cpu-boost<br/>Timeout: 10 min

    CR-->>PS: serviceUrl (*.run.app)

    PS->>PS: Health check (probeApiDocs)
    PS->>PS: Update session → RUNNING
    PS->>PS: Increment ACTIVE_PREVIEWS gauge
    PS->>PS: Schedule TTL cleanup (10 min)

    Note over PS: After TTL expires:
    PS->>CR: deleteService(serviceName)
    Note over CR: Timeout: 5 min
    PS->>AR: deleteImage(imageUri)
    PS->>PS: Decrement ACTIVE_PREVIEWS gauge

    Note over PS: Logs retrieval:
    PS->>LOG: gcloud logging read (filter by service)
    Note over LOG: Timeout: 2 min
    LOG-->>PS: Log entries
```

#### 6.2.2 Docker Preview Flow (Local Dev)

```mermaid
sequenceDiagram
    participant PS as PreviewService
    participant DDG as DockerfileGenerator
    participant DC as Docker Compose
    participant CADDY as CaddyProxyManager

    PS->>PS: Detect project type
    PS->>DDG: generateForDocker(type, workspace)
    Note over DDG: Includes ttyd for<br/>terminal access
    DDG-->>PS: Dockerfile + docker-compose.yml

    PS->>DC: docker compose up -d
    DC-->>PS: Container running on localhost:{port}

    PS->>CADDY: addRoute(subdomain, localhost:{port})
    Note over CADDY: Caddy admin API<br/>JSON config + Cloudflare Origin Cert

    PS->>PS: Health check (probeApiDocs → localhost:{port})
    PS->>PS: Update session → RUNNING
    PS->>PS: Increment ACTIVE_PREVIEWS gauge
    PS->>PS: Schedule TTL cleanup (10 min)

    Note over PS: After TTL expires:
    PS->>DC: docker compose down
    PS->>CADDY: removeRoute(subdomain)
    PS->>PS: Decrement ACTIVE_PREVIEWS gauge
```

---

## 7. Concurrency Model

```mermaid
graph TB
    subgraph "Thread Model"
        MAIN[Main Thread<br/>Spring MVC] --> SUBMIT[submitRun()]
        SUBMIT --> SEM[Semaphore<br/>maxRuns=10]
        SEM --> POOL[CachedThreadPool<br/>Daemon threads]
        POOL --> PIPELINE[Pipeline Thread<br/>1 per run]

        PIPELINE --> VT1[Virtual Thread<br/>Preview build<br/>Context-wrapped]
        PIPELINE --> VT2[Virtual Thread<br/>Post-merge agents<br/>Context-wrapped]

        PIPELINE --> GATE_POLL[Gate Polling<br/>500ms interval<br/>Redis-backed]
    end

    subgraph "Shared State"
        SS_MAP[StateStore<br/>PostgreSQL]
        REDIS_G[Redis<br/>Gate keys]
        CM[ConcurrentHashMap<br/>Preview sessions]
        RKS[RunKeyStore<br/>ConcurrentHashMap]
    end

    PIPELINE --> SS_MAP
    PIPELINE --> REDIS_G
    VT1 --> CM
    PIPELINE --> RKS
```

---

## 8. Observability

### 8.1 Logging

| Component | Log Level | Key Events |
|-----------|----------|------------|
| PipelineHarness | INFO | Run lifecycle (start, complete, fail) |
| WorkerAgent | INFO/DEBUG | Iteration count, tool calls, time remaining |
| PreviewService | INFO | Preview lifecycle (start, deploy, stop) |
| LlmRouter | WARN | Provider fallback, rate limits |
| TierEnforcementService | DEBUG | Quota checks, bonus run consumption |

### 8.2 Metrics (Spring Actuator)

- `actuator/health` — Health check (public)
- `actuator/prometheus` — Prometheus metrics (internal)
- Custom: `ModelTaskMetric` — per-task execution stats

### 8.3 SSE Event Types

| Event | When |
|-------|------|
| PLANNING | Pipeline starts planning |
| PLAN_READY | Plan generated, awaiting approval |
| WORKING | Tasks executing |
| TASK_STARTED / TASK_COMPLETED | Per-task lifecycle |
| VALIDATION_PASSED / VALIDATION_FAILED | Code validation |
| MODEL_ESCALATED | Retry with upgraded model |
| REVIEWING | Review phase |
| REPORT_GENERATED | Validation report ready (non-fatal) |
| TEST_GENERATION_STARTED | Test generation pipeline begins |
| TEST_EXECUTION_STARTED | Tests executing against live preview |
| TEST_EXECUTION_COMPLETED | Test execution finished |
| REVIEW_READY | Awaiting merge decision |
| COMPLETE / FAILED / PARTIAL | Terminal states |
| PREVIEW_ANALYZING / BUILDING / PUSHING / DEPLOYING / RUNNING | Preview phases |
| PREVIEW_STOPPED | Preview terminated |
| CLARIFICATION_NEEDED / RESOLVED | Context extraction gates |
| CONTEXT_EXTRACTED | JIRA/Confluence context enriched |

---

## 9. Technology Stack Summary

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.3 |
| AI Framework | Spring AI | 1.1.0 |
| Build | Maven | 3.9+ |
| Database | PostgreSQL | 15 |
| Cache/Gates | Redis (Memorystore) | 7.x |
| Frontend | React + Vite + Tailwind | 18 / 5 / 3 |
| Container | Docker | 24+ |
| Cloud | GCP | — |
| Compute | GCE (MIG) | n2-standard-8 |
| Serverless | Cloud Run | Gen2 |
| Registry | Artifact Registry | — |
| Storage | Cloud Storage (GCS) | — |
| Database | Cloud SQL (PostgreSQL) | 15 |
| LB | HTTPS External LB | — |
| CI/CD | GitHub Actions | — |
| Payment | Razorpay | API v1 |
| JVM | G1GC | — |

---

## 10. Key Design Decisions & Rationale

| Decision | Rationale | Alternative Considered |
|----------|-----------|----------------------|
| XML tool calls (not Spring AI native) | WorkerAgent needs `runId` injection; `@Tool` methods can't receive it | Spring AI `@Tool` annotation |
| Redis gates (not in-memory) | MIG: approval REST call may hit different instance than pipeline thread | CompletableFuture maps |
| Progressive merge (per-task) | Later tasks see previous output; reduces merge conflicts | Merge-all-at-end |
| 15-min time limit (not iteration count) | Predictable behavior; iteration count was unintuitive | Iteration-based limits |
| Dual-mode previews (docker + cloud-run) | Docker Compose + Caddy for local dev; Cloud Run for hosted envs (public HTTPS, no port exposure needed) | Single-mode only |
| 3-provider fallback chains | Resilience; no single-provider dependency | Single provider |
| Embedded DB for preview | Cloud Run can't run sidecars; H2/SQLite sufficient for preview | Shared Cloud SQL instance |
| ConcurrentHashMap for preview state | In-memory is fast; orphan cleanup handles restarts | Database-backed sessions |
| FIFO bonus run consumption | Fair; oldest credits used first; prevents hoarding | LIFO or random |

---

## 11. Design Decision Details

### Tool-Call Format
WorkerAgent uses **XML tool calls**: `<tool_call>{"name":"...", "args":{...}}</tool_call>`. NOT Spring AI native tool calling. Reason: `@Tool` methods require `runId` which the model doesn't know. Dispatch is manual via `parseXmlToolCalls()` + `dispatchTool()` with `runId` injected automatically.

### Completion Detection
WorkerAgent response must START with `{` (strict JSON check via `looksLikeCompletionReport()`). Prevents false positives on prose containing field names.

### Context Window Management
- **Sliding window**: First 2 messages + last 18 kept per API call
- **Progress checkpoints**: Every 15 iterations, re-inject task ID, objective, criteria
- **Completion urgency**: At iteration 75 (5 before cap), force JSON output
- **Pre-call token estimation**: If messages > 30, estimate tokens (chars/4), compact proactively
- **Emergency compaction**: HARD_STOP tries compaction first; only truly stops if recently compacted

### Prompt Caching
Anthropic `SYSTEM_ONLY` strategy. Cache TTL = 5 min. `AnthropicChatOptions.builder().maxTokens(maxOut).cacheOptions(SYSTEM_ONLY)` — no toolCallbacks.

### API Key Resolution
Resolution: **user key → org key → system key** based on org's `keySourceMode`:
- `SYSTEM`: Force platform keys only
- `OWN_KEYS`: User/org keys required
- `HYBRID` (default): User/org first, system fallback

---

## 12. WorkerAgent Tool Dispatch

| Tool call name | Java method |
|---|---|
| readFile / read_file | `fsTool.readFile(runId, path)` |
| writeFile / write_file | `fsTool.writeFile(runId, path, content)` |
| editFile / edit_file | `fsTool.editFile(runId, path, oldStr, newStr)` |
| listFiles / list_files | `fsTool.listFiles(runId, path)` |
| searchFiles / search_files | `fsTool.searchFiles(runId, pattern, directory)` |
| run / run_command / run_build / run_tests | `shellTool.run(runId, command, timeout)` |
| status / git_status | `gitTool.status(runId)` |
| diff / git_diff | `gitTool.diff(runId)` |
| commit / git_commit | `gitTool.commit(runId, message)` |

Result truncation: readFile → 8k chars, shell → 6k chars, diff → 5k chars.

---

## 13. Known Issues & Mitigations

| Issue | Mitigation |
|-------|-----------|
| Branch retry fails | `GitTool.createBranch()` catches `RefAlreadyExistsException` → checkout fallback |
| Token cost explosion | Sliding window (first 2 + last 18) + result truncation |
| Completion false positive | `looksLikeCompletionReport()` requires response starts with `{` |
| 80-iteration cap hit | Progress checkpoints every 15 iters + urgency at iter 75 |
| Hard stop at 73% context | Pre-call estimation + emergency compaction |
| Chat has no code access | ConversationAgent injects file tree, README, context, plan, review |
| Gate lost on server restart | `approve()` → if gate missing → auto-fail with message |

---

*This architecture document is maintained alongside the codebase. Last updated: 2026-03-30.*
