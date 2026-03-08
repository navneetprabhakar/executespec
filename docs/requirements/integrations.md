# External Integrations — Requirements

## Overview

Webhook-based integrations with Jira, Confluence, ServiceNow, and PagerDuty. Inbound webhooks trigger pipeline runs; outbound listeners publish run status back to source systems.

---

## Features

### 1. Integration Framework

**Requirements:**
- Master switch: `agent.integrations.enabled` (default: false)
- IntegrationManager routes webhooks to source-specific InboundAdapters
- InboundAdapter interface: verify signature, parse event, map to pipeline run
- OutboundAdapter interface: publish status updates back to source
- Deduplication via IntegrationEventLog (sourceType + eventId)
- Graceful handling of duplicate/ignorable events
- Per-source skip-approval configuration
- Endpoints: `POST /api/integrations/{source}/webhook`, `GET /api/integrations/status`

### 2. Jira Integration

**Requirements:**
- Trigger: Issue transitions to configured status (default: "Ready for Coding")
- Extract: issue summary + description as user request
- Status transitions:
  - Run started → "In Progress"
  - Run completed → "In Review"
  - Run failed → "Backlog"
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
- Update: add child page with run results link
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

---

## Key Files

| File | Purpose |
|------|---------|
| `integration/IntegrationManager.java` | Webhook routing |
| `integration/InboundAdapter.java` | Inbound interface |
| `integration/OutboundAdapter.java` | Outbound interface |
| `integration/jira/*.java` | Jira adapter |
| `integration/confluence/*.java` | Confluence adapter |
| `integration/servicenow/*.java` | ServiceNow adapter |
| `integration/pagerduty/*.java` | PagerDuty adapter |
| `state/entity/IntegrationRun.java` | External trigger linkage |
| `state/entity/IntegrationEventLog.java` | Webhook deduplication |
| `api/controller/IntegrationController.java` | Webhook endpoints |
| `api/controller/IntegrationStatusController.java` | Status endpoint |
