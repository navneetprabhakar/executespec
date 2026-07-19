# SCM transport package

`ScmRepositoryClient` is the provider-neutral brownfield source-read contract. Add a provider by
implementing the interface and registering a single Spring bean; duplicate providers fail startup
and unknown provider input fails closed.

## Capability contract

`ScmCapability` is an admission contract, not marketing metadata. Server services check the live
registered adapter before any provider-specific side effect; the persisted capability list lets
clients render the same honest boundary. GitHub currently has the complete legacy project feature
set. Bitbucket Cloud is read-only for catalogue/attach/sync/readiness/browse until its run,
architecture, review, webhook, bootstrap, and PR adapters have independent evidence.

SCM publication follows the durable run/source provider, never whichever credentials happen to be
present. Legacy runs may infer a provider only from an unambiguous remote URL or a single configured
provider; dual-credential legacy runs fail closed. Selected Bitbucket project sources remain
explicitly non-publishable until the provider-neutral run/PR lifecycle exists, so the older
account-level root-workspace push path cannot be used accidentally. The `/push-github` compatibility
route and `/push-scm` route reject caller-supplied `repoUrl` overrides: a stored PAT/API token is
never offered to a destination chosen at push time.

## Credentials and HTTP

- `ScmCredentialResolver` reads the authenticated user's encrypted credential fields and returns
  an ephemeral `ScmCredential`. Never serialize, persist, or log it; `toString` redacts the secret.
- Clone URLs must be public HTTPS coordinates without userinfo/query/fragment. Git authentication
  continues to use `GitProcessAuth`, not embedded credentials or process arguments.
- Bitbucket pagination is same-origin and bounded: catalogue 5 pages/500 repositories; inventory
  100 pages/500 directories/10,000 files. 401/403/404/429/5xx and transport failures are typed,
  secret-free `ScmProviderException` values. A truncated inventory never becomes READY evidence.
- Bitbucket catalogue uses the configured workspace-scoped `/repositories/{workspace}` endpoint;
  the cross-workspace `/repositories?role=member` endpoint was removed under CHANGE-2770. Current
  REST credentials are Atlassian account email + scoped API token. Git-over-HTTPS uses the static
  `x-bitbucket-api-token-auth` identity; legacy database/DTO names retain
  `username`/`appPassword` only for backward compatibility.
- Once attached, Bitbucket metadata/branch/tree/file requests address `workspace/{repository UUID}`;
  the slug remains display/assertion metadata, so a repository rename cannot silently retarget or
  orphan the durable project source.
- Deterministic tests use a loopback HTTP fixture. Live provider calls require explicit user
  authorization and are not part of unit/CI verification.
