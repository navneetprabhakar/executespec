import {useEffect, useState, useCallback} from 'react';
import {
  ChevronDown,
  ChevronRight,
  HelpCircle,
  ExternalLink,
  Eye,
  EyeOff,
  CheckCircle,
  AlertCircle,
  Loader2,
  Settings2,
  Unplug,
  Send,
  Hash,
  RefreshCw,
  Github,
} from 'lucide-react';
import IntegrationLogo from '@/components/integrations/IntegrationLogo';
import GlassPanel from '@/components/shared/GlassPanel';
import PageShell from '@/components/shared/PageShell';
import {useAuthStore} from '@/stores/useAuthStore';
import {useTierPermissions} from '@/hooks/useTierPermissions';
import * as api from '@/lib/api';
import {relativeTime, errMsg} from '@/lib/utils';
import type {RunSummary, OrgDto, SlackStatus, SlackChannel} from '@/lib/api';

// ── Integration metadata ──────────────────────────────────────────────────

// v2.5.14 (Phase 1.7) — categorised groups + honest "Coming soon" copy.
// Was: one flat list, "Unavailable" for everything not shipped.
const INTEGRATIONS: {
  key: string;
  name: string;
  description: string;
  configurable: boolean;
  userLevel?: boolean;
  category: 'code' | 'notifications' | 'ticketing' | 'incident';
  comingSoon?: boolean;
}[] = [
  {
    key: 'GITHUB',
    name: 'GitHub',
    description: 'Push code to GitHub',
    configurable: false,
    category: 'code',
  },
  {
    key: 'BITBUCKET',
    name: 'Bitbucket',
    description: 'Push code to Bitbucket',
    configurable: true,
    userLevel: true,
    category: 'code',
  },
  {
    key: 'SLACK',
    name: 'Slack',
    description: 'Pipeline notifications',
    configurable: true,
    category: 'notifications',
  },
  {
    key: 'JIRA',
    name: 'Jira',
    description: 'Issue-triggered runs',
    configurable: true,
    category: 'ticketing',
  },
  {
    key: 'CONFLUENCE',
    name: 'Confluence',
    description: 'Page-triggered runs',
    configurable: true,
    category: 'ticketing',
  },
  {
    key: 'SERVICENOW',
    name: 'ServiceNow',
    description: 'Incident response runs',
    configurable: false,
    category: 'incident',
    comingSoon: true,
  },
  {
    key: 'PAGERDUTY',
    name: 'PagerDuty',
    description: 'Alert-triggered runs',
    configurable: false,
    category: 'incident',
    comingSoon: true,
  },
];

const CATEGORY_LABELS: Record<string, string> = {
  code: 'Source control',
  notifications: 'Notifications',
  ticketing: 'Ticketing',
  incident: 'Incident response',
};

interface SetupGuide {
  title: string;
  url: string;
  steps: string[];
}

const SETUP_GUIDES: Record<string, SetupGuide> = {
  SLACK: {
    title: 'Slack Bot Installation',
    url: 'https://api.slack.com/apps',
    steps: [
      'Click "Configure" on the Slack card and then "Connect to Slack"',
      "You'll be redirected to Slack to authorize the ExecuteSpec bot",
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
      "Copy the generated token immediately (it won't be shown again)",
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
  BITBUCKET: {
    title: 'Bitbucket App Password',
    url: 'https://bitbucket.org/account/settings/app-passwords/',
    steps: [
      'Go to Bitbucket > Personal settings > App passwords',
      'Click "Create app password" and give it a label (e.g. "ExecuteSpec")',
      'Required permissions: Repositories (Read, Write, Admin), Pull requests (Read, Write)',
      "Copy the generated app password immediately (it won't be shown again)",
      'Click Configure on the Bitbucket card below and enter your credentials',
    ],
  },
};

// ── Secret input ──────────────────────────────────────────────────────────

function SecretField({
  label,
  value,
  onChange,
  placeholder,
  subLabel,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
  subLabel?: string;
}) {
  const [show, setShow] = useState(false);
  return (
    <div>
      <label className='text-xs text-secondary mb-1 block'>
        {label} {subLabel && <span className='text-muted'>{subLabel}</span>}
      </label>
      <div className='relative'>
        <input
          type={show ? 'text' : 'password'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-sm text-primary placeholder-[var(--text-muted)] focus:border-accent-blue focus:outline-none pr-10'
        />
        <button
          type='button'
          onClick={() => setShow((v) => !v)}
          className='absolute right-2.5 top-1/2 -translate-y-1/2 text-muted hover:text-secondary'
        >
          {show ? <EyeOff className='h-4 w-4' /> : <Eye className='h-4 w-4' />}
        </button>
      </div>
    </div>
  );
}

function TextField({
  label,
  value,
  onChange,
  placeholder,
  hint,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
  hint?: string;
}) {
  return (
    <div>
      <label className='text-xs text-secondary mb-1 block'>{label}</label>
      <input
        type='text'
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-sm text-primary placeholder-[var(--text-muted)] focus:border-accent-blue focus:outline-none'
      />
      {hint && <p className='text-xs text-muted mt-1'>{hint}</p>}
    </div>
  );
}

// ── JIRA Credential Form ──────────────────────────────────────────────────

function JiraCredentialForm({org, onSaved}: {org: OrgDto; onSaved: (o: OrgDto) => void}) {
  const [baseUrl, setBaseUrl] = useState(org.jiraBaseUrl ?? '');
  const [email, setEmail] = useState('');
  const [token, setToken] = useState('');
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [msg, setMsg] = useState<{ok: boolean; text: string} | null>(null);

  useEffect(() => {
    setBaseUrl(org.jiraBaseUrl ?? '');
  }, [org.jiraBaseUrl]);

  const handleTest = async () => {
    setTesting(true);
    setMsg(null);
    try {
      const result = await api.validateOrgJira(baseUrl, email, token);
      setMsg({ok: result.valid, text: result.message});
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Validation failed.'});
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setMsg(null);
    try {
      const updated = await api.updateOrgJiraSettings(baseUrl, email, token);
      onSaved(updated);
      setToken('');
      setMsg({ok: true, text: 'JIRA credentials saved.'});
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Failed to save.'});
    } finally {
      setSaving(false);
    }
  };

  const handleDisconnect = async () => {
    if (
      !confirm('Remove JIRA credentials? This will disable JIRA integration for your organization.')
    )
      return;
    setSaving(true);
    setMsg(null);
    try {
      const updated = await api.updateOrgJiraSettings('', '', '');
      onSaved(updated);
      setBaseUrl('');
      setEmail('');
      setToken('');
      setMsg({ok: true, text: 'JIRA credentials removed.'});
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Failed to clear.'});
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSave} className='space-y-3'>
      <TextField
        label='JIRA Base URL'
        value={baseUrl}
        onChange={setBaseUrl}
        placeholder='https://yourorg.atlassian.net'
      />
      <TextField
        label='Email'
        value={email}
        onChange={setEmail}
        placeholder='you@yourorg.com'
        hint={org.hasJiraCredentials ? 'Leave blank to keep existing' : undefined}
      />
      <SecretField
        label='API Token'
        subLabel={org.hasJiraCredentials ? '(leave blank to keep existing)' : undefined}
        value={token}
        onChange={setToken}
        placeholder={org.hasJiraCredentials ? '(saved)' : 'Atlassian API token'}
      />
      {msg && (
        <p className={`text-xs ${msg.ok ? 'text-accent-green' : 'text-accent-red'}`}>{msg.text}</p>
      )}
      <div className='flex gap-3 pt-1'>
        <button
          type='submit'
          disabled={saving || (!baseUrl.trim() && !email.trim() && !token.trim())}
          className='px-4 py-2 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 disabled:opacity-50 transition-colors'
        >
          {saving ? 'Saving...' : 'Save'}
        </button>
        <button
          type='button'
          onClick={handleTest}
          disabled={testing || !baseUrl.trim() || !email.trim() || !token.trim()}
          className='flex items-center gap-1.5 px-4 py-2 rounded-sm border border-[var(--border-default)] text-secondary text-sm hover:border-accent-blue/40 hover:text-primary disabled:opacity-40 transition-colors'
        >
          {testing ? <Loader2 className='h-3.5 w-3.5 animate-spin' /> : null}
          Test Connection
        </button>
        {org.hasJiraCredentials && (
          <button
            type='button'
            onClick={handleDisconnect}
            disabled={saving}
            className='flex items-center gap-1.5 px-4 py-2 rounded-sm text-secondary hover:text-accent-red text-sm transition-colors disabled:opacity-50'
          >
            <Unplug className='h-3.5 w-3.5' />
            Disconnect
          </button>
        )}
      </div>
    </form>
  );
}

// ── Confluence Credential Form ────────────────────────────────────────────

function ConfluenceCredentialForm({org, onSaved}: {org: OrgDto; onSaved: (o: OrgDto) => void}) {
  const [baseUrl, setBaseUrl] = useState(org.confluenceBaseUrl ?? '');
  const [email, setEmail] = useState('');
  const [token, setToken] = useState('');
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [msg, setMsg] = useState<{ok: boolean; text: string} | null>(null);

  useEffect(() => {
    setBaseUrl(org.confluenceBaseUrl ?? '');
  }, [org.confluenceBaseUrl]);

  const handleTest = async () => {
    setTesting(true);
    setMsg(null);
    try {
      const result = await api.validateOrgConfluence(baseUrl, email, token);
      setMsg({ok: result.valid, text: result.message});
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Validation failed.'});
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setMsg(null);
    try {
      const updated = await api.updateOrgConfluenceSettings(baseUrl, email, token);
      onSaved(updated);
      setToken('');
      setMsg({ok: true, text: 'Confluence credentials saved.'});
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Failed to save.'});
    } finally {
      setSaving(false);
    }
  };

  const handleDisconnect = async () => {
    if (
      !confirm(
        'Remove Confluence credentials? This will disable Confluence integration for your organization.',
      )
    )
      return;
    setSaving(true);
    setMsg(null);
    try {
      const updated = await api.updateOrgConfluenceSettings('', '', '');
      onSaved(updated);
      setBaseUrl('');
      setEmail('');
      setToken('');
      setMsg({ok: true, text: 'Confluence credentials removed.'});
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Failed to clear.'});
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSave} className='space-y-3'>
      <TextField
        label='Confluence Base URL'
        value={baseUrl}
        onChange={setBaseUrl}
        placeholder='https://yourorg.atlassian.net'
      />
      <TextField
        label='Email'
        value={email}
        onChange={setEmail}
        placeholder='you@yourorg.com'
        hint={org.hasConfluenceCredentials ? 'Leave blank to keep existing' : undefined}
      />
      <SecretField
        label='API Token'
        subLabel={org.hasConfluenceCredentials ? '(leave blank to keep existing)' : undefined}
        value={token}
        onChange={setToken}
        placeholder={org.hasConfluenceCredentials ? '(saved)' : 'Atlassian API token'}
      />
      {msg && (
        <p className={`text-xs ${msg.ok ? 'text-accent-green' : 'text-accent-red'}`}>{msg.text}</p>
      )}
      <div className='flex gap-3 pt-1'>
        <button
          type='submit'
          disabled={saving || (!baseUrl.trim() && !email.trim() && !token.trim())}
          className='px-4 py-2 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 disabled:opacity-50 transition-colors'
        >
          {saving ? 'Saving...' : 'Save'}
        </button>
        <button
          type='button'
          onClick={handleTest}
          disabled={testing || !baseUrl.trim() || !email.trim() || !token.trim()}
          className='flex items-center gap-1.5 px-4 py-2 rounded-sm border border-[var(--border-default)] text-secondary text-sm hover:border-accent-blue/40 hover:text-primary disabled:opacity-40 transition-colors'
        >
          {testing ? <Loader2 className='h-3.5 w-3.5 animate-spin' /> : null}
          Test Connection
        </button>
        {org.hasConfluenceCredentials && (
          <button
            type='button'
            onClick={handleDisconnect}
            disabled={saving}
            className='flex items-center gap-1.5 px-4 py-2 rounded-sm text-secondary hover:text-accent-red text-sm transition-colors disabled:opacity-50'
          >
            <Unplug className='h-3.5 w-3.5' />
            Disconnect
          </button>
        )}
      </div>
    </form>
  );
}

// ── Bitbucket Credential Form (user-level) ──────────────────────────────

function BitbucketCredentialForm({onSaved}: {onSaved: () => void}) {
  const {user} = useAuthStore();
  const loadMe = useAuthStore((s) => s.loadMe);
  const [username, setUsername] = useState(user?.bitbucketUsername ?? '');
  const [appPassword, setAppPassword] = useState('');
  const [workspace, setWorkspace] = useState(user?.bitbucketWorkspace ?? '');
  const [repoSlug, setRepoSlug] = useState(user?.bitbucketRepoSlug ?? '');
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [msg, setMsg] = useState<{ok: boolean; text: string} | null>(null);

  useEffect(() => {
    setUsername(user?.bitbucketUsername ?? '');
    setWorkspace(user?.bitbucketWorkspace ?? '');
    setRepoSlug(user?.bitbucketRepoSlug ?? '');
  }, [user?.bitbucketUsername, user?.bitbucketWorkspace, user?.bitbucketRepoSlug]);

  const handleTest = async () => {
    setTesting(true);
    setMsg(null);
    try {
      const result = await api.validateBitbucket(username, appPassword, workspace, repoSlug);
      setMsg({ok: result.valid, text: result.message});
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Validation failed.'});
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setMsg(null);
    try {
      await api.updateBitbucketSettings(username, appPassword, workspace, repoSlug);
      await loadMe();
      setAppPassword('');
      setMsg({ok: true, text: 'Bitbucket credentials saved.'});
      onSaved();
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Failed to save.'});
    } finally {
      setSaving(false);
    }
  };

  const handleDisconnect = async () => {
    if (!confirm('Remove Bitbucket credentials? This will disable Bitbucket integration.')) return;
    setSaving(true);
    setMsg(null);
    try {
      await api.updateBitbucketSettings('', '', '', '');
      await loadMe();
      setUsername('');
      setAppPassword('');
      setWorkspace('');
      setRepoSlug('');
      setMsg({ok: true, text: 'Bitbucket credentials removed.'});
      onSaved();
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Failed to clear.'});
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSave} className='space-y-3'>
      <TextField
        label='Username'
        value={username}
        onChange={setUsername}
        placeholder='your-bitbucket-username'
      />
      <SecretField
        label='App Password'
        subLabel={user?.hasBitbucketAppPassword ? '(leave blank to keep existing)' : undefined}
        value={appPassword}
        onChange={setAppPassword}
        placeholder={user?.hasBitbucketAppPassword ? '(saved)' : 'Bitbucket app password'}
      />
      <TextField
        label='Workspace'
        value={workspace}
        onChange={setWorkspace}
        placeholder='your-workspace'
        hint='Bitbucket workspace slug'
      />
      <TextField
        label='Repository Slug'
        value={repoSlug}
        onChange={setRepoSlug}
        placeholder='your-repo'
        hint='Target repository slug'
      />
      {msg && (
        <p className={`text-xs ${msg.ok ? 'text-accent-green' : 'text-accent-red'}`}>{msg.text}</p>
      )}
      <div className='flex flex-wrap gap-3 pt-1'>
        <button
          type='submit'
          disabled={saving || !username.trim() || !workspace.trim() || !repoSlug.trim()}
          className='px-4 py-2 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 disabled:opacity-50 transition-colors'
        >
          {saving ? 'Saving...' : 'Save'}
        </button>
        <button
          type='button'
          onClick={handleTest}
          disabled={
            testing ||
            !username.trim() ||
            !appPassword.trim() ||
            !workspace.trim() ||
            !repoSlug.trim()
          }
          className='flex items-center gap-1.5 px-4 py-2 rounded-sm border border-[var(--border-default)] text-secondary text-sm hover:border-accent-blue/40 hover:text-primary disabled:opacity-40 transition-colors'
        >
          {testing ? <Loader2 className='h-3.5 w-3.5 animate-spin' /> : null}
          Test Connection
        </button>
        {user?.hasBitbucketAppPassword && (
          <button
            type='button'
            onClick={handleDisconnect}
            disabled={saving}
            className='flex items-center gap-1.5 px-4 py-2 rounded-sm text-secondary hover:text-accent-red text-sm transition-colors disabled:opacity-50'
          >
            <Unplug className='h-3.5 w-3.5' />
            Disconnect
          </button>
        )}
      </div>
    </form>
  );
}

// ── Slack OAuth Config Form ──────────────────────────────────────────────

function SlackConfigForm({
  slackStatus,
  onRefresh,
}: {
  slackStatus: SlackStatus | null;
  onRefresh: () => void;
}) {
  const [connecting, setConnecting] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const [testingChannel, setTestingChannel] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ok: boolean; text: string} | null>(null);

  const handleConnect = async () => {
    setConnecting(true);
    setMsg(null);
    try {
      const {installUrl} = await api.getSlackInstallUrl();
      window.location.href = installUrl;
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Failed to start OAuth flow.'});
      setConnecting(false);
    }
  };

  const handleDisconnect = async () => {
    if (
      !confirm('Disconnect Slack? This will remove all channel mappings and revoke the bot token.')
    )
      return;
    setDisconnecting(true);
    setMsg(null);
    try {
      await api.disconnectSlack();
      setMsg({ok: true, text: 'Slack disconnected.'});
      onRefresh();
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Failed to disconnect.'});
    } finally {
      setDisconnecting(false);
    }
  };

  const handleTest = async (channelId: string) => {
    setTestingChannel(channelId);
    setMsg(null);
    try {
      const result = await api.sendSlackTestMessage(channelId);
      setMsg({
        ok: result.ok,
        text: result.ok ? 'Test message sent!' : result.message || 'Test failed.',
      });
    } catch (err) {
      setMsg({ok: false, text: errMsg(err) ?? 'Failed to send test.'});
    } finally {
      setTestingChannel(null);
    }
  };

  if (!slackStatus?.connected) {
    return (
      <div className='space-y-3'>
        <p className='text-xs text-secondary'>
          Connect your Slack workspace to receive pipeline notifications in your channels.
        </p>
        {msg && (
          <p className={`text-xs ${msg.ok ? 'text-accent-green' : 'text-accent-red'}`}>
            {msg.text}
          </p>
        )}
        <button
          onClick={handleConnect}
          disabled={connecting}
          className='flex items-center gap-2 px-4 py-2 rounded-sm bg-[#4A154B] text-primary text-sm font-medium hover:bg-[#4A154B]/80 disabled:opacity-50 transition-colors'
        >
          {connecting ? (
            <Loader2 className='h-3.5 w-3.5 animate-spin' />
          ) : (
            <svg width='16' height='16' viewBox='0 0 32 32' fill='none'>
              <path
                d='M8.8 19.2a2.4 2.4 0 01-2.4 2.4A2.4 2.4 0 014 19.2a2.4 2.4 0 012.4-2.4h2.4v2.4z'
                fill='#fff'
              />
              <path
                d='M10 19.2a2.4 2.4 0 012.4-2.4 2.4 2.4 0 012.4 2.4v6a2.4 2.4 0 01-2.4 2.4 2.4 2.4 0 01-2.4-2.4v-6z'
                fill='#fff'
              />
              <path
                d='M12.4 8.8a2.4 2.4 0 01-2.4-2.4A2.4 2.4 0 0112.4 4a2.4 2.4 0 012.4 2.4v2.4h-2.4z'
                fill='#fff'
              />
              <path
                d='M12.4 10a2.4 2.4 0 012.4 2.4 2.4 2.4 0 01-2.4 2.4h-6A2.4 2.4 0 014 12.4 2.4 2.4 0 016.4 10h6z'
                fill='#fff'
              />
            </svg>
          )}
          Connect to Slack
        </button>
      </div>
    );
  }

  return (
    <div className='space-y-3'>
      {/* Connection status */}
      <div className='flex items-center gap-2 text-xs'>
        <CheckCircle className='h-3.5 w-3.5 text-accent-green' />
        <span className='text-secondary'>
          Connected to <span className='font-medium text-primary'>{slackStatus.teamName}</span>
        </span>
        {slackStatus.channelCount != null && (
          <span className='text-muted'>
            ({slackStatus.channelCount} channel{slackStatus.channelCount !== 1 ? 's' : ''} mapped)
          </span>
        )}
      </div>

      {slackStatus.installedBy && (
        <p className='text-xs text-muted'>
          Installed by {slackStatus.installedBy}
          {slackStatus.installedAt && ` \u00B7 ${relativeTime(slackStatus.installedAt)}`}
        </p>
      )}

      <p className='text-xs text-muted'>
        Configure project-level channel mappings from each project&apos;s settings. Use the test
        button below to verify the bot can post to a channel.
      </p>

      {msg && (
        <p className={`text-xs ${msg.ok ? 'text-accent-green' : 'text-accent-red'}`}>{msg.text}</p>
      )}

      <SlackTestSection onTest={handleTest} testingChannel={testingChannel} />

      <div className='flex gap-3 pt-1'>
        <button
          onClick={onRefresh}
          className='flex items-center gap-1.5 px-3 py-1.5 rounded-sm border border-[var(--border-default)] text-secondary hover:text-primary text-xs transition-colors'
        >
          <RefreshCw className='h-3 w-3' /> Refresh Status
        </button>
        <button
          onClick={handleDisconnect}
          disabled={disconnecting}
          className='flex items-center gap-1.5 px-3 py-1.5 rounded-sm text-secondary hover:text-accent-red text-xs transition-colors disabled:opacity-50'
        >
          <Unplug className='h-3 w-3' />
          {disconnecting ? 'Disconnecting...' : 'Disconnect'}
        </button>
      </div>
    </div>
  );
}

function SlackTestSection({
  onTest,
  testingChannel,
}: {
  onTest: (channelId: string) => void;
  testingChannel: string | null;
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
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className='flex items-end gap-2'>
      <div className='flex-1'>
        <label className='text-xs text-secondary mb-1 block'>Send test message</label>
        <div className='flex gap-2'>
          {channels.length === 0 ? (
            <button
              onClick={loadChannels}
              disabled={loading}
              className='flex items-center gap-1.5 px-3 py-2 rounded-sm border border-[var(--border-default)] text-secondary text-xs hover:border-accent-blue/40 hover:text-primary disabled:opacity-40 transition-colors'
            >
              {loading ? (
                <Loader2 className='h-3 w-3 animate-spin' />
              ) : (
                <Hash className='h-3 w-3' />
              )}
              Load Channels
            </button>
          ) : (
            <>
              <select
                value={selectedChannel}
                onChange={(e) => setSelectedChannel(e.target.value)}
                className='flex-1 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-xs text-primary focus:border-accent-blue focus:outline-none'
              >
                {channels.map((ch) => (
                  <option key={ch.channelId} value={ch.channelId}>
                    #{ch.channelName}
                  </option>
                ))}
              </select>
              <button
                onClick={() => selectedChannel && onTest(selectedChannel)}
                disabled={!selectedChannel || testingChannel != null}
                className='flex items-center gap-1.5 px-3 py-2 rounded-sm bg-accent-blue text-primary text-xs font-medium hover:bg-accent-blue/80 disabled:opacity-50 transition-colors'
              >
                {testingChannel ? (
                  <Loader2 className='h-3 w-3 animate-spin' />
                ) : (
                  <Send className='h-3 w-3' />
                )}
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
  const {user} = useAuthStore();
  const permissions = useTierPermissions();
  const [enabledSources, setEnabledSources] = useState<string[]>([]);
  const [org, setOrg] = useState<OrgDto | null>(null);
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [showGuide, setShowGuide] = useState(false);
  const [openGuides, setOpenGuides] = useState<Record<string, boolean>>({});
  const [configuring, setConfiguring] = useState<string | null>(null);
  const [slackStatus, setSlackStatus] = useState<SlackStatus | null>(null);
  // M-005 (Wave 1) — GitHub App org-wide install card.
  const [ghAppStatus, setGhAppStatus] = useState<api.GitHubAppStatus | null>(null);
  const [ghAppBusy, setGhAppBusy] = useState(false);
  const [ghAppMsg, setGhAppMsg] = useState<string | null>(null);
  const refreshGhApp = useCallback(() => {
    if (!user?.orgId) return;
    api
      .getGitHubAppStatus()
      .then(setGhAppStatus)
      .catch(() => setGhAppStatus(null));
  }, [user?.orgId]);
  const handleGhAppInstall = async () => {
    setGhAppBusy(true);
    setGhAppMsg(null);
    try {
      const r = await api.getGitHubAppInstallUrl();
      window.open(r.installUrl, '_blank', 'noopener,noreferrer');
      setGhAppMsg('Install opened in new tab. Click "Refresh status" after completing.');
    } catch (e: unknown) {
      setGhAppMsg(e instanceof Error ? errMsg(e) : 'Failed to fetch install URL');
    } finally {
      setGhAppBusy(false);
    }
  };
  const handleGhAppUninstall = async () => {
    if (
      !confirm(
        'Uninstall the ExecuteSpec GitHub App for this org? You will also need to revoke from GitHub side.',
      )
    )
      return;
    setGhAppBusy(true);
    setGhAppMsg(null);
    try {
      await api.uninstallGitHubApp();
      setGhAppMsg('Uninstalled locally. Remember to revoke on github.com/settings/installations.');
      refreshGhApp();
    } catch (e: unknown) {
      setGhAppMsg(e instanceof Error ? errMsg(e) : 'Failed to uninstall');
    } finally {
      setGhAppBusy(false);
    }
  };
  // v2.5.8 — fetch public feature flags so Configure CTAs are gated when the
  // platform has turned off an integration. Without this, users clicking
  // Configure on Jira (with integrations.jira.enabled=false) hit silent 403s.
  const [publicFlags, setPublicFlags] = useState<Record<string, boolean>>({});
  useEffect(() => {
    api
      .getPublicFeatureFlags()
      .then(setPublicFlags)
      .catch(() => setPublicFlags({}));
  }, []);

  const isAdmin = user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN';

  /** v2.5.8 — check if an integration is enabled at the platform feature-flag level.
   *  Returns true by default (absence of a flag means "not gated"). */
  const integrationFlagEnabled = (key: string): boolean => {
    const flagKey = `integrations.${key.toLowerCase()}.enabled`;
    return publicFlags[flagKey] !== false;
  };

  // R-09 lint cleanup — react-hooks/rules-of-hooks: hooks must be called
  // unconditionally before any early return. Pre-fix the tier-gate early
  // return below sat above these hooks, so the hook order changed once
  // permissions resolved. Moving them above the gate fixed it.
  const fetchSlackStatus = useCallback(() => {
    if (!user?.orgId) return;
    api
      .getSlackStatus()
      .then(setSlackStatus)
      .catch(() => setSlackStatus(null));
  }, [user?.orgId]);

  useEffect(() => {
    api
      .getIntegrationStatus()
      .then((r) => setEnabledSources(r.enabledSources))
      .catch(() => {});
    api
      .getIntegrationRuns()
      .then(setRuns)
      .catch(() => {});
    refreshGhApp();
    if (user?.orgId) {
      api
        .getMyOrg()
        .then(setOrg)
        .catch(() => {});
      fetchSlackStatus();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  // Tier gate — show locked message if user doesn't have integration permissions.
  // (Stays AFTER all hooks, per the rule.)
  if (permissions && !permissions.canManageIntegrations) {
    return (
      <div className='h-full overflow-y-auto p-4 flex items-center justify-center'>
        <div className='text-center space-y-3 max-w-md'>
          <div className='h-12 w-12 rounded-sm bg-[var(--gray-200)] border border-[var(--border-default)] flex items-center justify-center mx-auto'>
            <Unplug className='h-6 w-6 text-muted' />
          </div>
          <h2 className='text-lg font-semibold text-primary'>Integrations Unavailable</h2>
          <p className='text-sm text-muted leading-relaxed'>
            Managing integrations requires a{' '}
            <span className='text-accent-blue font-medium'>PRO</span> or{' '}
            <span className='text-accent-blue font-medium'>ENTERPRISE</span> plan with org admin
            access. Contact your organization admin to upgrade.
          </p>
        </div>
      </div>
    );
  }

  const handleOrgUpdated = (updatedOrg: OrgDto) => {
    setOrg(updatedOrg);
    // Re-derive enabled sources from org credentials
    const sources: string[] = [];
    if (updatedOrg.hasJiraCredentials) sources.push('JIRA');
    if (updatedOrg.hasConfluenceCredentials) sources.push('CONFLUENCE');
    setEnabledSources(sources);
  };

  const toggleGuide = (key: string) => {
    setOpenGuides((prev) => ({...prev, [key]: !prev[key]}));
  };

  const isConfigured = (key: string): boolean => {
    if (key === 'JIRA') return !!org?.hasJiraCredentials;
    if (key === 'CONFLUENCE') return !!org?.hasConfluenceCredentials;
    if (key === 'SLACK') return !!slackStatus?.connected;
    if (key === 'GITHUB') return !!user?.hasGithubPat;
    if (key === 'BITBUCKET') return !!user?.hasBitbucketAppPassword;
    return enabledSources.includes(key);
  };

  return (
    <PageShell
      eyebrow='Integrations'
      title='Connect your stack.'
      accent='stack'
      description={`External tools that can trigger agent runs.${isAdmin ? ' Click Configure to enable an integration.' : ''}`}
      hero
      actions={
        <button
          onClick={() => setShowGuide(!showGuide)}
          className='inline-flex items-center gap-1.5 text-xs text-secondary hover:text-accent-blue transition-colors px-3 py-1.5 rounded-md border border-[var(--border-default)] hover:border-accent-blue/30'
        >
          <HelpCircle className='h-3.5 w-3.5' />
          Setup Guide
        </button>
      }
    >
      {/* No org warning */}
      {!user?.orgId && (
        <GlassPanel className='px-4 py-3 border-status-waiting/20'>
          <p className='text-xs text-accent-amber'>
            <AlertCircle className='h-3.5 w-3.5 inline mr-1 -mt-0.5' />
            You must be part of an organization to manage integrations. Create one from Org
            Settings.
          </p>
        </GlassPanel>
      )}

      {/* M-005 (Wave 1) — GitHub App org-wide install card. Backend endpoints
          live; this is the first UI binding. ORG_ADMIN only. */}
      {isAdmin && (
        <section className='space-y-2'>
          <h3 className='text-[11px] font-semibold uppercase tracking-wider text-muted'>
            Org-wide GitHub App (recommended)
          </h3>
          <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-4'>
            <div className='flex items-start gap-3'>
              <Github className='h-5 w-5 text-secondary mt-0.5 flex-shrink-0' />
              <div className='flex-1 min-w-0'>
                <div className='flex items-center gap-2 flex-wrap'>
                  <p className='text-sm font-semibold text-primary'>GitHub App (org-wide)</p>
                  {ghAppStatus?.installed && (
                    <span className='text-[10px] font-mono uppercase tracking-wide px-1.5 py-0.5 rounded border bg-status-success/10 text-status-success border-status-success/30'>
                      Installed
                    </span>
                  )}
                </div>
                <p className='text-xs text-muted mt-1 leading-relaxed'>
                  Install ExecuteSpec as an org-wide GitHub App so every project in your org can act
                  on shared repos without per-user PATs. Recommended over personal access tokens for
                  team / enterprise setups.
                </p>
                {ghAppStatus?.installed && ghAppStatus.installedAt && (
                  <p className='text-[11px] text-muted mt-1.5 font-mono'>
                    Installation #{ghAppStatus.installationId} · since{' '}
                    {new Date(ghAppStatus.installedAt).toLocaleDateString()}
                  </p>
                )}
                {ghAppMsg && <p className='text-[11px] text-secondary mt-2'>{ghAppMsg}</p>}
                <div className='flex items-center gap-2 mt-3 flex-wrap'>
                  {!ghAppStatus?.installed ? (
                    <button
                      onClick={handleGhAppInstall}
                      disabled={ghAppBusy}
                      className='px-3 py-1.5 rounded-sm bg-accent-blue text-primary text-xs font-medium hover:bg-accent-blue/80 disabled:opacity-50'
                    >
                      {ghAppBusy ? 'Working…' : 'Install on GitHub'}
                    </button>
                  ) : (
                    <button
                      onClick={handleGhAppUninstall}
                      disabled={ghAppBusy}
                      className='px-3 py-1.5 rounded-sm text-xs text-secondary hover:text-accent-red border border-[var(--border-default)] hover:border-status-error/40 disabled:opacity-50'
                    >
                      Uninstall
                    </button>
                  )}
                  <button
                    onClick={refreshGhApp}
                    className='px-3 py-1.5 rounded-sm text-xs text-muted hover:text-primary border border-[var(--border-default)]'
                  >
                    Refresh status
                  </button>
                </div>
              </div>
            </div>
          </div>
        </section>
      )}

      {/* Integration grid — grouped by category for faster scanning */}
      {(['code', 'notifications', 'ticketing', 'incident'] as const).map((category) => {
        const items = INTEGRATIONS.filter((i) => i.category === category);
        if (items.length === 0) return null;
        return (
          <section key={category} className='space-y-2'>
            <h3 className='text-[11px] font-semibold uppercase tracking-wider text-muted'>
              {CATEGORY_LABELS[category]}
            </h3>
            <div className='grid grid-cols-1 md:grid-cols-2 gap-3'>
              {items.map((int) => {
                const configured = isConfigured(int.key);
                const isExpanded = configuring === int.key;

                return (
                  <div
                    key={int.key}
                    className={`rounded-sm border transition-all ${
                      configured
                        ? 'border-accent-blue/30 bg-accent-blue/[0.04]'
                        : 'border-[var(--border-default)] bg-[var(--gray-100)]'
                    } ${!configured && !int.configurable ? 'opacity-60 grayscale' : ''}`}
                  >
                    {/* Card header */}
                    <div className='p-4'>
                      <div className='flex items-center justify-between mb-2'>
                        <div className='flex items-center gap-2.5'>
                          <IntegrationLogo integration={int.key} size={28} />
                          <div>
                            <p className='text-sm font-medium text-primary'>{int.name}</p>
                            <p className='text-xs text-muted'>{int.description}</p>
                          </div>
                        </div>
                        <div className='flex items-center gap-2'>
                          {(() => {
                            const flagOn = integrationFlagEnabled(int.key);
                            // v2.5.14 (Phase 1.7) — clearer three-state status:
                            // Enabled / Coming soon (non-configurable) / Admin disabled (flag-off) / Ready to configure.
                            if (configured) {
                              return (
                                <span className='text-xs px-2 py-0.5 rounded-sm font-medium text-accent-green bg-accent-green/10'>
                                  Enabled
                                </span>
                              );
                            }
                            if (int.comingSoon || !int.configurable) {
                              return (
                                <span
                                  className='text-xs px-2 py-0.5 rounded-sm font-medium text-accent-blue bg-accent-blue/10'
                                  title='Coming soon — join the waitlist from Settings'
                                >
                                  Coming soon
                                </span>
                              );
                            }
                            if (!flagOn && int.configurable) {
                              return (
                                <span
                                  className='text-xs px-2 py-0.5 rounded-sm font-medium text-muted bg-[var(--gray-400)]/10'
                                  title='Disabled by platform operator — contact support to enable'
                                >
                                  Admin disabled
                                </span>
                              );
                            }
                            return (
                              <span className='text-xs px-2 py-0.5 rounded-sm font-medium text-muted bg-[var(--gray-400)]/10'>
                                Ready to configure
                              </span>
                            );
                          })()}
                          {int.configurable &&
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            ((int as any).userLevel || (isAdmin && user?.orgId)) &&
                            integrationFlagEnabled(int.key) && (
                              <button
                                onClick={() => setConfiguring(isExpanded ? null : int.key)}
                                className='flex items-center gap-1 text-xs transition-colors px-2 py-1 rounded-sm border text-secondary hover:text-accent-blue border-[var(--border-default)] hover:border-accent-blue/30'
                              >
                                <Settings2 className='h-3 w-3' />
                                {isExpanded ? 'Close' : configured ? 'Edit' : 'Configure'}
                              </button>
                            )}
                        </div>
                      </div>
                      {configured && int.key === 'JIRA' && org?.jiraBaseUrl && (
                        <p className='text-xs text-muted mt-1 truncate'>URL: {org.jiraBaseUrl}</p>
                      )}
                      {configured && int.key === 'CONFLUENCE' && org?.confluenceBaseUrl && (
                        <p className='text-xs text-muted mt-1 truncate'>
                          URL: {org.confluenceBaseUrl}
                        </p>
                      )}
                      {configured && int.key === 'SLACK' && slackStatus?.teamName && (
                        <p className='text-xs text-muted mt-1 truncate'>
                          Workspace: {slackStatus.teamName}
                        </p>
                      )}
                      {configured && int.key === 'GITHUB' && user?.githubOwner && (
                        <p className='text-xs text-muted mt-1 truncate'>
                          Owner: {user.githubOwner}
                        </p>
                      )}
                      {configured && int.key === 'BITBUCKET' && user?.bitbucketWorkspace && (
                        <p className='text-xs text-muted mt-1 truncate'>
                          Workspace: {user.bitbucketWorkspace}/{user.bitbucketRepoSlug}
                        </p>
                      )}
                    </div>

                    {/* Expanded credential form */}
                    {isExpanded &&
                      // eslint-disable-next-line @typescript-eslint/no-explicit-any
                      ((int as any).userLevel || (int.key === 'SLACK' ? user?.orgId : org)) && (
                        <div className='px-4 pb-4 pt-1 border-t border-[var(--border-default)]'>
                          {int.key === 'JIRA' && org && (
                            <JiraCredentialForm org={org} onSaved={handleOrgUpdated} />
                          )}
                          {int.key === 'CONFLUENCE' && org && (
                            <ConfluenceCredentialForm org={org} onSaved={handleOrgUpdated} />
                          )}
                          {int.key === 'SLACK' && (
                            <SlackConfigForm
                              slackStatus={slackStatus}
                              onRefresh={fetchSlackStatus}
                            />
                          )}
                          {int.key === 'BITBUCKET' && (
                            <BitbucketCredentialForm onSaved={() => {}} />
                          )}
                        </div>
                      )}
                  </div>
                );
              })}
            </div>
          </section>
        );
      })}

      {/* Access Token Setup Guide */}
      {showGuide && (
        <GlassPanel className='p-4'>
          <h3 className='text-sm font-semibold text-primary mb-3 flex items-center gap-2'>
            <HelpCircle className='h-4 w-4 text-accent-blue' />
            How to Get Access Tokens
          </h3>
          <p className='text-xs text-muted mb-4'>
            Each integration requires an API token or credentials. Follow the steps below to
            configure access for each service.
          </p>
          <div className='space-y-1'>
            {Object.entries(SETUP_GUIDES).map(([key, guide]) => (
              <div
                key={key}
                className='border border-[var(--border-default)] rounded-sm overflow-hidden'
              >
                <button
                  onClick={() => toggleGuide(key)}
                  className='w-full flex items-center gap-2 px-3 py-2.5 text-left hover:bg-[var(--gray-100)] transition-colors'
                >
                  {openGuides[key] ? (
                    <ChevronDown className='h-3.5 w-3.5 text-muted flex-shrink-0' />
                  ) : (
                    <ChevronRight className='h-3.5 w-3.5 text-muted flex-shrink-0' />
                  )}
                  <span className='text-sm font-medium text-secondary'>{guide.title}</span>
                  <a
                    href={guide.url}
                    target='_blank'
                    rel='noopener noreferrer'
                    onClick={(e) => e.stopPropagation()}
                    className='ml-auto text-xs text-accent-blue hover:text-accent-blue/80 flex items-center gap-1'
                  >
                    Open <ExternalLink className='h-3 w-3' />
                  </a>
                </button>
                {openGuides[key] && (
                  <div className='px-3 pb-3 pt-0'>
                    <ol className='list-decimal list-inside space-y-1.5 text-xs text-secondary ml-5'>
                      {guide.steps.map((step, i) => (
                        <li key={i} className='leading-relaxed'>
                          {step}
                        </li>
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
        <GlassPanel className='overflow-hidden'>
          <div className='px-4 py-3 border-b border-[var(--border-default)]'>
            <h3 className='text-sm font-semibold text-primary'>Integration-triggered Runs</h3>
          </div>
          <div className='overflow-x-auto'>
            <table className='w-full text-sm min-w-[560px] md:min-w-0'>
              <thead>
                <tr className='border-b border-[var(--border-default)]'>
                  {['Run ID', 'Status', 'Goal', 'Created'].map((h) => (
                    <th key={h} className='text-left px-4 py-2.5 text-xs font-medium text-muted'>
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {runs.slice(0, 20).map((r) => (
                  <tr
                    key={r.runId}
                    className='border-b border-[var(--border-default)] hover:bg-[var(--gray-100)]'
                  >
                    <td className='px-4 py-2.5 font-mono text-xs text-secondary'>
                      {r.runId.slice(0, 12)}
                    </td>
                    <td className='px-4 py-2.5 text-xs text-secondary'>{r.status}</td>
                    <td className='px-4 py-2.5 text-xs text-secondary max-w-xs truncate'>
                      {(r.userRequest ?? '').slice(0, 60)}
                    </td>
                    <td className='px-4 py-2.5 text-xs text-muted'>{relativeTime(r.startedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </GlassPanel>
      )}
    </PageShell>
  );
}
