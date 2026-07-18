# Durable project exports

V289 replaces the request-thread `byte[]` export with a persisted job protocol. This package is the
only owner of project-export status, archive composition, publication, expiry and download audit.

## API and authorization

- `POST /api/projects/{projectId}/export` returns `202` plus a job DTO. `Idempotency-Key` is
  optional for backwards compatibility; Web always sends one. The same caller/key replays the
  original row, and the database permits only one active job per project.
- `GET /api/projects/{projectId}/export` and `GET .../{jobId}` list/status jobs. `POST
  .../{jobId}/cancel` is cooperative and idempotent. `GET .../{jobId}/download` streams the local
  or GCS object without browser cross-origin redirect/CORS dependence. `GET .../{jobId}/audit`
  returns the latest 100 lifecycle/download facts.
- Every handler calls `ProjectAccessGuard`; create/cancel require write access. The class-level role
  gate permits only host `ORG_ADMIN` or `SUPER_ADMIN`. `BILLING_ADMIN`, ordinary project members and
  cross-tenant identifiers cannot export project governance data.
- Controller errors are fixed messages/codes. Raw storage, SQL or archive exceptions never enter
  an HTTP response or persisted `error_code`.
- Missing scoped export jobs throw `ResponseStatusException(404)` directly. Do not rely on
  `@ResponseStatus` on a nested service exception: the controller advice path previously converted
  that case to a generic 500 (Phase-6 F15, fixed 2026-07-18).

## State and recovery

- `ProjectExportJobStore` owns `QUEUED -> RUNNING -> SUCCEEDED/FAILED/CANCELLED ->
  EXPIRING/EXPIRED`. Stale `RUNNING` rows are restart candidates. A monotonically increasing lease
  token fences completion, so a pre-restart JVM cannot publish after a takeover.
- `CANCEL_REQUESTED` rows with a live lease remain cooperative. If that worker/JVM disappears, the
  scheduler marks the row `CANCELLED` after lease expiry so it cannot block the project's active-job
  slot forever.
- Attempts are bounded and retry with a durable `next_attempt_at`. Daily project quota, one active
  export, a bounded worker pool and the unique indexes prevent request fan-out.
- Each attempt publishes to a token-specific object key. Completion records the key, byte size and
  SHA-256 only after the exact live lease wins. Expiry is two-phase: mark `EXPIRING`, delete the
  object, then clear the pointer and mark `EXPIRED`.
- `project_export_audit` records request/start/recovery/retry/completion/cancel/download/expiry. A
  database trigger rejects updates and deletes.

## Memory and redaction contract

- `ProjectExportBundleWriter` pages repository links, contracts and activity rows. Large members
  spool to private files and stream through gzip/tar; the archive is never a JVM `byte[]`.
- Hard ceilings cover repositories, contracts, activity rows, page size and cumulative
  uncompressed bytes. `manifest.json` carries explicit truncation flags; clients must inspect them.
- Schema v2 is a metadata/governance snapshot, not a source backup. It includes aggregate member
  roles, redacted contract metadata and 90-day activity summaries. It excludes credentials,
  provider keys, clone credentials, member/actor/target identifiers, activity payloads, source code
  and uploaded document bytes. `redaction.json` makes these omissions machine-visible.
- `ProjectExportObjectStore` uses the configured private GCS bucket only when
  `project.exports.gcs-enabled=true`; this is deliberately independent of workspace archival.
  Local/single-VM installs use `<agent.workspace>/project-export-store`, so JVM restarts are safe
  without loading archives into PostgreSQL. Multi-node deployments must mount that path durably or
  explicitly enable GCS.

## Observability and known boundary

`executespec.project.export.{requested,rejected,outcome,duration,expiry_failures}` exposes demand,
quota rejection, terminal/recovery outcomes, latency and retention failures without tenant IDs as
metric tags. Logs include job/project ids and fixed error codes, never exception text.

GCS should have a bucket lifecycle rule as a final defence for an object uploaded immediately
before process death but never referenced by a completed row. The application removes known stale
attempt objects and all normally expired objects; it cannot enumerate/delete an unrecorded GCS
orphan without broader bucket-list permission.
