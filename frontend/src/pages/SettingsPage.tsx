import { useEffect, useState } from 'react';
import GlassPanel from '@/components/shared/GlassPanel';
import { Server, Database, Cpu, Clock } from 'lucide-react';

export default function SettingsPage() {
  const [health, setHealth] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    fetch('/api/metrics')
      .then((r) => r.json())
      .then((d) => setHealth(d))
      .catch(() => {});
  }, []);

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      <div>
        <h1 className="text-lg font-semibold text-white">Settings</h1>
        <p className="text-xs text-zinc-500 mt-0.5">System health and configuration</p>
      </div>

      {/* Health cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <HealthCard icon={<Server className="h-5 w-5 text-accent-violet" />} label="API" value="Healthy" color="text-accent-emerald" />
        <HealthCard icon={<Database className="h-5 w-5 text-accent-cyan" />} label="Database" value="Connected" color="text-accent-emerald" />
        <HealthCard icon={<Cpu className="h-5 w-5 text-accent-amber" />} label="Total Runs" value={String(health?.totalRuns ?? '—')} color="text-zinc-200" />
        <HealthCard icon={<Clock className="h-5 w-5 text-accent-emerald" />} label="Active Runs" value={String(health?.activeRuns ?? '—')} color="text-zinc-200" />
      </div>

      {/* About */}
      <GlassPanel className="p-4 space-y-3">
        <h3 className="text-sm font-semibold text-zinc-200">About ExecuteSpec</h3>
        <div className="space-y-2 text-sm text-zinc-400">
          <p>A multi-agent AI coding system — React + Spring Boot 3 + Spring AI 1.0</p>
          <p>Tier 7 complete: Planner → Approval → Workers → Reviewer → Merge → Deploy → Integrations</p>
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
