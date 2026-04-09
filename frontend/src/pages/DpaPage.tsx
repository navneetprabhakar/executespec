import { Link } from 'react-router-dom';

export default function DpaPage() {
  return (
    <div className="min-h-screen bg-[var(--geist-bg)] text-secondary">
      {/* Header */}
      <header className="border-b border-[var(--border-default)] bg-[var(--gray-100)]">
        <div className="max-w-3xl mx-auto px-6 py-5 flex items-center justify-between">
          <Link to="/" className="text-lg font-bold text-[var(--text-secondary)] hover:text-accent-blue transition-colors">
            executespec
          </Link>
          <nav className="flex items-center gap-4 text-sm text-muted">
            <Link to="/terms" className="hover:text-secondary transition-colors">Terms</Link>
            <Link to="/privacy" className="hover:text-secondary transition-colors">Privacy</Link>
          </nav>
        </div>
      </header>

      {/* Content */}
      <main className="max-w-3xl mx-auto px-6 py-12">
        <h1 className="text-3xl font-bold text-primary mb-2">Data Processing Agreement</h1>
        <p className="text-sm text-muted mb-10">Last updated: March 31, 2026</p>

        <div className="space-y-10 leading-relaxed">
          {/* Intro */}
          <section>
            <h2 className="text-xl font-semibold text-primary mb-3">Overview</h2>
            <p>
              This page summarizes how ExecuteSpec processes data on behalf of its customers. For
              enterprise customers who require a formal, signed Data Processing Agreement (DPA)
              as part of their procurement or compliance process, please contact us at{' '}
              <a href="mailto:admin@executespec.ai" className="text-accent-blue font-medium hover:underline">
                admin@executespec.ai
              </a>{' '}
              and we will provide a full DPA tailored to your organization's requirements.
            </p>
          </section>

          {/* Scope */}
          <section>
            <h2 className="text-xl font-semibold text-primary mb-3">Data Processing Scope</h2>
            <p className="mb-3">
              When you use ExecuteSpec, we process the following categories of data on your behalf:
            </p>
            <ul className="list-disc list-inside space-y-1.5 text-secondary">
              <li><span className="text-primary font-medium">Specifications:</span> the natural-language descriptions you provide for code generation runs</li>
              <li><span className="text-primary font-medium">Generated artifacts:</span> source code, configuration files, and test suites produced by the pipeline</li>
              <li><span className="text-primary font-medium">Validation data:</span> compilation results, review findings, and test execution reports</li>
              <li><span className="text-primary font-medium">Context data:</span> JIRA tickets, Confluence pages, or other documents you choose to attach to a run</li>
            </ul>
            <p className="mt-3">
              All processing is performed solely to deliver the code generation service you
              requested. We do not use your data for any other purpose, including model training.
            </p>
          </section>

          {/* Sub-processors */}
          <section>
            <h2 className="text-xl font-semibold text-primary mb-3">Sub-Processors</h2>
            <p className="mb-3">
              ExecuteSpec relies on the following sub-processors to deliver its service:
            </p>
            <div className="rounded-sm border border-[var(--border-default)] overflow-hidden">
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-[var(--gray-200)] text-secondary">
                    <th className="text-left px-4 py-2.5 font-medium">Provider</th>
                    <th className="text-left px-4 py-2.5 font-medium">Purpose</th>
                    <th className="text-left px-4 py-2.5 font-medium">Location</th>
                  </tr>
                </thead>
                <tbody className="text-secondary">
                  <tr className="border-t border-[var(--border-default)]">
                    <td className="px-4 py-2.5">Google Cloud Platform</td>
                    <td className="px-4 py-2.5">Infrastructure, database, storage</td>
                    <td className="px-4 py-2.5">us-east4 (Virginia, USA)</td>
                  </tr>
                  <tr className="border-t border-[var(--border-default)]">
                    <td className="px-4 py-2.5">Anthropic</td>
                    <td className="px-4 py-2.5">LLM processing (Claude)</td>
                    <td className="px-4 py-2.5">USA</td>
                  </tr>
                  <tr className="border-t border-[var(--border-default)]">
                    <td className="px-4 py-2.5">OpenAI</td>
                    <td className="px-4 py-2.5">LLM processing (GPT)</td>
                    <td className="px-4 py-2.5">USA</td>
                  </tr>
                  <tr className="border-t border-[var(--border-default)]">
                    <td className="px-4 py-2.5">Gemini (Google)</td>
                    <td className="px-4 py-2.5">LLM processing (Gemini models)</td>
                    <td className="px-4 py-2.5">USA</td>
                  </tr>
                  <tr className="border-t border-[var(--border-default)]">
                    <td className="px-4 py-2.5">Razorpay</td>
                    <td className="px-4 py-2.5">Payment processing</td>
                    <td className="px-4 py-2.5">India</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p className="mt-3">
              Your data is sent only to the LLM provider you select for your project. We will
              notify you before adding new sub-processors.
            </p>
          </section>

          {/* Compliance */}
          <section>
            <h2 className="text-xl font-semibold text-primary mb-3">Regulatory Compliance</h2>
            <p className="mb-3">ExecuteSpec is committed to compliance with applicable data protection regulations:</p>
            <ul className="list-disc list-inside space-y-1.5 text-secondary">
              <li>
                <span className="text-primary font-medium">GDPR (EU):</span> We process data lawfully under
                contractual necessity. EU customers can request a DPA with Standard Contractual Clauses (SCCs)
                for international data transfers.
              </li>
              <li>
                <span className="text-primary font-medium">DPDPA (India):</span> We comply with the Digital Personal
                Data Protection Act, 2023, including data principal rights, purpose limitation, and data
                minimization requirements.
              </li>
            </ul>
          </section>

          {/* Security */}
          <section>
            <h2 className="text-xl font-semibold text-primary mb-3">Security Measures</h2>
            <ul className="list-disc list-inside space-y-1.5 text-secondary">
              <li>Encryption at rest (Google-managed keys) and in transit (TLS 1.2+)</li>
              <li>Application-level encryption for stored API keys</li>
              <li>Role-based access control (RBAC) with principle of least privilege</li>
              <li>JWT-based authentication with short-lived access tokens</li>
              <li>Isolated container execution for code previews</li>
              <li>Structured audit logging for all administrative actions</li>
            </ul>
          </section>

          {/* Contact */}
          <section>
            <h2 className="text-xl font-semibold text-primary mb-3">Request a DPA</h2>
            <p>
              Enterprise customers who need a signed DPA should contact{' '}
              <a href="mailto:admin@executespec.ai" className="text-accent-blue font-medium hover:underline">
                admin@executespec.ai
              </a>.
              We typically respond within 2 business days and can accommodate custom clauses
              where reasonable.
            </p>
          </section>
        </div>
      </main>

      {/* Footer */}
      <footer className="border-t border-[var(--border-default)] mt-16">
        <div className="max-w-3xl mx-auto px-6 py-6 flex items-center justify-between text-xs text-muted">
          <span>&copy; {new Date().getFullYear()} ExecuteSpec. All rights reserved.</span>
          <nav className="flex items-center gap-4">
            <Link to="/terms" className="text-muted hover:text-secondary transition-colors">Terms</Link>
            <Link to="/privacy" className="text-muted hover:text-secondary transition-colors">Privacy</Link>
            <Link to="/dpa" className="text-muted hover:text-secondary transition-colors">DPA</Link>
          </nav>
        </div>
      </footer>
    </div>
  );
}
