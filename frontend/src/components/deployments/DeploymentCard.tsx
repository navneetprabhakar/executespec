import { useState } from 'react';
import { ExternalLink, RotateCcw, Trash2, Loader2, Globe } from 'lucide-react';
import GlassPanel from '@/components/shared/GlassPanel';
import { useUiStore } from '@/stores/useUiStore';
import { deleteDeployment, engineRollback, engineDestroy } from '@/lib/api';
import { cn, relativeTime } from '@/lib/utils';
import type { DeploymentSummary } from '@/lib/api';

const STATUS_STYLES: Record<string, { border: string; badge: string; pulse: boolean }> = {
  PENDING:   { border: 'border-zinc-500/30',       badge: 'text-zinc-400 bg-zinc-500/10',        pulse: false },
  BUILDING:  { border: 'border-accent-amber/30',   badge: 'text-accent-amber bg-accent-amber/10', pulse: true },
  PUSHING:   { border: 'border-accent-cyan/30',    badge: 'text-accent-cyan bg-accent-cyan/10',   pulse: true },
  DEPLOYING: { border: 'border-accent-violet/30',  badge: 'text-accent-violet bg-accent-violet/10', pulse: true },
  LIVE:      { border: 'border-accent-emerald/40', badge: 'text-accent-emerald bg-accent-emerald/10', pulse: true },
  FAILED:    { border: 'border-accent-rose/30',    badge: 'text-accent-rose bg-accent-rose/10',   pulse: false },
  DELETED:   { border: 'border-white/[0.06]',      badge: 'text-zinc-600 bg-zinc-600/10',         pulse: false },
  DESTROYED: { border: 'border-white/[0.06]',      badge: 'text-zinc-600 bg-zinc-600/10',         pulse: false },
};

const PULSE_COLOR: Record<string, string> = {
  LIVE: 'bg-accent-emerald',
  BUILDING: 'bg-accent-amber',
  PUSHING: 'bg-accent-cyan',
  DEPLOYING: 'bg-accent-violet',
};

interface DeploymentCardProps {
  deployment: DeploymentSummary;
  onRefresh: () => void;
}

export default function DeploymentCard({ deployment, onRefresh }: DeploymentCardProps) {
  const { addToast } = useUiStore();
  const s = STATUS_STYLES[deployment.status] ?? STATUS_STYLES.FAILED;
  const [loading, setLoading] = useState('');

  const isActive = ['BUILDING', 'PUSHING', 'DEPLOYING', 'PENDING'].includes(deployment.status);

  const handleDelete = async () => {
    setLoading('delete');
    try {
      if (deployment.status === 'LIVE') {
        await engineDestroy(deployment.id);
      } else {
        await deleteDeployment(deployment.id);
      }
      onRefresh();
      addToast({ type: 'success', message: 'Deployment deleted' });
    } catch (e: any) {
      addToast({ type: 'error', message: e.message });
    } finally {
      setLoading('');
    }
  };

  const handleRollback = async () => {
    setLoading('rollback');
    try {
      await engineRollback(deployment.id);
      onRefresh();
      addToast({ type: 'success', message: 'Rollback initiated' });
    } catch (e: any) {
      addToast({ type: 'error', message: e.message });
    } finally {
      setLoading('');
    }
  };

  return (
    <GlassPanel className={cn('p-4 border', s.border)}>
      <div className="flex items-start justify-between mb-3">
        <div>
          <div className="flex items-center gap-2">
            {s.pulse && (
              <span className="relative flex h-2 w-2">
                <span className={cn('animate-ping absolute inline-flex h-full w-full rounded-full opacity-75',
                  PULSE_COLOR[deployment.status] || 'bg-accent-cyan')} />
                <span className={cn('relative inline-flex rounded-full h-2 w-2',
                  PULSE_COLOR[deployment.status] || 'bg-accent-cyan')} />
              </span>
            )}
            <span className={cn('text-xs px-2 py-0.5 rounded-sm font-medium', s.badge)}>
              {deployment.status}
            </span>
            {deployment.versionTag && (
              <span className="text-[10px] font-mono text-zinc-600">{deployment.versionTag}</span>
            )}
          </div>
          <p className="font-mono text-xs text-zinc-500 mt-1">Run: {deployment.runId?.slice(0, 12)}</p>
        </div>
        <span className="text-xs text-zinc-600">{relativeTime(deployment.createdAt)}</span>
      </div>

      {deployment.serviceUrl && (
        <a
          href={deployment.serviceUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-1.5 text-xs text-accent-cyan hover:text-accent-cyan/80 mb-3 truncate"
        >
          <Globe className="h-3 w-3 flex-shrink-0" />
          {deployment.serviceUrl}
        </a>
      )}

      {isActive && (
        <div className="flex items-center gap-2 mb-3">
          <Loader2 className="h-3 w-3 text-accent-amber animate-spin" />
          <span className="text-[10px] text-zinc-500">
            {deployment.status === 'BUILDING' && 'Building container image...'}
            {deployment.status === 'PUSHING' && 'Pushing to registry...'}
            {deployment.status === 'DEPLOYING' && 'Deploying to Cloud Run...'}
            {deployment.status === 'PENDING' && 'Preparing workspace...'}
          </span>
        </div>
      )}

      <div className="flex items-center gap-2">
        {deployment.serviceUrl && (
          <a href={deployment.serviceUrl} target="_blank" rel="noopener noreferrer"
            className="flex items-center gap-1 text-xs text-zinc-500 hover:text-zinc-300">
            <ExternalLink className="h-3.5 w-3.5" /> Open
          </a>
        )}
        {deployment.status === 'LIVE' && (
          <button onClick={handleRollback} disabled={loading === 'rollback'}
            className="flex items-center gap-1 text-xs text-zinc-500 hover:text-accent-amber disabled:opacity-40">
            {loading === 'rollback' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5" />}
            Rollback
          </button>
        )}
        {!isActive && (
          <button onClick={handleDelete} disabled={loading === 'delete'}
            className="flex items-center gap-1 text-xs text-zinc-500 hover:text-accent-rose ml-auto disabled:opacity-40">
            {loading === 'delete' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Trash2 className="h-3.5 w-3.5" />}
            {deployment.status === 'LIVE' ? 'Destroy' : 'Delete'}
          </button>
        )}
      </div>
    </GlassPanel>
  );
}
