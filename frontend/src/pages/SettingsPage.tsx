import { useEffect, useState } from 'react';
import {
  Eye, EyeOff, Github, CheckCircle, AlertCircle, Server, Database, Cpu, Clock,
  ChevronDown, ChevronRight, Key, Layers,
} from 'lucide-react';
import GlassPanel from '@/components/shared/GlassPanel';
import { useAuthStore } from '@/stores/useAuthStore';
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
  const [health, setHealth] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    fetch('/api/metrics').then((r) => r.json()).then(setHealth).catch(() => {});
  }, []);

  // ── GitHub ───────────────────────────────────────────────────────────────

  const [githubPat,   setGithubPat]   = useState('');
  const [githubOwner, setGithubOwner] = useState(user?.githubOwner ?? '');
  const [githubSaving, setGithubSaving] = useState(false);
  const [githubMsg, setGithubMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => { setGithubOwner(user?.githubOwner ?? ''); }, [user?.githubOwner]);

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

  // ── JIRA ─────────────────────────────────────────────────────────────────

  const [jiraUrl,   setJiraUrl]   = useState(user?.jiraBaseUrl ?? '');
  const [jiraEmail, setJiraEmail] = useState(user?.jiraEmail ?? '');
  const [jiraToken, setJiraToken] = useState('');
  const [jiraSaving, setJiraSaving] = useState(false);
  const [jiraMsg, setJiraMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    setJiraUrl(user?.jiraBaseUrl ?? '');
    setJiraEmail(user?.jiraEmail ?? '');
  }, [user?.jiraBaseUrl, user?.jiraEmail]);

  const handleSaveJira = async (e: React.FormEvent) => {
    e.preventDefault();
    setJiraSaving(true); setJiraMsg(null);
    try {
      const updated = await api.updateJiraSettings(jiraUrl, jiraEmail, jiraToken);
      setUser(updated); setJiraToken('');
      setJiraMsg({ ok: true, text: 'JIRA settings saved.' });
    } catch (err: any) {
      setJiraMsg({ ok: false, text: err.message ?? 'Failed to save.' });
    } finally { setJiraSaving(false); }
  };

  const handleClearJira = async () => {
    if (!confirm('Remove JIRA credentials?')) return;
    setJiraSaving(true); setJiraMsg(null);
    try {
      const updated = await api.updateJiraSettings('', '', '');
      setUser(updated); setJiraUrl(''); setJiraEmail(''); setJiraToken('');
      setJiraMsg({ ok: true, text: 'JIRA credentials cleared.' });
    } catch (err: any) {
      setJiraMsg({ ok: false, text: err.message ?? 'Failed to clear.' });
    } finally { setJiraSaving(false); }
  };

  // ── Confluence ───────────────────────────────────────────────────────────

  const [confUrl,   setConfUrl]   = useState(user?.confluenceBaseUrl ?? '');
  const [confEmail, setConfEmail] = useState(user?.confluenceEmail ?? '');
  const [confToken, setConfToken] = useState('');
  const [confSaving, setConfSaving] = useState(false);
  const [confMsg, setConfMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    setConfUrl(user?.confluenceBaseUrl ?? '');
    setConfEmail(user?.confluenceEmail ?? '');
  }, [user?.confluenceBaseUrl, user?.confluenceEmail]);

  const handleSaveConf = async (e: React.FormEvent) => {
    e.preventDefault();
    setConfSaving(true); setConfMsg(null);
    try {
      const updated = await api.updateConfluenceSettings(confUrl, confEmail, confToken);
      setUser(updated); setConfToken('');
      setConfMsg({ ok: true, text: 'Confluence settings saved.' });
    } catch (err: any) {
      setConfMsg({ ok: false, text: err.message ?? 'Failed to save.' });
    } finally { setConfSaving(false); }
  };

  const handleClearConf = async () => {
    if (!confirm('Remove Confluence credentials?')) return;
    setConfSaving(true); setConfMsg(null);
    try {
      const updated = await api.updateConfluenceSettings('', '', '');
      setUser(updated); setConfUrl(''); setConfEmail(''); setConfToken('');
      setConfMsg({ ok: true, text: 'Confluence credentials cleared.' });
    } catch (err: any) {
      setConfMsg({ ok: false, text: err.message ?? 'Failed to clear.' });
    } finally { setConfSaving(false); }
  };

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
            placeholder="navneetprabhakar"
            hint="Your GitHub username or org name."
          />
          <SaveMessage msg={githubMsg} />
          <SaveBar
            saving={githubSaving}
            disabled={!githubPat.trim() && !githubOwner.trim()}
            onClear={user?.hasGithubPat ? handleClearGitHub : undefined}
            clearLabel="Clear credentials"
          />
        </form>
      </IntegrationCard>

      {/* ── AI Provider Keys ────────────────────────────────────────────────── */}
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

      {/* ── JIRA ───────────────────────────────────────────────────────────── */}
      <IntegrationCard
        icon={<Layers className="h-4 w-4 text-zinc-400" />}
        title="JIRA Integration"
        statusBadge={user?.hasJiraToken
          ? <ConfiguredBadge label="Connected" />
          : <NotConfiguredBadge label="Not configured" />}
        defaultOpen={!user?.hasJiraToken}
      >
        <p className="text-xs text-zinc-500">
          Connect your Atlassian JIRA account to allow agents to read and update issues.
          Use an Atlassian API token (not your password).
        </p>
        <form onSubmit={handleSaveJira} className="space-y-3">
          <TextField
            label="JIRA Base URL"
            value={jiraUrl}
            onChange={setJiraUrl}
            placeholder="https://yourorg.atlassian.net"
          />
          <TextField
            label="JIRA Email"
            value={jiraEmail}
            onChange={setJiraEmail}
            placeholder="you@yourorg.com"
          />
          <SecretField
            label={`API Token${user?.hasJiraToken ? ' ✓' : ''}`}
            subLabel={user?.hasJiraToken ? '(leave blank to keep existing)' : undefined}
            value={jiraToken}
            onChange={setJiraToken}
            placeholder={user?.hasJiraToken ? '••••••••••••••••••••• (saved)' : 'Atlassian API token'}
          />
          <SaveMessage msg={jiraMsg} />
          <SaveBar
            saving={jiraSaving}
            disabled={!jiraUrl.trim() && !jiraEmail.trim() && !jiraToken.trim()}
            onClear={user?.hasJiraToken ? handleClearJira : undefined}
            clearLabel="Clear credentials"
          />
        </form>
      </IntegrationCard>

      {/* ── Confluence ─────────────────────────────────────────────────────── */}
      <IntegrationCard
        icon={<Layers className="h-4 w-4 text-zinc-400" />}
        title="Confluence Integration"
        statusBadge={user?.hasConfluenceToken
          ? <ConfiguredBadge label="Connected" />
          : <NotConfiguredBadge label="Not configured" />}
        defaultOpen={!user?.hasConfluenceToken}
      >
        <p className="text-xs text-zinc-500">
          Connect your Atlassian Confluence account to allow agents to read and create pages.
          Use an Atlassian API token (not your password).
        </p>
        <form onSubmit={handleSaveConf} className="space-y-3">
          <TextField
            label="Confluence Base URL"
            value={confUrl}
            onChange={setConfUrl}
            placeholder="https://yourorg.atlassian.net/wiki"
          />
          <TextField
            label="Confluence Email"
            value={confEmail}
            onChange={setConfEmail}
            placeholder="you@yourorg.com"
          />
          <SecretField
            label={`API Token${user?.hasConfluenceToken ? ' ✓' : ''}`}
            subLabel={user?.hasConfluenceToken ? '(leave blank to keep existing)' : undefined}
            value={confToken}
            onChange={setConfToken}
            placeholder={user?.hasConfluenceToken ? '••••••••••••••••••••• (saved)' : 'Atlassian API token'}
          />
          <SaveMessage msg={confMsg} />
          <SaveBar
            saving={confSaving}
            disabled={!confUrl.trim() && !confEmail.trim() && !confToken.trim()}
            onClear={user?.hasConfluenceToken ? handleClearConf : undefined}
            clearLabel="Clear credentials"
          />
        </form>
      </IntegrationCard>

      {/* ── System Health ───────────────────────────────────────────────────── */}
      <div>
        <h3 className="text-xs font-medium text-zinc-500 uppercase tracking-wider mb-3">System Health</h3>
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
          <HealthCard icon={<Server className="h-5 w-5 text-accent-violet" />} label="API" value="Healthy" color="text-accent-emerald" />
          <HealthCard icon={<Database className="h-5 w-5 text-accent-cyan" />} label="Database" value="Connected" color="text-accent-emerald" />
          <HealthCard icon={<Cpu className="h-5 w-5 text-accent-amber" />} label="Total Runs" value={String(health?.totalRuns ?? '—')} color="text-zinc-200" />
          <HealthCard icon={<Clock className="h-5 w-5 text-accent-emerald" />} label="Active Runs" value={String(health?.activeRuns ?? '—')} color="text-zinc-200" />
        </div>
      </div>

      {/* ── About ──────────────────────────────────────────────────────────── */}
      <GlassPanel className="p-4 space-y-3">
        <h3 className="text-sm font-semibold text-zinc-200">About ExecuteSpec</h3>
        <div className="space-y-2 text-sm text-zinc-400">
          <p>A multi-agent AI coding system — React + Spring Boot 3 + Spring AI 1.0</p>
          <p>Planner → Approval → Workers → Reviewer → Merge → GitHub Push → Deploy</p>
          <div className="pt-2 border-t border-white/[0.06] font-mono text-xs text-zinc-600 space-y-1">
            <p>Stack: Java 21 · Spring Boot 3.3 · Spring AI 1.0 · PostgreSQL · React 18 · Vite</p>
            <p>Agents: Planner (Opus) · Workers (Sonnet) · Reviewer (Haiku)</p>
          </div>
        </div>
      </GlassPanel>
    </div>
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
