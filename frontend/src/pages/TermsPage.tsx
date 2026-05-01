import {Link} from 'react-router-dom';

export default function TermsPage() {
  return (
    <div className='min-h-screen bg-[var(--geist-bg)] text-secondary relative'>
      <div
        aria-hidden
        className='pointer-events-none absolute inset-0 -z-10'
        style={{
          background:
            'radial-gradient(ellipse 60% 40% at 20% 0%, color-mix(in srgb, var(--accent-blue) 10%, transparent), transparent 60%)',
        }}
      />
      {/* Header */}
      <header className='border-b border-[var(--border-default)]'>
        <div className='max-w-3xl mx-auto px-4 sm:px-6 py-4 flex items-center justify-between'>
          <Link
            to='/'
            className='inline-flex items-center gap-2 font-semibold text-primary hover:text-accent-blue transition-colors'
          >
            <span className='inline-flex items-center justify-center w-7 h-7 rounded-md bg-[var(--text-primary)] text-[var(--geist-bg)] font-mono font-bold text-xs'>
              ES
            </span>
            ExecuteSpec
          </Link>
          <nav className='flex items-center gap-4 text-sm text-muted'>
            <Link to='/privacy' className='hover:text-secondary transition-colors'>
              Privacy
            </Link>
            <Link to='/dpa' className='hover:text-secondary transition-colors'>
              DPA
            </Link>
          </nav>
        </div>
      </header>

      {/* Content */}
      <main className='max-w-3xl mx-auto px-4 sm:px-6 py-10 sm:py-14'>
        <span className='inline-block font-mono text-[10.5px] font-medium uppercase tracking-[0.08em] text-accent-blue border border-[color-mix(in_srgb,var(--accent-blue)_40%,transparent)] bg-[color-mix(in_srgb,var(--accent-blue)_12%,transparent)] rounded-full px-2.5 py-0.5 mb-3'>
          Legal
        </span>
        <h1 className='text-2xl sm:text-3xl font-semibold text-primary mb-2 tracking-[-0.02em]'>
          Terms of Service
        </h1>
        <p className='text-sm text-muted mb-10 font-mono'>Last updated: April 21, 2026</p>

        <div className='space-y-10 leading-relaxed'>
          {/* 1 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>1. Service Description</h2>
            <p>
              ExecuteSpec is an autonomous code generation platform. You provide a specification
              describing the software you want, and our multi-agent pipeline plans, writes,
              compiles, validates, and previews the code for you. The platform orchestrates large
              language models from third-party providers to generate production-ready code from your
              instructions.
            </p>
          </section>

          {/* 2 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>2. Account Registration</h2>
            <p>
              To use ExecuteSpec you must create an account with a valid email address and a secure
              password. You are responsible for maintaining the confidentiality of your credentials
              and for all activity that occurs under your account. If you suspect unauthorized
              access, notify us immediately at{' '}
              <a
                href='mailto:admin@executespec.ai'
                className='text-accent-blue font-medium hover:underline'
              >
                admin@executespec.ai
              </a>
              .
            </p>
          </section>

          {/* 3 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>3. Acceptable Use</h2>
            <p className='mb-3'>When using ExecuteSpec, you agree not to:</p>
            <ul className='list-disc list-inside space-y-1.5 text-secondary'>
              <li>
                Generate malicious code, malware, exploits, or any software intended to cause harm
              </li>
              <li>Include personally identifiable information (PII) in your run specifications</li>
              <li>
                Attempt to reverse-engineer, decompile, or extract the platform's proprietary logic
              </li>
              <li>Use the service to violate any applicable law or regulation</li>
              <li>Circumvent usage limits, rate limits, or tier restrictions</li>
            </ul>
            <p className='mt-3'>
              We reserve the right to suspend or terminate accounts that violate these terms.
            </p>
          </section>

          {/* 4 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>4. API Keys</h2>
            <p>
              ExecuteSpec allows you to provide your own third-party API keys (for Anthropic,
              OpenAI, Gemini, and other providers) to power code generation. You are solely
              responsible for safeguarding these keys and for any charges incurred through their
              use. We encrypt all stored API keys at rest and transmit them only over encrypted
              connections. We never share your API keys with other users or third parties.
            </p>
          </section>

          {/* 5 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>5. Generated Code Ownership</h2>
            <p>
              You own all code generated by ExecuteSpec on your behalf. The platform claims no
              intellectual property rights over the output of your runs. You are free to use,
              modify, distribute, and commercialize the generated code without restriction. However,
              you are responsible for reviewing generated code before deploying it to production
              environments.
            </p>
          </section>

          {/* 6 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>6. Third-Party Services</h2>
            <p>
              Your specifications and generated code are processed by large language model
              providers, including Anthropic (Claude), Gemini, and OpenAI (GPT). Your data is sent
              to whichever provider you select for your project. These providers have their own
              terms of service and privacy policies. ExecuteSpec is not responsible for how these
              providers handle data once it leaves our platform, though we select providers that
              commit to not training on customer data.
            </p>
          </section>

          {/* 7 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>7. Payment Terms</h2>
            <p>
              ExecuteSpec offers Free, Pro, and Enterprise subscription tiers. Paid subscriptions
              are billed monthly through Razorpay. If you cancel a paid subscription, your access
              continues until the end of the current billing period, at which point your account
              reverts to the Free tier. Run top-up credits are non-refundable once purchased. We may
              change pricing with 30 days' notice.
            </p>
          </section>

          {/* 8 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>8. Limitation of Liability</h2>
            <p>
              All code generated by ExecuteSpec is provided "as is" without warranty of any kind,
              express or implied. We do not guarantee that generated code will be error-free,
              secure, or fit for any particular purpose. You acknowledge that AI-generated code may
              contain bugs, vulnerabilities, or inaccuracies. ExecuteSpec's total liability to you
              for any claims arising from your use of the service shall not exceed the amount you
              paid us in the 12 months preceding the claim.
            </p>
          </section>

          {/* 9 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>9. Termination</h2>
            <p>
              Either party may terminate this agreement at any time. You can delete your account
              from the Settings page. Upon termination, your data remains available for export for
              30 days, after which it is permanently deleted. We may terminate or suspend your
              account immediately if you violate these terms.
            </p>
          </section>

          {/* 10 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>10. Governing Law</h2>
            <p>
              These terms are governed by the laws of the Republic of India. Any disputes arising
              from these terms or your use of ExecuteSpec shall be subject to the exclusive
              jurisdiction of the courts in Bengaluru, Karnataka, India.
            </p>
          </section>

          {/* 11 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>11. Changes to These Terms</h2>
            <p>
              We may update these terms from time to time. When we make material changes, we will
              notify you by email or through a notice on the platform. Your continued use of
              ExecuteSpec after changes take effect constitutes acceptance of the revised terms.
            </p>
          </section>

          {/* 12 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>12. Contact</h2>
            <p>
              For questions about these terms, contact us at{' '}
              <a
                href='mailto:admin@executespec.ai'
                className='text-accent-blue font-medium hover:underline'
              >
                admin@executespec.ai
              </a>
              .
            </p>
          </section>
        </div>
      </main>

      {/* Footer */}
      <footer className='border-t border-[var(--border-default)] mt-16'>
        <div className='max-w-3xl mx-auto px-6 py-6 flex items-center justify-between text-xs text-muted'>
          <span>&copy; {new Date().getFullYear()} ExecuteSpec. All rights reserved.</span>
          <nav className='flex items-center gap-4'>
            <Link to='/terms' className='text-muted hover:text-secondary transition-colors'>
              Terms
            </Link>
            <Link to='/privacy' className='text-muted hover:text-secondary transition-colors'>
              Privacy
            </Link>
            <Link to='/dpa' className='text-muted hover:text-secondary transition-colors'>
              DPA
            </Link>
          </nav>
        </div>
      </footer>
    </div>
  );
}
