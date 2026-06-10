# ENTERPRISE SLA Reference

> Recreated 2026-06-10. This separates code-backed SLOs from commercial terms that must be negotiated.

## Code-Backed SLOs

The operational SLO source is `docs/operations/slos.md`.

| SLO | Target |
| --- | --- |
| Run terminal latency | 95% of runs terminal within 15 minutes |
| Verified-score correctness | >=90% correctness target |
| Cost estimate accuracy | within 30% on >=80% of runs |
| Webhook latency | 99% processed within 60 seconds |
| Hot-path 5xx | <0.5% |

Metrics are emitted through Micrometer/Prometheus with `executespec.v2.*` prefixes and Actuator endpoints.

## Commercial Terms

These are policy/legal terms, not automatic code behavior:

- monthly uptime percentage,
- service credits,
- support response times,
- named escalation contacts,
- maintenance-window notification,
- liability limits,
- force-majeure exclusions.

Do not treat this file as a signed contract. It is the engineering baseline for contract drafting.

## Availability Boundaries

Current runtime shape:

- primary deployment in GCP `us-east4`,
- GCE VM running the Spring Boot JAR,
- Cloud SQL PostgreSQL,
- Redis,
- Cloudflare in front of domains,
- Cloud Run for previews/deploy paths.

Known constraints:

- benchmark and preview environments are separate concerns,
- SAML is not end-to-end,
- customer-specific regions are not self-serve,
- full VM loss relies on deploy/runbook recovery, not transparent multi-region failover.

## Backups And Recovery

Source of truth for deploy/infra is `docs/DEPLOYMENT.md`. Operationally relevant code paths:

- `recovery/StartupRunResumer.java`
- `state/StateStore.java`
- `deployment/`
- `integration/RunOrchestrator.java`

Run recovery is best-effort around persisted state and workspace availability. Do not promise active-active failover.

## Incident Handling

Minimum engineering flow:

1. Confirm `/api/health` and `/api/health/ready`.
2. Check Actuator health/prometheus.
3. Inspect app logs and Cloud SQL/Redis status.
4. Confirm provider outage vs app outage for LLM failures.
5. Update customer-facing status only with confirmed impact.
6. Preserve run ids, trace ids, and payment/event ids for postmortem.

## Related Docs

- `docs/operations/slos.md`
- `docs/DEPLOYMENT.md`
- `docs/status-page.md`
- `docs/dpa-flow.md`
- `docs/RBAC.md`
