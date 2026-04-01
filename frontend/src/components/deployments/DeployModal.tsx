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
        className="w-full max-w-md rounded-sm bg-[var(--gray-200)] border border-[var(--border-default)]  p-6 space-y-4 overflow-y-auto"
        style={{ maxHeight: '95dvh' }}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-primary flex items-center gap-2">
            <Rocket className="h-4 w-4 text-accent-blue" /> Deploy to Cloud Run
          </h2>
          <button onClick={onClose} className="text-muted hover:text-secondary p-2 -m-1"><X className="h-4 w-4" /></button>
        </div>

        <div className="space-y-3">
          <div>
            <label className="block text-sm text-secondary mb-1.5">Run ID</label>
            <input
              value={runId}
              onChange={(e) => setRunId(e.target.value)}
              placeholder="e.g. abc12345-..."
              className="w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-primary focus:outline-none focus:border-accent-blue/40"
            />
          </div>
          <div>
            <label className="block text-sm text-secondary mb-1.5">Strategy</label>
            <select
              value={strategy}
              onChange={(e) => setStrategy(e.target.value)}
              className="w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-secondary focus:outline-none"
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
            'w-full py-2.5 rounded-sm font-medium text-sm text-primary transition-all',
            'bg-accent-blue hover:bg-accent-blue/80 disabled:opacity-40 disabled:cursor-not-allowed',
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
