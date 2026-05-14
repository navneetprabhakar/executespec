import {Link} from 'react-router-dom';

export default function PrivacyPage() {
  return (
    <div className='min-h-dvh bg-[var(--geist-bg)] text-secondary'>
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
            <Link to='/terms' className='hover:text-secondary transition-colors'>
              Terms
            </Link>
            <Link to='/dpa' className='hover:text-secondary transition-colors'>
              DPA
            </Link>
          </nav>
        </div>
      </header>

      {/* Content */}
      <main className='max-w-3xl mx-auto px-4 sm:px-6 py-10 sm:py-14 relative'>
        <div
          aria-hidden
          className='pointer-events-none absolute inset-0 -z-10'
          style={{
            background:
              'radial-gradient(ellipse 60% 40% at 20% 0%, color-mix(in srgb, var(--accent-blue) 10%, transparent), transparent 60%)',
          }}
        />
        <span className='inline-block font-mono text-[10.5px] font-medium uppercase tracking-[0.08em] text-accent-blue border border-[color-mix(in_srgb,var(--accent-blue)_40%,transparent)] bg-[color-mix(in_srgb,var(--accent-blue)_12%,transparent)] rounded-full px-2.5 py-0.5 mb-3'>
          Legal
        </span>
        <h1 className='text-2xl sm:text-3xl font-semibold text-primary mb-2 tracking-[-0.02em]'>
          Privacy Policy
        </h1>
        <p className='text-sm text-muted mb-10 font-mono'>Last updated: April 21, 2026</p>

        <div className='space-y-10 leading-relaxed'>
          {/* 1 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>1. Data We Collect</h2>
            <p className='mb-3'>
              We collect only the data necessary to provide and improve ExecuteSpec. This includes:
            </p>
            <ul className='list-disc list-inside space-y-1.5 text-secondary'>
              <li>
                <span className='text-primary font-medium'>Account information:</span> your email
                address and name
              </li>
              <li>
                <span className='text-primary font-medium'>Run specifications:</span> the
                natural-language descriptions you submit for code generation
              </li>
              <li>
                <span className='text-primary font-medium'>Generated code:</span> all files produced
                by the pipeline during your runs
              </li>
              <li>
                <span className='text-primary font-medium'>Usage metrics:</span> run counts,
                response times, and feature usage to improve the platform
              </li>
              <li>
                <span className='text-primary font-medium'>Payment data:</span> processed by
                Razorpay; we do not store card numbers
              </li>
            </ul>
          </section>

          {/* 2 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>2. Data Processors</h2>
            <p>
              To generate code, your specifications are sent to the LLM provider you select for your
              project. Our current providers are:
            </p>
            <ul className='list-disc list-inside space-y-1.5 mt-3 text-secondary'>
              <li>
                <span className='text-primary font-medium'>Anthropic</span> — Claude models (Opus,
                Sonnet, Haiku)
              </li>
              <li>
                <span className='text-primary font-medium'>OpenAI</span> — GPT models
              </li>
              <li>
                <span className='text-primary font-medium'>Gemini</span> — Gemini models (Flash,
                Pro)
              </li>
            </ul>
            <p className='mt-3'>
              Your data is sent only to the provider you choose. All providers have committed to not
              using customer data for model training. Each provider's own privacy policy governs
              their handling of data.
            </p>
          </section>

          {/* 3 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>3. Data Storage</h2>
            <p>
              All data is stored on Google Cloud Platform in the{' '}
              <strong className='text-primary'>us-east4</strong> (Northern Virginia) region. Data is
              encrypted at rest using Google-managed encryption keys. All data in transit is
              encrypted via TLS. API keys you provide are encrypted at rest with application-level
              encryption before being stored in the database.
            </p>
          </section>

          {/* 4 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>4. Data Retention</h2>
            <p>
              We retain your run data (specifications, generated code, and validation reports) based
              on your subscription tier:
            </p>
            <div className='mt-4 rounded-sm border border-[var(--border-default)] overflow-hidden'>
              <table className='w-full text-sm'>
                <thead>
                  <tr className='bg-[var(--gray-200)] text-secondary'>
                    <th className='text-left px-4 py-2.5 font-medium'>Tier</th>
                    <th className='text-left px-4 py-2.5 font-medium'>Retention</th>
                  </tr>
                </thead>
                <tbody className='text-secondary'>
                  <tr className='border-t border-[var(--border-default)]'>
                    <td className='px-4 py-2.5'>Free</td>
                    <td className='px-4 py-2.5'>30 days</td>
                  </tr>
                  <tr className='border-t border-[var(--border-default)]'>
                    <td className='px-4 py-2.5'>Pro</td>
                    <td className='px-4 py-2.5'>90 days</td>
                  </tr>
                  <tr className='border-t border-[var(--border-default)]'>
                    <td className='px-4 py-2.5'>Enterprise</td>
                    <td className='px-4 py-2.5'>Unlimited</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p className='mt-3'>
              After your retention period expires, data is permanently deleted. Account information
              (email, name) is retained until you delete your account.
            </p>
          </section>

          {/* 5 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>5. Your Rights</h2>
            <p className='mb-3'>You have the right to:</p>
            <ul className='list-disc list-inside space-y-1.5 text-secondary'>
              <li>
                <span className='text-primary font-medium'>Access your data:</span> view all
                specifications, generated code, and reports through the dashboard
              </li>
              <li>
                <span className='text-primary font-medium'>Export your data:</span> download your
                entire workspace as a ZIP archive from the Settings page
              </li>
              <li>
                <span className='text-primary font-medium'>Request deletion:</span> delete your
                account and all associated data from Settings, or email us
              </li>
              <li>
                <span className='text-primary font-medium'>Correct your data:</span> update your
                name and email from your account settings
              </li>
            </ul>
            <p className='mt-3'>
              Deletion requests are processed within 30 days. To exercise any of these rights, use
              the in-app settings or contact{' '}
              <a
                href='mailto:admin@executespec.ai'
                className='text-accent-blue font-medium hover:underline'
              >
                admin@executespec.ai
              </a>
              .
            </p>
          </section>

          {/* 6 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>6. Cookies</h2>
            <p>
              ExecuteSpec uses a single httpOnly cookie to store your authentication token (JWT).
              This cookie is essential for the service to function and cannot be disabled. We do not
              use tracking cookies, advertising cookies, or any third-party cookie-based analytics.
              No cookie consent banner is needed because we only use strictly necessary cookies.
            </p>
          </section>

          {/* 7 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>7. Analytics</h2>
            <p>
              We collect basic, anonymous usage metrics (page views, feature usage, error rates) to
              improve the platform. We do not use third-party analytics services like Google
              Analytics. We do not track you across websites. We do not sell or share usage data
              with any third party.
            </p>
          </section>

          {/* 8 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>8. Children's Privacy</h2>
            <p>
              ExecuteSpec is not intended for use by individuals under 16 years of age. We do not
              knowingly collect data from children. If you believe a child has provided us with
              personal data, please contact us and we will delete it promptly.
            </p>
          </section>

          {/* 9 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>9. Changes to This Policy</h2>
            <p>
              We may update this privacy policy from time to time. When we make material changes, we
              will notify you by email or through a notice on the platform. The "Last updated" date
              at the top of this page reflects the most recent revision.
            </p>
          </section>

          {/* 10 */}
          <section>
            <h2 className='text-xl font-semibold text-primary mb-3'>10. Contact</h2>
            <p>
              For questions about this privacy policy or how we handle your data, contact us at{' '}
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
