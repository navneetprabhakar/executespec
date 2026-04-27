# DPA flow (Data Processing Agreement)

> M-020 (Wave 3) — operator-side runbook for the DPA request &
> signature flow. Companion to the customer-facing
> [`/dpa`](../frontend/src/pages/DpaPage.tsx) page that points
> requesters at this process.

## Why this exists

Enterprise procurement gates almost always require a signed DPA
before the customer can store anything in our platform. EU
customers also need Standard Contractual Clauses (SCCs); India
customers need DPDPA-aligned commitments. Without a smooth flow,
the deal stalls at legal for 4-8 weeks. This doc captures the
end-to-end so the first oncall can run it cold.

## Status today

- **`/dpa` customer-facing page** — explains who needs a DPA, how
  to request one (email `admin@executespec.ai` with
  "DPA Request — [Company Name]"), and what info to include.
- **No automation** — every request goes through a human at the
  moment. That's fine for the first 5-10 enterprise customers; we'll
  formalise via a CRM (HubSpot or Pipedrive) once the volume warrants.

## Inbound flow

1. **Customer emails** `admin@executespec.ai` with the subject
   format `DPA Request — [Company Name]`. Body includes:
   - Legal entity name + jurisdiction
   - Primary contact (name + email + role)
   - Whether they need EU SCCs (default: yes for any EU subsidiary)
   - Estimated user count + processed-data volume
   - Their preferred DPA template OR a request for ours

2. **Triage (oncall, same business day)**
   - Acknowledge receipt with template reply (see § Templates).
   - Tag the email thread `dpa-request` in the shared inbox.
   - Create a row in the (manual for now) DPA tracker:
     `docs/dpa-tracker.md` (gitignored — ops-only).

3. **Drafting (legal, 3-5 business days)**
   - Copy the master DPA template from `legal/dpa-template-v3.docx`
     (off-repo, in the `legal/` Google Drive shared folder).
   - Fill in the parties section + the customer-specific scope
     (data categories + sub-processor list).
   - Attach SCCs (EU Module 2) if required.
   - Send back to the customer for review.

4. **Negotiation (legal, variable)**
   - Customer redlines round 1 → legal review → counter-redlines
     → repeat. Most customers settle in 1-2 rounds.
   - Sticking points live in `docs/dpa-negotiation-playbook.md`
     (off-repo) — common asks like "audit rights every 6 months"
     (we counter to annually) or "delete-on-request within 14 days"
     (we counter to 30).

5. **Signature**
   - Both parties sign in DocuSign or Adobe Sign. PDF stored in
     the customer's record in the (future) CRM; until then,
     `legal/signed-dpas/<company-slug>-<yyyy-mm-dd>.pdf` in the
     Google Drive shared folder.

6. **Activation**
   - Update the customer's `org` row in the DB with their DPA
     signed date (no schema change required; goes into
     `org.notes` JSON: `{"dpa_signed_at": "2026-04-30",
     "dpa_pdf_url": "drive://..."}`).
   - Email the customer with the signed PDF + the activation
     confirmation.

## Templates

### Reply: acknowledgement

```
Hi <name>,

Thanks for the DPA request — we've received it and the legal team
will draft a tailored agreement within 3-5 business days. We'll
include EU Standard Contractual Clauses (Module 2) since
<company> is in <jurisdiction>.

A couple of clarifying questions to speed up the draft:
1. Do you need DPDPA-aligned commitments alongside GDPR?
2. Do you have a preferred DPA template, or shall we send ours?
3. Who's the legal counterparty signatory + email?

Talk soon,
ExecuteSpec Legal
```

### Reply: signed + activated

```
Hi <name>,

Attached is the fully-executed DPA dated <yyyy-mm-dd>. Your
ExecuteSpec org (<org name>) is now flagged as DPA-covered in our
records — this unlocks:
- The "DPA executed" badge on your billing dashboard
- Inclusion in our quarterly sub-processor change notifications
- Priority routing for any data-deletion requests under
  GDPR Art. 17 / DPDPA Sec. 12

If anything in the agreement needs an update down the line — new
sub-processor, jurisdiction change, etc. — reply to this thread
and we'll spin a delta amendment.

Best,
ExecuteSpec
```

## Sub-processor list (current)

The DPA's Annex 2 (sub-processors) MUST be kept in sync with what
we actually use. Current sub-processors:

| Sub-processor | Purpose | Region | Customer notice on change |
|---|---|---|---|
| Google Cloud (GCP) | Compute + Cloud SQL + Storage | us-east4 | 30 days |
| Stripe | Payment processing (USD) | US | 30 days |
| Razorpay | Payment processing (INR) | India | 30 days |
| Anthropic | LLM inference (Claude family) | US | Same-day |
| OpenAI | LLM inference (GPT family) | US | Same-day |
| Google Gemini | LLM inference (Gemini family) | US | Same-day |
| Better Stack | Status page + uptime monitoring | EU | 30 days |
| PostHog | Product analytics | EU | 30 days |
| Gmail (Workspace) | Transactional email | US | 30 days |
| Cloudflare | DNS + CDN + WAF | US | 30 days |

**Same-day notice for LLM providers** is intentional — adding or
removing an LLM provider materially changes the inference path
that customer data flows through, so customers get to opt out
within 1 business day. Other sub-processors get the standard
30-day window per most-favoured-nation DPA templates.

## DPA renewal / amendment workflow

- **Master template revision** (annual): legal updates
  `legal/dpa-template-v3.docx` with the current year's regulatory
  changes (e.g. EU AI Act amendments). Existing customers get a
  notification + an option to re-sign (no force; the original
  agreement remains binding until they choose to update).
- **Sub-processor add/remove**: trigger the Customer notice
  workflow. For same-day items, send notification immediately;
  for 30-day items, send 30 days in advance. Use the
  `legal/sub-processor-change-template.txt` email template.

## Where this connects

- [`/dpa`](../frontend/src/pages/DpaPage.tsx) — customer-facing
  request page.
- [`docs/enterprise-one-pager.md`](enterprise-one-pager.md) —
  procurement-ready security posture for the customer to attach
  to their RFP.
- [`docs/enterprise-sla.md`](enterprise-sla.md) — separate
  document; an org can have an SLA without a DPA (and vice versa).
- [`docs/payment-history.md`](payment-history.md) — DPA-covered
  customers get the same payment audit trail as everyone else;
  no DPA-specific data export.
- [`/api/dsar/me`](../src/main/java/com/navneet/executespec/auth/dsar/DsarController.java)
  (M-017) — once the DPA is signed, the customer's users get
  DSAR rights automatically (the endpoint is already
  AUTH-gated, no extra config).

## Out of scope (tracked separately)

- **CRM-driven DPA-tracker** — once we hit ~10 enterprise
  customers, replace the manual tracker with HubSpot pipeline
  stages.
- **Automated sub-processor change notifications** — currently a
  manual email batch; needs the customer-notification system
  built first.
- **Self-service DPA generator for non-EU + non-DPDPA
  jurisdictions** — most procurement teams want a hand-tailored
  agreement; not worth building until we hit volume.
