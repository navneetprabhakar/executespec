# External Integrations — Requirements

> Last updated: 2026-04-03

## Overview

Webhook-based and manually-triggered integrations with Jira, Confluence, ServiceNow, PagerDuty, and Slack. Inbound webhooks and manual trigger endpoints create pipeline runs; outbound listeners publish run status back to source systems via the `MergeCompleted` event (fires in both PROTOTYPE and PRODUCTION modes). Slack provides push-only pipeline notifications. JIRA/Confluence context extraction auto-enriches run requests with referenced issue/page content. GitHub integration creates branches and opens PRs for existing-repo runs.

---

## Features

### 1. Integration Framework

**Requirements:**
- Master switch: `agent.integrations.enabled` (default: false)
- `IntegrationManager` routes inbound webhooks to source-specific InboundAdapters and listens for `MergeCompleted` to trigger outbound adapters
- InboundAdapter interface: verify signature, parse event, map to pipeline run
- OutboundAdapter interface: publish status updates back to source system
  - Triggered by the `MergeCompleted` event (not `RunCompleted`) -- this ensures the review report and validation data are available before outbound notifications fire
  - `MergeCompleted` fires in both PROTOTYPE and PRODUCTION pipeline modes
  - Adapters must be null-safe for review data: PROTOTYPE mode completes without a review phase, so review/report fields may be null
- Deduplication via IntegrationEventLog (sourceType + eventId)
- Graceful handling of duplicate/ignorable events
- Per-source skip-approval configuration
- Endpoints:
  - `POST /api/integrations/{source}/webhook` -- inbound webhooks
  - `GET /api/integrations/status` -- connection status
  - `POST /api/integrations/jira/trigger` -- manual Jira trigger (accepts `{issueKey, goal}`)
  - `POST /api/integrations/confluence/trigger` -- manual Confluence trigger (accepts `{pageId, goal}`)

### 2. Jira Integration

**Requirements:**
- Trigger: Issue transitions to configured status (default: "Ready for Coding")
- Extract: issue summary + description as user request
- Outbound status updates (via `MergeCompleted`):
  - Run started -> "In Progress"
  - Run completed -> "In Review"
  - Run failed -> "Backlog"
- Null-safe review handling: `JiraAdapter.onRunComplete()` gracefully handles null review data (PROTOTYPE mode has no review phase)
- Manual trigger: `POST /api/integrations/jira/trigger` with `{issueKey, goal}` -- fetches issue details and creates a pipeline run
- Webhook signature verification via `JIRA_WEBHOOK_SECRET`
- Configuration:
  ```yaml
  agent.integrations.jira:
    enabled: false
    base-url: ${JIRA_BASE_URL}
    email: ${JIRA_EMAIL}
    api-token: ${JIRA_API_TOKEN}
    trigger-status: "Ready for Coding"
    skip-approval: false
  ```

### 3. Confluence Integration

**Requirements:**
- Trigger: Page labeled with configured label (default: "agent-run")
- Extract: page title + body as user request
- Manual trigger: `POST /api/integrations/confluence/trigger` with `{pageId, goal}` -- fetches page content and creates a pipeline run
- Update: add child page with run results link (via `MergeCompleted` event)
- Null-safe review handling: `ConfluenceAdapter.onRunComplete()` gracefully handles null review data (PROTOTYPE mode has no review phase)
- Configuration:
  ```yaml
  agent.integrations.confluence:
    enabled: false
    base-url: ${CONFLUENCE_BASE_URL}
    trigger-label: "agent-run"
    space-key: "ENG"
    skip-approval: false
  ```

### 4. ServiceNow Integration

**Requirements:**
- Trigger: Incident in configured categories (default: "application", "database")
- Extract: incident short_description + description as user request
- Status: Update incident work notes with run progress
- Optional auto-resolve on successful completion
- Configuration:
  ```yaml
  agent.integrations.servicenow:
    enabled: false
    base-url: ${SNOW_BASE_URL}
    trigger-categories: ["application", "database"]
    auto-resolve: false
    skip-approval: false
  ```

### 5. PagerDuty Integration

**Requirements:**
- Trigger: Incident trigger events
- Extract: incident title + description as user request
- Status: Add notes to incident with run status
- Optional auto-resolve on successful completion
- Webhook signature verification via `PD_WEBHOOK_SECRET`
- Configuration:
  ```yaml
  agent.integrations.pagerduty:
    enabled: false
    api-token: ${PD_API_TOKEN}
    trigger-types: ["incident.trigger"]
    auto-resolve: false
    skip-approval: false
  ```

### 6. GitHub Integration

**Purpose:** Automatic branch creation and PR opening for existing-repo runs. New repos get a direct push to main.

**Requirements:**
- **New repos:** Create private repo via `GitHubRepoService`, push main branch
- **Existing repos:**
  - `PipelineHarness.pushToGitHubIfConfigured()` creates branch `executespec/{runId-short}`
  - Pushes to the branch (not main) via `GitTool.pushToBranch()`
  - Opens a PR automatically via `GitHubRepoService.createPullRequest()` against the repo's default branch (looked up via `GitHubRepoService.getDefaultBranch()`)
  - PR URL stored in `PipelineRun.githubPrUrl` (V52 migration)
  - PR URL surfaced in `RunDetail` API response and frontend overview tab
- Per-user GitHub PAT stored in `app_user` table; priority: user-level PAT -> system-level PAT
- Manual push endpoint: `POST /api/runs/{id}/push-github`

### 7. Slack Integration

**Purpose:** Push-only pipeline notifications to Slack channels. Org-level OAuth connection with per-project channel routing and configurable verbosity.

**Requirements:**
- OAuth 2.0 app installation flow -- org admin initiates from Integrations page
- State parameter: HMAC-signed (`orgId|timestamp|hmac`) using JWT secret, 5-minute TTL
- Per-org connection stored in `org_slack_integration` table (team_id, team_name, access_token, bot_user_id, scopes)
- Per-project channel routing via `project_slack_channel` table (project_id -> channel_id + channel_name)
- Default channel on org integration for non-project runs
- Three verbosity levels: `MINIMAL` (start/complete/fail only), `NORMAL` (+ gate events), `VERBOSE` (+ task progress, validation, cost)
- Block Kit messages with action buttons (Approve Plan, View Run) linking to dashboard
- Notification log in `slack_notification_log` (org_id, run_id, channel_id, event_type, status, error, timestamp)
- Rate limiting: per-workspace (team_id) with 1.1s minimum interval between posts
- Event bridge: `SlackNotificationService` listens for `RunEvent` via Spring `@EventListener`
- Conditional beans: `@ConditionalOnProperty("agent.integrations.slack.enabled")`
- Retry: up to 3 attempts with 1.5s delay on transient failures
- Notification log cleanup job: deletes entries older than 30 days
- Endpoints:
  - `GET /api/integrations/slack/install` -- returns OAuth install URL
  - `GET /api/integrations/slack/oauth/callback` -- handles OAuth redirect
  - `GET /api/integrations/slack/status` -- connection status for current org
  - `DELETE /api/integrations/slack/disconnect` -- removes Slack connection
  - `POST /api/integrations/slack/test` -- sends test message to a channel
  - `GET /api/integrations/slack/channels` -- lists available Slack channels
  - `GET /api/integrations/slack/channels/project/{projectId}` -- get project channel mapping
  - `PUT /api/integrations/slack/channels/project/{projectId}` -- set project channel mapping
- Configuration:
  ```yaml
  agent.integrations.slack:
    enabled: false
    client-id: ${SLACK_CLIENT_ID:}
    client-secret: ${SLACK_CLIENT_SECRET:}
    signing-secret: ${SLACK_SIGNING_SECRET:}
    redirect-uri: ${SLACK_REDIRECT_URI:http://localhost:8080/api/integrations/slack/oauth/callback}
    dashboard-url: ${DASHBOARD_URL:http://localhost:8080}
    bot-scopes: "chat:write,chat:write.public,channels:read,groups:read"
    max-retries: 3
    retry-delay-ms: 1500
  ```

### 8. JIRA/Confluence Context Extraction

**Purpose:** When a user mentions JIRA issue keys or Confluence page URLs in a run request, automatically fetch the referenced content and inject it into the planner's input -- giving the AI full context without manual copy-paste.

**Requirements:**
- Runs before the PlannerAgent in the pipeline (after workspace init, before planning)
- Pattern detection:
  - JIRA issue keys: `[A-Z][A-Z0-9]{1,9}-\d{1,6}` (e.g., PROJ-123)
  - JIRA browse URLs: `https://example.atlassian.net/browse/PROJ-123`
  - Confluence page URLs: `/wiki/spaces/.../pages/{id}` or `?pageId={id}`
- Fetches content using org-level credentials (not IntegrationProperties beans):
  - JIRA: REST API `/rest/api/3/issue/{key}` -- extracts summary, description (with ADF text extraction), status, acceptance criteria
  - Confluence: REST API `/wiki/rest/api/content/{id}?expand=body.storage` -- strips HTML, truncates to 4000 chars
- Vague mention detection: "jira ticket", "confluence page" without specific references -> opens clarification gate
- Clarification gate: Redis-backed (same pattern as plan gate), pipeline thread blocks until user provides specific reference or skips
- Non-fatal: all fetch failures log warning and continue with original request unchanged
- Enriched request format: original text + `\n\n---\n## Extracted Reference Context\n` + fetched content
- SSE events: `CLARIFICATION_NEEDED`, `CLARIFICATION_RESOLVED`, `CONTEXT_EXTRACTED`
- Frontend: ClarificationBanner on RunDetail page -- text input + Submit/Skip buttons, shown when `status === 'AWAITING_CLARIFICATION'`
- Endpoint: `POST /api/runs/{id}/clarify` -- accepts `{response: string}`, resolves clarification gate

### 9. Org-Level Integration Credential Management

**Purpose:** Integration credentials (JIRA/Confluence/Slack) are managed at the org level by ORG_ADMIN users, not per-user in Settings.

**Requirements:**
- JIRA/Confluence credentials stored on the `org` table (base_url, email, api_token for each)
- Integration enable/disable is determined by whether org has credentials configured (not application.yml flags)
- Only ORG_ADMIN and SUPER_ADMIN can manage integration credentials
- Credential management endpoints:
  - `PUT /api/org/me/settings/jira` -- save JIRA base URL, email, API token
  - `PUT /api/org/me/settings/confluence` -- save Confluence base URL, email, API token
  - `POST /api/org/me/settings/jira/validate` -- test JIRA connection (HTTP GET to /rest/api/2/myself)
  - `POST /api/org/me/settings/confluence/validate` -- test Confluence connection (HTTP GET to /wiki/rest/api/user/current)
- OrgDto exposes boolean flags (`hasJiraCredentials`, `hasConfluenceCredentials`) + base URLs -- never raw tokens
- `IntegrationStatusController` derives enabled integrations from org credentials:
  - Org users: checks org-level credentials
  - SUPER_ADMIN without org: falls back to IntegrationManager (application.yml)
  - No org, not super admin: no integrations

### 10. Integration UI

**Purpose:** Frontend page for configuring integrations, viewing status, and manually triggering runs.

**Requirements:**
- SVG brand logos for each integration (Jira, Confluence, ServiceNow, PagerDuty, Slack)
- Interactive integration cards with org-level credential management:
  - Not Configured: "Configure" button opens inline credential form
  - Configured: shows "Enabled" badge, "Edit" and "Disconnect" buttons
  - Only ORG_ADMIN / SUPER_ADMIN can edit -- developers see read-only status
- Credential form: Base URL + Email + API Token + "Test Connection" + "Save" buttons
- Test Connection validates credentials before save (calls /validate endpoint)
- Disconnect clears credentials (saves empty values)
- Users without an org see a warning directing them to create one
- Access token setup guide panel with per-integration instructions (API token URLs, required scopes)
- Manual trigger forms:
  - Jira: enter issue key + optional goal override, trigger run from issue
  - Confluence: enter page ID + optional goal override, trigger run from page content
- Integration runs table showing recent integration-triggered runs

---

## Key Files

| File | Purpose |
|------|---------|
| `integration/IntegrationManager.java` | Webhook routing, manual triggers, listens for `MergeCompleted` event |
| `integration/InboundAdapter.java` | Inbound interface |
| `integration/OutboundAdapter.java` | Outbound interface |
| `integration/jira/*.java` | Jira adapter (null-safe review handling) |
| `integration/confluence/*.java` | Confluence adapter (null-safe review handling) |
| `integration/servicenow/*.java` | ServiceNow adapter |
| `integration/pagerduty/*.java` | PagerDuty adapter |
| `integration/github/GitHubRepoService.java` | GitHub repo create/push, `getDefaultBranch()`, `createPullRequest()` |
| `tool/GitTool.java` | Workspace git ops, `pushToBranch()` for existing-repo branch + push |
| `integration/slack/SlackClient.java` | HTTP client for Slack Web API (chat.postMessage, conversations.list, auth.test) |
| `integration/slack/SlackOAuthService.java` | OAuth 2.0 flow -- install URL generation, callback handling, HMAC state validation |
| `integration/slack/SlackNotificationService.java` | @EventListener bridge -- maps RunEvent to Slack messages, channel routing, verbosity filtering |
| `integration/slack/SlackMessageBuilder.java` | Block Kit message construction for all event types |
| `integration/slack/SlackRateLimiter.java` | Per-workspace rate limiting (1.1s minimum interval) |
| `integration/slack/SlackNotificationLogCleanupJob.java` | Scheduled cleanup of notification logs older than 30 days |
| `integration/slack/entity/OrgSlackIntegration.java` | Org <-> Slack workspace connection (team_id, access_token, verbosity, default_channel) |
| `integration/slack/entity/ProjectSlackChannel.java` | Project <-> Slack channel mapping |
| `integration/slack/entity/SlackNotificationLog.java` | Notification delivery log for audit + debugging |
| `integration/context/ReferenceExtractorService.java` | Scans run requests for JIRA keys / Confluence URLs, fetches content, handles clarification |
| `integration/context/ExtractionResult.java` | Result record (enriched / unchanged / needsClarification) |
| `api/controller/SlackIntegrationController.java` | Slack OAuth, status, channels, test message endpoints |
| `api/controller/UserRunsController.java` | Per-user run summary for dashboard |
| `state/entity/IntegrationRun.java` | External trigger linkage |
| `state/entity/IntegrationEventLog.java` | Webhook deduplication |
| `api/controller/IntegrationController.java` | Webhook + manual trigger endpoints |
| `api/controller/IntegrationStatusController.java` | Status endpoint (derives from org credentials) |
| `auth/controller/OrgController.java` | JIRA/Confluence credential CRUD + validation endpoints |
| `auth/service/OrgService.java` | `getEnabledIntegrations(orgId)` + credential update methods |
| `auth/entity/Org.java` | JIRA/Confluence credential columns (base_url, email, api_token) |
| `frontend/src/components/IntegrationLogo.tsx` | SVG brand logos for integrations (including Slack) |
| `frontend/src/pages/IntegrationsPage.tsx` | Integration management UI with credential forms + Slack OAuth |
