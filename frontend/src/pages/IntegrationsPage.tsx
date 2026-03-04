import { useEffect, useState } from 'react';
import { Send } from 'lucide-react';
import IntegrationCard from '@/components/integrations/IntegrationCard';
import GlassPanel from '@/components/shared/GlassPanel';
import { getIntegrationStatus, triggerConfluence, getIntegrationRuns } from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';
import { relativeTime } from '@/lib/utils';
import type { RunSummary } from '@/lib/api';

const INTEGRATIONS = [
  { key: 'JIRA',        name: 'Jira',         description: 'Issue-triggered runs',    logo: '🎯' },
  { key: 'CONFLUENCE',  name: 'Confluence',    description: 'Page-triggered runs',     logo: '📚' },
  { key: 'SERVICENOW',  name: 'ServiceNow',    description: 'Incident response runs',  logo: '⚙️' },
  { key: 'PAGERDUTY',   name: 'PagerDuty',     description: 'Alert-triggered runs',    logo: '🚨' },
];

export default function IntegrationsPage() {
  const [enabledSources, setEnabledSources] = useState<string[]>([]);
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [pageId, setPageId] = useState('');
  const [goal, setGoal] = useState('');
  const [triggering, setTriggering] = useState(false);
  const { addToast } = useUiStore();

  useEffect(() => {
    getIntegrationStatus().then((r) => setEnabledSources(r.enabledSources)).catch(() => {});
    getIntegrationRuns().then(setRuns).catch(() => {});
  }, []);

  const handleTrigger = async () => {
    if (!pageId.trim() || !goal.trim()) return;
    setTriggering(true);
    try {
      const { runId } = await triggerConfluence({ pageId, goal });
      addToast({ type: 'success', message: `Run launched: ${runId.slice(0, 8)}` });
      setPageId('');
      setGoal('');
      const fresh = await getIntegrationRuns();
      setRuns(fresh);
    } catch (e: any) {
      addToast({ type: 'error', message: e.message });
    }
    setTriggering(false);
  };

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      <div>
        <h1 className="text-lg font-semibold text-white">Integrations</h1>
        <p className="text-xs text-zinc-500 mt-0.5">External tools that can trigger agent runs</p>
      </div>

      {/* Integration grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {INTEGRATIONS.map((int) => (
          <IntegrationCard
            key={int.key}
            name={int.name}
            description={int.description}
            logo={int.logo}
            enabled={enabledSources.includes(int.key)}
          />
        ))}
      </div>

      {/* Confluence manual trigger */}
      {enabledSources.includes('CONFLUENCE') && (
        <GlassPanel className="p-4">
          <h3 className="text-sm font-semibold text-zinc-200 mb-3">Manual Confluence Trigger</h3>
          <div className="flex flex-col sm:flex-row gap-3">
            <input
              value={pageId}
              onChange={(e) => setPageId(e.target.value)}
              placeholder="Page ID"
              className="flex-1 bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-zinc-200 focus:outline-none"
            />
            <input
              value={goal}
              onChange={(e) => setGoal(e.target.value)}
              placeholder="Goal / instructions"
              className="flex-1 bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-zinc-200 focus:outline-none"
            />
            <button
              onClick={handleTrigger}
              disabled={!pageId.trim() || !goal.trim() || triggering}
              className="flex items-center gap-2 px-4 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 disabled:opacity-40"
            >
              {triggering ? <span className="h-4 w-4 rounded-full border-2 border-white/30 border-t-white animate-spin" /> : <Send className="h-4 w-4" />}
              Trigger
            </button>
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
