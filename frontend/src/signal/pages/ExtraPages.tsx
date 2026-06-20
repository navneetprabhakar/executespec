import {useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Card, Button, KvList} from '@/signal/components/Primitives';
import AuthLayout from '@/signal/components/AuthLayout';
import * as api from '@/lib/api';

const MERGE_RAIL_MARK = '/assets/brand/merge-rail/executespec-merge-rail-mark-light.svg';

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
                style={{color: 'var(--signal-ink)', fontSize: 15, fontWeight: 600}}
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
              {k: 'API keys (BYOK)', v: 'Encrypted at rest · never logged'},
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

        <SectionHeader>How execution is isolated</SectionHeader>
        <Card>
          <ul style={{padding: '0 0 0 18px', margin: 0, fontSize: 14, lineHeight: 1.7}}>
            <li>Every run gets its own workspace, wiped at run end</li>
            <li>Shell + file access run inside a sandbox policy, not on a shared host account</li>
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
          Runs are metered in credits — <b>1 credit ≈ $5 of model spend</b>. Credits are charged
          only when you approve a plan, and refunded if the run fails. Pro bills monthly or yearly;
          Team is monthly, per seat.
        </Card>

        <div className='signal-grid cols-2 cols-4-xl'>
          {[
            {
              tier: 'FREE',
              price: '$0',
              sub: 'No card required',
              features: [
                '15 runs / month',
                'Small specs (1 credit)',
                '$3 per-run cost cap',
                '1 project · 1 repo',
                'Email support',
              ],
            },
            {
              tier: 'PRO',
              price: '$29',
              unit: '/mo',
              sub: 'For individuals · $289/yr (save 17%)',
              features: [
                '30 runs / month',
                'Managed keys — Anthropic · OpenAI · Gemini',
                'PR review + code review (4 scans/mo)',
                '2 projects · 2 repos each',
                '$5 per-run cap — add your own key to lift it',
              ],
            },
            {
              tier: 'TEAM',
              price: '$39',
              unit: '/seat/mo',
              sub: 'From 1 seat · bring your own API keys',
              features: [
                'Unlimited runs on your keys',
                'No platform cost cap',
                'Org spend caps + approval thresholds',
                '5 projects · cross-repo runs',
                'Unlimited code reviews + sharing',
                'Autofix: PR review · tech debt · code scans',
              ],
              best: true,
            },
            {
              tier: 'ENTERPRISE',
              price: 'Custom',
              sub: 'Custom contract · DPA',
              features: [
                'Unlimited runs · no cost cap',
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
                    fontSize: 10,
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
                  <span style={{fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em'}}>
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
                  fontSize: 13.5,
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

function FormError({msg}: {msg: string}) {
  if (!msg) return null;
  return (
    <div
      style={{
        background: 'var(--signal-coral-soft)',
        color: 'var(--signal-coral-text)',
        border: '1px solid var(--signal-coral)',
        borderRadius: 10,
        padding: '10px 14px',
        marginBottom: 12,
        fontSize: 13,
      }}
    >
      {msg}
    </div>
  );
}

export function AcceptInvitePage({kind = 'project'}: {kind?: 'org' | 'project'}) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token') || '';
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const noun = kind === 'org' ? 'organization' : 'project';

  async function accept() {
    if (!token) {
      setErr('No invite token in the URL. Use the link from your invite email.');
      return;
    }
    setErr('');
    setBusy(true);
    try {
      if (kind === 'org') {
        await api.acceptInvite(token);
        navigate('/setup', {replace: true});
      } else {
        const r = await api.acceptProjectInvite(token);
        navigate(r?.projectId ? `/projects/${r.projectId}` : '/setup', {replace: true});
      }
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Could not accept this invite.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthLayout>
      <div className='signal-splash'>
        <div className='brand'>
          <img className='logo' src={MERGE_RAIL_MARK} alt='' />
          <div style={{fontSize: 16, fontWeight: 600}}>ExecuteSpec</div>
        </div>
        <h1>Accept invite.</h1>
        <p className='lede'>
          Open this from your email invite link. We will add you to the {noun} and take you there.
        </p>
        <FormError msg={err} />
        <Card style={{marginBottom: 18}}>
          <KvList
            pairs={[
              {
                k: 'Invite token',
                v: token ? (
                  token.slice(0, 12) + '…'
                ) : (
                  <span style={{color: 'var(--signal-coral-text)'}}>(missing)</span>
                ),
              },
              {
                k: 'Status',
                v: token ? (
                  'Ready to accept'
                ) : (
                  <span style={{color: 'var(--signal-coral-text)'}}>
                    Open the link in your email
                  </span>
                ),
              },
            ]}
          />
        </Card>
        <Button kind='mint' block onClick={accept} disabled={busy || !token}>
          {busy ? 'Accepting…' : kind === 'org' ? 'Accept & continue' : 'Accept & open project'}
        </Button>
        <Button kind='ghost' block to='/compose' style={{color: 'var(--signal-coral-text)'}}>
          Decline invite
        </Button>
      </div>
    </AuthLayout>
  );
}
