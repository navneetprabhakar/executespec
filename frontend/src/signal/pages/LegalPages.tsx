/* Public legal + compliance pages — Terms, Privacy, DPA — and the org-level
 * settings page (SSO + standards). All deliberately lightweight: copy-only,
 * no JS-heavy charts, no auth required for the legal trio. The Signup CTA
 * links here so anonymous visitors can read these before agreeing. */

import {Link} from 'react-router-dom';
import {useEffect, useState} from 'react';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Card, KvList, Button, StatusPill} from '@/signal/components/Primitives';
import {LoadingState} from '@/signal/components/Loaders';
import {ConfirmModal} from '@/signal/components/Modal';
import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

// ──────────────────── Terms of Service ────────────────────
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
        Paid plans are billed monthly or annually as selected. Credits never expire; subscription
        cancellation stops future renewals but does not refund the current period.
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

// ──────────────────── Privacy Policy ────────────────────
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

// ──────────────────── Data Processing Addendum ────────────────────
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
        SOC 2 Type II controls (audit underway). ISO 27001 alignment. TLS 1.3 + AES-256, role-based
        access, audit-log retention 30 days, mandatory MFA for employees.
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
        ENTERPRISE customers may request a SOC 2 report annually under NDA. Upon termination,
        customer data is purged within 14 days; backups roll off within 35 days.
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

function LegalShell({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle: string;
  children: React.ReactNode;
}) {
  return (
    <div style={{minHeight: '100dvh', background: 'var(--signal-bg)', color: 'var(--signal-ink)'}}>
      <div style={{maxWidth: 720, margin: '0 auto', padding: '40px 24px 80px'}}>
        <Link
          to='/login'
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            fontSize: 13,
            color: 'var(--signal-text-dim)',
            textDecoration: 'none',
            marginBottom: 32,
          }}
        >
          ← Back to ExecuteSpec
        </Link>
        <h1
          style={{
            fontSize: 28,
            fontWeight: 700,
            letterSpacing: '-0.02em',
            margin: 0,
            marginBottom: 6,
          }}
        >
          {title}
        </h1>
        <p style={{fontSize: 13, color: 'var(--signal-text-dim)', marginTop: 0, marginBottom: 28}}>
          {subtitle}
        </p>
        <article style={{fontSize: 14, lineHeight: 1.7, color: 'var(--signal-ink)'}}>
          <style>{`
            article h3 { font-size: 16px; font-weight: 700; letter-spacing: -0.01em; margin: 24px 0 6px; }
            article p { margin: 0 0 14px; }
            article a { color: var(--signal-coral); text-decoration: underline; }
          `}</style>
          {children}
        </article>
      </div>
    </div>
  );
}

// ──────────────────── Org Settings (SSO, standards, profile) ────────────────────
export function OrgSettingsPage() {
  const user = useAuthStore((s) => s.user);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const canEdit = isSuperAdmin || user?.orgRole === 'ORG_ADMIN';
  const [org, setOrg] = useState<api.OrgDto | null>(null);
  const [sso, setSso] = useState<api.SsoConfig | null>(null);
  const [name, setName] = useState('');
  const [domains, setDomains] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  // SSO form state.
  const [ssoProvider, setSsoProvider] = useState<api.SsoProvider>('GOOGLE_OIDC');
  const [ssoMetadataUrl, setSsoMetadataUrl] = useState('');
  const [ssoDomain, setSsoDomain] = useState('');
  const [ssoEnforced, setSsoEnforced] = useState(false);
  const [confirmClearSso, setConfirmClearSso] = useState(false);
  const [clearSsoBusy, setClearSsoBusy] = useState(false);
  // V198 — busy flag for the external-sharing toggle so consecutive clicks
  // don't race the PUT.
  const [extBusy, setExtBusy] = useState(false);
  // Org-wide BYOK provider keys. Inputs are write-only — raw keys are never
  // returned (only has*Key flags) — and a blank field leaves the stored key
  // unchanged, so each provider saves independently without wiping the others.
  const [keyInputs, setKeyInputs] = useState({
    anthropic: '',
    openai: '',
    gemini: '',
    openrouter: '',
  });
  const [keyBusy, setKeyBusy] = useState<string | null>(null);

  useEffect(() => {
    Promise.allSettled([api.getMyOrg(), api.getSsoConfig()]).then(([o, s]) => {
      if (o.status === 'fulfilled') {
        setOrg(o.value);
        setName(o.value.name || '');
        setDomains((o.value as unknown as {domains?: string[]}).domains?.join(', ') || '');
      }
      if (s.status === 'fulfilled') {
        setSso(s.value);
        if (s.value.provider) setSsoProvider(s.value.provider);
        if (s.value.metadataUrl) setSsoMetadataUrl(s.value.metadataUrl);
        if (s.value.domain) setSsoDomain(s.value.domain);
        setSsoEnforced(!!s.value.enforced);
      }
    });
  }, []);

  async function saveOrg() {
    setBusy(true);
    setErr('');
    setMsg('');
    try {
      await api.updateOrg({
        name: name.trim() || undefined,
        domains: domains
          ? domains
              .split(',')
              .map((d) => d.trim())
              .filter(Boolean)
          : undefined,
      });
      setMsg('Org saved.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setBusy(false);
    }
  }

  async function saveSso() {
    setBusy(true);
    setErr('');
    setMsg('');
    try {
      await api.updateSsoConfig({
        provider: ssoProvider,
        enforced: ssoEnforced,
        metadataUrl: ssoMetadataUrl.trim() || null,
        domain: ssoDomain.trim() || null,
      });
      setMsg('SSO config saved.');
      setSso(await api.getSsoConfig());
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'SSO save failed.');
    } finally {
      setBusy(false);
    }
  }

  async function performClearSso() {
    setClearSsoBusy(true);
    try {
      await api.clearSsoConfig();
      setSso(null);
      setMsg('SSO cleared.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Clear SSO failed.');
    } finally {
      setClearSsoBusy(false);
      setConfirmClearSso(false);
    }
  }

  async function saveOrgKey(provider: 'anthropic' | 'openai' | 'gemini' | 'openrouter') {
    const value = keyInputs[provider].trim();
    if (!value) {
      setErr('Enter a key first.');
      return;
    }
    setKeyBusy(provider);
    setErr('');
    setMsg('');
    try {
      const next = await api.updateOrgAiKeySettings(
        provider === 'anthropic' ? value : '',
        provider === 'openai' ? value : '',
        provider === 'gemini' ? value : '',
        provider === 'openrouter' ? value : '',
      );
      setOrg(next);
      setKeyInputs((k) => ({...k, [provider]: ''}));
      setMsg('Provider key saved.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setKeyBusy(null);
    }
  }

  if (!org) return <LoadingState label='Loading org…' />;

  // OpenRouter is an Enterprise-only gateway — only offer it when the org qualifies
  // (the backend rejects the key with 403 otherwise).
  const isEnterprise = (org.tier || '').toUpperCase() === 'ENTERPRISE';
  const orgKeyRows: {
    provider: 'anthropic' | 'openai' | 'gemini' | 'openrouter';
    label: string;
    connected: boolean;
  }[] = [
    {provider: 'anthropic', label: 'Anthropic', connected: !!org.hasAnthropicKey},
    {provider: 'openai', label: 'OpenAI', connected: !!org.hasOpenAiKey},
    {provider: 'gemini', label: 'Gemini', connected: !!org.hasGeminiKey},
    ...(isEnterprise
      ? [{provider: 'openrouter' as const, label: 'OpenRouter', connected: !!org.hasOpenRouterKey}]
      : []),
  ];

  return (
    <>
      <TopBar
        title='Org settings'
        sub={`${org.name || 'Personal org'} · ${(org as unknown as {seats?: number}).seats ?? 1} seats`}
        back
        backTo='/account'
      />
      <Feed>
        {err && (
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
            {err}
          </div>
        )}
        {msg && (
          <div
            style={{
              background: 'var(--signal-mint-soft)',
              color: 'var(--signal-mint-text-on-light)',
              border: '1px solid var(--signal-mint)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {msg}
          </div>
        )}

        <SectionHeader>Profile</SectionHeader>
        <Card>
          <Field label='Org name'>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={!canEdit}
              style={inputStyle}
            />
          </Field>
          <Field label='Authorised email domains (comma-separated)'>
            <input
              value={domains}
              onChange={(e) => setDomains(e.target.value)}
              disabled={!canEdit}
              placeholder='acme.co, acme.com'
              style={inputStyle}
            />
          </Field>
          {canEdit && (
            <Button kind='primary' block disabled={busy} onClick={saveOrg}>
              {busy ? 'Saving…' : 'Save org profile'}
            </Button>
          )}
        </Card>

        <SectionHeader>Single sign-on (SSO)</SectionHeader>
        <Card
          style={{
            background: 'var(--signal-lavender-soft)',
            borderColor: 'transparent',
            marginBottom: 12,
          }}
        >
          <div style={{fontSize: 13, color: '#5443a8', lineHeight: 1.55}}>
            Pin login to your IdP — Google OIDC, Okta SAML, or Azure AD SAML. Members signing in
            with another method are blocked once SSO is enforced.
          </div>
        </Card>

        <Card>
          <Field label='Provider'>
            <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
              {(['GOOGLE_OIDC', 'OKTA_SAML', 'AZURE_AD_SAML'] as api.SsoProvider[]).map((p) => (
                <button
                  key={p}
                  type='button'
                  onClick={canEdit ? () => setSsoProvider(p) : undefined}
                  disabled={!canEdit}
                  className={`signal-chip ${ssoProvider === p ? 'active' : ''}`}
                  style={{padding: '6px 12px'}}
                >
                  {p.replace(/_/g, ' ')}
                </button>
              ))}
            </div>
          </Field>
          <Field label='Metadata URL'>
            <input
              value={ssoMetadataUrl}
              onChange={(e) => setSsoMetadataUrl(e.target.value)}
              disabled={!canEdit}
              placeholder='https://accounts.google.com/.well-known/openid-configuration'
              style={inputStyle}
            />
          </Field>
          <Field label='Login domain'>
            <input
              value={ssoDomain}
              onChange={(e) => setSsoDomain(e.target.value)}
              disabled={!canEdit}
              placeholder='acme.com'
              style={inputStyle}
            />
          </Field>
          <Field label='Enforce SSO'>
            <label
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                fontSize: 13,
                color: 'var(--signal-text-dim)',
              }}
            >
              <input
                type='checkbox'
                checked={ssoEnforced}
                disabled={!canEdit}
                onChange={(e) => setSsoEnforced(e.target.checked)}
              />
              Require SSO for all members on the login domain.
            </label>
          </Field>
          {canEdit && (
            <div style={{display: 'flex', gap: 8}}>
              <Button kind='primary' block disabled={busy} onClick={saveSso}>
                {busy ? 'Saving…' : 'Save SSO'}
              </Button>
              {sso && (
                <Button
                  kind='ghost'
                  sm
                  onClick={() => setConfirmClearSso(true)}
                  style={{color: 'var(--signal-coral-text)'}}
                >
                  Clear
                </Button>
              )}
            </div>
          )}
        </Card>

        <SectionHeader>AI provider keys</SectionHeader>
        <Card
          style={{
            background: 'var(--signal-lavender-soft)',
            borderColor: 'transparent',
            marginBottom: 12,
          }}
        >
          <div style={{fontSize: 13, color: '#5443a8', lineHeight: 1.55}}>
            Org-wide BYOK keys shared by every member. Runs against a provider you supply a key for
            bypass the platform cost cap. Keys are encrypted at rest and never shown again — paste a
            new value to replace one, or leave a field blank to keep the current key.
          </div>
        </Card>
        <Card style={{padding: 0}}>
          {orgKeyRows.map((row, i) => (
            <div
              key={row.provider}
              style={{
                padding: '14px 16px',
                borderBottom:
                  i < orgKeyRows.length - 1 ? '1px solid var(--signal-hairline)' : 'none',
              }}
            >
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  marginBottom: 8,
                }}
              >
                <div style={{fontSize: 14.5, fontWeight: 600}}>{row.label}</div>
                {row.connected ? (
                  <StatusPill kind='shipped'>Connected</StatusPill>
                ) : (
                  <StatusPill>Not set</StatusPill>
                )}
              </div>
              <div style={{display: 'flex', gap: 8}}>
                <input
                  type='password'
                  autoComplete='off'
                  placeholder={
                    row.connected ? 'Paste a new key to replace' : `Paste your ${row.label} key`
                  }
                  value={keyInputs[row.provider]}
                  onChange={(e) => setKeyInputs((k) => ({...k, [row.provider]: e.target.value}))}
                  disabled={!canEdit}
                  style={{
                    ...inputStyle,
                    flex: 1,
                    fontFamily: "'JetBrains Mono', monospace",
                    marginBottom: 0,
                  }}
                />
                <Button
                  kind='primary'
                  sm
                  disabled={!canEdit || keyBusy === row.provider}
                  onClick={() => saveOrgKey(row.provider)}
                >
                  {keyBusy === row.provider ? 'Saving…' : 'Save'}
                </Button>
              </div>
            </div>
          ))}
        </Card>

        <SectionHeader>External sharing</SectionHeader>
        <Card>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'flex-start',
              gap: 12,
            }}
          >
            <div style={{minWidth: 0, flex: 1}}>
              <div style={{fontSize: 14.5, fontWeight: 600, marginBottom: 4}}>
                Allow inviting people outside your org
              </div>
              <div style={{fontSize: 13, color: 'var(--signal-text-dim)', lineHeight: 1.5}}>
                When on, you (org admin) or a super admin can invite any email — including gmail /
                outlook — as a read-only or developer collaborator on a single project. When off,
                project invites are restricted to existing org members.
              </div>
            </div>
            {/* Reuse the same toggle pattern as the notification preference rows on
                /account so the affordance is recognisable. */}
            <button
              type='button'
              role='switch'
              aria-checked={!!org.externalSharingEnabled}
              disabled={!canEdit || extBusy}
              onClick={async () => {
                setExtBusy(true);
                setErr('');
                setMsg('');
                try {
                  const next = await api.updateExternalSharing(!org.externalSharingEnabled);
                  setOrg(next);
                  setMsg(
                    `External sharing ${next.externalSharingEnabled ? 'enabled' : 'disabled'}.`,
                  );
                } catch (e: unknown) {
                  setErr(e instanceof Error ? e.message : 'Toggle failed.');
                } finally {
                  setExtBusy(false);
                }
              }}
              style={{
                position: 'relative',
                width: 44,
                height: 24,
                background: org.externalSharingEnabled
                  ? 'var(--signal-mint)'
                  : 'var(--signal-card-elev)',
                border: '1px solid var(--signal-hairline)',
                borderRadius: 999,
                cursor: canEdit ? 'pointer' : 'not-allowed',
                opacity: canEdit ? 1 : 0.5,
                transition: 'background 0.2s',
                flexShrink: 0,
              }}
            >
              <span
                style={{
                  position: 'absolute',
                  top: 1,
                  left: org.externalSharingEnabled ? 21 : 1,
                  width: 20,
                  height: 20,
                  background: 'white',
                  borderRadius: '50%',
                  transition: 'left 0.2s',
                }}
              />
            </button>
          </div>
        </Card>

        <SectionHeader>Compliance</SectionHeader>
        <Card>
          <KvList
            pairs={[
              {k: 'SOC 2', v: <StatusPill kind='shipped'>Type II ready</StatusPill>},
              {k: 'GDPR', v: <StatusPill kind='shipped'>Compliant</StatusPill>},
              {
                k: 'DPA',
                v: (
                  <a href='/dpa' style={{color: 'var(--signal-coral)'}}>
                    View DPA
                  </a>
                ),
              },
              {
                k: 'Audit log',
                v: (
                  <a href='/audit' style={{color: 'var(--signal-coral)'}}>
                    Open audit log
                  </a>
                ),
              },
            ]}
          />
        </Card>
      </Feed>

      <ConfirmModal
        open={confirmClearSso}
        title='Clear SSO config?'
        body='All members will fall back to password / OAuth on next login. Active sessions are preserved until they expire.'
        confirmLabel='Clear SSO'
        danger
        busy={clearSsoBusy}
        onClose={() => setConfirmClearSso(false)}
        onConfirm={performClearSso}
      />
    </>
  );
}

function Field({label, children}: {label: string; children: React.ReactNode}) {
  return (
    <div style={{marginBottom: 12}}>
      <div
        style={{
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: '0.14em',
          textTransform: 'uppercase',
          color: 'var(--signal-text-mute)',
          marginBottom: 6,
        }}
      >
        {label}
      </div>
      {children}
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  width: '100%',
  background: 'var(--signal-card-elev)',
  border: '1px solid var(--signal-hairline)',
  borderRadius: 8,
  padding: '10px 12px',
  fontSize: 13,
  color: 'var(--signal-ink)',
  outline: 'none',
  fontFamily: 'inherit',
  boxSizing: 'border-box',
};
