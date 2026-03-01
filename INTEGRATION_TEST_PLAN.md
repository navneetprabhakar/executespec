# Tier 7 Integration — E2E Test Plan

This plan covers each integration independently. Run only the section(s) relevant to your test.

---

## Prerequisites

1. PostgreSQL running and reachable (`jdbc:postgresql://localhost:5432/agentteam`).
2. App built: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile`
3. A public URL for inbound webhooks. Use [ngrok](https://ngrok.com/) if testing locally:
   ```bash
   ngrok http 8080
   # Note the forwarding URL, e.g. https://abc123.ngrok-free.app
   ```
4. A working Anthropic API key already configured in `application.yml`.

---

## Flag Architecture

```
agent.integrations.enabled: true         # Master switch (registers /api/integrations/* endpoints)
agent.integrations.jira.enabled: true    # Loads JiraClient + JiraAdapter beans
agent.integrations.confluence.enabled:   # (same pattern for each integration)
agent.integrations.servicenow.enabled:
agent.integrations.pagerduty.enabled:
```

- Master `enabled: false` → no endpoints, no cleanup job, no outbound calls. App runs normally.
- Master `enabled: true` + individual `enabled: false` → endpoints registered but that adapter returns "unknown source".
- Both `true` → fully operational for that integration.

---

## Step 0 — Baseline: Confirm integrations OFF by default

**Config** (default, nothing to change):
```yaml
agent:
  integrations:
    enabled: false
```

**Steps:**
1. Start app: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run`
2. Verify endpoints are NOT registered:
   ```bash
   curl -s http://localhost:8080/api/integrations/status
   # Expected: 404 (no such endpoint)
   ```
3. Submit a normal run and confirm it works end-to-end:
   ```bash
   curl -s -X POST http://localhost:8080/api/runs \
     -H "Content-Type: application/json" \
     -d '{"userRequest":"Write a hello world Python script","sourceType":"MANUAL"}'
   # Expected: 202 with runId
   ```
4. Check run detail has no `integrationSource`:
   ```bash
   curl -s http://localhost:8080/api/runs/{runId}
   # Expected: integrationSource: null
   ```

**Pass criteria:** App boots, normal runs work, no `/api/integrations/*` endpoints respond.

---

## Step 1 — Master switch ON (no individual adapters)

**Config change** (`application.yml`):
```yaml
agent:
  integrations:
    enabled: true      # <-- change this
    jira:
      enabled: false   # all others remain false
```

**Steps:**
1. Restart app.
2. Verify status endpoint responds:
   ```bash
   curl -s http://localhost:8080/api/integrations/status
   # Expected: {"enabledSources":[]}
   ```
3. Verify webhook for unknown source returns 400:
   ```bash
   curl -s -X POST http://localhost:8080/api/integrations/jira/webhook \
     -H "Content-Type: application/json" \
     -d '{}'
   # Expected: {"error":"Unknown integration source: JIRA"}  (400)
   ```
4. List integration runs (empty):
   ```bash
   curl -s http://localhost:8080/api/integrations/runs
   # Expected: []
   ```

**Pass criteria:** Endpoints live, empty adapter list, unknown-source webhook returns 400.

---

## Step 2 — Jira E2E

### 2a. Prerequisites
- Jira Cloud instance (free tier works): `https://your-org.atlassian.net`
- API token: https://id.atlassian.com → Security → API tokens
- A project with a status named `"Ready for Coding"` (or customize `trigger-status`)
- An existing issue to test with

### 2b. Config change
```yaml
agent:
  integrations:
    enabled: true
    jira:
      enabled: true
      base-url: https://YOUR-ORG.atlassian.net
      email: your@email.com
      api-token: YOUR_JIRA_API_TOKEN
      webhook-secret: my-test-secret-123    # you'll set same value in Jira
      trigger-status: "Ready for Coding"
      in-progress-status: "In Progress"
      in-review-status: "In Review"
      reopen-status: "Backlog"
      dashboard-url: https://abc123.ngrok-free.app   # your ngrok URL
      skip-approval: true                            # auto-approve plan gate for tests
```

### 2c. Register webhook in Jira
1. Jira → Settings → System → WebHooks → Create.
2. URL: `https://abc123.ngrok-free.app/api/integrations/jira/webhook`
3. Events: **Issue → updated**
4. JQL filter (optional): `project = YOUR_PROJECT`
5. Secret: `my-test-secret-123`
6. Save.

### 2d. Test steps
1. Restart app, confirm Jira adapter is loaded:
   ```bash
   curl -s http://localhost:8080/api/integrations/status
   # Expected: {"enabledSources":["JIRA"]}
   ```
2. Open a Jira issue and transition it to **"Ready for Coding"**.
3. Watch app logs — you should see within ~5s:
   ```
   [Integration] Run <runId> submitted for JIRA event <issue-key>
   ```
4. Confirm the run was created:
   ```bash
   curl -s http://localhost:8080/api/integrations/runs?source=JIRA
   # Expected: [{runId, sourceType: "JIRA", sourceId: "<issue-key>", ...}]
   ```
5. Confirm comment added to Jira issue:
   - Look for: `🤖 Automated analysis started (Agent Run: ...)`
6. Confirm issue transitioned to **"In Progress"** (if configured).
7. Wait for pipeline to complete (PLANNING → WORKING → REVIEWING → AWAITING_MERGE).
8. For `skip-approval: true` runs: approve merge at REST:
   ```bash
   curl -s -X POST http://localhost:8080/api/runs/{runId}/merge \
     -H "Content-Type: application/json" \
     -d '{"approved":true}'
   ```
9. Confirm completion comment added to Jira issue:
   - Look for: `🤖 Automated Analysis Complete...`
10. Confirm issue transitioned to **"In Review"**.
11. Test idempotency — transition the same issue again:
    ```bash
    # Jira resends the webhook. Check app logs:
    # [Integration] Duplicate event <eventId> from JIRA — skipped
    ```

### 2e. Test failure path
1. Set `skip-approval: false`, restart app.
2. Transition issue to "Ready for Coding".
3. Run is created with plan gate open — do NOT approve it.
4. Let it time out (24h gate) OR cancel it:
   ```bash
   curl -s -X DELETE http://localhost:8080/api/runs/{runId}
   ```
5. Confirm Jira issue gets **"In Review"** transition skipped and `❌ Automated run failed` comment added.

**Pass criteria:** Webhook received → run created → Jira comments updated at start/complete → transitions happen → idempotency block on replay.

---

## Step 3 — Confluence E2E

### 3a. Prerequisites
- Confluence Cloud instance
- API token (same Atlassian account as above)
- A space key (e.g. `ENG`)
- A page with the label `agent-run`

### 3b. Config change
```yaml
agent:
  integrations:
    enabled: true
    confluence:
      enabled: true
      base-url: https://YOUR-ORG.atlassian.net
      email: your@email.com
      api-token: YOUR_CONFLUENCE_API_TOKEN
      trigger-label: "agent-run"
      space-key: "ENG"
      dashboard-url: https://abc123.ngrok-free.app
      skip-approval: true
```

### 3c. Register webhook in Confluence
1. Confluence → Settings → Audit Log → WebHooks (or via Atlassian admin).
   Alternative: Confluence Cloud supports webhooks via `https://YOUR-ORG.atlassian.net/wiki/rest/webhooks/1.0/webhook`.
2. URL: `https://abc123.ngrok-free.app/api/integrations/confluence/webhook`
3. Events: **Page created**, **Page updated**

### 3d. Test steps (webhook)
1. Restart app, confirm adapter loaded:
   ```bash
   curl -s http://localhost:8080/api/integrations/status
   # Expected: {"enabledSources":["CONFLUENCE"]}
   ```
2. Create or update a Confluence page and add the label `agent-run`.
3. Watch logs for: `[Integration] Run <runId> submitted for CONFLUENCE event <pageId>`
4. Confirm a child page was created under the original page after run completes.

### 3e. Test steps (manual trigger — no webhook needed)
This is useful for initial testing without setting up Confluence webhooks.
1. Find a page ID in Confluence (visible in the URL: `?pageId=12345`).
2. Trigger manually:
   ```bash
   curl -s -X POST http://localhost:8080/api/integrations/confluence/trigger \
     -H "Content-Type: application/json" \
     -d '{"pageId":"12345","goal":"Refactor the authentication module per this spec"}'
   # Expected: {"runId":"<uuid>"}
   ```
3. Monitor run progress via SSE:
   ```bash
   curl -N http://localhost:8080/api/runs/{runId}/events
   ```
4. After completion, check Confluence — a child page titled `Agent Run: <runId>` should appear.

**Pass criteria:** Webhook or manual trigger creates run → child output page created in Confluence.

---

## Step 4 — ServiceNow E2E

### 4a. Prerequisites
- ServiceNow developer instance (free at https://developer.servicenow.com)
- Instance URL: `https://YOUR-INSTANCE.service-now.com`
- Username/password with `itil` role (or admin for testing)
- An incident in category `application` or `database`

### 4b. Config change
```yaml
agent:
  integrations:
    enabled: true
    servicenow:
      enabled: true
      base-url: https://YOUR-INSTANCE.service-now.com
      username: ${SNOW_USERNAME}
      password: ${SNOW_PASSWORD}
      trigger-categories:
        - application
        - database
      auto-resolve: false
      dashboard-url: https://abc123.ngrok-free.app
      skip-approval: true
```

### 4c. Register webhook in ServiceNow
Create a Business Rule or REST Message outbound webhook:
1. Go to **Business Rules** → New
2. Table: `incident`
3. When: **After → Insert / Update**
4. Condition: `category IN ('application', 'database')`
5. Script:
   ```javascript
   var r = new sn_ws.RESTMessageV2();
   r.setEndpoint('https://abc123.ngrok-free.app/api/integrations/servicenow/webhook');
   r.setHttpMethod('post');
   r.setRequestHeader('Content-Type', 'application/json');
   r.setRequestBody(JSON.stringify({
     record: {
       sys_id: current.sys_id.toString(),
       number: current.number.toString(),
       short_description: current.short_description.toString(),
       description: current.description.toString(),
       category: current.category.toString(),
       kb_article: current.knowledge.toString()
     }
   }));
   r.execute();
   ```

### 4d. Test steps
1. Restart app, confirm adapter loaded:
   ```bash
   curl -s http://localhost:8080/api/integrations/status
   # Expected: {"enabledSources":["SERVICENOW"]}
   ```
2. Create or update a ServiceNow incident with category `application`.
3. Watch logs: `[Integration] Run <runId> submitted for SERVICENOW event INC0000001`
4. Check incident work notes — should contain `🤖 Automated analysis started...`
5. After run completes, check work notes for `🤖 Automated Analysis Complete...`
6. Test `auto-resolve: true` (optional):
   - Set `auto-resolve: true`, restart app.
   - After a completed run with MERGE recommendation, incident state should change to Resolved (state=6).

**Pass criteria:** Incident triggers run → work notes updated at start and complete.

---

## Step 5 — PagerDuty E2E

### 5a. Prerequisites
- PagerDuty account (free trial or paid)
- API token: PagerDuty → User menu → API Access → Create new API key (full access)
- `from-email`: the email address of the API key owner (required by PD for PATCH requests)
- A service + escalation policy set up

### 5b. Config change
```yaml
agent:
  integrations:
    enabled: true
    pagerduty:
      enabled: true
      api-token: ${PD_API_TOKEN}
      webhook-secret: my-pd-webhook-secret
      from-email: your@email.com
      trigger-types:
        - incident.trigger
      auto-resolve: false
      dashboard-url: https://abc123.ngrok-free.app
      skip-approval: true
```

### 5c. Register webhook in PagerDuty
1. PagerDuty → Integrations → Generic Webhooks (V3).
2. Webhook URL: `https://abc123.ngrok-free.app/api/integrations/pagerduty/webhook`
3. Events: **incident.trigger** (and optionally incident.acknowledge, incident.resolve)
4. After creating, copy the **signing secret** and set it as `webhook-secret` above.

### 5d. Test steps
1. Restart app, confirm adapter loaded:
   ```bash
   curl -s http://localhost:8080/api/integrations/status
   # Expected: {"enabledSources":["PAGERDUTY"]}
   ```
2. Trigger a new PagerDuty incident on your service.
3. Watch logs: `[Integration] Run <runId> submitted for PAGERDUTY event <incidentId>`
4. Check incident timeline in PagerDuty — should contain `🤖 Agent investigation started...`
5. After run completes, check incident notes for the Post-Incident Report.
6. Verify HMAC signature check:
   - Send a webhook with wrong/no secret header → should get `401 invalid_signature`.
   ```bash
   curl -s -X POST http://localhost:8080/api/integrations/pagerduty/webhook \
     -H "Content-Type: application/json" \
     -H "x-pagerduty-signature: v1=invalidsignature" \
     -d '{"messages":[]}'
   # Expected: {"error":"invalid_signature"}  (401)
   ```

**Pass criteria:** Incident triggers run → PD note at start → Post-Incident Report note on complete → HMAC check rejects tampered requests.

---

## Step 6 — Multi-integration smoke test

**Config:** Enable two integrations simultaneously, e.g. Jira + PagerDuty.

**Steps:**
1. Verify both appear in status:
   ```bash
   curl -s http://localhost:8080/api/integrations/status
   # Expected: {"enabledSources":["JIRA","PAGERDUTY"]}
   ```
2. Trigger a Jira issue transition and a PagerDuty incident within 10 seconds of each other.
3. Confirm two separate runs created, each appearing in `/api/integrations/runs`.
4. Confirm run detail includes correct `integrationSource` for each:
   ```bash
   curl -s http://localhost:8080/api/runs/{runId1} | jq .integrationSource
   # Expected: {"sourceType":"JIRA","sourceId":"PROJ-42","sourceUrl":"..."}
   ```

**Pass criteria:** Concurrent webhook runs proceed independently without interference.

---

## Step 7 — Verify clean disable

**Config:** After all tests, reset to defaults:
```yaml
agent:
  integrations:
    enabled: false
    jira:
      enabled: false
    confluence:
      enabled: false
    servicenow:
      enabled: false
    pagerduty:
      enabled: false
```

**Steps:**
1. Restart app.
2. Confirm `/api/integrations/status` returns 404.
3. Submit a normal REST run and confirm it works (no integration overhead).
4. Confirm existing `integration_run` rows are still in the DB (data not deleted on disable).

**Pass criteria:** App returns to clean state — no integration endpoints, normal pipeline unaffected.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `/api/integrations/status` returns 404 | Master flag is `false` | Set `agent.integrations.enabled: true` and restart |
| Webhook returns `{"error":"Unknown integration source: JIRA"}` | Individual `jira.enabled` is `false` | Set `agent.integrations.jira.enabled: true` and restart |
| Run created but no comment in Jira | `base-url`, `email`, or `api-token` wrong | Check env vars; test with `curl` against Jira REST API directly |
| PagerDuty webhook returns 401 | HMAC mismatch | Verify `webhook-secret` matches the signing secret shown in PD after webhook creation |
| Run stuck at AWAITING_APPROVAL | `skip-approval: false` for integration run | Set `skip-approval: true` to auto-approve plan gate, or manually approve via `/api/runs/{id}/approve` |
| No ngrok tunnel / webhook not reaching local app | ngrok session expired | Re-run `ngrok http 8080` and update webhook URLs in each system |
| ServiceNow PATCH fails with 401 | Wrong credentials | Ensure `username`/`password` are for a user with `itil` role |
