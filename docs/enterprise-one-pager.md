# ENTERPRISE One-Pager

> Recreated 2026-06-10. This is the engineering-truth version of the enterprise offer. Sales copy must not promise beyond this.

## What ENTERPRISE Gets

| Area | Current status |
| --- | --- |
| Runs | Unlimited quota by tier policy; still bounded by platform safety and provider availability |
| BYOK | Org-level key source modes: `OWN_KEYS`, `HYBRID`, `SYSTEM_ONLY` |
| Model matrix | Org admin overrides for `(provider, tier)` cells, resolved over YAML defaults |
| Project workbench | Repos, codemap, architecture docs, project memory, RAG docs, chat, tech debt, reviews |
| Sharing | Cross-org invites behind `org.external_sharing_enabled`; public read-only share links |
| Billing | Custom contract path plus Stripe/Razorpay plumbing where self-serve is used |
| Admin | Org admin settings, model matrix, external sharing, SSO config CRUD |
| Security | JWT/PAT auth, tenant access guards, project access guards, input/tool/shell guardrails |

## What ENTERPRISE Does Not Yet Fully Get

| Gap | Reality |
| --- | --- |
| SAML SSO | Config can be stored, but SAML enforcement is rejected until the SAML handshake is implemented |
| Dedicated region | Current infra is GCP `us-east4`; relocation is an explicit infra project |
| Hard enterprise uptime contract | Operational SLOs exist; commercial SLA terms are policy, not automatic code behavior |
| Fully automated erasure | Export/DSAR surfaces exist; hard-delete workflow remains operator/legal driven |
| Per-tenant physical isolation | Tenant guards are app-layer; ORM filter isolation is still planned |

## Model And Key Policy

ENTERPRISE is the primary tier for org-level model control:

1. YAML/property defaults define the system matrix.
2. ORG_ADMIN can override allowed model cells for the org.
3. BYOK users can override according to user-level key/config rules.

FREE users are outside the model matrix and receive the configured free default model only.

See `docs/features/models-and-routing.md`.

## Security And Compliance Position

The credible claims today:

- access control is enforced in controllers/services and pinned by `AccessGuardCoverageTest`,
- secrets are encrypted or redacted in export flows,
- payment webhooks verify gateway signatures,
- LLM calls route through `LlmRouter`, not ad hoc provider calls,
- BYOK lanes exist for direct providers and aggregators,
- public share links and cross-org invites have separate guard models.

Do not claim:

- SAML is end-to-end,
- SOC 2 is complete,
- hard tenant physical isolation exists,
- customer-managed VPC/region is self-serve.

## Source Files

- `src/main/java/com/navneet/executespec/auth/entity/Org.java`
- `src/main/java/com/navneet/executespec/auth/service/OrgService.java`
- `src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java`
- `src/main/java/com/navneet/executespec/router/ModelRegistry.java`
- `src/main/java/com/navneet/executespec/router/ModelResolver.java`
- `src/main/java/com/navneet/executespec/sharing/`
- `src/main/resources/application.yml`
