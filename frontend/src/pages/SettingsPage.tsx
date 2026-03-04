import { useEffect, useState } from 'react';
import { Eye, EyeOff, Github, CheckCircle, AlertCircle, Server, Database, Cpu, Clock } from 'lucide-react';
import GlassPanel from '@/components/shared/GlassPanel';
import { useAuthStore } from '@/stores/useAuthStore';
import * as api from '@/lib/api';

export default function SettingsPage() {
  const { user, setUser } = useAuthStore();
  const [health, setHealth] = useState<Record<string, unknown> | null>(null);

  // GitHub settings form
  const [githubPat,   setGithubPat]   = useState('');
  const [githubOwner, setGithubOwner] = useState(user?.githubOwner ?? '');
  const [showPat,     setShowPat]     = useState(false);
  const [saving,      setSaving]      = useState(false);
  const [saveMsg,     setSaveMsg]     = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    fetch('/api/metrics').then((r) => r.json()).then(setHealth).catch(() => {});
  }, []);

  // Keep owner field in sync when user loads
  useEffect(() => {
    setGithubOwner(user?.githubOwner ?? '');
  }, [user?.githubOwner]);

  const handleSaveGitHub = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setSaveMsg(null);
    try {
      const updated = await api.updateGithubSettings(githubPat, githubOwner);
      setUser(updated);
      setGithubPat('');   // clear PAT field after save (don't show it)
      setSaveMsg({ ok: true, text: 'GitHub settings saved.' });
    } catch (err: any) {
      setSaveMsg({ ok: false, text: err.message ?? 'Failed to save GitHub settings.' });
    } finally {
      setSaving(false);
    }
  };

  const handleClearGitHub = async () => {
    if (!confirm('Remove your GitHub PAT and owner? GitHub push will be disabled.')) return;
    setSaving(true);
    setSaveMsg(null);
    try {
      const updated = await api.updateGithubSettings('', '');
      setUser(updated);
      setGithubPat('');
      setGithubOwner('');
      setSaveMsg({ ok: true, text: 'GitHub credentials cleared.' });
    } catch (err: any) {
      setSaveMsg({ ok: false, text: err.message ?? 'Failed to clear GitHub settings.' });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="h-full overflow-y-auto p-4 space-y-6">
      <div>
        <h1 className="text-lg font-semibold text-white">Settings</h1>
        <p className="text-xs text-zinc-500 mt-0.5">Account configuration and system health</p>
      </div>

      {/* ── GitHub Integration ───────────────────────────────────────────────── */}
      <GlassPanel className="p-5 space-y-4">
        <div className="flex items-center gap-2">
          <Github className="h-4 w-4 text-zinc-400" />
          <h3 className="text-sm font-semibold text-zinc-200">GitHub Integration</h3>
          {user?.hasGithubPat ? (
            <span className="flex items-center gap-1 text-xs text-accent-emerald ml-auto">
              <CheckCircle className="h-3.5 w-3.5" /> PAT configured
            </span>
          ) : (
            <span className="flex items-center gap-1 text-xs text-accent-amber ml-auto">
              <AlertCircle className="h-3.5 w-3.5" /> No PAT — GitHub push disabled
            </span>
          )}
        </div>
        <p className="text-xs text-zinc-500">
          Runs will auto-push code to your GitHub after merge. Provide a Personal Access Token
          with <code className="text-zinc-400">repo</code> scope and your GitHub username or org name.
        </p>

        <form onSubmit={handleSaveGitHub} className="space-y-3">
          {/* PAT field */}
          <div>
            <label className="text-xs text-zinc-400 mb-1 block">
              Personal Access Token {user?.hasGithubPat && <span className="text-zinc-600">(leave blank to keep existing)</span>}
            </label>
            <div className="relative">
              <input
                type={showPat ? 'text' : 'password'}
                value={githubPat}
                onChange={(e) => setGithubPat(e.target.value)}
                placeholder={user?.hasGithubPat ? '••••••••••••••••••••• (saved)' : 'ghp_xxxxxxxxxxxxxxxxxxxx'}
                className="w-full rounded-lg border border-white/[0.08] bg-zinc-900 px-3 py-2 text-sm text-white placeholder-zinc-600 focus:border-accent-violet focus:outline-none pr-10"
              />
              <button
                type="button"
                onClick={() => setShowPat((v) => !v)}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-zinc-600 hover:text-zinc-300"
              >
                {showPat ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>

          {/* Owner field */}
          <div>
            <label className="text-xs text-zinc-400 mb-1 block">
              GitHub Owner <span className="text-zinc-600">(your username or org name)</span>
            </label>
            <input
              type="text"
              value={githubOwner}
              onChange={(e) => setGithubOwner(e.target.value)}
              placeholder="navneetprabhakar"
              className="w-full rounded-lg border border-white/[0.08] bg-zinc-900 px-3 py-2 text-sm text-white placeholder-zinc-600 focus:border-accent-violet focus:outline-none"
            />
            <p className="text-xs text-zinc-600 mt-1">
              If this is a GitHub organization, repos will be created under that org.
            </p>
          </div>

          {saveMsg && (
            <p className={`text-xs ${saveMsg.ok ? 'text-accent-emerald' : 'text-red-400'}`}>
              {saveMsg.text}
            </p>
          )}

          <div className="flex gap-3">
            <button
              type="submit"
              disabled={saving || (!githubPat.trim() && !githubOwner.trim())}
              className="px-4 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 disabled:opacity-50 transition-colors"
            >
              {saving ? 'Saving…' : 'Save GitHub Settings'}
            </button>
            {user?.hasGithubPat && (
              <button
                type="button"
                onClick={handleClearGitHub}
                disabled={saving}
                className="px-4 py-2 rounded-lg text-zinc-400 hover:text-red-400 text-sm transition-colors disabled:opacity-50"
              >
                Clear credentials
              </button>
            )}
          </div>
        </form>
      </GlassPanel>

      {/* ── System Health ────────────────────────────────────────────────────── */}
      <div>
        <h3 className="text-xs font-medium text-zinc-500 uppercase tracking-wider mb-3">System Health</h3>
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
          <HealthCard icon={<Server className="h-5 w-5 text-accent-violet" />} label="API" value="Healthy" color="text-accent-emerald" />
          <HealthCard icon={<Database className="h-5 w-5 text-accent-cyan" />} label="Database" value="Connected" color="text-accent-emerald" />
          <HealthCard icon={<Cpu className="h-5 w-5 text-accent-amber" />} label="Total Runs" value={String(health?.totalRuns ?? '—')} color="text-zinc-200" />
          <HealthCard icon={<Clock className="h-5 w-5 text-accent-emerald" />} label="Active Runs" value={String(health?.activeRuns ?? '—')} color="text-zinc-200" />
        </div>
      </div>

      {/* ── About ────────────────────────────────────────────────────────────── */}
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
