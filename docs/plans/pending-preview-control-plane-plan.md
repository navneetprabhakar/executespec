# Preview Control Plane (Dedicated VM Pool)

> Status: **design draft** — 2026-04-12. Replaces single-box Docker preview with a scheduler + agent topology so preview resources scale independently from the app.

## Why

Current state:
- App node runs Docker Compose directly (`deployment/preview/PreviewService` → local daemon).
- Every preview container contends for the same host resources as the Spring Boot API, test execution, and build subprocesses.
- Preview restarts when the app redeploys.
- Horizontal scaling of the app means horizontal scaling of previews too — wasteful.

Dedicated Preview VMs (already provisioned — see `preview/vm/*`) give us the base, but the scheduling + lifecycle are still app-local. The goal is to promote that to a proper control-plane / data-plane split.

## Topology

```
     ┌───────────────────────────┐      ┌───────────────────────────────┐
     │   App node (N replicas)   │      │   Preview VM pool (M VMs)     │
     │                           │      │                               │
     │  PreviewScheduler  ───────┼──────┼──► PreviewAgent (daemon)      │
     │    (picks VM)             │ mTLS │      ├─ Docker Engine         │
     │    (persists session)     │ gRPC │      ├─ Caddy (TLS)           │
     │                           │      │      └─ /opt/preview-agent    │
     │  Postgres: preview_*      │      │                               │
     │  Redis: hot state + lease │      │  Prometheus node-exporter     │
     └───────────────────────────┘      └───────────────────────────────┘
```

## Components

### App side (control plane)
- **`PreviewScheduler`** — replaces direct Docker invocations in `PreviewService`.
  - Picks a VM (least-loaded, respecting `preview.vm.vmList[].maxConcurrent` already in config).
  - Calls `PreviewAgent` via gRPC: `Start`, `Stop`, `Status`, `Logs`, `Exec`.
  - Writes an authoritative `preview_session` row (Postgres) before the RPC. Idempotent via `runId` PK.
- **`PreviewLeaseService`** — Redis-backed lease keyed by `runId`, 2× TTL, renewed every 30s. Fencing token included in every agent RPC so stale app replicas can't override a newer session.
- **`PreviewGarbageCollector` (existing)** — promoted from in-memory to SQL-driven. Queries `preview_session WHERE expiresAt < now() AND status = ACTIVE`, asks the owning agent to tear down.

### VM side (data plane)
- **`PreviewAgent`** — small Go binary (or Java if we want homogeneity) deployed to each VM via the existing `preview-engine` repo's deploy scripts.
  - gRPC server on port `:9440`, mTLS only (cert issued from app CA).
  - Methods:
    - `Start(req) → {containerId, externalPort, healthUrl}`
    - `Stop(runId, fencingToken)`
    - `Status(runId) → {state, uptime, lastHealth}`
    - `Logs(runId, tail) → stream<line>` (replaces SSH log tail)
    - `Exec(runId, cmd, stdin) → {stdout, stderr, exit}` (replaces `PreviewTab`'s REPL)
  - Locally owns: Docker, Caddy config writer, health prober, log retention.
- **Caddy** — unchanged (wildcard cert, dynamic site config).
- **Prometheus node-exporter** — scraped by app-side for VM utilization → feeds scheduler decisions.

## State model

```
preview_session
  run_id              (PK)
  vm_id               (FK preview_vm)
  container_id
  status              (PENDING|STARTING|ACTIVE|EXPIRING|STOPPED|FAILED)
  external_url
  external_port
  fencing_token       (monotonic int per run_id)
  started_at
  expires_at
  last_health_at

preview_vm
  vm_id               (PK)
  host
  region
  capacity            (maxConcurrent)
  active_count        (updated by scheduler)
  status              (HEALTHY|DRAINING|DOWN)
```

Same tables work for local-Docker mode if `vm_id` has a distinguished `local` row — keeps dev parity.

## Migration path

**Phase 1 — single VM, gRPC replaces SSH.**
- Stand up one VM with `PreviewAgent`.
- Add `preview.mode: "vm-grpc"` alongside existing `vm` (SSH) mode.
- Implement `PreviewScheduler` for the single-VM case; existing `PreviewProperties.vm.*` config is reused.
- Deployment: dev env only.

**Phase 2 — pool of 2-3 VMs.**
- Enable scheduler VM selection (random initially, least-loaded once Prometheus is scraped).
- Move `PreviewGarbageCollector` to SQL-based.
- Canary behind `preview.mode: "vm-grpc"` for 25% of runs; Docker mode remains default for the rest.

**Phase 3 — remove Docker-mode for dev/prod.**
- Keep Docker-mode only for local developer loops.
- Retire SSH-based VM driver.

## Open questions
- Container image build: stay on VM (current) or move to Kaniko-in-agent? Leaning stay-on-VM; Kaniko adds complexity without a clear win at our scale.
- gRPC vs plain REST? gRPC wins for streaming logs; REST might be fine for everything else. Default to gRPC.
- Region affinity: do we care? For now no — single region (us-east4) already.

## Metrics
- `executespec.preview.schedule.latency` (p50/p95/p99 app → agent)
- `executespec.preview.vm.utilization{vm_id}` (gauge, 0..1)
- `executespec.preview.lease.takeovers` (counter — should stay at 0 in healthy ops)
- `executespec.preview.agent.rpc{method,status}` (counter)

## Security
- mTLS between app and agent — cert rotation via Cloud KMS + a 24h ca-cert cache.
- Agent refuses any RPC without a valid fencing token ≥ the one it currently holds for that `run_id`.
- Workspace SCP superseded by gRPC `UploadChunk` stream — eliminates SSH key management on app side.
- Network policy: agent only exposes `:9440` to the app VPC; public traffic goes through Caddy on `:443`.
