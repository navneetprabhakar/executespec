import { Link } from 'react-router-dom';
import { trackV3, V3_EVENTS } from '../lib/events';
import Pill from '../components/Pill';

/**
 * R-151 — /v3/trust page (Amara unlock).
 *
 * Greenfield §4.13: bullet-dense, scannable, attachable to a vendor
 * risk packet in two clicks. SOC 2 status defaults to "Audit in
 * progress with [Auditor TBD]" placeholder per D-151 — user fills
 * in the real auditor name when audit kicks off.
 *
 * Public route. No auth required. Trust Pack PDF download fires the
 * trust_page_pdf_downloaded event for procurement-funnel attribution.
 */
export default function V3Trust() {
  function onPdfDownload() {
    trackV3(V3_EVENTS.TRUST_PAGE_PDF_DOWNLOADED);
    // TODO(R-151 follow-up): when the real Trust Pack PDF lands,
    // point this at /trust-pack.pdf in static assets.
    alert(
      'The Trust Pack PDF lands as a Wave-3 follow-up. Until then, ' +
      'email security@executespec.ai with your vendor-risk template.'
    );
  }

  function onContactSales() {
    trackV3(V3_EVENTS.ENTERPRISE_INQUIRY_SUBMITTED);
    window.location.href = 'mailto:security@executespec.ai?subject=Enterprise%20vendor%20review';
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, paddingBottom: 32 }}>
      {/* Hero */}
      <section style={{ padding: '24px 0 8px' }}>
        <h1 style={{ fontSize: 26, fontWeight: 600, color: 'var(--v3-text)', margin: 0 }}>
          Trust at ExecuteSpec
        </h1>
        <p style={{ fontSize: 12.5, color: 'var(--v3-text-muted)', margin: '6px 0 0', lineHeight: 1.55 }}>
          Everything procurement needs in one place. Last reviewed by our
          security team: 2026-04-27. Updates land as our compliance
          posture matures — you can also subscribe to changes via{' '}
          <a href="mailto:security@executespec.ai" style={{ color: 'var(--v3-brand)' }}>
            security@executespec.ai
          </a>
          .
        </p>
      </section>

      {/* Headline status */}
      <section style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        <Pill tone="warn">SOC 2 Type II — Audit in progress</Pill>
        <Pill tone="ok">TLS 1.3 in transit</Pill>
        <Pill tone="ok">AES-256 at rest</Pill>
        <Pill tone="brand">Data residency · us-east4</Pill>
      </section>

      {/* SOC 2 + audits */}
      <Section title="Audit & certifications">
        <Row label="SOC 2 Type II">
          <span>
            <strong>Audit in progress</strong> with [Auditor name TBD]. Anticipated
            attestation Q3 2026. Letter of engagement available under NDA.
          </span>
        </Row>
        <Row label="ISO 27001">Roadmap — paired with SOC 2 attestation.</Row>
        <Row label="HIPAA / FedRAMP">Not yet — contact us if you need either.</Row>
      </Section>

      {/* Data flow */}
      <Section title="Data flow">
        <Row label="Compute region">
          All compute in <strong>us-east4</strong> (Northern Virginia, GCP).
          Custom regions available for Enterprise.
        </Row>
        <Row label="Code retention">
          Workspaces destroyed <strong>24 hours</strong> after last access.
          PR diffs persist in your GitHub repo, never on our infrastructure.
        </Row>
        <Row label="Model output">
          Yours. We claim no rights over the code ExecuteSpec produces under
          your account.
        </Row>
        <Row label="Training">
          We <strong>do not</strong> train any model on your code, prompts, or
          run history.
        </Row>
        <Row label="Encryption">TLS 1.3 in transit · AES-256 at rest · KMS-managed.</Row>
      </Section>

      {/* Auth */}
      <Section title="Authentication & access">
        <Row label="OAuth">Google · GitHub. SSO is the default for personal accounts.</Row>
        <Row label="SAML / OIDC">Available on Enterprise. Configure from Team page.</Row>
        <Row label="Session">JWT with 1-hour access + 7-day refresh; rotation forced on logout-everywhere.</Row>
        <Row label="MFA">Inherited from your IdP — we don't store passwords for SSO accounts.</Row>
      </Section>

      {/* Sub-processors */}
      <Section title="Sub-processors">
        <SubProcRow name="Anthropic" purpose="LLM provider" region="US" />
        <SubProcRow name="OpenAI" purpose="LLM provider" region="US" />
        <SubProcRow name="Google AI (Gemini)" purpose="LLM provider" region="US" />
        <SubProcRow name="Google Cloud Platform" purpose="Compute · storage · networking" region="us-east4" />
        <SubProcRow name="Stripe" purpose="Payments (USD)" region="US" />
        <SubProcRow name="Razorpay" purpose="Payments (INR)" region="IN" />
        <SubProcRow name="PostHog" purpose="Product analytics (no PII)" region="US" />
        <SubProcRow name="Resend" purpose="Transactional email" region="US" />
      </Section>

      {/* Audit + DSAR */}
      <Section title="Audit log & data export">
        <Row label="Audit log">
          Every billing / identity / org change is time-stamped, exportable as
          JSON, and retained for <strong>7 years</strong> on Enterprise.
        </Row>
        <Row label="Data export">
          One-click project export from any project page. Includes all runs,
          PRs, validation reports, and configuration.
        </Row>
        <Row label="Data deletion SLA">
          <strong>30 days</strong> from request to full erasure. Audit log
          retains the deletion event for compliance.
        </Row>
        <Row label="DPA">Signable from any tier; shipped on Enterprise by default.</Row>
      </Section>

      {/* Contact + Trust Pack */}
      <Section title="For your vendor risk team">
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button
            type="button"
            onClick={onPdfDownload}
            style={{
              padding: '8px 14px',
              fontSize: 12.5,
              fontWeight: 600,
              background: 'var(--v3-brand)',
              color: 'var(--v3-text-on-brand)',
              border: 'none',
              borderRadius: 6,
              cursor: 'pointer',
              fontFamily: 'inherit',
            }}
          >
            Download Trust Pack (PDF)
          </button>
          <button
            type="button"
            onClick={onContactSales}
            style={{
              padding: '8px 14px',
              fontSize: 12.5,
              fontWeight: 500,
              background: 'transparent',
              color: 'var(--v3-text)',
              border: '1px solid var(--v3-border)',
              borderRadius: 6,
              cursor: 'pointer',
              fontFamily: 'inherit',
            }}
          >
            Contact security@executespec.ai
          </button>
          {/* Conversion-fix #A2: enterprise-evaluator personas (Sarah-the-CTO)
              convert via demo not mailto. Stays mailto-style for MVP — when
              Calendly is set up, just swap the href. */}
          <a
            href="mailto:enterprise@executespec.ai?subject=Demo%20request%20—%20enterprise%20evaluation&body=Briefly%20describe%20your%20org%20size%2C%20use%20case%2C%20and%20preferred%20demo%20window."
            style={{
              padding: '8px 14px',
              fontSize: 12.5,
              fontWeight: 600,
              background: 'rgba(91,141,239,0.12)',
              color: 'var(--v3-brand)',
              border: '1px solid rgba(91,141,239,0.36)',
              borderRadius: 6,
              cursor: 'pointer',
              fontFamily: 'inherit',
              textDecoration: 'none',
            }}
          >
            Book an enterprise demo →
          </a>
        </div>
        <p style={{ margin: '12px 0 0', fontSize: 11, color: 'var(--v3-text-subtle)' }}>
          Need an MSA, custom DPA, or sub-processor list addition?{' '}
          <Link to="/v3/pricing" style={{ color: 'var(--v3-brand)' }}>
            See Enterprise →
          </Link>
        </p>
      </Section>

      <footer
        style={{
          textAlign: 'center',
          padding: '12px 0',
          fontSize: 10.5,
          color: 'var(--v3-text-subtle)',
        }}
      >
        Last reviewed: 2026-04-27 ·{' '}
        <Link to="/terms" style={{ color: 'var(--v3-text-subtle)' }}>
          Terms
        </Link>{' '}
        ·{' '}
        <Link to="/privacy" style={{ color: 'var(--v3-text-subtle)' }}>
          Privacy
        </Link>{' '}
        ·{' '}
        <Link to="/dpa" style={{ color: 'var(--v3-text-subtle)' }}>
          DPA
        </Link>
      </footer>
    </div>
  );
}

// ─── primitives ─────────────────────────────────────────────────────────────

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        padding: 16,
      }}
    >
      <h2 style={{ margin: '0 0 10px', fontSize: 14, fontWeight: 600, color: 'var(--v3-text)' }}>
        {title}
      </h2>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>{children}</div>
    </section>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div
      style={{
        display: 'flex',
        gap: 12,
        alignItems: 'baseline',
        padding: '4px 0',
        borderBottom: '1px dashed var(--v3-border)',
        fontSize: 12,
        color: 'var(--v3-text-muted)',
      }}
    >
      <span
        style={{
          flex: '0 0 140px',
          color: 'var(--v3-text)',
          fontWeight: 500,
          fontSize: 11.5,
        }}
      >
        {label}
      </span>
      <span style={{ flex: 1, lineHeight: 1.55 }}>{children}</span>
    </div>
  );
}

function SubProcRow({ name, purpose, region }: { name: string; purpose: string; region: string }) {
  return (
    <div
      style={{
        display: 'flex',
        gap: 12,
        alignItems: 'baseline',
        padding: '4px 0',
        borderBottom: '1px dashed var(--v3-border)',
        fontSize: 12,
      }}
    >
      <span style={{ flex: '0 0 140px', color: 'var(--v3-text)', fontWeight: 500, fontSize: 11.5 }}>
        {name}
      </span>
      <span style={{ flex: 1, color: 'var(--v3-text-muted)' }}>{purpose}</span>
      <Pill tone="neutral" size="sm">{region}</Pill>
    </div>
  );
}
