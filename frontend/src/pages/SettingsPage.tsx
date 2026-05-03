import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {
  Eye,
  EyeOff,
  Github,
  CheckCircle,
  AlertCircle,
  Server,
  Database,
  Cpu,
  Clock,
  ChevronDown,
  ChevronRight,
  Key,
  Building2,
  Shield,
  Loader2,
  ArrowUpRight,
} from 'lucide-react';
import GlassPanel from '@/components/shared/GlassPanel';
import PageShell from '@/components/shared/PageShell';
import {useAuthStore} from '@/stores/useAuthStore';
import {useTierPermissions} from '@/hooks/useTierPermissions';
import type {OrgDto} from '@/lib/api';
import * as api from '@/lib/api';
import {fetchBillingStatus, type BillingStatus} from '@/lib/billing-api';
import NotificationPreferences from '@/components/settings/NotificationPreferences';
import {isAnalyticsOptedOut, setAnalyticsOptedOut} from '@/lib/analytics';

// ── Generic expand/collapse card ──────────────────────────────────────────────

interface IntegrationCardProps {
  icon: React.ReactNode;
  title: string;
  statusBadge: React.ReactNode;
  children: React.ReactNode;
  defaultOpen?: boolean;
  /**
   * Optional anchor id for deep-linking. When the URL hash matches this id (e.g.
   * `/settings/manage#github`), the card auto-opens and scrolls into view —
   * V3Settings and V3Integrations rely on this to land users straight on the
   * relevant form. Plain DOM id, not a React key.
   */
  id?: string;
}

function IntegrationCard({
  icon,
  title,
  statusBadge,
  children,
  defaultOpen = false,
  id,
}: IntegrationCardProps) {
  const [open, setOpen] = useState(defaultOpen);
  // Open + scroll on hash match (R-141 deep-link). Re-runs if the user
  // changes the hash without navigating away (anchor click within the page).
  useEffect(() => {
    if (!id) return;
    const sync = () => {
      if (window.location.hash === `#${id}`) {
        setOpen(true);
        // RAF gives the panel a frame to expand before we scroll, so the
        // target ends up centred instead of at the very top.
        requestAnimationFrame(() => {
          document.getElementById(id)?.scrollIntoView({behavior: 'smooth', block: 'start'});
        });
      }
    };
    sync();
    window.addEventListener('hashchange', sync);
    return () => window.removeEventListener('hashchange', sync);
  }, [id]);
  return (
    <GlassPanel className='overflow-hidden' id={id}>
      <button
        type='button'
        onClick={() => setOpen((v) => !v)}
        className='w-full flex items-center gap-2 px-5 py-4 text-left hover:bg-[var(--gray-100)] transition-colors'
      >
        {icon}
        <span className='text-sm font-semibold text-primary flex-1'>{title}</span>
        {statusBadge}
        {open ? (
          <ChevronDown className='h-4 w-4 text-muted ml-1 flex-shrink-0' />
        ) : (
          <ChevronRight className='h-4 w-4 text-muted ml-1 flex-shrink-0' />
        )}
      </button>
      {open && (
        <div className='px-5 pb-5 pt-1 space-y-3 border-t border-[var(--border-default)]'>
          {children}
        </div>
      )}
    </GlassPanel>
  );
}

// ── Status badge helpers ──────────────────────────────────────────────────────

function ConfiguredBadge({label}: {label: string}) {
  return (
    <span className='flex items-center gap-1 text-xs text-accent-green'>
      <CheckCircle className='h-3.5 w-3.5' /> {label}
    </span>
  );
}

function NotConfiguredBadge({label}: {label: string}) {
  return (
    <span className='flex items-center gap-1 text-xs text-accent-amber'>
      <AlertCircle className='h-3.5 w-3.5' /> {label}
    </span>
  );
}

// ── Secret input field ────────────────────────────────────────────────────────

function SecretField({
  label,
  subLabel,
  value,
  onChange,
  placeholder,
}: {
  label: string;
  subLabel?: string;
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
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

// ── Text input field ──────────────────────────────────────────────────────────

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

// ── Save message ──────────────────────────────────────────────────────────────

function SaveMessage({msg}: {msg: {ok: boolean; text: string} | null}) {
  if (!msg) return null;
  return (
    <p className={`text-xs ${msg.ok ? 'text-accent-green' : 'text-accent-red'}`}>{msg.text}</p>
  );
}

// ── Action buttons ────────────────────────────────────────────────────────────

function SaveBar({
  saving,
  disabled,
  onClear,
  clearLabel,
}: {
  saving: boolean;
  disabled?: boolean;
  onClear?: () => void;
  clearLabel?: string;
}) {
  return (
    <div className='flex flex-wrap gap-3 pt-1'>
      <button
        type='submit'
        disabled={saving || disabled}
        className='w-full sm:w-auto px-4 py-2 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 disabled:opacity-50 transition-colors'
      >
        {saving ? 'Saving…' : 'Save'}
      </button>
      {onClear && (
        <button
          type='button'
          onClick={onClear}
          disabled={saving}
          className='w-full sm:w-auto px-4 py-2 rounded-sm text-secondary hover:text-accent-red text-sm transition-colors disabled:opacity-50'
        >
          {clearLabel ?? 'Clear'}
        </button>
      )}
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function SettingsPage() {
  const navigate = useNavigate();
  const {user, setUser} = useAuthStore();
  const permissions = useTierPermissions();
  const [health, setHealth] = useState<api.MetricsResponse | null>(null);
  const [org, setOrg] = useState<OrgDto | null>(null);
  // v2.5.8 — fetch live billing status so the plan badge matches the Billing page.
  // Previously this card was rendered from JWT user.tier, which drifts from the
  // actual subscription plan (admin promotions change tier without updating the
  // subscription row, leaving Settings and Billing contradicting each other).
  const [billing, setBilling] = useState<BillingStatus | null>(null);

  useEffect(() => {
    if (permissions?.canViewMetrics) {
      api
        .getMetrics()
        .then(setHealth)
        .catch(() => {});
    }
  }, [permissions?.canViewMetrics]);

  useEffect(() => {
    fetchBillingStatus()
      .then(setBilling)
      .catch(() => setBilling(null));
  }, []);

  // Load org data for ORG_ADMIN users so we can show org-level key status
  const isOrgAdmin = user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN';
  const isOrgDeveloper = !!user?.orgId && !isOrgAdmin;
  useEffect(() => {
    if (isOrgAdmin) {
      api
        .getMyOrg()
        .then(setOrg)
        .catch(() => {});
    }
  }, [isOrgAdmin]);

  // ── GitHub ───────────────────────────────────────────────────────────────

  const [githubPat, setGithubPat] = useState('');
  const [githubOwner, setGithubOwner] = useState(user?.githubOwner ?? '');
  const [githubSaving, setGithubSaving] = useState(false);
  const [githubMsg, setGithubMsg] = useState<{ok: boolean; text: string} | null>(null);
  const [githubTesting, setGithubTesting] = useState(false);

  useEffect(() => {
    setGithubOwner(user?.githubOwner ?? '');
  }, [user?.githubOwner]);

  const handleTestGitHub = async () => {
    setGithubTesting(true);
    setGithubMsg(null);
    try {
      const result = await api.validateGitHub(githubPat);
      setGithubMsg({ok: result.valid, text: result.message});
    } catch (err: any) {
      setGithubMsg({ok: false, text: err.message ?? 'Validation failed.'});
    } finally {
      setGithubTesting(false);
    }
  };

  const handleSaveGitHub = async (e: React.FormEvent) => {
    e.preventDefault();
    setGithubSaving(true);
    setGithubMsg(null);
    try {
      const updated = await api.updateGithubSettings(githubPat, githubOwner);
      setUser(updated);
      setGithubPat('');
      setGithubMsg({ok: true, text: 'GitHub settings saved.'});
    } catch (err: any) {
      setGithubMsg({ok: false, text: err.message ?? 'Failed to save.'});
    } finally {
      setGithubSaving(false);
    }
  };

  const handleClearGitHub = async () => {
    if (!confirm('Remove your GitHub PAT and owner?')) return;
    setGithubSaving(true);
    setGithubMsg(null);
    try {
      const updated = await api.updateGithubSettings('', '');
      setUser(updated);
      setGithubPat('');
      setGithubOwner('');
      setGithubMsg({ok: true, text: 'GitHub credentials cleared.'});
    } catch (err: any) {
      setGithubMsg({ok: false, text: err.message ?? 'Failed to clear.'});
    } finally {
      setGithubSaving(false);
    }
  };

  // ── AI Keys ──────────────────────────────────────────────────────────────

  const [openAiKey, setOpenAiKey] = useState('');
  const [anthropicKey, setAnthropicKey] = useState('');
  const [geminiKey, setGeminiKey] = useState('');
  const [aiSaving, setAiSaving] = useState(false);
  const [aiMsg, setAiMsg] = useState<{ok: boolean; text: string} | null>(null);

  // Phase 1.2 #24 — BYOK Test buttons. One per provider; pings the
  // provider before saving so users get a clear "key works" / "key
  // rejected by OpenAI" signal instead of waiting for the next run
  // to silently 401.
  const [aiTestState, setAiTestState] = useState<
    Record<'OPENAI' | 'ANTHROPIC' | 'GEMINI', {busy: boolean; result?: {ok: boolean; text: string}}>
  >({
    OPENAI: {busy: false},
    ANTHROPIC: {busy: false},
    GEMINI: {busy: false},
  });
  const handleTestAiKey = async (provider: 'OPENAI' | 'ANTHROPIC' | 'GEMINI', key: string) => {
    if (!key.trim()) {
      setAiTestState((s) => ({
        ...s,
        [provider]: {busy: false, result: {ok: false, text: 'Paste a key first.'}},
      }));
      return;
    }
    setAiTestState((s) => ({...s, [provider]: {busy: true}}));
    try {
      const r = await api.validateAiKey(provider, key.trim());
      setAiTestState((s) => ({
        ...s,
        [provider]: {busy: false, result: {ok: r.valid, text: r.message}},
      }));
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Test failed';
      setAiTestState((s) => ({...s, [provider]: {busy: false, result: {ok: false, text: msg}}}));
    }
  };

  const handleSaveAiKeys = async (e: React.FormEvent) => {
    e.preventDefault();
    setAiSaving(true);
    setAiMsg(null);
    try {
      const updated = await api.updateAiKeySettings(openAiKey, anthropicKey, geminiKey);
      setUser(updated);
      setOpenAiKey('');
      setAnthropicKey('');
      setGeminiKey('');
      setAiMsg({ok: true, text: 'AI provider keys saved.'});
    } catch (err: any) {
      setAiMsg({ok: false, text: err.message ?? 'Failed to save.'});
    } finally {
      setAiSaving(false);
    }
  };

  const handleClearAiKeys = async () => {
    if (!confirm('Remove all AI provider keys?')) return;
    setAiSaving(true);
    setAiMsg(null);
    try {
      const updated = await api.updateAiKeySettings('', '', '');
      setUser(updated);
      setAiMsg({ok: true, text: 'AI provider keys cleared.'});
    } catch (err: any) {
      setAiMsg({ok: false, text: err.message ?? 'Failed to clear.'});
    } finally {
      setAiSaving(false);
    }
  };

  const hasAnyAiKey = user?.hasOpenAiKey || user?.hasAnthropicKey || user?.hasGeminiKey;

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <PageShell
      eyebrow='Settings'
      title='Configure your workspace.'
      accent='workspace'
      description='Account, keys, integrations, and system health — all in one place.'
      hero
    >
      {/* ── Current Plan & Upgrade ───────────────────────────────────────── */}
      <GlassPanel className='px-5 py-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3'>
        <div className='flex items-center gap-3'>
          {(() => {
            // v2.5.8 — billing is the source of truth; fall back to JWT tier when
            // the billing call hasn't returned yet (or the user has no subscription).
            // Tier-coherence: 4-tier ladder (FREE / PRO / TEAM / ENTERPRISE).
            // Legacy MAX was renamed to TEAM in V141 — checks updated to match.
            const planTier = billing?.plan?.tier ?? user?.tier ?? 'FREE';
            const planName =
              billing?.plan?.name ??
              (planTier === 'ENTERPRISE'
                ? 'Enterprise Plan'
                : planTier === 'TEAM'
                  ? 'Team Plan'
                  : planTier === 'PRO'
                    ? 'Pro Plan'
                    : 'Free Plan');
            const badge =
              planTier === 'ENTERPRISE'
                ? {cls: 'bg-accent-amber/20 text-accent-amber', letter: 'E'}
                : planTier === 'TEAM'
                  ? {cls: 'bg-status-success/20 text-status-success', letter: 'T'}
                  : planTier === 'PRO'
                    ? {cls: 'bg-accent-blue/20 text-accent-blue', letter: 'P'}
                    : {cls: 'bg-[var(--gray-400)] text-secondary', letter: 'F'};
            // Task 5+8 (2026-05-01 fix list) — PRO subtitle previously
            // claimed "Unlimited managed runs" which contradicts /pricing
            // (V145 capped PRO at 20/month, grandfathered). FREE subtitle
            // pre-V145 promised lifetime runs; the canonical message is
            // now "10 runs / month".
            const subtitle =
              planTier === 'ENTERPRISE'
                ? 'Unlimited runs, priority support, dedicated infrastructure'
                : planTier === 'TEAM'
                  ? 'BYOK + per-seat collaboration, audit log, SSO'
                  : planTier === 'PRO'
                    ? '20 runs / month (grandfathered) — managed keys, plan & review gate'
                    : '10 runs / month · no credit card';
            return (
              <>
                <div
                  className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold flex-shrink-0 ${badge.cls}`}
                >
                  {badge.letter}
                </div>
                <div>
                  <p className='text-sm font-semibold text-primary'>{planName}</p>
                  <p className='text-xs text-muted'>{subtitle}</p>
                </div>
              </>
            );
          })()}
        </div>
        <div className='flex items-center gap-2 flex-shrink-0'>
          <button
            onClick={() => navigate('/billing')}
            className='w-full sm:w-auto px-3 py-1.5 rounded-sm text-xs text-secondary hover:text-primary border border-[var(--border-default)] hover:border-[var(--border-default)] transition-colors'
          >
            View Billing
          </button>
          {user?.tier === 'FREE' && (
            <button
              onClick={() => navigate('/plans')}
              className='w-full sm:w-auto flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-sm bg-accent-blue text-primary text-xs font-medium hover:bg-accent-blue/80 transition-colors'
            >
              Upgrade Plan
              <ArrowUpRight className='h-3 w-3' />
            </button>
          )}
        </div>
      </GlassPanel>

      {/* ── Org developer notice (JIRA/Confluence/Slack only — GitHub PAT is user-level) */}
      {isOrgDeveloper && (
        <GlassPanel className='px-5 py-4'>
          <div className='flex items-center gap-3'>
            <Building2 className='h-5 w-5 text-accent-blue flex-shrink-0' />
            <div>
              <p className='text-sm font-medium text-primary'>
                JIRA, Confluence & Slack Managed at Org Level
              </p>
              <p className='text-xs text-muted mt-0.5'>
                JIRA, Confluence and Slack integrations are managed by your Org Admin. GitHub PAT is
                configured individually below.
              </p>
            </div>
          </div>
        </GlassPanel>
      )}

      {/* ── GitHub (user-level — shown to all users, not org-admin gated) ── */}
      <IntegrationCard
        id='github'
        icon={<Github className='h-4 w-4 text-secondary' />}
        title='GitHub Integration'
        statusBadge={
          user?.hasGithubPat ? (
            <ConfiguredBadge label='PAT configured' />
          ) : (
            <NotConfiguredBadge label='No PAT — push disabled' />
          )
        }
        defaultOpen={!user?.hasGithubPat}
      >
        <p className='text-xs text-muted'>
          Runs auto-push code to GitHub after merge. Provide a PAT with{' '}
          <code className='text-secondary'>repo</code> scope and your username or org name.
        </p>
        <form onSubmit={handleSaveGitHub} className='space-y-3'>
          <SecretField
            label='Personal Access Token'
            subLabel={user?.hasGithubPat ? '(leave blank to keep existing)' : undefined}
            value={githubPat}
            onChange={setGithubPat}
            placeholder={
              user?.hasGithubPat ? '••••••••••••••••••••• (saved)' : 'ghp_xxxxxxxxxxxxxxxxxxxx'
            }
          />
          <TextField
            label='GitHub Owner'
            value={githubOwner}
            onChange={setGithubOwner}
            placeholder='github_owner'
            hint='Your GitHub username or org name.'
          />
          <SaveMessage msg={githubMsg} />
          <div className='flex flex-wrap gap-3 pt-1'>
            <button
              type='submit'
              disabled={githubSaving || (!githubPat.trim() && !githubOwner.trim())}
              className='w-full sm:w-auto px-4 py-2 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 disabled:opacity-50 transition-colors'
            >
              {githubSaving ? 'Saving…' : 'Save'}
            </button>
            <button
              type='button'
              onClick={handleTestGitHub}
              disabled={githubTesting || !githubPat.trim()}
              className='w-full sm:w-auto flex items-center justify-center gap-1.5 px-4 py-2 rounded-sm border border-[var(--border-default)] text-secondary text-sm hover:border-accent-blue/40 hover:text-primary disabled:opacity-40 transition-colors'
            >
              {githubTesting ? <Loader2 className='h-3.5 w-3.5 animate-spin' /> : null}
              Test Connection
            </button>
            {user?.hasGithubPat && (
              <button
                type='button'
                onClick={handleClearGitHub}
                disabled={githubSaving}
                className='w-full sm:w-auto px-4 py-2 rounded-sm text-secondary hover:text-accent-red text-sm transition-colors disabled:opacity-50'
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
          id='api-keys'
          icon={<Key className='h-4 w-4 text-secondary' />}
          title='AI Provider Keys'
          statusBadge={
            hasAnyAiKey ? (
              <ConfiguredBadge
                label={`${[user?.hasOpenAiKey && 'OpenAI', user?.hasAnthropicKey && 'Anthropic', user?.hasGeminiKey && 'Gemini'].filter(Boolean).join(', ')} configured`}
              />
            ) : (
              <NotConfiguredBadge label='No keys saved' />
            )
          }
          defaultOpen={!hasAnyAiKey}
        >
          <p className='text-xs text-muted'>
            Per-user API keys for AI providers. Leave a field blank to keep an existing key
            unchanged. Save with all fields empty to clear all keys.
          </p>
          <form onSubmit={handleSaveAiKeys} className='space-y-3'>
            <div className='space-y-1'>
              <SecretField
                label={`OpenAI API Key${user?.hasOpenAiKey ? ' ✓' : ''}`}
                subLabel={user?.hasOpenAiKey ? '(leave blank to keep existing)' : undefined}
                value={openAiKey}
                onChange={setOpenAiKey}
                placeholder={user?.hasOpenAiKey ? '••••••••••••••••••••• (saved)' : 'sk-…'}
              />
              {/* Phase 1.2 #24 — BYOK Test buttons. Pings the provider before save. */}
              <AiKeyTestRow
                provider='OPENAI'
                hasKey={!!user?.hasOpenAiKey}
                keyValue={openAiKey}
                state={aiTestState.OPENAI}
                onTest={handleTestAiKey}
              />
            </div>
            <div className='space-y-1'>
              <SecretField
                label={`Anthropic API Key${user?.hasAnthropicKey ? ' ✓' : ''}`}
                subLabel={user?.hasAnthropicKey ? '(leave blank to keep existing)' : undefined}
                value={anthropicKey}
                onChange={setAnthropicKey}
                placeholder={user?.hasAnthropicKey ? '••••••••••••••••••••• (saved)' : 'sk-ant-…'}
              />
              <AiKeyTestRow
                provider='ANTHROPIC'
                hasKey={!!user?.hasAnthropicKey}
                keyValue={anthropicKey}
                state={aiTestState.ANTHROPIC}
                onTest={handleTestAiKey}
              />
            </div>
            <div className='space-y-1'>
              <SecretField
                label={`Gemini API Key${user?.hasGeminiKey ? ' ✓' : ''}`}
                subLabel={user?.hasGeminiKey ? '(leave blank to keep existing)' : undefined}
                value={geminiKey}
                onChange={setGeminiKey}
                placeholder={user?.hasGeminiKey ? '••••••••••••••••••••• (saved)' : 'AIza…'}
              />
              <AiKeyTestRow
                provider='GEMINI'
                hasKey={!!user?.hasGeminiKey}
                keyValue={geminiKey}
                state={aiTestState.GEMINI}
                onTest={handleTestAiKey}
              />
            </div>
            <SaveMessage msg={aiMsg} />
            <SaveBar
              saving={aiSaving}
              disabled={!openAiKey.trim() && !anthropicKey.trim() && !geminiKey.trim()}
              onClear={hasAnyAiKey ? handleClearAiKeys : undefined}
              clearLabel='Clear all keys'
            />
          </form>
        </IntegrationCard>
      )}

      {/* ── JIRA/Confluence note ──────────────────────────────────────────── */}
      {isOrgAdmin && (
        <GlassPanel className='px-5 py-3'>
          <p className='text-xs text-muted'>
            JIRA and Confluence integrations are now managed at the org level. Go to{' '}
            <a
              href='/integrations'
              className='text-accent-blue hover:text-accent-blue/80 underline'
            >
              Integrations
            </a>{' '}
            to configure them.
          </p>
        </GlassPanel>
      )}

      {/* ── Organisation AI Keys (ORG_ADMIN only) ──────────────────────────── */}
      {isOrgAdmin && <OrgAiKeyCard org={org} onSaved={setOrg} />}

      {/* ── Key Source & Provider Policy (ORG_ADMIN only) ──────────────────── */}
      {isOrgAdmin && org && <OrgKeySourceCard org={org} onSaved={setOrg} />}

      {/* ── Notification preferences (M-003 Wave 2) ────────────────────────── */}
      <NotificationPreferences />

      {/* ── Privacy (X-09, UX audit, 2026-05-03) ───────────────────────────── */}
      <PrivacySettings />

      {/* ── System Health (requires canViewMetrics) ────────────────────────── */}
      {permissions?.canViewMetrics && (
        <div>
          <h3 className='text-xs font-medium text-muted uppercase tracking-wider mb-3'>
            System Health
          </h3>
          <div className='grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3'>
            <HealthCard
              icon={<Server className='h-5 w-5 text-accent-blue' />}
              label='API'
              value='Healthy'
              color='text-accent-green'
            />
            <HealthCard
              icon={<Database className='h-5 w-5 text-secondary' />}
              label='Database'
              value='Connected'
              color='text-accent-green'
            />
            <HealthCard
              icon={<Cpu className='h-5 w-5 text-accent-amber' />}
              label='Total Runs'
              value={String(health?.totalRuns ?? '—')}
              color='text-primary'
            />
            <HealthCard
              icon={<Clock className='h-5 w-5 text-accent-green' />}
              label='Active Runs'
              value={String(health?.activeRuns ?? '—')}
              color='text-primary'
            />
          </div>
        </div>
      )}
    </PageShell>
  );
}

// ── Org-level AI key card (ORG_ADMIN only) ───────────────────────────────────

function OrgAiKeyCard({org, onSaved}: {org: OrgDto | null; onSaved: (o: OrgDto) => void}) {
  const [orgAnthropicKey, setOrgAnthropicKey] = useState('');
  const [orgOpenAiKey, setOrgOpenAiKey] = useState('');
  const [orgGeminiKey, setOrgGeminiKey] = useState('');
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState<{ok: boolean; text: string} | null>(null);

  const hasAnyOrgKey = org?.hasAnthropicKey || org?.hasOpenAiKey || org?.hasGeminiKey;

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setMsg(null);
    try {
      const updated = await api.updateOrgAiKeySettings(orgAnthropicKey, orgOpenAiKey, orgGeminiKey);
      onSaved(updated);
      setOrgAnthropicKey('');
      setOrgOpenAiKey('');
      setOrgGeminiKey('');
      setMsg({ok: true, text: 'Organisation AI keys saved.'});
    } catch (err: any) {
      setMsg({ok: false, text: err.message ?? 'Failed to save.'});
    } finally {
      setSaving(false);
    }
  };

  const handleClear = async () => {
    if (
      !confirm(
        'Remove all organisation AI keys? Members without personal keys will fall back to system keys.',
      )
    )
      return;
    setSaving(true);
    setMsg(null);
    try {
      const updated = await api.updateOrgAiKeySettings('', '', '');
      onSaved(updated);
      setMsg({ok: true, text: 'Organisation AI keys cleared.'});
    } catch (err: any) {
      setMsg({ok: false, text: err.message ?? 'Failed to clear.'});
    } finally {
      setSaving(false);
    }
  };

  return (
    <IntegrationCard
      icon={<Building2 className='h-4 w-4 text-secondary' />}
      title='Organisation AI Keys'
      statusBadge={
        hasAnyOrgKey ? (
          <ConfiguredBadge
            label={`Org keys: ${[org?.hasAnthropicKey && 'Anthropic', org?.hasOpenAiKey && 'OpenAI', org?.hasGeminiKey && 'Gemini'].filter(Boolean).join(', ')}`}
          />
        ) : (
          <NotConfiguredBadge label='No org keys' />
        )
      }
    >
      <p className='text-xs text-muted'>
        Org-wide API keys are used by all team members who haven't set personal keys.
        <br />
        Resolution order:{' '}
        <span className='text-secondary'>Personal key → Org key → System key</span>
      </p>
      <form onSubmit={handleSave} className='space-y-3'>
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
          clearLabel='Clear org keys'
        />
      </form>
    </IntegrationCard>
  );
}

// ── Key source & provider policy card (ORG_ADMIN only) ──────────────────────

const KEY_SOURCE_MODES = [
  {value: 'SYSTEM', label: 'Platform Keys', desc: 'Use executespec platform keys for all runs.'},
  {
    value: 'OWN_KEYS',
    label: 'Own Keys Only',
    desc: 'Use only org/personal keys. Runs fail if no key is set.',
  },
  {
    value: 'HYBRID',
    label: 'Hybrid (Default)',
    desc: 'Use own keys when available, fall back to platform.',
  },
] as const;

const PROVIDERS = [
  {id: 'anthropic', label: 'Anthropic'},
  {id: 'openai', label: 'OpenAI'},
  {id: 'google', label: 'Gemini'},
] as const;

function OrgKeySourceCard({org, onSaved}: {org: OrgDto; onSaved: (o: OrgDto) => void}) {
  const [mode, setMode] = useState(org.keySourceMode ?? 'HYBRID');
  const [providers, setProviders] = useState<Set<string>>(() => {
    if (!org.enabledProviders) return new Set(PROVIDERS.map((p) => p.id));
    return new Set(org.enabledProviders);
  });
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState<{ok: boolean; text: string} | null>(null);

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
    setSaving(true);
    setMsg(null);
    try {
      const enabledList = providers.size === PROVIDERS.length ? null : Array.from(providers);
      const updated = await api.updateOrgKeySourceSettings(mode, enabledList);
      onSaved(updated);
      setMsg({ok: true, text: 'Key source settings saved.'});
    } catch (err: any) {
      setMsg({ok: false, text: err.message ?? 'Failed to save.'});
    } finally {
      setSaving(false);
    }
  };

  const missingKeys =
    mode === 'OWN_KEYS' &&
    !(
      (providers.has('anthropic') && org.hasAnthropicKey) ||
      (providers.has('openai') && org.hasOpenAiKey) ||
      (providers.has('google') && org.hasGeminiKey)
    );

  return (
    <IntegrationCard
      icon={<Shield className='h-4 w-4 text-secondary' />}
      title='Key Source & Provider Policy'
      statusBadge={
        <span className='text-xs text-muted'>
          {mode === 'SYSTEM' ? 'Platform' : mode === 'OWN_KEYS' ? 'Own Keys' : 'Hybrid'}
        </span>
      }
    >
      {/* Key Source Mode */}
      <div className='space-y-2'>
        <label className='text-xs text-secondary block'>Key Source Mode</label>
        {KEY_SOURCE_MODES.map((m) => (
          <label key={m.value} className='flex items-start gap-2.5 cursor-pointer group'>
            <input
              type='radio'
              name='keySourceMode'
              value={m.value}
              checked={mode === m.value}
              onChange={() => setMode(m.value)}
              className='mt-0.5 accent-accent-blue'
            />
            <div>
              <span className='text-sm text-primary group-hover:text-primary'>{m.label}</span>
              <p className='text-xs text-muted'>{m.desc}</p>
            </div>
          </label>
        ))}
      </div>

      {/* Provider Restrictions */}
      <div className='space-y-2 pt-2 border-t border-[var(--border-default)]'>
        <label className='text-xs text-secondary block'>Enabled Providers</label>
        <div className='flex flex-wrap gap-3'>
          {PROVIDERS.map((p) => (
            <label key={p.id} className='flex items-center gap-2 cursor-pointer'>
              <input
                type='checkbox'
                checked={providers.has(p.id)}
                onChange={() => toggleProvider(p.id)}
                className='accent-accent-blue'
              />
              <span className='text-sm text-secondary'>{p.label}</span>
            </label>
          ))}
        </div>
        <p className='text-xs text-muted'>
          Disabled providers are excluded from fallback chains for all org runs.
        </p>
      </div>

      {/* Warning */}
      {missingKeys && (
        <div className='rounded-sm border border-status-waiting/30 bg-status-waiting/5 px-3 py-2'>
          <p className='text-xs text-accent-amber'>
            <AlertCircle className='h-3.5 w-3.5 inline mr-1 -mt-0.5' />
            OWN_KEYS mode is selected but no org/user keys are configured for the enabled providers.
            Runs will fail at submission.
          </p>
        </div>
      )}

      <SaveMessage msg={msg} />
      <div className='flex gap-3 pt-1'>
        <button
          onClick={handleSave}
          disabled={saving}
          className='w-full sm:w-auto px-4 py-2 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 disabled:opacity-50 transition-colors'
        >
          {saving ? 'Saving...' : 'Save'}
        </button>
      </div>
    </IntegrationCard>
  );
}

/**
 * X-09 (UX audit, 2026-05-03) — privacy section with anonymous-analytics
 * opt-out. Pre-fix the audit found PostHog calls were gated only on
 * VITE_POSTHOG_API_KEY, with no in-product affordance for users to opt
 * out — a procurement-blocking gap for GDPR / enterprise privacy reviews.
 * The toggle persists to localStorage; on flip we either disable
 * subsequent calls (handled by isAnalyticsOptedOut() inside analytics.ts)
 * or proactively call posthog.opt_out_capturing() if PostHog had already
 * loaded for this session.
 */
function PrivacySettings() {
  const [optedOut, setOptedOut] = useState<boolean>(() => isAnalyticsOptedOut());
  const [savedAt, setSavedAt] = useState<number | null>(null);

  const onToggle = (next: boolean) => {
    setOptedOut(next);
    setAnalyticsOptedOut(next);
    setSavedAt(Date.now());
  };

  return (
    <div>
      <h3 className='text-xs font-medium text-muted uppercase tracking-wider mb-3'>Privacy</h3>
      <GlassPanel className='p-4 space-y-3'>
        <div className='flex items-start justify-between gap-4'>
          <div className='min-w-0'>
            <p className='text-sm font-medium text-primary'>Send anonymous usage analytics</p>
            <p className='text-xs text-muted mt-1 leading-relaxed'>
              We use PostHog to count pageviews, sample funnel events (project created, run
              completed), and identify the tier you're on. No spec content, code, or repo names
              leave your browser. Opt out at any time — we respect the choice immediately and drop
              the local PostHog identifier.
            </p>
          </div>
          <label className='inline-flex items-center gap-2 flex-shrink-0 cursor-pointer'>
            <input
              type='checkbox'
              checked={!optedOut}
              onChange={(e) => onToggle(!e.target.checked)}
              className='h-4 w-4 rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] accent-accent-blue'
              aria-label='Send anonymous usage analytics'
            />
            <span className='text-xs text-secondary'>{optedOut ? 'Off' : 'On'}</span>
          </label>
        </div>
        {savedAt && (
          <p className='text-[11px] text-status-success'>
            {optedOut ? 'Analytics disabled.' : 'Analytics re-enabled.'} Saved.
          </p>
        )}
      </GlassPanel>
    </div>
  );
}

function HealthCard({
  icon,
  label,
  value,
  color,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  color: string;
}) {
  return (
    <GlassPanel className='p-4 flex items-center gap-3'>
      {icon}
      <div>
        <p className='text-xs text-muted'>{label}</p>
        <p className={`text-sm font-semibold ${color}`}>{value}</p>
      </div>
    </GlassPanel>
  );
}

// Phase 1.2 #24 — Test button + result chip for a single BYOK provider.
function AiKeyTestRow({
  provider,
  hasKey,
  keyValue,
  state,
  onTest,
}: {
  provider: 'OPENAI' | 'ANTHROPIC' | 'GEMINI';
  hasKey: boolean;
  keyValue: string;
  state: {busy: boolean; result?: {ok: boolean; text: string}};
  onTest: (provider: 'OPENAI' | 'ANTHROPIC' | 'GEMINI', key: string) => void;
}) {
  const canTest = keyValue.trim().length > 0;
  const reason =
    !canTest && hasKey
      ? 'Paste the key again to re-test (we never store it in plaintext).'
      : !canTest
        ? 'Paste a key first.'
        : '';
  return (
    <div className='flex items-center gap-2 text-[11px]'>
      <button
        type='button'
        onClick={() => onTest(provider, keyValue)}
        disabled={!canTest || state.busy}
        title={reason}
        className='px-2.5 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary hover:border-[var(--border-hover)] disabled:opacity-50 disabled:cursor-not-allowed'
      >
        {state.busy ? 'Testing…' : 'Test key'}
      </button>
      {state.result && (
        <span className={state.result.ok ? 'text-status-success' : 'text-status-error'}>
          {state.result.ok ? '✓' : '✗'} {state.result.text}
        </span>
      )}
      {!state.result && reason && <span className='text-muted italic'>{reason}</span>}
    </div>
  );
}
