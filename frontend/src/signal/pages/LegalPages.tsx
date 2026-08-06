import {ReactNode} from 'react';
import {Link} from 'react-router-dom';

export function TermsPage() {
  return (
    <LegalShell title='Terms of Service' subtitle='Last updated 1 May 2026'>
      <p>By using ExecuteSpec ("we", "us") you agree to these terms.</p>
      <h3>Acceptable use</h3>
      <p>
        You may use the platform to plan, build, test, and ship code via AI agents. You may NOT use
        it to generate malware, target individuals, scrape services in violation of their terms, or
        run any workload that violates applicable law.
      </p>
      <h3>Billing</h3>
      <p>
        Paid plans are billed monthly or annually as selected. Plan runs reset each billing cycle;
        top-up credits carry the validity period shown at purchase. Subscription cancellation stops
        future renewals but does not refund the current period.
      </p>
      <h3>Data ownership</h3>
      <p>
        You own everything you submit and everything the agent produces. We process it solely to
        operate the service. We do not train foundation models on your code.
      </p>
      <h3>Termination</h3>
      <p>
        Either party may terminate at any time. We retain audit metadata for 30 days; you can
        request a full GDPR-export ZIP from Account → Security & data.
      </p>
      <h3>Governing law</h3>
      <p>
        These terms are governed by the laws of England and Wales. Disputes go to the courts of
        London.
      </p>
      <p>
        Questions:{' '}
        <a href='mailto:legal@executespec.ai' className='signal-md-link'>
          legal@executespec.ai
        </a>
      </p>
    </LegalShell>
  );
}

export function PrivacyPage() {
  return (
    <LegalShell title='Privacy Policy' subtitle='Last updated 1 May 2026'>
      <h3>What we collect</h3>
      <p>
        Account data (email, name, OAuth provider IDs), usage data (runs, repos linked, spec text,
        cost), and operational logs. No payment-card data ever touches our servers — Stripe +
        Razorpay handle it directly.
      </p>
      <h3>Where it lives</h3>
      <p>
        Data is stored in EU (eu-west-2) by default; US (us-east4) for customers who explicitly opt
        in. All in transit via TLS 1.3, at rest via AES-256.
      </p>
      <h3>Who we share with</h3>
      <p>
        Anthropic, OpenAI, and Google as inference providers; Stripe + Razorpay as payment
        processors; AWS/GCP as infra. Standard DPAs in place with each.
      </p>
      <h3>Your rights (GDPR)</h3>
      <p>
        Access, rectify, delete, export, restrict, object. Use Account → Security & data → "Export
        my data" to download a full ZIP, or email{' '}
        <a href='mailto:privacy@executespec.ai' className='signal-md-link'>
          privacy@executespec.ai
        </a>
        .
      </p>
      <h3>Retention</h3>
      <p>
        Account data while the account is active. Run data for as long as you keep your account, or
        until you delete the run. Audit logs for 30 days. Deleted accounts purge within 14 days.
      </p>
      <h3>Cookies</h3>
      <p>Strictly necessary (auth session) and functional (theme). No advertising cookies.</p>
    </LegalShell>
  );
}

export function DpaPage() {
  return (
    <LegalShell
      title='Data Processing Addendum'
      subtitle='GDPR Article 28 — for TEAM + ENTERPRISE customers'
    >
      <p>
        This DPA forms part of your subscription agreement. It governs ExecuteSpec's processing of
        personal data on your behalf as a Processor.
      </p>
      <h3>1. Scope</h3>
      <p>
        Personal data your authorised users submit through specs, repos, chat messages, and
        integration sources (Jira, Confluence, GitHub).
      </p>
      <h3>2. Sub-processors</h3>
      <p>
        Current sub-processors: AWS, GCP, Anthropic, OpenAI, Google, Stripe, Razorpay. Updated list
        at{' '}
        <a href='mailto:legal@executespec.ai' className='signal-md-link'>
          legal@executespec.ai
        </a>
        . 30-day prior notice on changes for ENTERPRISE customers.
      </p>
      <h3>3. Security measures</h3>
      <p>
        TLS 1.3 in transit; AES-256 at rest (managed Cloud SQL). Provider credentials are stored
        under a versioned encryption envelope that fails closed. Role-based access control with
        tenant-isolation guards enforced in code and pinned by automated tests. Code execution is
        constrained by a shell-command policy, a filesystem-path sandbox and generated-credential
        scanning, and runs in a per-run isolated workspace directory. Containerised test execution
        applies resource limits, drops capabilities and uses a read-only root filesystem. Audit-log
        retention 30 days.
      </p>
      <h3>4. Cross-border transfers</h3>
      <p>
        SCCs (2021/914) in place for any transfer outside the UK/EEA. Customers can pin to EU-only
        inference where the chosen model supports it.
      </p>
      <h3>5. Breach notification</h3>
      <p>We notify affected customers within 72 hours of confirming a personal-data breach.</p>
      <h3>6. Audit + termination</h3>
      <p>
        ENTERPRISE customers may request our current security documentation and questionnaire
        responses under NDA. Upon termination, customer data is purged within 14 days; backups roll
        off within 35 days.
      </p>
      <p>
        To request a signed copy:{' '}
        <a href='mailto:legal@executespec.ai' className='signal-md-link'>
          legal@executespec.ai
        </a>
      </p>
    </LegalShell>
  );
}

/**
 * Reading shell for the three legal routes.
 *
 * The legal WORDING above is not a design surface and is deliberately
 * untouched — only its typography and layout are. This used to inject a
 * per-page inline `<style>` element whose selectors (`article h3`, `article p`,
 * `article a`) were unscoped and leaked to every other `<article>` on the page
 * for as long as a legal route was mounted. The rules now live in
 * `.signal-legal*` in signal.css, which also gives them a phone gutter and a
 * 680px reading measure.
 */
function LegalShell({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle: string;
  children: ReactNode;
}) {
  return (
    <div className='signal-legal'>
      <div className='signal-legal-inner'>
        <Link to='/login' className='signal-legal-back'>
          ← Back to ExecuteSpec
        </Link>
        <h1>{title}</h1>
        <p className='signal-legal-updated'>{subtitle}</p>
        <article className='signal-legal-body'>{children}</article>
      </div>
    </div>
  );
}
