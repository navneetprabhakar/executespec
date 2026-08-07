import {Check} from 'lucide-react';
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
              <div className='icon-bg'>
                <Check size={15} strokeWidth={2.25} aria-hidden='true' />
              </div>
              <h4>{b.label}</h4>
              <div className='count signal-trust-count'>{b.v}</div>
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
          <ul className='signal-trust-list'>
            <li>Train any model on your source code</li>
            <li>Retain your code outside the run workspace</li>
            <li>Share your data with third parties beyond the LLM provider you picked</li>
            <li>Read your code without the run-scoped JWT proving you authorized it</li>
          </ul>
        </Card>

        <SectionHeader>How execution is constrained</SectionHeader>
        <Card>
          <ul className='signal-trust-list'>
            <li>Every run gets its own workspace directory, retained for run evidence</li>
            <li>Shell commands and file access are restricted by an enforced policy</li>
            <li>Generated code is scanned for abuse patterns before execution</li>
            <li>Test execution runs in read-only, capability-dropped containers</li>
            <li>Tenant access guards are enforced in code and pinned by automated tests</li>
          </ul>
        </Card>

        <SectionHeader>Where we are on certifications</SectionHeader>
        <Card>
          <div className='signal-trust-note'>
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
        {/* Seven lines of mono at 375px before the first price. Same facts,
            fewer restated clauses — the per-tier cards carry the rest. */}
        <Card className='signal-price-note'>
          Managed AI is metered in <b>AI credits</b>: $1.25 = 100 credits, charged from real model
          cost as the run works. Near your monthly cap, frontier models step down automatically —
          BYOK removes the cap.
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
            // Recommended is a leading stripe (`.is-best`), not a swapped card
            // border — a repainted edge read as a selected/error state and gave
            // the row two competing edge treatments.
            <Card key={p.tier} className={`signal-price-card${p.best ? ' is-best' : ''}`}>
              {p.best && <div className='signal-price-flag'>RECOMMENDED</div>}
              <div className='signal-price-head'>
                <div className='signal-price-tier'>{p.tier}</div>
                <div className='signal-price-amount'>
                  {p.price}
                  {p.unit && <span className='signal-price-unit'>{p.unit}</span>}
                </div>
              </div>
              <div className='signal-price-sub'>{p.sub}</div>
              <ul className='signal-price-features'>
                {p.features.map((f) => (
                  <li key={f}>
                    <span>{f}</span>
                  </li>
                ))}
              </ul>
              {/* Pushed to the card's bottom edge so all five CTAs land on one
                  baseline regardless of how long that tier's feature list is. */}
              <div className='signal-price-cta'>
                <Button
                  kind={p.best ? 'primary' : 'secondary'}
                  block
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
              </div>
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
