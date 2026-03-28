import { useEffect, useState } from 'react';
import { Rocket, Lock, Sparkles, Cloud, Server, Globe } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import DeploymentCard from '@/components/deployments/DeploymentCard';
import DeployWizardModal from '@/components/deployments/DeployWizardModal';
import { getDeployments } from '@/lib/api';
import type { DeploymentSummary } from '@/lib/api';
import { Skeleton } from '@/components/shared/SkeletonLoader';
import EmptyState from '@/components/shared/EmptyState';
import { useTierPermissions } from '@/hooks/useTierPermissions';

export default function DeploymentsPage() {
  const [deployments, setDeployments] = useState<DeploymentSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [showWizard, setShowWizard] = useState(false);
  const permissions = useTierPermissions();
  const navigate = useNavigate();

  const canDeploy = permissions?.canDeploy;

  const load = async () => {
    if (!canDeploy) return;
    setLoading(true);
    try { setDeployments(await getDeployments()); } catch { setDeployments([]); } finally { setLoading(false); }
  };

  useEffect(() => { if (canDeploy) { load(); const i = setInterval(load, 15000); return () => clearInterval(i); } }, [canDeploy]);

  // Upgrade gate for FREE users
  if (permissions && !canDeploy) {
    return (
      <div className="h-full flex items-center justify-center p-4">
        <div className="text-center space-y-6 max-w-md">
          <div className="relative mx-auto w-fit">
            <div className="h-16 w-16 rounded-sm bg-gradient-to-br from-accent-violet/20 to-accent-cyan/20 border border-white/[0.08] flex items-center justify-center">
              <Rocket className="h-8 w-8 text-accent-violet" />
            </div>
            <div className="absolute -top-1 -right-1 h-5 w-5 rounded-full bg-accent-amber flex items-center justify-center">
              <Sparkles className="h-3 w-3 text-white" />
            </div>
          </div>
          <div>
            <h2 className="text-base font-semibold text-white">Deploy Engine</h2>
            <p className="text-sm text-zinc-400 mt-2 leading-relaxed">
              Deploy your code directly to GCP Cloud Run with one click.
              Available on <span className="text-accent-violet font-medium">PRO</span> and <span className="text-accent-cyan font-medium">Enterprise</span> plans.
            </p>
          </div>
          <div className="grid grid-cols-3 gap-3 text-center">
            <div className="p-3 rounded-sm bg-surface-1 border border-white/[0.06]">
              <Cloud className="h-4 w-4 text-accent-violet mx-auto mb-1" />
              <p className="text-[10px] text-zinc-400">Cloud Run</p>
            </div>
            <div className="p-3 rounded-sm bg-surface-1 border border-white/[0.06]">
              <Server className="h-4 w-4 text-accent-cyan mx-auto mb-1" />
              <p className="text-[10px] text-zinc-400">Auto-scaling</p>
            </div>
            <div className="p-3 rounded-sm bg-surface-1 border border-white/[0.06]">
              <Globe className="h-4 w-4 text-accent-emerald mx-auto mb-1" />
              <p className="text-[10px] text-zinc-400">Your GCP Project</p>
            </div>
          </div>
          <button
            onClick={() => navigate('/billing')}
            className="px-6 py-2.5 rounded-sm bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 transition-colors"
          >
            Upgrade to PRO
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-semibold text-white">Deployments</h1>
          <p className="text-xs text-zinc-500 mt-0.5">Deploy completed runs to your GCP Cloud Run</p>
        </div>
        <button
          onClick={() => setShowWizard(true)}
          className="flex items-center gap-2 px-3 py-2 rounded-sm bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 transition-colors"
        >
          <Rocket className="h-4 w-4" /> Deploy Run
        </button>
      </div>

      {loading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-36" />)}
        </div>
      ) : deployments.length === 0 ? (
        <EmptyState
          title="No deployments yet"
          description="Deploy a completed run to your GCP Cloud Run project"
          action={
            <button onClick={() => setShowWizard(true)}
              className="px-4 py-2 rounded-sm bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80">
              Deploy Run
            </button>
          }
        />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {deployments.map((d) => (
            <DeploymentCard key={d.id} deployment={d} onRefresh={load} />
          ))}
        </div>
      )}

      {showWizard && <DeployWizardModal onClose={() => setShowWizard(false)} onRefresh={load} />}
    </div>
  );
}
