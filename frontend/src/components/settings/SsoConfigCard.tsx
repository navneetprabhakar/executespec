import { useEffect, useState } from 'react';
import {
  getSsoConfig,
  updateSsoConfig,
  clearSsoConfig,
  type SsoConfig,
  type SsoProvider,
} from '@/lib/api';

const PROVIDERS: { value: SsoProvider; label: string }[] = [
  { value: 'GOOGLE_OIDC', label: 'Google Workspace (OIDC)' },
  { value: 'OKTA_SAML',    label: 'Okta (SAML)' },
  { value: 'AZURE_AD_SAML', label: 'Azure AD / Entra (SAML)' },
];

/**
 * M-004 (Wave 2) — SSO config UI for ORG_ADMIN. Backend
 * (SsoConfigController, V127) handles persistence; this card surfaces
 * the config form.
 *
 * Skeleton scope: stores intent (provider + metadata URL + domain +
 * enforced flag). The actual SAML/OIDC handshake ships in Wave 3
 * (M-016) — see parked decision #6 for library choice.
 */
export default function SsoConfigCard() {
  const [config, setConfig] = useState<SsoConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Form state
  const [provider, setProvider] = useState<SsoProvider | ''>('');
  const [metadataUrl, setMetadataUrl] = useState('');
  const [domain, setDomain] = useState('');
  const [enforced, setEnforced] = useState(false);
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const load = () => {
    setLoading(true);
    getSsoConfig()
      .then((c) => {
        setConfig(c);
        setProvider(c.provider ?? '');
        setMetadataUrl(c.metadataUrl ?? '');
        setDomain(c.domain ?? '');
        setEnforced(c.enforced);
        setError(null);
      })
      .catch((e) => setError(e?.message ?? 'Failed to load SSO config'))
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setMsg(null);
    try {
      const next = await updateSsoConfig({
        provider: provider === '' ? null : provider,
        metadataUrl: metadataUrl.trim() === '' ? null : metadataUrl.trim(),
        domain: domain.trim() === '' ? null : domain.trim(),
        enforced,
      });
      setConfig(next);
      setMsg({ ok: true, text: 'SSO configuration saved.' });
    } catch (err: any) {
      setMsg({ ok: false, text: err?.message ?? 'Save failed' });
    } finally {
      setSaving(false);
    }
  };

  const handleClear = async () => {
    if (!confirm('Clear SSO configuration? Members will fall back to password / OAuth login.')) return;
    setSaving(true);
    setMsg(null);
    try {
      await clearSsoConfig();
      load();
      setMsg({ ok: true, text: 'SSO cleared.' });
    } catch (err: any) {
      setMsg({ ok: false, text: err?.message ?? 'Clear failed' });
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <div className="text-xs text-muted">Loading SSO config…</div>;
  if (error) return <div className="text-xs text-status-error">{error}</div>;

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] p-4 space-y-4">
      <div className="flex items-baseline justify-between gap-3 flex-wrap">
        <div>
          <h3 className="text-sm font-medium text-primary">Single Sign-On (SSO)</h3>
          <p className="text-xs text-muted mt-0.5">
            Configure your IdP for centralised auth. Saving stores the intent —
            the SAML/OIDC handshake ships in a follow-up.
          </p>
        </div>
        {config?.configured && (
          <span className="text-[11px] uppercase tracking-wider text-status-success font-semibold">
            Configured ✓
          </span>
        )}
      </div>

      {msg && (
        <div className={`p-2 text-xs rounded-sm border ${
          msg.ok ? 'bg-status-success/10 border-status-success/30 text-status-success'
                 : 'bg-status-error/10 border-status-error/30 text-status-error'
        }`}>
          {msg.text}
        </div>
      )}

      <form onSubmit={handleSave} className="space-y-3">
        <div>
          <label className="block text-xs text-muted mb-1">Identity provider</label>
          <select
            value={provider}
            onChange={(e) => setProvider(e.target.value as SsoProvider | '')}
            className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-sm text-primary focus:border-accent-blue focus:outline-none"
          >
            <option value="">— No SSO —</option>
            {PROVIDERS.map((p) => (
              <option key={p.value} value={p.value}>{p.label}</option>
            ))}
          </select>
        </div>

        <div>
          <label className="block text-xs text-muted mb-1">SAML/OIDC metadata URL</label>
          <input
            type="url"
            value={metadataUrl}
            onChange={(e) => setMetadataUrl(e.target.value)}
            placeholder="https://idp.example.com/metadata"
            className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-sm text-primary focus:border-accent-blue focus:outline-none"
          />
        </div>

        <div>
          <label className="block text-xs text-muted mb-1">Email domain (members logging in from this domain are routed to SSO)</label>
          <input
            type="text"
            value={domain}
            onChange={(e) => setDomain(e.target.value)}
            placeholder="company.com"
            className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-sm text-primary focus:border-accent-blue focus:outline-none"
          />
        </div>

        <label className="flex items-start gap-2 text-sm text-secondary cursor-pointer">
          <input
            type="checkbox"
            checked={enforced}
            onChange={(e) => setEnforced(e.target.checked)}
            className="mt-0.5 accent-accent-blue"
          />
          <span>
            <span className="text-primary">Enforce SSO</span> — block password / OAuth login
            for members in the configured domain.
            <span className="block text-xs text-muted mt-0.5">
              Requires a configured provider. Leave off until you've tested the flow end-to-end.
            </span>
          </span>
        </label>

        <div className="flex items-center gap-2 pt-2">
          <button
            type="submit"
            disabled={saving}
            className="rounded-sm bg-accent-blue px-4 py-2 text-sm font-medium text-primary hover:bg-accent-blue/80 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {saving ? 'Saving…' : 'Save SSO config'}
          </button>
          {config?.configured && (
            <button
              type="button"
              onClick={handleClear}
              disabled={saving}
              className="rounded-sm border border-[var(--border-default)] px-4 py-2 text-sm text-muted hover:text-status-error hover:border-status-error/40"
            >
              Clear
            </button>
          )}
        </div>
      </form>
    </div>
  );
}
