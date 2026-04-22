# ExecuteSpec — Enterprise one-pager

_For IT / Security / Procurement teams evaluating ExecuteSpec for adoption._

## What ExecuteSpec is

A spec-driven AI coding platform. Your engineers submit a specification in
natural language; ExecuteSpec plans the work, generates code across one or
more repositories, runs a build + tests, and opens a pull request with a
0-100 verified score. Human approval happens at a plan gate — no code is
executed until an engineer clicks approve.

## Security posture

| Control | Status |
|---|---|
| Data-at-rest encryption | AES-256-GCM on all user-supplied secrets (API keys, OAuth tokens, passwords) |
| Data-in-transit encryption | TLS 1.2+ enforced via HSTS (`Strict-Transport-Security: max-age=31536000; includeSubDomains`) |
| Authentication | JWT access (1h TTL) + refresh cookie (7d, HttpOnly, SameSite=Lax) |
| SSO / SAML | Google Workspace OIDC on MAX tier; Okta + Azure AD SAML on Enterprise (Wave 3) |
| RBAC | SUPER_ADMIN / ORG_ADMIN / BILLING_ADMIN / DEVELOPER / PROJECT_ADMIN / PROJECT_DEVELOPER |
| Audit logging | Every privileged action → `activity_audit` table, retained per tier (FREE 30d / PRO 90d / MAX 90d / TEAM 180d / ENTERPRISE unlimited) |
| Secret scanning in user code | `InputSanitizer` + `CodeAbuseScanner` (8-layer defense) — blocks prompt injection, crypto-mining, reverse shells, PowerShell, DNS exfil, cloud-metadata access |
| Infrastructure | GCP us-east4 (N. Virginia); Cloud Run for previews (5-min TTL, managed HTTPS), Cloud SQL for state, Redis for gate coordination |
| SOC 2 Type I | Scoping in progress with Vanta (target: Q3 2026) |
| SOC 2 Type II | Target: Q1 2027 |
| GDPR DSAR | Soft-delete on `app_user`; PostHog analytics can be opted out; admin can hard-delete on request |
| HIPAA / PCI | Not in scope — ExecuteSpec is not a BAA-eligible or PCI-DSS-eligible service |

## Data handling

| Question | Answer |
|---|---|
| Where is customer data stored? | GCP us-east4 (PostgreSQL on Cloud SQL, Redis on GCE VMs, file workspaces on GCS `executespec-workspaces` bucket) |
| Who has access to customer data? | Engineering team (Navneet as sole operator today; CSM role opening Q4 2026). Access logged to `activity_audit`. |
| How is customer code isolated? | Each run gets a dedicated Docker container (read-only filesystem, `--cap-drop ALL`, memory/CPU limits, env-var whitelist); container destroyed at run end. |
| Do you train models on customer data? | No. Model providers (Anthropic, OpenAI, Google) are reached via API with zero-retention prompts on paid plans; ExecuteSpec does not fine-tune models on customer content. |
| Sub-processors | Anthropic API, OpenAI API, Google GenAI, Stripe (US/global billing), Razorpay (India billing), GCP (infra), PostHog (product analytics; opt-out available) |
| Incident response | Operator notification within 2 hours for P1 (security incident); customer notification within 24 hours per ENTERPRISE SLA |
| Deletion on churn | On cancellation, customer data is retained for 30 days for billing reconciliation then hard-deleted |

## Compliance-adjacent

- Stripe handles all payment card data — ExecuteSpec does not store card numbers
- Webhook signatures verified via HMAC SHA-256 (Razorpay, GitHub) or `Stripe-Signature` (Stripe)
- JWT secret validated at boot in prod (`JwtSecretValidator`) — placeholder values block startup
- Rate limiting per tenant via Redis (`RateLimitFilter`) — AUTH / RUNS / WEBHOOK / READ / WRITE / PREVIEW / DEPLOY groups

## What's available on each tier

| | FREE | PRO | TEAM | MAX | ENTERPRISE |
|---|---|---|---|---|---|
| Monthly runs | 5 | 20 credits | 50 credits/seat (shared) | Unlimited (fair-use) | Unlimited + SLA |
| Seats | 1 | 1 | 2-20 | 1 | Unlimited |
| BYOK | No | No | No | Yes | Yes |
| SSO / SAML | No | No | Google OIDC | Google OIDC + SAML | All providers + SCIM (Q1 2027) |
| Audit log retention | 30d | 90d | 180d | 90d | Unlimited + CSV export |
| Uptime SLA | None | None | None | 99.5% | 99.9% with credits |
| Named support | No | No | No | No | Named CSM + P1 4h |
| MSA / DPA | Standard Terms | Standard Terms | Standard Terms | Standard Terms | Negotiable |
| Sub-processor approval | Implicit | Implicit | Implicit | Implicit | Right to object |

## Contract path

1. **Evaluation:** 14-day team trial (up to 5 seats) — email `admin@executespec.ai` with work email + company name
2. **Pilot:** 30-day paid pilot on MAX or TEAM (standard Terms, no negotiation)
3. **Enterprise:** annual contract with negotiable MSA, minimum 10 seats, starts at $1,500/mo (~$75/seat for 20 seats)
4. **Volume pricing:** 21+ seats → direct sales engagement (not self-serve)

## Point of contact

- **Primary:** Navneet Prabhakar — `admin@executespec.ai`
- **Security:** `admin@executespec.ai` with subject line `SECURITY` (24h response target)
- **Procurement:** `admin@executespec.ai` with subject line `PROCUREMENT` (48h response target)

## Appendix — document requests

On request, we can provide:

- [ ] Latest SOC 2 scoping document (pre-Type-I)
- [ ] MSA template (PDF) — standard terms for ENTERPRISE; negotiable
- [ ] DPA template (PDF) — GDPR sub-processor list included
- [ ] Penetration test report — on NDA after signing LOI (Q3 2026 target for first pen-test)
- [ ] Sub-processor registry — full list with data residency + purpose of processing
- [ ] Security questionnaire response — SIG Core + custom questionnaires supported

---

_Last updated: 2026-04-23. This document is under active review as the platform
progresses toward SOC 2 Type I certification._
