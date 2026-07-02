# Package: `auth`

Auth, account, org, project, RBAC, PAT, OAuth, and user-level client preferences.

## Auth Source

- `JwtAuthenticationFilter` tags authenticated requests with `executespec.auth.source`.
- Values are `JWT` for browser/OAuth/JWT sessions and `PAT` for `es_pat_...` programmatic sessions.
- Server-only capabilities that require a local approval-capable client, currently `POST /api/runs localExecution=true`, must accept only `PAT`.
- Normal RBAC still comes from the same `AppUserDetails` regardless of JWT or PAT.

## Provider Preference

- `app_user.preferred_ai_provider` is the cross-surface default provider for web, CLI, and VS Code.
- `GET /api/auth/me/provider-preference` reads it; `PUT /api/auth/me/provider-preference` updates it.
- Explicit provider input on a run, chat turn, project create, or project settings update is remembered here.
- Missing provider input should resolve through `AiProviderPreferenceService` before falling back to project or YAML defaults.
- Legacy `GOOGLE` input is accepted as `GEMINI`; persistence should use canonical enum names.

## Data Export

- `GET /api/auth/me/data-export` is a ZIP-only personal-data export. Browser/download clients should send `Accept: application/zip`; JSON-default clients receive a clear `406` body with `error=data_export_requires_zip_accept` instead of a misleading route miss.

## Local CORS

- `SecurityConfig.corsConfigurationSource()` trims comma-separated `app.cors.allowed-origins` entries before registering them. The local default includes `http://localhost:5173`, `http://127.0.0.1:5173`, `http://localhost:5174`, and `http://127.0.0.1:5174` so concurrent Vite worktrees can authenticate through the browser without `Invalid CORS request`.

## OAuth Return URLs

- Browser OAuth success uses `auth.oauth.app-return-url` (`APP_SURFACE_RETURN_URL`, defaulting to `app.cors.app-return-url`) as the exact `/auth/callback` target. Do not use `app.mail.base-url` for OAuth; local backend-served mail links and Vite app OAuth callbacks can intentionally be different origins.
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
- `DELETE /api/org/me/invitations/{id}` cancels pending/escalated org invites by marking them `EXPIRED` and writing an `INVITATION_CANCELLED` audit row.
- Project member add/remove accepts org admins and super admins in addition to project admins; `ProjectService.requireProjectAdmin` mirrors the controller/access-guard semantics.

## Migration Trail

- `V215__personal_access_token.sql` creates hashed PATs for programmatic auth.
- `V237__provider_key_lanes.sql` adds DeepSeek and Fireworks BYOK lanes on `org` and `app_user`.
- `V242__user_preferred_ai_provider.sql` adds `app_user.preferred_ai_provider`.
- `V253__ai_credit_pricing_ladder.sql` refreshes Free/Pro/Pro+/BYOK/Team seed rows and restores Team's 2-seat minimum.
