# Dedicated Enterprise Environment and Organization Bootstrap

Status: draft operational standard for a new single-customer deployment. This document describes
the desired production procedure, and also calls out where the current repository cannot yet
execute that procedure safely without remediation.

## 1. Objective

Create an isolated ExecuteSpec deployment for one enterprise customer, for example
`commerceiq.executespec.ai`, with its own cloud project/account, VPC, compute, PostgreSQL, Redis,
storage, secrets, identity configuration, provider policy, observability, backups, budgets, and
release lane. Then create the customer's ExecuteSpec organization with conservative defaults that
cannot silently change provider, model, cost, code, or sharing policy.

The acceptance condition is not “the login page opens.” It is:

- infrastructure, application data, secrets, logs, metrics, backups, and billing are isolated;
- only customer-approved providers and models can be dispatched;
- an explicit run model remains exact through planning, workers, reviewer, test generation, chat,
  retry, recovery, and continuation;
- GitHub access is customer-owned and least-privileged;
- every readiness, migration, backup, restore, alert, and brownfield workflow check below passes;
- rollback has been rehearsed without deleting or recreating the database.

## 2. Deployment decision

Use a separate cloud project/account for each enterprise customer. A separate VM and DB inside the
shared ExecuteSpec project is not sufficient isolation: IAM, quotas, audit logs, budgets, Secret
Manager, Terraform state, networking, and incident blast radius would still be shared.

Current first-class infrastructure support is GCP. Deploying into an AWS VPC requires a separate,
reviewed AWS Terraform implementation; do not translate the GCP commands ad hoc during a customer
onboarding.

Recommended initial topology:

| Layer | Dedicated customer setting |
| --- | --- |
| Cloud boundary | One GCP project, linked billing account, customer labels and budget |
| Network | One VPC, private subnet, Private Service Access, Cloud NAT, no VM public IP |
| Ingress | Global HTTPS load balancer, managed certificate, Cloud Armor/approved edge policy |
| Compute | Minimum two application instances in a regional MIG for production; one VM is pilot-only |
| Database | Cloud SQL PostgreSQL 15, regional HA, PITR, 14+ retained backups |
| Cache | Memorystore Redis `STANDARD_HA` |
| Storage | Separate deploy and workspace buckets with versioning, retention and lifecycle policy |
| Secrets | Customer project Secret Manager, no plaintext GCS or instance-metadata secret fallback |
| Identity | Customer-specific OAuth/SSO applications and redirect URIs |
| Telemetry | Customer-scoped logs, metrics, traces, dashboards, alerts and audit export |
| Release | Customer-specific protected GitHub Environment and manual promotion |

The current Terraform provisions one standalone VM and unmanaged instance group. That is acceptable
for a time-boxed pilot only, with an explicitly accepted availability limitation. It is not the
target enterprise production topology.

## 3. Mandatory repository gaps before the first customer deployment

Do not run the present `infra/` module unchanged for a customer. Close these gaps first:

1. Add `deployment_id`/`customer_slug` and `public_url` variables. `environment` currently accepts
   only `dev`, `prod`, or `benchmark`, while names, the startup script, and CI are environment-bound.
2. Pass the computed public URL to VM metadata and generate CORS, mail, OAuth, auth-return, Caddy,
   and preview URLs from it. `infra/scripts/startup.sh` currently hardcodes ExecuteSpec dev/app
   domains and a shared preview gateway.
3. Parameterize `.github/workflows/deploy.yml`. It currently exposes only dev/benchmark targets and
   hardcodes instance names, buckets, domains, required provider keys, and preview registry.
4. Use a distinct versioned Terraform-state bucket/prefix per customer. `infra/backend.tf` currently
   hardcodes the shared `executespec-tfstate` bucket.
5. Wire `infra/modules/secrets` into `infra/main.tf`, extend its inventory to DeepSeek, OpenRouter,
   Fireworks, OAuth, Stripe and encryption keys, and remove the plaintext GCS `api-keys` fallback.
   Align secret IDs with `SecretManagerConfigLoader.SECRET_TO_ENV`: the module currently prefixes
   IDs with `name_prefix` (normally `executespec-prod-*`), while the loader recognizes exact
   environmentless IDs such as `executespec-anthropic-api-key`. Adding the module without resolving
   that mismatch creates secrets the application silently ignores.
6. Stop putting the database password in GCE instance metadata. Read it from Secret Manager at
   service start, or use Cloud SQL IAM database authentication.
7. Change the systemd service from `User=root`; enable `NoNewPrivileges`, `ProtectSystem`,
   `ProtectHome`, a dedicated service account, and narrowly scoped writable paths.
8. Replace hardcoded preview VM IPs/SSH paths and the shared preview engine with a dedicated,
   disabled, or customer-approved preview topology.
9. Add Cloud Monitoring alert policies, notification channels, uptime checks, log-based metrics,
   Cloud Armor policy, backup verification, and restore automation to Terraform.
10. Add regional MIG/autoscaling and workspace affinity/durability before claiming horizontal
    availability. The current local workspace and in-memory execution seams cannot be made HA by
    merely adding a second VM.
11. Merge and migration-test the durable provider/model route remediation introduced with V275.
    It persists the requested provider/model and routing mode, rebinds them on restart/retry and
    enforces exact locks at the LLM dispatch boundary. Treat it as unproven until clean-install,
    upgrade, restart, retry, background test-generation and CLI/Web acceptance evidence passes.
12. Make effective configuration observable: startup must publish the source and effective value of
    each routing/continuation flag, and deployment validation must fail on documented/YAML/DB drift.
13. Seed database rows for `routing.adaptive-routing.enabled` and `ci.autofix.enabled`, or remove
    them from the admin-editable flag contract. Both call sites currently fail closed when the row is
    absent, but `PUT /api/admin/feature-flags/{key}` rejects an unknown key; an operator cannot
    explicitly pin or audit either value through the documented endpoint today.
14. Reconcile the organization provider allow-list with `LlmProvider`. `OrgService.VALID_PROVIDERS`
    currently omits `gemini`, even though Gemini is registered, routable, key-backed and returned by
    provider readiness. Until fixed, an org cannot explicitly allow Gemini without the whole
    allow-list request failing.
15. Return the created organization ID from `POST /api/admin/invite` when `newOrgName` is used. The
    current response contains only a message, forcing a non-atomic list-and-name lookup during
    bootstrap. Until fixed, use `GET /api/admin/orgs`, verify the unique name and ID manually, and
    stop if the result is ambiguous.
16. Add an explicit, audited clear/revoke operation for each org provider key. The current
    `PUT /api/org/me/settings/ai-keys` treats null/blank fields as “leave unchanged,” so an admin can
    rotate a key but cannot remove a stored credential. The provider allow-list can block dispatch,
    but it is not credential deletion.

Items 11 and 12 are unconditional release blockers for a customer that contractually restricts
model use. Item 13 must also be resolved when the contract requires an auditable explicit value,
and item 14 is a blocker whenever Gemini is in the approved provider set.

## 4. Inputs and approvals

Record these before creating infrastructure:

- customer legal name, slug, domain and technical owner;
- GCP organization/folder/project ownership and billing account;
- region and data-residency requirement;
- expected users, concurrent runs, repository count, largest repository size and daily run volume;
- availability, RPO, RTO, retention and support objectives;
- identity method, allowed email domains, SCIM/SSO requirements and break-glass owners;
- GitHub organization, GitHub App/PAT permissions, IP allow-list and webhook policy;
- approved LLM providers, exact model IDs, key ownership, budget and data-processing terms;
- whether source can leave the customer VPC and whether provider zero-retention is contractually set;
- external project sharing policy;
- preview/deployment permissions and whether generated code may be pushed automatically;
- telemetry retention, customer access and incident-notification destinations.

No provider should be enabled merely because a system key exists.

## 5. Recommended flags and defaults for a new enterprise org

### 5.1 Precedence contract

The intended precedence is:

1. validated explicit run provider and model;
2. validated project provider/model policy;
3. organization model matrix and provider allow-list;
4. account preference, but only inside the organization allow-list;
5. platform YAML default, but only when the org explicitly permits managed keys.

An explicit model means exact-model mode: all stages and run-scoped chat use that model, and
adaptive routing, initial routing, failover, step-up/down, and provider change must not override it.
A provider-only selection may use different PLANNING/WORKHORSE/LIGHTWEIGHT models within that same
provider according to the org matrix. Corrupt or unavailable explicit routes fail closed.

This is the target contract. Until the durable-route remediation is merged and tested, operate the
environment in the locked baseline below.

### 5.2 Locked launch baseline

| Control | Recommended launch value | Reason |
| --- | ---: | --- |
| `security.guardrail_level` | `HIGH` | Enterprise code and shell policy |
| `submissions.paused` | `true` until acceptance; then `false` | Prevent use during incomplete bootstrap |
| `routing.adaptive-routing.enabled` | `false` | No silent provider change |
| `execution.initial-routing.enabled` | `false` initially | Do not override customer selection before route durability is proven |
| `router.failover.enabled` | `false` initially | Fail closed rather than cross a contractual provider boundary |
| `worker.cross-provider-fallback.enabled` | `false` | No second, independent cross-provider mechanism |
| `execution.continuation.acting.enabled` | `false` initially | Recommendations may be shown; automatic reruns wait for acceptance |
| `execution.continuation.post-review.enabled` | `true` | Generate advice/evidence after bad review; acting remains disabled |
| `execution.continuation.require-confirmation-before-expensive-continuation` | `true` | Human cost/provider approval |
| `execution.continuation.require-benchmark-prior-for-automatic-continuation` | `true` | No automatic candidate without comparable evidence |
| `execution.goal-decision.enforcement.enabled` | `true` after RCA suite passes | Stop false-done/waste outcomes at task boundaries |
| `scoring.additive-v2` | `true` | Current score formula |
| `scoring.gaming-guards` | `true` | Tighten score against trivial output/tests |
| `scoring.real-test-exec` | `true` | Prefer executed tests over LLM assertion |
| `reviewer.third-pass.enabled` | `true` | Bounded build-fix pass |
| `worker.verify-before-complete.enabled` | `true` | Server-owned completion check |
| `worker.auto-verify.enabled` | `true` | Bounded verification after edits |
| `worker.edit-diagnostics.enabled` | `true` | Immediate syntax evidence |
| `worker.salvage-force-tool-choice.enabled` | `true` | Bounded zero-edit salvage |
| `worker.critic-pass.enabled` | `true` | Acceptance review before PATCH completion |
| `worker.local-hands.enabled` | `true` only if CLI is approved | Browser/JWT local execution remains prohibited |
| `codeintel.inject-arch.enabled` | `true` | Use generated cross-repo architecture context |
| `codeintel.inject.enabled` | `true` after a customer-repo token/cost check | Brownfield localization, bounded input |
| `enterprise.inject.enabled` | `true` after contracts/ADRs are populated | Make governance context affect planning |
| `standards.enforcement.enabled` | `true` after standards dry run | Persist deterministic violations |
| `contracts.breaking-change.enabled` | `true` after contracts are registered | Surface cross-repo breaking changes |
| `project.runs.auto-push` | `false` for acceptance | Enable only after branch protection and dry run |
| `ci.autofix.enabled` | `false` | Unattended write surface needs separate approval |
| `org.external_sharing_enabled` | `false` | Customer must explicitly opt in |
| `worker.tool-compactor.enabled` | `false` | Unproven context mutation |
| `worker.history-compaction.enabled` | `false` | Unproven context mutation |
| `worker.capability-profiles.enabled` | `false` | Model overlays require reproducible evidence |
| `worker.provider-prompt-tuning.enabled` | `false` | No model-specific prompt overlay without evaluation |
| `worker.background-jobs.enabled` | `false` | Cleanup/container prerequisites remain |
| `worker.code-outline.enabled` | `false` | Java-only/model-schema evaluation incomplete |
| `worker.build-tdd.enabled` | `false` | Known schema-sensitive regression history |
| `build.test-exec-gate.enabled` | `false` | Superseded by server-owned auto-verify path |
| `v2.pause.enabled` | `false` | Current pause/resume is not cross-JVM durable |

After route durability and the customer's fixed-corpus provider evaluation pass, enable
`router.failover.enabled`, `execution.initial-routing.enabled`, and continuation acting only for an
explicit allow-list. For DeepSeek, the recovery order remains native DeepSeek, then OpenRouter, then
Fireworks. Do not allow an unrelated provider as a convenience fallback.

### 5.3 Configuration-drift warning

The repository currently disagrees about benchmark-prior behavior:
`application.yml` sets `require-benchmark-prior-for-automatic-continuation: true`, some `@Value`
fallbacks use `false`, and current documentation has also claimed `false`. Deployment acceptance
must read the effective runtime value, not infer it from one source file.

There are three distinct control mechanisms; do not substitute one for another:

- database feature flags are changed through `/api/admin/feature-flags/{key}` and must already have
  a seeded row;
- `execution.continuation.*` values are Spring configuration and must be supplied before process
  startup (environment, command-line property, or a startup-loaded property source);
- strict provider enforcement is read with `Boolean.getBoolean("executespec.strict-provider")`, so
  it requires the JVM system property `-Dexecutespec.strict-provider=true`. An environment variable
  with a similar name does not satisfy this check.

`POST /api/admin/config/refresh` is not a safe way to change the continuation settings above. It
sets system properties after startup, while the current singleton services have already captured
their `@Value` fields. Restart the application after changing startup configuration and verify the
effective values. The current `/api/admin/config` response lists only GCS runtime-config entries;
it is not a merged Spring effective-configuration export.

### 5.4 Apply and verify platform flags

Feature flags are platform-scoped in the current schema, not per-org. A dedicated environment makes
that acceptable because it contains one customer. In a shared environment this matrix cannot be
treated as an org policy.

```bash
export BASE_URL='https://commerceiq.executespec.ai'
export SUPERADMIN_TOKEN='<short-lived-super-admin-jwt>'

set_flag() {
  curl --fail-with-body -sS -X PUT "$BASE_URL/api/admin/feature-flags/$1" \
    -H "Authorization: Bearer $SUPERADMIN_TOKEN" \
    -H 'Content-Type: application/json' \
    --data "{\"value\":\"$2\"}"
}

set_flag submissions.paused true
set_flag security.guardrail_level HIGH
set_flag execution.initial-routing.enabled false
set_flag router.failover.enabled false
set_flag worker.cross-provider-fallback.enabled false
set_flag execution.goal-decision.enforcement.enabled false
set_flag scoring.additive-v2 true
set_flag scoring.gaming-guards true
set_flag scoring.real-test-exec true
set_flag reviewer.third-pass.enabled true
set_flag worker.verify-before-complete.enabled true
set_flag worker.auto-verify.enabled true
set_flag worker.edit-diagnostics.enabled true
set_flag worker.salvage-force-tool-choice.enabled true
set_flag worker.critic-pass.enabled true
set_flag worker.local-hands.enabled false
set_flag codeintel.inject-arch.enabled true
set_flag codeintel.inject.enabled false
set_flag enterprise.inject.enabled false
set_flag standards.enforcement.enabled false
set_flag contracts.breaking-change.enabled false
set_flag project.runs.auto-push false
set_flag worker.tool-compactor.enabled false
set_flag worker.history-compaction.enabled false
set_flag worker.capability-profiles.enabled false
set_flag worker.provider-prompt-tuning.enabled false
set_flag worker.background-jobs.enabled false
set_flag worker.code-outline.enabled false
set_flag worker.build-tdd.enabled false
set_flag build.test-exec-gate.enabled false
set_flag v2.pause.enabled false

curl --fail-with-body -sS "$BASE_URL/api/admin/feature-flags" \
  -H "Authorization: Bearer $SUPERADMIN_TOKEN" > effective-feature-flags.json
```

Do not add `routing.adaptive-routing.enabled` or `ci.autofix.enabled` to this script until their
rows are seeded. Their current absence makes `FeatureFlagService.isEnabled(...)` return false, but
an attempted update returns `Unknown feature flag` and must fail the bootstrap rather than be
ignored.

Set these Spring/JVM controls in the immutable service definition before start (shown as JVM
properties because that also handles the strict-provider check unambiguously):

```bash
JAVA_OPTS="$JAVA_OPTS \
 -Dexecutespec.strict-provider=true \
 -Dexecution.continuation.acting.enabled=false \
 -Dexecution.continuation.post-review.enabled=true \
 -Dexecution.continuation.require-confirmation-before-expensive-continuation=true \
 -Dexecution.continuation.require-benchmark-prior-for-automatic-continuation=true"
```

Persist the equivalent values in the customer service manager; the shell assignment alone is only
illustrative. Restart, inspect the startup environment/service definition, and execute negative
route tests. Do not claim effective-value evidence from `/api/admin/config` until section 3 item 12
is implemented.

Keep `effective-feature-flags.json`, the reviewed service configuration, deployment SHA, model
matrix and provider-readiness response in the onboarding evidence bundle. Never place tokens or keys
in that bundle.

## 6. GCP environment creation from scratch

The commands below assume the mandatory parameterization gaps in section 3 have been merged.

### 6.1 Create the customer project

```bash
export CUSTOMER_SLUG='commerceiq'
export GCP_PROJECT='executespec-commerceiq-prod'
export GCP_REGION='us-east4'
export GCP_ZONE='us-east4-a'
export BILLING_ACCOUNT='<billing-account-id>'
export GCP_ORG='<gcp-organization-id>'

gcloud projects create "$GCP_PROJECT" --organization="$GCP_ORG" \
  --name="ExecuteSpec CommerceIQ Production"
gcloud billing projects link "$GCP_PROJECT" --billing-account="$BILLING_ACCOUNT"
gcloud config set project "$GCP_PROJECT"
```

Attach budget alerts before application traffic. Use 50%, 75%, 90%, 100% and forecasted 100%
thresholds, with both email and the operations notification channel.

### 6.2 Create isolated Terraform state

```bash
export TF_STATE_BUCKET="${GCP_PROJECT}-tfstate"
gcloud storage buckets create "gs://$TF_STATE_BUCKET" \
  --project="$GCP_PROJECT" --location="$GCP_REGION" --uniform-bucket-level-access
gcloud storage buckets update "gs://$TF_STATE_BUCKET" --versioning
```

Use a customer-specific backend config; do not edit the shared backend in place:

```hcl
bucket = "executespec-commerceiq-prod-tfstate"
prefix = "executespec/prod"
```

Restrict state-bucket access to the Terraform deploy identity and named break-glass operators.

### 6.3 Create the customer tfvars

Create `infra/environments/commerceiq-prod.tfvars` from this template; do not commit customer IDs,
network ranges or account metadata if the repository is public.

```hcl
project_id       = "executespec-commerceiq-prod"
environment      = "prod"
customer_slug    = "commerceiq"
public_url       = "https://commerceiq.executespec.ai"
region           = "us-east4"
zone             = "us-east4-a"
domain           = "executespec.ai"
subdomain_prefix = "commerceiq"

machine_type      = "n2-standard-8"
boot_disk_size_gb = 100
boot_disk_type    = "pd-ssd"

db_tier         = "db-custom-4-15360"
db_disk_size_gb = 100
db_ha_enabled   = true
db_name         = "executespec"
db_user         = "executespec_app"

redis_memory_gb = 5
redis_tier      = "STANDARD_HA"
enable_lb       = true
enable_iap      = true

labels = {
  customer    = "commerceiq"
  environment = "prod"
  data_class  = "confidential"
  owner       = "executespec-platform"
}
```

Sizing is an initial assumption, not a benchmark result. Adjust it after a load test using the
customer's repository-size and concurrency distribution.

### 6.4 Plan and provision

```bash
cd infra
terraform init -reconfigure -backend-config="bucket=$TF_STATE_BUCKET" \
  -backend-config='prefix=executespec/prod'
terraform workspace new "$CUSTOMER_SLUG" 2>/dev/null || terraform workspace select "$CUSTOMER_SLUG"
terraform fmt -check -recursive
terraform validate
terraform plan -out="/tmp/${CUSTOMER_SLUG}.tfplan" \
  -var-file="environments/${CUSTOMER_SLUG}-prod.tfvars"
terraform show "/tmp/${CUSTOMER_SLUG}.tfplan"
terraform apply "/tmp/${CUSTOMER_SLUG}.tfplan"
terraform output
```

Have a second operator review the plan for destroy/recreate actions, public IPs, broad IAM roles,
shared resources and non-customer names before apply.

### 6.5 DNS and TLS

Create the customer hostname at the edge/DNS provider pointing to Terraform's `lb_ip`. Confirm the
managed certificate is ACTIVE before login testing. Set TLS to strict end-to-end validation and
apply the approved WAF/rate-limit policy. Do not reuse dev wildcard assumptions.

## 7. Secrets and identity

Create secrets in the customer project with `app=executespec`, `customer=<slug>` and `env=prod`
labels. Required inventory depends on the approved features, but at minimum includes:

- JWT signing secret and credential-encryption key;
- database credential if IAM DB authentication is not yet used;
- approved LLM provider keys only;
- OAuth/SSO client ID and secret;
- GitHub App credentials or customer-owned fine-grained PAT;
- mail provider credentials;
- webhook secrets;
- billing keys only when billing is enabled for this dedicated tenant;
- preview-engine secret only when a dedicated preview engine is approved.

Example without shell-history exposure:

```bash
gcloud secrets describe executespec-deepseek-api-key --project="$GCP_PROJECT" >/dev/null 2>&1 || \
  gcloud secrets create executespec-deepseek-api-key --project="$GCP_PROJECT" \
    --replication-policy=automatic \
    --labels="app=executespec,customer=$CUSTOMER_SLUG,env=prod"
read -rsp 'DeepSeek key: ' SECRET_VALUE; echo
printf %s "$SECRET_VALUE" | gcloud secrets versions add executespec-deepseek-api-key \
  --project="$GCP_PROJECT" --data-file=-
unset SECRET_VALUE
```

The current `SecretManagerConfigLoader` auto-derives `DEEPSEEK_API_KEY` from this exact
`executespec-deepseek-api-key` ID, so a correctly labelled manually created secret is loadable. The
Terraform secret inventory does not create it today, and an environment-prefixed ID such as
`executespec-prod-deepseek-api-key` derives the wrong property (`PROD_DEEPSEEK_API_KEY`). Resolve
section 3 item 5 for repeatable provisioning, then verify provider readiness after restart.

Prohibit plaintext `config/api-keys` objects in GCS. Verify the runtime service account has secret
access and operators do not. Rotate a non-production key once before go-live to prove the path.

Create customer-specific OAuth applications with callbacks under
`https://<customer>.executespec.ai/api/auth/oauth2/callback/{registrationId}` and the exact auth
return URL used by the application. Verify login, logout, refresh, forced logout and revoked-user
behavior.

## 8. Build and first deployment

Use an immutable reviewed commit. The customer release workflow must run the same PR gates as dev,
then require manual approval in a protected GitHub Environment.

```bash
git fetch origin
git checkout --detach '<approved-commit-sha>'
npm --prefix frontend ci
npm --prefix frontend run format:check
npm --prefix frontend run lint
npm --prefix frontend run build
JAVA_HOME=/path/to/java-21 mvn verify
```

Upload by SHA, never overwrite a release object:

```bash
export RELEASE_SHA="$(git rev-parse HEAD)"
export DEPLOY_BUCKET="$(cd infra && terraform output -raw deploy_bucket)"
JAR="$(find target -maxdepth 1 -name '*.jar' ! -name '*-sources.jar' | head -1)"
gcloud storage cp "$JAR" "gs://$DEPLOY_BUCKET/releases/app-$RELEASE_SHA.jar"
printf %s "$RELEASE_SHA" | gcloud storage cp - "gs://$DEPLOY_BUCKET/CURRENT_SHA"
```

Restart through the customer deployment workflow or IAP, then verify the symlink and recorded SHA.
Do not run Flyway manually against a different JAR. Application startup applies the immutable
migrations with prod `baseline-on-migrate=false`, `out-of-order=false`, and JPA `ddl-auto=validate`.

For a brand-new database, migration must reach the repository head exactly. For an existing
database, take an on-demand backup, run a rehearsal on a restored clone, and compare schema history
before production deployment. Never rewrite or combine an already-applied migration.

## 9. Customer organization bootstrap

1. Keep `submissions.paused=true`.
2. Sign in with the environment's break-glass SUPER_ADMIN.
3. Create the customer org and invite the first customer administrator:

```bash
curl --fail-with-body -sS -X POST "$BASE_URL/api/admin/invite" \
  -H "Authorization: Bearer $SUPERADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"email":"<customer-admin-email>","newOrgName":"CommerceIQ","orgRole":"ORG_ADMIN"}'
```

   The response does not currently contain the new org ID. Until section 3 item 15 is fixed, list
   orgs and verify exactly one matching customer org before continuing:

```bash
curl --fail-with-body -sS "$BASE_URL/api/admin/orgs" \
  -H "Authorization: Bearer $SUPERADMIN_TOKEN" > customer-orgs.json
jq -r '.[] | select(.name == "CommerceIQ") | [.id, .name, .tier, .status] | @tsv' \
  customer-orgs.json
```

4. Capture the new org ID, set tier to `ENTERPRISE`, and set the agreed run limit. A null override
   means “use the tier default”; it does not mean unlimited. Use an explicit numeric limit when the
   contract specifies one:

```bash
export ORG_ID='<new-org-id>'
export MONTHLY_RUN_LIMIT='<agreed-positive-integer>'
curl --fail-with-body -sS -X PUT "$BASE_URL/api/admin/orgs/$ORG_ID/tier" \
  -H "Authorization: Bearer $SUPERADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data '{"tier":"ENTERPRISE"}'
curl --fail-with-body -sS -X PUT "$BASE_URL/api/admin/orgs/$ORG_ID/run-limit" \
  -H "Authorization: Bearer $SUPERADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data "{\"limit\":$MONTHLY_RUN_LIMIT}"
```

5. The customer ORG_ADMIN sets `keySourceMode=OWN_KEYS`, the exact provider allow-list, and org
   provider keys through `/api/org/me/settings/key-source` and `/api/org/me/settings/ai-keys`.
   Prefer org keys over personal user keys for auditability. `enabledProviders` values are lowercase
   provider IDs such as `deepseek`, `openrouter`, and `fireworks`; omission/empty means no provider
   restriction, not “deny all.” Never send an empty list for a restricted customer. For example:

```bash
export ORGADMIN_TOKEN='<short-lived-customer-org-admin-jwt>'
curl --fail-with-body -sS -X PUT "$BASE_URL/api/org/me/settings/key-source" \
  -H "Authorization: Bearer $ORGADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data '{"keySourceMode":"OWN_KEYS","enabledProviders":["deepseek"]}'
```

   Do not add Gemini to this list until section 3 item 14 is fixed. Submit the key through the UI or
   a protected request-body file, not an inline shell argument or committed script; the exact JSON
   field for native DeepSeek is `deepSeekApiKey`. Null or blank key fields do not clear a stored key;
   use the provider allow-list to fail closed and treat section 3 item 16 as an offboarding blocker.
6. Set the org model matrix through `/api/org/me/model-matrix`. Pin every enabled provider/tier cell;
   leave disabled providers absent. For a DeepSeek-first customer, use native DeepSeek for its
   approved tiers and permit OpenRouter/Fireworks only when their data-processing and model routes
   are separately approved. Matrix keys use lowercase tiers. A native-DeepSeek example is:

```bash
curl --fail-with-body -sS -X PUT "$BASE_URL/api/org/me/model-matrix" \
  -H "Authorization: Bearer $ORGADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data '{"overrides":{"deepseek:planning":"deepseek-v4-pro-native","deepseek:workhorse":"deepseek-v4-pro-native","deepseek:lightweight":"deepseek-v4-flash-native"}}'
```

   Treat `PUT` as full replacement: omitted existing override cells are cleared. Read the matrix
   back and compare `overrides`, `defaults`, and `modelsByProvider` before enabling submissions.
7. Explicitly keep external sharing false with the ORG_ADMIN token (the default is false, but the
   API call creates auditable evidence):

```bash
curl --fail-with-body -sS -X PUT "$BASE_URL/api/org/me/settings/external-sharing" \
  -H "Authorization: Bearer $ORGADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data '{"enabled":false}'
```

   Add approved email domains and at least two ORG_ADMINs.
8. Verify `/api/v3/provider-readiness` returns only `AVAILABLE_ORG_KEY` (or the deliberately selected
   source) for approved providers and `POLICY_BLOCKED`/`UNAVAILABLE` for every other provider. The
   response provider names are uppercase enum values; readiness is account-effective, so call it
   using the customer ORG_ADMIN/member token, not the environment SUPER_ADMIN token.
9. Only after the effective key source, allow-list and actual readiness result have been verified,
   set the legacy `api_keys_provided` entitlement marker. This endpoint does not store or validate a
   key; it only toggles a boolean:

```bash
curl --fail-with-body -sS -X PUT "$BASE_URL/api/admin/orgs/$ORG_ID/api-keys" \
  -H "Authorization: Bearer $SUPERADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data '{"provided":true}'
```

10. Generate a PAT for CLI use, verify revocation, and never copy a browser JWT into automation.

## 10. Observability, monitoring and alerting

Before customer testing, provide SUPER_ADMIN and ORG_ADMIN views appropriate to their scope.

Minimum dashboards:

- request rate, p50/p95/p99 latency and 4xx/5xx;
- active/queued/terminal runs, phase and task latency, failure reason and stuck-run age;
- provider/model calls, latency, error/429 rate, circuit state, tokens, cache, reasoning and cost;
- continuation recommendations/actions, provider switches and route-policy violations;
- workspace disk, inode, CPU, memory, JVM heap/GC, process count and Docker pressure;
- Cloud SQL CPU/storage/connections/replication/backup age;
- Redis memory/evictions/connections/latency;
- webhook lag/failure, mail failure, preview failure and GitHub API rate limit;
- per-org usage, budget, top repositories and audit events without exposing source or secrets.

Minimum paging alerts:

- public and internal readiness unavailable for 5 minutes;
- 5xx above 1% for 5 minutes or the published SLO threshold;
- no terminal progress for an active run beyond its class threshold;
- provider auth failure, circuit open, sustained 429 or policy-violation attempt;
- Cloud SQL availability, storage >80%, connections >80%, backup/PITR failure;
- Redis availability, evictions, memory >80%;
- VM/MIG unhealthy, disk >80%, JVM heap >85%, repeated OOM/restart;
- deploy health/provider verification failure;
- cost anomaly and 50/75/90/100% budget thresholds;
- security/audit anomalies such as SUPER_ADMIN creation, key-source change, model-matrix change,
  external-sharing enablement or repeated authorization denial.

### Canonical LLM usage persistence failure

`CanonicalLlmUsagePersistenceFailure` fires when
`executespec_v2_llm_usage_persistence_total{result="failed"}` increases. Treat it as potential
provider spend without a canonical tenant-attributed ledger row:

1. Pause new LLM submissions for the affected environment; do not silence while dispatch continues.
2. Capture the deployment SHA, failure interval, provider/model route and affected run/session ids
   from secret-free application logs and traces.
3. Inspect `llm_usage_terminal_outbox` by status. `PENDING`/`PROCESSING` rows are exact bounded
   terminal facts awaiting materialization; `DEAD` is a checksum, identity-conflict or exhausted
   retry incident. Never repair either table by inventing tokens or cost.
4. Restore database write health/schema compatibility. Let the V298 scheduler reclaim expired
   leases and confirm each physical attempt reaches exactly one `run_step_usage` row with the same
   `terminal_payload_sha256`; then compare provider-side usage, run totals and managed-AI events.
5. If PostgreSQL could not accept the outbox stage, inspect the configured local recovery directory.
   It is safe only when backed by a persistent/shared volume. The immutable V293 intent remains the
   cross-JVM missing-terminal truth if that last-resort payload is unavailable; retain `UNKNOWN`
   instead of inventing a zero-cost row.
6. Resume only after pending/dead gauges return to zero, a synthetic fixture increments the
   `result="ok"` series, and no new persistence alert fires.

Test only in an isolated environment. First deny `INSERT` on `run_step_usage` while allowing the
outbox table, dispatch a non-paid fixture, verify the exact fact queues, restore the grant, terminate
the first JVM and prove another JVM materializes it once. Separately simulate total PostgreSQL
unavailability and prove the local fallback plus stale-intent alert. Verify pager delivery for
persistence, pending-backlog and dead-letter alerts. Silence is allowed only for a recorded
database-maintenance window with submissions paused.

Every alert needs severity, owner, runbook URL, notification channel, test procedure and silence
policy. Send a synthetic alert and acknowledge it before go-live.

## 11. Backup, restore and disaster recovery

- Confirm Cloud SQL automated backup, PITR and retention in Terraform and in the live API.
- Take an on-demand backup before every migration-bearing release.
- Enable bucket versioning and lifecycle policies; verify workspace retention matches contract.
- Export organization audit data according to the agreed retention schedule.
- Monthly: restore Cloud SQL into an isolated project, boot the same release SHA, run schema
  validation and an authenticated read-only smoke.
- Quarterly: rehearse application rollback plus DB forward-compatibility and record achieved RPO/RTO.
- Never test restore by overwriting the live customer database.

## 12. Acceptance test sequence

Run all checks against the customer hostname while submissions remain paused, except the dedicated
test window.

1. Infrastructure: private networking, no unintended public IP, TLS, WAF, IAM, secrets, backup,
   restore, budget and alerts.
2. Application: readiness, Flyway head, JPA validation, Redis, provider readiness and deployed SHA.
3. Auth/RBAC: SUPER_ADMIN, two ORG_ADMINs, developer, viewer, revoked user, PAT, tenant isolation.
4. Provider contract: every approved provider/model, every denied provider, missing key, bad key,
   circuit open, explicit pin, provider-only tier route, continuation and restart recovery.
5. Brownfield project: create a project, link a real large repo, verify source readiness, index/search,
   architecture, chat grounding, plan approval, code change, tests, reviewer, score evidence and PR.
6. CLI parity: released npm CLI, login/PAT, provider selection, local-hands permission prompts,
   reconnect, restart and final evidence.
7. Web parity: the same project/run/provider actions, SSE reconnect, cost ledger and downloadable
   report.
8. Adversarial: cross-tenant access, prompt injection, shell/file sandbox, malicious repository,
   webhook forgery, stale event replay, duplicate submit and runaway-cost controls.
9. Load/soak: expected and 2x concurrency, largest repository, provider throttling, DB pool pressure,
   Redis eviction, rolling restart and one-zone failure.
10. Operations: alert delivery, log/trace correlation, backup restore and immutable rollback.

Store evidence by release SHA. A UI score is not acceptance evidence; independently inspect the
generated diff, build output, tests, reviewer findings and Git state.

## 13. Go-live and rollback

Go live only after both ExecuteSpec and customer owners sign the acceptance record.

1. Set the approved final flag matrix and export it.
2. Set `submissions.paused=false`.
3. Enable auto-push, failover, initial routing or automatic continuation only if each has separate
   acceptance evidence and the customer-approved allow-list is enforced.
4. Monitor the first runs live and review every provider/model/cost ledger row.
5. Hold daily launch review for the first week and weekly capacity/security review for the pilot.

Rollback procedure:

1. set `submissions.paused=true`;
2. allow safe in-flight work to terminate or explicitly cancel it;
3. point `/opt/app/app.jar` and `CURRENT_SHA` to the last approved compatible release;
4. restart and run health, provider, Flyway, auth and read-only project smoke checks;
5. do not delete, reset or automatically reverse database migrations;
6. if schema compatibility is broken, restore into a new database and follow the rehearsed recovery
   decision rather than mutating the only copy.

## 14. Handover evidence

The final handover bundle must contain:

- architecture and data-flow diagram;
- Terraform plan/apply output and resource inventory;
- IAM and secret inventory without values;
- DNS/TLS/WAF evidence;
- release SHA, image/JAR checksum and Flyway head;
- organization settings, model matrix, provider readiness and effective flag/config exports;
- RBAC and tenant-isolation results;
- brownfield Web and CLI walkthrough results;
- load/soak report and capacity headroom;
- dashboards, alert tests and on-call matrix;
- backup/restore and rollback rehearsal;
- known exceptions with owner and expiry;
- customer and ExecuteSpec sign-off.

Any model should be able to resume onboarding from this bundle without relying on conversation
history.
