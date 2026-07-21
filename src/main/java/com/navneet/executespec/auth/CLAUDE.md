# Package: `auth`

Auth, account, org, project, RBAC, PAT, OAuth, and user-level client preferences.

## Auth Source

- `JwtAuthenticationFilter` tags authenticated requests with `executespec.auth.source`.
- Values are `JWT` for browser/OAuth/JWT sessions and `PAT` for `es_pat_...` programmatic sessions.
- Server-only capabilities that require a local approval-capable client, currently `POST /api/runs localExecution=true`, must accept only `PAT`.
- Normal RBAC still comes from the same `AppUserDetails` regardless of JWT or PAT.
- PAT scopes are enforceable capabilities, not display metadata. Legacy `full` rows remain
  compatible; a new request with no explicit scopes receives the least-privilege client set
  `api:read,runs:write,projects:write,agent:write,settings:write`. Billing, token management,
  `/api/admin/**`, `/api/v2/test/**`, and unknown mutations require an explicit capability or
  `full`. The filter stamps resolved token id/scopes request attributes after authorization.
- Boundary-crossing agent routes require the UNION of both capabilities, decided before the generic
  `/api/agent/` prefix rule: `POST /api/orchestrator/turn/confirm` creates a billable run and needs
  `agent:write` AND `runs:write`; `/api/agent/sessions/{id}/writer/**` mutates files on the caller's
  machine and needs `agent:write` AND the new `local:write`. `local:write` is deliberately excluded
  from `CLIENT_DEFAULT_SCOPES` — a chat-capable token is not a local-filesystem writer — so a token
  that wants the writer seam must request it explicitly. `full` tokens are unaffected.
- Device-cap LRU revocation is returned in `CreatedToken.autoRevokedTokenIds` and the controller
  appends one `USER_PAT_AUTO_REVOKED` audit event per displaced token.

## Provider Preference

- `app_user.preferred_ai_provider` is the cross-surface default provider for web, CLI, and VS Code.
- `GET /api/auth/me/provider-preference` reads it; `PUT /api/auth/me/provider-preference` updates it.
- Explicit provider input on a run, chat turn, project create, or project settings update is remembered here.
- Missing provider input should resolve through `AiProviderPreferenceService` before falling back to project or YAML defaults.
- Legacy `GOOGLE` input is accepted as `GEMINI`; persistence should use canonical enum names.

## BYOK credential mutation

- User and org provider-key PUTs are patch-style: null/blank fields leave existing credentials
  unchanged because the Web UI saves one provider at a time.
- Destructive removal is explicit and provider-scoped: `DELETE
  /api/auth/settings/ai-keys/{provider}` for personal keys and `DELETE
  /api/org/me/settings/ai-keys/{provider}` for org keys.
- Both revoke paths are authenticated, tier/role gated, secret-free in their response and append an
  audit event (`USER_AI_KEY_REVOKED` or `ORG_AI_KEY_REVOKED`). Never reintroduce blank-means-delete
  semantics; it can erase unrelated providers through positional client payloads.
- Credential removal intentionally invalidates subsequent dispatch through V282 current-credential
  re-authorization. Existing execution snapshots do not preserve plaintext credentials.
- Programmatic credential mutation requires the explicit `credentials:manage` PAT capability. It
  is excluded from client-default PATs; routine CLI/device tokens cannot replace, validate or
  revoke user/org provider, SCM, Jira or Confluence credentials or change org key-source mode.
  Legacy `full` tokens remain compatible.
- AI-key validation sends Gemini keys only in `x-goog-api-key` and returns bounded status messages;
  raw provider/transport exception text and credential-bearing URLs are forbidden.

## External credential validation

- Every server-side credential-validation probe (personal and org GitHub / Jira / Confluence /
  AI-provider checks) goes through `auth/service/ExternalConnectionValidator`. Controllers must not
  open their own `HttpClient` against a caller-supplied base URL.
- Policy: HTTPS only, no URL-embedded credentials, no CR/LF in headers, every resolved address must
  be public unicast (loopback / any-local / link-local / site-local / multicast / CGNAT / IPv6
  unique-local / IPv4-mapped equivalents refused).
- The TCP connection is pinned to the exact validated `InetAddress` while TLS SNI + hostname
  verification still run against the original hostname, so a second DNS answer cannot be substituted
  between check and connect.
- Only the HTTP status line is read (1 KB cap); the body is never buffered and redirects are never
  followed. Callers receive a coarse category (`OK` / `TARGET_NOT_ALLOWED` / `HTTP_ERROR` /
  `UNREACHABLE`) — never an exception message, resolved address, or other transport detail.

## Personal Access Token lifetime

- A PAT always has a bounded lifetime. Omitting `expiresInDays` applies
  `PersonalAccessTokenService.DEFAULT_EXPIRY_DAYS` (90); the maximum accepted is `MAX_EXPIRY_DAYS`
  (365). There is no "never expires" PAT.

## Project membership

- `GET /api/projects/{id}/member-capabilities` publishes the effective per-action authority
  (`canManageMembership`, `canManageShareLinks`, `canInviteExternal` + `inviteBlockedReason` of
  `TIER` / `ROLE` / `ORG_EXTERNAL_SHARING_DISABLED`). Clients must read it rather than re-deriving
  membership authority from a single role, because membership admin and share-link admin are two
  different server guards.
- `PUT /api/projects/{id}/members/{userId}` changes an existing member's role, with the same
  last-admin guard on demotion that `removeMember` applies on removal. `POST .../members` now
  returns 409 on an existing membership instead of silently no-op'ing with 201.
- `ProjectMemberDto` carries `external` and `homeOrgName` so cross-org collaborators are
  distinguishable from employees on the members screen.
- External-invite entitlement resolves through `TierEnforcementService.resolveUserTier` +
  `TierEnforcementService.isPaidTier`; never hardcode a tier-name allow-list.

## Data Export

- `GET /api/auth/me/data-export` is a ZIP-only personal-data export. Browser/download clients should send `Accept: application/zip`; JSON-default clients receive a clear `406` body with `error=data_export_requires_zip_accept` instead of a misleading route miss.

## Local CORS

- `SecurityConfig.corsConfigurationSource()` trims comma-separated `app.cors.allowed-origins` entries before registering them. The local default includes `http://localhost:5173`, `http://127.0.0.1:5173`, `http://localhost:5174`, and `http://127.0.0.1:5174` so concurrent Vite worktrees can authenticate through the browser without `Invalid CORS request`.

## OAuth Return URLs

- Browser OAuth success uses `auth.oauth.app-return-url` (`APP_SURFACE_RETURN_URL`, defaulting to `app.cors.app-return-url`) as the exact `/auth/callback` target. Do not use `app.mail.base-url` for OAuth; local backend-served mail links and Vite app OAuth callbacks can intentionally be different origins.
- Deployed profiles (`dev`, `benchmark`, `prod`) must pin non-local `auth.oauth.app-return-url` / `app.cors.*-return-url` defaults, and the deploy workflow must upsert `APP_SURFACE_RETURN_URL`, `BUILD_SURFACE_RETURN_URL`, and `APP_MAIL_BASE_URL` from the resolved public URL. Otherwise OAuth and email links can silently fall back to localhost.
- CLI / VS Code device login remains separate: `OAuthRedirectCookieFilter` only stores safe `/cli-login...` paths in `es_oauth_redirect`, and `OAuthSuccessHandler` appends that path as the callback `redirect=` param after token generation.
- `OAuthSuccessHandlerTest` pins app-origin callback selection plus safe `/cli-login` handoff and unsafe redirect rejection.

## Tier Permissions

- `GET /api/auth/me/permissions` keeps the legacy `monthlyRunLimit` integer for backward compatibility, but clients must read `quotaModel`, `includedAiCredits`, `unlimitedAiCredits`, and `quotaDescription` to understand the active customer-facing quota boundary. Managed-key tiers such as FREE/PRO/PRO_PLUS report `quotaModel=MANAGED_AI_CREDITS`; BYOK/TEAM/ENTERPRISE report `OWN_KEYS_UNLIMITED`.

## Org Lifecycle

- Registration bootstraps solo users into a personal org (`org.is_personal=true`) so project/run ownership always has an org boundary.
- `POST /api/org` converts the caller's personal org into a real team org instead of rejecting the request just because `AppUser.orgId` is already set.
- Conversion updates org profile fields, clears `is_personal`, stamps the creator as owner/admin contact, and self-heals a missing `ORG_ADMIN` membership row before returning `OrgDto.isPersonal=false`.
- Users already attached to a non-personal org still cannot create a second org through this endpoint; multi-org switching is not implemented.

## Team Access

- Org membership invites (`POST /api/org/invite`) are work-domain invites. Enterprise orgs always block consumer domains and enforce configured org domains.
- V253 pricing tiers: `PRO_PLUS` and `BYOK` are first-class tier values. BYOK ranks with Team for own-key/model-matrix capability as a single-person org. Team self-serve subscriptions require 2-20 seats.
- Project-only external collaborators use the separate V175 project invite surface (`POST /api/projects/{id}/invites`) and are controlled by `org.external_sharing_enabled`.
- Accepted external project collaborators inherit the highest active host-project org tier through `TierEnforcementService.resolveUserTier` / `EntitlementResolver.resolveTier`. This is an effective entitlement only: do not mutate the collaborator's personal billing tier. Removing the `ProjectMember` row naturally falls back to the user's own org/user tier.
- Project-scoped read surfaces must honor `ProjectMember` even when the caller's home `orgId` differs from the host project org. This includes project-filtered run lists, single-run reads for runs with `PipelineRun.projectId`, linked repo reads, and project chat. Write access remains stricter: use `ProjectAccessGuard.assertCanWrite` / `RunAccessGuard.requireRunWriteAccess` so `PROJECT_VIEWER` cannot mutate project or run state.
- Query-string credentials must never be promoted by `JwtAuthenticationFilter` into general
  authentication. PAT and normal JWT authentication are Authorization-header-only. The run and
  execution SSE controllers alone may consume a query token, and must validate `scope=sse` plus the
  exact resource identifier before subscribing.
- `DELETE /api/org/me/invitations/{id}` cancels pending/escalated org invites by marking them `EXPIRED` and writing an `INVITATION_CANCELLED` audit row.
- Project member add/remove accepts org admins and super admins in addition to project admins; `ProjectService.requireProjectAdmin` mirrors the controller/access-guard semantics.

## Append-only security audit (V292)

- `activity_audit` is a hash-chained security record, not a mutable application table. V292 installs
  a PostgreSQL trigger that rejects every UPDATE and DELETE; retention must therefore use an
  approved archival/partition procedure rather than row mutation.
- `ActivityAuditService.record` takes a transaction-scoped PostgreSQL advisory lock before reading
  the chain tail and assigning the next sequence/hash. The database, not a JVM-local monitor,
  serializes append order across instances.
- Project Chat confirmation issue/allow/deny/consume/replay/expiry/action-complete/action-fail events
  record secret-free scope identifiers and hashes. Bearer tokens, canonical target JSON, prompts,
  tool arguments/results, provider responses, and credentials must never enter the audit payload.
- Real PostgreSQL evidence covers V291→V292 upgrade, mutation rejection, idempotent rerun, and a
  concurrent two-consumer race yielding exactly one consume and one replay denial.

## Migration Trail

- `V215__personal_access_token.sql` creates hashed PATs for programmatic auth.
- `V237__provider_key_lanes.sql` adds DeepSeek and Fireworks BYOK lanes on `org` and `app_user`.
- `V242__user_preferred_ai_provider.sql` adds `app_user.preferred_ai_provider`.
- `V253__ai_credit_pricing_ladder.sql` refreshes Free/Pro/Pro+/BYOK/Team seed rows and restores Team's 2-seat minimum.
- `V292__project_chat_scoped_confirmations.sql` adds hashed, actor/tenant/project/route/target-scoped
  one-shot Project Chat authority and makes the existing `activity_audit` table database-append-only.
