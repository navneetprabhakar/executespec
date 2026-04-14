# ExecuteSpec — User Manual

> **Version**: 2.4.0
> **Date**: 2026-04-14
> **Audience**: Developers, Product Managers, Tech Leads

> **Pilot v3.0 quick links**
> - [Project workspace walkthrough](#13-pilot-v30--project-workspace) — one-time setup, multi-repo runs, architecture doc, scoring
> - Plan + journey detail: [`docs/plans/pilot-v3-plan.md`](plans/pilot-v3-plan.md), [`docs/user-journeys.md`](user-journeys.md)

---

## Table of Contents

1. [Getting Started](#1-getting-started)
2. [AI Provider Setup](#2-ai-provider-setup)
3. [Submitting a Run](#3-submitting-a-run)
4. [The Pipeline Explained](#4-the-pipeline-explained)
5. [Understanding Run Status](#5-understanding-run-status)
6. [Previews](#6-previews)
7. [Background Validation](#7-background-validation)
8. [Collaboration](#8-collaboration)
9. [Billing & Credits](#9-billing--credits)
10. [Integrations](#10-integrations)
11. [Troubleshooting](#11-troubleshooting)
12. [API Reference](#12-api-reference)

---

## 1. Getting Started

### 1.1 Registration

Visit `https://dev.executespec.ai` and click **Get Started**. You can register with:

- **Email + password** — click **Sign Up**, enter your details, and verify your email address via the link sent to your inbox.
- **Google OAuth** — click **Continue with Google** and authorize the ExecuteSpec application.
- **GitHub OAuth** — click **Continue with GitHub** and authorize the ExecuteSpec application.

After registration, you are placed on the **FREE tier** (5 runs/month, 1-credit specs only).

### 1.2 First Login

On first login you will see the **Dashboard**. Key navigation elements:

| Element | Location | Purpose |
|---------|----------|---------|
| Launch Run | Header (primary button) | Submit a new run |
| Runs list | Sidebar / main area | View all your runs |
| Projects | Sidebar | Manage project groupings |
| Billing | Sidebar | View plans, credits, top-up |
| Settings | Sidebar (bottom) | Account, integrations, API keys |
| Notifications | Header bell | In-app run completion/failure alerts |

### 1.3 Creating a Project

Projects are optional groupings that allow you to:
- Select a default AI provider for all runs in that project
- Share runs with org members by role
- Configure integrations (GitHub repo, Slack channel) per project

To create a project:
1. Go to **Projects** in the sidebar
2. Click **New Project**
3. Enter a name and optional description
4. Select a default AI provider (Anthropic, OpenAI, or Gemini)
5. Optionally configure a GitHub repository (for automatic PR creation after each run)

---

## 2. AI Provider Setup

ExecuteSpec routes all AI calls through one of three LLM providers: **Anthropic** (Claude), **OpenAI** (GPT), or **Gemini** (Google AI Studio). Each provider is backed by a dedicated thread pool with priority queuing — your runs are never delayed by other providers' congestion.

### 2.1 Platform-Managed Keys (MANAGED mode)

On FREE and PRO tiers, ExecuteSpec uses platform API keys by default. You do not need to supply your own. Choose your preferred provider when launching a run or in your project settings.

### 2.2 Bring Your Own Keys (BYOK — MAX/ENTERPRISE)

MAX and ENTERPRISE users can configure their own API keys. BYOK is required for MAX tier.

To configure your keys:
1. Go to **Settings → AI Keys**
2. Enter your key for each provider you want to use
3. Click **Save** — keys are validated immediately
4. Set your org's **Key Source Mode**:
   - **MANAGED** — platform keys used (no user keys needed)
   - **HYBRID** — your keys used when available, platform keys as fallback
   - **OWN_KEYS** — your keys only; runs fail if no key is configured for the selected provider

**Key storage:** All user-provided API keys are encrypted at rest using AES-256-GCM before being stored. Keys are never written to application logs.

### 2.3 Selecting a Provider per Run

When launching a run, the **Provider** selector in the Launch modal shows all available providers (based on your org's key configuration and enabled providers). Select your preferred provider — all tasks in the run will use it as primary, with automatic fallover to healthy providers if needed.

**Provider failover order:**
- Anthropic → [OpenAI, Google]
- OpenAI → [Google, Anthropic]
- Google → [OpenAI, Anthropic]

Once the first task succeeds, the run is **pinned** to that provider to prevent context drift from mid-run provider switches.

### 2.4 Provider Availability

If a provider returns errors, ExecuteSpec's circuit breaker (10 consecutive failures → OPEN) temporarily routes to fallback providers. When a provider is degraded, the provider pill in the Launch modal shows a warning indicator.

---

## 3. Submitting a Run

### 3.1 Writing an Effective Spec

The **spec** (your natural-language request) is the primary input to ExecuteSpec. Good specs produce better plans.

**Tips for effective specs:**

| Do | Avoid |
|----|-------|
| Describe what the feature should do for the user | Describing implementation details (which classes, which files) |
| Reference specific pages, flows, or entities in your codebase | Vague instructions like "make it better" |
| Mention constraints: tech stack, design system, performance budget | Asking for too many unrelated features in one spec |
| State acceptance criteria: what does "done" look like? | Omitting the "why" behind a requirement |

**Example — effective spec:**
```
Add a paginated transaction history table to the Billing page. Each row should 
show: date, description, amount (INR/USD based on currency), status badge 
(green=paid, yellow=pending, red=failed). Clicking a row opens a slide-over 
with the full invoice details and a "Download PDF" button. Use the existing 
InvoiceService.getInvoices() endpoint and follow our Linear design system.
```

### 3.2 Execution Mode

When submitting a run, choose an execution mode:

| Mode | Use Case |
|------|----------|
| **PROTOTYPE** | New features, exploratory work — fastest path to a working preview |
| **PRODUCTION** | Production-quality code with full review, tests, and validation report |

PROTOTYPE runs skip some validation phases and are significantly faster.

### 3.3 Real-Time vs Batch Execution

After your plan is generated and approved, choose how tasks execute:

| Option | Description | Cost |
|--------|-------------|------|
| **Real-time** | Tasks execute immediately, in order | Standard credit cost |
| **Batch** | Tasks submitted to provider batch APIs overnight | 50% credit discount |

Batch execution is ideal for large, non-urgent runs. You receive an in-app notification and email when batch results are ready. Failed batch tasks are automatically retried in real-time.

### 3.4 Repository Mode

| Mode | Description |
|------|-------------|
| **New repository** | ExecuteSpec creates a fresh workspace from scratch |
| **Existing repository** | Provide a GitHub (or Bitbucket) repo URL — the pipeline clones it, works on a branch, and opens a PR |

For existing repos, configure your GitHub PAT in **Settings → Integrations → GitHub**.

### 3.5 Launching the Run

1. Click **Launch Run** in the header
2. Write your spec in the text area (max 50,000 characters)
3. Select provider, execution mode, and repository mode
4. Click **Submit** — you are navigated to the Run Detail page

The run begins immediately with request triage to determine the optimal execution path (single-shot for simple requests, multi-task for complex ones).

---

## 4. The Pipeline Explained

### 4.1 Overview

```
Submit → Triage → Plan → Approve → Work → Compile → Merge → COMPLETE
                                                                  ↓
                                              Background Validation (parallel)
                                              Review → Test Gen → Test Exec → Report
```

### 4.2 Request Triage

Before planning, ExecuteSpec classifies your request into one of three tiers:

| Classification | Criteria | Path |
|---------------|----------|------|
| SINGLE_SHOT | Simple change, ≤1 file, low complexity | Skips planner; single worker task |
| MEDIUM | Moderate complexity, 2-4 files | Planner capped at 4 tasks |
| MULTI_TASK | Complex, multi-file, >4 estimated tasks | Full planner decomposition |

If a single-shot run turns out more complex than expected (>8 files, >8 minutes elapsed, or >5 compiler errors), it **auto-escalates** to MULTI_TASK without user intervention.

### 4.3 Planning Gate

The **PlannerAgent** decomposes your request into an ordered task list. Each task includes:
- Description of what to build
- Files to create or modify (with complexity: LOW, MEDIUM, HIGH)
- Dependencies on other tasks (topological ordering)
- Acceptance criteria

You review the plan and **cost estimate** before approving. The cost estimate shows:
- Low estimate (1 LLM iteration per task)
- High estimate (3 LLM iterations per task, accounting for retries)
- Credit cost: `credits = max(1, ceil(bufferedEstimate / $5.00))`

Click **Approve** to start execution. Credits are charged at approval. If you reject the plan, no credits are charged.

### 4.4 Task Execution

Tasks run **sequentially**, one at a time, in the order the planner emitted them. For each task, a single **WorkerAgentV2** loop:
1. Reads the task's description + any hard constraints injected by the planner
2. Invokes the LLM via native tool calling (Anthropic / OpenAI / Gemini)
3. Calls file tools (`readFile`, `writeFile`, `editFile`, `shell`, `gitCommit`, etc.) as needed
4. Ends the task by invoking the `completeTask` tool

Iteration caps per complexity: **LOW 12 iters / 4k tokens**, **MEDIUM 22 / 8k**, **HIGH 35 / 16k**.

Each task ends with a **smoke test** — the project's build command (Maven / Gradle / npm / cargo / go / python compileall) is invoked via the `buildProject` tool. If the smoke test fails, the worker gets one retry with the build error injected. If the retry also fails, the task is marked `COMPLETED_WITH_WARNING` and the run continues — the post-execute reviewer takes another pass.

V2 does not use subtask decomposition or progressive merge; each task writes to the main branch directly and the next task sees the result.

**Task execution limits:**
- 25-minute wall-clock time limit per task
- Stall detection: if no file writes occur for 5 minutes, the model is escalated from WORKHORSE to PLANNING tier
- 3 retry attempts per task with error feedback

### 4.5 Smoke test (per task)

After each task's worker finishes, V2 runs the project's build command as a smoke test (`buildProject` tool):
- Java/Maven: `mvn -q -DskipTests compile`
- Java/Gradle: `./gradlew -q assemble -x test` (or bare `gradle` if no wrapper)
- TypeScript/Node: `npm run build --if-present`
- Python: `python3 -m compileall -q .`
- Rust: `cargo build --quiet`
- Go: `go build ./...`

If the smoke test fails, the worker retries once with the build error injected into the user prompt. If that retry also fails, the task is marked `COMPLETED_WITH_WARNING` and the run continues. The post-execute **reviewer** (see §5.1) will take another pass.

### 4.6 Plan Approval in Detail

The plan approval gate supports:
- **`skipApproval` flag** — for CI/CD integrations, auto-approve the plan without human review
- **24-hour wait timeout** — if unapproved for 24 hours, the gate auto-closes and the run fails with a message
- **Server restart resilience (v2)** — gate state is dual-written to Redis and the `gate_record` database table; a Redis restart during a long approval wait does not lose the gate

---

## 5. Understanding Run Status

| Status | Meaning | User Action |
|--------|---------|-------------|
| `QUEUED` | Waiting for a provider pool slot | None — automatic |
| `PLANNING` | PlannerAgent is decomposing your request | None — wait |
| `AWAITING_APPROVAL` | Plan is ready; waiting for your approval | **Approve or reject** the plan |
| `WORKING` | Tasks are executing | None — watch the live task graph |
| `REVIEWING` | Reviewer running general + wiring passes | None — wait |
| `COMPLETE` | Execution + review done; preview starting | Use the preview, check the validation report |
| `PARTIAL` | Some tasks succeeded; others failed | Review failed tasks; optionally retry |
| `FAILED` | Run could not be completed | Check the failure reason; retry or adjust spec |
| `PAUSED` | Run manually paused | Resume when ready |

### 5.1 Task Status

Within a run, each task has its own status:

| Task Status | Meaning |
|------------|---------|
| `PENDING` | Waiting to execute |
| `WORKING` | Worker executing tasks sequentially |
| `COMPLETE` | Task finished and merged |
| `FAILED` | Task failed after all retries |
| `BLOCKED` | A dependency task failed; this task was skipped |
| `RETRYING` | Failed task in retry loop |

### 5.2 Live Event Stream

The Run Detail page receives live SSE events as the pipeline executes. Key events:

- **PLAN_READY** — plan is available for review
- **TASK_STARTED / TASK_COMPLETED** — individual task lifecycle
- **MODEL_ESCALATED** — stall detected; model upgraded
- **COMPILING / MERGING** — pipeline phase transitions
- **COMPLETE** — pipeline finished; preview starting
- **REVIEW_STARTED / REVIEW_COMPLETE** — background review phases
- **REPORT_GENERATED** — validation report available

---

## 6. Previews

### 6.1 Automatic Preview

When a run reaches `COMPLETE`, ExecuteSpec automatically starts a **preview** of the generated code. The preview:
- Starts within 60–90 seconds of COMPLETE
- Has a 5-minute TTL (auto-stops after 5 minutes)
- Runs on Cloud Run (hosted environments) or Docker Compose (local dev)
- Is accessible via a secure `*.run.app` URL (Cloud Run) or your Caddy proxy subdomain (local)

### 6.2 Preview Concurrency Limits

| Tier | Concurrent Previews |
|------|---------------------|
| FREE | 1 |
| PRO | 3 |
| ENTERPRISE | 10 |

If you are at your concurrent limit, starting a new preview stops the oldest one.

### 6.3 Manual Preview Control

On the **Preview** tab of Run Detail:
- **Start Preview** — manually start a preview (e.g., after the automatic 5-min TTL expired)
- **Stop Preview** — immediately stop the active preview
- **Run Tests** (Beta) — manually trigger test execution against the running preview

### 6.4 Preview Modes

| Mode | Environment | URL |
|------|-------------|-----|
| Cloud Run | Dev / Prod | `https://es-preview-{shortId}.run.app` |
| Docker | Local dev | `https://{shortId}.preview.yourdomain.com` |

The mode is selected via `executespec.preview.mode` in `application.yml`.

### 6.5 Preview Logs

While a preview is running, click **View Logs** on the Preview tab to see real-time application logs from the Cloud Run service or Docker container.

---

## 7. Background Validation

After a run reaches `COMPLETE`, ExecuteSpec performs background validation in a separate thread while you interact with the preview. This keeps your feedback loop fast while ensuring quality.

### 7.1 Validation Phases

1. **Code Review (ReviewerAgentV3)**
   - Two agentic passes — a general review (logic bugs, incomplete implementations, contract violations) and a project-type-aware wiring review (controller registration, barrel exports, module wiring, etc.)
   - After both passes, the build is re-run
   - Result: CLEAN / FIXED_ISSUES / UNRESOLVED_ISSUES / REVIEW_FAILED

2. **Test Generation (TestGenOrchestrator)**
   - Generates a test plan based on your spec and the generated code
   - Writes test files (unit tests, integration tests, API tests as appropriate)
   - Compiles tests

3. **Test Execution (TestPreviewCoordinator)**
   - Runs the generated tests against the live preview
   - Reports pass/fail per test

4. **Validation Report (ValidationReportGenerator)**
   - Aggregates findings from review, compilation, and test execution
   - Produces a markdown report with severity-classified issues
   - Available for PDF download
   - Can be shared via a public link (no auth required)

**Note:** Batch runs skip the automatic preview during background validation. Test execution is skipped for batch runs (use the manual "Run Tests" button after manually starting a preview).

### 7.2 Viewing the Validation Report

On the Run Detail page, click the **Validation Report** tab. The report shows:
- Overall pipeline result: PASSED / PASSED_WITH_FIXES / FAILED
- Per-issue breakdown with severity badges (CRITICAL, MEDIUM, LOW)
- Full markdown review narrative
- Test results (pass/fail counts, failed test names)

To download: click **Download PDF** on the Validation Report tab.

To share: click **Share Report** — generates a public link with a 30-day expiry.

### 7.3 Durable Validation (v2)

Validation state is now persisted to the database. If the validation thread stalls (no progress for 10 minutes), the system automatically retries from the last completed stage (up to 3 attempts).

If automatic retry fails:
1. You will receive an in-app notification
2. Go to the Run Detail page → **Validation** tab → click **Retry Validation**

---

## 8. Collaboration

### 8.1 Organizations

To collaborate with teammates:
1. Go to **Settings → Organization**
2. Click **Create Organization** (if you don't have one)
3. Invite teammates via **Invite Member** (enter their email address)

Invited members receive an email and must accept to join.

### 8.2 Roles

| Role | What They Can Do |
|------|-----------------|
| `ORG_ADMIN` | Manage members, billing, integrations, all runs in the org |
| `BILLING_ADMIN` | View and manage billing; cannot see code or runs |
| `DEVELOPER` | Create and view all runs in the org |
| `PROJECT_ADMIN` | Manage members and settings for specific projects |
| `PROJECT_DEVELOPER` | Create and view runs within assigned projects |

Free users without an org can only see their own runs.

### 8.3 Project Sharing

Runs belong to a **project**. Add teammates to a project via **Project Settings → Members**. Assign roles at the project level (PROJECT_ADMIN or PROJECT_DEVELOPER).

### 8.4 Run Archive

Completed or failed runs can be archived to keep the run list clean:
- Click the **...** menu on any run card → **Archive**
- Archived runs are hidden from the default list but remain accessible via the **Archived** filter
- Archived runs are NOT deleted — they count toward storage retention

---

## 9. Billing & Credits

### 9.1 Tiers

| Tier | Runs/Month | Max Concurrent Previews | API Keys |
|------|-----------|------------------------|---------|
| FREE | 5 | 1 | Platform keys |
| PRO | 100 | 3 | Platform keys (HYBRID) |
| MAX | Unlimited | 3 | Own keys required (BYOK) |
| ENTERPRISE | Unlimited | 10 | Own keys or managed |

### 9.2 Credit Calculation

Each run costs a variable number of credits, calculated at plan approval:

```
credits = max(1, ceil(bufferedEstimate / $5.00))
```

Where `bufferedEstimate = estimatedCost × 1.10` (10% buffer for overruns).

The estimated cost is shown on the Plan Approval screen before you commit.

**Free tier:** Limited to 1-credit specs only (simple, single-task runs). The Launch modal warns you if your spec is estimated at >1 credit.

### 9.3 Batch Discount

Choosing **Batch** execution at plan approval reduces the credit cost by **50%**. Batch execution uses provider batch APIs (Anthropic Message Batches, OpenAI Batch API, Gemini Batch Mode), which have lower per-token pricing.

### 9.4 Credit Refunds

If a run **fails** (status = FAILED), all credits charged at approval are automatically refunded to your balance. Partial failures (PARTIAL status) do not receive automatic refunds — you can contact support for manual review.

### 9.5 Top-Up Credits

To purchase additional run credits beyond your monthly plan:
1. Go to **Billing → Top Up**
2. Select a package (5, 10, or 20 runs — INR or USD)
3. Complete payment via Razorpay (INR) or Stripe (USD/EUR/GBP)

Top-up credits are non-refundable and do not expire.

### 9.6 Upgrading Plans

1. Go to **Billing → Plans**
2. Click **Upgrade** on your desired plan
3. Complete payment — your new tier is active immediately

When upgrading mid-cycle (e.g., PRO → MAX), unused runs from your current plan are prorated and refunded automatically.

### 9.7 Invoices

All invoices are available under **Billing → Invoices**. INR invoices include GST (18%). PDF download available per invoice.

---

## 10. Integrations

### 10.1 GitHub

**Purpose:** After a run completes, ExecuteSpec automatically creates a branch (`executespec/{runId-short}`) and opens a pull request against your repo's default branch.

**Setup:**
1. Go to **Settings → Integrations → GitHub**
2. Enter your GitHub Personal Access Token (PAT) with `repo` scope
3. Click **Save & Validate**

**Per run:** When launching a run, select **Existing Repository** and enter the repo URL. The PR link appears in the Run Detail **Overview** tab after the run completes.

### 10.2 Bitbucket

**Purpose:** Same as GitHub — branch creation and PR opening against your Bitbucket repo.

**Setup:**
1. Go to **Settings → Integrations → Bitbucket**
2. Enter your Bitbucket username, app password (with `repository:write` permission), workspace, and repo slug
3. Click **Save & Validate**

After a run completes, the PR link appears in the Run Detail **Overview** tab.

### 10.3 JIRA

**Purpose:** ExecuteSpec posts a comment to the linked JIRA issue when a run completes, including the PR URL and validation summary.

**Setup:**
1. Go to **Settings → Integrations → JIRA**
2. Enter your JIRA base URL, email, and API token
3. Click **Save**

**Inbound:** Configure a JIRA webhook to `POST /api/webhooks/jira` — attaching a JIRA issue key to the run automatically pulls the issue's description as additional context for the planner.

### 10.4 Confluence

**Purpose:** ExecuteSpec creates a child page under your configured Confluence page with the validation report after each run.

**Setup:**
1. Go to **Settings → Integrations → Confluence**
2. Enter your Confluence base URL, email, API token, and parent page ID
3. Click **Save**

### 10.5 Slack

**Purpose:** Receive Slack notifications when runs complete, fail, or require approval.

**Setup:**
1. Go to **Settings → Integrations → Slack**
2. Click **Connect Slack** — complete OAuth authorization
3. Configure which projects map to which Slack channels

Notifications use Block Kit format and include a direct link to the Run Detail page.

---

## 11. Troubleshooting

### 11.1 Common Issues

| Symptom | Likely Cause | Resolution |
|---------|-------------|------------|
| Run stuck in PLANNING for >5 minutes | LLM provider slow or rate-limited | Wait — the circuit breaker will switch providers automatically |
| Task FAILED with "timeout" | Task exceeded 25-minute wall-clock limit | Retry the task (see 11.2); consider breaking the spec into smaller runs |
| Run FAILED with "no API key" | OWN_KEYS mode, key not configured | Go to Settings → AI Keys and add your key for the selected provider |
| SSE stream disconnected | Network interruption | Refresh the page — events since disconnect are replayed automatically |
| Preview won't start | Concurrent preview limit reached | Stop another active preview first |
| "Insufficient credits" on approval | Credit balance too low | Top up credits via Billing → Top Up |
| Preview starts but app crashes | Code issue in generated output | Check the Preview Logs tab; apply suggestions from the Validation Report |
| Validation report not appearing | Background validation stalled | Wait up to 10 minutes for auto-retry; use Retry Validation if still stuck |

### 11.2 Retrying Failed Tasks

To retry a specific failed task:
1. On the Run Detail page, find the failed task in the **Task Graph**
2. Click the **...** menu on the task node → **Retry Task**
3. The task re-executes with error feedback from the previous attempt and (if needed) a higher-tier model

### 11.3 Cancelling a Run

To cancel an in-progress run:
1. On the Run Detail page, click **Cancel Run** in the top-right menu
2. Confirm cancellation

Cancellation is best-effort — tasks already executing may complete before cancellation takes effect. Credits for cancelled runs in WORKING/COMPILING state are not automatically refunded (the platform has already consumed API credits).

### 11.4 Applying Chat Suggestions

The **Chat** tab on Run Detail allows you to ask questions about the generated code and request targeted improvements. Suggestions can be applied to the run workspace without starting a new run:
1. Describe the change you want in the Chat tab
2. Review the suggestion
3. Click **Apply Suggestion** — ExecuteSpec executes the change in your existing workspace

### 11.5 Stuck Background Validation

If the Validation Report tab shows "Validation in progress" for more than 15 minutes:
1. Click **Retry Validation** on the Validation Report tab (requires DEVELOPER role or higher)
2. Validation resumes from the last successfully completed stage

### 11.6 Rate Limits

If you see HTTP 429 errors:
- **Run submission rate limit:** Wait 1 minute (limits reset per minute)
- **Provider queue full:** The selected provider's queue is temporarily at capacity; retry in a few seconds or switch providers
- **API key rate limit:** Your own API key is being rate-limited by the provider; wait for the provider's rate limit window to reset

---

## 12. API Reference

All endpoints require a valid JWT in the `Authorization: Bearer {token}` header, except where noted.

### 12.1 Authentication

**Login:**
```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "yourpassword"
}
```

Response:
```json
{
  "accessToken": "eyJhbGci...",
  "user": { "id": "...", "email": "user@example.com", "tier": "PRO" }
}
```

**Refresh token:**
```http
POST /api/auth/refresh
```
Uses HTTP-only refresh cookie (set automatically on login). Returns a new `accessToken`.

**Get SSE token (required for SSE streams):**
```http
GET /api/runs/{runId}/sse-token
Authorization: Bearer {accessToken}
```

Response:
```json
{ "token": "ey..." }
```

### 12.2 Run Lifecycle

**Submit a run:**
```http
POST /api/runs
Content-Type: application/json
Authorization: Bearer {token}

{
  "userRequest": "Add a paginated transaction history table to the Billing page...",
  "aiProvider": "OPENAI",
  "executionMode": "PRODUCTION",
  "repoMode": "NEW",
  "projectId": "proj-uuid"
}
```

Response (202 Accepted):
```json
{
  "runId": "run-uuid",
  "status": "PLANNING",
  "estimatedCredits": 2
}
```

**Get run status:**
```http
GET /api/runs/{runId}
```

Response:
```json
{
  "id": "run-uuid",
  "status": "AWAITING_APPROVAL",
  "plan": {
    "tasks": [...],
    "estimatedCostLow": 0.05,
    "estimatedCostHigh": 0.15,
    "creditsRequired": 1
  }
}
```

**Approve plan:**
```http
POST /api/runs/{runId}/approve
Content-Type: application/json

{
  "approved": true,
  "executionMode": "REALTIME",
  "creditsConfirmed": 1
}
```

Batch execution is not yet available under V2 — `executionMode` accepts only `REALTIME` (or may be omitted). The V2 batch re-implementation is tracked in `docs/plans/v2-batch-execution-plan.md`.

**Cancel run:**
```http
DELETE /api/runs/{runId}
```

**Subscribe to SSE events:**
```
GET /api/runs/{runId}/events?token={sseToken}
Accept: text/event-stream
```

Events are streamed as:
```
event: TASK_COMPLETED
data: {"taskId":"task-uuid","title":"Add transaction table","status":"COMPLETE"}

event: COMPLETE
data: {"runId":"run-uuid","status":"COMPLETE"}
```

### 12.3 Validation Report

**Get report:**
```http
GET /api/runs/{runId}/report
```

**Download as PDF:**
```http
GET /api/runs/{runId}/report/pdf
```

**Share report (generates public link):**
```http
POST /api/runs/{runId}/report/share
```

Response:
```json
{ "shareToken": "abc123", "shareUrl": "https://dev.executespec.ai/reports/shared/abc123" }
```

**View shared report (no auth required):**
```http
GET /api/reports/shared/{shareToken}
```

### 12.4 Previews

**Start preview:**
```http
POST /api/runs/{runId}/preview/start
```

**Stop preview:**
```http
DELETE /api/runs/{runId}/preview/stop
```

**Run tests against preview (Beta):**
```http
POST /api/runs/{runId}/run-tests
```

Requires preview to be in RUNNING status.

### 12.5 Retry Failed Task

```http
POST /api/runs/{runId}/tasks/{taskId}/retry
```

### 12.6 Retry Stalled Validation

```http
POST /api/runs/{runId}/retry-validation
```

Requires DEVELOPER role or higher. Resumes from the last successfully completed validation stage.

### 12.7 GitHub / SCM Push

```http
POST /api/runs/{runId}/push-scm
```

Pushes merged workspace to configured GitHub or Bitbucket repo and opens a PR.

### 12.8 Notifications

**List notifications (paginated):**
```http
GET /api/notifications?page=0&size=20
```

**Mark as read:**
```http
POST /api/notifications/{notificationId}/read
```

**Mark all as read:**
```http
POST /api/notifications/read-all
```

**Unread count:**
```http
GET /api/notifications/unread-count
```

### 12.9 Billing

**List available plans:**
```http
GET /api/billing/plans
```

**Purchase top-up credits:**
```http
POST /api/billing/topup/purchase
Content-Type: application/json

{
  "packageId": "topup-10-usd",
  "currency": "USD",
  "gateway": "STRIPE"
}
```

**Payment history:**
```http
GET /api/billing/payment-history?page=0&size=20
```

### 12.10 GDPR Data Deletion

```http
DELETE /api/users/me/data
```

Anonymizes personal data fields on your account, soft-deletes all associated runs, and removes your API keys from the platform. This action is irreversible.

---

## 13. Pilot v3.0 — Project workspace

> Released in commit `6bbfde9b`. Backwards compatible: legacy single-shot
> projectless runs continue to work unchanged.

### 13.1 One-time project setup (per ORG_ADMIN)

1. Open `/projects/:projectId/dashboard`. The header shows the project name,
   description, and provider chip.
2. **Repos tab → Add repository.**
   - **Create new** for a fresh registration: choose scope (`ORG` = shared
     across the org, `USER` = personal) and type `owner/repo`. Stack is
     auto-detected from the file tree (Spring Boot, Vite, FastAPI, Go,
     Rust, Ruby/Rails, PHP).
   - **Pick existing** to attach a repo someone already created in your org.
3. Tag relationships on each repo card ("calls api-server", "depends on
   shared-models"). These flow into the architecture doc.
4. **Architecture tab → Generate.** Builds a per-repo codemap (top-level
   layout, routes, models) and a project-wide architecture document. Versioned;
   you can browse older versions from the dropdown.
5. Add free-text annotations ("auth handled by external Keycloak", "money
   path uses BigDecimal"). The pre-plan assessment + planner read these.

### 13.2 Submitting a multi-repo run

1. Click **New Run**.
2. Pick the project. The modal swaps the single-repo URL field for
   **checkboxes of every linked repo**. Tick the ones to clone.
3. (Optional) paste a **Figma URL** to enable design-compliance scoring.
4. Submit. The orchestrator clones each selected repo into
   `workspace/<runId>/repos/<name>/` so workers can read/edit across all of
   them in the same run.

### 13.3 Pre-plan assessment

Before planning, the system runs a **feature assessment** and broadcasts an
SSE log entry. The `AssessmentPanel` appears above the plan-approval gate,
showing:

- **Complexity** chip (S / M / L)
- **Repos in scope**
- **Cross-repo dependencies** derived from architecture annotations
- **Risks** detected from request keywords (auth, payment, schema, destructive)

When the `assessment.llm.enabled` feature flag is on (off by default), the
assessment is upgraded to an LLM-backed call that also returns
`filesAffected` and `newFiles` — which files are likely to change before you
approve credits. Any LLM failure silently degrades to the heuristic so you
never wait on assessment.

### 13.4 PR workflow

When the run completes and a push is configured:

- Each cloned subdirectory with worker changes gets its own branch
  (`Project.branchNamingPattern` template, defaults to
  `executespec/{runId}/{description}`).
- A PR opens against `Project.targetBranch` (or each repo's actual default
  branch as fallback).
- Reviewers from `Project.defaultReviewers` are auto-requested.
- All sibling PRs are cross-linked in their bodies for easy navigation.
- The **PR list** in the run detail sidebar shows every PR with a colored
  status chip (OPEN / MERGED / CLOSED). Status flips automatically when the
  GitHub webhook fires.

### 13.5 Validation report — six-dimension scoring

Every completed run's validation report now opens with a `ScoreCard` showing:

- Overall accuracy (0–100, A–F grade)
- **Code quality** — derived from review issue count + severity
- **Test coverage** — from the accuracy report's coverage level
- **Architecture** — from spec-mismatch / cross-task / logic issues
- **Security** — from security-categorised issues
- **Performance** — from deployability / performance-flagged issues
- **Design compliance** — only when the run included a Figma URL

Bars are colour-coded ≥80 green / 60–79 yellow / <60 red. The trend chart on
the dashboard plots the overall + four key dimensions across every completed
run in the project.

### 13.6 Dashboard

`/projects/:projectId/dashboard` shows:

- **Metric tiles** — active / completed / failed runs + average accuracy.
- **Overview tab** — recent runs (with per-run score), recent PRs (with
  status chips + merge-rate stat), score trend chart.
- **Tasks / Repos / Architecture / Team tabs** — deeper drill-downs.
- **Architecture tab** displays an amber **"Stale — N/M repos ahead"** badge
  whenever the underlying repos have moved past the SHA the codemap was
  generated from. Click Regenerate to refresh.

### 13.7 Operational toggles

| Setting | Where | Default | Purpose |
|---|---|---|---|
| `github.webhook.secret` | env / config | (unset) | Required to accept GitHub PR webhooks |
| `assessment.llm.enabled` | feature flags table | `false` | When `true`, FeatureAssessmentService uses Anthropic instead of the heuristic |
| `StackRefreshJob` | `@Scheduled` | daily 03:00 | Refreshes detected_stack + last_synced_sha for every project_repo |

