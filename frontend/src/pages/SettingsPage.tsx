import { useEffect, useState } from 'react';
import {
  Eye, EyeOff, Github, CheckCircle, AlertCircle, Server, Database, Cpu, Clock,
  ChevronDown, ChevronRight, Key, Building2, Shield, Loader2,
} from 'lucide-react';
import GlassPanel from '@/components/shared/GlassPanel';
import { useAuthStore } from '@/stores/useAuthStore';
import { useTierPermissions } from '@/hooks/useTierPermissions';
import type { OrgDto } from '@/lib/api';
import * as api from '@/lib/api';

// ── Generic expand/collapse card ──────────────────────────────────────────────

interface IntegrationCardProps {
  icon: React.ReactNode;
  title: string;
  statusBadge: React.ReactNode;
  children: React.ReactNode;
  defaultOpen?: boolean;
}

function IntegrationCard({ icon, title, statusBadge, children, defaultOpen = false }: IntegrationCardProps) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <GlassPanel className="overflow-hidden">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="w-full flex items-center gap-2 px-5 py-4 text-left hover:bg-white/[0.02] transition-colors"
      >
        {icon}
        <span className="text-sm font-semibold text-zinc-200 flex-1">{title}</span>
        {statusBadge}
        {open
          ? <ChevronDown className="h-4 w-4 text-zinc-500 ml-1 flex-shrink-0" />
          : <ChevronRight className="h-4 w-4 text-zinc-500 ml-1 flex-shrink-0" />}
      </button>
      {open && (
        <div className="px-5 pb-5 pt-1 space-y-3 border-t border-white/[0.06]">
          {children}
        </div>
      )}
    </GlassPanel>
  );
}

// ── Status badge helpers ──────────────────────────────────────────────────────

function ConfiguredBadge({ label }: { label: string }) {
  return (
    <span className="flex items-center gap-1 text-xs text-accent-emerald">
      <CheckCircle className="h-3.5 w-3.5" /> {label}
    </span>
  );
}

function NotConfiguredBadge({ label }: { label: string }) {
  return (
    <span className="flex items-center gap-1 text-xs text-accent-amber">
      <AlertCircle className="h-3.5 w-3.5" /> {label}
    </span>
  );
}

// ── Secret input field ────────────────────────────────────────────────────────

function SecretField({
  label, subLabel, value, onChange, placeholder,
}: {
  label: string; subLabel?: string; value: string; onChange: (v: string) => void; placeholder: string;
}) {
  const [show, setShow] = useState(false);
  return (
    <div>
      <label className="text-xs text-zinc-400 mb-1 block">
        {label} {subLabel && <span className="text-zinc-600">{subLabel}</span>}
      </label>
      <div className="relative">
        <input
          type={show ? 'text' : 'password'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          className="w-full rounded-lg border border-white/[0.08] bg-zinc-900 px-3 py-2 text-sm text-white placeholder-zinc-600 focus:border-accent-violet focus:outline-none pr-10"
        />
        <button
          type="button"
          onClick={() => setShow((v) => !v)}
          className="absolute right-2.5 top-1/2 -translate-y-1/2 text-zinc-600 hover:text-zinc-300"
        >
          {show ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
        </button>
      </div>
    </div>
  );
}

// ── Text input field ──────────────────────────────────────────────────────────

function TextField({
  label, value, onChange, placeholder, hint,
}: {
  label: string; value: string; onChange: (v: string) => void; placeholder: string; hint?: string;
}) {
  return (
    <div>
      <label className="text-xs text-zinc-400 mb-1 block">{label}</label>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full rounded-lg border border-white/[0.08] bg-zinc-900 px-3 py-2 text-sm text-white placeholder-zinc-600 focus:border-accent-violet focus:outline-none"
      />
      {hint && <p className="text-xs text-zinc-600 mt-1">{hint}</p>}
    </div>
  );
}

// ── Save message ──────────────────────────────────────────────────────────────

function SaveMessage({ msg }: { msg: { ok: boolean; text: string } | null }) {
  if (!msg) return null;
  return <p className={`text-xs ${msg.ok ? 'text-accent-emerald' : 'text-red-400'}`}>{msg.text}</p>;
}

// ── Action buttons ────────────────────────────────────────────────────────────

function SaveBar({
  saving, disabled, onClear, clearLabel,
}: {
  saving: boolean; disabled?: boolean; onClear?: () => void; clearLabel?: string;
}) {
  return (
    <div className="flex gap-3 pt-1">
      <button
        type="submit"
        disabled={saving || disabled}
        className="px-4 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 disabled:opacity-50 transition-colors"
      >
        {saving ? 'Saving…' : 'Save'}
      </button>
      {onClear && (
        <button
          type="button"
          onClick={onClear}
          disabled={saving}
          className="px-4 py-2 rounded-lg text-zinc-400 hover:text-red-400 text-sm transition-colors disabled:opacity-50"
        >
          {clearLabel ?? 'Clear'}
        </button>
      )}
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function SettingsPage() {
  const { user, setUser } = useAuthStore();
  const permissions = useTierPermissions();
  const [health, setHealth] = useState<api.MetricsResponse | null>(null);
  const [org, setOrg] = useState<OrgDto | null>(null);

  useEffect(() => {
    if (permissions?.canViewMetrics) {
      api.getMetrics().then(setHealth).catch(() => {});
    }
  }, [permissions?.canViewMetrics]);

  // Load org data for ORG_ADMIN users so we can show org-level key status
  const isOrgAdmin = user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN';
  useEffect(() => {
    if (isOrgAdmin) {
      api.getMyOrg().then(setOrg).catch(() => {});
    }
  }, [isOrgAdmin]);

  // ── GitHub ───────────────────────────────────────────────────────────────

  const [githubPat,   setGithubPat]   = useState('');
  const [githubOwner, setGithubOwner] = useState(user?.githubOwner ?? '');
  const [githubSaving, setGithubSaving] = useState(false);
  const [githubMsg, setGithubMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [githubTesting, setGithubTesting] = useState(false);

  useEffect(() => { setGithubOwner(user?.githubOwner ?? ''); }, [user?.githubOwner]);

  const handleTestGitHub = async () => {
    setGithubTesting(true); setGithubMsg(null);
    try {
      const result = await api.validateGitHub(githubPat);
      setGithubMsg({ ok: result.valid, text: result.message });
    } catch (err: any) {
      setGithubMsg({ ok: false, text: err.message ?? 'Validation failed.' });
    } finally { setGithubTesting(false); }
  };

  const handleSaveGitHub = async (e: React.FormEvent) => {
    e.preventDefault();
    setGithubSaving(true); setGithubMsg(null);
    try {
      const updated = await api.updateGithubSettings(githubPat, githubOwner);
      setUser(updated); setGithubPat('');
      setGithubMsg({ ok: true, text: 'GitHub settings saved.' });
    } catch (err: any) {
      setGithubMsg({ ok: false, text: err.message ?? 'Failed to save.' });
    } finally { setGithubSaving(false); }
  };

  const handleClearGitHub = async () => {
    if (!confirm('Remove your GitHub PAT and owner?')) return;
    setGithubSaving(true); setGithubMsg(null);
    try {
      const updated = await api.updateGithubSettings('', '');
      setUser(updated); setGithubPat(''); setGithubOwner('');
      setGithubMsg({ ok: true, text: 'GitHub credentials cleared.' });
    } catch (err: any) {
      setGithubMsg({ ok: false, text: err.message ?? 'Failed to clear.' });
    } finally { setGithubSaving(false); }
  };

  // ── AI Keys ──────────────────────────────────────────────────────────────

  const [openAiKey,     setOpenAiKey]     = useState('');
  const [anthropicKey,  setAnthropicKey]  = useState('');
  const [geminiKey,     setGeminiKey]     = useState('');
  const [aiSaving, setAiSaving] = useState(false);
  const [aiMsg, setAiMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const handleSaveAiKeys = async (e: React.FormEvent) => {
    e.preventDefault();
    setAiSaving(true); setAiMsg(null);
    try {
      const updated = await api.updateAiKeySettings(openAiKey, anthropicKey, geminiKey);
      setUser(updated); setOpenAiKey(''); setAnthropicKey(''); setGeminiKey('');
      setAiMsg({ ok: true, text: 'AI provider keys saved.' });
    } catch (err: any) {
      setAiMsg({ ok: false, text: err.message ?? 'Failed to save.' });
    } finally { setAiSaving(false); }
  };

  const handleClearAiKeys = async () => {
    if (!confirm('Remove all AI provider keys?')) return;
    setAiSaving(true); setAiMsg(null);
    try {
      const updated = await api.updateAiKeySettings('', '', '');
      setUser(updated);
      setAiMsg({ ok: true, text: 'AI provider keys cleared.' });
    } catch (err: any) {
      setAiMsg({ ok: false, text: err.message ?? 'Failed to clear.' });
    } finally { setAiSaving(false); }
  };

  const hasAnyAiKey = user?.hasOpenAiKey || user?.hasAnthropicKey || user?.hasGeminiKey;

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className="h-full overflow-y-auto p-4 space-y-6">
      <div>
        <h1 className="text-lg font-semibold text-white">Settings</h1>
        <p className="text-xs text-zinc-500 mt-0.5">Account configuration and system health</p>
      </div>

      {/* ── GitHub ─────────────────────────────────────────────────────────── */}
      <IntegrationCard
        icon={<Github className="h-4 w-4 text-zinc-400" />}
        title="GitHub Integration"
        statusBadge={user?.hasGithubPat
          ? <ConfiguredBadge label="PAT configured" />
          : <NotConfiguredBadge label="No PAT — push disabled" />}
        defaultOpen={!user?.hasGithubPat}
      >
        <p className="text-xs text-zinc-500">
          Runs auto-push code to GitHub after merge. Provide a PAT with{' '}
          <code className="text-zinc-400">repo</code> scope and your username or org name.
        </p>
        <form onSubmit={handleSaveGitHub} className="space-y-3">
          <SecretField
            label="Personal Access Token"
            subLabel={user?.hasGithubPat ? '(leave blank to keep existing)' : undefined}
            value={githubPat}
            onChange={setGithubPat}
            placeholder={user?.hasGithubPat ? '••••••••••••••••••••• (saved)' : 'ghp_xxxxxxxxxxxxxxxxxxxx'}
          />
          <TextField
            label="GitHub Owner"
            value={githubOwner}
            onChange={setGithubOwner}
            placeholder="github_owner"
            hint="Your GitHub username or org name."
          />
          <SaveMessage msg={githubMsg} />
          <div className="flex gap-3 pt-1">
            <button
              type="submit"
              disabled={githubSaving || (!githubPat.trim() && !githubOwner.trim())}
              className="px-4 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 disabled:opacity-50 transition-colors"
            >
              {githubSaving ? 'Saving…' : 'Save'}
            </button>
            <button
              type="button"
              onClick={handleTestGitHub}
              disabled={githubTesting || !githubPat.trim()}
              className="flex items-center gap-1.5 px-4 py-2 rounded-lg border border-white/[0.08] text-zinc-300 text-sm hover:border-accent-violet/40 hover:text-white disabled:opacity-40 transition-colors"
            >
              {githubTesting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
              Test Connection
            </button>
            {user?.hasGithubPat && (
              <button
                type="button"
                onClick={handleClearGitHub}
                disabled={githubSaving}
                className="px-4 py-2 rounded-lg text-zinc-400 hover:text-red-400 text-sm transition-colors disabled:opacity-50"
              >
                Clear credentials
              </button>
            )}
          </div>
        </form>
      </IntegrationCard>

      {/* ── AI Provider Keys (requires canManageAiKeys) ─────────────────────── */}
      {permissions?.canManageAiKeys && (
        <IntegrationCard
          icon={<Key className="h-4 w-4 text-zinc-400" />}
          title="AI Provider Keys"
          statusBadge={hasAnyAiKey
            ? <ConfiguredBadge label={`${[user?.hasOpenAiKey && 'OpenAI', user?.hasAnthropicKey && 'Anthropic', user?.hasGeminiKey && 'Gemini'].filter(Boolean).join(', ')} configured`} />
            : <NotConfiguredBadge label="No keys saved" />}
          defaultOpen={!hasAnyAiKey}
        >
          <p className="text-xs text-zinc-500">
            Per-user API keys for AI providers. Leave a field blank to keep an existing key unchanged.
            Save with all fields empty to clear all keys.
          </p>
          <form onSubmit={handleSaveAiKeys} className="space-y-3">
            <SecretField
              label={`OpenAI API Key${user?.hasOpenAiKey ? ' ✓' : ''}`}
              subLabel={user?.hasOpenAiKey ? '(leave blank to keep existing)' : undefined}
              value={openAiKey}
              onChange={setOpenAiKey}
              placeholder={user?.hasOpenAiKey ? '••••••••••••••••••••• (saved)' : 'sk-…'}
            />
            <SecretField
              label={`Anthropic API Key${user?.hasAnthropicKey ? ' ✓' : ''}`}
              subLabel={user?.hasAnthropicKey ? '(leave blank to keep existing)' : undefined}
              value={anthropicKey}
              onChange={setAnthropicKey}
              placeholder={user?.hasAnthropicKey ? '••••••••••••••••••••• (saved)' : 'sk-ant-…'}
            />
            <SecretField
              label={`Gemini API Key${user?.hasGeminiKey ? ' ✓' : ''}`}
              subLabel={user?.hasGeminiKey ? '(leave blank to keep existing)' : undefined}
              value={geminiKey}
              onChange={setGeminiKey}
              placeholder={user?.hasGeminiKey ? '••••••••••••••••••••• (saved)' : 'AIza…'}
            />
            <SaveMessage msg={aiMsg} />
            <SaveBar
              saving={aiSaving}
              disabled={!openAiKey.trim() && !anthropicKey.trim() && !geminiKey.trim()}
              onClear={hasAnyAiKey ? handleClearAiKeys : undefined}
              clearLabel="Clear all keys"
            />
          </form>
        </IntegrationCard>
      )}

      {/* ── JIRA/Confluence note ──────────────────────────────────────────── */}
      <GlassPanel className="px-5 py-3">
        <p className="text-xs text-zinc-500">
          JIRA and Confluence integrations are now managed at the org level.
          Go to <a href="/integrations" className="text-accent-violet hover:text-accent-violet/80 underline">Integrations</a> to configure them.
        </p>
      </GlassPanel>

      {/* ── Organisation AI Keys (ORG_ADMIN only) ──────────────────────────── */}
      {isOrgAdmin && <OrgAiKeyCard org={org} onSaved={setOrg} />}

      {/* ── Key Source & Provider Policy (ORG_ADMIN only) ──────────────────── */}
      {isOrgAdmin && org && <OrgKeySourceCard org={org} onSaved={setOrg} />}

      {/* ── System Health (requires canViewMetrics) ────────────────────────── */}
      {permissions?.canViewMetrics && (
        <div>
          <h3 className="text-xs font-medium text-zinc-500 uppercase tracking-wider mb-3">System Health</h3>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            <HealthCard icon={<Server className="h-5 w-5 text-accent-violet" />} label="API" value="Healthy" color="text-accent-emerald" />
            <HealthCard icon={<Database className="h-5 w-5 text-accent-cyan" />} label="Database" value="Connected" color="text-accent-emerald" />
            <HealthCard icon={<Cpu className="h-5 w-5 text-accent-amber" />} label="Total Runs" value={String(health?.totalRuns ?? '—')} color="text-zinc-200" />
            <HealthCard icon={<Clock className="h-5 w-5 text-accent-emerald" />} label="Active Runs" value={String(health?.activeRuns ?? '—')} color="text-zinc-200" />
          </div>
        </div>
      )}

    </div>
  );
}

// ── Org-level AI key card (ORG_ADMIN only) ───────────────────────────────────

function OrgAiKeyCard({ org, onSaved }: { org: OrgDto | null; onSaved: (o: OrgDto) => void }) {
  const [orgAnthropicKey, setOrgAnthropicKey] = useState('');
  const [orgOpenAiKey,    setOrgOpenAiKey]    = useState('');
  const [orgGeminiKey,    setOrgGeminiKey]    = useState('');
  const [saving,  setSaving]  = useState(false);
  const [msg,     setMsg]     = useState<{ ok: boolean; text: string } | null>(null);

  const hasAnyOrgKey = org?.hasAnthropicKey || org?.hasOpenAiKey || org?.hasGeminiKey;

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true); setMsg(null);
    try {
      const updated = await api.updateOrgAiKeySettings(orgAnthropicKey, orgOpenAiKey, orgGeminiKey);
      onSaved(updated);
      setOrgAnthropicKey(''); setOrgOpenAiKey(''); setOrgGeminiKey('');
      setMsg({ ok: true, text: 'Organisation AI keys saved.' });
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Failed to save.' });
    } finally { setSaving(false); }
  };

  const handleClear = async () => {
    if (!confirm('Remove all organisation AI keys? Members without personal keys will fall back to system keys.')) return;
    setSaving(true); setMsg(null);
    try {
      const updated = await api.updateOrgAiKeySettings('', '', '');
      onSaved(updated);
      setMsg({ ok: true, text: 'Organisation AI keys cleared.' });
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Failed to clear.' });
    } finally { setSaving(false); }
  };

  return (
    <IntegrationCard
      icon={<Building2 className="h-4 w-4 text-zinc-400" />}
      title="Organisation AI Keys"
      statusBadge={hasAnyOrgKey
        ? <ConfiguredBadge label={`Org keys: ${[org?.hasAnthropicKey && 'Anthropic', org?.hasOpenAiKey && 'OpenAI', org?.hasGeminiKey && 'Gemini'].filter(Boolean).join(', ')}`} />
        : <NotConfiguredBadge label="No org keys" />}
    >
      <p className="text-xs text-zinc-500">
        Org-wide API keys are used by all team members who haven't set personal keys.
        <br />
        Resolution order: <span className="text-zinc-400">Personal key → Org key → System key</span>
      </p>
      <form onSubmit={handleSave} className="space-y-3">
        <SecretField
          label={`Anthropic API Key${org?.hasAnthropicKey ? ' ✓' : ''}`}
          subLabel={org?.hasAnthropicKey ? '(leave blank to keep existing)' : undefined}
          value={orgAnthropicKey}
          onChange={setOrgAnthropicKey}
          placeholder={org?.hasAnthropicKey ? '••••••••••••••••••••• (saved)' : 'sk-ant-…'}
        />
        <SecretField
          label={`OpenAI API Key${org?.hasOpenAiKey ? ' ✓' : ''}`}
          subLabel={org?.hasOpenAiKey ? '(leave blank to keep existing)' : undefined}
          value={orgOpenAiKey}
          onChange={setOrgOpenAiKey}
          placeholder={org?.hasOpenAiKey ? '••••••••••••••••••••• (saved)' : 'sk-…'}
        />
        <SecretField
          label={`Gemini API Key${org?.hasGeminiKey ? ' ✓' : ''}`}
          subLabel={org?.hasGeminiKey ? '(leave blank to keep existing)' : undefined}
          value={orgGeminiKey}
          onChange={setOrgGeminiKey}
          placeholder={org?.hasGeminiKey ? '••••••••••••••••••••• (saved)' : 'AIza…'}
        />
        <SaveMessage msg={msg} />
        <SaveBar
          saving={saving}
          disabled={!orgAnthropicKey.trim() && !orgOpenAiKey.trim() && !orgGeminiKey.trim()}
          onClear={hasAnyOrgKey ? handleClear : undefined}
          clearLabel="Clear org keys"
        />
      </form>
    </IntegrationCard>
  );
}

// ── Key source & provider policy card (ORG_ADMIN only) ──────────────────────

const KEY_SOURCE_MODES = [
  { value: 'SYSTEM',   label: 'Platform Keys',   desc: 'Use executespec platform keys for all runs.' },
  { value: 'OWN_KEYS', label: 'Own Keys Only',    desc: 'Use only org/personal keys. Runs fail if no key is set.' },
  { value: 'HYBRID',   label: 'Hybrid (Default)', desc: 'Use own keys when available, fall back to platform.' },
] as const;

const PROVIDERS = [
  { id: 'anthropic', label: 'Anthropic' },
  { id: 'openai',    label: 'OpenAI' },
  { id: 'google',    label: 'Gemini' },
] as const;

function OrgKeySourceCard({ org, onSaved }: { org: OrgDto; onSaved: (o: OrgDto) => void }) {
  const [mode, setMode] = useState(org.keySourceMode ?? 'HYBRID');
  const [providers, setProviders] = useState<Set<string>>(() => {
    if (!org.enabledProviders) return new Set(PROVIDERS.map((p) => p.id));
    return new Set(org.enabledProviders);
  });
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  // Sync when org prop changes
  useEffect(() => {
    setMode(org.keySourceMode ?? 'HYBRID');
    if (!org.enabledProviders) {
      setProviders(new Set(PROVIDERS.map((p) => p.id)));
    } else {
      setProviders(new Set(org.enabledProviders));
    }
  }, [org.keySourceMode, org.enabledProviders]);

  const toggleProvider = (id: string) => {
    setProviders((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        if (next.size <= 1) return prev; // at least one must remain
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const handleSave = async () => {
    setSaving(true); setMsg(null);
    try {
      const enabledList = providers.size === PROVIDERS.length ? null : Array.from(providers);
      const updated = await api.updateOrgKeySourceSettings(mode, enabledList);
      onSaved(updated);
      setMsg({ ok: true, text: 'Key source settings saved.' });
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Failed to save.' });
    } finally { setSaving(false); }
  };

  const missingKeys = mode === 'OWN_KEYS' && !(
    (providers.has('anthropic') && org.hasAnthropicKey) ||
    (providers.has('openai') && org.hasOpenAiKey) ||
    (providers.has('google') && org.hasGeminiKey)
  );

  return (
    <IntegrationCard
      icon={<Shield className="h-4 w-4 text-zinc-400" />}
      title="Key Source & Provider Policy"
      statusBadge={
        <span className="text-xs text-zinc-500">
          {mode === 'SYSTEM' ? 'Platform' : mode === 'OWN_KEYS' ? 'Own Keys' : 'Hybrid'}
        </span>
      }
    >
      {/* Key Source Mode */}
      <div className="space-y-2">
        <label className="text-xs text-zinc-400 block">Key Source Mode</label>
        {KEY_SOURCE_MODES.map((m) => (
          <label
            key={m.value}
            className="flex items-start gap-2.5 cursor-pointer group"
          >
            <input
              type="radio"
              name="keySourceMode"
              value={m.value}
              checked={mode === m.value}
              onChange={() => setMode(m.value)}
              className="mt-0.5 accent-accent-violet"
            />
            <div>
              <span className="text-sm text-zinc-200 group-hover:text-white">{m.label}</span>
              <p className="text-xs text-zinc-500">{m.desc}</p>
            </div>
          </label>
        ))}
      </div>

      {/* Provider Restrictions */}
      <div className="space-y-2 pt-2 border-t border-white/[0.06]">
        <label className="text-xs text-zinc-400 block">Enabled Providers</label>
        <div className="flex gap-3">
          {PROVIDERS.map((p) => (
            <label key={p.id} className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={providers.has(p.id)}
                onChange={() => toggleProvider(p.id)}
                className="accent-accent-violet"
              />
              <span className="text-sm text-zinc-300">{p.label}</span>
            </label>
          ))}
        </div>
        <p className="text-xs text-zinc-600">
          Disabled providers are excluded from fallback chains for all org runs.
        </p>
      </div>

      {/* Warning */}
      {missingKeys && (
        <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 px-3 py-2">
          <p className="text-xs text-amber-400">
            <AlertCircle className="h-3.5 w-3.5 inline mr-1 -mt-0.5" />
            OWN_KEYS mode is selected but no org/user keys are configured for the enabled providers.
            Runs will fail at submission.
          </p>
        </div>
      )}

      <SaveMessage msg={msg} />
      <div className="flex gap-3 pt-1">
        <button
          onClick={handleSave}
          disabled={saving}
          className="px-4 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 disabled:opacity-50 transition-colors"
        >
          {saving ? 'Saving...' : 'Save'}
        </button>
      </div>
    </IntegrationCard>
  );
}

function HealthCard({ icon, label, value, color }: { icon: React.ReactNode; label: string; value: string; color: string }) {
  return (
    <GlassPanel className="p-4 flex items-center gap-3">
      {icon}
      <div>
        <p className="text-xs text-zinc-500">{label}</p>
        <p className={`text-sm font-semibold ${color}`}>{value}</p>
      </div>
    </GlassPanel>
  );
}
