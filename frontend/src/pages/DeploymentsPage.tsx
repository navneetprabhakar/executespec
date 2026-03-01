import { useEffect, useState } from 'react';
import { Plus } from 'lucide-react';
import DeploymentCard from '@/components/deployments/DeploymentCard';
import DeployModal from '@/components/deployments/DeployModal';
import { getDeployments } from '@/lib/api';
import type { DeploymentSummary } from '@/lib/api';
import { Skeleton } from '@/components/shared/SkeletonLoader';
import EmptyState from '@/components/shared/EmptyState';

export default function DeploymentsPage() {
  const [deployments, setDeployments] = useState<DeploymentSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);

  const load = async () => {
    setLoading(true);
    try { setDeployments(await getDeployments()); } finally { setLoading(false); }
  };

  useEffect(() => { load(); const i = setInterval(load, 15000); return () => clearInterval(i); }, []);

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-semibold text-white">Deployments</h1>
          <p className="text-xs text-zinc-500 mt-0.5">GCP Cloud Run deployments from your runs</p>
        </div>
        <button
          onClick={() => setShowModal(true)}
          className="flex items-center gap-2 px-3 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 transition-colors"
        >
          <Plus className="h-4 w-4" /> Deploy Run
        </button>
      </div>

      {loading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-36" />)}
        </div>
      ) : deployments.length === 0 ? (
        <EmptyState
          title="No deployments yet"
          description="Deploy a completed run to GCP Cloud Run"
          action={
            <button onClick={() => setShowModal(true)}
              className="px-4 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80">
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

      {showModal && <DeployModal onClose={() => setShowModal(false)} onRefresh={load} />}
    </div>
  );
}
