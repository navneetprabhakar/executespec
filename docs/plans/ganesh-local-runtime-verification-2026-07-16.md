# Ganesh integration local runtime verification — 2026-07-16

Status: LOCAL PROFILE, BOOT, HEALTH, LOGIN, LOGOUT, AND CLEAN SHUTDOWN VERIFIED

## Effective non-secret configuration

- checkout: `/Users/navneetprabhakar/Documents/antigravity/executespec`
- active profiles: `dev,local` (local applied last)
- bind: `127.0.0.1:18080`
- datasource: `jdbc:postgresql://localhost:5432/executespec`, schema `public`
  (credentials redacted)
- Redis: `127.0.0.1:6379`, logical database 14
- workspace: `/tmp/executespec-ganesh-integration/workspace`
- configured provider names: Anthropic, OpenAI, Google GenAI, OpenRouter,
  DeepSeek, and Fireworks
- provider transport: disabled by `executespec.live-llm.enabled=false`
- deployment: disabled
- preview proxy/engine routing: disabled
- external integrations: disabled
- billing gateways: disabled
- email: SMTP selected with blank credentials, so delivery is skipped
- scheduling: disabled whenever the `local` profile is active
- GCS workspace client: disabled before client construction
- crypto GCP lookup: disabled

The ignored `src/main/resources/application-local.yml` is mode `0600`; provider
secrets are sourced from the user-supplied key-rotation file, unrelated keys
are excluded, and Git ignores the profile. Its post-safety-fix SHA-256 is
`525e5942c83f497051c0176598e0c9b7109c8e8ab9e110df7e770de98d0f47f4`.
Secret values are not evidence and must never be uploaded or committed.

## Verification result

1. YAML parse: pass.
2. Spring test compilation and local-safety focused tests: pass.
3. The first E1/E2 integration boot exposed an invalid V274 feature-flag seed
   (`enabled` is not a column in this repository's `feature_flag` table). Flyway
   rolled the transactional migration back and startup stopped. V274 was
   corrected to the established `flag_value` / `value_type` / `scope` schema.
4. The successful final boot validated 275 migrations and advanced the local
   `public` schema from 273 to 274 by applying V274. No remote datasource was
   used. Earlier in this session the same loopback database had advanced from
   272 to 273 by applying out-of-order V119 and new V273.
5. The final Spring context started in 12.812 seconds.
6. Tomcat bound to `http-nio-127.0.0.1-18080`.
7. `GET /actuator/health`: HTTP 200, `status=UP`.
8. Credential line 2 from the mode-`0600` local credential file authenticated:
   identity matched, global role `USER`, tier `FREE`, account status `ACTIVE`.
9. The authenticated model catalog exposed six frontier providers and no
   Ganesh provider/model selection; Ganesh remains an internal worker-only
   route.
10. `DELETE /api/auth/logout`: HTTP 204.
11. Temporary response/cookie material was stored only in a mode-restricted
   `/tmp` state directory and deleted immediately after logout.
12. The application shut down gracefully; no process remains on port 18080.

No provider inference, deployment, preview, billing, email, GPU, model
download, publication, or infrastructure creation occurred.

## Corrected preflight defect

The first startup attempt revealed two unconditional client initializers that
were inconsistent with the local profile:

- dev crypto configuration attempted GCP Secret Manager authentication; and
- `GcsWorkspaceStore` created an ADC client and attempted an OAuth token refresh
  even though workspace GCS was disabled.

Both credential operations failed before any secret/object read or mutation.
The process was stopped immediately. The local profile now overrides crypto to
disabled, and `GcsWorkspaceStore` now returns before constructing a cloud
client when `gcs-enabled=false` or the bucket is blank. Focused tests cover the
scheduling and GCS guards. The successful verification boot showed the crypto
passthrough and GCS-disabled messages and no cloud request.

This incident must remain visible in the final audit; it must not be restated
as “no network attempt ever occurred.” It created no cloud resource and changed
no cloud state.
