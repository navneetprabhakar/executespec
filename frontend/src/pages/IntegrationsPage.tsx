import { useEffect, useState, useCallback } from 'react';
import {
  ChevronDown, ChevronRight, HelpCircle, ExternalLink,
  Eye, EyeOff, CheckCircle, AlertCircle, Loader2, Settings2, Unplug,
  Send, Hash, RefreshCw,
} from 'lucide-react';
import IntegrationLogo from '@/components/integrations/IntegrationLogo';
import GlassPanel from '@/components/shared/GlassPanel';
import { useAuthStore } from '@/stores/useAuthStore';
import * as api from '@/lib/api';
import { relativeTime } from '@/lib/utils';
import type { RunSummary, OrgDto, SlackStatus, SlackChannel, ProjectSlackChannelDto } from '@/lib/api';

// ── Integration metadata ──────────────────────────────────────────────────

const INTEGRATIONS = [
  { key: 'SLACK',      name: 'Slack',      description: 'Pipeline notifications',  configurable: true  },
  { key: 'JIRA',       name: 'Jira',       description: 'Issue-triggered runs',    configurable: true  },
  { key: 'CONFLUENCE', name: 'Confluence',  description: 'Page-triggered runs',     configurable: true  },
  { key: 'SERVICENOW', name: 'ServiceNow',  description: 'Incident response runs',  configurable: false },
  { key: 'PAGERDUTY',  name: 'PagerDuty',   description: 'Alert-triggered runs',    configurable: false },
];

interface SetupGuide { title: string; url: string; steps: string[]; }

const SETUP_GUIDES: Record<string, SetupGuide> = {
  SLACK: {
    title: 'Slack Bot Installation',
    url: 'https://api.slack.com/apps',
    steps: [
      'Click "Configure" on the Slack card and then "Connect to Slack"',
      'You\'ll be redirected to Slack to authorize the ExecuteSpec bot',
      'Select the workspace you want to install the bot in and click "Allow"',
      'Once connected, add channels to your projects to receive notifications',
      'Choose a verbosity level: Minimal (start/complete/fail), Standard (+ approvals, reviews), or Verbose (all events)',
    ],
  },
  JIRA: {
    title: 'Jira API Token',
    url: 'https://id.atlassian.com/manage-profile/security/api-tokens',
    steps: [
      'Go to Atlassian Account > Security > API tokens',
      'Click "Create API token" and give it a label (e.g. "ExecuteSpec")',
      'Copy the generated token immediately (it won\'t be shown again)',
      'Click Configure on the JIRA card below and enter your credentials',
      'Set your Jira base URL (e.g. https://yoursite.atlassian.net)',
    ],
  },
  CONFLUENCE: {
    title: 'Confluence API Token',
    url: 'https://id.atlassian.com/manage-profile/security/api-tokens',
    steps: [
      'Uses the same Atlassian API token as Jira (same account)',
      'Go to Atlassian Account > Security > API tokens',
      'Create or reuse your existing API token',
      'Click Configure on the Confluence card below and enter your credentials',
      'Set your Confluence base URL (same as Jira, e.g. https://yoursite.atlassian.net)',
    ],
  },
  GITHUB: {
    title: 'GitHub Personal Access Token',
    url: 'https://github.com/settings/tokens',
    steps: [
      'Go to GitHub > Settings > Developer settings > Personal access tokens',
      'Click "Generate new token (classic)" or "Fine-grained token"',
      'Required scopes: repo (full control of private repositories)',
      'Set an expiration (90 days recommended) and click "Generate token"',
      'Copy the token and paste it into Settings > GitHub > Personal Access Token',
    ],
  },
};

// ── Secret input ──────────────────────────────────────────────────────────

function SecretField({
  label, value, onChange, placeholder, subLabel,
}: {
  label: string; value: string; onChange: (v: string) => void; placeholder: string; subLabel?: string;
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

// ── JIRA Credential Form ──────────────────────────────────────────────────

function JiraCredentialForm({
  org, onSaved,
}: {
  org: OrgDto; onSaved: (o: OrgDto) => void;
}) {
  const [baseUrl, setBaseUrl] = useState(org.jiraBaseUrl ?? '');
  const [email, setEmail] = useState('');
  const [token, setToken] = useState('');
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => { setBaseUrl(org.jiraBaseUrl ?? ''); }, [org.jiraBaseUrl]);

  const handleTest = async () => {
    setTesting(true); setMsg(null);
    try {
      const result = await api.validateOrgJira(baseUrl, email, token);
      setMsg({ ok: result.valid, text: result.message });
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Validation failed.' });
    } finally { setTesting(false); }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true); setMsg(null);
    try {
      const updated = await api.updateOrgJiraSettings(baseUrl, email, token);
      onSaved(updated);
      setToken('');
      setMsg({ ok: true, text: 'JIRA credentials saved.' });
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Failed to save.' });
    } finally { setSaving(false); }
  };

  const handleDisconnect = async () => {
    if (!confirm('Remove JIRA credentials? This will disable JIRA integration for your organization.')) return;
    setSaving(true); setMsg(null);
    try {
      const updated = await api.updateOrgJiraSettings('', '', '');
      onSaved(updated);
      setBaseUrl(''); setEmail(''); setToken('');
      setMsg({ ok: true, text: 'JIRA credentials removed.' });
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Failed to clear.' });
    } finally { setSaving(false); }
  };

  return (
    <form onSubmit={handleSave} className="space-y-3">
      <TextField label="JIRA Base URL" value={baseUrl} onChange={setBaseUrl} placeholder="https://yourorg.atlassian.net" />
      <TextField label="Email" value={email} onChange={setEmail} placeholder="you@yourorg.com" hint={org.hasJiraCredentials ? 'Leave blank to keep existing' : undefined} />
      <SecretField
        label="API Token"
        subLabel={org.hasJiraCredentials ? '(leave blank to keep existing)' : undefined}
        value={token}
        onChange={setToken}
        placeholder={org.hasJiraCredentials ? '(saved)' : 'Atlassian API token'}
      />
      {msg && <p className={`text-xs ${msg.ok ? 'text-accent-emerald' : 'text-red-400'}`}>{msg.text}</p>}
      <div className="flex gap-3 pt-1">
        <button
          type="submit"
          disabled={saving || (!baseUrl.trim() && !email.trim() && !token.trim())}
          className="px-4 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 disabled:opacity-50 transition-colors"
        >
          {saving ? 'Saving...' : 'Save'}
        </button>
        <button
          type="button"
          onClick={handleTest}
          disabled={testing || !baseUrl.trim() || !email.trim() || !token.trim()}
          className="flex items-center gap-1.5 px-4 py-2 rounded-lg border border-white/[0.08] text-zinc-300 text-sm hover:border-accent-violet/40 hover:text-white disabled:opacity-40 transition-colors"
        >
          {testing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
          Test Connection
        </button>
        {org.hasJiraCredentials && (
          <button
            type="button"
            onClick={handleDisconnect}
            disabled={saving}
            className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-zinc-400 hover:text-red-400 text-sm transition-colors disabled:opacity-50"
          >
            <Unplug className="h-3.5 w-3.5" />
            Disconnect
          </button>
        )}
      </div>
    </form>
  );
}

// ── Confluence Credential Form ────────────────────────────────────────────

function ConfluenceCredentialForm({
  org, onSaved,
}: {
  org: OrgDto; onSaved: (o: OrgDto) => void;
}) {
  const [baseUrl, setBaseUrl] = useState(org.confluenceBaseUrl ?? '');
  const [email, setEmail] = useState('');
  const [token, setToken] = useState('');
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => { setBaseUrl(org.confluenceBaseUrl ?? ''); }, [org.confluenceBaseUrl]);

  const handleTest = async () => {
    setTesting(true); setMsg(null);
    try {
      const result = await api.validateOrgConfluence(baseUrl, email, token);
      setMsg({ ok: result.valid, text: result.message });
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Validation failed.' });
    } finally { setTesting(false); }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true); setMsg(null);
    try {
      const updated = await api.updateOrgConfluenceSettings(baseUrl, email, token);
      onSaved(updated);
      setToken('');
      setMsg({ ok: true, text: 'Confluence credentials saved.' });
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Failed to save.' });
    } finally { setSaving(false); }
  };

  const handleDisconnect = async () => {
    if (!confirm('Remove Confluence credentials? This will disable Confluence integration for your organization.')) return;
    setSaving(true); setMsg(null);
    try {
      const updated = await api.updateOrgConfluenceSettings('', '', '');
      onSaved(updated);
      setBaseUrl(''); setEmail(''); setToken('');
      setMsg({ ok: true, text: 'Confluence credentials removed.' });
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Failed to clear.' });
    } finally { setSaving(false); }
  };

  return (
    <form onSubmit={handleSave} className="space-y-3">
      <TextField label="Confluence Base URL" value={baseUrl} onChange={setBaseUrl} placeholder="https://yourorg.atlassian.net" />
      <TextField label="Email" value={email} onChange={setEmail} placeholder="you@yourorg.com" hint={org.hasConfluenceCredentials ? 'Leave blank to keep existing' : undefined} />
      <SecretField
        label="API Token"
        subLabel={org.hasConfluenceCredentials ? '(leave blank to keep existing)' : undefined}
        value={token}
        onChange={setToken}
        placeholder={org.hasConfluenceCredentials ? '(saved)' : 'Atlassian API token'}
      />
      {msg && <p className={`text-xs ${msg.ok ? 'text-accent-emerald' : 'text-red-400'}`}>{msg.text}</p>}
      <div className="flex gap-3 pt-1">
        <button
          type="submit"
          disabled={saving || (!baseUrl.trim() && !email.trim() && !token.trim())}
          className="px-4 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 disabled:opacity-50 transition-colors"
        >
          {saving ? 'Saving...' : 'Save'}
        </button>
        <button
          type="button"
          onClick={handleTest}
          disabled={testing || !baseUrl.trim() || !email.trim() || !token.trim()}
          className="flex items-center gap-1.5 px-4 py-2 rounded-lg border border-white/[0.08] text-zinc-300 text-sm hover:border-accent-violet/40 hover:text-white disabled:opacity-40 transition-colors"
        >
          {testing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
          Test Connection
        </button>
        {org.hasConfluenceCredentials && (
          <button
            type="button"
            onClick={handleDisconnect}
            disabled={saving}
            className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-zinc-400 hover:text-red-400 text-sm transition-colors disabled:opacity-50"
          >
            <Unplug className="h-3.5 w-3.5" />
            Disconnect
          </button>
        )}
      </div>
    </form>
  );
}

// ── Slack OAuth Config Form ──────────────────────────────────────────────

function SlackConfigForm({
  slackStatus, onRefresh,
}: {
  slackStatus: SlackStatus | null; onRefresh: () => void;
}) {
  const [connecting, setConnecting] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const [testingChannel, setTestingChannel] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const handleConnect = async () => {
    setConnecting(true); setMsg(null);
    try {
      const { installUrl } = await api.getSlackInstallUrl();
      window.location.href = installUrl;
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Failed to start OAuth flow.' });
      setConnecting(false);
    }
  };

  const handleDisconnect = async () => {
    if (!confirm('Disconnect Slack? This will remove all channel mappings and revoke the bot token.')) return;
    setDisconnecting(true); setMsg(null);
    try {
      await api.disconnectSlack();
      setMsg({ ok: true, text: 'Slack disconnected.' });
      onRefresh();
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Failed to disconnect.' });
    } finally { setDisconnecting(false); }
  };

  const handleTest = async (channelId: string) => {
    setTestingChannel(channelId); setMsg(null);
    try {
      const result = await api.sendSlackTestMessage(channelId);
      setMsg({ ok: result.ok, text: result.ok ? 'Test message sent!' : (result.message || 'Test failed.') });
    } catch (err: any) {
      setMsg({ ok: false, text: err.message ?? 'Failed to send test.' });
    } finally { setTestingChannel(null); }
  };

  if (!slackStatus?.connected) {
    return (
      <div className="space-y-3">
        <p className="text-xs text-zinc-400">
          Connect your Slack workspace to receive pipeline notifications in your channels.
        </p>
        {msg && <p className={`text-xs ${msg.ok ? 'text-accent-emerald' : 'text-red-400'}`}>{msg.text}</p>}
        <button
          onClick={handleConnect}
          disabled={connecting}
          className="flex items-center gap-2 px-4 py-2 rounded-lg bg-[#4A154B] text-white text-sm font-medium hover:bg-[#4A154B]/80 disabled:opacity-50 transition-colors"
        >
          {connecting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : (
            <svg width="16" height="16" viewBox="0 0 32 32" fill="none"><path d="M8.8 19.2a2.4 2.4 0 01-2.4 2.4A2.4 2.4 0 014 19.2a2.4 2.4 0 012.4-2.4h2.4v2.4z" fill="#fff"/><path d="M10 19.2a2.4 2.4 0 012.4-2.4 2.4 2.4 0 012.4 2.4v6a2.4 2.4 0 01-2.4 2.4 2.4 2.4 0 01-2.4-2.4v-6z" fill="#fff"/><path d="M12.4 8.8a2.4 2.4 0 01-2.4-2.4A2.4 2.4 0 0112.4 4a2.4 2.4 0 012.4 2.4v2.4h-2.4z" fill="#fff"/><path d="M12.4 10a2.4 2.4 0 012.4 2.4 2.4 2.4 0 01-2.4 2.4h-6A2.4 2.4 0 014 12.4 2.4 2.4 0 016.4 10h6z" fill="#fff"/></svg>
          )}
          Connect to Slack
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {/* Connection status */}
      <div className="flex items-center gap-2 text-xs">
        <CheckCircle className="h-3.5 w-3.5 text-accent-emerald" />
        <span className="text-zinc-300">Connected to <span className="font-medium text-white">{slackStatus.teamName}</span></span>
        {slackStatus.channelCount != null && (
          <span className="text-zinc-600">({slackStatus.channelCount} channel{slackStatus.channelCount !== 1 ? 's' : ''} mapped)</span>
        )}
      </div>

      {slackStatus.installedBy && (
        <p className="text-xs text-zinc-600">
          Installed by {slackStatus.installedBy}
          {slackStatus.installedAt && ` \u00B7 ${relativeTime(slackStatus.installedAt)}`}
        </p>
      )}

      <p className="text-xs text-zinc-500">
        Configure project-level channel mappings from each project&apos;s settings.
        Use the test button below to verify the bot can post to a channel.
      </p>

      {msg && <p className={`text-xs ${msg.ok ? 'text-accent-emerald' : 'text-red-400'}`}>{msg.text}</p>}

      <SlackTestSection onTest={handleTest} testingChannel={testingChannel} />

      <div className="flex gap-3 pt-1">
        <button
          onClick={onRefresh}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-white/[0.08] text-zinc-400 hover:text-white text-xs transition-colors"
        >
          <RefreshCw className="h-3 w-3" /> Refresh Status
        </button>
        <button
          onClick={handleDisconnect}
          disabled={disconnecting}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-zinc-400 hover:text-red-400 text-xs transition-colors disabled:opacity-50"
        >
          <Unplug className="h-3 w-3" />
          {disconnecting ? 'Disconnecting...' : 'Disconnect'}
        </button>
      </div>
    </div>
  );
}

function SlackTestSection({
  onTest, testingChannel,
}: {
  onTest: (channelId: string) => void; testingChannel: string | null;
}) {
  const [channels, setChannels] = useState<SlackChannel[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedChannel, setSelectedChannel] = useState('');

  const loadChannels = async () => {
    setLoading(true);
    try {
      const list = await api.listSlackChannels();
      setChannels(list);
      if (list.length > 0 && !selectedChannel) setSelectedChannel(list[0].channelId);
    } catch { /* ignore */ }
    finally { setLoading(false); }
  };

  return (
    <div className="flex items-end gap-2">
      <div className="flex-1">
        <label className="text-xs text-zinc-400 mb-1 block">Send test message</label>
        <div className="flex gap-2">
          {channels.length === 0 ? (
            <button
              onClick={loadChannels}
              disabled={loading}
              className="flex items-center gap-1.5 px-3 py-2 rounded-lg border border-white/[0.08] text-zinc-300 text-xs hover:border-accent-violet/40 hover:text-white disabled:opacity-40 transition-colors"
            >
              {loading ? <Loader2 className="h-3 w-3 animate-spin" /> : <Hash className="h-3 w-3" />}
              Load Channels
            </button>
          ) : (
            <>
              <select
                value={selectedChannel}
                onChange={(e) => setSelectedChannel(e.target.value)}
                className="flex-1 rounded-lg border border-white/[0.08] bg-zinc-900 px-3 py-2 text-xs text-white focus:border-accent-violet focus:outline-none"
              >
                {channels.map((ch) => (
                  <option key={ch.channelId} value={ch.channelId}>#{ch.channelName}</option>
                ))}
              </select>
              <button
                onClick={() => selectedChannel && onTest(selectedChannel)}
                disabled={!selectedChannel || testingChannel != null}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-accent-violet text-white text-xs font-medium hover:bg-accent-violet/80 disabled:opacity-50 transition-colors"
              >
                {testingChannel ? <Loader2 className="h-3 w-3 animate-spin" /> : <Send className="h-3 w-3" />}
                Test
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ── Main IntegrationsPage ──────────────────────────────────────────────────

export default function IntegrationsPage() {
  const { user } = useAuthStore();
  const [enabledSources, setEnabledSources] = useState<string[]>([]);
  const [org, setOrg] = useState<OrgDto | null>(null);
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [showGuide, setShowGuide] = useState(false);
  const [openGuides, setOpenGuides] = useState<Record<string, boolean>>({});
  const [configuring, setConfiguring] = useState<string | null>(null);
  const [slackStatus, setSlackStatus] = useState<SlackStatus | null>(null);

  const isAdmin = user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN';

  const fetchSlackStatus = useCallback(() => {
    if (!user?.orgId) return;
    api.getSlackStatus().then(setSlackStatus).catch(() => setSlackStatus(null));
  }, [user?.orgId]);

  useEffect(() => {
    api.getIntegrationStatus().then((r) => setEnabledSources(r.enabledSources)).catch(() => {});
    api.getIntegrationRuns().then(setRuns).catch(() => {});
    if (user?.orgId) {
      api.getMyOrg().then(setOrg).catch(() => {});
      fetchSlackStatus();
    }
  }, [user?.orgId, fetchSlackStatus]);

  // Handle OAuth redirect — detect ?slack=success or ?slack=error in URL
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const slackResult = params.get('slack');
    if (slackResult === 'success') {
      fetchSlackStatus();
      setConfiguring('SLACK');
      // Clean URL
      window.history.replaceState({}, '', window.location.pathname);
    } else if (slackResult === 'error') {
      setConfiguring('SLACK');
      window.history.replaceState({}, '', window.location.pathname);
    }
  }, [fetchSlackStatus]);

  const handleOrgUpdated = (updatedOrg: OrgDto) => {
    setOrg(updatedOrg);
    // Re-derive enabled sources from org credentials
    const sources: string[] = [];
    if (updatedOrg.hasJiraCredentials) sources.push('JIRA');
    if (updatedOrg.hasConfluenceCredentials) sources.push('CONFLUENCE');
    setEnabledSources(sources);
  };

  const toggleGuide = (key: string) => {
    setOpenGuides((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  const isConfigured = (key: string): boolean => {
    if (key === 'JIRA') return !!org?.hasJiraCredentials;
    if (key === 'CONFLUENCE') return !!org?.hasConfluenceCredentials;
    if (key === 'SLACK') return !!slackStatus?.connected;
    return enabledSources.includes(key);
  };

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-semibold text-white">Integrations</h1>
          <p className="text-xs text-zinc-500 mt-0.5">
            External tools that can trigger agent runs.
            {isAdmin && ' Click Configure to enable an integration for your organization.'}
          </p>
        </div>
        <button
          onClick={() => setShowGuide(!showGuide)}
          className="flex items-center gap-1.5 text-xs text-zinc-400 hover:text-accent-violet transition-colors px-3 py-1.5 rounded-lg border border-white/[0.08] hover:border-accent-violet/30"
        >
          <HelpCircle className="h-3.5 w-3.5" />
          Setup Guide
        </button>
      </div>

      {/* No org warning */}
      {!user?.orgId && (
        <GlassPanel className="px-4 py-3 border-amber-500/20">
          <p className="text-xs text-amber-400">
            <AlertCircle className="h-3.5 w-3.5 inline mr-1 -mt-0.5" />
            You must be part of an organization to manage integrations.
            Create one from Org Settings.
          </p>
        </GlassPanel>
      )}

      {/* Integration grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {INTEGRATIONS.map((int) => {
          const configured = isConfigured(int.key);
          const isExpanded = configuring === int.key;

          return (
            <div
              key={int.key}
              className={`rounded-xl border transition-all ${
                configured
                  ? 'border-accent-violet/30 bg-accent-violet/[0.04]'
                  : 'border-white/[0.06] bg-white/[0.02]'
              } ${!configured && !int.configurable ? 'opacity-60 grayscale' : ''}`}
            >
              {/* Card header */}
              <div className="p-4">
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-2.5">
                    <IntegrationLogo integration={int.key} size={28} />
                    <div>
                      <p className="text-sm font-medium text-zinc-200">{int.name}</p>
                      <p className="text-xs text-zinc-500">{int.description}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                      configured ? 'text-accent-emerald bg-accent-emerald/10' : 'text-zinc-600 bg-zinc-600/10'
                    }`}>
                      {configured ? 'Enabled' : 'Disabled'}
                    </span>
                    {isAdmin && int.configurable && user?.orgId && (
                      <button
                        onClick={() => setConfiguring(isExpanded ? null : int.key)}
                        className="flex items-center gap-1 text-xs text-zinc-400 hover:text-accent-violet transition-colors px-2 py-1 rounded-lg border border-white/[0.06] hover:border-accent-violet/30"
                      >
                        <Settings2 className="h-3 w-3" />
                        {isExpanded ? 'Close' : configured ? 'Edit' : 'Configure'}
                      </button>
                    )}
                  </div>
                </div>
                {configured && int.key === 'JIRA' && org?.jiraBaseUrl && (
                  <p className="text-xs text-zinc-500 mt-1 truncate">URL: {org.jiraBaseUrl}</p>
                )}
                {configured && int.key === 'CONFLUENCE' && org?.confluenceBaseUrl && (
                  <p className="text-xs text-zinc-500 mt-1 truncate">URL: {org.confluenceBaseUrl}</p>
                )}
                {configured && int.key === 'SLACK' && slackStatus?.teamName && (
                  <p className="text-xs text-zinc-500 mt-1 truncate">Workspace: {slackStatus.teamName}</p>
                )}
              </div>

              {/* Expanded credential form */}
              {isExpanded && (int.key === 'SLACK' ? user?.orgId : org) && (
                <div className="px-4 pb-4 pt-1 border-t border-white/[0.06]">
                  {int.key === 'JIRA' && org && (
                    <JiraCredentialForm org={org} onSaved={handleOrgUpdated} />
                  )}
                  {int.key === 'CONFLUENCE' && org && (
                    <ConfluenceCredentialForm org={org} onSaved={handleOrgUpdated} />
                  )}
                  {int.key === 'SLACK' && (
                    <SlackConfigForm slackStatus={slackStatus} onRefresh={fetchSlackStatus} />
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Access Token Setup Guide */}
      {showGuide && (
        <GlassPanel className="p-4">
          <h3 className="text-sm font-semibold text-zinc-200 mb-3 flex items-center gap-2">
            <HelpCircle className="h-4 w-4 text-accent-violet" />
            How to Get Access Tokens
          </h3>
          <p className="text-xs text-zinc-500 mb-4">
            Each integration requires an API token or credentials. Follow the steps below to configure access for each service.
          </p>
          <div className="space-y-1">
            {Object.entries(SETUP_GUIDES).map(([key, guide]) => (
              <div key={key} className="border border-white/[0.06] rounded-lg overflow-hidden">
                <button
                  onClick={() => toggleGuide(key)}
                  className="w-full flex items-center gap-2 px-3 py-2.5 text-left hover:bg-white/[0.02] transition-colors"
                >
                  {openGuides[key]
                    ? <ChevronDown className="h-3.5 w-3.5 text-zinc-500 flex-shrink-0" />
                    : <ChevronRight className="h-3.5 w-3.5 text-zinc-500 flex-shrink-0" />
                  }
                  <span className="text-sm font-medium text-zinc-300">{guide.title}</span>
                  <a
                    href={guide.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={(e) => e.stopPropagation()}
                    className="ml-auto text-xs text-accent-violet hover:text-accent-violet/80 flex items-center gap-1"
                  >
                    Open <ExternalLink className="h-3 w-3" />
                  </a>
                </button>
                {openGuides[key] && (
                  <div className="px-3 pb-3 pt-0">
                    <ol className="list-decimal list-inside space-y-1.5 text-xs text-zinc-400 ml-5">
                      {guide.steps.map((step, i) => (
                        <li key={i} className="leading-relaxed">{step}</li>
                      ))}
                    </ol>
                  </div>
                )}
              </div>
            ))}
          </div>
        </GlassPanel>
      )}

      {/* Integration runs */}
      {runs.length > 0 && (
        <GlassPanel className="overflow-hidden">
          <div className="px-4 py-3 border-b border-white/[0.06]">
            <h3 className="text-sm font-semibold text-zinc-200">Integration-triggered Runs</h3>
          </div>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-white/[0.04]">
                {['Run ID', 'Status', 'Goal', 'Created'].map((h) => (
                  <th key={h} className="text-left px-4 py-2.5 text-xs font-medium text-zinc-500">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {runs.slice(0, 20).map((r) => (
                <tr key={r.runId} className="border-b border-white/[0.04] hover:bg-white/[0.02]">
                  <td className="px-4 py-2.5 font-mono text-xs text-zinc-400">{r.runId.slice(0, 12)}</td>
                  <td className="px-4 py-2.5 text-xs text-zinc-300">{r.status}</td>
                  <td className="px-4 py-2.5 text-xs text-zinc-400 max-w-xs truncate">{r.userRequest.slice(0, 60)}</td>
                  <td className="px-4 py-2.5 text-xs text-zinc-600">{relativeTime(r.startedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </GlassPanel>
      )}
    </div>
  );
}
