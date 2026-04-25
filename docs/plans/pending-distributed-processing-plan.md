# Distributed Run Processing & File Handling — Execution Plan

> Status: draft for review (2026-04-13). Depends on: [`partial-work.md`](partial-work.md) §3 Gap 2 (full StartupRunResumerV2 replay; consolidated from the former `harness-v2-remaining-work.md`).
> Preview scheduling is scoped separately in [`pending-preview-control-plane-plan.md`](pending-preview-control-plane-plan.md) and not covered here.

## Goal

Move ExecuteSpec from a single-VM run executor to a horizontally-scalable cluster without dropping in-flight runs on deploys or node failures. Ship in two phases: **A** decouples storage; **B** distributes compute.

## Non-goals

- Not parallelising tasks within a run. V2 stays sequential-by-design.
- Not re-implementing previews — that's its own plan.
- Not migrating Postgres / Redis. Both are already external; they scale fine.
- Not building a bespoke job queue. We reuse Postgres + Redis for lease coordination.

## Current state (baseline)

- Workspace: `./workspace/<runId>/` on local disk of the one GCE VM running the Spring Boot app.
- GCS archive exists (`executespec-workspaces`), flag-disabled (`gcs-enabled: false`).
- Run execution: single JVM runs the full pipeline — planner, worker, reviewer, test-gen — plus test-exec Docker and (in local-mode) preview Docker.
- State: Postgres for runs/tasks/audit; Redis for feature flags, gate coordination, `ContextStoreV2` worker snapshots (every 5 iterations, 24h TTL), Shedlock.
- No run lease. `PipelineRun.status` is the only ownership signal. A second JVM pointing at the same DB would race.
- Crash recovery: V1's `StartupRunResumer` exists (currently disabled per CLAUDE.md); V2 has no resumer (Gap 2).

### Failure modes today

| Event | Outcome |
|---|---|
| JVM crash mid-run | Run stuck in `WORKING`. Manual "resume" via UI. Context in Redis — not replayed today. |
| Rolling deploy (SIGTERM) | Same — run dies with the JVM. No graceful handoff. |
| Workspace disk full | Runs fail with IO errors. No auto-cleanup beyond time-based retention. |
| CPU contention (>3 concurrent heavy runs) | Latency degrades linearly; tier quota is the only throttle. |
| Disk I/O contention | Worst on parallel writes; not measured today. |

## Architecture target

Two phases, each independently deployable + revertable.

### Phase A — Decouple storage (single compute VM retained)

```
┌──────────────────────────────────────────────┐
│ Single VM (today's deployment)               │
│                                              │
│   JVM ─┬─ planner / worker / reviewer        │
│        ├─ test exec                           │
│        └─ preview docker (or Cloud Run)       │
│                                              │
│   writes to: ◀── Filestore mount (NFS)       │
│              /mnt/workspaces/<runId>/        │
└──────────────────────────────────────────────┘
              │
              ▼
       Filestore (GCP regional NFS)
       10 GB baseline, auto-grow to 1 TB
              │
              ▼  (archive job, unchanged)
       GCS bucket: executespec-workspaces
       (retention: 30 days on regional Filestore,
        ∞ on GCS nearline)
```

Deliverables:
- All `writeFile`/`readFile`/`listFiles`/git ops continue to use `java.nio.file.Path`. No code change in tools — they already are path-agnostic.
- Filestore replaces local disk for `<agent.workspace>`. Config flip only.
- Archive lifecycle: on run terminal state, compress + upload to GCS; delete from Filestore after 24h (or keep live for suggested-apply window).
- Checkpointable: workspace state is on Filestore, survives the JVM.

Why Filestore over GCS:
- Every V2 tool uses `Path`; swapping in GCS means either a `gcsfuse` mount (fragile, corruption risk) or rewriting 10 tools to talk object APIs. Filestore is drop-in.
- Cost at scale: 100 GB hot workspaces = ~$30/mo on Filestore vs ~$2/mo on GCS. Trivial vs engineering cost of the object rewrite. Cost bite is ~$300/mo at 1 TB; revisit then.
- Filestore Enterprise tier gives regional HA (9s) out of the box.

### Phase B — Worker pool with lease-based run assignment

```
┌──────────────────────────────────────────────┐
│ Gateway (Cloud Run / GCE, stateless)         │
│   /api/runs, /api/auth, /api/billing, SSE    │
│   no run execution here                      │
└──────────────────────────────────────────────┘
              │  (POST /runs)
              ▼
     Postgres: pipeline_run row created
                run_lease row unset
              │
              ▼
┌──────────────────────────────────────────────┐
│ Worker pool (N x GCE VMs or Cloud Run jobs)  │
│                                              │
│   every 2s: try to acquire a free lease      │
│     SELECT runId FROM pipeline_run           │
│     WHERE status IN (QUEUED, RESUMABLE)      │
│     AND NOT EXISTS (lease WHERE heartbeat    │
│                     > now() - 30s)           │
│     LIMIT 1 FOR UPDATE SKIP LOCKED           │
│                                              │
│   on acquire: INSERT run_lease, start heartbeat │
│   on run terminal / SIGTERM / crash:         │
│     DELETE run_lease (or let TTL expire)     │
│                                              │
│   all file ops → Filestore mount             │
└──────────────────────────────────────────────┘
              ▲
              │ every 10s
              ▼
      run_lease.heartbeat_at
```

Deliverables:
- **Postgres-backed lease** on every active run, 30s TTL, 10s heartbeat.
- **FOR UPDATE SKIP LOCKED** for assignment (battle-tested pattern).
- **Graceful shutdown**: SIGTERM → stop picking up new runs → finish current iteration → flush checkpoint → release lease. Max grace 2 min.
- **Pool sizing**: initial 3 workers behind an unmanaged instance group in GCE, each 4 vCPU / 16 GB. Scale rule: `active_runs / pool_size < 2`. Add a worker at 2, remove at 0.5.
- **Gateway is stateless**: any pod can serve the HTTP API. SSE streams subscribe to Redis pub/sub; the worker pod pushes events, any gateway relays them.

## Checkpoint + recovery

Already foundational: `ContextStoreV2` saves worker conversation every 5 iterations. Extend into a proper checkpoint subsystem:

### What is checkpointed

| State | Where today | Target |
|---|---|---|
| Worker conversation | Redis `v2:agent-ctx:{runId}:{taskId}` every 5 iterations | Same key; bump to every iteration during active work; force-flush on SIGTERM |
| Task cursor (which task of the plan) | `PipelineRun.status` + `AgentTask.status` | Same; already durable |
| Workspace files | Filestore (post-A) | Filestore; optionally tag `checkpoint/<runId>/iter<N>` via `git tag` every 10 iters for replayable snapshots |
| Cost tracker | In-memory on the JVM | Flush to `run_cost_log` every iteration; makes recovery + billing consistent |
| SSE listeners | In-memory per pod | Move to Redis pub/sub topic `run:{runId}:events` — any pod can subscribe |

### Checkpoint granularity: **per iteration**, not per tool call

- Per-tool-call: 5-10× Redis writes, tiny recovery gain, partial-side-effect risk (half-applied editFile batch).
- Per-task: loses up to a full task's LLM work on crash.
- Per-iteration: sweet spot. ≤1 iter lost on crash, 0 lost on graceful deploy.

Tool semantics on resume:
- `writeFile`, `editFile`, `gitCommit`, `listFiles`, `readFile` — safe to re-execute (idempotent or read-only).
- `shell` — NOT idempotent. Mark iterations that invoked `shell` as "replay-from-iteration-start"; rerun the shell call. In practice this is rare enough (mostly build invocations).

### Recovery flow (StartupRunResumerV2, Gap 2)

```
On boot or on lease acquisition:
  for run in runs with status ∈ {PLANNING, WORKING, REVIEWING, AWAITING_APPROVAL}:
    if AWAITING_APPROVAL:
      re-arm GateStore.awaitPlanGate()        # no replay
      continue

    snapshot = contextStore.load(runId, currentTaskId)
    if snapshot is null (TTL expired, > 24h old):
      mark run FAILED reason="recovery snapshot expired"
      continue

    optional: git reset --hard checkpoint/<runId>/iter<N>
    worker.resumeFromIteration(snapshot.history, snapshot.iter)
    orchestrator continues from post-worker path
```

### Deploy strategy

1. Rolling deploy triggers.
2. Cloud deploy controller sends SIGTERM to one pod.
3. Pod's Spring `ApplicationShutdownHook` calls `V2RunOrchestrator.drain()`:
   - Stop accepting new run assignments (`leaseAcquirer.pause()`).
   - Current run's `WorkerAgentV2` finishes the in-flight iteration; on iteration boundary, forces `contextStore.save()`.
   - Releases the lease.
4. Pod exits within the 2-min grace window.
5. New pod comes up, `StartupRunResumerV2` scans + resumes any run whose lease expired or was released.

Worst case — pod killed ungracefully (OOM, node evict): lease TTL (30s) expires → another pod picks up → replays from last checkpoint (≤1 iter lost).

### Fencing (prevents split-brain)

Lease row includes a `fencing_token` (monotonic bigint). Every `PipelineRun` / `AgentTask` mutation goes through a stored procedure that asserts `lease.fencing_token == incoming.token`. If a node lost its lease while writing, its update fails — safer than last-write-wins.

## Phased execution

### Phase A — Shared storage (3 PRs)

- **A1 — Filestore provisioning** (ops, low risk)
  - Terraform: regional Filestore instance, 1 TB standard, VPC-peered.
  - GCE VM: mount `/mnt/workspaces`. Add `systemd` unit + health check.
  - Flag `agent.workspace=/mnt/workspaces`; fall back to local if mount missing.
- **A2 — Workspace archival hardening** (medium risk)
  - Enable `gcs-enabled: true`.
  - On run terminal: zip + upload workspace to GCS.
  - Cleanup job: delete Filestore workspace after 24h (configurable).
  - Download-zip endpoint prefers Filestore, falls back to GCS redirect (existing behaviour for older runs).
- **A3 — Smoke + cost observation** (no code)
  - Run full eval matrix on Filestore.
  - Compare p50/p95 tool latency before/after. Acceptable: ≤20% regression (NFS overhead).

### Phase B — Worker pool + lease (5 PRs)

- **B1 — Lease table + heartbeat service** (low risk, no behaviour change)
  - Flyway `V81__run_lease.sql`.
  - `RunLeaseService` with acquire / heartbeat / release / fencing.
  - Single JVM still; just prove the lease round-trips work. Every active run gets a lease.
- **B2 — StartupRunResumerV2** (medium risk — Gap 2 delivery)
  - On boot: scan expired-lease runs, attempt resume.
  - Wire `ContextStoreV2.load()` → `WorkerAgentV2.resumeFromIteration()`.
  - Iteration-boundary save: bump `CONTEXT_PERSIST_EVERY_N_ITERATIONS` to 1 during active work; tiered (every 5 at idle, every 1 at high-change).
- **B3 — Graceful shutdown + drain** (medium risk)
  - `ApplicationShutdownHook` → `V2RunOrchestrator.drain()`.
  - Integration test: kill the JVM during a run, verify pickup by another pod.
- **B4 — Multi-pod deployment** (high risk, gated on B1–B3 soak)
  - Terraform: MIG with 3 workers.
  - Gateway stays separate (Cloud Run; stateless).
  - SSE via Redis pub/sub (`run:{runId}:events`).
  - Canary 1 pod for 48h; expand to 3 once clean.
- **B5 — Autoscaling rule** (low risk)
  - Custom metric: `executespec.v2.active_runs`.
  - Scale rule: `active_runs / pool_size ∈ [0.5, 2]`.
  - Test scaling up and down manually before turning on the policy.

### Phase C — Observability & runbooks (1 PR)

- Dashboards: lease occupancy, resume count, iteration-save latency, Filestore p95 read/write, checkpoint replay outcome.
- Runbook: "What to do when a run is stuck WORKING for > 30min with no heartbeat."
- Alerts: lease-stealing spikes, resume-from-snapshot failure rate, workspace quota near full.

## Test strategy

- **Unit** — lease acquire/expire race; fencing token reject; checkpoint replay.
- **Integration** — submit a run, kill the pod mid-iteration, watch another pod resume it, assert same outcome as an uninterrupted run (same plan, same tasks, near-identical files).
- **Chaos** — randomised SIGTERM during eval matrix runs. No data loss permitted.
- **Load** — 10 concurrent runs on 1 worker (expect queuing); 10 runs on 3 workers (expect full parallelism).
- **Filestore failure** — unmount during a run; confirm graceful failure with clear message (not data corruption).

## Risks

| Risk | Mitigation |
|---|---|
| NFS latency slows workers measurably | A3 is an explicit benchmark gate; if >20% regression, revert to local disk + build a GCS sync layer instead |
| Lease-steal races | FOR UPDATE SKIP LOCKED + fencing token; integration test with deliberate race |
| Shell-tool replay corrupts state on resume | Mark shell iterations non-idempotent; replay rolls back workspace to last tag and reruns the iteration |
| Redis eviction of checkpoint snapshot before resume | 24h TTL today; extend to 48h for runs longer than 4h; never evict via maxmemory (use noeviction policy on that key prefix) |
| Zombie pod retains in-memory state after lease loss | Fencing token makes writes fail-safe; operator runbook includes `kill -9` as last resort |
| Autoscaler thrashes | Scale rule uses 5-min windows; cooldown 10 min between scale events |
| First multi-pod deploy surfaces hidden singleton assumptions | Grep for `@Component` + `static` state pre-B4; add `@DistributedSingleton` marker for any that must be global (e.g. `BatchPollService`) |

## Cost envelope

- Filestore 1 TB: ~$300/mo
- 3× GCE n2-standard-4 workers (24/7): ~$400/mo
- Gateway on Cloud Run, usage-based: ~$50/mo at current traffic
- GCS archive (nearline): ~$10/mo per TB
- Total delta vs today (~$120/mo for one VM): **~+$650/mo**

Headroom: supports ~30 concurrent runs (3 workers × 10 concurrent each). Current peak is ~5.

## Files that will change

### New
- Terraform: `infra/filestore.tf`, `infra/workers-mig.tf`, `infra/gateway-cloudrun.tf`
- `com.navneet.executespec.v2.lease.RunLeaseService`, `RunLease` entity, `LeaseAcquirer` (scheduler)
- `com.navneet.executespec.v2.recovery.StartupRunResumerV2`
- `com.navneet.executespec.v2.orchestration.GracefulDrainService`
- Flyway: `V81__run_lease.sql`, `V82__run_lease_fencing.sql`
- `application.yml`: `agent.workspace`, `distributed.*` profiles
- `config/DistributedModeConfig.java` (feature-flag-gated wiring)

### Modified
- `V2RunOrchestrator.java` (drain hook, lease integration, cost flush per iteration)
- `WorkerAgentV2.java` (iteration-boundary save, resumeFromIteration)
- `ContextStoreV2.java` (configurable save cadence; noeviction key prefix)
- `SseBroadcaster.java` (Redis pub/sub backend)
- `RunController.java` (lease status exposed on detail?)
- `CLAUDE.md`, `docs/USER_MANUAL.md`, CHANGELOG, VERSION

Estimated size: ~3000 LOC new, ~500 modified, plus ~400 lines of Terraform.

## Suggested commit / PR boundaries

| PR | Content | Gate |
|---|---|---|
| **#1** | A1 Terraform + Filestore mount | Ops review |
| **#2** | A2 archival hardening | Eval green on shared storage |
| **#3** | A3 benchmark report | No code — sign-off |
| **#4** | B1 lease table + service | Single-pod, no behaviour change |
| **#5** | B2 StartupRunResumerV2 + iteration checkpoint | Chaos test passes |
| **#6** | B3 graceful drain + SIGTERM hook | Integration test passes |
| **#7** | B4 multi-pod deploy | Canary 48h clean |
| **#8** | B5 autoscaler | Scale-event observability |
| **#9** | Phase C runbook + dashboards | Ops accept |

## Exit criteria

- [ ] Workspace is on Filestore; VMs are stateless from the filesystem's perspective.
- [ ] SIGTERM kills no run. Zero data loss over a 100-run chaos test.
- [ ] Multi-pod deploy is the default. Old single-VM topology is archived in Terraform.
- [ ] Autoscaler responds to load inside the 5-min window.
- [ ] Dashboards show lease / resume / checkpoint health.
