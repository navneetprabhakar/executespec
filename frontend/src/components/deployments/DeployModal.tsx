import { useState } from 'react';
import { motion } from 'framer-motion';
import { X, Rocket } from 'lucide-react';
import { createDeployment } from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';
import { cn } from '@/lib/utils';

interface DeployModalProps {
  onClose: () => void;
  onRefresh: () => void;
}

export default function DeployModal({ onClose, onRefresh }: DeployModalProps) {
  const [runId, setRunId] = useState('');
  const [strategy, setStrategy] = useState('SPRING_BOOT');
  const [loading, setLoading] = useState(false);
  const { addToast } = useUiStore();

  const handleDeploy = async () => {
    if (!runId.trim()) return;
    setLoading(true);
    try {
      await createDeployment({ runId, strategy });
      addToast({ type: 'success', message: 'Deployment started' });
      onRefresh();
      onClose();
    } catch (e: any) {
      addToast({ type: 'error', message: e.message });
      setLoading(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-2 md:p-4"
      style={{ backdropFilter: 'blur(4px)', background: 'rgba(0,0,0,0.6)' }}
      onClick={onClose}
    >
      <motion.div
        initial={{ opacity: 0, scale: 0.96 }}
        animate={{ opacity: 1, scale: 1 }}
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md rounded-sm bg-surface-2 border border-white/[0.08]  p-6 space-y-4 overflow-y-auto"
        style={{ maxHeight: '95dvh' }}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-white flex items-center gap-2">
            <Rocket className="h-4 w-4 text-accent-violet" /> Deploy to Cloud Run
          </h2>
          <button onClick={onClose} className="text-zinc-500 hover:text-zinc-300 p-2 -m-1"><X className="h-4 w-4" /></button>
        </div>

        <div className="space-y-3">
          <div>
            <label className="block text-sm text-zinc-400 mb-1.5">Run ID</label>
            <input
              value={runId}
              onChange={(e) => setRunId(e.target.value)}
              placeholder="e.g. abc12345-..."
              className="w-full bg-surface-1 border border-white/[0.08] rounded-sm px-3 py-2 text-sm text-zinc-200 focus:outline-none focus:border-accent-violet/40"
            />
          </div>
          <div>
            <label className="block text-sm text-zinc-400 mb-1.5">Strategy</label>
            <select
              value={strategy}
              onChange={(e) => setStrategy(e.target.value)}
              className="w-full bg-surface-1 border border-white/[0.08] rounded-sm px-3 py-2 text-sm text-zinc-300 focus:outline-none"
            >
              <option value="SPRING_BOOT">Spring Boot</option>
              <option value="STATIC">Static Site</option>
            </select>
          </div>
        </div>

        <button
          onClick={handleDeploy}
          disabled={!runId.trim() || loading}
          className={cn(
            'w-full py-2.5 rounded-sm font-medium text-sm text-white transition-all',
            'bg-accent-violet hover:bg-accent-violet/80 disabled:opacity-40 disabled:cursor-not-allowed',
            'flex items-center justify-center gap-2'
          )}
        >
          {loading ? <span className="h-4 w-4 rounded-full border-2 border-white/30 border-t-white animate-spin" /> : <Rocket className="h-4 w-4" />}
          {loading ? 'Deploying...' : 'Deploy'}
        </button>
      </motion.div>
    </div>
  );
}
