# ExecuteSpec — Enterprise Support SLA

_Applicable to customers on the ENTERPRISE tier. PRO / TEAM customers receive best-effort email support at `admin@executespec.ai`._

## Severity definitions

| Severity | Definition | Example |
|---|---|---|
| **P1 — Critical** | Production down OR security incident. No workaround available. | All runs on the customer's org returning 500; SSO login broken for every user; suspected data exposure. |
| **P2 — High** | Core functionality broken for multiple users; workaround available but inconvenient. | Planner hangs on `HIGH` complexity specs; PR creation fails intermittently. |
| **P3 — Medium** | Non-critical feature broken OR significant UI regression for one user. | Validation report PDF download missing one section; Slack notification wrong channel. |
| **P4 — Low** | Cosmetic issue, documentation request, feature inquiry. | Label color wrong on dark mode; "how do I configure X?" |

## Response-time SLA (ENTERPRISE only)

| Severity | Initial acknowledgement | Update cadence | Resolution target |
|---|---|---|---|
| P1 | 4 hours (24×7) | Every 2 hours until resolved | 24 hours |
| P2 | 8 business hours (09:00-18:00 IST Mon-Fri) | Daily | 5 business days |
| P3 | 1 business day | Weekly | 15 business days |
| P4 | 3 business days | Monthly | Best effort |

**Clock start:** timestamp of the customer's email / in-app ticket / named
CSM message. Clock pauses when the ticket is waiting on customer info.

## Uptime SLA

| Tier | Target | Service credit |
|---|---|---|
| ENTERPRISE | 99.9% | 10% of monthly fee per 0.1% missed; capped at 100% of monthly fee |
| TEAM | 99.5% (target, not credit-eligible) | None — best effort |
| PRO / FREE | No SLA | None |

**Exclusions:** scheduled maintenance (announced ≥ 48h in advance), upstream
provider outages (Anthropic / OpenAI / Gemini / GCP — we attribute through
our fallback chain but cannot guarantee against simultaneous three-provider
failures), customer-triggered abuse.

**Measurement:** monthly uptime = `1 - (total_downtime_seconds / total_month_seconds)`,
based on our Prometheus + Grafana dashboards. Customer can request the raw
data for any month on request.

## Named CSM engagement

_Available when the CSM role is filled (target: Q4 2026)._

- One named CSM per ENTERPRISE customer
- Weekly 30-minute check-in (optional, customer-initiated)
- Quarterly business review covering usage, roadmap alignment, upcoming
  platform changes
- Direct Slack / email / phone contact (phone: P1 only)

Until a CSM is in place, the founder (Navneet) serves as the direct
point of contact for all ENTERPRISE customers. Escalation:
`admin@executespec.ai` with subject `ENTERPRISE ESCALATION`.

## After-hours escalation (P1 only)

- P1 after-hours contact: email `admin@executespec.ai` with subject
  `P1 AFTER-HOURS`. Alerts the founder via SMS bridge.
- Response target: 4 hours 24×7 (including weekends + IST holidays)
- **Not a 24-hour hotline** — a founder-led company cannot offer follow-
  the-sun coverage in v1. When our team headcount supports it we'll
  extend to a full 24×7 rotation + publish a named on-call page.

## Service credit process

When uptime falls below the monthly target:

1. Customer submits a claim within 30 days of the affected month:
   `admin@executespec.ai` subject `SLA CREDIT CLAIM <month>`
2. We validate against our monitoring data + respond within 5 business
   days with the calculated credit amount
3. Credit applied to the next invoice; no cash refunds
4. Credit cap: 100% of that month's fee per affected customer

## Change notice

We'll give 30 days' notice of any breaking API change or SLA adjustment
via:
- Email to the named billing contact on file
- In-app notification banner for the affected org
- Public changelog entry at `app.executespec.ai/changelog`

## Out of scope

- PCI-DSS or HIPAA compliance (not in scope — we do not process
  cardholder or health data; Stripe handles all card data)
- FedRAMP / StateRAMP (not in scope for v1 — US public sector requires
  separate certification path)
- Data residency guarantees beyond "US only" (GCP us-east4). EU
  residency + India residency planned for 2027.

## Contact

- **Primary:** `admin@executespec.ai`
- **Security incidents (24×7):** `admin@executespec.ai` subject `SECURITY`
- **Billing issues:** `admin@executespec.ai` subject `BILLING`
- **P1 after-hours:** `admin@executespec.ai` subject `P1 AFTER-HOURS`

---

_Last updated: 2026-05-12 (1.0.0-M1). This SLA is the reference document for
all ENTERPRISE contracts. Individual MSAs may include customer-specific
overrides — the MSA language controls when conflicts arise._
