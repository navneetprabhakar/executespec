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

## Org Lifecycle

- Registration bootstraps solo users into a personal org (`org.is_personal=true`) so project/run ownership always has an org boundary.
- `POST /api/org` converts the caller's personal org into a real team org instead of rejecting the request just because `AppUser.orgId` is already set.
- Conversion updates org profile fields, clears `is_personal`, stamps the creator as owner/admin contact, and self-heals a missing `ORG_ADMIN` membership row before returning `OrgDto.isPersonal=false`.
- Users already attached to a non-personal org still cannot create a second org through this endpoint; multi-org switching is not implemented.

## Team Access

- Org membership invites (`POST /api/org/invite`) are work-domain invites. Enterprise orgs always block consumer domains and enforce configured org domains.
- Project-only external collaborators use the separate V175 project invite surface (`POST /api/projects/{id}/invites`) and are controlled by `org.external_sharing_enabled`.
- Accepted external project collaborators inherit the highest active host-project org tier through `TierEnforcementService.resolveUserTier` / `EntitlementResolver.resolveTier`. This is an effective entitlement only: do not mutate the collaborator's personal billing tier. Removing the `ProjectMember` row naturally falls back to the user's own org/user tier.
- `DELETE /api/org/me/invitations/{id}` cancels pending/escalated org invites by marking them `EXPIRED` and writing an `INVITATION_CANCELLED` audit row.
- Project member add/remove accepts org admins and super admins in addition to project admins; `ProjectService.requireProjectAdmin` mirrors the controller/access-guard semantics.

## Migration Trail

- `V215__personal_access_token.sql` creates hashed PATs for programmatic auth.
- `V237__provider_key_lanes.sql` adds DeepSeek and Fireworks BYOK lanes on `org` and `app_user`.
- `V242__user_preferred_ai_provider.sql` adds `app_user.preferred_ai_provider`.
