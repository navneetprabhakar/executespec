import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Card, Button, KvList} from '@/signal/components/Primitives';

export function TrustPage() {
  return (
    <>
      <TopBar title='Trust & security' sub='Security · DPA · GDPR' back backTo='/login' />
      <Feed>
        <div className='signal-quick-grid'>
          {[
            {label: 'DPA (GDPR Art. 28)', v: 'Available', tone: 'mint'},
            {label: 'GDPR export + erasure', v: 'Built-in', tone: 'mint'},
            {label: 'Sandboxed execution', v: '8 layers', tone: 'signal'},
            {label: 'Training on your code', v: 'Never', tone: 'mint'},
          ].map((b) => (
            <div key={b.label} className={`signal-quick-card ${b.tone}`}>
              <div className='icon-bg'>✓</div>
              <h4>{b.label}</h4>
              <div
                className='count'
                style={{color: 'var(--signal-ink)', fontSize: 14, fontWeight: 600}}
              >
                {b.v}
              </div>
            </div>
          ))}
        </div>

        <SectionHeader>What we encrypt</SectionHeader>
        <Card>
          <KvList
            pairs={[
              {k: 'At rest', v: 'AES-256 (managed Cloud SQL)'},
              {k: 'In transit', v: 'TLS 1.3'},
              {k: 'API keys (BYOK)', v: 'Server-side storage · never logged'},
              {k: 'Source code', v: 'Workspace-local · cleared at run end'},
              {k: 'Backups', v: 'Daily · 30d retention · encrypted'},
            ]}
          />
        </Card>

        <SectionHeader>What we never do</SectionHeader>
        <Card>
          <ul style={{padding: '0 0 0 18px', margin: 0, fontSize: 14, lineHeight: 1.7}}>
            <li>Train any model on your source code</li>
            <li>Retain your code outside the run workspace</li>
            <li>Share your data with third parties beyond the LLM provider you picked</li>
            <li>Read your code without the run-scoped JWT proving you authorized it</li>
          </ul>
        </Card>

        <SectionHeader>How execution is constrained</SectionHeader>
        <Card>
          <ul style={{padding: '0 0 0 18px', margin: 0, fontSize: 14, lineHeight: 1.7}}>
            <li>Every run gets its own workspace directory, retained for run evidence</li>
            <li>Shell commands and file access are restricted by an enforced policy</li>
            <li>Generated code is scanned for abuse patterns before execution</li>
            <li>Test execution runs in read-only, capability-dropped containers</li>
            <li>Tenant access guards are enforced in code and pinned by automated tests</li>
          </ul>
        </Card>

        <SectionHeader>Where we are on certifications</SectionHeader>
        <Card>
          <div style={{fontSize: 14, lineHeight: 1.7, color: 'var(--signal-text-dim)'}}>
            We're an early-stage company and we only publish what's true today. SOC 2 Type II is on
            our roadmap, not yet certified — happy to walk through our current controls or complete
            your security questionnaire:{' '}
            <a href='mailto:sales@executespec.ai' className='signal-md-link'>
              sales@executespec.ai
            </a>
            .
          </div>
        </Card>

        <SectionHeader>Documents</SectionHeader>
        <div className='signal-row'>
          <div className='pill-dot mute' />
          <div>
            <div className='row-title'>Data Processing Addendum (DPA)</div>
            <div className='row-meta'>Standard MSAs</div>
          </div>
        </div>
        <div className='signal-row'>
          <div className='pill-dot mute' />
          <div>
            <div className='row-title'>Sub-processors list</div>
            <div className='row-meta'>GCP · Anthropic · OpenAI · Google AI</div>
          </div>
        </div>
        <div className='signal-row'>
          <div className='pill-dot mute' />
          <div>
            <div className='row-title'>Security questionnaire</div>
            <div className='row-meta'>On request</div>
          </div>
        </div>
        <div className='signal-row'>
          <div className='pill-dot mute' />
          <div>
            <div className='row-title'>Privacy policy</div>
            <div className='row-meta'>last updated 2026-05-01</div>
          </div>
        </div>
      </Feed>
    </>
  );
}

export function PublicPricingPage() {
  return (
    <>
      <TopBar title='Pricing' sub='Start free · upgrade when you need to' back backTo='/login' />
      <Feed>
        <Card style={{fontSize: 13, color: 'var(--signal-text-dim)', lineHeight: 1.6}}>
          Managed AI is metered in <b>AI credits</b> — $1.25 = 100 credits, charged from real model
          cost as your run works. Frontier models step down automatically near your monthly cap; use
          BYOK to remove it. Pro bills monthly; Team is monthly, per seat.
        </Card>

        <div className='signal-grid cols-2 cols-4-xl'>
          {[
            {
              tier: 'FREE',
              price: '$0',
              sub: 'No card required',
              features: [
                '200 AI credits / month',
                'Small specs only',
                'Managed-AI cap',
                '1 project · 1 repo',
                'Email support',
              ],
            },
            {
              tier: 'PRO',
              price: '$20',
              unit: '/mo',
              sub: 'For individuals · monthly',
              features: [
                '2,000 AI credits / month',
                'Managed keys — Anthropic · OpenAI · DeepSeek',
                'PR review + code review (4 scans/mo)',
                '2 projects · 2 repos each',
                'Managed-AI cap — Pro+ upsell when needed',
              ],
            },
            {
              tier: 'BYOK',
              price: '$50',
              unit: '/mo',
              sub: 'Solo org · bring your own API keys',
              features: [
                'No managed-AI cap',
                'Mandatory provider keys',
                'Model matrix available',
                'Single-person organization',
                'No provider markup',
                'Upgrade to Team for members',
              ],
              best: true,
            },
            {
              tier: 'TEAM',
              price: '$50',
              unit: '/seat/mo',
              sub: 'Minimum 2 seats · bring your own API keys',
              features: [
                'Unlimited on your own keys (BYOK)',
                'No managed-AI cap',
                'Org spend caps + approval thresholds',
                '10 projects · cross-repo runs',
                'Unlimited code reviews + sharing',
                'Autofix: PR review · tech debt · code scans',
              ],
            },
            {
              tier: 'ENTERPRISE',
              price: 'Custom',
              sub: 'Custom contract · DPA',
              features: [
                'Unlimited · no managed-AI cap',
                'Model matrix override + per-run model pins',
                'OpenRouter gateway models',
                'Standards · Contracts · ADRs',
                'Governance: audit trail · repo allowlists',
                'Dedicated support',
              ],
            },
          ].map((p) => (
            <Card
              key={p.tier}
              style={{position: 'relative', borderColor: p.best ? 'var(--signal-ink)' : undefined}}
            >
              {p.best && (
                <div
                  style={{
                    position: 'absolute',
                    top: -10,
                    right: 16,
                    background: 'var(--signal-y)',
                    color: 'var(--signal-y-text)',
                    fontSize: 12,
                    fontWeight: 700,
                    padding: '3px 9px',
                    borderRadius: 999,
                  }}
                >
                  RECOMMENDED
                </div>
              )}
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'baseline',
                  marginBottom: 6,
                }}
              >
                <div style={{fontSize: 16, fontWeight: 700}}>{p.tier}</div>
                <div>
                  <span style={{fontSize: 24, fontWeight: 700, letterSpacing: '-0.02em'}}>
                    {p.price}
                  </span>
                  {p.unit && (
                    <span style={{fontSize: 13, color: 'var(--signal-text-dim)', fontWeight: 400}}>
                      {p.unit}
                    </span>
                  )}
                </div>
              </div>
              <div style={{fontSize: 12, color: 'var(--signal-text-dim)', marginBottom: 14}}>
                {p.sub}
              </div>
              <ul
                style={{
                  listStyle: 'none',
                  padding: 0,
                  margin: 0,
                  display: 'grid',
                  gap: 8,
                  fontSize: 13,
                }}
              >
                {p.features.map((f) => (
                  <li key={f}>✓ {f}</li>
                ))}
              </ul>
              <Button
                kind={p.best ? 'primary' : 'secondary'}
                block
                style={{marginTop: 14}}
                to={p.tier === 'ENTERPRISE' ? undefined : '/register'}
                onClick={
                  p.tier === 'ENTERPRISE'
                    ? () => {
                        window.location.href =
                          'mailto:sales@executespec.ai?subject=Enterprise%20plan';
                      }
                    : undefined
                }
              >
                {p.tier === 'ENTERPRISE'
                  ? 'Talk to sales'
                  : p.tier === 'FREE'
                    ? 'Start free'
                    : 'Get started'}
              </Button>
            </Card>
          ))}
        </div>
      </Feed>
    </>
  );
}

// RR-1327 — AcceptInvitePage (and its FormError helper) were extracted into a
// dedicated file so ExtraPages stays within its size ceiling. Re-exported here
// for existing import sites.
export {AcceptInvitePage} from './AcceptInvitePage';
