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

## Migration Trail

- `V215__personal_access_token.sql` creates hashed PATs for programmatic auth.
- `V237__provider_key_lanes.sql` adds DeepSeek and Fireworks BYOK lanes on `org` and `app_user`.
- `V242__user_preferred_ai_provider.sql` adds `app_user.preferred_ai_provider`.
