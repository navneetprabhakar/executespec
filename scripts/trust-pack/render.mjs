// R-320 — generate the Trust Pack PDF from the live /v3/trust content.
// Renders a self-contained HTML mirror of V3Trust.tsx via Playwright headless
// to a printable letter-sized PDF, then drops the result at
// frontend/public/trust-pack.pdf so the V3Trust button can serve it directly.
//
// The HTML below is a static mirror of the React component — same sections,
// same row labels, same copy. NO new claims; this PDF is just the on-page
// content rendered to a printable surface.
//
// Run from the repo root:
//   node scripts/trust-pack/render.mjs
//
// Re-run after any /v3/trust copy change to keep the PDF in sync with what
// the page renders live.
import { chromium } from 'playwright';
import fs from 'fs/promises';

const HTML = `<!doctype html>
<html><head>
<meta charset="utf-8"/>
<title>ExecuteSpec — Trust Pack</title>
<style>
  @page { size: letter; margin: 0.6in 0.55in; }
  html, body {
    font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Text', system-ui, sans-serif;
    font-size: 10pt; line-height: 1.5; color: #14171C; background: #fff;
    margin: 0; padding: 0;
  }
  h1 { font-size: 22pt; line-height: 1.2; margin: 0 0 6pt; color: #0B0D10; }
  h2 { font-size: 13pt; margin: 16pt 0 8pt; color: #0B0D10; }
  p { margin: 4pt 0; }
  .lead { color: #4C5662; font-size: 10pt; line-height: 1.55; }
  .section {
    background: #ffffff;
    border: 0.5pt solid #ECEEF2;
    border-radius: 6pt;
    padding: 12pt 14pt;
    margin: 10pt 0;
    page-break-inside: avoid;
  }
  .pills { margin: 12pt 0; display: flex; gap: 6pt; flex-wrap: wrap; }
  .pill {
    padding: 3pt 9pt;
    border-radius: 999pt;
    font-size: 9pt;
    font-weight: 600;
    border: 0.5pt solid #d0d4dc;
  }
  .pill.ok { background: rgba(16,185,129,0.10); color: #047857; border-color: rgba(16,185,129,0.32); }
  .pill.brand { background: rgba(91,141,239,0.10); color: #2351AC; border-color: rgba(91,141,239,0.32); }
  .pill.neutral { background: #ECEEF2; color: #4C5662; }
  .row {
    display: flex;
    gap: 10pt;
    align-items: baseline;
    padding: 5pt 0;
    border-bottom: 0.5pt dashed #ECEEF2;
    font-size: 9.5pt;
  }
  .row:last-child { border-bottom: none; }
  .row .lbl {
    flex: 0 0 130pt;
    color: #14171C;
    font-weight: 600;
    font-size: 9pt;
  }
  .row .val { flex: 1; color: #4C5662; line-height: 1.55; }
  .subproc {
    display: flex;
    gap: 10pt;
    align-items: baseline;
    padding: 5pt 0;
    border-bottom: 0.5pt dashed #ECEEF2;
    font-size: 9.5pt;
  }
  .subproc:last-child { border-bottom: none; }
  .subproc .name { flex: 0 0 130pt; color: #14171C; font-weight: 600; font-size: 9pt; }
  .subproc .purpose { flex: 1; color: #4C5662; }
  .subproc .region { flex: 0 0 60pt; text-align: right; }
  strong { color: #0B0D10; }
  .footer {
    text-align: center;
    margin-top: 14pt;
    padding-top: 10pt;
    border-top: 0.5pt solid #ECEEF2;
    font-size: 8pt;
    color: #8A94A6;
  }
  .meta {
    margin-bottom: 6pt;
    font-size: 9pt;
    color: #8A94A6;
  }
  .brand-mark {
    display: inline-block;
    width: 22pt; height: 22pt;
    border-radius: 5pt;
    background: linear-gradient(135deg, #5B8DEF 0%, #7AD1C8 100%);
    color: #fff;
    text-align: center;
    line-height: 22pt;
    font-weight: 700;
    font-size: 11pt;
    margin-right: 8pt;
    vertical-align: middle;
  }
</style></head>
<body>
  <div class="meta">
    <span class="brand-mark">ES</span>
    <strong style="color:#0B0D10;font-size:11pt;">ExecuteSpec — Trust Pack</strong>
    &nbsp;·&nbsp; <span>Last reviewed: 2026-04-27</span>
  </div>

  <h1>Trust at ExecuteSpec</h1>
  <p class="lead">
    Everything procurement needs in one place. Updates land as our compliance
    posture matures — you can also subscribe to changes via
    <strong>security@executespec.ai</strong>.
  </p>

  <div class="pills">
    <span class="pill ok">TLS 1.3 in transit</span>
    <span class="pill ok">AES-256 at rest</span>
    <span class="pill brand">Data residency · us-east4</span>
  </div>

  <div class="section">
    <h2>Audit & certifications</h2>
    <div class="row">
      <span class="lbl">SOC 2 Type II</span>
      <span class="val">Not yet certified. We will publish audit status, auditor name, and target attestation date here once an engagement is formally underway.</span>
    </div>
    <div class="row">
      <span class="lbl">ISO 27001</span>
      <span class="val">Not yet — paired with future SOC 2 attestation.</span>
    </div>
    <div class="row">
      <span class="lbl">HIPAA / FedRAMP</span>
      <span class="val">Not yet — contact us if you need either.</span>
    </div>
  </div>

  <div class="section">
    <h2>Data flow</h2>
    <div class="row">
      <span class="lbl">Compute region</span>
      <span class="val">All compute in <strong>us-east4</strong> (Northern Virginia, GCP). Custom regions available for Enterprise.</span>
    </div>
    <div class="row">
      <span class="lbl">Code retention</span>
      <span class="val">Workspaces destroyed <strong>24 hours</strong> after last access. PR diffs persist in your GitHub repo, never on our infrastructure.</span>
    </div>
    <div class="row">
      <span class="lbl">Model output</span>
      <span class="val">Yours. We claim no rights over the code ExecuteSpec produces under your account.</span>
    </div>
    <div class="row">
      <span class="lbl">Training</span>
      <span class="val">We <strong>do not</strong> train any model on your code, prompts, or run history.</span>
    </div>
    <div class="row">
      <span class="lbl">Encryption</span>
      <span class="val">TLS 1.3 in transit · AES-256 at rest · KMS-managed.</span>
    </div>
  </div>

  <div class="section">
    <h2>Authentication & access</h2>
    <div class="row">
      <span class="lbl">OAuth</span>
      <span class="val">Google · GitHub. SSO is the default for personal accounts.</span>
    </div>
    <div class="row">
      <span class="lbl">SAML / OIDC</span>
      <span class="val">Available on Enterprise. Configure from Team page.</span>
    </div>
    <div class="row">
      <span class="lbl">Session</span>
      <span class="val">JWT with 1-hour access + 7-day refresh; rotation forced on logout-everywhere.</span>
    </div>
    <div class="row">
      <span class="lbl">MFA</span>
      <span class="val">Inherited from your IdP — we don't store passwords for SSO accounts.</span>
    </div>
  </div>

  <div class="section">
    <h2>Sub-processors</h2>
    <div class="subproc"><span class="name">Anthropic</span><span class="purpose">LLM provider</span><span class="region"><span class="pill neutral">US</span></span></div>
    <div class="subproc"><span class="name">OpenAI</span><span class="purpose">LLM provider</span><span class="region"><span class="pill neutral">US</span></span></div>
    <div class="subproc"><span class="name">Google AI (Gemini)</span><span class="purpose">LLM provider</span><span class="region"><span class="pill neutral">US</span></span></div>
    <div class="subproc"><span class="name">Google Cloud Platform</span><span class="purpose">Compute · storage · networking</span><span class="region"><span class="pill neutral">us-east4</span></span></div>
    <div class="subproc"><span class="name">Stripe</span><span class="purpose">Payments (USD)</span><span class="region"><span class="pill neutral">US</span></span></div>
    <div class="subproc"><span class="name">Razorpay</span><span class="purpose">Payments (INR)</span><span class="region"><span class="pill neutral">IN</span></span></div>
    <div class="subproc"><span class="name">PostHog</span><span class="purpose">Product analytics (no PII)</span><span class="region"><span class="pill neutral">US</span></span></div>
    <div class="subproc"><span class="name">Resend</span><span class="purpose">Transactional email</span><span class="region"><span class="pill neutral">US</span></span></div>
  </div>

  <div class="section">
    <h2>Audit log & data export</h2>
    <div class="row">
      <span class="lbl">Audit log</span>
      <span class="val">Every billing / identity / org change is time-stamped, exportable as JSON, and retained for <strong>7 years</strong> on Enterprise.</span>
    </div>
    <div class="row">
      <span class="lbl">Data export</span>
      <span class="val">One-click project export from any project page. Includes all runs, PRs, validation reports, and configuration.</span>
    </div>
    <div class="row">
      <span class="lbl">Data deletion SLA</span>
      <span class="val"><strong>30 days</strong> from request to full erasure. Audit log retains the deletion event for compliance.</span>
    </div>
    <div class="row">
      <span class="lbl">DPA</span>
      <span class="val">Signable from any tier; shipped on Enterprise by default.</span>
    </div>
  </div>

  <div class="section">
    <h2>For your vendor risk team</h2>
    <p>Reach us:</p>
    <ul style="padding-left: 18pt; margin: 6pt 0;">
      <li><strong>Security questions / vendor-risk template:</strong> security@executespec.ai</li>
      <li><strong>Enterprise demo / MSA / custom DPA:</strong> enterprise@executespec.ai</li>
    </ul>
    <p style="font-size: 9pt; color: #8A94A6;">
      Need an MSA, custom DPA, or sub-processor list addition? Email
      enterprise@executespec.ai with your standard procurement template attached.
    </p>
  </div>

  <div class="footer">
    Generated 2026-04-28 from /v3/trust content · Last reviewed 2026-04-27 · executespec.ai/terms · executespec.ai/privacy · executespec.ai/dpa
  </div>
</body></html>`;

await fs.writeFile('/tmp/trust-pack-render.html', HTML);

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto('file:///tmp/trust-pack-render.html');
await page.pdf({
  path: 'frontend/public/trust-pack.pdf',
  format: 'Letter',
  margin: { top: '0.6in', bottom: '0.6in', left: '0.55in', right: '0.55in' },
  printBackground: true,
});
await browser.close();
console.log('Trust Pack PDF generated:', 'frontend/public/trust-pack.pdf');
