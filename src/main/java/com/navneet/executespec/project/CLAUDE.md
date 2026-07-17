# Project source and linking package

`ProjectRepo` is the provider-neutral project source registry; `ProjectRepoLink` attaches a source
to one or more projects. The legacy GitHub fields and `/api/repos` contracts remain supported.

## LOCAL sources (V278)

- `POST /api/projects/{projectId}/sources/local` is write-guarded and registers or renews a LOCAL
  connector. It accepts random client-minted UUIDv4 `localFolderId` and `installationId`, a bounded
  label/revision/capability set, small path-free metadata, and a 30-300 second lease.
- The server never accepts or stores an absolute local filesystem path. `source_key` for LOCAL is
  the opaque `localFolderId`; labels and metadata are not used to reconstruct a path.
- Registration is idempotent for `(owner_user_id, local_folder_id, installation_id)`. A different
  installation attempting to claim the same user/folder identity fails with 409.
- Connector state is derived from `source_lease_expires_at`: future means `ONLINE`, expired or
  absent means `OFFLINE`. Registration proves only `LINKED`, not readable/inventoried/ready source.
- LOCAL rows are user-owned. The controller and service both enforce tenant/project access, and a
  cross-org `PROJECT_VIEWER` cannot register one.
- The normal cloud clone pipeline rejects LOCAL rows with 422 — `ProjectRepoService.resolveForRunStrict`
  keeps LOCAL sources out of cloud runs unconditionally. This prevents a null GitHub identity from
  failing deep in clone/push code.

## Heartbeat + local-run binding (2026-07-17 slice)

- `POST /api/projects/{projectId}/sources/local/{localFolderId}/heartbeat` (write-guarded) renews
  the lease for the holding installation only: unknown folder → 404, foreign installation → 409,
  not linked to the project → 404. It never creates sources or links, and only overwrites the
  stored revision when a non-blank one is supplied (registration, by contrast, is a full metadata
  re-declaration and overwrites).
- **Local-run admission** (`ProjectRepoService.resolveForLocalRunStrict`, called from
  `RunController.submit` when `localExecution=true` and a `projectId` is present): every selected
  LOCAL source must be owned by the submitter, registered to the `RunRequest.localInstallationId`
  the client sent, and hold a currently-ONLINE lease — otherwise 422 before quota, billing, or any
  provider spend. SCM sources pass through unchanged (the client works in its own clone). Local
  submits with a projectId also now get the same `ProjectAccessGuard.assertCanWrite` as cloud
  submits (previously skipped entirely).
- The CLI half lives in `executespec-cli` `core/src/localSource.ts`: installation identity at
  `<configDir>/installation.json`, folder identity at `<dir>/.executespec/local-source.json`,
  `ensureLocalSourceRegistered` + `startLocalSourceHeartbeat` (45s cadence vs the 90s default
  lease); `run local` binds only on explicit intent (`--project` flag or the directory's link
  file), degrades to an unbound local run when the endpoint is absent (older backend), and fails
  closed on a 409 foreign-installation conflict.
- `LocalProjectSourceService` has TWO constructors; the production one is explicitly
  `@Autowired` (the package-private Clock overload is test-only). Removing that annotation breaks
  context startup — this was caught live, not by the mocked unit tests.

V278 makes the GitHub identity columns nullable only for LOCAL rows, backfills neutral SCM fields
for existing rows, and adds scoped unique/indexed local identities and lease lookup.
