# Enterprise One-Pager

> Engineering-truth enterprise offer, refreshed 2026-06-19. Sales copy must not promise beyond this.

## Positioning

ExecuteSpec Enterprise is for teams that want AI-generated pull requests with governance, model control, cost visibility, and verification evidence. The sale is not "unbounded autonomy." The sale is controlled delegation: plan approval, traceable tool activity, model/key policy, validation evidence, auditability, and a commercial/security review path.

## What Enterprise Gets

| Area | Current status |
| --- | --- |
| Runs | Unlimited tier policy; still bounded by platform safety, provider availability, contract terms, and abuse controls. |
| BYOK | Org-level key source modes: `OWN_KEYS`, `HYBRID`, `SYSTEM_ONLY`. |
| Provider lanes | Anthropic, OpenAI, Gemini, DeepSeek, OpenRouter, Fireworks according to configured keys and registered model matrix. |
| Model matrix | Org admin overrides for `(provider, tier)` cells, resolved over YAML defaults. |
| Project workbench | Repos, codemap, architecture docs, project memory, RAG docs, chat, tech debt, reviews, standards, contracts. |
| Verification | Additive verified score, trust caps, validation report, and `verificationEvidence[]` ledger. |
| Sharing | Cross-org invites behind `org.external_sharing_enabled`; public read-only share links. |
| Billing | Custom contract path plus Stripe/Razorpay plumbing where self-serve is used. |
| Admin | Org admin settings, model matrix, external sharing, provider keys, JIRA/Confluence settings, SSO config storage. |
| Security | JWT/PAT auth, scoped SSE tokens, tenant/project access guards, input/tool/shell guardrails, payment webhook signatures. |

## What Enterprise Does Not Yet Fully Get

| Gap | Reality |
| --- | --- |
| SAML SSO enforcement | Config can be stored, but SAML login/enforcement is not implemented. |
| SOC 2 completion | Security posture exists; certification is not complete. |
| Dedicated region | Current infra is GCP `us-east4`; relocation is an explicit infra project. |
| Hard automated uptime SLA | Operational SLOs exist; commercial SLA terms are contractual, not automatic code behavior. |
| Fully automated erasure | Export/DSAR surfaces exist; hard-delete workflow remains operator/legal driven. |
| Per-tenant physical isolation | Tenant guards are app-layer; ORM filter isolation is still planned. |

## Model And Key Policy

Enterprise is the primary tier for org-level model control:

1. YAML/property defaults define the system matrix.
2. ORG_ADMIN can override allowed model cells for the org.
3. BYOK keys can be set at org/user scopes where allowed.
4. Account-scoped provider preference applies when a run/chat omits an explicit provider.

FREE users are outside the model matrix and receive the configured free default model only. Team is BYOK-oriented but does not get the same custom matrix/security-review posture as Enterprise.

See [`features/models-and-routing.md`](features/models-and-routing.md).

## Security And Compliance Position

Credible claims today:

- access control is enforced in controllers/services and pinned by `AccessGuardCoverageTest`,
- secrets are encrypted or redacted in export flows,
- payment webhooks verify gateway signatures,
- LLM calls route through `LlmRouter`, not ad hoc provider calls,
- BYOK lanes exist for direct providers and aggregators,
- public share links and cross-org invites have separate guard models,
- validation reports distinguish deterministic evidence from LLM-assisted judgments.

Do not claim:

- SAML login is end-to-end,
- SOC 2 is complete,
- hard tenant physical isolation exists,
- customer-managed VPC/region is self-serve,
- official benchmark standing without current official scorer artifacts.

## Enterprise Discovery Checklist

- Which repos and languages are in scope?
- Is BYOK mandatory, optional, or prohibited by policy?
- Which provider/model lanes are approved by procurement/security?
- What evidence is required before reviewing or merging an AI-authored PR?
- What data must stay local or inside customer-controlled infrastructure?
- Is SSO required for pilot, or only for production rollout?
- What is the acceptable plan-approval and cost-approval workflow?

## Source Files

- `src/main/java/com/navneet/executespec/auth/entity/Org.java`
- `src/main/java/com/navneet/executespec/auth/service/OrgService.java`
- `src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java`
- `src/main/java/com/navneet/executespec/router/ModelRegistry.java`
- `src/main/java/com/navneet/executespec/router/ModelResolver.java`
- `src/main/java/com/navneet/executespec/sharing/`
- `src/main/java/com/navneet/executespec/report/ValidationReportService.java`
- `src/main/resources/application.yml`
